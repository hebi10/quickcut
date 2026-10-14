# QuickCut 저장 안정화 설계

사용자는 2026-10-14 전체 코드 검토에서 제안한 개선을 모두 반영하도록 승인했다. 기존 오프라인 Android 편집기에서 입력과 출력의 일치, 원본 보호, 긴 영상·일괄 작업의 지속 실행을 개선한다.

## 범위와 제약

- Kotlin/Compose/Media3 1.11.1, minSdk 29, compile/targetSdk 36을 유지한다. 새로운 제품 의존성, INTERNET 권한, 분석·광고·원격 데이터 전송을 추가하지 않는다.
- 앱은 사용자 입력을 검증한 구간만 저장한다. 남는 길이 100ms는 허용하고 모든 저장 진입점에서 잘못된 입력을 차단한다. 미리보기 음소거와 출력 음소거가 일치해야 한다.
- 같은 저장 작업에서 고속 저장 실패 후 정밀 저장 동의를 다시 받아도 날짜·원본 처리 선택은 유지한다. 앱 프로세스 재생성이나 새 작업에서는 원본 처리 선택을 KEEP으로 초기화한다.
- 출력은 예상 길이(오차 300ms 이하), 오디오 유무, 앞/뒤 프레임 읽기 및 해당되는 원본 날짜를 검증한 뒤 갤러리에 공개한다. 검증 실패·취소 파일은 정리한다.
- 갤러리 공개와 성공 기록은 취소로 분리되지 않는 완료 단계이다. 출력 생성 URI를 먼저 기록하고 공개 직후 완료 기록을 남긴다. 프로세스 종료 후에는 공개된 출력을 확인해 중복 저장을 막고 미공개 출력만 정리한다.
- 저장 전에 예상 출력 크기와 캐시/갤러리 공간을 비교한다. 크기는 추정임을 안내하고 실제 저장 오류도 처리한다. 원본 날짜 유지에서 직접 고속 복사가 지원되지 않는다는 설명을 선택 시 표시한다.
- 사용자 시작 작업만 foreground service로 실행한다. API 35 이상 mediaProcessing, API 29~34 dataSync를 사용한다. 알림으로 상태·중단을 제공하고 정상 완료/실패/시간 제한에서 서비스와 wake lock을 해제한다. 자동 부팅 실행은 없다.
- 앱 수준에 편집·일괄 ViewModel을 유지해 화면 재생성 때문에 실행 작업을 취소하지 않는다. 일괄 목록/계획/출력 상태를 AtomicFile JSON으로 저장한다. 프로세스 종료 뒤 남은 작업은 사용자가 재검토 후 다시 시작하며 원본 삭제 동의를 복원하지 않는다.
- 간편 편집은 가로/낮은 높이에서 미리보기와 입력을 나누고 좁은 화면·큰 글꼴에서는 입력 행을 재배치한다. 4dp 버튼/다이얼로그 모서리와 기존 색상 토큰을 사용한다.
- 저장 대화상자를 분리한다. README/QA 문서는 최종 검사 결과와 구현 범위를 기록한다. 실기기 속도와 모든 코덱 지원은 측정하지 않은 상태로 보장하지 않는다.

## 작업 경계와 인터페이스

UI 담당은 ui/*.kt 및 입력 검증 helper/editor/TrimInput.kt와 해당 테스트만 수정한다. 미디어 담당은 media/*.kt와 미디어 테스트만 수정한다. 실행·복구 담당은 Application, export service, EditorViewModel, BatchViewModel, BatchScreen, manifest와 복구 테스트를 수정한다.

- EditorViewModel.isTrimInputValid: Boolean, setTrimInputValidity(Boolean), canStartExport: Boolean을 실행 담당이 제공한다. UI는 입력이 유효한지 즉시 전달하고 잘못된 입력에서는 모든 저장 버튼을 비활성화한다.
- QuickCutApplication.editorModel / batchModel을 실행 담당이 제공한다. QuickCutApp은 이 인스턴스를 기본 모델로 사용한다.
- VideoExporter.export()에 onOutputCreated: (Uri) -> Unit, onCommitted: (Uri) -> Unit 기본 콜백을 미디어 담당이 제공한다. 두 콜백은 Main에서 실행한다. onCommitted는 공개/검증 성공을 뜻하고 완료 기록을 동기적으로 저장한다.
- ExportPlanner의 기존 analyze/ExportAssessment 계약을 유지한다. 용량 안내는 explanation에 포함하거나 기존 호출에 기본값을 둔 필드로 추가한다.

## 검증

100ms 경계, 잘못된 입력의 저장 우회, 미리보기 음소거, 재인코딩 재시도 선택 유지, 출력 길이/오디오/프레임 검증, 완료 단계 취소, 목록/계획 복원과 중복 방지, 삭제 동의 복원 금지, foreground service 정리, 320dp·가로·200% 글꼴을 검사한다. GitHub Actions에서 JVM/Lint/APK 및 API 35 계측 검사를 실행한다.
