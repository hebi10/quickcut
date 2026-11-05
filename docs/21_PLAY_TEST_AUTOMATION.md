# Google Play 테스트 배포

`Google Play test release`는 `main`의 `Android build` 및 `Android emulator smoke`가 같은 커밋에서 성공한 뒤 기존 내부 테스트 트랙을 업데이트합니다. 수동 실행의 `target`은 `internal`(기본), `closed`입니다. 비공개 테스트는 수동 실행에서 `closed`를 명시적으로 선택한 경우에만 업데이트합니다. 자동 실행에 비공개 대상이 전달되면 게이트와 게시 스크립트가 거부합니다. 프로덕션과 오픈 테스트 배포, 테스터 목록 변경은 지원하지 않습니다.

기존 `Signed release APK`의 GitHub 태그·릴리스 생성은 그대로 유지됩니다. Play 게이트는 기존 GitHub 태그가 있어도 실행할 수 있으나, 현재 `main` HEAD와 두 검증 워크플로의 최신 성공 실행을 반드시 확인합니다. 새 커밋에 대한 검증이 끝나지 않았거나 검증이 실패한 경우 서명 및 배포를 시작하지 않습니다. 빌드 후 게시 직전에도 같은 조건을 확인합니다. 자동 실행은 두 필수 CI 중 나중에 완료된 최신 실행의 ID와 시도 번호에 해당하는 이벤트만 허용합니다. 완료 시각이 같으면 실행 ID가 큰 쪽을 선택하므로 지연 도착한 이전 완료 이벤트가 중복 버전을 만들지 않습니다. 수동 실행은 이 이벤트 선택 조건을 적용하지 않습니다.

## 최초 연결

기존 QuickCut 업로드 키에 대응하는 다음 네 GitHub Actions 시크릿이 모두 필요합니다. 하나라도 없으면 네이티브 빌드 전에 실패합니다.

| 시크릿 | 값 |
| --- | --- |
| `QUICKCUT_KEYSTORE_BASE64` | 기존 keystore 파일의 Base64 |
| `QUICKCUT_KEYSTORE_PASSWORD` | 기존 keystore 암호 |
| `QUICKCUT_KEY_ALIAS` | 기존 키 별칭 |
| `QUICKCUT_KEY_PASSWORD` | 기존 키 암호 |

업로드 인증서 SHA256은 `config/release-certificate.sha256`에 고정됩니다. 새 키를 생성하거나 pin을 변경하여 통과시키지 않습니다. APK와 AAB 모두 기존 키로 서명하고, AAB의 모든 실제 파일이 같은 단일 인증서로 서명되어 있는지 확인합니다.

Google 인증은 다음 두 저장소 변수를 사용하는 Workload Identity Federation을 우선합니다.

| 변수 | 값 |
| --- | --- |
| `QUICKCUT_PLAY_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL/providers/PROVIDER` |
| `QUICKCUT_PLAY_SERVICE_ACCOUNT` | Play 권한이 부여된 서비스 계정 이메일 |
| `QUICKCUT_PLAY_CLOSED_TRACK` | 이미 존재하는 비공개 테스트 트랙 ID. 기본 `alpha` |

Google Cloud 프로젝트에서 Android Publisher API를 활성화하고, GitHub OIDC 공급자의 저장소/브랜치 조건을 `hebi10/quickcut`의 `refs/heads/main`에 제한합니다. 서비스 계정에 해당 GitHub 주체의 `roles/iam.workloadIdentityUser` 권한을 부여합니다. Play Console 사용자 및 권한에서 같은 서비스 계정에 **QuickCut 앱의 테스트 트랙 릴리스 권한**을 부여합니다. 앱·테스트 릴리스 조회 권한도 필요합니다. 프로덕션 릴리스 권한을 부여할 필요가 없습니다.

이미 제공된 서비스 계정 JSON이 있는 경우에 한해 `QUICKCUT_PLAY_SERVICE_ACCOUNT_JSON` 시크릿을 선택적으로 재사용할 수 있습니다. WIF가 완전히 설정되어 있으면 JSON보다 WIF를 우선합니다. 워크플로는 OAuth `androidpublisher` scope의 단기 access token을 게시 직전에 발급하며 토큰은 프로세스 환경 변수로만 전달합니다. JSON·키·토큰을 출력하거나 산출물에 포함하지 않습니다.

`tracks.list`에서 실제 존재하는 트랙만 선택합니다. 내부 트랙은 앱에서 반환되는 `internal` 또는 `qa` 중 하나여야 합니다. 비공개 트랙 변수에는 Console에 생성되어 있는 정확한 ID를 입력합니다. `production`, `beta`, `internal`, `qa`, 폼팩터 트랙 및 경로 형태의 값은 비공개 트랙으로 사용할 수 없습니다. 이 자동화는 트랙을 새로 생성하지 않습니다.

## 버전과 산출물

현재 소스의 버전명은 `0.4.20`, 기본 `versionCode`는 `27`입니다. Play 빌드는 서명 전에 모든 트랙(초안 포함), AAB, APK의 코드를 조회하고 `max(소스 기본 코드, Play 전체 최댓값 + 1)`을 할당합니다. 허용 범위는 1–2100000000이며, 잘못된 서버 코드나 범위 소진은 빌드 전에 실패합니다. 조회용 edit는 삭제하며 업로드·트랙 갱신·commit은 하지 않습니다. 새 앱 소스의 자동 배포를 위해 기본 코드를 직접 올릴 필요는 없습니다. 소스의 버전명·기본 코드·GitHub 태그는 자동으로 수정하지 않습니다.

할당 코드는 기존 Gradle 속성 `quickcutTestVersionCode`로 APK/AAB 양쪽에 적용됩니다. 실제 APK badging과 AAB manifest가 동일한 패키지·버전명·할당 코드를 포함하는지 검사합니다. 버전명을 바꿀 때는 `docs/releases/VERSION.md`의 해당 한국어 릴리스 노트도 필요하며 공백 포함 500자 이하로 작성합니다.

게시 직전에 기존 source/CI 게이트를 재검증하고 단기 토큰을 새로 발급합니다. 외부 Console 작업 등이 더 높은 코드를 선점하면 재할당·재업로드하지 않고 실패합니다. 같은 코드가 서버에 있으면 로컬 후보 AAB와 서버 번들의 SHA256이 정확히 같아야 재사용·`unchanged`를 허용합니다. 번들 누락·중복·해시 누락/불일치는 목표 트랙에 해당 코드가 있어도 실패합니다. 새 업로드 응답도 코드와 SHA256을 검사한 뒤에만 트랙을 갱신합니다.

승인된 새 실행마다 새 코드를 할당하므로 같은 소스의 명시적 수동 재실행도 다음 코드를 생성할 수 있습니다. commit 응답이 불확실했던 실행은 먼저 기록된 결과를 확인한 뒤 재실행합니다. 기존 심사 상태를 바꾸거나 실패한 업로드·commit을 자동 재시도하지 않습니다.

`play-version-plan.json`에는 소스 SHA·트리·기본 코드, 관측된 Play 최댓값, 할당 코드, 버전명, 실제 Gradle 속성과 최종 APK/AAB SHA256을 기록합니다. 대응 소스에는 커밋된 기본 코드가 그대로 있으므로 동일 버전 메타데이터를 재현하려면 계획에 기록된 할당 속성을 빌드에 적용합니다.

```bash
gradle --no-daemon -PquickcutTestVersionCode=ALLOCATED_VERSION_CODE :app:assembleRelease :app:bundleRelease
```

`quickcut-play-vVERSION-RUN_ID` 산출물에는 검증된 APK/AAB, 대응 소스, 소스 식별 정보, 원본 소스 위치, 네이티브 소스 안내, 릴리스 노트, `play-version-plan.json` 및 `SHA256SUMS`가 포함됩니다. 기존 네이티브 소스/캐시 검증 및 정확한 캐시 키를 재사용하고 대체 캐시 키나 트리거 실행의 산출물은 사용하지 않습니다. bundletool `1.18.2`는 공식 GitHub 릴리스의 SHA256으로 검증합니다.

## 실행 결과와 재실행

Play 편집은 이 워크플로 내에서 직렬화됩니다. 다른 사람이나 Console에서 동시에 앱 설정을 편집하지 않는 편이 좋습니다. API는 먼저 edit를 검증한 다음 `changesInReviewBehavior=ERROR_IF_IN_REVIEW`로 commit합니다. 이미 진행 중인 스토어 이미지 등의 심사가 있으면 취소하지 않고 실패합니다. 심사를 마친 뒤 같은 소스로 다시 실행할 수 있습니다. 자동으로 기존 심사를 취소하거나 심사 전송 방식을 바꾸지 않습니다.

게시 단계의 요약과 `quickcut-play-result-RUN_ID`에는 비밀정보 없이 다음 상태를 남깁니다.

| 결과 | 의미 |
| --- | --- |
| `unchanged` | 선택 트랙에 해당 코드가 완료 상태이고 서버/후보 AAB SHA256도 일치함 |
| `committed` | Google이 edit commit을 수락함. 심사·수동 게시 등은 아직 남을 수 있음 |
| `uncertain` | commit 응답을 확정하지 못함. 독립 조회 결과를 남기고 실행을 실패 처리함 |
| `published: true` | 릴리스 조회에서 해당 코드가 모든 선택 트랙의 `PUBLISHED`로 확인됨 |

`completed` 트랙 상태와 commit 성공만으로 테스터에게 배포되었다고 판단하지 않습니다. `applications.tracks.releases.list`의 lifecycle 상태를 별도로 조회하며 조회 불가/아직 미반영 상태는 성공한 공개로 표시하지 않습니다.

네트워크 오류 후 업로드나 commit을 자동 재시도하지 않습니다. 특히 commit 타임아웃은 Google이 이미 적용했을 가능성이 있으므로 기록된 edit ID와 트랙 lifecycle을 먼저 확인합니다. 재실행 시 기존 코드와 트랙 상태를 다시 조회하여 중복 업로드를 피합니다. 인증·권한 오류, 업로드 실패, 유효성 검사 실패, 심사 충돌은 배포 성공으로 표시하지 않습니다.

## 검증

```bash
python3 scripts/test-release-gate.py
python3 scripts/test-play-publisher.py
python3 scripts/test-play-bundle.py
```

Python 3.11 이상과 JDK 17이 필요합니다. 번들 테스트에는 로컬에서만 생성하고 삭제하는 일회용 테스트 인증서를 사용합니다. 실제 QuickCut 서명 키나 Google 자격 증명 없이 실행할 수 있습니다. 전체 Android 테스트·lint·네이티브 빌드와 API 29/35/36 에뮬레이터 검증은 기존 CI에서 수행합니다.

참고: [Google auth](https://github.com/google-github-actions/auth), [트랙 ID](https://developers.google.com/android-publisher/tracks), [심사 보존 commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit), [릴리스 lifecycle](https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases), [공식 bundletool](https://github.com/google/bundletool/releases/tag/1.18.2), [서버 번들 SHA256](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles), [버전 코드 한도](https://developer.android.com/studio/publish/versioning).
