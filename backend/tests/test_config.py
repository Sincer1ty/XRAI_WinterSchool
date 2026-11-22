"""설정 검증 소스. 테스트 실행과 통과 판정은 사용자 확인."""
import os
import sys
from dataclasses import replace
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings, load_settings


def settings(**overrides):
    values = dict(
        analyzer_provider="gemini", openai_api_key="test-key",
        openai_base_url="https://example.test/v1", openai_model="primary",
        openai_timeout_seconds=45, max_script_chars=20000, max_parts=10,
    )
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def clean_env(monkeypatch):
    # config import가 읽은 개인 .env에 영향을 받지 않는 환경을 만든다.
    for key in list(os.environ):
        if key.startswith(("OPENAI_", "GEMINI_", "OMNIROUTE_", "KEYBERT_")) or key in {
            "ANALYZER_PROVIDER", "ANALYSIS_TIMEOUT_SECONDS", "MAX_SCRIPT_CHARS", "MAX_PARTS"
        }:
            monkeypatch.delenv(key)
    return monkeypatch


@pytest.mark.parametrize("field", ["max_script_chars", "max_parts"])
@pytest.mark.parametrize("value", [0, -1, True, 1.5])
def test_counts_require_positive_integers(field, value):
    with pytest.raises(ValueError):
        settings(**{field: value})


@pytest.mark.parametrize("field", [
    "openai_timeout_seconds", "attempt_timeout_seconds",
    "keybert_timeout_seconds", "analysis_timeout_seconds",
])
@pytest.mark.parametrize("value", [0, -1, float("nan"), float("inf"), True])
def test_active_timeouts_require_finite_positive_numbers(field, value):
    with pytest.raises(ValueError):
        settings(**{field: value})


def test_default_budgets_fit_and_attempt_is_clamped_by_analyzer():
    cfg = settings()
    assert cfg.openai_timeout_seconds + cfg.keybert_timeout_seconds == cfg.analysis_timeout_seconds == 55
    assert cfg.analysis_timeout_seconds < 60
    assert settings(openai_timeout_seconds=5).attempt_timeout_seconds == 15


@pytest.mark.parametrize("overrides", [
    {"analysis_timeout_seconds": 60}, {"analysis_timeout_seconds": 61},
    {"analysis_timeout_seconds": 54}, {"keybert_timeout_seconds": 11},
])
def test_phone_and_combined_budget_mismatches_fail(overrides):
    with pytest.raises(ValueError):
        settings(**overrides)


def test_validation_applies_to_dataclass_replace_too():
    with pytest.raises(ValueError, match="MAX_PARTS"):
        replace(settings(), max_parts=0)


@pytest.mark.parametrize("url", [
    "file:///secret", "https://example.test:0/v1", "https://example.test:70000/v1",
    "https://example.test:bad/v1", "https://user:secret@example.test/v1",
    "https://example.test/v1?key=secret", "https://example.test/v1#secret",
    "https://bad host/v1", "https://example.test/v1/chat/completions",
    "https://[broken/v1", "https://@example.test/v1",
    "https://example.test/v1/chat/completions/",
])
def test_invalid_urls_report_only_field_name(url):
    with pytest.raises(ValueError) as caught:
        settings(openai_base_url=url)
    assert "GEMINI_BASE_URL" in str(caught.value)
    assert url not in str(caught.value)
    assert "secret" not in str(caught.value)


@pytest.mark.parametrize("raw, expected", [
    ("true", True), ("1", True), (" YES ", True),
    ("false", False), ("0", False), ("No", False),
])
def test_supported_boolean_values(clean_env, raw, expected):
    clean_env.setenv("ANALYZER_PROVIDER", "gemini")
    clean_env.setenv("KEYBERT_FALLBACK", raw)
    assert load_settings().keybert_fallback is expected


@pytest.mark.parametrize("name, raw", [
    ("KEYBERT_FALLBACK", "tru-secret"),
    ("GEMINI_TIMEOUT_SECONDS", "private-invalid-value"),
    ("KEYBERT_TIMEOUT_SECONDS", "NaN"), ("ANALYSIS_TIMEOUT_SECONDS", "inf"),
    ("MAX_SCRIPT_CHARS", "private-invalid-value"), ("MAX_PARTS", "0"),
    ("GEMINI_JSON_MODE", "private-invalid-value"),
])
def test_environment_errors_do_not_echo_values(clean_env, name, raw):
    clean_env.setenv("ANALYZER_PROVIDER", "gemini")
    clean_env.setenv(name, raw)
    with pytest.raises(ValueError) as caught:
        load_settings()
    assert name in str(caught.value)
    assert raw not in str(caught.value)


@pytest.mark.parametrize("provider", ["mock", "keybert"])
def test_local_modes_ignore_unrelated_remote_configuration(clean_env, provider):
    clean_env.setenv("ANALYZER_PROVIDER", provider)
    for key, value in {
        "OPENAI_BASE_URL": "https://[broken", "OPENAI_TIMEOUT_SECONDS": "invalid",
        "OPENAI_ATTEMPT_TIMEOUT_SECONDS": "invalid", "OPENAI_JSON_MODE": "invalid",
        "KEYBERT_FALLBACK": "invalid",
    }.items():
        clean_env.setenv(key, value)
    assert load_settings().analyzer_provider == provider


def test_missing_credentials_remain_request_time_error(clean_env):
    clean_env.setenv("ANALYZER_PROVIDER", "gemini")
    clean_env.setenv("GEMINI_API_KEY", "")
    clean_env.setenv("GEMINI_MODEL", "")
    cfg = load_settings()
    assert not cfg.openai_api_key and not cfg.openai_model


def test_unknown_provider_and_empty_local_model_path_fail(clean_env):
    clean_env.setenv("ANALYZER_PROVIDER", "unknown-secret")
    with pytest.raises(ValueError, match="ANALYZER_PROVIDER"):
        load_settings()
    with pytest.raises(ValueError, match="KEYBERT_MODEL_PATH"):
        settings(keybert_model_path=" ")
