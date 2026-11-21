# 함께 개발하는 방법

## 저장소 접근

공개 저장소는 누구나 내려받을 수 있습니다. 직접 브랜치를 올릴 팀원은 소유자가 GitHub의
Settings → Collaborators에서 계정을 초대하고, 팀원이 초대를 수락해야 합니다.
권한을 받기 전에는 fork에서 개발하고 Pull Request를 보낼 수도 있습니다.

## 작업 시작

`main`은 함께 빌드 가능한 기준으로 유지합니다. 역할별로 기능 브랜치를 만듭니다.

```powershell
git switch main
git pull --ff-only origin main
git switch -c feature/android-hint-input
```

PC 기능은 `feature/pc-ppt-page`, 웹 기능은 `feature/web-slide-editor`처럼 이름을 정합니다.
같은 파일을 수정해야 한다면 먼저 담당자와 범위를 맞춥니다.

## 변경 올리기

변경 내용을 먼저 확인하고 이번 작업에 해당하는 경로만 추가합니다.

```powershell
git status
git diff
git add android/app/src
git commit -m "feat: add manual hint input"
git push -u origin feature/android-hint-input
```

GitHub에서 Pull Request를 만들고 무엇을 바꿨는지, 어떻게 확인했는지 적습니다.
팀원 리뷰 후 `main`에 병합합니다. 강제 푸시는 공동 브랜치에서 사용하지 않습니다.

## 확인과 비밀정보

Android 변경은 `android/`에서 `gradlew.bat assembleDebug`를 실행합니다.
기기 기능은 사용한 기기·연결 조건과 결과를 PR에 적고, 실행하지 않았다면 그대로 표시합니다.
API 키, 인증 토큰, 개인 `local.properties`, 서명키, 녹음·실제 PPT 자료를 커밋하지 않습니다.
APK와 Gradle 캐시는 빌드 결과이므로 올리지 않습니다. Gradle Wrapper JAR와 앱 리소스는 빌드에 필요하므로 포함합니다.
