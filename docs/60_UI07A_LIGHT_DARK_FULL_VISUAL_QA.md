# UI-07-A — 라이트·다크 전체 화면 시각 QA

2026-11-12 · 기준 문서: [QuickCut Modern Edit Desk UI/UX 계획](https://docs.google.com/document/d/1hyNminChkLsX3MHes3xM_m4KuuHTHD1gC0K8O5UnRCo/edit)

## 목표와 현 상태

- 목표: 앱의 핵심 화면과 위험·실패·완료 상태에서 **실제 Android 앱 화면**을 라이트/다크 쌍으로 수집하고, 팔레트 일관성·접근성·검수 증빙을 확인합니다.
- 구현: 코드 수정 및 Android 계측/CI 게이트 추가 완료. **GitHub Actions 최신 HEAD 결과와 캡처 원본 시각 검수 전에는 전체 QA 통과로 판정하지 않습니다.**
- 기준 브랜치: 선행 UI-06-A PR #60. 이 작업은 별도 Draft PR로 유지합니다.
- 절대로 실제 저장·원본 삭제·새 권한·서명·Play 트랙을 변경하지 않습니다.

## 1. 실제 코드의 디자인 이슈 및 수정

1. 라이트 `background=#F6F6F4`, `panel=#ECECEA` 등 미세한 아이보리·유색 톤 발견. 사용자 지정에 따라 `background=#F7F7F7`, `panel=#EEEEEE`, `border=#D9D9D9`, `accent=#1C1C1C` 및 나머지 회색 토큰을 중성화했습니다.
2. 다크 `background=#111315`, `surface=#1B1D20`, `panel=#26292D` 등에 남은 푸른 색조를 제거해 `background=#121212`, `surface=#1C1C1C`, `panel=#282828`, `accent=#F1F1F1`로 통일했습니다.
3. 상태(오류·경고·성공)와 재생 헤드의 의미색은 유지합니다. 새로운 장식·그라데이션·그림자·애니메이션은 추가하지 않았습니다.
4. 기존 `PurpleThemeInstrumentedTest` 두 테스트 안에서 중성 배경/텍스트의 RGB 일치와 본문·보조 텍스트 및 강조 버튼의 WCAG 대비 4.5:1 이상을 확인합니다. 하드코딩된 구형 색상 토큰 단언은 새로운 값에 맞게 수정했습니다.

## 1-A. 기존 실제 Android 캡처 육안 확인 (선행 PR #55)

2026-11-12에 [PR #55 API35 성공 작업의 실제 캡처 아티팩트](https://github.com/hebi10/quickcut/actions/runs/43135762098/artifacts/13908446305)를 다운로드하여 `ui03a_light/dark_{home,projects,tools,settings}.png`, 구간 추출·크롭·오디오·텍스트·저장/삭제 모달 등 일부 화면을 대조했습니다.

- **확인:** 홈/프로젝트/도구/설정 네 탭에서 라이트/다크 배경·CTA·아이콘 가독성 차이가 실제 화면에 나타납니다. 일부 핵심 컴포넌트는 구형 라이트 팔레트에서 아이보리 색조를 사용하고 있어 1절의 팔레트 보정을 적용했습니다.
- **구간 추출/크롭 단독 캡처의 문제:** 상단 제목/스크롤 내용이 Android 상태바 영역에 겹쳐 보였습니다. `QuickCutApp` 본체는 이미 `safeDrawingPadding()`을 적용하지만, `Stage14ExtractionScreensInstrumentedTest`/ `Stage16CropScreensInstrumentedTest`/ `Stage17AudioScreensInstrumentedTest` 등의 독립 스크린샷 테스트에는 없었습니다. 이를 **실앱 회귀로 단정하지 않고** 기존 테스트 화면을 `Ui07aAppSafeSurface`로 감싸 앱의 실제 인셋 구조와 일치시켰습니다.
- 온보딩·주요 탭·선택 갤러리·완료 화면의 독립 캡처도 같은 안전 영역을 적용해 기존 기준 캡처와 새 캡처를 구분합니다.
- **미확인:** 위 육안 확인은 PR #55의 과거 API 35 캡처에 한정됩니다. PR #61에서 변경된 라이트·다크 40장과 API29/35/36 결과는 해당 최신 커밋의 CI 종료 후 다시 확인해야 하며, 이를 미리 PASS로 기록하지 않습니다.

## 2. 검증 매트릭스 (20종 × 2테마 = 40장)

모든 파일은 Android 계측 중 실제 기기 화면에서 생성됩니다. 기존 정상 동작·실제 미디어 픽스처를 사용하며, 테스트 결과는 앱 프로젝트/원본 파일의 저장 성공을 대신하지 않습니다.

| 화면/상태 | 라이트·다크 PNG 패턴 | 기반 테스트 |
| --- | --- | --- |
| 홈 | `ui03a_{theme}_home.png` | ThemeMode |
| 프로젝트 빈 상태 | `ui03a_{theme}_projects.png` | ThemeMode |
| 도구 | `ui03a_{theme}_tools.png` | ThemeMode |
| 설정 | `ui03a_{theme}_settings.png` | ThemeMode |
| 빠른 자르기 | `editor_design_quick_trim_{theme}.png` | EditorDesign |
| 상세 편집 | `editor_design_detailed_{theme}.png` | EditorDesign |
| 온보딩 | `ui07a_{theme}_onboarding.png` | Stage2Welcome |
| 프로젝트 실데이터 | `ui07a_{theme}_projects.png` | Stage13Reference |
| 파일 선택 갤러리 | `ui07a_{theme}_gallery.png` | Stage13Reference |
| 일괄 편집 | `ui07a_{theme}_batch.png` | BatchScreen |
| 여러 구간 추출 | `ui07a_{theme}_extraction.png` | Stage14Extraction |
| 크롭·비율 | `ui07a_{theme}_crop.png` | Stage16Crop |
| 오디오·페이드 | `ui07a_{theme}_audio.png` | Stage17Audio |
| 텍스트 오버레이 | `ui07a_{theme}_text.png` | Stage18TextEditor |
| 저장 설정 | `ui07a_{theme}_save_options.png` | Stage15ExportQuality |
| 저장 완료 | `ui07a_{theme}_completed.png` | Stage15ExportQuality |
| 저장 중 진행률 | `ui07a_{theme}_progress.png` | ExportStage5 |
| 원본 영구 삭제 활성화 | `ui07a_{theme}_original_delete.png` | DialogDesign |
| 작업 오류 다이얼로그 | `ui07a_{theme}_error_dialog.png` | DialogDesign |
| 저장 취소·완료 처리 | `ui07a_{theme}_export_cancel.png` | DialogDesign |

## 3. 재현 가능한 검증 및 산출물

- 원본 PNG: CI 아티팩트 `quickcut-emulator-start-api-29/35/36` 안의 `quickcut-design-review/`.
- 증빙 JSON: API별 `quickcut-ui07a-visual-matrix-api-29/35/36` 아티팩트의 `visual-matrix.json`.
- 검증 명령: `scripts/verify-ui07a-visual-matrix.py --screenshots quickcut-design-review --out quickcut-ui07a-evidence/visual-matrix.json`.
- 검증 조건: 정해진 40장 모두 존재, PNG chunk CRC/크기 정상, 라이트·다크 해상도 일치, 두 캡처 SHA-256이 실제로 달라야 함. `Ui07aVisualEvidence`가 실제 Android 화면의 단색/빈 캡처를 거부.
- 모의 CI: 유효한 테마 차이·CRC 정상 캡처는 통과하며, 잘못된 PNG와 **바이트까지 동일한 라이트/다크 이미지**는 둘 다 차단해야 합니다.
- APK/앱 인코딩 동작·원본 SHA256·API29 정확한 292개 계측·API35/36 전체 검사는 기존 게이트를 그대로 유지합니다.

## 4. 미확인/추가 실기기 검수

아래는 이미지 무결성 자동화만으로 통과 처리하지 않습니다.

- UI 요소의 실제 **잘림/겹침/줄바꿈/버튼 시인성**: 40장 원본을 사람이 일대일로 판독하고 항목별 수정·재캡처 이력을 남겨야 합니다.
- 장치별 **320·360·412dp, 가로/폴더블/태블릿, 160~200% 글꼴 및 키보드**: 기존 API 계측 중 320·360·860dp와 확대 글꼴/가로 일부 검사 유지. 이번 40장 매트릭스가 모든 해상도를 대변하지는 않으며, 412dp와 실제 폴더블/태블릿 실기기 전체는 별도 확인 필요.
- 시스템 모드 자동 전환, 상태바/내비게이션바, 선택 상태·스크린리더: 기존 시스템/명시 모드 전환 회귀 테스트 보존. Android 버전별 동작을 아티팩트로 재검증.
- 실제 MP4 인코딩 결과와 갤러리 등록, 원본 SHA 및 Android 삭제 승인: 화면 캡처와 구분해 기존 미디어·원본 안전 계측으로 별도 검사.

## 5. 안전한 병합 및 다음 단계

- 선행 PR #55 → #56 → #57 → #58 → #59 → #60의 **정확한 최신 HEAD**가 빌드·API29/35/36 전체 계측·캡처 검증에 모두 성공해야 합니다.
- 선행 단계 순차 병합 후 이 PR의 base를 최신 `main`으로 전환하고, 최종 HEAD에서 전수 검증과 실제 PNG 비교를 다시 수행합니다.
- 판정 전 Draft 상태 유지; Google Play 내부·비공개 테스트 배포는 이번 UI QA에 포함하지 않습니다.
- 다음 작업 `UI-08-A — 전체 기능 회귀·출시 준비·PR 순차 병합`: MP4/AAC·원본 보존·프로젝트 복구 및 Play 테스트 트랙 전 조건을 마지막으로 검증합니다.
