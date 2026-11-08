# QuickCut — Modern Edit Desk 시각 QA 및 출시 준비

기준일: 2026-11-09 (KST) · 상태: **진행 중 / 출시 승인 아님**

## 1. 기준 소스 및 확인 가능한 실증 자료

- UI 기능 통합 선행 커밋: `c63d967da3afcbbcd55c4bca6ad85d301657a6d2` (PR #47의 이전 성공 HEAD). **현재 HEAD 또는 병합된 main의 결과가 아님**.
- 성공한 Android build: https://github.com/hebi10/quickcut/actions/runs/42490554712
- 성공한 Android emulator smoke (API 29·35·36): https://github.com/hebi10/quickcut/actions/runs/42490554753
- API35 full suite: **292 tests / 0 failures / 0 errors / 0 skips**. 실제 PNG 57개 중 디자인 검수 54개 모두 열람·파일 무결성 확인.
- Stage20 증명 JSON: Android UI 클래스 62종, 필수 13종, Stage14~18 실 PNG 18개(SHA-256·IHDR 포함), 커밋 SHA 기록.
- 이전 1-A API35 실패: https://github.com/hebi10/quickcut/actions/runs/42493681386. 292개 중 1개, `Stage16CropScreensInstrumentedTest.largeFontTabletCanReachApplyAction`의 `SnapshotStateObserver` 레이아웃 스레드 충돌. **레이아웃 잘림의 직접 증거가 아님**.
- Compose v2 기반 수정: PR #44~#47. 최신 CI 완료와 비교 전까지 해당 오류 해결을 확정하지 않음.

## 2. 화면별 실제 캡처 확인 및 잔여 커버리지

| 화면 | 기존 실캡처 | 다크 및 확장 검증 | 현재 판정 |
| --- | --- | --- | --- |
| 홈·하단 4개 탭 | 라이트·다크 1080×2400, 프로젝트 채움 상태 | 다크 320dp / 160% 신규 계측 | 부분 검증 |
| 프로젝트·도구·설정 | API35 강제 360dp 실 렌더 캡처 | 다크 320dp / 160% 신규 계측 | 부분 검증 |
| 빠른 자르기·상세 편집·타임라인 | 실제 영상 픽셀과 UI 포함 | 320dp 세부 잘림·다크 상세화면 전수 캡처 미완료 | 추가 검수 필요 |
| 여러 구간 추출·일괄·템플릿 | 기존 14·13단계 실제 Android 캡처 | 다크 설정·오류 상태 전수 확인 필요 | 추가 검수 필요 |
| 크롭 | 원본/9:16/1:1/자유 PNG, 160% 태블릿 강제 레이아웃 | v2 스레드 수정 CI + 다크 크롭 실제 적용 버튼 탭 검사 | 차단 검사 중 |
| 오디오 | 기본/페이드/무음/160% 캡처 | 다크 + 실제 적용 클릭 신규 계측 | 신규 검증 중 |
| 텍스트 | 빈 상태/선택/스타일/160% 캡처 | 다크 + 실제 적용 클릭 신규 계측 | 신규 검증 중 |
| 화질·내보내기·저장 | 자동/수동/미지원 옵션, 저장 모달 | 다크 저장 모달 신규 캡처 | 신규 검증 중 |
| 취소·삭제·복구·진단 모달 | 기존 실제 Android 캡처 존재 | 다크 전체 상태·160% 세부 버튼 배치 미완료 | 추가 검수 필요 |

중요: `ForcedSize(DpSize(860.dp,640.dp))`는 Compose 테스트 레이아웃 시나리오이며 **실제 860dp 태블릿 단말의 end-to-end 테스트를 대체하지 않음**. 이 시나리오의 화면 위쪽 상태바 겹침과 아래쪽 넓은 여백을 앱의 실기기 UI 결함이라고 단정하면 안 됨.

## 3. 색상 대비 사전 계산

1-A 컬러 역할 기준 WCAG 대비 계산(명도 공식, 알파 혼합 없는 정적 토큰 조합):

| 조합 | 라이트 | 다크 | 판정 |
| --- | ---: | ---: | --- |
| 기본 텍스트 / 배경 | 16.42:1 | 16.91:1 | AA 기준 충족 |
| 보조 텍스트 / Surface | 5.46:1 | 7.32:1 | AA 기준 충족 |
| OnAccent / 주요 버튼 | 16.72:1 | 16.46:1 | AA 기준 충족 |
| SecondaryAccent / 패널 | 8.17:1 | 9.89:1 | AA 기준 충족 |

명도 대비는 화면의 실제 텍스트 잘림, 확대 글꼴 레이아웃, 스크린리더 및 아이콘 동작을 증명하지 않음. 해당 사항은 Android UI 계측을 통과해야 함.

## 4. 실제 영상·오디오 사전 검증

**이전 성공 커밋(c63d967) 기준**:
- `stage17-output.mp4`: H.264 영상 + AAC 44.1kHz, 영상 길이 3.000초, 오디오 스트림 길이 약 2.959초. ffprobe로 별도 읽기 성공.
- PCM 변환 후 source/output RMS 비교: 원본은 약 0.089, 출력 중간 약 0.04, 시작/끝에서 감소. 기존 음량 변경·페이드 계측 결과와 부합.
- 동일 이전 성공 API35 full suite에서 `Stage17AudioExportInstrumentedTest` 4개, `Stage16CropExportInstrumentedTest` 4개, `Stage19CombinedExportInstrumentedTest` 1개, `Stage20RecoveryInstrumentedTest` 4개 통과.
- 원본 불변·출력 재인코딩 승인·과거 프로젝트(v2) 복구·작업 중단·재실행 안전성 테스트는 기존 전체 계측에 포함.
- **현재 main HEAD에 대한 동일 검증은 아직 수행되지 않음.**

## 5. 앱 아이콘·Google Play 이미지

기획: https://docs.google.com/document/d/1hyNminChkLsX3MHes3xM_m4KuuHTHD1gC0K8O5UnRCo/edit
기존 자료 폴더: https://drive.google.com/drive/folders/1CVSenIY8KBzj73OTHPXGY-eDSnLD0SZN

- Drive의 현재 512×512 `QuickCut-play-icon-512.png`는 민트/피치 3D 필름 아이콘. 1024×500 `QuickCut-play-feature-1024x500.png`와 스크린샷 1~5도 **구 디자인**임. Modern Edit Desk 새 앱 브랜드와 다르므로 최종 등록 자료로 사용 금지.
- 신규 `ic_launcher_foreground.xml`은 필름 프레임 + 컷 마커의 단색 기하학 마크. 홈 로고/스플래시/Android 13 모노크롬 아이콘에서 동일 리소스 사용.
- 후보 Play 아이콘은 512×512로 확보되었으나, 최신 릴리스 아이콘 검증·샘플 비교·콘솔 업로드 확인 전에는 **최종 승인/등록으로 표기하지 않음**.
- 현재 Play 설명 이미지 1~5는 구 UI 스크린샷이 포함되므로 최신 빌드의 라이트·다크 실제 화면으로 다시 구성 필요. 사용자에게 없는 기능·가상 영상을 보여주는 합성 화면 금지. 배경 검정·회색, 영상 원본/설명/타이포그래피 위계 중심으로 정렬.
- 기능 그래픽 1024×500 신규 제작·전수 검수, 스토어 정책·텍스트·콘텐츠 등 메타데이터 확인 전 신규 Play 게시 불가.

## 6. 출시 차단 게이트

1. PR #44 → #45 → #46 → #47 → QA PR #48 순서대로 최신 동일 HEAD 기준 Android build/Lint/unit과 API29·35·36 **모두 success**.
2. 실제 화면 캡처 320dp·일반 휴대폰·160% 글꼴·라이트/다크, 새 QA PNG 8장 무결성 및 버튼 클릭 확인.
3. 통합된 **main** 자체에서 다시 Android full suite/실제 MP4 H264+AAC/원본 보호/프로젝트 복구/계측 XML·PNG를 검증.
4. 512 Play 앱 아이콘과 1024×500 신규 기능 그래픽, 설명 이미지 모두 실제 새 브랜드와 일치 확인 후 최종 등록.
5. 신규 Play 테스트 트랙 게시 여부는 별도 명시 요청 및 CI/Play Console 상태 확인. UI QA 작업만으로 게시 완료를 주장하지 않음.

현재 출시 판정: **NO-GO (PR Draft 및 최신 Android/실캡처 검증 미완료)**.
