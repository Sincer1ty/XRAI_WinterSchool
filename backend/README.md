# PC 서버 (FastAPI)

현재 구현: **파트 1. 대본 입력과 준비** — 폰에서 보낸 대본을 파트·키워드·힌트로 분석합니다.
대본을 새로 생성하지는 않습니다.

## 실행 (Windows PowerShell)

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env        # 필요하면 값 수정
.\.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- 폰과 PC를 같은 Wi-Fi에 연결하고, 앱의 `PC 서버 주소`에 `http://<PC IP>:8000`을 입력합니다.
  PC IP는 `ipconfig`의 IPv4 주소입니다. Windows 방화벽에서 Python의 개인 네트워크 접근을 허용해야 할 수 있습니다.
- 동작 확인: 브라우저에서 `http://localhost:8000/health`, API 문서는 `http://localhost:8000/docs`.
- 테스트: `.\.venv\Scripts\python -m pytest -q`

## 분석 방식

| `ANALYZER_PROVIDER` | 동작 |
|---|---|
| `mock` (기본) | API 키 없이 규칙으로 분석. 마크다운 `#` 제목이 있으면 제목 단위, 없으면 빈 줄 문단 단위로 나눕니다. |
| `openai` | OpenAI 호환 Chat Completions API 호출 (`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`). |

제공받은 AI API가 OpenAI 호환이 아니면 `app/analyzer.py`에 분석기를 추가하고 `build_analyzer`에 연결합니다.
실제 API 키는 `backend/.env`에만 넣고 커밋하지 않습니다.

## API

### `POST /api/v1/scripts/analyze`

요청

```json
{ "script": "발표 대본 전문", "client_revision": 3 }
```

성공 `200`

```json
{
  "script_sha256": "<요청한 script 원문의 UTF-8 SHA-256>",
  "client_revision": 3,
  "analyzer": "mock",
  "parts": [
    { "index": 1, "title": "문제 소개", "keywords": ["발표 불안"], "hint": "안녕하세요" }
  ]
}
```

- `parts`는 1개 이상이고, 각 파트의 `title`·`hint`는 비어 있지 않으며 `keywords`는 1~5개입니다.
- `client_revision`과 `script_sha256`은 폰이 늦게 도착한 이전 대본의 결과를 버리는 데 씁니다.

오류는 모두 `{"error": {"code": "...", "message": "..."}}` 형식입니다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | `empty_script` | 대본이 비어 있음 |
| 413 | `script_too_long` | `MAX_SCRIPT_CHARS` 초과 |
| 422 | `invalid_request` | 요청 JSON 형식 오류 |
| 502 | `analysis_invalid_format` | AI 응답이 약속한 형식이 아님 |
| 502 | `llm_failed` / `llm_unreachable` | AI 호출 실패 |
| 503 | `analyzer_not_configured` | `openai` 방식인데 API 키 없음 |
| 504 | `llm_timeout` | AI 응답 시간 초과 |

### `GET /health`

`{"status": "ok", "analyzer": "mock"}`

## 앞으로 추가할 범위

PPT 현재 페이지, 음성 인식·쉼 감지, 핵심 내용 전달 판단, 표시 타이머, 복기 결과.
자세한 범위는 [기능 문서](../docs/features.md), [구조](../docs/architecture.md), [분업](../docs/team-work.md)을 확인하세요.
