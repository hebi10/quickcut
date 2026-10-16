# Android 스마트 렌더링 엔진 도입 검토

확인일: 2026-10-16. 대상: SolveigMM Video Editing SDK의 Android 버전.

## 현재 결론

SolveigMM은 공식 제품 페이지에 Android 지원을 명시한다. 그러나 확인한 공개 다운로드 신청서에는 Windows x64와 Linux x64만 있고, 공개 문서 목록에도 Android SDK 문서가 없다. 현재 확보한 Android 바이너리, 샘플, API 문서 또는 평가용 라이선스는 없다. 따라서 이번 검토에서 실제 SDK 연결이나 Android 스마트 렌더링 성능 검증을 완료했다고 할 수 없다.

실제 통합에는 공급사의 Android SDK와 해당 사용에 맞는 라이선스 조건이 필요하다. 구매를 결정하거나 공급사에 문의를 전송하지 않았다. 공개 자료에서 찾지 못했다는 뜻이며 Android SDK가 존재하지 않는다는 뜻은 아니다. 검증되지 않은 API, 임시 성공 응답, 동작하지 않는 엔진 선택지는 추가하지 않는다.

## 공식 자료에서 확인한 범위

| 자료 | 확인한 내용 | Android 통합에 적용할 때의 한계 |
| --- | --- | --- |
| [SDK 제품 페이지](https://www.solveigmm.com/en/products/video-editing-sdk/) | Windows, Linux, macOS, Android 지원과 스마트 렌더링을 안내한다. FAQ는 플랫폼마다 기능이 다르다고 명시한다. | 공통 코덱 목록을 Android 지원 목록으로 간주할 수 없다. Android 최소 API, ABI, 패키지 및 API는 확인되지 않는다. |
| [SDK 다운로드 신청서](https://www.solveigmm.com/en/download-sdk/) | 제품 선택은 Windows x64와 Linux x64 두 가지다. 이메일과 프로젝트 설명을 요구한다. | 공개 Android 다운로드를 확보하지 못했다. 신청서를 제출하지 않았다. |
| [공개 문서 목록](https://www.solveigmm.com/en/support/doc/) | SDK 문서는 Windows와 Linux로 연결된다. | Android용 설치·빌드·API 문서는 이 목록에서 확인되지 않는다. |
| [Windows SDK 문서](https://www.solveigmm.com/en/support/doc/video-editing-sdk-for-windows/notices.html) | DLL, DirectShow, COM 기반 엔진과 코덱별 지원 범위를 설명한다. | Windows API를 Android JNI API로 추정해서 구현할 수 없다. |
| [Linux SDK 문서](https://www.solveigmm.com/en/support/doc/video-editing-sdk-for-linux/product-description.html) | GStreamer 플러그인, 동적 라이브러리, C++ API를 설명한다. 입력 매개변수가 기준 파일과 다르면 재인코딩한다고 명시한다. | Linux x64 패키지가 Android arm64에서 실행된다는 근거는 아니다. |
| [공식 Android 앱 TriMP4](https://www.solveigmm.com/en/products/trimp4/) | MP4/3GP 편집, 프레임 정확 편집, MP4의 HEVC 지원을 설명한다. | Android용 기술의 사례이지만 소비자 앱이다. SDK 제공 범위·지원 프로필·배포권을 대신 증명하지 않는다. |

SDK 제품 페이지는 연구·개발용 사용과 런타임 재배포 라이선스를 구분하며, 배포 비용이 형식·해상도·수량에 따라 달라진다고 안내한다. Android 평가판의 구체적인 사용 조건과 QuickCut APK 배포 조건은 별도 확인 대상이다. 무료 개발 안내를 무제한 APK 재배포 허가로 해석하지 않는다.

## 통합에 필요한 자료와 확인 질문

현재 QuickCut은 `minSdk 29`, `compileSdk/targetSdk 36`, Media3 1.11.1을 사용한다. 다음 자료가 확보되면 실제 샘플을 빌드하고 엔진 연결 여부를 결정할 수 있다.

1. **Android 패키지와 샘플:** arm64-v8a 라이브러리 또는 AAR, 헤더/JNI 인터페이스, 의존 라이브러리, Gradle/CMake 예제, 지원 NDK/최소 Android API, 16KB 페이지 크기 호환 여부. 에뮬레이터 평가를 위한 x86_64 제공 여부도 확인한다.
2. **평가 및 배포 조건:** 평가용 키와 만료·워터마크·출력 길이 제한, 오프라인 동작/활성화 여부, 개인용 APK와 공개·상업 배포 각각의 허용 범위, 코덱별 추가 비용, 로열티 산정 단위와 유지보수 비용. 공개 자료만으로 가격이나 구매 필요 범위를 확정하지 않는다.
3. **Android 전용 지원표:** 컨테이너와 영상·음성 코덱별로 원본 복사, 경계 부분 재인코딩, 전체 재인코딩을 구분한 표. 우선 MP4/MOV의 AVC, HEVC와 AAC를 확인하고 AV1, VP9, MKV, WebM 등은 별도 확인한다.
4. **정확한 경계 조건:** 닫힌 GOP·열린 GOP, B프레임·선행 프레임, IDR/CRA, 가변 프레임률, 긴 GOP, 임의 시작·끝 자르기, 여러 구간 연결, 음소거, 음성 지연·패딩·트랙 길이 차이의 처리 방식. 원래 선택 시각을 키프레임으로 옮기는 동작과 숨겨진 삭제 구간을 남기는 동작의 유무를 확인한다.
5. **화질·메타데이터:** AVC/HEVC 프로필·레벨·비트 심도·색차 샘플링, HDR10/HLG/HDR10+/Dolby Vision 보존 범위, 회전·색 공간·추가 트랙 지원. 지원되지 않는 경우 오류인지 변환인지, 전체 재인코딩으로 전환되는지 확인한다.
6. **실행 API:** 로컬 파일과 Android 파일 디스크립터/콘텐츠 URI 지원, 진행률·취소·오류·완료 콜백, 스레드 규칙, 임시 파일 요구량, 실제 복사/부분/전체 인코딩 결과를 판별하는 API나 로그. API에 근거가 없으면 처리 방식을 추정해 표시하지 않는다.

## 재현 가능한 평가 계획

아래는 **향후 SDK 평가 기준**이며 SolveigMM으로 통과한 결과가 아니다. 기존 테스트는 현재 QuickCut 엔진의 비교 기준으로 사용한다. 테스트 입력은 `scripts/android-emulator-check.sh`가 생성하며 생성 명령을 그대로 보존한다.

| 입력 또는 기존 테스트 | 자르기 조건 | 확인할 결과 |
| --- | --- | --- |
| `quickcut_exact_boundary.mp4`, `ExactTrimInstrumentedTest` | 1,950ms부터 원본 끝. 2초 전은 빨강, 이후는 파랑 | 첫 출력 프레임이 빨강이고 요청 시각이 2초로 이동하지 않는다. 음성과 마지막 프레임이 남고 길이 오차는 이 30fps 입력의 1프레임 이내다. |
| 같은 테스트의 기기 인코더로 만든 입력 | 500–4,500ms | 실제 부분 인코딩 결과를 확인한다. 이 특수 입력의 성공을 외부 인코더 영상 전체로 일반화하지 않는다. |
| `quickcut_keyframes.mp4` | 4,000ms부터 끝, 3,000ms부터 끝 | 정확한 키프레임 복사와 비키프레임 경계 처리를 구별한다. 3초를 4초로 바꾸지 않는다. |
| `quickcut_bframes.mp4`, `FastBFrameInstrumentedTest` | 4,000ms부터 끝, 3,000ms부터 끝, 4,000–7,000ms | PTS 순서가 뒤섞인 영상에서 첫·마지막 프레임, 전체 디코딩, 음성 동기화를 검사한다. 끝 자르기의 참조 프레임 누락도 검사한다. |
| `quickcut_open_gop.mp4`, 같은 테스트 | 4,000ms부터 끝 및 비키프레임 시작 | 잘라낸 앞부분을 참조하는 선행 프레임 때문에 깨짐·시각 이동·숨겨진 앞부분이 생기지 않는다. 현재 안전 거절 경로와 공급사 경계 재인코딩을 비교한다. |
| `quickcut_unequal_tail.mp4`, `FastTailInstrumentedTest` | 0–2,000ms. 음성은 약 0.5초, 버리는 뒷부분에는 B프레임 존재 | 짧은 음성이 영상 종료를 결정하지 않는다. 출력 영상 끝과 음성 길이 및 불필요한 전체 처리를 확인한다. |
| `quickcut_fixture.mp4`, 기존 무결성·일괄 저장 테스트 | 전체/부분/음소거, 여러 파일 연속 저장 | 원본 보존, 날짜, 정상 갤러리 등록 1회, 실패·취소 후 미완성 파일 정리, 실제 처리 결과 기록을 유지한다. |
| 별도 확보할 HEVC/HDR/VFR/긴 GOP 입력 | 동일한 시작·끝, 음소거 포함 | 지원표에 해당하는 형식만 평가한다. 색·HDR 메타데이터·회전·동기화 보존과 실제 처리 방식을 확인한다. 현재 위 생성 입력이 이 범위를 검증하지는 않는다. |
| 사용자가 문제를 겪은 원본 영상 | 앞 3초 삭제 및 사용자가 지정한 구간 | 실제 기기에서 현재 엔진과 비교한다. 현재 제공된 화면만으로 원본 코덱·GOP 구조를 재현할 수 없으며 원본 영상은 확보되지 않았다. |

기존 기준 검사는 JDK·Gradle·Android SDK·FFmpeg와 연결된 테스트 기기/에뮬레이터가 준비된 환경에서 저장소 루트의 `bash scripts/android-emulator-check.sh`로 실행한다. 이 스크립트는 앱 설치·업데이트, 입력 생성, 전체 계측 검사를 포함한다. 입력이 이미 생성된 경우 관련 계측 검사만 다시 실행할 수 있다.

```sh
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.ExactTrimInstrumentedTest,com.hebi.quickcut.media.FastBFrameInstrumentedTest,com.hebi.quickcut.media.FastTailInstrumentedTest
```

SDK 어댑터가 실제로 구현된 뒤에는 같은 입력과 요청 구간을 양쪽 엔진에 전달한다. 공급사 SDK 버전, 라이선스 모드, 입력 SHA-256, 기기·Android 버전, 요청 시각, 출력 메타데이터, 실제 처리 방식과 로그를 기록한다. 복사 구간은 기존 패킷 해시·PTS 비교 방식을 이용하고, 재인코딩 경계는 디코딩한 첫·마지막 프레임과 음성 타이밍으로 검증한다. 출력 파일 크기나 처리 시간만으로 원본 복사 여부를 추정하지 않는다.

성능은 같은 실제 기기와 저장소에서 예열 후 최소 3회 측정하고 중앙값·최댓값, 임시 공간 및 발열 상태를 남긴다. 화면을 나가거나 화면이 꺼진 상태의 완료·취소·복구도 확인한다. 320×240의 짧은 생성 영상과 에뮬레이터 결과는 긴 4K 원본의 속도 보장이 아니다.

## 도입 판단 기준

Android 패키지를 실제로 빌드할 수 있고, 해당 APK의 배포 조건이 확인되며, 요청 구간을 유지한 채 현재 전체 인코딩이 필요한 대표 입력에서 이득이 입증된 경우에만 통합한다. 미지원 형식은 명확히 표시하고 현재 정확한 위치 저장 경로로 처리한다. 라이선스 오류·취소·지원 실패가 갤러리 중복 등록이나 원본 훼손으로 이어져서는 안 된다.

모든 영상에서 빠르다는 보장은 도입 기준에 포함하지 않는다. 확인된 형식·조건의 지원 범위와 실제 측정 결과를 공개하고, SDK가 확보될 때까지 현재 엔진을 유지한다.
