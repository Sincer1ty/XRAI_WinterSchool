import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzer import OpenAICompatibleAnalyzer  # noqa: E402


@pytest.fixture(autouse=True)
def _no_retry_delay(monkeypatch):
    monkeypatch.setattr(OpenAICompatibleAnalyzer, "retry_delay_seconds", 0)
