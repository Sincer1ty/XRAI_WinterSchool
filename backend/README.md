# PC 서버 (FastAPI)

현재 구현:

- **파트 1. 대본 입력과 준비** — 폰에서 보낸 대본을 파트·키워드·힌트로 분석합니다. 대본을 새로 생성하지는 않습니다.
- **파트 3. 파트 매칭·멈춤 힌트** — 인식 문장으로 현재 파트를 추정하고, 멈춤 때 띄울 힌트를 고릅니다.
  발표 중에는 LLM을 호출하지 않고 준비 단계의 키워드만 씁니다.
- **파트 4. 전달 판단** — 힌트를 띄운 뒤의 문장으로 힌트 내용을 말했는지 판단합니다 (폰이 숨김).

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
| 409 | `script_not_prepared` | 매칭·멈춤 요청의 대본이 서버에 준비되지 않음 |
| 413 | `script_too_long` | `MAX_SCRIPT_CHARS` 초과 |
| 422 | `invalid_request` | 요청 JSON 형식 오류 |
| 502 | `analysis_invalid_format` | AI 응답이 약속한 형식이 아님 |
| 502 | `llm_failed` / `llm_unreachable` | AI 호출 실패 |
| 503 | `analyzer_not_configured` | `openai` 방식인데 API 키 없음 |
| 504 | `llm_timeout` | AI 응답 시간 초과 |

### `POST /api/v1/parts/match` · `POST /api/v1/hints/pause`

요청 (둘 다 같음)

```json
{ "script_sha256": "<분석 응답의 script_sha256>", "text": "폰 STT가 인식한 최근 문장" }
```

성공 `200`

```json
{
  "script_sha256": "...",
  "stt_text": "폰 STT가 인식한 최근 문장",
  "status": "matched",
  "part_index": 1,
  "matched_keywords": ["발표 불안"],
  "hint": "대본",
  "hint_kind": "remaining_keywords",
  "hint_part_index": 1,
  "hint_keywords": ["대본"]
}
```

- `status`: `matched`(한 파트로 추정) / `unknown`(키워드 없음) / `ambiguous`(여러 파트 동점). 미확인은 확정하지 않으며 `part_index`가 `null`입니다.
- 추정 방식: 각 파트 키워드가 문장에 몇 개 나왔는지 공백을 무시하고 셉니다. 의역·동의어는 잡지 못합니다.
- `match`는 `hint` 관련 값이 항상 `null`입니다. `pause`만 힌트를 고릅니다.
- 멈춤 힌트 기본 정책 (**팀 확정 필요**): 현재 파트에서 아직 안 나온 키워드 → 없으면 다음 파트의 `hint` → 마지막 파트면 힌트 없음.
- 분석한 대본은 서버 메모리에만 보관합니다. 서버를 재시작했거나 분석하지 않은 대본이면 `409 script_not_prepared`이며, 폰은 대본을 다시 분석해야 합니다.
- `hint_keywords`: 전달 판단에 쓸 키워드. 남은 키워드 힌트면 그 키워드, 다음 파트 힌트면 다음 파트의 키워드입니다.
- 중복 멈춤·발표 상태는 폰(`PresentationController`)이 관리합니다. 파트 순서 변경 정책은 미정입니다.

### `POST /api/v1/hints/delivery` (파트 4. 전달 판단)

요청

```json
{ "script_sha256": "...", "hint": "대본", "hint_keywords": ["대본"], "text": "힌트를 띄운 뒤 인식한 문장" }
```

성공 `200`

```json
{ "script_sha256": "...", "stt_text": "...", "delivered": true, "matched_keywords": ["대본"] }
```

- 기본 정책 (**팀 확정 필요**): `hint_keywords` 중 하나 이상 또는 `hint` 문구 전체를 말하면 전달. 말을 다시 시작한 것만으로는 전달이 아닙니다.
- 의역·부정 표현은 구분하지 못합니다. 준비되지 않은 대본이면 `409 script_not_prepared`.

### `GET /health`

`{"status": "ok", "analyzer": "mock"}`

## 앞으로 추가할 범위

PPT 현재 페이지, 최대 표시 시간·재표시, WebSocket·재접속, 복기 결과. (음성 인식·멈춤 감지는 폰 앱이 담당)
자세한 범위는 [기능 문서](../docs/features.md), [구조](../docs/architecture.md), [분업](../docs/team-work.md)을 확인하세요.
