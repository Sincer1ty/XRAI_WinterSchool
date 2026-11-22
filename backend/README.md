# PC 서버 (FastAPI)

현재 구현:

- **파트 1. 대본 입력과 준비** — 폰에서 보낸 대본을 파트·키워드·힌트로 분석합니다. 대본을 새로 생성하지는 않습니다.
- **파트 3. 파트 매칭·멈춤 힌트** — 인식 문장으로 현재 파트를 추정하고, 멈춤 때 띄울 힌트를 고릅니다.
  발표 중에는 LLM을 호출하지 않고 준비 단계의 키워드만 씁니다.
- **파트 4. 전달 판단** — 힌트를 띄운 뒤의 문장으로 힌트 내용을 말했는지 판단합니다 (폰이 숨김).

## 분석 기한·설정 검증 (2026-11-22)

Python 3.11 이상이 필요하다. asyncio.timeout으로 HTTPX의 청크별 읽기 제한과 별도로 비동기 대기의 경과 시간을 제한한다.

| 범위 | 설정 | 기본값 |
|---|---|---|
| 대체 모델이 남은 원격 시도 하나 | GEMINI_ATTEMPT_TIMEOUT_SECONDS | 15초 |
| 주·대체 원격 모델 모두 | GEMINI_TIMEOUT_SECONDS | 45초 |
| 로컬 KeyBERT | KEYBERT_TIMEOUT_SECONDS | 10초 |
| 원격·로컬을 포함한 분석 전체 | ANALYSIS_TIMEOUT_SECONDS | 55초 |
| 폰 분석 응답 읽기 | ScriptAnalysisClient.readTimeoutMs | 60초 |

- OpenAI·OmniRoute도 제공자별 TIMEOUT_SECONDS·ATTEMPT_TIMEOUT_SECONDS를 사용한다. 마지막 모델은 남은 원격 시간을 사용하고 각 시도는 남은 시간으로 제한한다.
- 활성 분석기 시간 합계는 ANALYSIS_TIMEOUT_SECONDS 이하여야 하며, 전체 기한은 기존 폰 읽기 대기 60초보다 작아야 한다. Gemini 로컬 대체를 끄면 원격 시간만 합산한다.
- 전체 기한 초과는 HTTP 504 analysis_timeout이다. 원격 한도 초과의 llm_timeout, 로컬 한도 초과의 keybert_timeout은 유지한다. 실패한 요청을 준비 결과로 저장하지 않는다.
- 기한 안에 반환된 실제 분석 출처(keybert 등)와 LLM 대기 시간은 같은 Task의 ContextVar로 보존한다.
- 시간의 0·음수·NaN·무한대, 최대 글자·파트 수의 0·음수·비정수, 불리언 오타, 잘못된 URL·포트를 시작 시 거부한다. 오류 메시지에는 설정 이름만 쓰고 입력값·키·주소는 넣지 않는다.
- KEYBERT_FALLBACK은 true/false, 1/0, yes/no만 허용한다. mock·keybert 모드는 사용하지 않는 원격 설정을 읽지 않는다.
- 키·모델 누락은 기존처럼 요청 시 503이며, 로컬 모델 파일 준비 여부도 분석 시 확인한다.
- 폰의 두 HTTP 클라이언트는 연결·읽기 제한을 0 또는 음수로 생성할 수 없다. 0은 플랫폼에서 무제한 대기를 뜻하기 때문이다. 기본값은 유지한다.
- Python 기한은 이벤트 루프의 취소가 처리될 때 적용된다. 동기 CPU 처리·취소 정리 시간까지 물리적으로 강제 종료하는 기한은 아니다. KeyBERT 작업 스레드는 응답 기한 뒤에도 남을 수 있고 기존 잠금으로 중복 실행을 막는다.
- 폰 읽기 제한은 데이터가 들어오지 않는 시간 기준이다. 폰의 전체 요청 경과 시간·블로킹 I/O 즉시 중단을 보장하지 않는다. 서버의 전체 기한은 폰 왕복 시간 보장이 아니다.

사용자 실행 (backend 폴더):

~~~powershell
.\.venv\Scripts\python -m pytest tests/test_config.py tests/test_deadlines.py tests/test_fallback.py tests/test_keybert_fallback.py tests/test_gemini.py tests/test_omniroute.py tests/test_analyze.py -q
~~~

Android 폴더:

~~~powershell
.\gradlew.bat --console=plain testDebugUnitTest --tests "com.rokid.renewcxrlsample.script.*" --tests "com.rokid.renewcxrlsample.presentation.*"
~~~

실제 .env는 변경하지 않았다. 빌드·테스트·설치·실제 API·로컬 모델 실행은 사용자 확인 필요다.

## 파트 1: Gemini API 키 연결

폰 → PC FastAPI(:8000) → Google Gemini → 파트·키워드·힌트 검증 → 폰.
Google의 OpenAI 호환 엔드포인트를 기존 HTTP 분석기에 연결한다.

1. https://aistudio.google.com/apikey 에서 Gemini API 키를 준비한다.
2. backend/.env의 GEMINI_API_KEY에 키를 입력한다. 기존 파일을 예제로 덮어쓰지 않는다.

~~~dotenv
ANALYZER_PROVIDER=gemini
GEMINI_API_KEY=<발급받은 Gemini 키>
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
GEMINI_MODEL=gemini-3.1-flash-lite
GEMINI_FALLBACK_MODELS=gemini-2.5-flash,gemini-3-flash-preview
GEMINI_ATTEMPT_TIMEOUT_SECONDS=15
GEMINI_TIMEOUT_SECONDS=45
~~~

기본 모델은 gemini-3.1-flash-lite다. 2026-11-21 실호출에서 gemini-3.8-flash와
gemini-flash-latest는 짧은 요청에도 30~90초 동안 응답이 없었고, gemini-3.1-flash-lite는
대본 분석을 약 2초에 반환했다. 팀 최종 선정은 별도이며 GEMINI_MODEL로 변경할 수 있다.

**대체 모델**: Gemini 모델별 혼잡은 시간에 따라 바뀐다 (같은 날 3.1-flash-lite도 503·12초 지연이 있었고, 2026-11-22에는 같은 요청에 503과 성공을 번갈아 반환했다).
주 모델이 아래 이유로 실패하면 GEMINI_FALLBACK_MODELS 순서대로 다시 시도한다.

| 다음 모델로 넘어감 | 바로 실패 (모든 모델에서 같음) |
|---|---|
| 시간 초과, HTTP 404·429·500·502·503·504, 응답 형식 오류 | HTTP 400·401·403 (키·요청), 인터넷 연결 실패 |

- 대체 모델이 남아 있으면 모델 1개당 GEMINI_ATTEMPT_TIMEOUT_SECONDS(15초)까지만 기다린다.
- GEMINI_TIMEOUT_SECONDS(45초)는 원격 모델 시도 전체의 한도다. 마지막 모델은 남은 시간을 모두 쓴다.
- Gemini 대체 모델까지 재시도 가능한 오류로 실패하면 KEYBERT_FALLBACK=true일 때 로컬 KeyBERT를 사용한다. 끈 경우 마지막 오류와 시도한 모델을 안내한다.
- 어떤 모델로 성공했는지 서버 콘솔에 남는다 (analysis used fallback model ...).
- GEMINI_FALLBACK_MODELS= 처럼 비우면 주 모델만 쓴다.
키는 PC의 .env에만 입력한다. 폰에는 PC 서버의 :8000 주소를 입력한다.

3. 기존 서버를 Ctrl+C로 종료한 뒤 backend 폴더에서 재시작한다.
터미널 환경 변수는 .env보다 우선하므로 이전 제공자 설정을 제거한다.

~~~powershell
Remove-Item Env:ANALYZER_PROVIDER -ErrorAction SilentlyContinue
.\.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
~~~

4. 폰에서 대본을 다시 분석하고 파트·키워드·힌트를 확인한다.
서버 재시작 시 이전 분석 결과는 메모리에서 사라진다.
분석 버튼을 누르면 대본이 Gemini로 전송된다.

/health의 analyzer=gemini는 설정 선택을 뜻하며 실제 API 호출 성공을 뜻하지 않는다.
키·모델이 없으면 503 오류로 처리하며 다른 제공자나 mock으로 자동 전환하지 않는다.
Gemini 요청에는 parts JSON 스키마를 보내며, 응답도 기존 규칙으로 검증한다.
STT와 발표 중 키워드 판단은 기존 구현을 유지한다.

| 오류 | 확인할 항목 |
|---|---|
| 503 analyzer_not_configured | GEMINI_API_KEY·GEMINI_MODEL 누락 |
| 502 llm_unreachable | PC 인터넷 연결·API 주소 |
| 502 llm_failed, HTTP 400/401/403 | 키 유효성·모델 권한·요청 옵션 |
| 502 llm_failed, HTTP 404 | 모델 ID·API 주소 |
| 502 llm_failed, HTTP 429 | 호출 한도·할당량 |
| 502 analysis_invalid_format | 모델의 parts JSON 형식 |
| 504 llm_timeout | API 응답 지연·시간 제한 |

사용자 실행 테스트: .\.venv\Scripts\python -m pytest tests/test_gemini.py tests/test_analyze.py tests/test_fallback.py -q
2026-11-21: 실제 Gemini 호출로 대본 분석과 폰 앱의 준비 완료까지 확인했다.
공식 API 문서: https://aistudio.google.com/docs/openai
사용 가능한 모델 ID 확인: GEMINI_BASE_URL 뒤에 /models를 붙여 GET 요청한다 (Authorization: Bearer 키).
목록에 있어도 응답이 지연되는 모델이 있으므로, 바꾼 뒤에는 짧은 분석 요청으로 응답 시간을 확인한다.

## 파트별 대본 본문 보존 (2026-11-22)

- 분석 응답의 각 파트에 body를 추가했다. hint는 첫 구절, body는 카드에 보여줄 전체 원문이다.
- 제목으로 정해진 파트와 mock·KeyBERT 결과는 서버가 나눈 원문 본문을 연결한다.
- 제목 없는 대본의 여러 AI 파트에는 start_quote(파트 시작 원문 구절)를 요청한다. 서버가 원문 위치를 확인하고 다음 파트 시작까지 잘라 body로 돌려준다.
- 시작 구절이 없거나 원문에 없거나 반복돼 위치를 특정할 수 없으면 analysis_invalid_format으로 처리한다. 첫 부분 누락·순서 역전도 거부한다.
- 제목 없는 대본이 한 파트이면 전체 입력을 body로 사용한다. AI가 본문을 다시 생성하거나 요약하지 않는다.
- 파트의 키워드는 해당 본문에서 확인한다. 합쳐진 파트의 중간 제목은 본문에 보존하되 발화 키워드 후보에서는 제외한다.
- 제목·힌트의 서버 30자 잘림과 생략 기호 추가를 제거했다. 키워드 길이 제한은 기존 매칭 규칙을 유지한다.
- 폰 분석 카드에는 파트 제목, 대본 본문, 키워드를 구분해 표시한다. 본문의 줄바꿈을 유지하고 표시 줄 수를 제한하지 않는다.
- 이전 서버 응답에 body가 없으면 짧은 hint를 전체 대본으로 대신 표시하지 않고 서버 업데이트·재분석을 안내한다.
- 서버와 앱을 함께 업데이트하고 대본을 다시 분석한다. 서버 재시작으로 기존 준비 결과가 지워지는 동작은 기존과 같다.

사용자 실행:

~~~powershell
# backend 폴더
.\.venv\Scripts\python -m pytest tests/test_script_body.py tests/test_analyze.py tests/test_gemini.py tests/test_keybert_fallback.py -q
~~~

긴 제목·30자 이상 본문·중간 줄바꿈·마지막 문장을 확인한다. 여러 파트와 로컬 분석에서도 원문이 빠지지 않는지 확인한다.
빌드·테스트·실제 API·KeyBERT 실행·휴대폰 화면은 사용자 확인 필요다.
이번 단계는 본문 보존과 카드 내용 구분이며 화면별 반응형 배치·스크롤 개선은 다음 단계다.

## Gemini 실패 시 KeyBERT 로컬 분석 (2026-11-22)

- Gemini 주·대체 모델의 재시도 가능한 오류(429·혼잡·시간 초과·응답 형식 오류 등)가 모두 남으면 로컬 KeyBERT로 전환한다.
- HTTP 400·401·403, API 키·모델 누락, 인터넷 연결 실패는 기존 오류로 안내한다.
- Gemini 정상 응답 때는 로컬 모델을 불러오지 않는다. 로컬 모델은 한 번 로드해 재사용한다.
- 실제 결과의 analyzer는 gemini 또는 keybert다. /health의 analyzer는 설정된 주 분석기를 뜻한다.
- 폰에는 로컬 분석 안내를 표시한다. 결과를 확인하고 준비를 확정하는 기존 흐름을 유지한다.
- KeyBERT는 제목·문단 구분, 원문 후보 추출, 기존 결과 검증을 재사용하고 후보의 의미 관련도를 평가한다.
- 제목이 있으면 제목별 본문, 없으면 문단별 본문을 사용한다. 최대 파트 수를 넘으면 기존 병합 규칙을 따른다.
- 조사 제거는 기존 규칙을 사용한다. 긴 파트는 임베딩 모델의 입력 길이 제한을 받으며 실제 대본 품질 확인이 필요하다.

사용자가 backend 폴더에서 준비한다. 아래 설치·다운로드·테스트는 AI가 실행하지 않았다.

~~~powershell
.\.venv\Scripts\python -m pip install -r requirements-keybert.txt
.\.venv\Scripts\python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2').save('models/keybert')"
~~~

첫 모델 준비에는 인터넷과 디스크 공간이 필요하다. 이후 분석은 저장한 모델만 사용하며 모델을 자동 다운로드하지 않는다.
backend/models는 Git에서 제외한다. 기존 .env의 API 키는 보존한다.

~~~dotenv
KEYBERT_FALLBACK=true
KEYBERT_TIMEOUT_SECONDS=10
# KEYBERT_MODEL_PATH는 생략하면 backend/models/keybert
~~~

- Gemini 기본 45초와 로컬 기본 10초의 합계를 폰 분석 대기 60초보다 짧게 둔다. 설정 변경 시 합계와 실제 처리 시간을 확인한다.
- 서버 이벤트 루프 밖에서 로컬 분석한다. 시간 초과 뒤 CPU 작업이 남아 있으면 중복 로컬 작업은 503 keybert_busy로 안내한다.
- 모델·패키지 미준비: 503 keybert_not_ready. 로컬 시간 초과: 504 keybert_timeout. 일반 분석 실패: 503 keybert_failed.
- Gemini 없이 직접 확인하려면 .env에서 ANALYZER_PROVIDER=keybert로 설정하고 서버를 재시작한다.
- 자동 대체를 끄려면 KEYBERT_FALLBACK=false로 설정한다.

사용자 실행:

~~~powershell
.\.venv\Scripts\python -m pytest tests/test_keybert_fallback.py tests/test_fallback.py tests/test_delivery_judge.py -q
~~~

위 테스트는 가짜 분석기·HTTP 응답으로 대체 조건과 출처·키워드 규칙을 확인한다. 실제 모델 품질을 증명하지 않는다.
로컬 의존성이 없으면 sklearn이 필요한 후보 선별 테스트는 건너뛴다. 설치 후 다시 실행한다.
실제 모델은 ANALYZER_PROVIDER=keybert에서 대본을 분석해 키워드가 원문·파트 순서와 맞는지 확인한다.

공식 자료: [KeyBERT](https://maartengr.github.io/KeyBERT/), [다국어 모델](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2).

## 실행 (Windows PowerShell)

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
if (-not (Test-Path .env)) { Copy-Item .env.example .env } # 기존 키 보존
.\.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- 폰과 PC를 같은 Wi-Fi에 연결하고, 앱의 `PC 서버 주소`에 `http://<PC IP>:8000`을 입력합니다.
  PC IP는 `ipconfig`의 IPv4 주소입니다. Windows 방화벽에서 Python의 개인 네트워크 접근을 허용해야 할 수 있습니다.
- 동작 확인: 브라우저에서 `http://localhost:8000/health`, API 문서는 `http://localhost:8000/docs`.
- 테스트: `.\.venv\Scripts\python -m pytest -q`

## 팀원 노트북에서 실행

- 저장소를 받은 뒤 위 실행 절차로 가상환경과 의존성을 준비한다.
- .env.example을 .env로 복사하고 본인의 GEMINI_API_KEY를 입력한다.
  실제 .env는 Git에서 제외되므로 저장소를 내려받아도 키는 전달되지 않는다.
- 폰의 PC 서버 주소를 해당 노트북의 IPv4 주소와 8000 포트로 변경한다.
- 구현 범위와 미검증 항목은 [구현하기](../docs/구현하기.md)를 따른다.
  서버 시작과 실제 Gemini 분석 성공은 별도로 확인한다.

## 분석 방식

| `ANALYZER_PROVIDER` | 동작 |
|---|---|
| `gemini` | Gemini API 직접 호출 (GEMINI_API_KEY, GEMINI_MODEL). .env.example의 선택값. |
| `omniroute` | 기존 게이트웨이 연결 옵션 (OMNIROUTE_*). |
| keybert | 미리 준비한 로컬 다국어 임베딩으로 키워드를 선별한다. 패키지·모델 준비 필요. |
| `mock` (.env 미설정 기본) | API 키 없이 규칙으로 분석. 마크다운 `#` 제목이 있으면 제목 단위, 없으면 빈 줄 문단 단위로 나눕니다. |
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
    { "index": 1, "title": "문제 소개", "keywords": ["발표 불안"], "hint": "안녕하세요", "body": "안녕하세요. 발표 불안 문제를 이야기하겠습니다." }
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
| 503 | `analyzer_not_configured` | 선택한 LLM의 API 키 또는 모델 누락 |
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

- 사용자 결정 (2026-11-22): 표시한 hint_keywords 중 하나 이상을 말하면 전달. hint 문구만 말하거나 발화를 재개한 것만으로는 전달하지 않습니다.
- 의역·부정 표현은 구분하지 못합니다. 준비되지 않은 대본이면 `409 script_not_prepared`.

### `GET /health`

`{"status": "ok", "analyzer": "mock"}`

## 앞으로 추가할 범위

PPT 현재 페이지, 최대 표시 시간·재표시, WebSocket·재접속, 복기 결과. (음성 인식·멈춤 감지는 폰 앱이 담당)
자세한 범위는 [기능 문서](../docs/features.md), [구조](../docs/architecture.md), [분업](../docs/team-work.md)을 확인하세요.
