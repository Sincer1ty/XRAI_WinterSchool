"""발표 힌트 PC 서버 — 파트 1: 대본 분석 API.

실행: backend 폴더에서 `uvicorn app.main:app --host 0.0.0.0 --port 8000`
"""

from __future__ import annotations

import hashlib
import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .analyzer import Analyzer, AnalyzerError, build_analyzer
from .config import Settings, load_settings
from .schemas import AnalyzeRequest, AnalyzeResponse, ErrorResponse

logger = logging.getLogger("xrai.backend")


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def create_app(settings: Settings | None = None, analyzer: Analyzer | None = None) -> FastAPI:
    settings = settings or load_settings()
    analyzer = analyzer or build_analyzer(settings)

    app = FastAPI(title="XRAI 발표 힌트 서버", version="0.1.0")
    error_responses = {code: {"model": ErrorResponse} for code in (400, 413, 422, 502, 503, 504)}

    @app.exception_handler(RequestValidationError)
    async def _validation_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error(422, "invalid_request", "요청 형식이 올바르지 않습니다.")

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "analyzer": analyzer.name}

    @app.post("/api/v1/scripts/analyze", response_model=AnalyzeResponse, responses=error_responses)
    async def analyze_script(req: AnalyzeRequest):
        script = req.script.replace("\r\n", "\n").strip()
        if not script:
            return _error(400, "empty_script", "대본이 비어 있습니다.")
        if len(script) > settings.max_script_chars:
            return _error(
                413,
                "script_too_long",
                f"대본이 너무 깁니다 (최대 {settings.max_script_chars}자).",
            )

        try:
            parts = await analyzer.analyze(script)
        except AnalyzerError as e:
            logger.warning("analysis failed: %s %s", e.code, e.message)
            return _error(e.status_code, e.code, e.message)

        return AnalyzeResponse(
            # 폰은 자신이 보낸 원문(req.script)으로 해시를 계산해 비교한다.
            script_sha256=hashlib.sha256(req.script.encode("utf-8")).hexdigest(),
            client_revision=req.client_revision,
            analyzer=analyzer.name,
            parts=parts,
        )

    return app


app = create_app()
