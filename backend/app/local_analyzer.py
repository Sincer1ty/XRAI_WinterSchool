"""Gemini 사용 한도·혼잡·시간 초과 시 미리 준비한 로컬 KeyBERT 모델로 분석한다."""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from threading import Lock

from .analyzer import (
    Analyzer, AnalyzerError, KEYWORDS_MAX, KEYWORD_LEN_MAX, _first_phrase,
    _first_sentence, _keywords, _merge_to_limit, _split_sections, _spoken_body, analysis_source, normalize_parts,
)
from .config import Settings
from .hint_engine import _norm
from .schemas import ScriptPart

logger = logging.getLogger("xrai.backend.analyzer")
CANDIDATE_LIMIT = 100


class KeyBertAnalyzer:
    name = "keybert"

    def __init__(self, settings: Settings):
        self.settings = settings
        self._model = None
        self._lock = Lock()

    def _load_model(self):
        if self._model is None:
            path = Path(self.settings.keybert_model_path)
            if not (path / "modules.json").is_file():
                raise AnalyzerError(
                    "keybert_not_ready",
                    "로컬 KeyBERT 모델이 없습니다. backend/README.md의 모델 준비 절차를 실행하세요.",
                    503,
                )
            try:
                from keybert import KeyBERT
                from sentence_transformers import SentenceTransformer
                self._model = KeyBERT(model=SentenceTransformer(
                    str(path), device="cpu", local_files_only=True, trust_remote_code=False,
                ))
            except Exception as error:
                logger.warning("local KeyBERT model load failed: %s", type(error).__name__)
                raise AnalyzerError(
                    "keybert_not_ready", "KeyBERT 패키지와 로컬 모델 파일을 확인하세요.", 503,
                ) from error
        return self._model

    def _analyze(self, script: str) -> list[ScriptPart]:
        # 시간 제한으로 응답을 끝내도 실행 중인 CPU 작업은 계속될 수 있어 중복 실행을 막는다.
        if not self._lock.acquire(blocking=False):
            raise AnalyzerError("keybert_busy", "로컬 분석이 진행 중입니다. 잠시 후 다시 분석하세요.", 503)
        try:
            model = self._load_model()
            from sklearn.feature_extraction.text import CountVectorizer

            sections = _merge_to_limit(_split_sections(script), self.settings.max_parts)
            raw = []
            for heading, body in sections:
                source = body or heading or ""
                candidates = [
                    word for word in _keywords(_spoken_body(body), n=CANDIDATE_LIMIT)
                    if len(word) <= KEYWORD_LEN_MAX
                ]
                if not candidates:
                    raise AnalyzerError("analysis_invalid_format", "대본에서 짧은 키워드 후보를 찾지 못했습니다.")
                # 조사 제거 후에도 원문에 포함된 후보를 KeyBERT가 평가하게 한다.
                vectorizer = CountVectorizer(
                    vocabulary=candidates,
                    analyzer=lambda text: [word for word in candidates if _norm(word) in _norm(text)],
                    lowercase=False,
                )
                ranked = model.extract_keywords(
                    source, vectorizer=vectorizer, top_n=KEYWORDS_MAX, use_mmr=True,
                )
                keywords = [word for word, _ in ranked]
                keywords.sort(key=lambda word: _norm(source).find(_norm(word)))
                raw.append({
                    "title": heading or _first_sentence(source),
                    "hint": _first_phrase(source) or source,
                    "keywords": keywords,
                })
            return normalize_parts(raw, self.settings.max_parts, script, sections)
        except AnalyzerError:
            raise
        except Exception as error:
            logger.warning("local KeyBERT analysis failed: %s", type(error).__name__)
            raise AnalyzerError("keybert_failed", "로컬 KeyBERT 분석에 실패했습니다. 모델과 대본을 확인하세요.", 503) from error
        finally:
            self._lock.release()

    async def analyze(self, script: str) -> list[ScriptPart]:
        try:
            return await asyncio.wait_for(
                asyncio.to_thread(self._analyze, script), timeout=self.settings.keybert_timeout_seconds,
            )
        except TimeoutError as error:
            raise AnalyzerError("keybert_timeout", "로컬 KeyBERT 분석 시간이 초과되었습니다.", 504) from error


class KeyBertFallback:
    def __init__(self, primary: Analyzer, local: Analyzer):
        self.primary = primary
        self.local = local
        self.name = primary.name

    async def analyze(self, script: str) -> list[ScriptPart]:
        analysis_source.set(self.primary.name)
        try:
            return await self.primary.analyze(script)
        except AnalyzerError as error:
            if not error.retryable:
                raise
            logger.warning("Gemini models failed (%s); using local KeyBERT", error.code)
        parts = await self.local.analyze(script)
        analysis_source.set(self.local.name)
        return parts
