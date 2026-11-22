"""환경 변수 설정. 실제 키는 backend/.env에만 두고 오류 메시지에 출력하지 않는다."""

import math
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[1] / ".env")

# 폰 ScriptAnalysisClient의 기존 읽기 대기와 맞춰 유지하는 호환 경계다.
PHONE_ANALYSIS_READ_TIMEOUT_SECONDS = 60.0
DEFAULT_KEYBERT_MODEL_PATH = str(Path(__file__).resolve().parents[1] / "models" / "keybert")
REMOTE_PROVIDERS = {"openai", "omniroute", "gemini"}


@dataclass(frozen=True)
class Settings:
    analyzer_provider: str
    openai_api_key: str
    openai_base_url: str
    openai_model: str
    openai_timeout_seconds: float
    max_script_chars: int
    max_parts: int
    json_response_format: bool = True
    # 주 모델이 혼잡하면 순서대로 시도할 대체 모델.
    fallback_models: tuple[str, ...] = ()
    # 대체 모델이 남았을 때 한 모델의 한도. 마지막 모델은 남은 원격 시간을 쓴다.
    attempt_timeout_seconds: float = 15.0
    keybert_fallback: bool = True
    keybert_model_path: str = DEFAULT_KEYBERT_MODEL_PATH
    keybert_timeout_seconds: float = 10.0
    analysis_timeout_seconds: float = 55.0

    def __post_init__(self) -> None:
        provider = self.analyzer_provider
        if provider not in REMOTE_PROVIDERS | {"mock", "keybert"}:
            raise ValueError("ANALYZER_PROVIDER must be mock, keybert, openai, omniroute or gemini.")
        if type(self.keybert_fallback) is not bool:
            raise ValueError("KEYBERT_FALLBACK must be a boolean.")
        if type(self.json_response_format) is not bool:
            raise ValueError("json_response_format must be a boolean.")
        for name, value in (("MAX_SCRIPT_CHARS", self.max_script_chars), ("MAX_PARTS", self.max_parts)):
            if type(value) is not int or value <= 0:
                raise ValueError(f"{name} must be a positive integer.")
        local = provider == "keybert" or (provider == "gemini" and self.keybert_fallback)
        times = {"ANALYSIS_TIMEOUT_SECONDS": self.analysis_timeout_seconds}
        if provider in REMOTE_PROVIDERS:
            times[f"{provider.upper()}_TIMEOUT_SECONDS"] = self.openai_timeout_seconds
            times[f"{provider.upper()}_ATTEMPT_TIMEOUT_SECONDS"] = self.attempt_timeout_seconds
        if local:
            times["KEYBERT_TIMEOUT_SECONDS"] = self.keybert_timeout_seconds
            if not isinstance(self.keybert_model_path, str) or not self.keybert_model_path.strip():
                raise ValueError("KEYBERT_MODEL_PATH must be a non-empty local path.")
        for name, value in times.items():
            if type(value) not in (int, float) or value <= 0 or (type(value) is float and not math.isfinite(value)):
                raise ValueError(f"{name} must be a finite positive number.")
        if self.analysis_timeout_seconds >= PHONE_ANALYSIS_READ_TIMEOUT_SECONDS:
            raise ValueError("ANALYSIS_TIMEOUT_SECONDS must be less than the phone read timeout (60 seconds).")

        budget = self.openai_timeout_seconds if provider in REMOTE_PROVIDERS else 0
        if local:
            budget += self.keybert_timeout_seconds
        if budget > self.analysis_timeout_seconds and not math.isclose(budget, self.analysis_timeout_seconds):
            raise ValueError("Active analyzer timeouts must fit within ANALYSIS_TIMEOUT_SECONDS.")

        if provider in REMOTE_PROVIDERS:
            label = f"{provider.upper()}_BASE_URL"
            try:
                parsed = urlsplit(self.openai_base_url)
                port = parsed.port
                valid = (
                    parsed.scheme in {"http", "https"} and bool(parsed.hostname)
                    and not any(c.isspace() for c in self.openai_base_url)
                    and parsed.username is None and parsed.password is None
                    and not parsed.query and not parsed.fragment
                    and not parsed.path.rstrip("/").endswith("/chat/completions")
                    and (port is None or 1 <= port <= 65535)
                )
            except (TypeError, ValueError):
                valid = False
            if not valid:
                raise ValueError(f"{label} must be an HTTP(S) API root without credentials, query or /chat/completions.")


GEMINI_DEFAULT_FALLBACKS = "gemini-2.5-flash,gemini-3-flash-preview"


def _model_list(raw: str, primary: str) -> tuple[str, ...]:
    """쉼표 목록에서 빈 값·주 모델·중복을 뺀다."""
    out: list[str] = []
    for name in raw.split(","):
        name = name.strip()
        if name and name != primary and name not in out:
            out.append(name)
    return tuple(out)


def _float_env(name: str, default: str) -> float:
    try:
        return float(os.getenv(name, default))
    except ValueError:
        raise ValueError(f"{name} must be a finite positive number.") from None


def _int_env(name: str, default: str) -> int:
    try:
        return int(os.getenv(name, default))
    except ValueError:
        raise ValueError(f"{name} must be a positive integer.") from None


def _bool_env(name: str, default: str) -> bool:
    values = {"true": True, "1": True, "yes": True, "false": False, "0": False, "no": False}
    raw = os.getenv(name, default).strip().lower()
    if raw not in values:
        raise ValueError(f"{name} must be true/false, 1/0 or yes/no.")
    return values[raw]


def load_settings() -> Settings:
    provider = os.getenv("ANALYZER_PROVIDER", "mock").strip().lower()
    defaults = {
        "openai": ("OPENAI", "https://api.openai.com/v1", "gpt-4o-mini", ""),
        "omniroute": ("OMNIROUTE", "http://127.0.0.1:20128/v1", "", ""),
        "gemini": (
            "GEMINI", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-3.1-flash-lite", GEMINI_DEFAULT_FALLBACKS,
        ),
    }
    if provider not in REMOTE_PROVIDERS | {"mock", "keybert"}:
        raise ValueError("ANALYZER_PROVIDER must be mock, keybert, openai, omniroute or gemini.")
    prefix, default_url, default_model, default_fallbacks = defaults.get(provider, defaults["openai"])
    remote = provider in REMOTE_PROVIDERS
    fallback = _bool_env("KEYBERT_FALLBACK", "true") if provider == "gemini" else False
    local = provider == "keybert" or fallback
    mode = os.getenv(f"{prefix}_JSON_MODE", "json_object").strip().lower() if remote else "json_object"
    if mode not in {"json_object", "prompt"}:
        raise ValueError(f"{prefix}_JSON_MODE must be json_object or prompt.")
    model = os.getenv(f"{prefix}_MODEL", default_model).strip() if remote else ""
    return Settings(
        analyzer_provider=provider,
        openai_api_key=os.getenv(f"{prefix}_API_KEY", "").strip() if remote else "",
        openai_base_url=os.getenv(f"{prefix}_BASE_URL", default_url).strip().rstrip("/") if remote else default_url,
        openai_model=model,
        openai_timeout_seconds=_float_env(f"{prefix}_TIMEOUT_SECONDS", "45") if remote else 45.0,
        analysis_timeout_seconds=_float_env("ANALYSIS_TIMEOUT_SECONDS", "55"),
        keybert_fallback=fallback,
        keybert_model_path=os.getenv("KEYBERT_MODEL_PATH", DEFAULT_KEYBERT_MODEL_PATH).strip() if local else DEFAULT_KEYBERT_MODEL_PATH,
        keybert_timeout_seconds=_float_env("KEYBERT_TIMEOUT_SECONDS", "10") if local else 10.0,
        max_script_chars=_int_env("MAX_SCRIPT_CHARS", "20000"),
        max_parts=_int_env("MAX_PARTS", "10"),
        json_response_format=mode == "json_object",
        fallback_models=_model_list(os.getenv(f"{prefix}_FALLBACK_MODELS", default_fallbacks), model) if remote else (),
        attempt_timeout_seconds=_float_env(f"{prefix}_ATTEMPT_TIMEOUT_SECONDS", "15") if remote else 15.0,
    )
