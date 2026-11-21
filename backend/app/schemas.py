"""대본 분석 API의 요청·응답 형식."""

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
    analyzer: str = Field(..., description="분석에 사용한 방식 (mock / openai)")
    parts: list[ScriptPart] = Field(..., min_length=1)


class ErrorBody(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorBody
