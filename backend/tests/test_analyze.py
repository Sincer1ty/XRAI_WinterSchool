import hashlib
import json
import sys
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import OpenAICompatibleAnalyzer  # noqa: E402
from app.config import load_settings  # noqa: E402
from app.main import create_app  # noqa: E402

SCRIPT_MD = """# 문제 소개
안녕하세요, 오늘은 발표 불안 문제를 이야기하겠습니다. 발표자는 긴장하면 대본을 잊습니다.

# 해결 방법
그래서 저희는 스마트 안경에 힌트를 띄웁니다. 안경은 발표자가 멈췄을 때만 힌트를 보여줍니다.

# 기대 효과
결과적으로 발표자는 자신감을 얻습니다.
"""


def _settings(**kw):
    kw.setdefault("analyzer_provider", "mock")
    # Keep unit fixtures independent of the local gateway .env.
    kw.setdefault("openai_model", "test-model")
    kw.setdefault("openai_base_url", "https://example.test/v1")
    kw.setdefault("json_response_format", True)
    return replace(load_settings(), **kw)


@pytest.fixture
def client():
    return TestClient(create_app(_settings()))


def test_health(client):
    assert client.get("/health").json() == {"status": "ok", "analyzer": "mock"}


def test_mock_markdown_parts(client):
    r = client.post("/api/v1/scripts/analyze", json={"script": SCRIPT_MD, "client_revision": 7})
    assert r.status_code == 200
    body = r.json()
    assert body["client_revision"] == 7
    assert body["script_sha256"] == hashlib.sha256(SCRIPT_MD.encode()).hexdigest()
    assert [p["title"] for p in body["parts"]] == ["문제 소개", "해결 방법", "기대 효과"]
    for i, p in enumerate(body["parts"], start=1):
        assert p["index"] == i
        assert p["keywords"]
        assert p["hint"]
    assert body["parts"][0]["hint"] == "안녕하세요"


def test_plain_text_paragraphs_merged_to_limit():
    client = TestClient(create_app(_settings(max_parts=2)))
    script = "\n\n".join(f"{i}번째 문단입니다. 발표 내용 {i}." for i in range(1, 6))
    r = client.post("/api/v1/scripts/analyze", json={"script": script})
    assert r.status_code == 200
    assert len(r.json()["parts"]) == 2


@pytest.mark.parametrize("script", ["", "   \n\t "])
def test_empty_script_rejected(client, script):
    r = client.post("/api/v1/scripts/analyze", json={"script": script})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "empty_script"


def test_too_long_script_rejected():
    client = TestClient(create_app(_settings(max_script_chars=10)))
    r = client.post("/api/v1/scripts/analyze", json={"script": "가" * 11})
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "script_too_long"


def test_malformed_request(client):
    r = client.post("/api/v1/scripts/analyze", json={"text": "x"})
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "invalid_request"


# --- OpenAI 호환 분석기: 가짜 전송 계층으로 확인 --------------------------------

def _llm_app(handler, **kw):
    settings = _settings(analyzer_provider="openai", openai_api_key="test-key", **kw)
    analyzer = OpenAICompatibleAnalyzer(settings, transport=httpx.MockTransport(handler))
    return TestClient(create_app(settings, analyzer))


def _chat(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_llm_success():
    parts = {"parts": [{"title": "소개", "keywords": ["발표 불안", "발표 불안", " "], "hint": "안녕하세요"}]}

    def handler(request: httpx.Request):
        assert request.headers["authorization"] == "Bearer test-key"
        assert json.loads(request.content)["response_format"] == {"type": "json_object"}
        return _chat(json.dumps(parts, ensure_ascii=False))

    r = _llm_app(handler).post("/api/v1/scripts/analyze", json={"script": "안녕하세요. 발표 불안 이야기입니다."})
    assert r.status_code == 200
    assert r.json()["analyzer"] == "openai"
    assert r.json()["parts"][0]["keywords"] == ["발표 불안"]


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        "[]",
        json.dumps({"parts": []}),
        json.dumps({"parts": [{"title": "a", "keywords": [], "hint": "b"}]}),
        json.dumps({"parts": [{"title": "", "keywords": ["k"], "hint": "b"}]}),
        json.dumps({"parts": [{"title": "a", "keywords": "k", "hint": "b"}]}),
    ],
)
def test_llm_invalid_format(content):
    r = _llm_app(lambda req: _chat(content)).post("/api/v1/scripts/analyze", json={"script": "x"})
    assert r.status_code == 502
    assert r.json()["error"]["code"] == "analysis_invalid_format"


def test_llm_http_error():
    r = _llm_app(lambda req: httpx.Response(500)).post("/api/v1/scripts/analyze", json={"script": "x"})
    assert r.status_code == 502
    assert r.json()["error"]["code"] == "llm_failed"


def test_llm_timeout():
    def handler(request):
        raise httpx.ReadTimeout("slow", request=request)

    r = _llm_app(handler).post("/api/v1/scripts/analyze", json={"script": "x"})
    assert r.status_code == 504
    assert r.json()["error"]["code"] == "llm_timeout"


def test_llm_missing_key():
    settings = _settings(analyzer_provider="openai", openai_api_key="")
    r = TestClient(create_app(settings)).post("/api/v1/scripts/analyze", json={"script": "x"})
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "analyzer_not_configured"


def test_llm_keeps_only_short_keywords_found_in_script():
    script = "안녕하세요. 발표할 때 긴장하면 대본을 잊습니다."
    parts = {"parts": [{"title": "문제", "hint": "안녕하세요", "keywords": [
        "긴장하면 대본을 잊습니다", "대본 망각", "긴장", "대 본",
    ]}]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": script}
    )
    assert r.status_code == 200
    # 긴 문장·대본에 없는 요약어는 버리고, 띄어쓰기만 다른 원문 표현은 남긴다.
    assert r.json()["parts"][0]["keywords"] == ["긴장", "대 본"]


def test_llm_rejects_part_without_script_keywords():
    parts = {"parts": [{"title": "문제", "hint": "안녕하세요", "keywords": ["대본 망각"]}]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": "긴장하면 대본을 잊습니다."}
    )
    assert r.status_code == 502
    assert r.json()["error"]["code"] == "analysis_invalid_format"


def _timing(response) -> dict[str, float]:
    items = [item.strip().split(";dur=") for item in response.headers["Server-Timing"].split(",")]
    return {name: float(value) for name, value in items}


def test_llm_wait_is_recorded_separately_from_server_time():
    parts = {"parts": [{"title": "소개", "keywords": ["발표"], "hint": "오늘 발표"}]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": "오늘 발표"}
    )
    timing = _timing(r)
    assert set(timing) == {"app", "llm", "total"}
    assert timing["total"] == pytest.approx(timing["app"] + timing["llm"], abs=0.2)


def test_llm_wait_is_recorded_even_when_llm_fails():
    r = _llm_app(lambda req: httpx.Response(500)).post("/api/v1/scripts/analyze", json={"script": "x"})
    assert r.status_code == 502
    assert "llm" in _timing(r)


def test_requests_without_llm_report_server_time_only():
    parts = {"parts": [{"title": "소개", "keywords": ["발표"], "hint": "오늘 발표"}]}
    client = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False)))
    r = client.post("/api/v1/hints/pause", json={"script_sha256": "none", "text": "발표"})
    assert r.status_code == 409
    assert set(_timing(r)) == {"app", "total"}


def test_keywords_shared_across_parts_are_dropped():
    script = "수업 발표가 어렵습니다. 대본을 봅니다. 그래서 발표 힌트 안경을 만들었습니다. 단서를 줍니다."
    parts = {"parts": [
        {"title": "문제", "hint": "수업 발표가", "start_quote": "수업 발표가 어렵습니다.", "keywords": ["수업 발표", "대본"]},
        {"title": "해결", "hint": "그래서", "start_quote": "그래서 발표 힌트 안경을 만들었습니다.", "keywords": ["발표", "힌트 안경", "단서"]},
        {"title": "끝", "hint": "단서를", "start_quote": "단서를 줍니다.", "keywords": ["단서"]},
    ]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": script}
    )
    assert r.status_code == 200
    got = [p["keywords"] for p in r.json()["parts"]]
    # "발표"는 "수업 발표"에 포함되어 양쪽에서 빠진다. "단서"는 2번 본문에 없어 그 파트에서 빠진다.
    # 빼면 키워드가 없는 마지막 파트는 원래 키워드를 유지한다.
    assert got == [["대본"], ["힌트 안경"], ["단서"]]


HEADED = "# 문제\n발표 불안이 큽니다.\n\n# 해결\n스마트 안경을 씁니다."


def test_headings_fix_part_count_and_keyword_source():
    def handler(request):
        payload = json.loads(request.content)
        assert "정확히 2개 파트" in payload["messages"][1]["content"]
        # 2번 파트에 1번 본문 단어("발표 불안")를 넣어도 그 파트 본문에 없으므로 빠진다.
        parts = {"parts": [
            {"title": "AI 제목", "hint": "발표 불안이", "keywords": ["발표 불안"]},
            {"title": "AI 제목2", "hint": "스마트 안경을", "keywords": ["발표 불안", "스마트 안경"]},
        ]}
        return _chat(json.dumps(parts, ensure_ascii=False))

    r = _llm_app(handler).post("/api/v1/scripts/analyze", json={"script": HEADED})
    assert r.status_code == 200
    got = [(p["title"], p["keywords"]) for p in r.json()["parts"]]
    assert got == [("문제", ["발표 불안"]), ("해결", ["스마트 안경"])]


def test_part_count_different_from_headings_is_rejected():
    parts = {"parts": [{"title": "하나로", "hint": "발표", "keywords": ["발표 불안"]}]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": HEADED}
    )
    assert r.status_code == 502
    assert r.json()["error"]["code"] == "analysis_invalid_format"


def test_keywords_found_in_other_section_text_are_dropped():
    script = "# 문제\n발표 불안이 큽니다. 서버 이야기도 합니다.\n\n# 해결\n서버와 스마트 안경을 씁니다."
    parts = {"parts": [
        {"title": "a", "hint": "발표", "keywords": ["발표 불안"]},
        {"title": "b", "hint": "서버와", "keywords": ["서버", "스마트 안경"]},
    ]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": script}
    )
    # "서버"는 1번 파트 본문에도 있어 그 문장을 말하면 2번 파트로 잘못 판단하므로 뺀다.
    assert [p["keywords"] for p in r.json()["parts"]] == [["발표 불안"], ["스마트 안경"]]


def test_heading_words_are_not_keywords():
    script = "# 데모 시연\n이제 직접 보여드리겠습니다."
    parts = {"parts": [{"title": "a", "hint": "이제", "keywords": ["데모 시연", "직접"]}]}
    r = _llm_app(lambda req: _chat(json.dumps(parts, ensure_ascii=False))).post(
        "/api/v1/scripts/analyze", json={"script": script}
    )
    # 제목은 말하지 않으므로 제목에만 있는 키워드는 매칭될 수 없다.
    assert r.json()["parts"][0]["keywords"] == ["직접"]
