"""Gemini contract tests using an in-process HTTP transport, never the real API."""
import asyncio
import json
import os
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, OpenAICompatibleAnalyzer, build_analyzer
from app.config import load_settings


@pytest.fixture
def gemini_settings(monkeypatch):
    for key in list(os.environ):
        if key.startswith(("GEMINI_", "OPENAI_", "OMNIROUTE_")):
            monkeypatch.delenv(key)
    monkeypatch.setenv("ANALYZER_PROVIDER", "gemini")
    monkeypatch.setenv("OPENAI_API_KEY", "wrong-openai-key")
    monkeypatch.setenv("OMNIROUTE_API_KEY", "wrong-gateway-key")
    return load_settings


def test_config_isolated_from_other_providers(gemini_settings, monkeypatch):
    cfg = gemini_settings()
    assert cfg.openai_api_key == ""
    assert cfg.openai_base_url == "https://generativelanguage.googleapis.com/v1beta/openai"
    assert cfg.openai_model == "gemini-3.1-flash-lite"
    assert build_analyzer(cfg).name == "gemini"
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv("GEMINI_MODEL", "selected-gemini-model")
    assert gemini_settings().openai_api_key == "test-gemini-key"
    assert gemini_settings().openai_model == "selected-gemini-model"


def test_gemini_request_and_parts_contract(gemini_settings, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/")
    def handler(request):
        assert str(request.url) == "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        assert request.headers["authorization"] == "Bearer test-gemini-key"
        payload = json.loads(request.content)
        assert payload["model"] == "gemini-3.1-flash-lite"
        assert payload["stream"] is False
        assert "temperature" not in payload
        fmt = payload["response_format"]
        assert fmt["type"] == "json_schema"
        item = fmt["json_schema"]["schema"]["properties"]["parts"]["items"]
        assert set(item["required"]) == {"title", "keywords", "hint"}
        assert "발표 대본" in payload["messages"][1]["content"]
        parts = {"parts": [{"title": "소개", "keywords": ["발표"], "hint": "오늘 발표"}]}
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(parts)}}]})
    analyzer = OpenAICompatibleAnalyzer(gemini_settings(), transport=httpx.MockTransport(handler))
    result = asyncio.run(analyzer.analyze("발표 대본"))
    assert result[0].index == 1
    assert result[0].keywords == ["발표"]


@pytest.mark.parametrize("missing", ["GEMINI_API_KEY", "GEMINI_MODEL"])
def test_missing_config_never_falls_back(gemini_settings, monkeypatch, missing):
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv(missing, "")
    def unexpected(_):
        pytest.fail("Missing configuration must not call an API")
    analyzer = OpenAICompatibleAnalyzer(gemini_settings(), transport=httpx.MockTransport(unexpected))
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "analyzer_not_configured"
    assert error.value.status_code == 503


@pytest.mark.parametrize("status", [400, 403, 429])
def test_provider_errors_do_not_expose_response_body(gemini_settings, monkeypatch, status):
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    analyzer = OpenAICompatibleAnalyzer(
        gemini_settings(),
        transport=httpx.MockTransport(lambda _: httpx.Response(status, text="PRIVATE_KEY_AND_SCRIPT")),
    )
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "llm_failed"
    assert str(status) in error.value.message
    assert "PRIVATE_KEY_AND_SCRIPT" not in error.value.message


@pytest.mark.parametrize("content", ["not json", '{"parts": []}'])
def test_invalid_parts_are_not_accepted(gemini_settings, monkeypatch, content):
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    analyzer = OpenAICompatibleAnalyzer(
        gemini_settings(),
        transport=httpx.MockTransport(lambda _: httpx.Response(
            200, json={"choices": [{"message": {"content": content}}]}
        )),
    )
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "analysis_invalid_format"
