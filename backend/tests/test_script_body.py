"""원문 보존 계약 테스트 소스. 실제 LLM·KeyBERT 모델을 호출하지 않는다."""
import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, MockAnalyzer, normalize_parts


def part(keyword, quote=None, **extra):
    raw = {"title": keyword, "hint": keyword, "keywords": [keyword], **extra}
    if quote is not None:
        raw["start_quote"] = quote
    return raw


def test_single_part_uses_full_source_without_length_clipping():
    body = "발표 대본을 준비합니다.\n" + "마지막 문장까지 그대로 보존합니다. " * 20
    title = "긴 제목도 임의로 잘라내지 않고 보존합니다. " * 4
    hint = "긴 첫 구절에도 생략 기호를 붙이지 않고 내용을 보존합니다. " * 3
    result = normalize_parts([part("대본", title=title, hint=hint, body="AI가 바꾼 본문")], 10, body)[0]
    assert result.body == body
    assert result.title == title.strip()
    assert result.hint == hint.strip()


def test_fixed_sections_use_server_source_not_model_body():
    body = "대본의 첫 문장입니다.\n중간 내용입니다.\n마지막 내용입니다."
    result = normalize_parts(
        [part("대본", body="잘못된 요약", start_quote="없는 구절")],
        10, body, [("제목", body)],
    )[0]
    assert result.title == "제목"
    assert result.body == body


def test_unheaded_parts_cover_source_in_order_and_preserve_line_breaks():
    first = "발표 대본을 준비합니다.\n빠뜨리지 않을 중간 문장입니다."
    second = "스마트 안경으로 단서를 보여줍니다.\n마지막 문장입니다."
    script = first + "\n\n" + second
    parts = normalize_parts([
        part("대본", "발표 대본을 준비합니다."),
        part("안경", "스마트 안경으로 단서를 보여줍니다."),
    ], 10, script)
    assert [p.body for p in parts] == [first, second]
    assert "\n\n".join(p.body for p in parts) == script


@pytest.mark.parametrize("quotes", [
    [None, "스마트 안경"],
    ["대본", "스마트 안경"],  # 첫 부분 누락
    ["없는 구절", "스마트 안경"],
    ["스마트 안경", "발표 대본"],  # 순서 뒤바뀜
])
def test_missing_unknown_or_out_of_order_boundaries_are_rejected(quotes):
    script = "발표 대본을 준비합니다. 스마트 안경으로 보여줍니다."
    with pytest.raises(AnalyzerError) as error:
        normalize_parts([part("대본", quotes[0]), part("안경", quotes[1])], 10, script)
    assert error.value.code == "analysis_invalid_format"


def test_repeated_boundary_is_rejected_instead_of_guessing():
    script = "같은 시작 대본. 같은 시작 안경."
    with pytest.raises(AnalyzerError):
        normalize_parts([part("대본", "같은 시작"), part("안경", "같은 시작")], 10, script)


def test_keywords_must_come_from_their_own_source_section():
    script = "발표 대본을 준비합니다.\n\n스마트 안경을 씁니다."
    parts = normalize_parts([
        part("대본", "발표 대본을 준비합니다.", keywords=["대본", "스마트 안경"]),
        part("안경", "스마트 안경을 씁니다."),
    ], 10, script)
    assert parts[0].keywords == ["대본"]


def test_mock_merge_keeps_middle_headings_and_all_body_text():
    script = "# 첫 제목\n발표 대본을 읽습니다.\n\n# 둘째 제목\n안경 단서를 봅니다."
    result = asyncio.run(MockAnalyzer(1).analyze(script))[0]
    assert result.title == "첫 제목"
    assert result.body == "발표 대본을 읽습니다.\n\n# 둘째 제목\n안경 단서를 봅니다."
    assert all("제목" not in word for word in result.keywords)
