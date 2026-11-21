# XRAI Winter School — 발표 힌트 안경

발표자가 잠시 말을 멈췄을 때, 현재 PPT 페이지에 맞는 짧은 힌트를 Rokid 안경에 표시하는 서비스입니다.
페이지별로 연습한 단서를 실전에서 활용하고, 발표 후 단서를 수정해 다음 발표에 반영합니다.

## 현재 동작하는 것

- 받은 CXR-L 샘플 기반 Android 앱 빌드·휴대폰 설치·실행.
- Hi Rokid 연결 승인과 안경에 기본 문구 표시. 안경의 `Hello World` 출력은 실기기로 확인했습니다.
- 한국어 버튼·안내 문구 190개. 휴대폰에서 한국어 UI 표시를 확인했습니다.
- 안경 테스트 문구를 `안녕하세요`, 갱신 문구를 `안녕하세요 로키드 <번호>`로 변경했습니다. 안경의 한글 렌더링은 추가 확인이 필요합니다.

PPT 연동, PC와 휴대폰 통신, 자동 힌트, AI 대본 생성, 핵심 내용 판단, 복기 화면은 아직 구현하지 않았습니다.

## 폴더 구성

```text
android/       # 현재 실행 가능한 Kotlin / CXR-L 앱
backend/       # PC 처리 프로그램 개발 위치 (안내 문서만 있음)
web/           # 준비·연습·복기 웹 개발 위치 (안내 문서만 있음)
docs/          # 기능 범위, 실행 방법, 구조, 분업과 확인 목록
```

Android 개발은 `android/`를 Android Studio에서 열거나 저장소 루트를 VS Code에서 엽니다.
기존 기본 프로젝트 MyApplication2 대신 이 저장소의 `android/` 코드를 협업 기준으로 사용합니다.

## 팀원이 처음 시작할 때

Windows에서는 한글과 공백이 없는 경로에 받는 것을 권장합니다. 받은 샘플은 한글 경로에서 Android Gradle Plugin의 경로 검사에 걸렸습니다.

```powershell
git clone https://github.com/Sincer1ty/XRAI_WinterSchool.git
cd XRAI_WinterSchool
```

1. [Windows 실행 안내](docs/setup-windows.md)에 따라 JDK·Android SDK를 준비합니다.
2. [구현할 기능](docs/features.md)과 [현재 구조](docs/architecture.md)를 읽습니다.
3. [분업·진행표](docs/team-work.md)에서 맡을 작업을 정하고 기능 브랜치를 만듭니다.

환경 변수를 설정한 뒤 Android 빌드:

```powershell
cd android
.\gradlew.bat --console=plain assembleDebug
```

## 협업 문서

- [구현할 기능과 미정 정책](docs/features.md)
- [Android 실행·기기 연결](docs/setup-windows.md)
- [구성 요소와 연결 방향](docs/architecture.md)
- [분업 제안과 통합 확인](docs/team-work.md)
- [브랜치·PR 작업 방법](CONTRIBUTING.md)
- [샘플·외부 의존성 출처](THIRD_PARTY_NOTICES.md)

API 키, 개인 SDK 경로, 서명키, 실제 녹음·발표 자료는 커밋하지 않습니다. SDK 라이브러리는 Gradle이 Maven 저장소에서 받습니다.
