# QC-AD-LICENSE — 광고 SDK 대응 소스·배포 적합성 검토

상태: **차단 유지 / 출시에 포함 금지** (2026-11-17). 이 문서는 기술적 증거이며 법률 자문 또는 GPLv3 예외 승인 문서가 아닙니다.

## 확인된 실패

- 대상: PR #77, HEAD `9efa0668fda38a5f4c4d88cc80de61b62387d840`
- CI: https://github.com/hebi10/quickcut/actions/runs/43806995433
- 단계: `scripts/package-smartcut-source.sh` → Gradle `:app:collectQuickCutReleaseSources`
- 실패: `Missing runtime dependency sources` (소스 JAR을 해석하지 못한 런타임 모듈 11개)
- 정상 기준선: `main` SHA `6da96314d2a0e8fec7e272c9246d8eca2089faa5`의 Android build/OPS-08 및 API29·35·36 전체 계측 성공. 해당 기준선에는 광고 SDK가 포함되지 않습니다.

### 소스 JAR 미확보 모듈 (실패 로그 확인)

1. `com.google.android.gms:play-services-ads:25.5.0`
2. `com.google.android.gms:play-services-appset:16.0.1`
3. `com.google.android.gms:play-services-basement:18.9.0`
4. `com.google.android.gms:play-services-tasks:18.2.0`
5. `com.google.android.ump:user-messaging-platform:4.0.0`
6. `com.google.android.gms:play-services-ads-identifier:18.0.0`
7. `com.google.android.gms:play-services-ads-api:25.5.0`
8. `com.google.android.play:hsdp:2.0.1`
9. `com.google.android.gms:play-services-measurement-sdk-api:20.1.2`
10. `com.google.android.gms:play-services-measurement-base:20.1.2`
11. `com.google.android.gms:play-services-base:18.0.0`

**의미 구분:** Maven sources JAR의 부재는 프로젝트 전체 소스의 부재나 특정 라이선스 위반을 자동으로 입증하지 않습니다. 반대로 Google Play Services라는 이유만으로 GPLv3 시스템 라이브러리 예외나 재배포 권한을 인정할 수도 없습니다.

## 이번 진단 개선

`scripts/package-smartcut-source.sh`에서 Gradle 런타임 소스 해석이 완료된 직후, 성공/실패와 무관하게 `build/runtime-source-audit/dependencies.json` 파일을 생성합니다. JSON에는 해석된 런타임 의존성, 소스 JAR 누락 목록, 해석된 소스 JAR 수, 성공 여부만 포함되며 로컬 경로·토큰·서명 정보는 포함하지 않습니다.

CI는 `quickcut-runtime-source-audit` 아티팩트를 `if: always()`로 보관합니다. 독립 검증기 및 8개 회귀 케이스로 진단 스키마·목록 무결성을 확인합니다. **기존 누락 소스 오류 및 릴리스 차단 동작은 그대로 유지**합니다. 이 보고서가 성공해도 GPLv3 적합성, 법적 권리, 서명 AAB·Play 게시 승인을 의미하지 않습니다.

## 결정 전 확인 사항

- [ ] 실제 APK/AAB의 모든 Google 광고·UMP·Google Play Services 모듈과 각 라이선스 텍스트·재배포 권한 명세 확보
- [ ] SDK 배포 약관과 SmartCut GPL-3.0-only 및 서드파티 저작권자 권리의 양립 여부 검토
- [ ] GPLv3 예외/권리 변경이 필요한 경우 해당 소스의 **실제 저작권자별** 허용 근거 확보
- [ ] 각 모듈별 시스템 라이브러리 예외 적용 가능성 검토. 앱에 번들되는 실행 코드에 예외를 자동 적용하지 않음
- [ ] 재현 가능한 대응 소스·라이선스 기록과 함께 CI 소스 패키징 성공
- [ ] 최신 `main` 기반 광고 PR 코드·전체 CI(API29·35·36 포함)·원본 보존 및 동의/광고 미송출 회귀 검증
- [ ] **모든 기획 기능과 최종 실기기 QA** 종료 후에만 별도 승인으로 배포 단계 진입

대안: (1) 정식 권리 근거를 확보하고 광고 연동 계속, (2) 적법성 확인이 가능한 광고/수익화 방식으로 별도 설계, (3) 광고 통합 보류 및 기존 오프라인 편집 기능 유지. 어떠한 선택도 이번 코드 변경만으로 자동 승인되지 않습니다.

공식 참고: https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs · https://www.gnu.org/licenses/gpl-faq.en.html#SystemLibraryException · https://developers.google.com/mobile/terms · https://developers.google.com/admob/android/terms

연관: https://github.com/hebi10/quickcut/issues/80 · https://github.com/hebi10/quickcut/pull/77
