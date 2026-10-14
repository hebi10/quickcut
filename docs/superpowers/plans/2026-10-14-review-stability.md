# QuickCut Review Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** 입력·미리보기·저장 결과를 일치시키고 긴 영상 및 일괄 작업의 안전한 지속 실행/복구를 제공한다.

**Architecture:** 기존 Compose와 Media3 계층을 유지한다. UI/미디어/작업 실행을 파일 소유권에 따라 분리하고 foreground service와 AtomicFile 복구 기록을 기존 ViewModel에 연결한다.

**Tech Stack:** Kotlin, Compose, Android platform foreground service/AtomicFile, Media3 1.11.1.

**Spec:** docs/superpowers/specs/2026-10-14-review-stability-design.md

## Global Constraints

- minSdk 29, compile/targetSdk 36, Media3 1.11.1 유지.
- 제품 의존성 및 INTERNET/분석/광고/원격 전송 추가 없음.
- 원본 삭제는 새 작업 및 프로세스 재생성에서 KEEP, 별도 앱/시스템 승인 필요.
- 100ms 최소 길이, 출력 길이 허용 오차 300ms, 기존 색상과 4dp 모서리 유지.
- 로컬 Android SDK가 없으므로 실행 검증은 같은 코드의 GitHub Actions에서 수행한다. CI 대기 중 별도 독립 작업을 진행한다.

## Review Focus

- 잘못된 시간 입력 후 저장 옵션 또는 상세 편집 저장으로 우회하는 경우.
- 공개 직전/직후 취소 및 프로세스 종료 때 결과 중복·미완성 파일 잔존.
- 복구된 URI 권한 만료, 삭제된 출력, 원본 날짜 정보 누락.
- Android 29/34/35/36 foreground service 종류·시간 제한·화면 재생성.
- 320dp, 가로, 200% 글꼴에서 입력·저장·취소 접근.

### Task 1: 입력 및 미리보기 안정화

**Files:** ui/QuickTrimScreen.kt, ui/EditorScreen.kt, ui/QuickCutApp.kt, ui/ExportDialogs.kt, ui/QuickCutTheme.kt, editor/TrimInput.kt 및 UI/입력 테스트.
**Interfaces:** EditorViewModel.isTrimInputValid / setTrimInputValidity(Boolean) / canStartExport, QuickCutApplication.editorModel / batchModel을 소비한다. 기존 저장 함수 계약은 유지한다.
- [ ] 100ms 입력, 잘못된 입력 저장 우회, 음소거 및 재시도 선택 유지 회귀 테스트 작성.
- [ ] 변경 전 테스트 실행: 해당 동작이 실패함을 확인한다.
- [ ] 입력 검증 통일, 플레이어 음소거 적용, 저장 전 검증, 원본 옵션 이어짐, 낮은 높이/좁은 창 배치, 대화상자 분리 구현.
- [ ] JVM/계측 검사를 통해 통과 및 회귀 없음 확인.
- [ ] 변경 커밋.

### Task 2: 출력 트랜잭션 및 용량

**Files:** media/MediaReader.kt, media/VideoExporter.kt, media/GalleryWriter.kt, media/ExportPlanner.kt, media/ExportValidation.kt, media/ExportStorage.kt 및 미디어 테스트.
**Interfaces:** export()에 기본값을 둔 onOutputCreated/onCommitted 콜백을 제공하며 Main에서 호출한다. 콜백 성공까지 공개된 결과를 안전하게 기록하거나 실패 시 정리한다.
- [ ] 길이/오디오 오류, 취소 경계 및 저장 공간 회귀 테스트 작성·실행으로 실패 확인.
- [ ] 공통 출력 검증, 비취소 완료 단계, 실패 파일 정리 및 출력 생성/완료 콜백 구현.
- [ ] 고속/일반 경로 용량 추정 및 부족 안내 연결.
- [ ] JVM/계측 검사 통과 확인 후 커밋.

### Task 3: 백그라운드 작업 및 복구

**Files:** QuickCutApplication.kt, export/ExportWorkMonitor.kt, export/ExportWorkService.kt, batch/BatchSessionStore.kt, batch/BatchViewModel.kt, batch/BatchScreen.kt, editor/EditorViewModel.kt, AndroidManifest.xml, 관련 테스트.
**Interfaces:** Task 1의 모델 계약을 제공하고 Task 2 콜백으로 URI/성공 상태를 원자적으로 저장한다.
- [ ] 완료 결과 복원·중단 결과 재검토·삭제 동의 제외·재시도 대상·서비스 수명 테스트 작성·실행으로 실패 확인.
- [ ] 앱 수준 모델, 단일 실행 foreground service, 알림/중단/시간 제한/wake lock 정리 구현.
- [ ] JSON 복구 기록, 출력 생성 journal, 공개 결과 재검사, 중복 방지 및 잔여 작업 재시작 구현.
- [ ] 회귀 검사 통과 후 커밋.

### Task 4: 통합 검증과 전달

**Files:** app/build.gradle.kts, README.md, docs/14_STABILITY_BACKGROUND.md, CI 경로 필터, QA/회귀 테스트.
- [ ] 버전을 0.4.0(versionCode 5)로 올리고 구현 범위·실기기 남은 QA를 문서화.
- [ ] 전체 JVM/Lint/APK/API 35 계측 검사 통과 확인.
- [ ] 독립 코드 검토를 거쳐 중요한 발견 사항 수정 및 재검증.
- [ ] 검증된 변경을 GitHub에 반영하고 APK를 제공.
