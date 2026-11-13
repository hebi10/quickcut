# UI-06-A — 모달·진단·오류·원본 삭제 확인 화면 정밀 리디자인

기준: 2026-11-12
기획 기준: [QuickCut Modern Edit Desk 전체 리디자인](https://docs.google.com/document/d/1hyNminChkLsX3MHes3xM_m4KuuHTHD1gC0K8O5UnRCo/edit) 의 6-A 및 7-6
선행 PR: #55(UI-03-A) → #56(UI-04-A) → #57(UI-04-B) → #58(UI-05-A) → #59(UI-05-B)
현재 PR: 본 단계(UI-06-A), Draft/미병합. 선행 PR #59 브랜치 기반.

## 실제 변경 범위

### 1. 원본 처리 활성화·영구 삭제 확인
- 원본 영구 삭제/휴지통 설정 활성화와 실제 Android 삭제/휴지통 승인은 **서로 다른 단계**임을 명시.
- 확인 버튼 클릭 시 기기 원본을 즉시 삭제하지 않음: 기존 `onConfirm` 콜백으로 처리 방식 저장 → 출력 저장 완료 → 실제 결과 검증 → Android 시스템 승인(기존 정책) 순서 보존.
- 실패·취소·건너뛴 원본 보호, 설정 지속성, 원본 영구 삭제 후 복구 불가능 경고를 구분해 표시.
- 프로젝트 삭제 확인에도 편집 기록과 원본 영상을 구분해 표시; `onDelete` 콜백·프로젝트 저장 구조는 변경하지 않음.

### 2. 저장 방식·취소·복구·오류 모달
- 저장 방식 분석 결과를 중립 정보 패널로, 사용자 지정 정확한 자르기와 추천 키프레임 위치 변경은 별도 설명으로 분리.
- 저장 취소 가능/최종 등록 중 취소 불가 및 원본 보호 로직은 불변. 기존 `ExportCancelDialog` 콜백도 그대로 사용.
- 저장 복구 다이얼로그는 작업 확인 상태와 후속 진단 안내를 분리. 기존 `retryExportRecovery`·상태 저장/숨기기 콜백 유지.
- 편집 복원은 오류 사유와 복원 방법을 분리. 원본 URI 재연결 및 다시 시도/닫기 콜백 불변.
- 작업 오류는 `TaskErrorDialog`에서 원인, 진단 보기, 확인을 분리. 기존 `dismissError` 동작 유지.

### 3. 진단·미리보기 오류
- 기기에 저장된 진단 목록은 상태·단계 및 작업 식별 정보를 분리하고 큰 글꼴에서 세로 정렬.
- 진단 상세는 기존 텍스트/클립보드 복사를 유지하며 공유 전 사용자 검토를 안내. 자동 외부 전송·새 권한 추가 없음.
- 미리보기 오류의 장식 이미지를 제거하고 실제 오류 문장/편집 내용 유지/재시도/로컬 진단 액션을 명확히 표현. 편집·재생·내보내기 로직 불변.

## 필수 Android QA (기존 테스트 수 불변)

- `DialogDesignInstrumentedTest` **기존** 테스트에서 원본 삭제 활성화 설명, 편집 복원, 오류 팝업, 미리보기 복구, 큰 글꼴 및 저장 취소 화면 확인.
- `ExportDiagnosticButtonInstrumentedTest` **기존** 테스트에서 진단 목록 행과 실제 작업 ID, 클릭/복사 성공 확인.
- 기존 `SaveOptionsAccessibilityInstrumentedTest`, `EditorRecoveryUiInstrumentedTest`, `OriginalMediaActionsInstrumentedTest`, `Stage20RecoveryInstrumentedTest` 등 실행 조건 유지.
- 기존 API29 292개 계측 건수 및 API35/36 전체 계측 경계 변경 없음.
- 필수 실제 Android 이미지 12개: `dialog_save_settings.png`, `dialog_original_delete.png`, `dialog_export_method.png`, `dialog_export_cancel.png`, `dialog_export_finalizing.png`, `dialog_edit_recovery.png`, `dialog_diagnostics.png`, `dialog_task_error.png`, `ui06a_preview_recovery.png`, `dialog_save_large_font.png`, `dialog_export_analysis_large_font.png`, `dialog_licenses.png`.
- `DialogDesignInstrumentedTest`가 기기 화면을 직접 캡처해 보존하며, `android-instrumentation-check.sh`에서 각 PNG의 무결성/CRC·크기를 엄격히 검사. `test-emulator-flow.py`에서 손상/가짜 오류 텍스트를 주입한 경우 반드시 실패하는지 별도로 확인.
- 테스트/캡처 프로그램은 화면 증빙만 제공합니다. 실제 원본 삭제 시스템 승인과 실기기 동작은 Android 시스템/개별 MediaStore 회귀 검사 및 별도 실기기 검증으로 평가합니다.

## 완료 판정 및 병합/릴리스 제한

1. 선행 PR #55→#56→#57→#58→#59의 정확한 각 HEAD에서 Android build, Lint, API29/35/36 필수 CI·실캡처 검수 통과 후 차례로 `main` 병합.
2. 본 PR을 최신 `main`으로 갱신하여 코드 중복/충돌 없이 Android 전체 CI 및 실제 기기 캡처 12장 다시 검증.
3. 데이터/원본 SHA-256/MP4 인코딩 및 앱 복구 검증이 성공하고 사용자 화면 검수까지 마친 경우에만 Draft 해제/병합 판단.
4. 이번 작업은 업로드 키·앱 버전·Google Play 테스트 트랙·스토어 이미지 WIF/자격 증명·삭제 정책을 수정하지 않으며, CI 실행/완료 전에는 '병합·배포 완료'로 보고하지 않음.

다음 단계: UI-07-A — 라이트·다크 전체 화면 시각 QA.
