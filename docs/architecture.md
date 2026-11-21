# 현재 구조와 개발 방향

## 현재 구현

`android/`는 받은 CXRLSample에 한국어 문구를 추가한 앱입니다.
휴대폰 앱이 CXR-L과 Hi Rokid를 통해 안경 화면에 접근합니다.

| 파일·폴더 | 역할 |
|---|---|
| `android/app/build.gradle.kts` | SDK 버전과 Android 빌드 설정 |
| `.../activities/main/MainViewModel.kt` | 연결 앱 확인과 승인 |
| `.../utils/CxrSessionGate.kt` | CXR 세션 생성·연결 |
| `.../link/CxrLinkConnectionHub.kt` | 서비스·블루투스 연결 상태 |
| `.../activities/session/SessionHubViewModel.kt` | 안경 화면 열기·갱신·닫기와 테스트 문구 |
| `.../activities/audio/AudioUsageViewModel.kt` | 샘플 음성 수신·저장 기능 |
| `android/app/src/main/res/values-ko/strings.xml` | 한국어 문구 |

`...`는 `android/app/src/main/java/com/rokid/renewcxrlsample`입니다.
음성·사진 등은 샘플 코드가 있지만 실기기 동작 확인을 완료한 기능으로 취급하지 않습니다.

## 제안한 최종 구성 — 아직 미구현

```text
Windows PowerPoint ─ 페이지 정보 ─┐
준비·연습·복기 웹 ─ 입력·설정 ───┤
                              PC 프로그램
                              ↕ 음성·힌트
                              Android 중계 앱
                              ↕ CXR-L / Hi Rokid
                              Rokid 안경
```

- Android: 연결, 안경 음성 수신, PC로 음성 전달, 표시·갱신·숨김 요청 처리.
- PC: PPT 페이지, 음성 인식·쉼 감지, AI 대본·전달 판단, 타이머·결과 저장.
- 웹: 페이지별 입력·대본 확정, 연습 설정, 실전 상태, 최소 복기.

PC는 Python/FastAPI, 웹은 HTML·CSS·JavaScript, PC↔폰은 WebSocket을 후보로 정했습니다.
서버와 메시지 형식은 아직 구현·확정하지 않았습니다. 담당자끼리 먼저 인터페이스를 합의하세요.
제공 AI API의 실제 음성 인식 지원 여부도 확인해야 합니다.

## 초기 기술 확인

- 힌트를 비우거나 숨기는 동안에도 안경 음성이 유지되는지.
- 실제 PowerPoint 슬라이드 쇼의 현재 페이지를 읽을 수 있는지.
- 안경 음성을 PC에서 받을 수 있는지, 지연과 오디오 형식은 어떤지.
- 핵심 내용 판단과 5초 이내 숨김을 연결할 수 있는지.
