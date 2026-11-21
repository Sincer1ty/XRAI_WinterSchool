import sys
from dataclasses import replace
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import load_settings  # noqa: E402
from app.hint_engine import hint_for_pause, match_part  # noqa: E402
from app.main import create_app  # noqa: E402
from app.schemas import ScriptPart  # noqa: E402

SHA = "test-sha"
PARTS = [
    ScriptPart(index=1, title="문제", keywords=["발표 불안", "대본"], hint="안녕하세요"),
    ScriptPart(index=2, title="해결", keywords=["스마트 안경", "힌트"], hint="그래서 저희는"),
    ScriptPart(index=3, title="효과", keywords=["자신감", "힌트"], hint="결과적으로"),
]


def test_single_part_matched_even_with_different_spacing():
    r = match_part(SHA, PARTS, "저희는 스마트안경을 씁니다")
    assert (r.status, r.part_index, r.matched_keywords) == ("matched", 2, ["스마트 안경"])
    assert r.stt_text == "저희는 스마트안경을 씁니다"
    assert r.hint is None


def test_unrelated_speech_stays_unknown():
    r = match_part(SHA, PARTS, "오늘 날씨가 좋네요")
    assert (r.status, r.part_index) == ("unknown", None)


def test_shared_keyword_tie_is_ambiguous_not_guessed():
    r = match_part(SHA, PARTS, "힌트를 보여줍니다")
    assert (r.status, r.part_index, r.matched_keywords) == ("ambiguous", None, ["힌트"])


def test_pause_shows_unsaid_keywords_of_current_part():
    r = hint_for_pause(SHA, PARTS, "발표 불안 때문에 힘듭니다")
    assert (r.hint, r.hint_kind, r.hint_part_index) == ("대본", "remaining_keywords", 1)


def test_pause_after_all_keywords_shows_next_part_opening():
    r = hint_for_pause(SHA, PARTS, "발표 불안으로 대본을 잊습니다")
    assert (r.hint, r.hint_kind, r.hint_part_index) == ("그래서 저희는", "next_part", 2)


def test_pause_after_last_part_or_unknown_gives_no_hint():
    assert hint_for_pause(SHA, PARTS, "자신감과 힌트").hint is None
    assert hint_for_pause(SHA, PARTS, "음...").hint is None


def test_latest_sentence_wins_right_after_moving_to_next_part():
    # 합쳐서 세면 파트 1(2개)이 이기지만, 최신 문장은 파트 2를 말하고 있다.
    text = "발표 불안이 큽니다\n대본을 잊습니다\n그래서 스마트 안경을 만들었습니다"
    r = hint_for_pause(SHA, PARTS, text)
    assert (r.status, r.part_index, r.matched_keywords) == ("matched", 2, ["스마트 안경"])
    assert (r.hint, r.hint_kind) == ("힌트", "remaining_keywords")


def test_sentence_without_keywords_falls_back_to_previous_sentence():
    r = hint_for_pause(SHA, PARTS, "발표 불안이 큽니다\n음 그러니까")
    assert (r.part_index, r.matched_keywords, r.hint) == (1, ["발표 불안"], "대본")


def test_said_keywords_counted_across_consecutive_sentences_of_same_part():
    r = hint_for_pause(SHA, PARTS, "발표 불안이 큽니다\n그래서 대본을 봅니다")
    assert (r.part_index, r.matched_keywords, r.hint_kind) == (1, ["발표 불안", "대본"], "next_part")


def test_shared_keyword_tie_broken_by_recent_sentences():
    r = match_part(SHA, PARTS, "자신감이 생깁니다\n힌트 덕분입니다")
    assert (r.status, r.part_index, r.matched_keywords) == ("matched", 3, ["자신감", "힌트"])


def _client() -> TestClient:
    return TestClient(create_app(replace(load_settings(), analyzer_provider="mock")))


def test_match_rejects_script_the_server_did_not_prepare():
    for path in ("/api/v1/parts/match", "/api/v1/hints/pause"):
        r = _client().post(path, json={"script_sha256": "old", "text": "안녕하세요"})
        assert r.status_code == 409
        assert r.json()["error"]["code"] == "script_not_prepared"


def test_analyze_then_match_uses_prepared_keywords():
    client = _client()
    analyzed = client.post("/api/v1/scripts/analyze", json={"script": "첫 문단 발표 불안\n\n둘째 문단 스마트 안경"}).json()
    keyword = analyzed["parts"][1]["keywords"][0]
    r = client.post("/api/v1/parts/match", json={"script_sha256": analyzed["script_sha256"], "text": keyword})
    assert r.status_code == 200
    assert r.json()["part_index"] == 2


def test_empty_match_checks_prepared_script_without_selecting_hint():
    client = _client()
    analyzed = client.post("/api/v1/scripts/analyze", json={"script": "발표 불안과 스마트 안경"}).json()
    request = {"script_sha256": analyzed["script_sha256"], "text": ""}
    response = client.post("/api/v1/parts/match", json=request)
    assert response.status_code == 200
    body = response.json()
    assert body["script_sha256"] == request["script_sha256"]
    assert body["stt_text"] == ""
    assert body["status"] == "unknown"
    assert body["part_index"] is None
    assert body["hint"] is None
    # A restarted server cannot claim the old preparation still exists.
    missing = _client().post("/api/v1/parts/match", json=request)
    assert missing.status_code == 409
    assert missing.json()["error"]["code"] == "script_not_prepared"


LONG_PARTS = [
    ScriptPart(index=1, title="문제", keywords=["발표 불안", "대본", "긴장"], hint="안녕하세요"),
    ScriptPart(index=2, title="해결", keywords=["스마트 안경", "힌트"], hint="그래서 저희는"),
    ScriptPart(index=3, title="효과", keywords=["자신감", "힌트"], hint="결과적으로"),
]


def test_keyword_said_early_in_part_is_not_shown_again():
    # 폰이 최근 3문장만 보내던 때는 첫 문장의 '발표 불안'이 빠져 다시 표시됐다.
    lines = ["발표 불안이 큽니다", "정말 그렇습니다", "많이들 겪습니다", "그래서 대본을 봅니다"]
    r = hint_for_pause(SHA, LONG_PARTS, "\n".join(lines))
    assert (r.part_index, r.matched_keywords) == (1, ["발표 불안", "대본"])
    assert r.hint_keywords == ["긴장"]


def test_tie_break_looks_back_only_until_another_part():
    # 오래전 파트 3 문장이 있어도, 바로 앞이 파트 2면 파트 2로 정한다.
    lines = ["자신감이 생깁니다", "자신감 덕분입니다", "스마트 안경을 씁니다", "힌트가 뜹니다"]
    r = match_part(SHA, LONG_PARTS, "\n".join(lines))
    assert (r.status, r.part_index, r.matched_keywords) == ("matched", 2, ["스마트 안경", "힌트"])


def test_tie_without_decisive_sentence_stays_ambiguous():
    r = match_part(SHA, LONG_PARTS, "발표 불안이 큽니다\n힌트가 뜹니다")
    assert (r.status, r.part_index) == ("ambiguous", None)
