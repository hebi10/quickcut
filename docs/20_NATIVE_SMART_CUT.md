# SmartCut 네이티브 통합 및 대응 소스 배포

이 통합은 선택한 시각을 바꾸지 않으면서 지원되는 영상의 경계 구간을 다시
인코딩하고, 가운데 완전한 GOP를 복사하는 경로를 추가한다. 모든 형식·모든
구간에서 고속 처리를 보장하는 기능은 아니다. 실제 처리 방식은 저장이
완료된 뒤 보고하며, 지원 조건을 충족하지 않으면 기존 정확한 저장을 사용한다.

## 처리 경계

- 기존 무재인코딩 `FastMp4Trimmer`를 먼저 시도한다. `FAST_ONLY`의 의미는
  바뀌지 않는다. 네이티브 부분 재인코딩은 `ALLOW_REENCODE`에서만 사용한다.
- MP4 계열의 H.264 8-bit 및 HEVC Main/Main10 8/10-bit 4:2:0 클립과 같은 원본의 여러 구간을 대상으로 한다.
  HEVC Main10은 SDR, BT.2020 HDR10(PQ)·HLG 색상 정보를 유지한다.
  여러 구간은 원본 URI·길이·음소거 정책이 같을 때 한 번 인덱싱하고 요청 순서대로
  처리한다. 역순·반복·겹치는 구간은 입력 오디오 디코더를 새로 시작하되 출력
  인코더와 시계는 유지해 소리 누락을 방지한다. 모든 영상 패킷을 복사할 수 있는
  연결도 허용하며, 소리만 처리했다면 `AUDIO_REENCODE`로 보고한다.
- 현재 날짜와 원본 날짜 저장 모두 고속 경로를 시도한다. 원본 날짜는 완성된
  비공개 MP4의 `mvhd/tkhd/mdhd`를 먼저 검사한 뒤 날짜 필드만 변경한다.
  헤더 형식·버전·날짜 표현 범위가 불확실하면 일반 저장을 사용한다.
  날짜를 유지하는 직접 복사는 날짜 검증을 위해 캐시 파일과 갤러리 복사가 필요하다.
- 0/90/180/270도 순수 회전 행렬은 영상 픽셀을 변환하지 않고 최종 출력에 보존한다.
  반사·확대·이동·동적 회전 정보는 부분 처리에서 거절한다.
- AAC·MP3·AC-3·E-AC-3·Opus·FLAC·ALAC 및 지원되는 little-endian PCM 소리를
  AAC로 처리하며 표준 AAC 채널 배치로 표현 가능한 1~8채널 수를 유지한다.
  표준 배치가 아닌 PCE 등은 소리 형식 사유로 일반 저장한다. 모든 영상 구간을 복사한 경우에는
  소리만 처리했다고 보고한다. 음소거에서는 소리 형식 때문에 영상을 재인코딩하지 않는다.
- 서로 다른 원본이나 음소거 정책, 12-bit·4:2:2·4:4:4, Dolby Vision·HDR10+,
  가변 프레임률, 인터레이스, 추가 트랙, 지원 밖의 소리 구성, 불확실한 GOP 구조는 기존 Media3 경로로
  이어진다. FFmpeg가 지원하는 모든 형식을 앱이 부분 처리하는 것은 아니다.
- 원래 요청 시각과 네이티브 계획 시각이 달라지면 거절한다. 키프레임으로
  시작점을 옮기거나 MP4 edit list로 원치 않는 앞부분을 숨겨 성공 처리하지 않는다.
  실제 존재하는 프레임의 시간 해상도는 유지된다.
- 부분 재인코딩 보고에는 실제로 쓴 복사 영상 패킷과 재인코딩 영상 패킷이
  모두 필요하다. 오디오 재인코딩만으로 영상 부분 복사를 주장하지 않는다.
- Baseline/Main/High AVC 프로파일만 부분 처리 대상으로 삼는다. FFmpeg 7.1의
  libx264가 인식하지 못하는 Constrained Baseline 표기는 Baseline으로 전달해
  경계 구간만 High로 바뀌지 않도록 한다. Extended 등은 기존 정확한 저장을 사용한다.
- Android가 AAC 준비 표본 1,024개를 포함한 원시 `mdhd` 길이를 파일 끝으로
  보고하는 경우가 있다. 단일 정상 AAC edit가 길이 차이를 정확히 설명하고 요청
  끝값이 해당 원시 길이의 밀리초 반올림 값과 일치할 때만 이 전체 파일 선택을
  인정한다. 요청 좌표를 바꾸거나 임의의 초과 시간을 허용하지 않는다.

## Android 입출력과 취소

Android `content://` URI를 FFmpeg 파일 경로로 넘기지 않는다. 전체 파일을
가리키는 탐색 가능한 descriptor를 열고 `/proc/self/fd/<fd>` 식별자를 전달한다.
네이티브는 이 경로를 다시 열지 않고 descriptor를 복제한 custom AVIO로 읽는다.
각 demuxer는 독립적인 `pread` 위치를 유지하며 원본 descriptor의 위치를 바꾸지 않는다.
원본 descriptor는 네이티브 scan/cut 및 내부 읽기가 모두 끝날 때까지 유지한다.
offset이 있는 asset, pipe, 크기/범위가 불명확한 provider는 네이티브 경로에서
거절하고 기존 exporter에 넘긴다. 따라서 이 경로를 위해 대용량 원본 전체를
캐시에 먼저 복사할 필요는 없다.

Android 기본 MP4 extractor에서 읽을 수 있도록 AVC 최종 출력은 `avc1`이다. 경계마다
겹치지 않는 SPS/PPS ID로 인코딩한 뒤 모든 설정을 고정 `avcC`에 넣는 추가 remux를
수행한다. 이 과정에서 영상 압축 데이터는 다시 인코딩하지 않지만 중간 출력의
추가 읽기·쓰기가 필요하다. 중간 출력과 최종 캐시 파일이 동시에 존재할 수 있으며,
중간 출력은 갤러리 공개 전에 삭제한다. 프로세스가 종료되어 남은 중간 출력도 다음
작업의 기존 캐시 정리 단계에서 제거한다.

HEVC는 `hev1`으로 출력하며 닫힌 IDR GOP의 설정을 각 구간과 함께 전달한다.
Main/Main10 4:2:0, 고정 프레임률과 안정적인 단일 VPS/SPS/PPS를 확인한다.
자른 경계는 x265로 처리하고 완전한 중간 GOP의 압축 영상 데이터는 복사한다.
열린 GOP의 CRA 경계 및 다중 레이어는 이 경로에 포함되지 않는다.
복사 연결의 IDR이 `no_output_of_prior_pics_flag`로 앞 구간의 지연 프레임을
버리도록 지시하면 새로 만든 연결 지점에 한해 플래그를 보정한다. 모든 슬라이스의
구조와 이전 장면의 크기·색상 표본·비트 심도·디코더 버퍼가 일치해야 한다.
출력 첫 장면과 그대로 유지되는 구간 내부의 IDR은 변경하지 않는다.
안전한 보정이 불가능하면 일반 저장을 사용하며 해당 사유를 표시한다.
HDR 정적 mastering display·content light 정보는 경계 인코더에 전달한다.
소스 도중 바뀌거나 늦게 나타나는 HDR 정보와 지원하지 않는 동적 색상 정보는 거절한다.
x265의 입력 소스는 4.1로 고정하고 8/10-bit 정적 라이브러리를 결합하며 Android 정적 C++ runtime을 사용한다.
경계 처리의 스레드 수를 제한하며 전체 영상 디코딩/재인코딩은 수행하지 않는다.
이 조건의 지원 여부와 실제 저장 시간은 별개이며 전체 사용자 영상의 지원 비율은
측정하지 않았다.

JNI는 IO 작업에서 실행한다. 취소 플래그는 native scan/cut 중 확인하며, 취소된
coroutine이 descriptor나 출력 파일을 먼저 닫지 않도록 실제 native 종료까지
기다린다. upstream의 `stop_reads_when`은 스레드 지역 상태이므로 같은 JNI
스레드에서 guard를 유지한다. 취소를 일반 실패로 바꿔 Media3를 재시작하지 않는다.
로컬 파일에도 OS I/O 지연이 있을 수 있으므로 즉시 중단 시간은 보장하지 않는다.

출력은 고유한 캐시 `.mp4`에 쓴다. 성공 결과를 검증한 뒤 기존 `GalleryWriter`가
한 번만 pending 항목 생성·검증·공개·journal 확정을 수행한다. 취소·네이티브
거절·실패 경로는 공개 전에 끝나야 한다. 공개 이후 보고 callback 오류 때문에
다시 저장하거나 이미 확정한 출력을 미완료로 되돌리면 안 된다. 원본 날짜 보존,
복구 기록과 원본 삭제 승인은 기존 계약을 따른다.

## 라이선스와 배포 범위

SmartCut commit `510ee91614a29f9c7fd6a446b75e9767f0df3d8a`의 실제
`LICENSE`와 README는 GPLv3를 지정한다. 이 코어와 GPL libx264/libx265를 연결한
이번 QuickCut 결합 배포물은 GPLv3 조건으로 제공한다. JNI라는 기술 경계로
앱 코드를 대응 소스에서 제외하지 않는다. 기존에 별도로 배포한 과거 릴리스의
라이선스를 소급하여 바꾸는 선언은 아니다.

APK와 함께 앱·네이티브 대응 소스 및 고지, 의존성의 정확한 공식 소스 위치 안내를
같은 전달 위치에 제공한다. GPLv3 §6(d)에 따라 별도 서버의 소스를 제공하는 경우
에도 APK 옆에서 접근 방법을 명확히 안내하고 그 접근을 유지한다. 공개 GitHub
저장소로 바꾸는 것은 이 배포 절차에 포함되지 않는다. 비공개 전달의 수신자도
GPL 조건에 따라 수정·재배포할 수 있으므로 별도의 비밀유지·재배포 금지 조건을
붙이지 않는다. 이 배포는 소스를 즉시 함께 제공하는 방식이며, 나중에 요청하면
제공한다는 약속만으로 대체하지 않는다.

`third_party/licenses/NOTICE.txt` 및 라이선스 본문은 APK의 오픈소스 화면에
포함한다. `scripts/collect-native-licenses.py`는 빌드에 쓰인 Cargo 패키지와
FFmpeg/x264/x265 원본 고지를 모아 `build/generated/smartcutAssets/licenses/`에
생성한다. 구성 요소의 기존 저작권·라이선스는 보존한다. FFmpeg는 GPL 기능과
libx264/libx265를 활성화하고 `--enable-nonfree`는 사용하지 않는다.

근거 원문:

- [GNU GPLv3 §§1, 5, 6](https://www.gnu.org/licenses/gpl-3.0.html)
- [GNU FAQ: public source posting](https://www.gnu.org/licenses/gpl-faq.html#GPLRequireSourcePostedPublic)
- [FFmpeg legal information](https://ffmpeg.org/legal.html)

## 대응 소스 만들기

`scripts/package-smartcut-source.sh`는 CI의 실제 `git HEAD`를 `git archive`로
복사한다. 작업 디렉터리의 수정된 파일을 HEAD 소스라고 포장하지 않으며, tracked
변경이 있으면 실패한다. 원격 CI가 검사한 commit/tree가 `SOURCE-IDENTITY.json`에
기록되므로 APK artifact의 CI head와 대조할 수 있다.

번들에는 다음이 들어간다.

- QuickCut Kotlin·리소스·manifest·JNI 및 수정한 SmartCut Rust workspace,
  원저작물 고지와 수정 설명, Gradle/native 빌드·검사 스크립트
- `scripts/native-smartcut-sources.json`으로 버전·SHA256을 고정한 FFmpeg와
  x264/x265 전체 원본 archive, 적용하는 변경과 구성 명령
- 고정한 Cargo.lock과 `cargo vendor --locked` 결과, 오프라인 Cargo 설정
- `releaseRuntimeClasspath`에서 실제 선택된 AndroidX/Compose/Media3/Kotlin 등
  전이 의존성의 sources JAR와 배포 binary에 포함된 저작권·라이선스 고지,
  정확한 Maven POM/Gradle module 메타데이터와 파일 해시
- 소스 식별 정보, 파일별 SHA256 목록과 자체 키로 빌드·설치하는 이 설명

SDK/NDK·JDK·Rust·Gradle 같은 일반 빌드 도구는 번들에 재배포하지 않는다.
반면 APK에 포함되는 runtime 라이브러리를 Android 시스템 라이브러리라고
간주해 소스에서 제외하지 않는다. 해당 sources JAR가 누락되면 패키징이 실패한다.
sources JAR에는 upstream 프로젝트의 모든 리소스·manifest·빌드 스크립트가
들어 있지 않으므로 이를 외부 프로젝트 전체 소스라고 부르지 않는다.
`UPSTREAM-SOURCE-LOCATIONS.md`에는 선택된 좌표별로 포함한 소스와 별도 공식
프로젝트 소스 위치를 구분한다. 검증된 AndroidX 릴리스 commit은 공식 Gitiles
전체 소스 archive로, Media3는 공식 release tag의 commit archive로 연결한다.
POM의 저장소 URL만 확인된 항목은 확인하지 않은 commit을 추정해서 쓰지 않는다.
전체 AndroidX/Kotlin monorepo를 여러 버전 중복 다운로드하는 것은 필수 빌드 단계가 아니다.
소스 번들은 모든 Gradle binary 및 플러그인까지 오프라인 캐시한 배포물은 아니다.
빌드 시 선택된 Maven 의존성과 도구를 내려받을 네트워크가 필요할 수 있다.

네이티브 빌드와 Android release 빌드가 끝난 깨끗한 CI checkout에서 실행한다.

```sh
bash scripts/package-smartcut-source.sh
```

결과는 `build/source-delivery/quickcut-corresponding-source-<HEAD12>.tar.gz`,
해당 `.sha256`, `source-identity.json`, `UPSTREAM-SOURCE-LOCATIONS.md`이다.
APK와 모두 함께 전달하고 전달한
archive는 보존한다. 개인키·keystore·비밀번호·환경 파일·전체 홈 디렉터리는
포함하지 않는다. 특정 릴리스 소스를 나중에 움직이는 branch 링크만으로 대신하지 않는다.

## 소스에서 빌드하고 본인 키로 설치하기

Linux x86_64에서 JDK 17, Gradle 9.4.1, Android SDK platform 36,
Build Tools 36.0.0 및 `scripts/native-smartcut-sources.json`에 기록된 NDK와
Rust 버전을 사용한다. Python 3.12 이상, cmake, make, nasm, pkg-config, C/C++ 빌드
도구 및 rustup도 필요하다. host 검사를 실행하면 fixture 생성용 FFmpeg CLI도 필요하다.
`ANDROID_HOME`과 선택적으로 `ANDROID_NDK_HOME`을 해당 도구의 경로로 설정한다.

```sh
bash scripts/build-native-smartcut.sh
gradle --no-daemon :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

debug 빌드는 개발용 키로 서명되며 배포용 개인키가 필요하지 않다. release 빌드를
원하면 `gradle :app:assembleRelease`로 unsigned APK를 만든 뒤 Android SDK의
`zipalign`/`apksigner`와 본인의 keystore로 서명한다. 빌드 설정의 네
`QUICKCUT_*` 서명 환경변수를 모두 본인 키 값으로 설정해도 된다. 비밀번호를
소스·명령 기록·배포 archive에 저장하지 않는다.

본인 키로 서명한 수정판은 기존 배포 키의 APK 위에 덮어 설치할 수 없다.
기존 앱의 편집·설정·템플릿이 필요하면 먼저 보관한 뒤 기존 앱을 제거하고 설치하거나,
수정판의 applicationId를 바꾸어 별도로 설치한다. 원본·갤러리 출력 파일과 앱 내부
복구 기록은 별개다. `scripts/verify-release-apk.sh`는 공식 배포 키 확인용이므로
본인 키 빌드에서는 통과 대상이 아니다. 별도의 인증서 강제로 수정판 실행을 막지 않는다.

## 검증과 주장 범위

실제 imported AVC fixture에서 경계 프레임, 복사 구간 VCL 바이트·PTS, seam
양쪽의 디코딩, AAC 재생·길이, 근접 키프레임 fallback, 취소와 공개 횟수를 확인한다.
기존 복구·원본 보호·화면 OFF·백그라운드 검사를 함께 유지한다. 최종 테스트 수와
실행한 기기는 해당 릴리스의 CI 증거로 기록한다. 짧은 fixture의 성공을 대용량
4K 기기 성능, 모든 코덱 또는 제조사별 장시간 절전 동작의 증명으로 사용하지 않는다.
