# QuickCut 16단계 — 화면 비율·크롭

기획 기준: [14~19단계 신규 기능 디자인 기획서](https://docs.google.com/document/d/1fTi_fx0g6CF6SwpRKjrlYdbK9Pp9oAqmSg6To4GkhfI/edit)
기준: 15단계 PR #38 개발 브랜치를 기반으로 쌓은 단계(검증 완료 전 `main` 병합 금지).

## 이번 단계 구현

- 상세 편집 도구에 **비율·크롭**을 추가한다. 빠른 자르기·상세 편집의 기존 자르기/분할/삭제/순서/음소거 UI는 변경하지 않는다.
- 선택한 클립의 원본 프레임을 불러와 크롭 프리뷰와 타임라인을 분리한다.
- 프리셋 **원본 / 9:16 / 1:1 / 16:9 / 자유**. 프리셋은 현재 원본의 표시 방향(회전 메타데이터 포함)을 기준으로 픽셀 종횡비를 유지한다.
- 프레임 이동(드래그, 좌·우 버튼), 확대/축소(두 손가락 제스처와 버튼), 자유 비율의 폭·높이 슬라이더, 원본 비율 초기화, 적용·취소.
- 내부 좌표는 원본의 top-left 기준 `[0,1]` 정규화로 보존하고, Media3 `Crop`의 NDC 좌표로 변환한다. 유효 범위 밖 크롭은 허용하지 않는다.
- **크롭을 적용하지 않은 클립에는 영상 효과를 부여하지 않는다.** 기존 고속 스트림 복사 경로와 처리 방식을 유지한다.
- 크롭이 있으면 `ExportPlanner`에서 재인코딩 필요 사유를 표시하고, 사용자가 승인한 후에만 `VideoExporter`로 Media3 변환한다.
- 크롭 설정은 `VideoClip`의 선택적 필드로 추가되어 기존 `EditorSessionStore` 및 `ProjectStore`에서 저장·복원되며 `EditHistory` 실행 취소/다시 실행에 포함된다.
- 15단계 화질 설정과 같이 사용할 경우 **크롭 후 표시 영역**을 기준으로 인코더 지원 해상도/예상 용량을 판단한다.

## 코드 영향 범위

- `editor/CropGeometry.kt`: 비율·좌표, 중앙 크롭, 이동, 확대/축소, 자유 크기 보정, Media3 NDC 매핑.
- `editor/EditTimeline.kt`: 클립별 `CropRegion?`, 변경 불가능 타임라인 크롭 적용.
- `editor/EditorSessionStore.kt`: 기존 세션·프로젝트 JSON과 역호환되는 선택적 크롭 필드.
- `editor/EditorViewModel.kt`: 적용 시 checkpoint 기록·복원·자동저장.
- `ui/CropEditorScreen.kt`: 실제 파일 프리뷰, 프리셋, 제스처, 접근 가능한 터치 컨트롤.
- `ui/EditorScreen.kt`: 상세 편집의 새 크롭 도구 진입.
- `media/CompositionFactory.kt`: 편집 미리보기와 실제 내보내기에 동일한 효과 체인.
- `media/ExportPlanner.kt`, `media/VideoExporter.kt`: 크롭 선택 시 원본 복사 차단, 명시적 재인코딩 승인, 기존 저장 무결성·복구 유지.
- `media/ExportQualityInspector.kt`: 크롭 후 실효 크기로 출력 설정 검증.
- 단위 및 기기 테스트: 기하학·JPEG 미리보기 대신 실제 mp4 재생/출력·저장·테스트별 캡처.

## 검증 게이트

- [ ] 최신 PR HEAD 기준 Kotlin·Lint·단위테스트 성공.
- [ ] API 29·35·36 에뮬레이터 전체 회귀 테스트 성공.
- [ ] `stage16_crop_vertical.png`, `stage16_crop_square.png`, `stage16_crop_free.png`, `stage16_crop_largefont.png` PNG 증거 검증.
- [ ] 원본 320×240 예제에서 1:1 크롭 -> 실제 1:1 MP4 및 오디오·길이 검증.
- [ ] 세로 원본 9:16/가로 원본 16:9 및 회전 메타데이터와 실제 출력 일치.
- [ ] 크롭을 적용하지 않았을 때 기존 빠른 저장 경로·템플릿·원본 처리 정책 회귀 없음.
- [ ] 크롭 상태 저장/복원, undo/redo, 앱 종료·재실행·프로젝트 재개 상태 확인.
- [ ] 태블릿·폴더블·작은 화면·큰 글꼴의 실제 화면과 원본 기획 비교.
- [ ] 15단계 PR #38 검증·병합 후 충돌 없는 `main` 통합 검증.

**진행 정책:** 위 조건 통과 전에는 16단계 PR을 Draft로 유지하며 `main` 병합이나 Google Play closed 트랙 게시를 하지 않는다.
