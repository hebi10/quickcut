# QC-03 — Google Play 한국어 이미지 등록·검증

기준: 2026-11-11. 대상 앱 `com.hebi.quickcut`; 등록 언어 `ko-KR`.

## 승인된 등록 자료

- `store/google-play/icon.png` — 512×512 Google Play 아이콘
- `store/google-play/featureGraphic.png` — 1024×500 대표 그래픽
- `store/google-play/ko-KR/phoneScreenshots/01.png`~`08.png` — 1080×1920 실제 Android 캡처 기반 최종 문구 8장
- 스크린샷 8장의 순서·SHA-256은 `store/google-play/ko-KR/phoneScreenshots/manifest.json`과 반드시 일치해야 함
- 해당 원본은 기존 PR #49의 Git blob과 일치하는 자료이며, 기존 Google Drive의 오래된 개별 PNG 대신 최종 ZIP 원본으로 검증됨

## 보호 규칙

1. 기본 `main`의 동일 SHA에서 Android build + 에뮬레이터 API29·35·36가 전부 성공해야 이미지를 업로드할 수 있음. QC-01 PR #51·QC-02 PR #52의 수정 사항을 포함하고 완전한 CI가 통과해야 함.
2. API는 기존 `ko-KR` listing 존재 여부를 확인하고 해당 `icon`, `featureGraphic`, `phoneScreenshots` 세 이미지 유형만 한 개 edit 안에서 교체. 앱 버전, 테스트 트랙, 가격, 앱 기능, 다른 언어 및 자산 변경 금지.
3. 이미지 업로드 직후, 같은 **미커밋 edit**에서 이미지 유형별 수·순서·SHA-256을 다시 조회. 승인된 원본과 조금이라도 다르면 commit 거부하고 미커밋 edit 폐기.
4. `edits.validate` 이후 `edits.commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW` 실행. 기존 심사가 진행 중이라면 심사를 취소하지 않고 오류 처리.
5. commit 성공은 Play의 **edit 수락**을 의미하며, 곧바로 스토어 공개·심사 승인·테스터 설치 상태가 확인된 것은 아님. Play Console 게시 개요 또는 별도 API/사용자 화면으로 확인 전에는 `공개 완료`를 보고하지 않음.
6. commit 네트워크 오류는 성공/실패 불확실 가능성이 있으므로 자동 재시도하지 않음. 원본 이미지와 결과 증거를 점검해 중복 게시를 피함.

## 검증 증거

- PR 이미지 검증: GitHub Actions의 `QuickCut Play listing images` / `validate` job
- Android 검증: 새 `main`에서 `Android build`와 `Android emulator smoke`의 API29/35/36 별도 작업 success
- 업로드 검증: `publish` job의 `precommit_sha256_verified=true`, `mode=committed` 또는 `mode=unchanged`, 이미지 유형별 1+1+8개
- 최종 상태: Play API edit commit 응답과 스토어 상세 페이지/콘솔 심사 현황을 구분해 기록

최종 상태: PR 및 CI 진행 중. `main` 병합·Play image commit·심사 상태 확인 전에는 배포 완료로 표기하지 않음.

## 스토어 이미지만 바꿀 때 앱 버전 불변

- `main`의 스토어 이미지/문서/CI-only 커밋도 Android 두 워크플로를 트리거하므로, 기존 Google Play internal 자동 업로드 또는 GitHub Signed release 워크플로가 불필요한 새 앱 버전을 만들 수 있었음.
- `scripts/require-app-release-change.py`: 자동 `workflow_run`에서 현재 HEAD와 첫 부모 사이의 변경 파일을 검증하고, 앱 실행 리소스·네이티브 소스·빌드 구성의 실제 변경이 있는 경우만 앱 버전 릴리스 승인. 소스가 없거나 Git 비교 실패하면 자동 릴리스 거부.
- `.github/workflows/play-closed-test.yml`, `.github/workflows/release.yml`: automatic-only gate 적용. **명시적 수동 앱 릴리스**는 기존 승인 조건을 따르며 방해하지 않음.
- `.github/workflows/play-listing-images.yml`: 이번 보호 대상으로 삼지 않으므로, 이미지 변경은 동일 HEAD 전체 CI 성공 후 기존 이미지 등록 자동화를 그대로 진행.
- `scripts/test-app-release-change.py`: 스토어 파일/CI-only 변경 시 앱 자동 릴리스 차단 및 앱 소스 변경 시 기존 자동 릴리스 허용을 회귀 검사.

주의: 스토어 이미지 edit commit만으로 Google Play 외부 공개나 심사 완료가 증명되지는 않음.
