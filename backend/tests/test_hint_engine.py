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
