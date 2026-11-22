"""비동기 대기 취소와 출처 보존. 가짜 HTTP만 사용하며 실제 API/모델은 호출하지 않는다."""
import asyncio
import hashlib
import json
import sys
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, OpenAICompatibleAnalyzer, analysis_source, llm_wait_ms
from app.config import Settings
from app.local_analyzer import KeyBertFallback
from app.main import create_app
from app.schemas import ScriptPart

SCRIPT = "오늘 발표를 시작합니다"
PARTS = [{"title": "소개", "keywords": ["발표"], "hint": "오늘 발표"}]


def settings(**overrides):
    values = dict(
        analyzer_provider="gemini", openai_api_key="test-key",
        openai_base_url="https://example.test/v1", openai_model="primary",
        openai_timeout_seconds=5, max_script_chars=20000, max_parts=10,
        keybert_fallback=False,
    )
    values.update(overrides)
    return Settings(**values)


def response():
    return httpx.Response(200, json={"choices": [{"message": {
        "content": json.dumps({"parts": PARTS}, ensure_ascii=False)
    }}]})


def test_attempt_deadline_cancels_slow_primary_before_backup():
    calls, cancelled = [], []

    async def handler(request):
        model = json.loads(request.content)["model"]
        calls.append(model)
        if model == "primary":
            try:
                await asyncio.sleep(10)
            except asyncio.CancelledError:
                cancelled.append(model)
                raise
        return response()

    analyzer = OpenAICompatibleAnalyzer(
        settings(fallback_models=("backup",), attempt_timeout_seconds=0.02),
        transport=httpx.MockTransport(handler),
    )
    result = asyncio.run(analyzer.analyze(SCRIPT))
    assert calls == ["primary", "backup"]
    assert cancelled == ["primary"]
    assert result[0].keywords == ["발표"]


def test_remote_deadline_cancels_pending_io_and_keeps_retryable_error_and_timing():
    cancelled = []

    async def handler(request):
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            cancelled.append(True)
            raise
        return response()

    async def run():
        analyzer = OpenAICompatibleAnalyzer(
            settings(openai_timeout_seconds=0.02), transport=httpx.MockTransport(handler)
        )
        llm_wait_ms.set(None)
        with pytest.raises(AnalyzerError) as caught:
            await analyzer.analyze(SCRIPT)
        assert caught.value.code == "llm_timeout"
        assert caught.value.status_code == 504 and caught.value.retryable
        assert llm_wait_ms.get() is not None

    asyncio.run(run())
    assert cancelled == [True]


def test_endpoint_whole_deadline_does_not_prepare_timed_out_script():
    cancelled = []

    class SlowAnalyzer:
        name = "mock"

        async def analyze(self, script):
            try:
                await asyncio.sleep(10)
            except asyncio.CancelledError:
                cancelled.append(True)
                raise
            return [ScriptPart(index=1, body=script, **PARTS[0])]

    cfg = settings(analyzer_provider="mock", analysis_timeout_seconds=0.02)
    client = TestClient(create_app(cfg, SlowAnalyzer()))
    result = client.post("/api/v1/scripts/analyze", json={"script": SCRIPT})
    assert result.status_code == 504
    assert result.json()["error"]["code"] == "analysis_timeout"
    assert cancelled == [True]
    match = client.post("/api/v1/parts/match", json={
        "script_sha256": hashlib.sha256(SCRIPT.encode()).hexdigest(), "text": "발표",
    })
    assert match.status_code == 409


def test_whole_deadline_preserves_actual_analyzer_source_and_llm_time():
    class LocalResult:
        name = "gemini"

        async def analyze(self, script):
            analysis_source.set("keybert")
            llm_wait_ms.set(0.1)
            await asyncio.sleep(0)
            return [ScriptPart(index=1, body=script, **PARTS[0])]

    client = TestClient(create_app(settings(), LocalResult()))
    result = client.post("/api/v1/scripts/analyze", json={"script": SCRIPT})
    assert result.status_code == 200
    assert result.json()["analyzer"] == "keybert"
    assert "llm;dur=0.1" in result.headers["Server-Timing"]


def test_external_cancellation_is_not_converted_into_timeout():
    async def run():
        entered = asyncio.Event()

        async def handler(request):
            entered.set()
            await asyncio.sleep(10)
            return response()

        analyzer = OpenAICompatibleAnalyzer(settings(), transport=httpx.MockTransport(handler))
        task = asyncio.create_task(analyzer.analyze(SCRIPT))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(run())


def test_remote_deadline_can_fall_back_to_local_in_same_request():
    async def slow_remote(request):
        await asyncio.sleep(10)
        return response()

    class LocalAnalyzer:
        name = "keybert"

        async def analyze(self, script):
            return [ScriptPart(index=1, body=script, **PARTS[0])]

    async def run():
        cfg = settings(openai_timeout_seconds=0.02, keybert_fallback=True)
        remote = OpenAICompatibleAnalyzer(cfg, transport=httpx.MockTransport(slow_remote))
        analysis_source.set(None)
        result = await KeyBertFallback(remote, LocalAnalyzer()).analyze(SCRIPT)
        assert result[0].body == SCRIPT
        assert analysis_source.get() == "keybert"
        assert llm_wait_ms.get() is not None

    asyncio.run(run())
