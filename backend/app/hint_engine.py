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


def match_part(script_sha256: str, parts: list[ScriptPart], text: str) -> MatchResponse:
    """키워드가 가장 많이 들린 파트를 고른다. 근거가 없거나 동점이면 확정하지 않는다."""
    # ponytail: 키워드 부분 문자열 개수 비교. 의역·동의어까지 잡아야 하면 임베딩 유사도로 바꾼다.
    spoken = _norm(text)
    hits = [[k for k in p.keywords if _norm(k) and _norm(k) in spoken] for p in parts]
    best = max((len(h) for h in hits), default=0)
    base = {"script_sha256": script_sha256, "stt_text": text}
    if best == 0:
        return MatchResponse(**base, status="unknown")
    top = [i for i, h in enumerate(hits) if len(h) == best]
    if len(top) > 1:
        shared = sorted({k for i in top for k in hits[i]})
        return MatchResponse(**base, status="ambiguous", matched_keywords=shared)
    i = top[0]
    return MatchResponse(**base, status="matched", part_index=parts[i].index, matched_keywords=hits[i])


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
