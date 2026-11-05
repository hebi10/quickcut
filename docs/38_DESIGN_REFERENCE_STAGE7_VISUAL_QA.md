# QuickCut 디자인 재구현 7단계 — 실제 화면 비교 및 시각 보정

## 비교 자료
- 원본 기준: Google Drive `QuickCut_보라색_리디자인_수정시안.png`, 2026-11-05
- 보조 기준: `QuickCut_보라색_디자인가이드_화면흐름.png` 및 `QuickCut_보라색_디자인가이드_상세화면.png`
- 실제 앱 증거: GitHub Actions `Android emulator smoke` #42045006357, API 35, `quickcut-emulator-start-api-35` 산출물의 `quickcut-start.png`, `quickcut-design-review/editor_design_quick_trim.png`, `editor_design_detailed.png`, `dialog_save_settings.png`, `dialog_export_method.png`, `batch_review_default.png`.
- 실제 캡처는 1080×2400px(Android 15, API 35)이고 원본 시안의 스마트폰 화면은 다른 화면비를 사용한다. 픽셀 단위 오차를 산출하지 않으며 상대적 배치/정보 계층 차이를 우선 분석한다.

## 확인된 불일치
| 화면 | 실제 관찰 | 7단계 대응 |
| --- | --- | --- |
| 온보딩 | 기존 캡처는 정중앙 가위 아이콘과 단순 안내, 시안의 필름스트립 일러스트·로고와 다름 | 이 단계에서 실물 캡처를 보존하고 다음 에셋 작업 후보로 남김 |
| 홈 | 기존 구현은 메인 CTA보다 도구·정보 섹션이 과밀 | CTA와 프로젝트 헤더·전체 보기 및 하단 탭 중심으로 순서 단순화, 도구를 도구 탭에 배치 |
| 빠른 자르기 | 실제 캡처의 시간 입력·선택 정보 박스가 시안보다 복잡 | 4단계 구간 슬라이더 유지, 후속 시각 비교 후보 |
| 상세 편집 | 재생/타임라인/하단 기능의 순서와 비율이 상이 | 실제 동작 보존, 7단계 캡처 수집 강화 |
| 저장 설정/방식 | 시스템 기본 AlertDialog 폭이 시안 모달보다 좁음 | 16dp 수평 여백·최대 480dp인 공통 반응형 폭을 저장 관련 다이얼로그에 적용 |
| 일괄 편집 | 템플릿 관리/파일 검토 UI가 시안의 완성 템플릿 목록과 다름 | 실제 데이터 모델과 영상 처리 유지, 별도 기능 개발 필요한 영역으로 구분 |

## 신규 캡처 검증
- `Stage7ReferenceScreensInstrumentedTest`는 360×640dp 기기 크기로 최초 온보딩, 홈, 프로젝트 빈 상태를 렌더링하고 캡처를 `/data/local/tmp/quickcut-design-review/`로 보존한다.
- 기존 `EditorDesignInstrumentedTest`, `DialogDesignInstrumentedTest`의 실제 편집/모달 캡처는 계속 사용한다.
- 텍스트/UI 동작 검증과 이미지 캡처 수집은 별개이다. 캡처 저장 실패를 화면 구현 성공으로 간주하지 않는다.

## 추가 보정과 남은 차이
- 적용: 홈 시안에 맞춘 헤더·CTA·프로젝트 목록·탭 배치, 하단 내비게이션 높이, 저장 관련 모달의 모바일 폭, 홈/온보딩 캡처 계측.
- 미완료: 전용 스플래시 일러스트/온보딩 에셋, 앨범 전체 탐색, 자막/속도/회전 등 실제 영상 기능, 시안의 내보내기 품질 옵션, 완전한 템플릿 관리 화면.
- 시안 이미지에서 실제로 동작해야 하는 기능은 단순히 유사한 아이콘을 배치하는 대신 별도 기능 구현·권한 검토가 필요하다.
- 디자인의 **완전 동일** 판정은 시안과 동일한 화면비·글꼴·실데이터로 캡처한 최신 HEAD를 검수한 뒤에만 가능하다.

## 검증 및 병합 게이트
- PR #25 → #26 → #27 → #28 → #29 → 7단계 PR 순차 검증 및 main 병합.
- 각 최신 HEAD의 Android build(unit/Lint/APK/AAB) 및 Android emulator smoke API29/35/36 전체 성공.
- 기존 단계별 에뮬레이터 계측 실패를 미확인 상태에서 '통과' 처리하지 않는다.
- main 병합·GitHub Actions 결과·Google Play 내부 테스트 업로드 결과는 각각 별도로 확인한다.
