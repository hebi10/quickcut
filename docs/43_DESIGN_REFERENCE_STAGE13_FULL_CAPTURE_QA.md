# QuickCut 13단계 — 전체 Android 화면 캡처 비교 및 잔여 디자인 보정

## 기준 자료

- Google Drive 원본 `QuickCut_보라색_리디자인_수정시안.png` (ID `1_kQSvWxXVdyIx0lbsSa5O5PWk_dFwPxW`)
- Google Drive `QuickCut_보라색_디자인가이드_상세화면.png` (ID `15DymnTIXdh5PbFbgdJj5Leh1bOq6MP7W`)
- Google Drive `QuickCut_보라색_디자인가이드_화면흐름.png` (ID `1ks7bEfbiHdBB4vdWK9bbz-FojUcLELOG`)
- 수정 전 Android 실제 캡처: 12단계 API35 아티팩트 `quickcut-emulator-start-api-35` 및 그 안의 `quickcut-design-review/`. 12단계의 디자인 변경은 `main` 커밋 `9031e7013ca842c6199e4419cf175c4fd93ae59d`에 병합됨.

## 화면별 실캡처 대조

| 화면 | 시안에서 강조하는 구조 | 실제 12단계 캡처에서 확인된 차이 | 13단계 조치 |
| --- | --- | --- | --- |
| 스플래시/온보딩 | 로고, 보라색 필름아트, 다음 버튼 | 필름아트와 주요 CTA는 표시, 일러스트·배경은 시안과 1:1 아님 | 기존 자산 유지, `stage7_onboarding.png` 재캡처 |
| 홈 | 헤더 → 하나의 주요 CTA → 최근 프로젝트 → 4개 탭 | 헤더에서 같은 부제 반복, 프로젝트가 없는 테스트 상태만 캡처 | 부제 중복 제거, 빈/프로젝트 보유 상태 모두 캡처 |
| 프로젝트 | 실제 영상 썸네일과 클립·수정 날짜 | 기존 증거는 빈 상태, 복구 없음 문구와 빈 상태 안내 중복 | 빈 상태 문구 통합·시작 CTA, 썸네일 있는 프로젝트 캡처 |
| 영상 선택 | 넓은 2열 영상 썸네일과 선택 상태, 하단 주요 버튼 | 기존 앱은 112dp 기준 적응형 3열, 실제 선택 화면의 캡처 누락 | 모바일 2열, 선택 테두리·체크·선택 수, 동작 가능한 버튼 유지 |
| 빠른 자르기 | 미리보기·구간 타임라인·저장 | 이전 화면 실캡처 보유, 소스 영상은 테스트 패턴 | 동일 소스·상태로 `editor_design_quick_trim.png` 재검수 |
| 상세 편집 | 미리보기 → 시간 → 타임라인 → 편집 도구 | 12단계에서 5개 도구 한 줄·오버레이 재생 보정 | `editor_design_detailed.png`, 320dp 캡처 재검수 |
| 일괄 편집/템플릿 | 파일 목록, 템플릿 및 설정 | 실제 데이터 모델에는 시안의 가상 영상 썸네일이 없음 | 기존 영상 선택·템플릿 관리 캡처 재검수; 가짜 정보 미추가 |
| 저장 및 모달 | 명확한 주 동작·상태·경고 | 필수 경고 설명이 시안보다 상세함 | 데이터 보호·원본 삭제 안내는 유지, 기존 Dialog 캡처 모두 보존 |
| 완료/실패/진단 | 저장·공유·복귀 선택, 오류 해석 | 실제 저장/실패 분기는 미디어 처리 결과 의존 | 기존 기능 테스트 보존, 실기기 사용자 검수 별도 |

원본 시안의 자막, 속도, 회전, 색상 조절, FPS·해상도 등 실제로 없는 기능은 UI 장식으로 만들지 않는다. Android Photo Picker에서 사용자가 선택하지 않은 전체 갤러리를 읽지 않으며, 가상의 앨범 필터를 표시하지 않는다.

## 신규 화면 캡처 증거

`Stage13ReferenceScreensInstrumentedTest`는 테스트 APK의 실제로 디코딩 가능한 `quickcut_keyframes.mp4`를 사용하며, **실제 사용자의 파일이나 프로젝트를 변경하지 않는다.** 프로젝트 이름은 계측 검수를 위한 테스트 데이터다.

추가로 보존해야 하는 캡처:
1. `stage13_home_populated.png`
2. `stage13_projects_populated.png`
3. `stage13_tools.png`
4. `stage13_settings.png`
5. `stage13_gallery_selected.png`
6. `stage13_gallery_one_selected.png`
7. `stage13_projects_empty_large_font.png`

기존 `stage7_home.png`, `stage7_projects_empty.png`, `stage7_onboarding.png`, `editor_design_quick_trim.png`, `editor_design_detailed.png`, `batch_*`, `dialog_*`는 삭제하지 않고 전부 유지한다. `scripts/android-instrumentation-check.sh`에서 앱 개인 파일의 새 캡처까지 `run-as`로 꺼내고 파일이 없으면 테스트를 실패 처리한다.

## 보정 범위

- 홈: 헤더 안에서 이미 보이는 부제 반복 제거, CTA/프로젝트 정보 우선순위 유지
- 프로젝트: 비어 있을 때 중복 안내 삭제, 단일 안내와 기존 빠른 편집 진입 CTA 제공; 카드 외곽선으로 목록 구분 강화
- 영상 선택: 600dp 미만은 2열, 큰 화면은 적응형 그리드; 실제 선택된 URI만 표시하고 보라색 체크·선택 수·선택 해제 결과를 동기화
- 원본 유지·삭제 승인, 코덱·인코딩·저장 결과, 프로젝트 스키마, 배포 정책 불변

## 검증 및 완료 판정

1. **최신 PR HEAD**의 Android build(단위 테스트/Lint/APK/AAB) 성공.
2. 동일 SHA의 Android emulator smoke(API29·35·36) 성공, 특히 13단계의 신규 캡처 7장과 기존 증거가 실제 artifact에 존재하는지 확인.
3. 실제 캡처를 기준 이미지와 같은 화면 폭/서체/데이터 조건에서 대조한다. 기존 원본 시안은 합성 목업이므로 해상도 차이에 따른 픽셀 단위 유사도를 완료 근거로 쓰지 않는다.
4. 회귀 오류가 없을 때만 `main` 병합, 병합 SHA에서 다시 CI 확인.
5. 내부 테스트만 기존 자동 게시 정책을 사용한다. 비공개 테스트는 사용자의 별도 요청 전 게시하지 않는다.

## 의도적으로 남기는 실기기 항목

- 폴더블·태블릿의 실제 힌지/회전/분할 화면
- 인물·풍경·세로 영상 등 실제 사용자 미디어의 썸네일 품질
- Android Photo Picker 실제 권한 UX와 Play 테스트 설치본
- 시스템 스플래시 및 제조사 커스텀 UI

## 13단계 추가 — 런처 브랜드 아이콘 동기화

- 기존 APK의 `ic_launcher_foreground.xml`이 민트색 Pocket Cut Studio 마크의 `quickcut_launcher_monochrome.png`를 흰색으로 틴트해 표시하는 것을 확인. 화면 내부 `QuickCutLogo`와 보라색 리디자인 시안의 가위·재생 마크와 불일치.
- 새 적응형 아이콘: 기존 브랜드 배경색 `#6D28D9` 위 흰색 가위·재생 표식. `drawable/ic_launcher_foreground.xml`의 네이티브 VectorDrawable로 표현하며 민트 로고 이미지는 더 이상 런처에 참조하지 않는다.
- Android 8+ 적응형 아이콘의 `foreground`는 새 표식을 사용하고, Android 13+ 단색 테마 아이콘의 `monochrome`도 동일 표식으로 연결. Manifest의 앱 아이콘/원형 아이콘 및 시스템 기본 스플래시는 기존 `@mipmap/ic_launcher`를 유지해 새 자산을 공유.
- Android build에서 `scripts/test-launcher-icon.py`로 Manifest 및 v26/v33 아이콘 리소스를 검사한다. 앱 이름·인코더·앱 패키지/서명·Play 테스트 트랙 설정은 변경하지 않는다.
- Google Play 스토어 등록정보의 512px 그래픽은 Android APK 런처 리소스와 별개이므로 자동 변경 완료로 간주하지 않는다.
