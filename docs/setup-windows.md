# Windows에서 Android 앱 실행

## 필요한 환경

- Git, Android Studio, Android SDK Platform-Tools.
- SDK Platform Android 36.1, Build-Tools 36.0.0. Android Studio SDK Manager에서 설치합니다.
- 샘플 Gradle Daemon은 JDK 21을 지정합니다. `gradle/gradle-daemon-jvm.properties`를 확인하세요. 필요한 JDK 자동 다운로드에는 인터넷이 필요합니다.
- Android 12 / API 31 이상 휴대폰. 안경 연결에는 호환되는 Hi Rokid 또는 Rokid AI가 필요합니다.

고정된 프로젝트 버전은 AGP 9.2.1, Gradle Wrapper 9.4.1, CXR-L `com.rokid.cxr:client-l:1.0.4`입니다.
SDK Maven 주소는 `https://maven.rokid.com/repository/maven-public/`이며 `android/settings.gradle.kts`에 있습니다.

## PowerShell 환경

다음은 Android Studio와 SDK가 기본 경로에 설치된 경우입니다. 다르게 설치했다면 실제 경로를 사용하세요.
Android Studio 내장 JDK는 Gradle 실행에 사용하며, Daemon JDK 21은 프로젝트 설정에 따라 선택됩니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
java -version
adb version
cd android
.\gradlew.bat --version
.\gradlew.bat --console=plain assembleDebug
```

APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
`local.properties`가 필요하면 각 PC에서 만들고 Git에는 올리지 않습니다. 프로젝트는 `ANDROID_HOME`으로도 SDK를 찾을 수 있습니다.
Windows에서 한글 경로 오류가 나면 저장소를 영문 경로에 다시 받으세요.

## 휴대폰 설치

갤럭시는 설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호 7번 → 개발자 옵션 → USB 디버깅을 켭니다.
USB 연결 후 휴대폰 잠금을 풀고 이 PC의 디버깅 연결을 허용합니다.

```powershell
adb devices -l
```

휴대폰이 `device`로 표시될 때 설치합니다. `<PHONE_SERIAL>`은 방금 출력된 실제 휴대폰 시리얼로 바꿉니다.
안경이 함께 연결되어 있으면 안경 시리얼을 선택하지 않도록 주의합니다.

```powershell
adb -s <PHONE_SERIAL> install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s <PHONE_SERIAL> shell am start -W -n com.rokid.renewcxrlsample/.activities.main.MainActivity
```

앱의 한국어 이름은 `로키드 연결 샘플`입니다. 한국어 리소스는 휴대폰 언어가 한국어일 때 적용됩니다.

## 안경 연결과 표시

1. Hi Rokid에서 안경을 연결합니다.
2. 샘플의 `연결 승인하기`를 눌러 권한을 확인합니다. 승인 완료 상태라면 선택 화면으로 이동합니다.
3. `안경 화면 모드`를 선택하고 안경 서비스와 블루투스가 연결됐는지 확인합니다.
4. `안경 화면 열기` → `문구 바꾸기` → `안경 화면 닫기`를 확인합니다.

`안경 앱 모드`는 별도의 안경용 앱을 전제로 합니다. 현재 첫 표시 시험은 `안경 화면 모드`를 사용합니다.
마이크·카메라·파일 권한은 샘플이 요청하는 내용을 읽고 사용할 기능에 맞춰 판단하세요.

## 확인한 상태

- 2026-11-21: Windows에서 샘플 빌드, Android API 36 휴대폰 설치·실행 성공.
- Hi Rokid `G1.17.1.1112`에서 승인 완료, 안경에 기본 영문 문구 출력 확인.
- 한국어 UI 적용 확인. 안경 한글 출력, 숨김 중 음성 유지, PPT·PC 통신은 추가 확인 대상입니다.

다른 기기·펌웨어·앱 버전에서도 같은 결과가 나는지는 각자 확인해야 합니다.
