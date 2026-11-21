"""대체 모델 테스트. 가짜 HTTP 전송 계층만 쓰고 실제 API는 호출하지 않는다."""
import asyncio
import json
import sys
from dataclasses import replace
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, OpenAICompatibleAnalyzer, llm_wait_ms  # noqa: E402
from app.config import _model_list, load_settings  # noqa: E402

SCRIPT = "오늘 발표를 시작합니다"
PARTS = {"parts": [{"title": "소개", "keywords": ["발표"], "hint": "오늘 발표"}]}


def _settings(**kw):
    base = replace(
        load_settings(),
        analyzer_provider="gemini",
        openai_api_key="test-key",
        openai_base_url="https://example.test/v1",
        openai_model="primary",
        fallback_models=("backup-1", "backup-2"),
        openai_timeout_seconds=45,
        attempt_timeout_seconds=15,
    )
    return replace(base, **kw)


def _ok():
    return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(PARTS, ensure_ascii=False)}}]})


def _run(handler, **kw):
    """분석을 실행하고 (결과 또는 오류, 호출된 모델 순서)를 돌려준다."""
    calls: list[str] = []

    def record(request: httpx.Request):
        calls.append(json.loads(request.content)["model"])
        return handler(request, calls[-1])

    analyzer = OpenAICompatibleAnalyzer(_settings(**kw), transport=httpx.MockTransport(record))
    try:
        return asyncio.run(analyzer.analyze(SCRIPT)), calls
    except AnalyzerError as e:
        return e, calls


def test_primary_success_does_not_try_fallbacks():
    result, calls = _run(lambda req, model: _ok())
    assert calls == ["primary"]
    assert result[0].keywords == ["발표"]


@pytest.mark.parametrize("status", [429, 500, 502, 503, 504, 404])
def test_overloaded_primary_falls_back(status):
    result, calls = _run(lambda req, model: httpx.Response(status) if model == "primary" else _ok())
    assert calls == ["primary", "backup-1"]
    assert not isinstance(result, AnalyzerError)


def test_timeout_falls_back():
    def handler(request, model):
        if model == "primary":
            raise httpx.ReadTimeout("slow", request=request)
        return _ok()

    result, calls = _run(handler)
    assert calls == ["primary", "backup-1"]
    assert not isinstance(result, AnalyzerError)


def test_invalid_output_falls_back():
    def handler(request, model):
        if model == "primary":
            return httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}]})
        return _ok()

    result, calls = _run(handler)
    assert calls == ["primary", "backup-1"]
    assert not isinstance(result, AnalyzerError)


def test_keywords_not_in_script_fall_back():
    bad = {"parts": [{"title": "소개", "keywords": ["대본에 없는 말"], "hint": "오늘"}]}

    def handler(request, model):
        if model == "primary":
            return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(bad, ensure_ascii=False)}}]})
        return _ok()

    result, calls = _run(handler)
    assert calls == ["primary", "backup-1"]
    assert not isinstance(result, AnalyzerError)


@pytest.mark.parametrize("status", [400, 401, 403])
def test_key_or_request_errors_fail_immediately(status):
    error, calls = _run(lambda req, model: httpx.Response(status))
    assert calls == ["primary"]
    assert error.code == "llm_failed"
    assert "시도한 모델" not in error.message


def test_network_error_fails_immediately():
    def handler(request, model):
        raise httpx.ConnectError("offline", request=request)

    error, calls = _run(handler)
    assert calls == ["primary"]
    assert error.code == "llm_unreachable"


def test_all_models_fail_reports_last_error_and_tried_models():
    error, calls = _run(lambda req, model: httpx.Response(503))
    assert calls == ["primary", "backup-1", "backup-2"]
    assert error.code == "llm_failed"
    assert "HTTP 503" in error.message
    assert "primary, backup-1, backup-2" in error.message


def test_no_fallbacks_keeps_single_model_behavior():
    error, calls = _run(lambda req, model: httpx.Response(503), fallback_models=())
    assert calls == ["primary"]
    assert "시도한 모델" not in error.message


def test_attempt_timeouts_split_total_budget():
    seen: list[float] = []

    def handler(request, model):
        seen.append(request.extensions["timeout"]["read"])
        return httpx.Response(503)

    _run(handler, openai_timeout_seconds=45, attempt_timeout_seconds=15)
    # 대체 모델이 남아 있으면 15초, 마지막 모델은 남은 전체 시간.
    assert seen[0] == pytest.approx(15, abs=0.5)
    assert seen[1] == pytest.approx(15, abs=0.5)
    assert seen[2] == pytest.approx(45, abs=1)


def test_llm_wait_covers_all_attempts():
    async def slow_503(request):
        await asyncio.sleep(0.05)
        return httpx.Response(503)

    async def run():
        # 엔드포인트처럼 같은 코루틴 안에서 분석 후 값을 읽는다.
        analyzer = OpenAICompatibleAnalyzer(_settings(), transport=httpx.MockTransport(slow_503))
        llm_wait_ms.set(None)
        with pytest.raises(AnalyzerError):
            await analyzer.analyze(SCRIPT)
        return llm_wait_ms.get()

    waited = asyncio.run(run())
    assert waited is not None and waited >= 3 * 50 * 0.9  # 세 모델 대기 시간의 합


def test_fallback_list_parsing():
    assert _model_list(" a, b,,a ,primary", "primary") == ("a", "b")
    assert _model_list("", "primary") == ()


def test_gemini_default_fallbacks(monkeypatch):
    for key in ("GEMINI_FALLBACK_MODELS", "GEMINI_MODEL", "GEMINI_ATTEMPT_TIMEOUT_SECONDS"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("ANALYZER_PROVIDER", "gemini")
    cfg = load_settings()
    assert cfg.fallback_models == ("gemini-2.5-flash", "gemini-3-flash-preview")
    assert cfg.attempt_timeout_seconds == 15

    monkeypatch.setenv("GEMINI_FALLBACK_MODELS", "")
    assert load_settings().fallback_models == ()

    monkeypatch.setenv("GEMINI_MODEL", "gemini-2.5-flash")
    monkeypatch.setenv("GEMINI_FALLBACK_MODELS", "gemini-2.5-flash, gemini-3-flash-preview")
    assert load_settings().fallback_models == ("gemini-3-flash-preview",)


def test_other_providers_have_no_default_fallbacks(monkeypatch):
    monkeypatch.delenv("OPENAI_FALLBACK_MODELS", raising=False)
    monkeypatch.setenv("ANALYZER_PROVIDER", "openai")
    assert load_settings().fallback_models == ()
