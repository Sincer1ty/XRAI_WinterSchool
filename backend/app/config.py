"""환경 변수 설정. 실제 키는 backend/.env 에만 둔다 (커밋 금지)."""

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    analyzer_provider: str
    openai_api_key: str
    openai_base_url: str
    openai_model: str
    openai_timeout_seconds: float
    max_script_chars: int
    max_parts: int


def load_settings() -> Settings:
    return Settings(
        analyzer_provider=os.getenv("ANALYZER_PROVIDER", "mock").strip().lower(),
        openai_api_key=os.getenv("OPENAI_API_KEY", "").strip(),
        openai_base_url=os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
        openai_model=os.getenv("OPENAI_MODEL", "gpt-4o-mini").strip(),
        openai_timeout_seconds=float(os.getenv("OPENAI_TIMEOUT_SECONDS", "45")),
        max_script_chars=int(os.getenv("MAX_SCRIPT_CHARS", "20000")),
        max_parts=int(os.getenv("MAX_PARTS", "10")),
    )
