import sys
from dataclasses import replace
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import load_settings  # noqa: E402
from app.delivery_judge import judge_delivery  # noqa: E402
from app.main import create_app  # noqa: E402


def test_keyword_spoken_is_delivered_even_with_different_spacing():
    r = judge_delivery("sha", "스마트 안경 · 힌트", ["스마트 안경", "힌트"], "저희는 스마트안경을 씁니다")
    assert r.delivered is True
    assert r.matched_keywords == ["스마트 안경"]
    assert r.stt_text == "저희는 스마트안경을 씁니다"


def test_hint_phrase_spoken_is_delivered():
    assert judge_delivery("sha", "그래서 저희는", [], "그래서 저희는 시작했습니다").delivered is True


def test_resuming_with_unrelated_words_is_not_delivered():
    r = judge_delivery("sha", "그래서 저희는", ["스마트 안경"], "음 잠시만요")
    assert (r.delivered, r.matched_keywords) == (False, [])


def test_delivery_api_requires_prepared_script_and_judges():
    client = TestClient(create_app(replace(load_settings(), analyzer_provider="mock")))
    body = {"script_sha256": "old", "hint": "x", "hint_keywords": ["발표"], "text": "발표"}
    r = client.post("/api/v1/hints/delivery", json=body)
    assert (r.status_code, r.json()["error"]["code"]) == (409, "script_not_prepared")

    sha = client.post("/api/v1/scripts/analyze", json={"script": "첫 문단 발표 불안"}).json()["script_sha256"]
    r = client.post("/api/v1/hints/delivery", json={**body, "script_sha256": sha})
    assert r.status_code == 200
    assert r.json()["delivered"] is True


def test_pause_hint_carries_keywords_for_delivery():
    client = TestClient(create_app(replace(load_settings(), analyzer_provider="mock")))
    analyzed = client.post(
        "/api/v1/scripts/analyze", json={"script": "# 소개\n발표 불안 대본 대본\n\n# 해결\n스마트 안경 안경"}
    ).json()
    first = analyzed["parts"][0]["keywords"]
    r = client.post("/api/v1/hints/pause", json={"script_sha256": analyzed["script_sha256"], "text": first[0]}).json()
    assert r["hint"]
    assert r["hint_keywords"]
    assert first[0] not in r["hint_keywords"]
