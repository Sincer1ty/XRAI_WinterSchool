import asyncio
import json
import sys
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import AnalyzerError, OpenAICompatibleAnalyzer, analysis_source
from app.config import load_settings
from app.local_analyzer import KeyBertAnalyzer, KeyBertFallback
from app.main import create_app
from app.schemas import ScriptPart

PARTS = [ScriptPart(index=1, title="소개", hint="발표", keywords=["대본"])]


class FakeAnalyzer:
    def __init__(self, name, error=None):
        self.name = name
        self.error = error
        self.calls = 0

    async def analyze(self, script):
        self.calls += 1
        if self.error:
            raise self.error
        return PARTS


def test_primary_success_does_not_load_local_model():
    primary, local = FakeAnalyzer("gemini"), FakeAnalyzer("keybert")
    assert asyncio.run(KeyBertFallback(primary, local).analyze("대본")) == PARTS
    assert (primary.calls, local.calls) == (1, 0)


@pytest.mark.parametrize("status", [429, 503])
def test_all_gemini_models_fail_then_local_result_reports_actual_source(status):
    cfg = replace(load_settings(), analyzer_provider="gemini", openai_api_key="test",
                  openai_model="primary", fallback_models=("backup",))
    calls = []

    def unavailable(request):
        calls.append(json.loads(request.content)["model"])
        return httpx.Response(status)

    primary = OpenAICompatibleAnalyzer(cfg, transport=httpx.MockTransport(unavailable))
    local = FakeAnalyzer("keybert")
    client = TestClient(create_app(cfg, analyzer=KeyBertFallback(primary, local)))
    result = client.post("/api/v1/scripts/analyze", json={"script": "대본", "client_revision": 7})
    assert result.status_code == 200
    assert calls == ["primary", "backup"]
    assert result.json()["analyzer"] == "keybert"
    assert result.json()["client_revision"] == 7
    assert local.calls == 1
    assert client.get("/health").json()["analyzer"] == "gemini"


def test_key_error_is_reported_without_local_fallback():
    primary = FakeAnalyzer("gemini", AnalyzerError("llm_failed", "HTTP 401"))
    local = FakeAnalyzer("keybert")
    with pytest.raises(AnalyzerError, match="401"):
        asyncio.run(KeyBertFallback(primary, local).analyze("대본"))
    assert local.calls == 0


def test_local_unavailability_is_not_reported_as_success():
    primary = FakeAnalyzer("gemini", AnalyzerError("llm_timeout", "timeout", 504, retryable=True))
    local = FakeAnalyzer("keybert", AnalyzerError("keybert_not_ready", "모델 준비 필요", 503))
    with pytest.raises(AnalyzerError) as caught:
        asyncio.run(KeyBertFallback(primary, local).analyze("대본"))
    assert caught.value.code == "keybert_not_ready"


def test_actual_source_is_isolated_between_concurrent_requests():
    class MixedPrimary:
        name = "gemini"

        async def analyze(self, script):
            await asyncio.sleep(0)
            if script == "local":
                raise AnalyzerError("llm_timeout", "timeout", retryable=True)
            return PARTS

    async def run():
        analyzer = KeyBertFallback(MixedPrimary(), FakeAnalyzer("keybert"))

        async def request(script):
            await analyzer.analyze(script)
            return analysis_source.get()

        return await asyncio.gather(request("local"), request("remote"))

    assert asyncio.run(run()) == ["keybert", "gemini"]


def test_missing_local_model_has_actionable_error(tmp_path):
    cfg = replace(load_settings(), keybert_model_path=str(tmp_path / "missing"))
    with pytest.raises(AnalyzerError) as caught:
        asyncio.run(KeyBertAnalyzer(cfg).analyze("대본"))
    assert caught.value.code == "keybert_not_ready"


def test_timed_out_worker_does_not_allow_overlapping_local_inference():
    analyzer = KeyBertAnalyzer(load_settings())
    analyzer._lock.acquire()
    try:
        with pytest.raises(AnalyzerError) as caught:
            asyncio.run(analyzer.analyze("대본"))
        assert caught.value.code == "keybert_busy"
    finally:
        analyzer._lock.release()


def test_local_keywords_use_source_order_and_existing_contract(monkeypatch):
    pytest.importorskip("sklearn")
    seen = []

    class FakeKeyBert:
        def extract_keywords(self, source, **options):
            vectorizer = options["vectorizer"]
            vectorizer.fit_transform([source])
            candidates = vectorizer.get_feature_names_out().tolist()
            seen.append(candidates)
            assert options["top_n"] == 5
            return [(word, 0.9) for word in reversed(candidates[:2])]

    cfg = replace(load_settings(), max_parts=10)
    analyzer = KeyBertAnalyzer(cfg)
    monkeypatch.setattr(analyzer, "_load_model", lambda: FakeKeyBert())
    parts = asyncio.run(analyzer.analyze("# 소개\n발표 불안 대본\n\n# 해결\n스마트 안경 기술"))
    assert [part.title for part in parts] == ["소개", "해결"]
    assert all(part.hint and part.keywords for part in parts)
    assert [part.body for part in parts] == ["발표 불안 대본", "스마트 안경 기술"]
    assert len(seen) == 2
    for part, source in zip(parts, ["발표 불안 대본", "스마트 안경 기술"]):
        assert all(keyword in source for keyword in part.keywords)
        assert part.keywords == sorted(part.keywords, key=source.index)


def test_local_timeout_has_distinct_error(monkeypatch):
    cfg = replace(load_settings(), keybert_timeout_seconds=0.01)

    async def slow_worker(*args):
        await asyncio.sleep(1)

    monkeypatch.setattr("app.local_analyzer.asyncio.to_thread", slow_worker)
    with pytest.raises(AnalyzerError) as caught:
        asyncio.run(KeyBertAnalyzer(cfg).analyze("대본"))
    assert caught.value.code == "keybert_timeout"
    assert caught.value.status_code == 504
