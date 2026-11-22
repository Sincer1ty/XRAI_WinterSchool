# 샘플과 외부 의존성

Android 앱은 사용자가 받은 `CXRLSample`을 바탕으로 합니다. 기존 패키지와 주석을 유지하고 한국어 문구를 추가했습니다.
원본 샘플에 별도 LICENSE 파일은 발견되지 않았습니다. 이 저장소에서 임의의 오픈소스 라이선스를 지정하지 않았습니다.

- CXR-L: `com.rokid.cxr:client-l:1.0.4`, `https://maven.rokid.com/repository/maven-public/`에서 Gradle로 받습니다.
- Gradle Wrapper: 받은 샘플의 Wrapper와 고지 주석을 유지합니다.
- AndroidX·Compose·Kotlin 등 버전은 `android/gradle/libs.versions.toml`과 `android/app/build.gradle.kts`에 있습니다.

SDK AAR/JAR 배포 파일, 인증 정보와 실제 녹음은 저장소에 포함하지 않습니다.
Gradle Wrapper JAR와 샘플의 이미지 리소스는 프로젝트 빌드에 필요한 파일입니다.

## VAD·로컬 키워드 분석 의존성

- Android VAD WebRTC 2.0.10: [gkonovalov/android-vad](https://github.com/gkonovalov/android-vad), MIT. 내장 WebRTC 코드의 고지는 원본 배포물을 따른다.
- KeyBERT: [MaartenGr/KeyBERT](https://github.com/MaartenGr/KeyBERT), MIT.
- Sentence Transformers 및 로컬 모델: [sentence-transformers](https://github.com/huggingface/sentence-transformers), [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2).
- 모델 가중치는 저장소에 포함하지 않으며 backend/README.md에 사용자 준비 절차를 기록한다.
