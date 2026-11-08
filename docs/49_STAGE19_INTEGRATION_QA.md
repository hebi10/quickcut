# QuickCut 19단계 — 14~18단계 통합 Android QA·릴리스 게이트

작성: 2026-11-08 / 대상: QuickCut Android / 기준: 15·16단계가 포함된 브랜치
원본 기획 문서: https://docs.google.com/document/d/1fTi_fx0g6CF6SwpRKjrlYdbK9Pp9oAqmSg6To4GkhfI/edit

## 실제 사전 검증 결과 (수정 작업 전)
| PR | 기능 | 확인한 결과 | 조치 |
| --- | --- | --- | --- |
| #37 | 14단계 여러 구간 추출 | 기존 Android CI 성공 이력, 여전히 Draft | 전체 통합 후 재검증 |
| #38 | 15단계 화질 설정 | 최신 Android build 성공, API35 success, API29/36 emulator offline 또는 failure | 기기/리소스 원인과 테스트 불합격을 구분해 재실행 |
| #39 | 16단계 크롭 | unit test CropRegion.zoom 최소 크기 정밀도 실패, API35 계측에서 고정 버튼의 scroll semantics 오류 | CropGeometry MIN_FRACTION 여유값·고정 버튼 접근 테스트 수정 |
| #40 | 17단계 오디오 | AudioWaveformReader.kt 컴파일 실패 (block-body missing return) | 명시적 return 수정 |
| #41 | 18단계 텍스트 | Compose Lint: 사용하지 않는 BoxWithConstraints scope | 단순 Box로 변경 |

수정 브랜치별 CI와 새 통합 PR CI가 모두 필요하다. 과거 통과한 커밋은 최신 코드의 합격 판정 근거로 사용하지 않는다.

## 통합 아키텍처
- **한 클립의 불변 상태**: VideoClip에 crop(16), audio(17), textLayers(18)를 모두 선택적 필드로 포함. 누락된 구버전 JSON은 각각 원본 화면/기본 볼륨/텍스트 없음으로 복원.
- **저장과 실행 취소**: EditorSessionStore v5의 기존 세션·프로젝트 데이터 보호, EditHistory를 통한 통합 효과의 Undo/Redo, 트림/분할 이후 레이어 시간과 오디오 페이드 길이 보정.
- **하나의 Media3 effects 체인**: Crop → TextOverlay → Presentation/FrameDrop; 별도 오디오 GainProcessor. CompositionPlayer 미리보기와 MP4 Transformer가 동일한 효과 생성기를 사용한다.
- **데이터 보호**: 효과가 하나라도 있는 영상은 고속 복사를 건너뛰고 반드시 재인코딩 사전 동의를 요구. 효과 없는 클립은 기존 고속 복사·네이티브 처리 우선 정책을 유지.
- **화면 진입**: 홈에는 여러 구간 추출(14), 상세 편집에는 기존 도구와 크롭(16)·오디오(17)·텍스트(18) 기능을 함께 노출. 해상도·FPS·화질(15)은 기존 저장 설정에서 선택.
- **기존 기능**: 원본 날짜, 원본 삭제 승인, 빠른 자르기/상세 편집/일괄 편집/템플릿, 출력 저널 및 중단·복구를 계속 유지해야 한다.

## 필수 검증 항목 (체크는 검증 후에만)
- [ ] Stage14ExtractionPlannerTest 및 3구간 실제 MP4 출력/부분 성공·중단 복구.
- [ ] Stage15 ExportQualityPolicyTest, 실제 720p/1080p 출력 해상도·FPS·오디오 검증.
- [ ] Stage16 CropGeometryTest, 회전·정사각·9:16 실제 MP4와 유효 크롭 UI.
- [ ] Stage17 AudioAdjustmentTest, 실제 PCM 볼륨/페이드와 음소거·프로젝트 보존.
- [ ] Stage18 TextLayerTest, 실제 MP4 프레임에서 지정된 시간에만 문구 노출/한글 저장.
- [ ] Stage19IntegrationTest: 합성 모델/트림/분할/Undo/Redo가 혼합 상태를 보존.
- [ ] Stage19CombinedExportInstrumentedTest: 720p 결과에 크롭+페이드+텍스트를 동시 적용, 실제 출력 프레임·오디오·원본 보호 검사.
- [ ] API29·35·36 emulator full unfiltered instrumentation, 새 기능 화면 14~18 PNG 전부 수집 및 파일 무결성 검증.
- [ ] API29·35·36 에뮬레이터 화면 가림·접근성 160%·320dp/태블릿 테스트.
- [ ] Android JVM/Native 빌드, Lint, APK/AAB, 취소·중단 복구, 원본 삭제 승인, 기존 템플릿 회귀.
- [ ] 원본 기획 이미지와 모든 단계의 Android 화면을 나란히 비교해 명백한 사용성 문제 수정.
- [ ] Google Play 테스트 업로드용 versionCode 및 업로드키 검사, 게시 권한 및 릴리스 노트 확인.

## 병합 순서 및 금지
- 현재 이 통합 브랜치는 #39(stage16) 위에 14·17·18 변경을 합성한 Draft 통합 검증 브랜치이다.
- #38(15단계) → #39(16단계)를 먼저 처리하고, #37·#40·#41의 독립 브랜치와 통합 브랜치의 공통 파일 충돌 해결 및 동등 기능 확인 후 최종 PR 병합 방법을 선택한다.
- 기존 PR들을 일괄 병합하거나 19단계 통합 브랜치를 바로 main에 덮어쓰지 않는다.
- 실제 Android 테스트 결과 없는 화면을 완료로 간주하지 않으며, 이전 테스트의 결과를 최신 SHA의 통과로 주장하지 않는다.
- Google Play 내부/비공개 테스트 게시 작업은 모든 게이트 확인 이후에만 시행한다. 사용자 지시 없이 closed track으로 배포하지 않는다.

**현재 판정**: 19단계 기능 통합 후보 생성. 실제 빌드·계측·화면 비교 결과는 검증 중이며 릴리스 완료가 아니다.
