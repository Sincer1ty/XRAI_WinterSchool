"""대본을 파트·키워드·힌트로 나누는 분석기.

- MockAnalyzer: 네트워크·API 키 없이 동작하는 규칙 기반 분석 (개발·시연 대비용)
- OpenAICompatibleAnalyzer: OpenAI 호환 Chat Completions API 호출

두 분석기 모두 `list[ScriptPart]`를 돌려주며, 실패는 AnalyzerError로 알린다.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from collections import Counter
from contextvars import ContextVar
from typing import Any, Protocol

import httpx

from .config import Settings
from .hint_engine import _norm
from .schemas import ScriptPart

logger = logging.getLogger("xrai.backend")

TITLE_MAX = 30
HINT_MAX = 30
KEYWORDS_MAX = 5

# 이번 요청에서 LLM API 응답을 기다린 시간(ms). 서버 처리 시간과 분리해 기록한다.
# 분석기를 직접 await하는 엔드포인트에서 읽는다 (같은 컨텍스트).
llm_wait_ms: ContextVar[float | None] = ContextVar("llm_wait_ms", default=None)
# 안경에 한눈에 보이는 회상 단서 길이. 잘라 쓰면 원문 매칭이 깨지므로 넘으면 버린다.
KEYWORD_LEN_MAX = 12


class AnalyzerError(Exception):
    """분석 실패. code는 API 오류 응답에 그대로 쓰인다."""

    def __init__(self, code: str, message: str, status_code: int = 502):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class Analyzer(Protocol):
    name: str

    async def analyze(self, script: str) -> list[ScriptPart]: ...


# ---------------------------------------------------------------------------
# 공통 정규화·검증
# ---------------------------------------------------------------------------

def _clip(text: str, limit: int) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[:limit].rstrip() + "…"


def normalize_parts(
    raw_parts: Any,
    max_parts: int,
    script: str | None = None,
    sections: list[tuple[str | None, str]] | None = None,
) -> list[ScriptPart]:
    """LLM 등 외부 결과를 검증해 ScriptPart 목록으로 만든다. 형식이 틀리면 AnalyzerError.

    script를 주면 대본에 실제로 나온 키워드만 남긴다. 발표 중 매칭이 원문 표현 기준이기 때문이다.
    sections(제목 기준 파트)를 주면 파트 수가 같아야 하고, 키워드는 그 파트 본문에 나온 것만 남기며
    제목은 대본의 제목을 그대로 쓴다.
    """
    source = _norm(script) if script is not None else None
    if not isinstance(raw_parts, list) or not raw_parts:
        raise AnalyzerError("analysis_invalid_format", "분석 결과에 파트 목록이 없습니다.")
    if len(raw_parts) > max_parts:
        raise AnalyzerError(
            "analysis_invalid_format", f"파트 수가 최대 {max_parts}개를 넘었습니다."
        )
    if sections is not None and len(raw_parts) != len(sections):
        raise AnalyzerError(
            "analysis_invalid_format",
            f"대본 제목은 {len(sections)}개인데 AI가 {len(raw_parts)}개 파트로 나눴습니다. 다시 분석하세요.",
        )

    checked: list[tuple[str, str, list[str]]] = []
    for i, raw in enumerate(raw_parts, start=1):
        if not isinstance(raw, dict):
            raise AnalyzerError("analysis_invalid_format", f"{i}번 파트 형식이 올바르지 않습니다.")
        title = raw.get("title")
        hint = raw.get("hint")
        keywords = raw.get("keywords")
        if not isinstance(title, str) or not title.strip():
            raise AnalyzerError("analysis_invalid_format", f"{i}번 파트에 제목이 없습니다.")
        if not isinstance(hint, str) or not hint.strip():
            raise AnalyzerError("analysis_invalid_format", f"{i}번 파트에 힌트가 없습니다.")
        if not isinstance(keywords, list):
            raise AnalyzerError("analysis_invalid_format", f"{i}번 파트의 키워드 형식이 올바르지 않습니다.")

        part_source = source
        if sections is not None:
            heading, body = sections[i - 1]
            # 제목은 소리 내어 읽지 않으므로 키워드는 본문에서만 찾는다.
            part_source = _norm(body)
            title = heading or title
        cleaned: list[str] = []
        for kw in keywords:
            if not isinstance(kw, str):
                continue
            kw = re.sub(r"\s+", " ", kw).strip()
            if not kw or len(kw) > KEYWORD_LEN_MAX or kw in cleaned:
                continue
            if part_source is not None and _norm(kw) not in part_source:
                continue
            cleaned.append(kw)
        if not cleaned:
            raise AnalyzerError(
                "analysis_invalid_format", f"{i}번 파트에 대본에 나온 짧은 키워드가 없습니다."
            )

        checked.append((title, hint, cleaned))

    checked = _drop_shared_keywords(checked)
    if sections is not None:
        checked = _drop_keywords_in_other_sections(checked, sections)
    return [
        ScriptPart(index=i, title=_clip(title, TITLE_MAX), keywords=kws[:KEYWORDS_MAX], hint=_clip(hint, HINT_MAX))
        for i, (title, hint, kws) in enumerate(checked, start=1)
    ]


def _drop_keywords_in_other_sections(
    parts: list[tuple[str, str, list[str]]],
    sections: list[tuple[str | None, str]],
) -> list[tuple[str, str, list[str]]]:
    """다른 파트 본문에도 나오는 키워드를 뺀다. 그 문장을 말할 때 다른 파트로 잘못 판단하기 때문이다.

    빼고 나면 키워드가 없는 파트는 원래 키워드를 그대로 둔다.
    """
    texts = [_norm(body) for _, body in sections]
    result = []
    for i, (title, hint, kws) in enumerate(parts):
        unique = [kw for kw in kws if not any(_norm(kw) in t for j, t in enumerate(texts) if j != i)]
        result.append((title, hint, unique or kws))
    return result


def _drop_shared_keywords(
    parts: list[tuple[str, str, list[str]]],
) -> list[tuple[str, str, list[str]]]:
    """여러 파트에 걸치는 키워드를 뺀다. 그런 키워드가 들리면 발표 중 파트를 가르지 못하기 때문이다.

    한쪽이 다른 쪽을 포함해도 겹친 것으로 본다 ("발표"는 "수업 발표"를 말할 때도 들린다).
    빼고 나면 키워드가 없는 파트는 원래 키워드를 그대로 둔다.
    """
    norms = [[_norm(k) for k in kws] for _, _, kws in parts]

    def shared(i: int, k: str) -> bool:
        return any(k in o or o in k for j, others in enumerate(norms) if j != i for o in others)

    result = []
    for i, (title, hint, kws) in enumerate(parts):
        unique = [kw for kw, k in zip(kws, norms[i]) if not shared(i, k)]
        result.append((title, hint, unique or kws))
    return result


# ---------------------------------------------------------------------------
# 규칙 기반 분석기
# ---------------------------------------------------------------------------

_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_PARTICLES = (
    "으로써", "으로서", "에서는", "에게서", "이라고", "라고는", "입니다", "합니다", "했습니다",
    "으로", "에서", "에게", "까지", "부터", "처럼", "보다", "이라", "라고", "하고", "이며",
    "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "만", "요",
)
_VERB_ENDINGS = ("니다", "세요", "어요", "아요", "해요", "었다", "았다", "한다", "된다", "겠다")
_STOPWORDS = {
    "그리고", "그러나", "하지만", "그래서", "또한", "이번", "이것", "저것", "그것", "여기",
    "저희", "우리", "제가", "여러분", "오늘", "다음", "먼저", "마지막", "정말", "아주", "매우",
    "있습니다", "없습니다", "됩니다", "하는", "있는", "없는", "되는", "같은", "통해", "대한",
    "위해", "때문", "the", "and", "for", "with", "this", "that", "are", "is",
}


def _strip_particle(token: str) -> str:
    for p in _PARTICLES:
        if len(token) > len(p) + 1 and token.endswith(p):
            return token[: -len(p)]
    return token


def _split_sections(script: str) -> list[tuple[str | None, str]]:
    """(제목 또는 None, 본문) 목록. 마크다운 제목이 있으면 제목 기준, 없으면 빈 줄 기준."""
    lines = script.splitlines()
    if any(_HEADING_RE.match(line) for line in lines):
        sections: list[tuple[str | None, list[str]]] = []
        for line in lines:
            m = _HEADING_RE.match(line)
            if m:
                sections.append((m.group(1).strip(), []))
            elif sections:
                sections[-1][1].append(line)
            elif line.strip():
                sections.append((None, [line]))
        result = [(t, "\n".join(b).strip()) for t, b in sections]
        return [(t, b) for t, b in result if b or t]

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", script) if p.strip()]
    return [(None, p) for p in paragraphs]


def _merge_to_limit(sections: list[tuple[str | None, str]], limit: int) -> list[tuple[str | None, str]]:
    while len(sections) > limit:
        # 가장 짧은 인접 쌍을 합친다.
        best = min(range(len(sections) - 1), key=lambda i: len(sections[i][1]) + len(sections[i + 1][1]))
        (t1, b1), (t2, b2) = sections[best], sections[best + 1]
        sections[best : best + 2] = [(t1 or t2, f"{b1}\n\n{b2}".strip())]
    return sections


def heading_sections(script: str, max_parts: int) -> list[tuple[str | None, str]] | None:
    """대본에 마크다운 제목이 있으면 제목 기준 파트 목록. 없거나 최대 파트 수를 넘으면 None (AI가 나눈다)."""
    if not any(_HEADING_RE.match(line) for line in script.splitlines()):
        return None
    sections = _split_sections(script)
    return sections if 0 < len(sections) <= max_parts else None


def _first_sentence(text: str) -> str:
    m = re.split(r"(?<=[.!?。])\s+|\n", text.strip(), maxsplit=1)
    return m[0] if m else text


def _first_phrase(text: str) -> str:
    sentence = _first_sentence(text)
    phrase = re.split(r"[,，;:]", sentence, maxsplit=1)[0]
    return phrase.strip(" .!?。")


def _keywords(text: str, n: int = 3) -> list[str]:
    counts: Counter[str] = Counter()
    first_seen: dict[str, int] = {}
    for i, raw in enumerate(_TOKEN_RE.findall(text)):
        if raw.endswith(_VERB_ENDINGS):
            continue
        tok = _strip_particle(raw)
        if len(tok) < 2 or tok.lower() in _STOPWORDS or tok.isdigit():
            continue
        counts[tok] += 1
        first_seen.setdefault(tok, i)
    ranked = sorted(counts, key=lambda t: (-counts[t], -len(t), first_seen[t]))
    return ranked[:n]


class MockAnalyzer:
    name = "mock"

    def __init__(self, max_parts: int):
        self.max_parts = max_parts

    async def analyze(self, script: str) -> list[ScriptPart]:
        sections = _merge_to_limit(_split_sections(script), self.max_parts)
        raw_parts = []
        for title, body in sections:
            source = body or title or ""
            kws = _keywords(f"{title or ''}\n{body}")
            if not kws:
                kws = [source.strip()[:10].strip()]
            raw_parts.append(
                {
                    "title": title or _first_sentence(source),
                    "keywords": kws,
                    "hint": _first_phrase(source) or source,
                }
            )
        return normalize_parts(raw_parts, self.max_parts, script)


# ---------------------------------------------------------------------------
# OpenAI 호환 LLM 분석기
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """너는 발표 대본을 분석해 발표자가 안경 화면으로 볼 회상 단서를 만드는 도우미다.
대본을 새로 쓰거나 내용을 덧붙이지 말고, 주어진 대본만 근거로 답한다.

규칙:
1. 대본을 흐름에 따라 순서대로 {max_parts}개 이하의 파트로 나눈다. 대본의 모든 내용이 어느 한 파트에 속해야 한다.
   대본에 "#"로 시작하는 제목이 있으면 제목 하나를 파트 하나로 삼고, 제목 안을 더 쪼개지 않는다.
2. 각 파트마다
   - title: 파트 내용을 요약한 제목 (한국어 20자 이내)
   - keywords: 그 파트에서 반드시 말해야 하는 핵심 회상 단서 3~5개 (파트가 아주 짧으면 1~2개).
     · 파트의 앞·중간·뒤에서 고르게 고른다. 발표 중 어느 문장을 말하는지 따라가는 데 쓰인다.
     · 대본에 글자 그대로 나온 짧은 명사·명사구만 쓴다 (2~8자, 띄어쓰기 포함 최대 {kw_len}자).
     · 문장·서술어("~합니다", "~잊습니다")는 쓰지 말고 핵심 명사만 남긴다.
       예: 대본 "긴장하면 대본을 잊습니다" → "긴장", "대본" (O) / "긴장하면 대본을 잊습니다" (X)
     · 대본에 없는 요약어·동의어를 새로 만들지 않는다 (예: "대본 망각" X).
     · 다른 파트에도 나오는 단어("발표", "발표자"처럼 대본 전체에 반복되는 말)는 쓰지 않고,
       그 파트에서만 나오는 표현을 고른다. 발표 중 이 키워드로 현재 파트를 구분한다.
   - hint: 그 파트를 시작할 때 말하는 첫 구절 (대본 그대로, 25자 이내)
3. 다음 JSON 객체 하나만 출력한다. 다른 글은 쓰지 않는다.
{{"parts": [{{"title": "...", "keywords": ["..."], "hint": "..."}}]}}"""


class OpenAICompatibleAnalyzer:
    name = "openai"
    # 일시적 실패 뒤 다시 시도하기 전 대기. 테스트에서는 0으로 둔다 (tests/conftest.py).
    retry_delay_seconds = 1.0

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.name = settings.analyzer_provider
        self.settings = settings
        self.transport = transport

    async def analyze(self, script: str) -> list[ScriptPart]:
        s = self.settings
        if not s.openai_api_key or not s.openai_model:
            raise AnalyzerError(
                "analyzer_not_configured", "서버에 AI API 키 또는 모델이 설정되지 않았습니다.", status_code=503
            )

        # 제목이 있으면 파트는 서버가 정한다. AI 응답이 매번 달라 제목 지시만으로는 보장되지 않기 때문이다.
        sections = heading_sections(script, s.max_parts)
        user = f"발표 대본:\n\"\"\"\n{script}\n\"\"\""
        if sections is not None:
            listing = "\n".join(f"{n}. {t or '(제목 없음)'}" for n, (t, _) in enumerate(sections, start=1))
            user += (
                f"\n\n이 대본은 제목으로 파트 {len(sections)}개가 정해져 있다. 아래 순서대로 정확히 "
                f"{len(sections)}개 파트를 만들고, 각 파트의 keywords와 hint는 그 제목 아래 본문에서만 고른다. 제목 글자는 발표 중 말하지 않으므로 키워드로 쓰지 않는다.\n{listing}"
            )
        min_items, max_items = (len(sections), len(sections)) if sections is not None else (1, s.max_parts)
        payload = {
            "model": s.openai_model,
            "stream": False,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT.format(max_parts=s.max_parts, kw_len=KEYWORD_LEN_MAX)},
                {"role": "user", "content": user},
            ],
        }
        if s.analyzer_provider == "gemini":
            payload["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "script_analysis",
                    "strict": True,
                    "schema": {
                        "type": "object",
                        "properties": {
                            "parts": {
                                "type": "array",
                                "minItems": min_items,
                                "maxItems": max_items,
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "title": {"type": "string"},
                                        "keywords": {
                                            "type": "array",
                                            "items": {"type": "string", "maxLength": KEYWORD_LEN_MAX},
                                            "minItems": 1,
                                            "maxItems": KEYWORDS_MAX,
                                        },
                                        "hint": {"type": "string"},
                                    },
                                    "required": ["title", "keywords", "hint"],
                                    "additionalProperties": False,
                                },
                            },
                        },
                        "required": ["parts"],
                        "additionalProperties": False,
                    },
                },
            }
        elif s.json_response_format:
            payload["response_format"] = {"type": "json_object"}
        # Use the model default for Gemini and gateway reasoning models.
        if s.analyzer_provider == "openai":
            payload["temperature"] = 0.2
        headers = {"Authorization": f"Bearer {s.openai_api_key}"}

        # 제공자 과부하(503 등)는 잠깐 뒤 성공하는 일이 잦다. 같은 모델로 한 번 더, 그래도 안 되면 예비 모델.
        # 폰이 분석 응답을 기다리는 시간 안에 끝나도록 모든 시도를 합쳐 openai_timeout_seconds 안에서 한다.
        models = [s.openai_model, s.openai_model]
        if s.fallback_model and s.fallback_model != s.openai_model:
            models.append(s.fallback_model)
        started = time.perf_counter()
        deadline = started + s.openai_timeout_seconds
        resp: httpx.Response | None = None
        error: AnalyzerError | None = None
        try:
            for attempt, model in enumerate(models):
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    break
                if attempt:
                    await asyncio.sleep(min(self.retry_delay_seconds, remaining))
                    remaining = deadline - time.perf_counter()
                    if remaining <= 0:
                        break
                    logger.warning("retrying analysis with %s after %s", model, error.code if error else "-")
                try:
                    resp = await self._post({**payload, "model": model}, headers, remaining)
                except AnalyzerError as e:
                    error, resp = e, None
                    continue
                if resp.status_code == 200:
                    break
                error = _http_failure(resp.status_code)
                if resp.status_code not in RETRYABLE_STATUS:
                    raise error
        finally:
            llm_wait_ms.set((time.perf_counter() - started) * 1000)
        if resp is None or resp.status_code != 200:
            raise error or AnalyzerError("llm_timeout", "AI 응답 시간이 초과되었습니다.", 504)

        try:
            content = resp.json()["choices"][0]["message"]["content"]
            data = json.loads(content)
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise AnalyzerError("analysis_invalid_format", "AI 응답을 해석하지 못했습니다.") from e

        if not isinstance(data, dict):
            raise AnalyzerError("analysis_invalid_format", "AI 응답이 JSON 객체가 아닙니다.")
        return normalize_parts(data.get("parts"), s.max_parts, script, sections)

    async def _post(self, payload: dict, headers: dict, timeout: float) -> httpx.Response:
        try:
            async with httpx.AsyncClient(timeout=timeout, transport=self.transport) as client:
                return await client.post(
                    f"{self.settings.openai_base_url}/chat/completions", json=payload, headers=headers
                )
        except httpx.TimeoutException as e:
            raise AnalyzerError("llm_timeout", "AI 응답 시간이 초과되었습니다.", 504) from e
        except httpx.HTTPError as e:
            raise AnalyzerError("llm_unreachable", "AI 서버에 연결하지 못했습니다.") from e


# 잠시 뒤 다시 시도하면 성공할 수 있는 제공자 응답.
RETRYABLE_STATUS = {429, 500, 502, 503, 504}


def _http_failure(status: int) -> AnalyzerError:
    explanations = {
        400: "모델의 요청 옵션·JSON 모드 지원을 확인하세요.",
        401: "API 키를 확인하세요.",
        403: "API 키의 모델 접근 권한을 확인하세요.",
        404: "API 주소와 모델 ID 또는 콤보 이름을 확인하세요.",
        429: "사용 한도 또는 제공자 용량을 확인하고 잠시 후 다시 시도하세요.",
        503: "AI 제공자가 일시적으로 혼잡합니다. 잠시 후 다시 분석하세요.",
    }
    detail = explanations.get(status, "게이트웨이와 모델 제공자 상태를 확인하세요.")
    # Do not expose upstream bodies, credentials or script text.
    return AnalyzerError("llm_failed", f"AI 요청이 실패했습니다 (HTTP {status}). {detail}")


def build_analyzer(settings: Settings) -> Analyzer:
    if settings.analyzer_provider in {"openai", "omniroute", "gemini"}:
        return OpenAICompatibleAnalyzer(settings)
    if settings.analyzer_provider == "mock":
        return MockAnalyzer(settings.max_parts)
    raise ValueError(f"알 수 없는 ANALYZER_PROVIDER: {settings.analyzer_provider}")
