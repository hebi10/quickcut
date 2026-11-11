# QC-02 — 동일 커밋 Android 릴리스 CI 검증

기준일: 2026-11-11 · 상태: 구현 및 CI 검증 중. **출시 승인/배포 완료가 아님.**

## 범위

- 현재 `main`의 정확한 HEAD SHA와 두 Android CI 워크플로의 `head_sha`가 일치해야 함
- `Android build`: `build` 작업 1개 성공
- `Android emulator smoke`: `emulator (29)`, `emulator (35)`, `emulator (36)` 작업 3개 각각 성공
- 각 작업은 `status=completed`, `conclusion=success`, 같은 `run_id`, 같은 `run_attempt` 및 같은 `head_sha`를 가져야 함
- 누락·중복·알 수 없는 작업, 페이지 누락, 재실행 중·실패·취소·건너뛰기, 잘못된 응답은 **승인 금지**
- 각 워크플로는 가장 최신 실행 결과만 인정함. 과거 성공과 최신 실패를 혼합하지 않음
- 자동 Play 게시·서명 이벤트에서는 두 워크플로 중 나중에 끝난 트리거 하나만 승인하여 중복 게시를 제한
- Google Play 심사·실제 게시 상태는 GitHub CI 성공과 별도로 Play API에서 검증

## 관련 파일

- `scripts/verify-release-gate.py`: 실제 GitHub 워크플로·작업 API의 독립 증거 확인
- `scripts/test-release-gate.py`: 누락/추가/중복/다른 SHA/다른 재시도/failed/skipped/진행 중/페이지 누락 차단 테스트
- `docs/21_PLAY_TEST_AUTOMATION.md`: Play 안전 게이트 계약

## QC-01 의존성

QC-01 PR #51은 API29에서 292개 중 일괄/단일 백그라운드 출력 2건을 독립 계측 프로세스로 분리함.
QC-02는 그 검증이 성공하기 전 QA 브랜치 또는 `main`에 병합하지 않음. 이번 작업 PR은 PR #51 브랜치를 base로 사용하여 합성 검증하며, PR #48/#49 및 Google Play 배포 트랙은 변경하지 않음.

## 완료 판정

1. QC-02 PR의 `Android build`와 `Android emulator smoke` API29/35/36 모두 동일 HEAD에서 SUCCESS.
2. `test-release-gate.py`에서 모든 차단/승인 회귀 테스트 통과.
3. QC-01 PR #51 성공 후 QC-02 PR 병합 및 PR #51/50 갱신 CI 재확인.
4. 최종 `main`에서 Android build + API29/35/36 전체 계측이 새 SHA로 SUCCESS, 릴리스 게이트가 해당 SHA 네 작업의 성공만 인정.
5. PR #50의 스토어 이미지 등록은 별도 승인된 QC-03 경로로 진행하며 Play API commit과 공개 상태를 독립 확인.
