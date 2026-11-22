"""힌트를 띄운 뒤의 발화로 핵심 내용이 전달됐는지 판단한다. LLM을 호출하지 않는다."""

from __future__ import annotations

from .hint_engine import _norm
from .schemas import DeliveryResponse


def judge_delivery(script_sha256: str, hint: str, hint_keywords: list[str], text: str) -> DeliveryResponse:
    """표시한 키워드 중 하나 이상을 말하면 전달 (사용자 결정 2026-11-22).

    말을 다시 시작한 것만으로는 전달로 보지 않는다. 키워드가 없는 말은 전달되지 않은 것으로 남긴다.
    """
    # ponytail: 부분 문자열 비교라 의역·부정("~가 아니라")은 구분 못 함. 필요하면 LLM 판정으로 바꾼다.
    spoken = _norm(text)
    matched = [k for k in hint_keywords if _norm(k) and _norm(k) in spoken]
    delivered = bool(matched)
    return DeliveryResponse(
        script_sha256=script_sha256, stt_text=text, delivered=delivered, matched_keywords=matched
    )
