"""대본을 파트·키워드·힌트로 나누는 분석기.

- MockAnalyzer: 네트워크·API 키 없이 동작하는 규칙 기반 분석 (개발·시연 대비용)
- OpenAICompatibleAnalyzer: OpenAI 호환 Chat Completions API 호출

두 분석기 모두 `list[ScriptPart]`를 돌려주며, 실패는 AnalyzerError로 알린다.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from typing import Any, Protocol

import httpx

from .config import Settings
from .schemas import ScriptPart

TITLE_MAX = 30
HINT_MAX = 30
KEYWORDS_MAX = 5


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


def normalize_parts(raw_parts: Any, max_parts: int) -> list[ScriptPart]:
    """LLM 등 외부 결과를 검증해 ScriptPart 목록으로 만든다. 형식이 틀리면 AnalyzerError."""
    if not isinstance(raw_parts, list) or not raw_parts:
        raise AnalyzerError("analysis_invalid_format", "분석 결과에 파트 목록이 없습니다.")
    if len(raw_parts) > max_parts:
        raise AnalyzerError(
            "analysis_invalid_format", f"파트 수가 최대 {max_parts}개를 넘었습니다."
        )

    parts: list[ScriptPart] = []
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

        cleaned: list[str] = []
        for kw in keywords:
            if isinstance(kw, str):
                kw = _clip(kw, 20)
                if kw and kw not in cleaned:
                    cleaned.append(kw)
        if not cleaned:
            raise AnalyzerError("analysis_invalid_format", f"{i}번 파트에 키워드가 없습니다.")

        parts.append(
            ScriptPart(
                index=i,
                title=_clip(title, TITLE_MAX),
                keywords=cleaned[:KEYWORDS_MAX],
                hint=_clip(hint, HINT_MAX),
            )
        )
    return parts


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
                kws = [_clip(source, 10)]
            raw_parts.append(
                {
                    "title": title or _first_sentence(source),
                    "keywords": kws,
                    "hint": _first_phrase(source) or source,
                }
            )
        return normalize_parts(raw_parts, self.max_parts)


# ---------------------------------------------------------------------------
# OpenAI 호환 LLM 분석기
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """너는 발표 대본을 분석해 발표자가 안경 화면으로 볼 회상 단서를 만드는 도우미다.
대본을 새로 쓰거나 내용을 덧붙이지 말고, 주어진 대본만 근거로 답한다.

규칙:
1. 대본을 흐름에 따라 순서대로 {max_parts}개 이하의 파트로 나눈다. 대본의 모든 내용이 어느 한 파트에 속해야 한다.
2. 각 파트마다
   - title: 파트 내용을 요약한 제목 (한국어 20자 이내)
   - keywords: 그 파트에서 반드시 말해야 하는 핵심 키워드 1~5개. 대본에 실제로 나온 표현을 쓴다.
   - hint: 그 파트를 시작할 때 말하는 첫 구절 (대본 그대로, 25자 이내)
3. 다음 JSON 객체 하나만 출력한다. 다른 글은 쓰지 않는다.
{{"parts": [{{"title": "...", "keywords": ["..."], "hint": "..."}}]}}"""


class OpenAICompatibleAnalyzer:
    name = "openai"

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        self.transport = transport

    async def analyze(self, script: str) -> list[ScriptPart]:
        s = self.settings
        if not s.openai_api_key:
            raise AnalyzerError(
                "analyzer_not_configured", "서버에 AI API 키가 설정되지 않았습니다.", status_code=503
            )

        payload = {
            "model": s.openai_model,
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT.format(max_parts=s.max_parts)},
                {"role": "user", "content": f"발표 대본:\n\"\"\"\n{script}\n\"\"\""},
            ],
        }
        headers = {"Authorization": f"Bearer {s.openai_api_key}"}

        try:
            async with httpx.AsyncClient(
                timeout=s.openai_timeout_seconds, transport=self.transport
            ) as client:
                resp = await client.post(
                    f"{s.openai_base_url}/chat/completions", json=payload, headers=headers
                )
        except httpx.TimeoutException as e:
            raise AnalyzerError("llm_timeout", "AI 응답 시간이 초과되었습니다.", 504) from e
        except httpx.HTTPError as e:
            raise AnalyzerError("llm_unreachable", "AI 서버에 연결하지 못했습니다.") from e

        if resp.status_code != 200:
            raise AnalyzerError("llm_failed", f"AI 요청이 실패했습니다 (HTTP {resp.status_code}).")

        try:
            content = resp.json()["choices"][0]["message"]["content"]
            data = json.loads(content)
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise AnalyzerError("analysis_invalid_format", "AI 응답을 해석하지 못했습니다.") from e

        if not isinstance(data, dict):
            raise AnalyzerError("analysis_invalid_format", "AI 응답이 JSON 객체가 아닙니다.")
        return normalize_parts(data.get("parts"), s.max_parts)


def build_analyzer(settings: Settings) -> Analyzer:
    if settings.analyzer_provider == "openai":
        return OpenAICompatibleAnalyzer(settings)
    if settings.analyzer_provider == "mock":
        return MockAnalyzer(settings.max_parts)
    raise ValueError(f"알 수 없는 ANALYZER_PROVIDER: {settings.analyzer_provider}")
