# UI-08-A — 전체 기능 회귀·최종 QA·PR 순차 병합·출시 준비

작성 기준: 2026-11-12 · Drive 기획:
https://docs.google.com/document/d/1hyNminChkLsX3MHes3xM_m4KuuHTHD1gC0K8O5UnRCo/edit

## 작업 원칙
- 최종 릴리스 심사는 **Android 실제 빌드/계측·실 MP4/AAC 출력·원본 보호·화면 증빙**이 일치할 때만 통과합니다.
- 단순 APK 빌드, 임의의 PNG, 시뮬레이션된 '저장 성공' 문구를 실제 출시 증빙으로 대체하지 않습니다.
- 이 작업은 릴리스 대비 QA이며, 서명/Play internal/closed 트랙이나 앱 버전은 자동 변경하지 않습니다.
- `UI-03-A` → `UI-07-A` 단계의 계층형 PR을 반드시 **검증·최신 head 확인 → base 최신 main 변경 → merge commit 병합** 순으로 적용합니다.

## 2026-11-12 첫 기준선 확인

| PR | 화면/기능 | 최신 빌드 | API 29/35/36 | 판정 |
| --- | --- | --- | --- | --- |
| #55 | UI-03-A 온보딩·홈·설정 | 성공 | 모두 성공 | **main 병합 완료** (0bc39e63) |
| #56 | UI-04-A 빠른 자르기·상세·타임라인 | 성공 | 모두 성공 | **main 병합 완료** (a4b14f9a) |
| #57 | UI-04-B 구간 추출·일괄·템플릿 | 성공 | 모두 성공 | **main 병합 완료** (8310de60) |
| #58 | UI-05-A 크롭·오디오·텍스트 | 성공 | 29·36 성공, 35 오류 | API35 실패 job만 재실행, 재검증 보류 |
| #59 | UI-05-B 내보내기/완료 | 이전 SHA 성공 | 캡처 빈 화면 확인 | PNG 렌더 대기·중앙 영역 검사 후 최신 SHA 재검증 |
| #60 | UI-06-A 오류·원본 삭제 | 이전 SHA 성공 | 복구 진단 테스트 문구 변경 실패 | 실패 테스트 수정 후 최신 SHA 재검증 |
| #61 | UI-07-A 라이트·다크 40장 | 대기 | 대기 | 안전 영역/캡처 검증 보완 후 최신 SHA 재검증 |

이 표는 해당 기준선에서 확인한 이력이며 **PR 자동 실행 중 상태는 시간이 지나면 바뀔 수 있습니다.** 병합 시에는 언제나 최신 GitHub API를 다시 대조합니다.

## 실제 아티팩트 QA 발견사항

- API 35 실캡처 `quickcut-emulator-start-api-35`를 PR #56·#57·#59로부터 다운로드해 핵심 4탭/편집/내보내기/큰 글꼴을 확인했습니다.
- 기존 PR #59의 `ui05b_export_progress.png` 및 `ui05b_export_completed.png`는 PNG 무결성 검증을 통과했어도 **실제 콘텐츠 중앙이 단색**으로 표시된 사례입니다. 앱 저장 로직이 실패했다고 단정할 수 없지만, 이 사진으로 완료 UI의 렌더 성공을 증명할 수는 없습니다.
- #59·#60·#61의 기존 계측 메서드에 렌더 대기·중앙 영역 다중 색상 검사·최대 8회 재캡처를 적용했습니다. 전부 실패하면 테스트가 실패하도록 변경했습니다.
- UI-07-A 캡처기 또한 상태바/내비게이션바 색만으로 빈 콘텐츠가 통과하지 못하도록 중앙 앱 영역을 검사하고, 독립 캡처 화면은 실제 앱의 `safeDrawingPadding`을 사용합니다.
- #60의 `BatchRecoveryUiInstrumentedTest`에 남아 있던 삭제된 줄바꿈까지 포함한 안내 문구 단언을 최신 UI에 맞게 바로잡았습니다. 원본/진단/복구 동작 자체는 바꾸지 않았습니다.

## UI-08-A 통합 출시 증빙 게이트

`scripts/verify-ui08a-release-evidence.py` (Python 표준 라이브러리만 사용)가 다음 모두를 검사합니다.

1. 현재 API(29/35/36)의 전체 Android XML 실제 test case·class 수, 실패·건너뜀 0건. API29는 정확히 **292개 테스트**.
2. MP4/크롭/오디오/복합 인코딩, Android 원본 보호, 일괄 저장, 단일 복구, 백그라운드 내보내기 등 필수 Android 클래스의 실제 실행 여부.
3. Stage20 `verified-screens-and-tests.json`의 소스 SHA·테스트 SHA와 PR/checkout SHA 일치, Media UI 18장 PNG 실제 파일의 크기·SHA-256 및 CRC 일치.
4. UI07A `visual-matrix.json`의 **20쌍/40장** 테마별 SHA-256/해상도와 실제 파일 대조. 동일 라이트·다크 프레임은 거부.
5. 저장 중·완료, 원본 삭제 경고, 취소·복구·오류 등 안전 UI 9장 필수 존재와 PNG 무결성.
6. 상기 조건 모두 통과할 때만 `quickcut-ui08a-evidence/release-qa.json` 생성. CI에는 API별 아티팩트 `quickcut-ui08a-release-qa-api-N`으로 보존.

## 최종 수동/실기기 필수 체크

- 앱의 실제 라이트·다크 40장 원본 화면을 사람이 직접 대조해야 하며, 이미지 파일이 유효하다는 것만으로 디자인 QA 통과라고 기록하지 않습니다.
- 320/360/412dp, 가로 화면, 160~200% 글꼴, 폴더블/태블릿, 키보드 노출 및 주요 CTA의 잘림/겹침은 별도 이슈와 재캡처로 남깁니다.
- Android 시스템 삭제 승인창·원본 SHA-256 검증, MP4/AAC 출력/재생과 프로젝트·일괄 작업 복구는 계측 로그 및 필요 시 실기기 검증이 완료되어야 합니다.
- 현재 코드/PR 상태 확인과 **Google Play 내부·비공개 게시 성공 여부 확인은 별도 단계**입니다. GitHub 통과만으로 Play 게시를 주장하지 않습니다.

## 순차 병합 완료 판정

1. #58 API35 재검증 통과, 최신 정확한 SHA 확인 후 main으로 안전 병합.
2. #59의 **새 렌더 검증**을 포함한 전체 API QA 및 실캡처를 확인한 뒤 병합.
3. #60/61의 최신 QA와 40장 테마 캡처를 모두 확인한 뒤 병합.
4. UI-08-A PR의 base를 병합된 main으로 변경해 최신 커밋에서 Android build·API29/35/36·통합 증빙 JSON을 다시 검증한 후에만 병합.
5. 병합된 main SHA에서 별도 Android 빌드/서명·정확한 Play track 등록 상태를 재확인. 서명 AAB 업로드 및 비공개 테스트 게시 요구는 명시 승인 범위 내에서 별도 수행.

## 작업 링크
- GitHub 저장소: https://github.com/hebi10/quickcut
- PR #55~#61: https://github.com/hebi10/quickcut/pulls
- Actions: https://github.com/hebi10/quickcut/actions
- 기획 Drive 폴더: https://drive.google.com/drive/folders/1V8N0OS1j57eoD0OGDtZfcBtwIWxKjMA5
