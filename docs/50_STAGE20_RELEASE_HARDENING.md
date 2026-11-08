# QuickCut 20단계 — 출시 전 무결성·복구 검증 강화

기준: 2026-11-08 / QuickCut Android 0.4.20 / 통합 19단계 PR #42의 브랜치를 기초로 한 별도 Draft PR.

## 목표
14~19단계가 통합된 상태에서, Android 테스트가 **일부 실행되지 않았거나 캡처가 가짜인 경우**에도 CI가 통과하는 것을 차단하고, 사용자의 기존 프로젝트·원본 영상·중단 저장 기록을 보호한다.

## 실제 구현
- `scripts/stage20-evidence-verify.py`: API별 실기기 계측 결과 XML에서 필수 Android 테스트 클래스 12종(14~20단계) 존재·성공 확인. PR 소스 HEAD SHA와 계측에 사용한 병합 체크아웃 SHA를 별도 기록하고 API 레벨을 매니페스트에 포함한다. CI에서 SHA/API 출처가 불분명하면 릴리스 증거 생성을 거부한다.
- 14~18단계의 실제 화면 캡처 18종을 모두 확인. PNG signature뿐 아니라 IHDR/IDAT/IEND·모든 청크 CRC·사이즈까지 검사.
- `scripts/android-instrumentation-check.sh`: 기존 전체 계측 및 PNG 수집 이후 Stage20 검증을 필수로 실행하고 최초 실패 코드를 보존.
- `scripts/test-emulator-flow.py`: 모의 CI에서 올바른 테스트 클래스 목록 및 실제 PNG 데이터를 만들도록 변경. 클래스 하나라도 누락되거나 adb 오류 문자를 이미지로 반환하면 CI 실패.
- `scripts/test-stage20-evidence.py`: 정상 케이스와 누락된 XML·클래스·PNG, 잘못된 CRC, fake adb 오류 데이터에 대한 CI 회귀 검사.
- `app/src/androidTest/java/com/hebi/quickcut/media/Stage20RecoveryInstrumentedTest.kt`: 이전 schema 2 프로젝트의 v5 복원, 크롭+오디오+텍스트 상태·음소거·트림의 영속성, 부분 완료/중단 구간 저널, 재인코딩 동의 우회 방지.

## 필수 최종 QA
- [ ] 20단계 최신 동일 HEAD에 대한 Android build / unit / Lint / unsigned APK·AAB 모두 성공.
- [ ] API29/35/36 각각 전체 Android suite 정상 종료 + 필수 클래스 12종 통과 + 14~18 캡처 18종 무결성 검사.
- [ ] Stage19 실제 MP4 동시 효과(해상도+크롭+오디오+텍스트), Stage14 개별 저장·부분 실패 복구 검증.
- [ ] 파일 이름·화면 배치·접근성·160% 폰트·폴더블과 기존 원본 시안 비교.
- [ ] Play 업로드키, 버전코드, Google Play 내부 테스트 자동 실행 안전성 검사.
- [ ] 기존 PR #37~#42와 브랜치 통합 순서/리뷰 및 최종 APK/AAB 결과 확인.

**정책:** 이전 커밋 또는 일부 API 성공을 전체 성공으로 간주하지 않는다. 이미 작동하는 원본 영상·편집·일괄 처리·템플릿 기능은 변경하지 않는다. 최신 전체 QA 통과 이전에는 PR Draft 유지, `main` 병합 및 Google Play 게시 보류.

상세 기획: https://docs.google.com/document/d/1fTi_fx0g6CF6SwpRKjrlYdbK9Pp9oAqmSg6To4GkhfI/edit
