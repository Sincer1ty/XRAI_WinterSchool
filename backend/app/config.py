"""환경 변수 설정. 실제 키는 backend/.env 에만 둔다 (커밋 금지)."""

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[1] / ".env")


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


def load_settings() -> Settings:
    provider = os.getenv("ANALYZER_PROVIDER", "mock").strip().lower()
    defaults = {
        "openai": ("OPENAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        "omniroute": ("OMNIROUTE", "http://127.0.0.1:20128/v1", ""),
        "gemini": ("GEMINI", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite"),
    }
    prefix, default_url, default_model = defaults.get(provider, defaults["openai"])
    base_url = os.getenv(
        f"{prefix}_BASE_URL",
        default_url,
    ).strip().rstrip("/")
    parsed = urlsplit(base_url)
    if provider in {"openai", "omniroute", "gemini"} and (
        parsed.scheme not in {"http", "https"} or not parsed.hostname
        or parsed.username or parsed.password or parsed.query or parsed.fragment
        or parsed.path.endswith("/chat/completions")
    ):
        raise ValueError(f"{prefix}_BASE_URL must be an HTTP(S) API root, without credentials or /chat/completions.")
    mode = os.getenv(f"{prefix}_JSON_MODE", "json_object").strip().lower()
    if mode not in {"json_object", "prompt"}:
        raise ValueError(f"{prefix}_JSON_MODE must be json_object or prompt.")
    return Settings(
        analyzer_provider=provider,
        openai_api_key=os.getenv(f"{prefix}_API_KEY", "").strip(),
        openai_base_url=base_url,
        # Credentials and model selection stay within the selected provider.
        openai_model=os.getenv(f"{prefix}_MODEL", default_model).strip(),
        openai_timeout_seconds=float(os.getenv(f"{prefix}_TIMEOUT_SECONDS", "45")),
        max_script_chars=int(os.getenv("MAX_SCRIPT_CHARS", "20000")),
        max_parts=int(os.getenv("MAX_PARTS", "10")),
        json_response_format=mode == "json_object",
    )
