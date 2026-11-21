"""발표 힌트 PC 서버 — 파트 1: 대본 분석, 파트 3: 파트 매칭·멈춤 힌트, 파트 4: 전달 판단.

실행: backend 폴더에서 `uvicorn app.main:app --host 0.0.0.0 --port 8000`
"""

from __future__ import annotations

import hashlib
import logging
import time

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .analyzer import Analyzer, AnalyzerError, build_analyzer, llm_wait_ms
from .config import Settings, load_settings
from .delivery_judge import judge_delivery
from .hint_engine import hint_for_pause, match_part
from .schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    DeliveryRequest,
    DeliveryResponse,
    ErrorResponse,
    MatchRequest,
    MatchResponse,
    ScriptPart,
)

logger = logging.getLogger("xrai.backend")
if not logger.handlers:
    # uvicorn 기본 설정은 앱 로거의 INFO를 출력하지 않는다. 처리 시간 기록을 콘솔에 남긴다.
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(levelname)s:     %(message)s"))
    logger.addHandler(_handler)
    logger.setLevel(logging.INFO)


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def create_app(settings: Settings | None = None, analyzer: Analyzer | None = None) -> FastAPI:
    settings = settings or load_settings()
    analyzer = analyzer or build_analyzer(settings)

    app = FastAPI(title="XRAI 발표 힌트 서버", version="0.1.0")
    error_responses = {code: {"model": ErrorResponse} for code in (400, 413, 422, 502, 503, 504)}
    # ponytail: 메모리 보관, 서버 재시작 시 사라짐. 저장·삭제 정책은 파트 5에서 확정.
    prepared: dict[str, list[ScriptPart]] = {}

    @app.middleware("http")
    async def _timing(request: Request, call_next):
        # 서버 처리 시간과 LLM API 대기 시간을 분리해 응답 헤더·로그에 남긴다.
        # 폰은 왕복 시간에서 total을 빼 네트워크 시간을 구한다.
        started = time.perf_counter()
        response = await call_next(request)
        total = (time.perf_counter() - started) * 1000
        llm = getattr(request.state, "llm_ms", None)
        app_ms = total - (llm or 0)
        timing = f"app;dur={app_ms:.1f}, total;dur={total:.1f}"
        if llm is not None:
            timing = f"app;dur={app_ms:.1f}, llm;dur={llm:.1f}, total;dur={total:.1f}"
        response.headers["Server-Timing"] = timing
        logger.info(
            "%s %s %s server=%.1fms llm=%s",
            request.method, request.url.path, response.status_code, app_ms,
            "-" if llm is None else f"{llm:.1f}ms",
        )
        return response

    @app.exception_handler(RequestValidationError)
    async def _validation_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error(422, "invalid_request", "요청 형식이 올바르지 않습니다.")

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "analyzer": analyzer.name}

    @app.post("/api/v1/scripts/analyze", response_model=AnalyzeResponse, responses=error_responses)
    async def analyze_script(req: AnalyzeRequest, request: Request):
        script = req.script.replace("\r\n", "\n").strip()
        if not script:
            return _error(400, "empty_script", "대본이 비어 있습니다.")
        if len(script) > settings.max_script_chars:
            return _error(
                413,
                "script_too_long",
                f"대본이 너무 깁니다 (최대 {settings.max_script_chars}자).",
            )

        llm_wait_ms.set(None)
        try:
            parts = await analyzer.analyze(script)
        except AnalyzerError as e:
            logger.warning("analysis failed: %s %s", e.code, e.message)
            return _error(e.status_code, e.code, e.message)
        finally:
            request.state.llm_ms = llm_wait_ms.get()

        script_sha256 = hashlib.sha256(req.script.encode("utf-8")).hexdigest()
        prepared[script_sha256] = parts
        return AnalyzeResponse(
            # 폰은 자신이 보낸 원문(req.script)으로 해시를 계산해 비교한다.
            script_sha256=script_sha256,
            client_revision=req.client_revision,
            analyzer=analyzer.name,
            parts=parts,
        )

    match_errors = {code: {"model": ErrorResponse} for code in (409, 422)}

    def _not_prepared() -> JSONResponse:
        return _error(409, "script_not_prepared", "서버에 준비된 대본이 아닙니다. 대본을 다시 분석하세요.")

    @app.post("/api/v1/parts/match", response_model=MatchResponse, responses=match_errors)
    async def match(req: MatchRequest):
        parts = prepared.get(req.script_sha256)
        if parts is None:
            return _not_prepared()
        return match_part(req.script_sha256, parts, req.text)

    @app.post("/api/v1/hints/pause", response_model=MatchResponse, responses=match_errors)
    async def pause(req: MatchRequest):
        parts = prepared.get(req.script_sha256)
        if parts is None:
            return _not_prepared()
        return hint_for_pause(req.script_sha256, parts, req.text)

    @app.post("/api/v1/hints/delivery", response_model=DeliveryResponse, responses=match_errors)
    async def delivery(req: DeliveryRequest):
        if req.script_sha256 not in prepared:
            return _not_prepared()
        return judge_delivery(req.script_sha256, req.hint, req.hint_keywords, req.text)

    return app


app = create_app()
