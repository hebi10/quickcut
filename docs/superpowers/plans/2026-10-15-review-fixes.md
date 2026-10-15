# QuickCut 0.4.1 안정화 계획

> For agentic workers: use subagent-driven-development or executing-plans. This plan implements the user's approved 2026-10-15 code-review feedback.

**Goal:** 잘못된 편집 적용, 실행 취소·복원 누락, 완료 파일 이동 및 출력 정리 오류를 해결하고 업데이트 가능한 APK 배포 기반을 마련한다.

**Architecture:** 기존 Kotlin/Compose/Media3 구조를 유지한다. 편집 입력의 적용 책임은 가져오기 작업에 연결하고, 완료 출력 검증과 미완성 출력 삭제를 구분한다. APK 서명은 저장소 외부의 영속 키를 사용하며 키를 소스·로그·APK에 포함하지 않는다.

**Tech Stack:** Kotlin, Jetpack Compose, Media3, JUnit, Android instrumentation, GitHub Actions.

**Spec:** 이 대화에서 승인한 2026-10-15 리뷰의 여섯 문제와 재생 위치·고속 복사 성능 개선.

## Global Constraints
- 기존 UI·기술 스택·설정·원본 삭제 승인 흐름을 유지한다.
- 원본 영상은 자동 삭제하지 않는다. 새 네트워크 기능·분석 SDK·의존성을 추가하지 않는다.
- 앱 ID는 com.hebi.quickcut, 새 버전은 0.4.1 / versionCode 6.
- 비밀키·토큰·키 비밀번호는 소스나 로그에 남기지 않는다.
- 서명 키의 기존 설치본 호환 여부는 확인한 사실만 보고한다.
- 실기기 성능을 자동 검사 결과로 대체하여 주장하지 않는다.

## Review Focus
- 빠른 가져오기 실패 후 상세 가져오기·세션 복원에서 자르기와 음소거 불변.
- 마지막 클립 삭제 후 사용자 UI에서 실행 취소와 재실행 가능.
- 250개를 넘는 정상 편집 세션의 저장·재시작 복원 일치.
- 폴더가 이동된 완료 출력의 보존과 작업 목록 제거·초기화 가능.
- 출력 삭제 실패에서 재시도 차단 및 출력 추적 유지, 짧은 오디오와 긴 영상의 종료 처리.

## Task 1: 편집·화면 상태
Files: EditorViewModel.kt, EditorSessionStore.kt, QuickCutApp.kt, QuickTrimScreen.kt, EditorScreen.kt and focused editor/ui instrumentation tests.
- [x] 기존 동작으로 실패하는 회귀 검사: 빠른 가져오기 실패 후 상세 편집/복원; 마지막 클립 삭제 실행 취소; 251개 클립 복원; 빠른 미리보기 위치 유지.
- [x] 테스트를 수정 전 코드에서 실행해 실제 실패를 확인한다.
- [x] 빠른 기본값을 해당 가져오기 성공에만 적용하고 임시 UI 플래그를 제거한다.
- [x] 빈 편집 상태에서도 실행 취소 접근을 유지한다. 복원은 정상 저장 가능한 클립 수와 일치시킨다.
- [x] 미리보기 위치를 편집 모델과 동기화하고 회전·상세 편집 전환에 복원한다.
- [ ] 회귀 검사와 전체 검사 성공을 확인한다.

## Task 2: 일괄 복구
Files: BatchViewModel.kt and BatchRecoveryInstrumentedTest/BatchJournalInstrumentedTest.
- [x] 완료 영상을 다른 폴더로 이동한 뒤 복원, 목록 제거, 새 작업 초기화 검사를 추가한다.
- [x] 이전 코드 실패를 확인한다.
- [x] 완료 출력은 원래 폴더와 무관하게 검증하고 미완성 출력 삭제는 소유권·안전한 경로를 확인한다.
- [x] 기존 삭제 실패·권한 실패·중단 출력 채택 검사를 유지한다.

## Task 3: 영상 출력·정리
Files: GalleryWriter.kt, VideoExporter.kt, FastMp4Trimmer.kt and focused media instrumentation tests/fixtures.
- [x] 출력 삭제 실패(예외/0개 삭제) 뒤 대체 저장이 시작되지 않는 검사를 추가한다.
- [x] 오디오가 먼저 끝나는 영상의 고속 복사가 지정 구간 이후를 불필요하게 읽지 않는 검사를 추가한다.
- [x] 이전 코드 실패를 확인한다.
- [x] 정리 미확인 오류는 원래 고속 저장 오류와 구분해 재인코딩 전환을 막고 추적 정보를 유지한다.
- [x] 종료 시점에 도달한 트랙의 선택을 해제한다. 잘못된 샘플·취소·출력 검증을 유지한다.

## Task 4: 서명·배포·검증
Files: app/build.gradle.kts, .github/workflows/*, scripts/*, README and release/QA notes.
- [x] 기존 서명 자료와 현재 연결의 비밀 저장소 쓰기 지원 여부를 확인한다.
- [x] 비밀 저장소 기반 고정 서명 설정과 실패 시 임의 키 대체 금지를 구현한다.
- [ ] 단위 검사·Lint·APK 빌드·계측 검사를 수행하고 동일 서명으로 업데이트 설치를 검증한다.
- [ ] 독립 리뷰의 확인된 결함을 보완한다.
- [ ] 저장소에 수정 내용을 반영하고 APK를 사용자가 지정한 폴더에 전달한다.
- [ ] 기존 키 미확보·비밀 저장소 권한 부재·실기기 미검증은 정확히 보고한다.
