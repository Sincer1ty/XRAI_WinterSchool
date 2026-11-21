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

    r = _llm_app(handler).post("/api/v1/scripts/analyze", json={"script": "안녕하세요"})
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
