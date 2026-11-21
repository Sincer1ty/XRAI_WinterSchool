"""인식 문장으로 현재 파트를 추정하고, 멈춤 때 띄울 힌트를 고른다.

LLM을 호출하지 않는다. 준비 단계에서 만든 파트 키워드만 쓴다 (발표 중 지연 최소화).
"""

from __future__ import annotations

import re

from .schemas import MatchResponse, ScriptPart

_SPACE_RE = re.compile(r"\s+")


def _norm(text: str) -> str:
    # STT 띄어쓰기가 대본과 달라도 맞도록 공백을 지우고 비교한다.
    return _SPACE_RE.sub("", text).lower()


def _hits(parts: list[ScriptPart], sentence: str) -> list[list[str]]:
    spoken = _norm(sentence)
    return [[k for k in p.keywords if _norm(k) and _norm(k) in spoken] for p in parts]


def match_part(script_sha256: str, parts: list[ScriptPart], text: str) -> MatchResponse:
    """현재 파트를 고른다. 근거가 없거나 동점이면 확정하지 않는다.

    text는 최근 문장을 줄바꿈으로 구분한 것이다 (오래된 것 → 최신).
    파트를 넘어간 직후 이전 문장이 섞여도 흔들리지 않도록 최신 문장 우선으로 판단한다:
    1. 최신 문장부터 거꾸로 보며 키워드가 처음 나온 문장에서 가장 많이 맞은 파트를 고른다.
       그 문장 안에서 동점이면 최근 문장 전체 키워드 수로 가르고, 그래도 같으면 ambiguous.
    2. 말한 키워드는 그 문장부터 거꾸로, 다른 파트 키워드만 나온 문장을 만나기 전까지 모은다.
    """
    # ponytail: 키워드 부분 문자열 개수 비교. 의역·동의어까지 잡아야 하면 임베딩 유사도로 바꾼다.
    sentences = [line for line in text.split("\n") if line.strip()]
    per_sentence = [_hits(parts, line) for line in sentences]
    base = {"script_sha256": script_sha256, "stt_text": text}

    anchor = next((j for j in reversed(range(len(sentences))) if any(per_sentence[j])), None)
    if anchor is None:
        return MatchResponse(**base, status="unknown")
    best = max(len(h) for h in per_sentence[anchor])
    top = [i for i, h in enumerate(per_sentence[anchor]) if len(h) == best]
    if len(top) > 1:
        totals = {i: len({k for h in per_sentence for k in h[i]}) for i in top}
        most = max(totals.values())
        top = [i for i in top if totals[i] == most]
    if len(top) > 1:
        shared = sorted({k for i in top for k in per_sentence[anchor][i]})
        return MatchResponse(**base, status="ambiguous", matched_keywords=shared)

    i = top[0]
    said: list[str] = []
    for j in reversed(range(anchor + 1)):
        h = per_sentence[j]
        if any(h) and not h[i]:
            break  # 이전 파트를 말하던 문장
        said += [k for k in h[i] if k not in said]
    said = [k for k in parts[i].keywords if k in said]
    return MatchResponse(**base, status="matched", part_index=parts[i].index, matched_keywords=said)


def hint_for_pause(script_sha256: str, parts: list[ScriptPart], text: str) -> MatchResponse:
    """멈춤 직전 문장으로 현재 파트를 추정해 힌트를 고른다.

    기본 정책(팀 확정 필요): 현재 파트에서 아직 안 나온 키워드가 있으면 그 키워드,
    모두 나왔으면 다음 파트의 첫 구절. 마지막 파트를 다 말했거나 파트 미확인이면 힌트 없음.
    """
    result = match_part(script_sha256, parts, text)
    if result.status != "matched":
        return result
    pos = next(i for i, p in enumerate(parts) if p.index == result.part_index)
    remaining = [k for k in parts[pos].keywords if k not in result.matched_keywords]
    if remaining:
        return result.model_copy(
            update={"hint": " · ".join(remaining), "hint_kind": "remaining_keywords",
                    "hint_part_index": parts[pos].index, "hint_keywords": remaining}
        )
    if pos + 1 < len(parts):
        nxt = parts[pos + 1]
        return result.model_copy(
            update={"hint": nxt.hint, "hint_kind": "next_part", "hint_part_index": nxt.index,
                    "hint_keywords": list(nxt.keywords)}
        )
    return result
