"""대본 분석·파트 매칭 API의 요청·응답 형식."""

from typing import Literal

from pydantic import BaseModel, Field


class AnalyzeRequest(BaseModel):
    script: str = Field(..., description="사용자가 입력한 발표 대본 전문")
    client_revision: int | None = Field(
        default=None,
        description="폰이 보낸 대본 수정 번호. 응답에 그대로 돌려주어 늦은 응답을 구분한다.",
    )


class ScriptPart(BaseModel):
    index: int = Field(..., ge=1, description="1부터 시작하는 파트 순서")
    title: str = Field(..., min_length=1, description="파트 제목")
    keywords: list[str] = Field(..., min_length=1, description="파트의 필수 키워드 (1개 이상)")
    hint: str = Field(..., min_length=1, description="안경에 띄울 짧은 힌트 (파트 첫 구절)")


class AnalyzeResponse(BaseModel):
    script_sha256: str = Field(..., description="분석한 대본(UTF-8)의 SHA-256 hex")
    client_revision: int | None = None
    analyzer: str = Field(..., description="분석에 사용한 방식 (mock / openai / omniroute / gemini)")
    parts: list[ScriptPart] = Field(..., min_length=1)


class MatchRequest(BaseModel):
    script_sha256: str = Field(..., description="분석 응답의 script_sha256. 서버가 준비한 대본과 같아야 한다.")
    text: str = Field(..., description="폰 STT가 인식한 최근 문장(원문)")


class MatchResponse(BaseModel):
    script_sha256: str
    stt_text: str = Field(..., description="요청한 STT 원문 (판단 결과와 구분)")
    status: Literal["matched", "unknown", "ambiguous"] = Field(
        ..., description="matched: 한 파트로 추정 / unknown: 근거 없음 / ambiguous: 여러 파트 동점"
    )
    part_index: int | None = Field(None, description="추정한 현재 파트. matched일 때만 값이 있다.")
    matched_keywords: list[str] = Field(default_factory=list)
    hint: str | None = Field(None, description="멈춤 응답에서만: 안경에 띄울 힌트")
    hint_kind: Literal["remaining_keywords", "next_part"] | None = None
    hint_part_index: int | None = Field(None, description="힌트가 속한 파트")
    hint_keywords: list[str] = Field(
        default_factory=list,
        description="전달 판단에 쓸 키워드. 이 중 하나를 말하면 힌트 내용이 전달된 것으로 본다.",
    )


class DeliveryRequest(BaseModel):
    script_sha256: str = Field(..., description="분석 응답의 script_sha256")
    hint: str = Field(..., description="안경에 띄운 힌트 문구 (멈춤 응답의 hint)")
    hint_keywords: list[str] = Field(default_factory=list, description="멈춤 응답의 hint_keywords")
    text: str = Field(..., description="힌트를 띄운 뒤 STT가 인식한 문장(원문)")


class DeliveryResponse(BaseModel):
    script_sha256: str
    stt_text: str = Field(..., description="요청한 STT 원문 (판단 결과와 구분)")
    delivered: bool = Field(..., description="힌트의 핵심 내용을 말했다고 판단하면 true")
    matched_keywords: list[str] = Field(default_factory=list)


class ErrorBody(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorBody
