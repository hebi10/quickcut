# 0.4.1 안정화 및 고정 서명

## 변경 범위

- 빠른 가져오기 기본값을 해당 가져오기 성공에만 적용한다.
- 마지막 클립 삭제 후 빈 편집 화면에서 실행 취소·다시 실행을 유지한다.
- 정상 저장한 251개 이상의 편집 클립을 다시 읽을 수 있도록 복원 제한을 맞춘다.
- 빠른 미리보기 위치를 편집 모델에 반영해 화면 재생성과 상세 편집 전환에서 유지한다.
- 다른 폴더로 이동한 완료 출력은 유효성을 확인하여 보존한다. 미완성 출력 삭제의 소유권·폴더 확인은 유지한다.
- 미완성 출력 삭제가 확인되지 않으면 재인코딩 전환을 중단하여 일괄 작업의 출력 URI 추적이 덮어써지지 않게 한다.
- 고속 복사에서 자르기 종료점에 도달한 트랙을 해제하여 버릴 구간을 계속 읽지 않는다.

## APK 서명과 업데이트

앱 ID는 `com.hebi.quickcut`, 버전은 `0.4.1`, versionCode는 `6`이다.
0.4.1 배포 APK부터 보관된 고정 키를 사용한다. 개인키·비밀번호는 저장소에 포함하지 않는다.
기존 0.3.0/0.4.0 임시 디버그 키는 확보하지 못했으므로 이전 배포 APK 위에 덮어쓰기할 수 있다고 보장하지 않는다.
서명이 다른 기존 앱을 삭제하면 앱 설정·템플릿·편집 복원 기록도 삭제되므로, 진행 중 편집을 완료하고 필요한 설정을 기록한 뒤 전환해야 한다.
갤러리에 이미 저장한 원본·출력은 앱 내부 편집 기록과 별개다.

### 로컬 빌드

JDK 17, Android SDK 36, Build Tools 36.0.0을 설치한다.
다음 환경변수를 모두 설정한 환경에서 `./gradlew :app:assembleRelease`를 실행한다.

| 환경변수 | 값 |
| --- | --- |
| `QUICKCUT_KEYSTORE_PATH` | 보관한 키 파일의 절대 경로 |
| `QUICKCUT_KEYSTORE_PASSWORD` | 저장소 비밀번호 |
| `QUICKCUT_KEY_ALIAS` | `quickcut-release` |
| `QUICKCUT_KEY_PASSWORD` | 키 비밀번호 |

네 값이 모두 없으면 외부 서명용 `app-release-unsigned.apk`만 만든다. 일부만 설정하면 빌드가 실패한다.
서명 없는 APK는 설치용으로 배포하지 않는다. `debug` APK는 CI 검사용이며 버전 간 서명 호환성을 보장하지 않는다.

### GitHub Actions

`Android build`는 단위 테스트·Lint·debug 및 unsigned release 빌드를 검사한다.
`Signed release APK`는 수동 실행하며 다음 Repository Secrets가 모두 있어야 한다.

- `QUICKCUT_KEYSTORE_BASE64`: 보관된 키 파일의 Base64
- `QUICKCUT_KEYSTORE_PASSWORD`
- `QUICKCUT_KEY_ALIAS`
- `QUICKCUT_KEY_PASSWORD`

비밀값이 없으면 작업이 실패하며 대체 키를 생성하지 않는다. 서명 후 `scripts/verify-release-apk.sh`에서 `config/release-certificate.sha256`에 기록된 고정 인증서와 일치하는지 검사한다.
현재 연결 도구는 Secrets 등록을 지원하지 않으므로 등록 자체는 완료되지 않았다.
개인 서명 백업에 포함된 `configure-github-secrets.sh`를 GitHub CLI 로그인 환경에서 실행하면 이 저장소의 네 값을 등록한다.
서명 자료는 공개 아티팩트나 저장소에 올리지 않는다.

## 검증 범위

수정 전 코드에 회귀 검사를 먼저 적용하여 실패 원인을 확인하고 수정 후 동일 검사를 실행한다.
수정 전 재현 기록: [편집·복구·정리 검사](https://github.com/hebi10/quickcut/actions/runs/38712821113), [미리보기·고속 복사 검사](https://github.com/hebi10/quickcut/actions/runs/38714341247).
첫 실행에서 발견한 테스트 스레드·반환형·MediaStore 테스트 구성 오류는 제품 결함과 구분하여 수정했다. 소유권 검사는 실제 MediaStore 항목의 소유자 열만 대체하여 삭제 호출이 없는지 확인한다.
API 35 검사는 기존 검사와 함께 잘못된 가져오기, 실행 취소, 긴 세션, 미리보기 위치, 이동된 출력, 삭제 실패, 짧은 오디오·긴 영상 조합을 다룬다.
업데이트 검사는 현재 코드로 만든 versionCode 5 검사본에서 6으로 같은 CI 디버그 서명을 사용하여 `adb install -r`을 수행하고 앱 내부 파일이 유지되는지 확인한다.
이 검사는 기존 배포 APK 서명 호환성이나 실제 기종의 대용량 영상 성능을 증명하지 않는다.

실기기에서 4K·HEVC·HDR·긴 영상·대량 일괄 작업 및 원본 삭제 승인 흐름을 확인하는 QA는 계속 필요하다.
