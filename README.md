# QuickCut

**로그인·서버·DB 없이 실행되는 오프라인 Android 영상 편집기.**

스마트폰과 태블릿에서 영상을 선택해 필요한 장면만 편집하고 갤러리에 저장합니다. 디자인은 2026-10-08 확정한 다크 톤의 스마트폰 세로·가로 및 태블릿 가로 화면 시안을 기반으로 구현합니다.

> 구현 상태: **Android 1차 MVP 코드·자동 빌드 완료(0.1.0 개발판)**. [GitHub Actions](../../actions)에서 Kotlin 컴파일, 단위 테스트 및 Debug APK 생성을 통과했습니다. 단, **실제 기기 영상 재생·내보내기·성능 및 태블릿/폴더블 동작은 아직 검증하지 않았습니다.**

## 구현된 1차 범위

| 기능 | 구현 상태 |
| --- | --- |
| Android 영상 여러 개 선택 / 추가 | 코드 작성 |
| 영상 메타데이터·썸네일 읽기 | 코드 작성 |
| 편집 결과 미리보기 (Media3 CompositionPlayer) | 코드 작성 |
| 구간 자르기 / 분할 / 클립 삭제 | 코드 작성 |
| 클립 순서 변경 (앞·뒤 이동) | 코드 작성 |
| 클립별 음소거 | 코드 작성 |
| MP4 내보내기 / 진행률 / 취소 / 갤러리 저장 | 코드 작성 |
| 저장 완료·오류·종료 확인 화면 | 코드 작성 |
| 폰 세로 / 낮은 높이(폰 가로) / 태블릿 넓은 창 적응형 레이아웃 | 코드 작성 |
| 화면 회전 시 ViewModel 편집 세션 유지 | 코드 작성 |
| 편집 데이터 모델 단위 테스트 13개 | 테스트 코드 작성 |

**APK 빌드 성공은 실제 영상 재생·편집 결과가 정상이라는 의미가 아닙니다.** 코덱·HDR·가변 프레임레이트·기기 성능에 따라 내보내기가 오래 걸리거나 실패할 수 있습니다.

### UI 배치

- **세로 스마트폰:** 상단 미리보기, 재생바, 타임라인, 하단 도구(자르기/분할/삭제/순서/음소거).
- **가로 스마트폰(사용 가능 높이 480dp 미만):** 미리보기 왼쪽, 도구 오른쪽, 축소 타임라인 하단.
- **넓은 태블릿 창(너비 840dp 이상):** 미리보기 왼쪽, 우측 속성 패널, 하단 전체 너비 타임라인.
- **그 외 중간 폭:** 세로 스택을 넓게 확장하고 영상 종횡비를 보존.
- 창의 현재 너비·높이 기준으로 분기합니다. 폴더블 힌지 회피는 후속 실기기 검증 항목입니다.

### 내보내기 정책

- AndroidX Media3 Transformer의 스트림 복사·트리밍 최적화 사용 가능 여부를 자동 판단하도록 위임합니다.
- 단일 영상에서는 trim optimization을 시도하며 지원하지 않는 조합은 일반 처리로 진행할 수 있습니다.
- 잘린 프레임을 MP4 edit list로 **보이지 않게만** 하는 위험한 저장 방식은 사용하지 않습니다.
- 출력은 일단 앱 캐시에 생성하고 유효한 결과물만 `Movies/QuickCut` 갤러리에 등록합니다.
- 원본 영상은 수정하거나 삭제하지 않습니다. 내보내기 속도는 편집 종류·코덱·기기 성능에 따라 다릅니다.
- 파일을 실제로 확인하기 전까지 절대 '무손실' 또는 '무조건 몇 초'를 보장하지 않습니다.

## 개발 환경

| 구성 | 버전 |
| --- | --- |
| Android Studio | API 36 설치 가능 버전 |
| JDK | 17 이상 |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| Kotlin Compose compiler plugin | 2.3.21 |
| Jetpack Compose BOM | 2026.06.01 |
| AndroidX Media3 | 1.11.1 |
| Android API | minSdk 29, targetSdk 36, compileSdk 36 |

Android Studio에서 저장소 루트의 Gradle 프로젝트를 여세요. **현재 저장소에는 Gradle Wrapper JAR과 실행 스크립트가 포함되지 않습니다.** 시스템에 Gradle 9.4.1이 설치된 경우 아래 명령으로 wrapper를 생성한 후, Android Studio의 Gradle Sync와 테스트를 수행할 수 있습니다.

```powershell
gradle wrapper --gradle-version 9.4.1
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

설치된 Android SDK에서 API 36과 Build Tools 36.0.0을 사용합니다. 원격 빌드는 [Actions](../../actions)에서 Gradle 9.4.1을 설정해 실행하도록 구성했습니다.

## 프로젝트 구조

```text
app/src/main/java/com/hebi/quickcut/
├── MainActivity.kt
├── editor/
│   ├── EditTimeline.kt
│   └── EditorViewModel.kt
├── media/
│   ├── MediaReader.kt
│   ├── CompositionFactory.kt
│   ├── VideoExporter.kt
│   └── GalleryWriter.kt
└── ui/
    ├── QuickCutApp.kt
    ├── EditorScreen.kt
    ├── TimelineStrip.kt
    ├── ClipThumbnail.kt
    └── QuickCutTheme.kt
```

## 제외 범위 / 다음 작업

- 현재 미구현: 음악 삽입, 텍스트/자막, 크롭, 회전, 속도 변경, 필터, 전환 효과, 프로젝트 저장/복구, 백그라운드 지속 내보내기.
- 현재 내보내기는 임시 파일에서 MediaStore로 복사하므로 저장 완료 시간에 **파일 복사 비용**이 포함됩니다.
- 자르기는 **타임라인 핸들 드래그 + 범위 슬라이더**, 클립 재정렬은 **길게 눌러 한 칸 이동 + 이동 버튼**을 지원합니다. 여러 칸 연속 드래그는 후속 UX 개선 범위입니다.
- 폴더블 힌지와 태블릿 분할화면은 창 크기 적응 레이아웃을 적용했으나, 별도 WindowManager 기반 힌지 회피와 실기기 QA는 남았습니다.
- 우선순위: **실기기에서 1080p/H.264 자르기·분할·합치기 확인 → 오디오/비디오 싱크 검증 → 태블릿·폴더블 QA → 저장 속도 비교**.
- 테스트·남은 작업별 검증 시나리오: [구현 현황 및 실기기 QA](docs/08_IMPLEMENTATION_QA.md).

## 기획 및 디자인 문서

- [제품 요구사항](docs/01_PRODUCT_REQUIREMENTS.md)
- [화면·UX 흐름](docs/02_UX_FLOW.md)
- [기술 및 내보내기 설계](docs/03_TECH_EXPORT.md)
- [개발 로드맵 및 QA](docs/04_ROADMAP_QA.md)
- [디자인 시스템](docs/05_DESIGN_SYSTEM.md)
- [스마트폰·태블릿 적응형 레이아웃](docs/06_ADAPTIVE_LAYOUT.md)
- [화면 상세 설계](docs/07_SCREEN_DETAIL.md)
- [구현 현황 및 실기기 QA](docs/08_IMPLEMENTATION_QA.md)

## 개인정보 및 저장

- 로그인, Firebase, 원격 서버, 분석 SDK, 광고가 없습니다.
- `INTERNET` 권한과 전체 저장소 권한을 사용하지 않습니다.
- 사용자가 명시적으로 선택한 영상의 URI만 읽고 갤러리에 별도 MP4로 저장합니다.
- 편집 데이터는 메모리에서 관리하며, **앱이 프로세스 종료된 후 프로젝트를 복구하는 기능은 아직 없습니다.**
