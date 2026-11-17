# QuickCut 광고 연동 — QC-AD-01

상태: Google Mobile Ads SDK + UMP + 홈 하단 배너 코드 준비. 운영 광고 ID 미연결, Play 배포 미실행.

## 광고 위치·표시 조건
- 광고 형식: 홈 탭 최하단에 표시하는 Google AdMob 배너 1개. 인앱 편집·미리보기·저장·원본 삭제·복구·완료 화면 및 나머지 탭은 광고 요청하지 않음.
- 별도의 전면 광고·보상형 광고·추가 광고 슬롯 없음.
- 앱 실행 시 Google UMP 동의 상태를 갱신하고, 가능한 경우에만 SDK를 초기화하고 배너를 로드함. 동의 정보가 없으면 요청하지 않음.
- UMP 개인정보 선택 메뉴가 필요한 사용자에게만 앱 설정 탭에 "광고 개인정보 선택 변경" 버튼 표시.
- 향후 Google Play Billing에서 확인한 광고 제거 권한이 있으면 광고 뷰 자체가 렌더링되지 않도록 `AdDisplayPolicy` 진입점을 마련. 현재 앱에는 Billing 구매·복원·구독 검증 기능을 추가하지 않음.

## 연결 및 빌드 안전장치
- 사용한 SDK: Google Mobile Ads (Legacy) `25.5.0`, UMP `4.0.0`. Android API 29/35/36 및 기존 Media3·네이티브 편집 엔진 보존.
- `debug`는 기본적으로 광고 꺼짐. 테스트 광고 확인이 필요할 때만 Gradle 속성 `-PquickcutTestAds=true`로 켜며, 공식 Google 테스트 앱·배너 ID를 사용함.
- `release`는 서로 일치하는 운영용 AdMob **앱 ID**와 해당 앱의 **배너 광고 단위 ID**가 지정된 경우에만 광고를 활성화함. 계정/앱 등록 전에는 테스트용 앱 ID가 메타데이터에 있더라도 네트워크 광고 요청을 실행하지 않음.
- 운영 구성은 CI 비밀 변수 `QUICKCUT_ADMOB_APP_ID` / `QUICKCUT_ADMOB_BANNER_UNIT_ID`로 전달. 두 값이 비어 있으면 안전하게 광고 비활성화, 한쪽만 있거나 형식이 잘못되면 빌드 실패. 값은 Git 이력·로그에 출력하지 않음.
- GalliPick·다른 앱의 AdMob 앱 ID/광고 단위 ID는 QuickCut에 재사용하지 말고 QuickCut `com.hebi.quickcut`용 앱과 광고 단위를 별도 생성.

## 광고 송출 전 남은 외부 작업
1. AdMob에 Android 앱 `com.hebi.quickcut` 등록 및 배너 광고 단위 생성. 해당 값들을 GitHub Actions 비밀 변수에 저장.
2. AdMob의 개인정보 보호 및 메시지에서 해당 앱의 UMP 메시지를 설정하고 필요한 동의·개인정보 선택 메뉴를 확인.
3. Play Console 광고 포함 신고와 데이터 보안(Advertising ID/광고 SDK 수집 항목) 재검토. 광고 개인정보처리방침 공개 링크가 실제로 갱신되었는지 확인.
4. AdMob `app-ads.txt`의 게시자 항목을 승인된 개발자 사이트 루트에 배포하고 AdMob 계정에서 확인.
5. 테스트 광고가 테스트 기기에서만 표시되는지, 해상도·화면 비율·대형 글꼴/폴더블·다크 모드 및 오프라인에서도 편집·저장이 정상인지 확인.
6. QuickCut은 기존 SmartCut을 포함하는 GPLv3 대응 소스 제공 릴리스이므로, AdMob/UMP 라이브러리의 라이선스 호환성 및 배포 조건을 확인하고 `package-smartcut-source.sh`의 대응 소스 수집 게이트를 통과해야 함. 소스가 제공되지 않는 의존성을 임의로 예외 처리하여 게이트를 우회하지 않음.
7. 기존 PR #74~#76의 검증·병합 상태와 이 광고 PR의 CI를 확인한 뒤, 별도의 배포 승인 단계로 진행.

## 향후 광고 제거 결제(이번 PR 범위 밖)
- 예정: 월간 자동 갱신 1,000원 / 연간 자동 갱신 9,900원 (대한민국 기준).
- Play Billing이 포함된 서명 AAB를 테스트 트랙에 업로드한 뒤 구독 상품 및 요금제를 설정하고, 서명된 구매 토큰을 서버/Google Play API로 검증해 권한을 반영해야 함.
- 구독 승인·만료·환불·복원 시 광고 표시 상태를 갱신해야 함. 로컬 임의 토글을 결제 인증으로 취급하지 않음.

공식 문서:
- https://developers.google.com/admob/android/quick-start
- https://developers.google.com/admob/android/privacy
- https://developers.google.com/admob/android/banner


## 2026-11-17 — GPLv3/SDK 대응 소스 검증 상태

- SDK sources JAR 누락 11개가 광고 브랜치 CI의 소스 패키징 실패 원인입니다. `docs/AD_LICENSE_REVIEW.md`에 모듈별 목록 및 확인해야 할 권리를 정리했습니다.
- CI 진단 아티팩트 `quickcut-runtime-source-audit`는 법적 승인 자료가 아니라 소스 JAR 해석 결과입니다. 누락 시 기존 릴리스 차단을 유지합니다.
- 광고·구독은 승인된 권리 검토 및 전체 QA가 완료되기 전에는 병합·서명·Play 배포하지 않습니다.
