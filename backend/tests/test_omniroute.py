"""OmniRoute request/configuration tests. Uses an in-process transport; no real API calls."""
import asyncio
import json
import sys
from dataclasses import replace
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, OpenAICompatibleAnalyzer, build_analyzer
from app.config import Settings, load_settings

PARTS = {"parts": [{"title": "소개", "keywords": ["발표"], "hint": "오늘은 발표"}]}


def settings(**changes):
    base = Settings(
        analyzer_provider="omniroute",
        openai_api_key="test-gateway-key",
        openai_base_url="http://127.0.0.1:20128/v1",
        openai_model="test-provider/chat-model",
        openai_timeout_seconds=5,
        max_script_chars=20000,
        max_parts=10,
    )
    return replace(base, **changes)


@pytest.mark.parametrize("json_mode", [True, False])
def test_gateway_request_preserves_contract(json_mode):
    def handler(request):
        assert str(request.url) == "http://127.0.0.1:20128/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer test-gateway-key"
        payload = json.loads(request.content)
        assert payload["model"] == "test-provider/chat-model"
        assert payload["stream"] is False
        assert "temperature" not in payload
        assert payload["messages"][1]["content"].find("오늘은 발표") >= 0
        if json_mode:
            assert payload["response_format"] == {"type": "json_object"}
        else:
            assert "response_format" not in payload
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(PARTS)}}]})

    analyzer = OpenAICompatibleAnalyzer(
        settings(json_response_format=json_mode), transport=httpx.MockTransport(handler)
    )
    assert analyzer.name == "omniroute"
    assert asyncio.run(analyzer.analyze("오늘은 발표")).pop().keywords == ["발표"]


@pytest.mark.parametrize("missing", ["openai_api_key", "openai_model"])
def test_missing_gateway_config_does_not_call_any_provider(missing):
    def unexpected(_):
        pytest.fail("Unconfigured gateway must not make a request")
    analyzer = OpenAICompatibleAnalyzer(
        settings(**{missing: ""}), transport=httpx.MockTransport(unexpected)
    )
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "analyzer_not_configured"
    assert error.value.status_code == 503


@pytest.mark.parametrize("status", [400, 401, 403, 404, 429, 500])
def test_gateway_error_does_not_leak_upstream_body(status):
    def handler(_):
        return httpx.Response(status, text="PRIVATE_KEY_AND_SCRIPT")
    analyzer = OpenAICompatibleAnalyzer(settings(), transport=httpx.MockTransport(handler))
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "llm_failed"
    assert str(status) in error.value.message
    assert "PRIVATE_KEY_AND_SCRIPT" not in error.value.message


def test_prompt_mode_still_rejects_non_json():
    analyzer = OpenAICompatibleAnalyzer(
        settings(json_response_format=False),
        transport=httpx.MockTransport(lambda _: httpx.Response(
            200, json={"choices": [{"message": {"content": "잘못된 출력"}}]}
        )),
    )
    with pytest.raises(AnalyzerError) as error:
        asyncio.run(analyzer.analyze("대본"))
    assert error.value.code == "analysis_invalid_format"


def test_omniroute_settings_do_not_use_openai_credentials(monkeypatch):
    for key in list(__import__("os").environ):
        if key.startswith(("OMNIROUTE_", "OPENAI_")):
            monkeypatch.delenv(key)
    monkeypatch.setenv("ANALYZER_PROVIDER", "omniroute")
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-be-used")
    monkeypatch.setenv("OPENAI_MODEL", "must-not-be-used")
    cfg = load_settings()
    assert cfg.openai_api_key == ""
    assert cfg.openai_model == ""
    assert cfg.openai_base_url == "http://127.0.0.1:20128/v1"
    assert build_analyzer(cfg).name == "omniroute"
    monkeypatch.setenv("OMNIROUTE_API_KEY", "gateway-key")
    monkeypatch.setenv("OMNIROUTE_MODEL", "team-combo")
    monkeypatch.setenv("OMNIROUTE_BASE_URL", " http://localhost:20128/v1/ ")
    monkeypatch.setenv("OMNIROUTE_JSON_MODE", "prompt")
    cfg = load_settings()
    assert cfg.openai_api_key == "gateway-key"
    assert cfg.openai_model == "team-combo"
    assert cfg.openai_base_url == "http://localhost:20128/v1"
    assert not cfg.json_response_format


@pytest.mark.parametrize("url", [
    "http://localhost:20128/v1/chat/completions",
    "file:///gateway", "http://user:secret@localhost/v1", "http://localhost/v1?key=secret",
])
def test_invalid_api_root_is_rejected(monkeypatch, url):
    monkeypatch.setenv("ANALYZER_PROVIDER", "omniroute")
    monkeypatch.setenv("OMNIROUTE_BASE_URL", url)
    with pytest.raises(ValueError):
        load_settings()
