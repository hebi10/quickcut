# QuickCut 14단계 — 여러 구간 개별 추출

기준: `0.4.20` · 기존 1~13단계 기능 유지. 기획: [Google Drive](https://docs.google.com/document/d/1fTi_fx0g6CF6SwpRKjrlYdbK9Pp9oAqmSg6To4GkhfI/edit).

## 사용자 흐름

1. 도구 → **여러 구간 추출** → Android 문서 선택기에서 영상 1개 선택.
2. 4초 또는 원본 길이 중 짧은 값의 기본 구간을 확인. **구간 추가**를 통해 최대 30개 생성.
3. 선택 구간 미리보기, 시작·종료 시각을 초 단위로 입력하거나 범위 슬라이더로 지정.
4. 각 구간은 서로 겹칠 수 있지만 시작·끝이 완전히 같은 구간의 중복 추가/변경은 차단. 0.1초 미만 및 원본 범위 밖은 저장하지 않음.
5. 개별 저장 시 기존 `ExportPlanner`가 각 구간의 고속 저장 가능 여부를 검사.
6. 고속 저장이 불가능한 구간이 하나라도 있으면 사용자에게 정확한 위치 유지·재인코딩 가능성을 명시적으로 안내한 뒤 승인받음.
7. 기존 `VideoExporter`로 각 구간을 **독립 MP4**로 생성. 원본 삭제/휴지통 처리 없음.
8. 구간별 완료·실패·저장 상태를 표시하고 성공물은 재시도 시 건너뜀.

## 코드 구조

- `segments/ExtractionPlanner.kt`: 구간 유효성·중복 검사·출력 이름·단일 클립 타임라인 생성.
- `segments/ExtractionSessionStore.kt`: 구간·출력 URI·상태를 `AtomicFile`로 저장.
- `segments/ExtractionViewModel.kt`: 실제 미디어 분석·동의 흐름·직렬 실행·취소·부분 성공·소유권 검증 복구.
- `segments/ExtractionScreen.kt`: 동영상 미리보기·통합 구간 타임라인·범위 편집·결과 목록.
- `ui/StudioHome.kt`, `ui/QuickCutApp.kt`, `QuickCutApplication.kt`: 기존 UI는 유지하고 별도 진입만 추가.
- `ExtractionPlannerTest`, `Stage14ExtractionScreensInstrumentedTest`: 단위 및 Android 화면 검수.

## 보호 규칙

- 기본 설정·일괄 템플릿·기존 단일/일괄 편집·원본 처리 정책을 변경하지 않음.
- 하나의 구간에 대해 갤러리 파일이 `IS_PENDING` 상태로 생성되면 **출력 URI를 원자적으로 기록한 뒤** 파일 쓰기를 계속함.
- 앱 재시작/시스템 중단 이후 `MediaStoreSingleExportAccess`로 앱 소유·검증 상태를 조사. 검증된 완료물은 유지, 앱 소유 미완성 출력은 제거 확인 후 재시도, 불명확한 결과는 **BLOCKED**로 처리하여 중복 저장 방지.
- `ExportWorkMonitor`로 다른 단일/일괄 저장과 동시 실행 금지. 완료된 파일을 재저장하지 않음.
- 저장 중 오류가 생긴 파일만 상태를 실패로 기록. 성공한 출력에는 영향을 주지 않음.
- 새 기능은 선택된 단일 원본 URI만 읽고 기존 개인정보·권한 정책을 따름.

## 필수 QA 게이트

- [ ] 동일 SHA의 JVM/Android build/Lint/APK/AAB 성공
- [ ] API 29·35·36 emulator smoke 통과
- [ ] `stage14_extraction_one.png` · `stage14_extraction_three.png` · `stage14_extraction_tablet_largefont.png` 실캡처 파일 존재/화면 열람
- [ ] 실제 Android에서 구간 3개 → MP4 3개, 길이·프레임·오디오·이름 검증
- [ ] 부분 실패 후 저장된 결과 유지, 실패 구간만 재시도, 중복 결과 없음
- [ ] 앱 강제 종료/시스템 타임아웃/저장 취소 후 pending 출력 정리 및 안전한 복구
- [ ] 원본 유지·기존 빠른 자르기/일괄 편집/프로젝트/템플릿 회귀 없음
- [ ] 320dp 좁은 화면·폰 가로·태블릿·160% 폰트·폴더블 실기기 확인
- [ ] 이전 디자인 시안과 실제 렌더링 대조 및 잔여 문제 수정
- [ ] 검증 완료 후에만 PR `main` 병합 및 내부 테스트 게시 확인

**상태:** PR 초안 작성. 검증 항목은 실제 CI/실기기 결과가 확인될 때만 체크한다. 비공개 테스트 배포는 사용자 별도 지시 전 금지.
