# QuickCut 0.4.6 설치·소스 안내

## 배포 파일

- 설치 파일: `QuickCut-v0.4.6-release.apk` (73,027,100 bytes)
- 대응 소스: `QuickCut-v0.4.6-source.tar.gz` (54,704,562 bytes)
- 파일 무결성 목록: `SHA256SUMS.txt`
- Android 10 이상, arm64-v8a·x86_64, versionCode 11

기존 0.4.5 위에 APK를 열어 업데이트 설치합니다. 서명이 같은 0.4.1 이후 정식 배포본은 동일한 앱 ID를 사용합니다. 0.4.0 이전 디버그 배포본의 서명 호환성은 보장하지 않습니다. 앱 삭제는 내부 설정·템플릿·복구 기록을 삭제할 수 있으므로 업데이트를 위해 먼저 삭제하지 마세요.

## 변경 내용과 검증

Android 10 이상에서 사용할 수 있는 QuickCut 0.4.6 설치 파일입니다.

## 설치 및 업데이트

아래 Assets에서 `QuickCut-v0.4.6-release.apk`를 내려받아 설치하세요. 기존 0.4.5와 같은 인증서로 서명했습니다. 기존 앱을 삭제하지 않고 업데이트 설치할 수 있습니다.

## 변경 사항

- 원본 날짜와 0/90/180/270도 회전 정보를 유지하면서 고속 처리를 시도합니다.
- 지원되는 MP4/H.265 Main 8-bit SDR 영상은 자른 경계를 처리하고 중간 영상 데이터를 복사합니다.
- 같은 원본의 역순·반복·겹치는 구간에서도 해당 시점의 소리를 유지합니다. 영상 전체를 복사할 수 있으면 소리만 재인코딩합니다.
- H.265 연결에서 이전 구간의 마지막 프레임이 누락될 수 있는 조건은 정확한 일반 저장으로 전환합니다.
- 일괄 편집의 전체 자동 저장, 분·초 입력과 파일별 결과 길이 표시를 포함합니다.

## 검증

- [새 버전 빌드](https://github.com/hebi10/quickcut/actions/runs/39205413554)와 [API 35 Android 검사](https://github.com/hebi10/quickcut/actions/runs/39205413452) 성공.
- JVM 105개, 네이티브 24개, Android 전체 110개 통과. JVM·Android 결과 XML의 실패·오류·건너뜀 0.
- 첫 실행의 UI 검사 1개는 비동기 분석 완료 전에 버튼을 찾아 실패했습니다. 실제 버튼 표시를 기다리도록 검사만 보완한 뒤 전체 검사를 통과했습니다.
- CI 서명을 사용한 versionCode 10→11 업데이트와 앱 내부 데이터 유지 확인. 배포 APK는 별도로 기존 0.4.5와 같은 인증서임을 검증했습니다.
- 배포 APK의 서명, 버전, non-debuggable 설정, ZIP·두 ABI ELF의 16KiB 정렬과 CI APK 내부 파일 일치를 확인했습니다.
- 버전: 0.4.6 / versionCode 11. arm64-v8a·x86_64 포함.
- 릴리스 소스: `c4193403cf07246007349a973db44ed9c1bc9b23`
- 실제 CI 빌드 커밋: `2cf112959c8aba0563a23be7980c9808dfaf1552`
- 동일 소스 tree: `5de0491b28ea63ce2c56ef9dc2e3b3b44315f462`

APK SHA-256:
```text
c25402b912c56f11eadca2b1777a7cbcb341afc0c170c14c6211055ca6277ba2
```

대응 소스와 빌드·의존성 안내는 `QuickCut-v0.4.6-source.tar.gz`, `QuickCut-v0.4.6-source-guide.md`에 함께 제공합니다. GitHub 자동 생성 Source code 대신 첨부한 대응 소스 번들을 사용하세요. `SHA256SUMS.txt`는 세 배포 파일의 체크섬입니다.

HDR·10-bit·가변 프레임률·열린 GOP 등 지원 범위 밖의 영상은 일반 처리로 전환될 수 있습니다. 실제 사용자 영상 묶음의 지원 비율이나 처리 속도, 모든 기기의 장시간·절전 동작은 측정하지 않았습니다.


## 대응 소스 검증

소스 archive SHA-256:
```text
2e949c9a8d6993c8773e36c3283f98afe13d50f5748bb7d5ab0ea0656226d862
```

내부 SHA256SUMS의 4,329개 파일 해시와 저장소의 269개 파일을 검증했습니다. 고정한 FFmpeg·x264·x265 원본, Cargo vendor, 선택된 runtime 모듈 96개의 소스·메타데이터·고지를 포함합니다. 두 ABI의 네이티브 라이브러리 해시는 APK와 아래 소스 식별 정보에서 일치합니다. 개인 서명키나 비밀번호는 포함하지 않습니다.

```json
{
  "gitCommit": "2cf112959c8aba0563a23be7980c9808dfaf1552",
  "gitTree": "5de0491b28ea63ce2c56ef9dc2e3b3b44315f462",
  "gitCommitTimestamp": 1792388171,
  "smartCutCommit": "510ee91614a29f9c7fd6a446b75e9767f0df3d8a",
  "cargoLockSha256": "f09b405a04433c7f217134a302b53ca84a53c331d037e22917ae56b70b8cb590",
  "nativeSourceArchives": [
    {
      "name": "ffmpeg-7.1.5.tar.xz",
      "sha256": "de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f"
    },
    {
      "name": "x264-b35605ace3ddf7c1a5d67a2eb553f034aef41d55.tar.gz",
      "sha256": "cd71a7515b0e9a012e1ac9b1f8415bebcaf6fc97d4db32286642ac4c0fbe24f9"
    },
    {
      "name": "x265_4.1.tar.gz",
      "sha256": "a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29"
    }
  ],
  "nativeLibraries": [
    {
      "abi": "arm64-v8a",
      "sha256": "8258e5d3e818ddb27c3a75a0804c5f38006682c790b9600c4beb402e3a477dd3"
    },
    {
      "abi": "x86_64",
      "sha256": "55a51aeb81930fae7edf84413f1d46713b76b1f12f3a5afb4bfec8095f35de7c"
    }
  ],
  "toolchains": {
    "ndk": "27.3.13750724",
    "rust": "1.98.0",
    "androidApi": 29
  },
  "runtimeSourceModules": 96,
  "rustc": "rustc 1.98.0 (88d9e12ae 2026-08-18)",
  "cargo": "cargo 1.98.0 (797e8a9bc 2026-08-05)",
  "signingSecretsIncluded": false
}
```

## 소스에서 빌드

소스 archive를 압축 해제한 디렉터리에서 실행합니다. Linux x86_64, JDK 17, Gradle 9.4.1, Android SDK platform 36, Build Tools 36.0.0, NDK 27.3.13750724, Rust 1.98.0, Python 3.12 이상, cmake·make·nasm·pkg-config·C/C++ 빌드 도구가 필요합니다. `ANDROID_HOME`을 설치한 SDK 경로로 설정합니다. 일반 도구와 Maven binary 의존성은 별도로 설치 또는 다운로드하며 완전한 오프라인 빌드 번들은 아닙니다.

```sh
bash scripts/build-native-smartcut.sh
gradle --no-daemon :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

release 빌드는 다음 명령으로 서명 없는 APK를 만든 뒤 본인 키로 zipalign/apksigner를 사용해 서명할 수 있습니다.

```sh
gradle --no-daemon :app:assembleRelease
```

본인 키로 서명한 수정판은 기존 배포 APK 위에 업데이트 설치할 수 없습니다. 별도 applicationId를 사용하거나 필요한 내부 데이터를 보관한 뒤 전환하세요. 공식 배포 인증서를 요구하는 `scripts/verify-release-apk.sh`는 본인 키 수정판의 통과 대상이 아닙니다. 수정판 실행에 별도 인증서 제한은 없습니다.

빌드 구성과 라이선스 고지는 소스의 `docs/20_NATIVE_SMART_CUT.md`, `native/vendor/smartcut/UPSTREAM.md`, `third_party/`, `build/generated/smartcutAssets/licenses/`를 참고하세요.

## 의존성 공식 소스 위치

다음은 CI가 선택한 의존성별 포함 소스와 공식 upstream 위치입니다. 포함된 sources JAR를 외부 프로젝트 전체 저장소라고 부르지 않으며, 확인하지 않은 commit을 추정하지 않습니다.

# QuickCut runtime dependency source locations

The archive contains the complete QuickCut/JNI/modified SmartCut source, pinned native sources, vendored Rust crates, and selected runtime source JARs. Runtime JARs alone do not include every upstream resource, manifest or project build script. Those project sources remain available at the official locations below; this file accompanies the APK/source delivery.

The immutable AndroidX archive URLs contain the whole source tree including project build scripts. Alternatively use `git clone <repository>` followed by `git checkout <commit>`. Do not replace a pinned revision with the current main branch.

## androidx.lifecycle:lifecycle-viewmodel-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-android_2.10.0__lifecycle-viewmodel-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-android/2.10.0/lifecycle-viewmodel-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-android-2.10.0.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-core-jvm_1.10.2__kotlinx-coroutines-core-jvm-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-core-jvm-1.10.2.pom`.

## androidx.annotation:annotation-jvm:1.9.1

Included published sources: `third_party/runtime-sources/androidx.annotation_annotation-jvm_1.9.1__annotation-jvm-1.9.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.annotation__annotation-jvm-1.9.1.pom`.

## androidx.savedstate:savedstate-ktx:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-ktx_1.4.0__savedstate-ktx-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-ktx/1.4.0/savedstate-ktx-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-ktx-1.4.0.pom`.

## androidx.compose.material3:material3-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.compose.material3_material3-android_1.4.0__material3-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/5113b82a4ed6416d6344efa50e41db8cb65928ff.tar.gz

Revision: `5113b82a4ed6416d6344efa50e41db8cb65928ff`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material3__material3-android-1.4.0.pom`.

## androidx.compose.runtime:runtime-retain-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-retain-android_1.11.4__runtime-retain-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain-android/1.11.4/runtime-retain-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-retain-android-1.11.4.pom`.

## androidx.media3:media3-ui:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-ui_1.11.1__media3-ui-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-ui/1.11.1/media3-ui-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-ui-1.11.1.pom`.

## androidx.interpolator:interpolator:1.0.0

Included published sources: `third_party/runtime-sources/androidx.interpolator_interpolator_1.0.0__interpolator-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.interpolator__interpolator-1.0.0.pom`.

## androidx.media3:media3-container:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-container_1.11.1__media3-container-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-container/1.11.1/media3-container-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-container-1.11.1.pom`.

## androidx.core:core-viewtree:1.0.0

Included published sources: `third_party/runtime-sources/androidx.core_core-viewtree_1.0.0__core-viewtree-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core-viewtree/1.0.0/core-viewtree-1.0.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-viewtree-1.0.0.pom`.

## androidx.media3:media3-database:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-database_1.11.1__media3-database-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-database/1.11.1/media3-database-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-database-1.11.1.pom`.

## com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava

Verified empty conflict-avoidance artifact: no executable code or resources. The publisher supplies no sources JAR. Binary hash and ZIP-entry verification: `third_party/runtime-sources/guava-listenablefuture-empty-artifact.txt`.

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.pom`.

## androidx.compose.ui:ui-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-android_1.11.4__ui-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-android-1.11.4.pom`.

## androidx.media3:media3-decoder:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-decoder_1.11.1__media3-decoder-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-decoder/1.11.1/media3-decoder-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-decoder-1.11.1.pom`.

## androidx.media3:media3-extractor:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-extractor_1.11.1__media3-extractor-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-extractor-1.11.1.pom`.

## androidx.graphics:graphics-path:1.0.1

Included published sources: `third_party/runtime-sources/androidx.graphics_graphics-path_1.0.1__graphics-path-1.0.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/8a05a22af450d589ef911d772a001a49dcb05b71.tar.gz

Revision: `8a05a22af450d589ef911d772a001a49dcb05b71`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path-1.0.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.graphics__graphics-path-1.0.1.pom`.

## androidx.documentfile:documentfile:1.0.0

Included published sources: `third_party/runtime-sources/androidx.documentfile_documentfile_1.0.0__documentfile-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/documentfile/documentfile/1.0.0/documentfile-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.documentfile__documentfile-1.0.0.pom`.

## androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-compose-android_2.10.0__lifecycle-runtime-compose-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.10.0/lifecycle-runtime-compose-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-compose-android-2.10.0.pom`.

## androidx.concurrent:concurrent-futures:1.2.0

Included published sources: `third_party/runtime-sources/androidx.concurrent_concurrent-futures_1.2.0__concurrent-futures-1.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.2.0/concurrent-futures-1.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.concurrent__concurrent-futures-1.2.0.pom`.

## org.jspecify:jspecify:1.0.0

Included published sources: `third_party/runtime-sources/org.jspecify_jspecify_1.0.0__jspecify-1.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0-sources.jar

Publisher's project source location: https://github.com/jspecify/jspecify/

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jspecify__jspecify-1.0.0.pom`.

## org.checkerframework:checker-qual:3.43.0

Included published sources: `third_party/runtime-sources/org.checkerframework_checker-qual_3.43.0__checker-qual-3.43.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0-sources.jar

Publisher's project source location: https://github.com/typetools/checker-framework.git

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.checkerframework__checker-qual-3.43.0.pom`.

## androidx.emoji2:emoji2:1.4.0

Included published sources: `third_party/runtime-sources/androidx.emoji2_emoji2_1.4.0__emoji2-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2/1.4.0/emoji2-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/2d25c5e925564cf1f52fb6d2f88088d2dd5becfe.tar.gz

Revision: `2d25c5e925564cf1f52fb6d2f88088d2dd5becfe`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.emoji2__emoji2-1.4.0.pom`.

## com.google.guava:failureaccess:1.0.2

Included published sources: `third_party/runtime-sources/com.google.guava_failureaccess_1.0.2__failureaccess-1.0.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__failureaccess-1.0.2.pom`.

## androidx.media3:media3-common:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-common_1.11.1__media3-common-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.1/media3-common-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-common-1.11.1.pom`.

## androidx.compose.ui:ui-unit-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-unit-android_1.11.4__ui-unit-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.11.4/ui-unit-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-unit-android-1.11.4.pom`.

## androidx.compose.material:material-icons-core-android:1.7.8

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-icons-core-android_1.7.8__material-icons-core-android-1.7.8-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core-android/1.7.8/material-icons-core-android-1.7.8-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/215cdfd8cb9c0762dd0347c383250644057c367f.tar.gz

Revision: `215cdfd8cb9c0762dd0347c383250644057c367f`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-icons-core-android-1.7.8.pom`.

## androidx.arch.core:core-common:2.2.0

Included published sources: `third_party/runtime-sources/androidx.arch.core_core-common_2.2.0__core-common-2.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.arch.core__core-common-2.2.0.pom`.

## androidx.tracing:tracing:1.2.0

Included published sources: `third_party/runtime-sources/androidx.tracing_tracing_1.2.0__tracing-1.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.2.0/tracing-1.2.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/538750227118fd2cca14f4de7df81bace1f50dd3.tar.gz

Revision: `538750227118fd2cca14f4de7df81bace1f50dd3`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.tracing__tracing-1.2.0.pom`.

## androidx.savedstate:savedstate-compose-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-compose-android_1.4.0__savedstate-compose-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-android/1.4.0/savedstate-compose-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-compose-android-1.4.0.pom`.

## androidx.media3:media3-exoplayer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-exoplayer_1.11.1__media3-exoplayer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.1/media3-exoplayer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-exoplayer-1.11.1.pom`.

## androidx.recyclerview:recyclerview:1.3.0

Included published sources: `third_party/runtime-sources/androidx.recyclerview_recyclerview_1.3.0__recyclerview-1.3.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/1.3.0/recyclerview-1.3.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/86bf1b167c3ff33791a75eb42b0c8f056eaeab3e.tar.gz

Revision: `86bf1b167c3ff33791a75eb42b0c8f056eaeab3e`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/recyclerview#recyclerview-1.3.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.recyclerview__recyclerview-1.3.0.pom`.

## androidx.customview:customview:1.0.0

Included published sources: `third_party/runtime-sources/androidx.customview_customview_1.0.0__customview-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/customview/customview/1.0.0/customview-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.customview__customview-1.0.0.pom`.

## androidx.lifecycle:lifecycle-runtime-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-android_2.10.0__lifecycle-runtime-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.10.0/lifecycle-runtime-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-android-2.10.0.pom`.

## androidx.media3:media3-transformer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-transformer_1.11.1__media3-transformer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-transformer/1.11.1/media3-transformer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-transformer-1.11.1.pom`.

## androidx.activity:activity:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity_1.13.0__activity-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.13.0/activity-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-1.13.0.pom`.

## androidx.compose.material:material-ripple-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-ripple-android_1.11.4__material-ripple-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.11.4/material-ripple-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-ripple-android-1.11.4.pom`.

## androidx.compose.ui:ui-text-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-text-android_1.11.4__ui-text-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.11.4/ui-text-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-text-android-1.11.4.pom`.

## com.google.code.findbugs:jsr305:3.0.2

Included published sources: `third_party/runtime-sources/com.google.code.findbugs_jsr305_3.0.2__jsr305-3.0.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2-sources.jar

Publisher's project source location: https://code.google.com/p/jsr-305/

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.code.findbugs__jsr305-3.0.2.pom`.

## androidx.navigationevent:navigationevent-compose-android:1.0.0

Included published sources: `third_party/runtime-sources/androidx.navigationevent_navigationevent-compose-android_1.0.0__navigationevent-compose-android-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/navigationevent/navigationevent-compose-android/1.0.0/navigationevent-compose-android-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/a336be7aa59aced2a87c402849988939d848fee3.tar.gz

Revision: `a336be7aa59aced2a87c402849988939d848fee3`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.navigationevent__navigationevent-compose-android-1.0.0.pom`.

## androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-ktx_2.10.0__lifecycle-viewmodel-ktx-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.10.0/lifecycle-viewmodel-ktx-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-ktx-2.10.0.pom`.

## com.google.errorprone:error_prone_annotations:2.28.0

Included published sources: `third_party/runtime-sources/com.google.errorprone_error_prone_annotations_2.28.0__error_prone_annotations-2.28.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.errorprone__error_prone_annotations-2.28.0.pom`.

## androidx.compose.ui:ui-geometry-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-geometry-android_1.11.4__ui-geometry-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry-android/1.11.4/ui-geometry-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-geometry-android-1.11.4.pom`.

## androidx.compose.runtime:runtime-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-android_1.11.4__runtime-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.11.4/runtime-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-android-1.11.4.pom`.

## androidx.navigationevent:navigationevent-android:1.0.0

Included published sources: `third_party/runtime-sources/androidx.navigationevent_navigationevent-android_1.0.0__navigationevent-android-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/navigationevent/navigationevent-android/1.0.0/navigationevent-android-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/a336be7aa59aced2a87c402849988939d848fee3.tar.gz

Revision: `a336be7aa59aced2a87c402849988939d848fee3`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.navigationevent__navigationevent-android-1.0.0.pom`.

## androidx.lifecycle:lifecycle-livedata:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata_2.10.0__lifecycle-livedata-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata/2.10.0/lifecycle-livedata-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-2.10.0.pom`.

## androidx.dynamicanimation:dynamicanimation:1.0.0

Included published sources: `third_party/runtime-sources/androidx.dynamicanimation_dynamicanimation_1.0.0__dynamicanimation-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.dynamicanimation__dynamicanimation-1.0.0.pom`.

## androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-ktx-android_2.10.0__lifecycle-runtime-ktx-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx-android/2.10.0/lifecycle-runtime-ktx-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-ktx-android-2.10.0.pom`.

## androidx.media3:media3-datasource:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-datasource_1.11.1__media3-datasource-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.11.1/media3-datasource-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-datasource-1.11.1.pom`.

## androidx.legacy:legacy-support-core-utils:1.0.0

Included published sources: `third_party/runtime-sources/androidx.legacy_legacy-support-core-utils_1.0.0__legacy-support-core-utils-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/legacy/legacy-support-core-utils/1.0.0/legacy-support-core-utils-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.legacy__legacy-support-core-utils-1.0.0.pom`.

## androidx.compose.runtime:runtime-saveable-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-saveable-android_1.11.4__runtime-saveable-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.11.4/runtime-saveable-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-saveable-android-1.11.4.pom`.

## androidx.compose.foundation:foundation-layout-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.foundation_foundation-layout-android_1.11.4__foundation-layout-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.11.4/foundation-layout-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.foundation__foundation-layout-android-1.11.4.pom`.

## androidx.autofill:autofill:1.0.0

Included published sources: `third_party/runtime-sources/androidx.autofill_autofill_1.0.0__autofill-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/autofill/autofill/1.0.0/autofill-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/34328d238b2999f231e824d2a9149acdbe3d5c00.tar.gz

Revision: `34328d238b2999f231e824d2a9149acdbe3d5c00`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/autofill#1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.autofill__autofill-1.0.0.pom`.

## androidx.startup:startup-runtime:1.1.1

Included published sources: `third_party/runtime-sources/androidx.startup_startup-runtime_1.1.1__startup-runtime-1.1.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/8337e0c05174a73ea6db825b680f461c33144bf4.tar.gz

Revision: `8337e0c05174a73ea6db825b680f461c33144bf4`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/startup#1.1.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.startup__startup-runtime-1.1.1.pom`.

## androidx.lifecycle:lifecycle-viewmodel:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel_2.10.0__lifecycle-viewmodel-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.10.0/lifecycle-viewmodel-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-2.10.0.pom`.

## androidx.exifinterface:exifinterface:1.3.6

Included published sources: `third_party/runtime-sources/androidx.exifinterface_exifinterface_1.3.6__exifinterface-1.3.6-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/00bc5313008ea35c4c78635d300238697527b76b.tar.gz

Revision: `00bc5313008ea35c4c78635d300238697527b76b`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/exifinterface#1.3.6

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.exifinterface__exifinterface-1.3.6.pom`.

## androidx.annotation:annotation-experimental:1.4.1

Included published sources: `third_party/runtime-sources/androidx.annotation_annotation-experimental_1.4.1__annotation-experimental-1.4.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/cb993e8594b035615bb12e0cb8294769eb7c10d1.tar.gz

Revision: `cb993e8594b035615bb12e0cb8294769eb7c10d1`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/annotation#annotation-experimental-1.4.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.annotation__annotation-experimental-1.4.1.pom`.

## androidx.window:window:1.5.0

Included published sources: `third_party/runtime-sources/androidx.window_window_1.5.0__window-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/window/window/1.5.0/window-1.5.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3b3f055fe2da3f4a8a74003f8640b461b1694954.tar.gz

Revision: `3b3f055fe2da3f4a8a74003f8640b461b1694954`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/window#1.5.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.window__window-1.5.0.pom`.

## androidx.lifecycle:lifecycle-viewmodel-compose-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-compose-android_2.10.0__lifecycle-viewmodel-compose-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose-android/2.10.0/lifecycle-viewmodel-compose-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-compose-android-2.10.0.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-android_1.10.2__kotlinx-coroutines-android-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-android-1.10.2.pom`.

## androidx.compose.animation:animation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.animation_animation-android_1.11.4__animation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.11.4/animation-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.animation__animation-android-1.11.4.pom`.

## org.jetbrains.kotlin:kotlin-stdlib:2.3.21

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlin_kotlin-stdlib_2.3.21__kotlin-stdlib-2.3.21-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21-sources.jar

Publisher's project source location: https://github.com/JetBrains/kotlin

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlin__kotlin-stdlib-2.3.21.pom`.

## androidx.compose.ui:ui-util-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-util-android_1.11.4__ui-util-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util-android/1.11.4/ui-util-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-util-android-1.11.4.pom`.

## org.jetbrains:annotations:23.0.0

Included published sources: `third_party/runtime-sources/org.jetbrains_annotations_23.0.0__annotations-23.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0-sources.jar

Publisher's project source location: https://github.com/JetBrains/java-annotations

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains__annotations-23.0.0.pom`.

## androidx.compose.runtime:runtime-annotation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-annotation-android_1.11.4__runtime-annotation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation-android/1.11.4/runtime-annotation-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-annotation-android-1.11.4.pom`.

## androidx.lifecycle:lifecycle-process:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-process_2.10.0__lifecycle-process-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.10.0/lifecycle-process-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-process-2.10.0.pom`.

## com.google.j2objc:j2objc-annotations:3.0.0

Included published sources: `third_party/runtime-sources/com.google.j2objc_j2objc-annotations_3.0.0__j2objc-annotations-3.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0-sources.jar

Publisher's project source location: http://github.com/google/j2objc

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.j2objc__j2objc-annotations-3.0.0.pom`.

## androidx.window:window-core-android:1.5.0

Included published sources: `third_party/runtime-sources/androidx.window_window-core-android_1.5.0__window-core-android-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/window/window-core-android/1.5.0/window-core-android-1.5.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3b3f055fe2da3f4a8a74003f8640b461b1694954.tar.gz

Revision: `3b3f055fe2da3f4a8a74003f8640b461b1694954`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/window#1.5.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.window__window-core-android-1.5.0.pom`.

## androidx.savedstate:savedstate-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-android_1.4.0__savedstate-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-android/1.4.0/savedstate-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-android-1.4.0.pom`.

## androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-savedstate-android_2.10.0__lifecycle-viewmodel-savedstate-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate-android/2.10.0/lifecycle-viewmodel-savedstate-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-savedstate-android-2.10.0.pom`.

## androidx.compose.ui:ui-graphics-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-graphics-android_1.11.4__ui-graphics-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.11.4/ui-graphics-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-graphics-android-1.11.4.pom`.

## androidx.customview:customview-poolingcontainer:1.0.0

Included published sources: `third_party/runtime-sources/androidx.customview_customview-poolingcontainer_1.0.0__customview-poolingcontainer-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/1e0793130863c72dc4a2d02bc975128f3ef0158b.tar.gz

Revision: `1e0793130863c72dc4a2d02bc975128f3ef0158b`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/customview#customview-poolingcontainer-1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.customview__customview-poolingcontainer-1.0.0.pom`.

## androidx.lifecycle:lifecycle-common-java8:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-common-java8_2.10.0__lifecycle-common-java8-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-java8/2.10.0/lifecycle-common-java8-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-common-java8-2.10.0.pom`.

## androidx.collection:collection-ktx:1.5.0

Included published sources: `third_party/runtime-sources/androidx.collection_collection-ktx_1.5.0__collection-ktx-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/collection/collection-ktx/1.5.0/collection-ktx-1.5.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.collection__collection-ktx-1.5.0.pom`.

## androidx.lifecycle:lifecycle-common-jvm:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-common-jvm_2.10.0__lifecycle-common-jvm-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.10.0/lifecycle-common-jvm-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-common-jvm-2.10.0.pom`.

## androidx.media3:media3-effect:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-effect_1.11.1__media3-effect-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-effect/1.11.1/media3-effect-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-effect-1.11.1.pom`.

## androidx.lifecycle:lifecycle-livedata-core:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata-core_2.10.0__lifecycle-livedata-core-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.10.0/lifecycle-livedata-core-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-core-2.10.0.pom`.

## androidx.activity:activity-compose:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity-compose_1.13.0__activity-compose-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-compose-1.13.0.pom`.

## androidx.versionedparcelable:versionedparcelable:1.1.1

Included published sources: `third_party/runtime-sources/androidx.versionedparcelable_versionedparcelable_1.1.1__versionedparcelable-1.1.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/9fd278801e06c07a5d230fd7edbb97e16c322949.tar.gz

Revision: `9fd278801e06c07a5d230fd7edbb97e16c322949`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/versionedparcelable#1.1.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.versionedparcelable__versionedparcelable-1.1.1.pom`.

## org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-serialization-core-jvm_1.7.3__kotlinx-serialization-core-jvm-1.7.3-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.serialization

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-serialization-core-jvm-1.7.3.pom`.

## androidx.compose.foundation:foundation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.foundation_foundation-android_1.11.4__foundation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.11.4/foundation-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.foundation__foundation-android-1.11.4.pom`.

## androidx.arch.core:core-runtime:2.2.0

Included published sources: `third_party/runtime-sources/androidx.arch.core_core-runtime_2.2.0__core-runtime-2.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.arch.core__core-runtime-2.2.0.pom`.

## androidx.collection:collection-jvm:1.5.0

Included published sources: `third_party/runtime-sources/androidx.collection_collection-jvm_1.5.0__collection-jvm-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.collection__collection-jvm-1.5.0.pom`.

## androidx.loader:loader:1.0.0

Included published sources: `third_party/runtime-sources/androidx.loader_loader_1.0.0__loader-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.loader__loader-1.0.0.pom`.

## androidx.localbroadcastmanager:localbroadcastmanager:1.0.0

Included published sources: `third_party/runtime-sources/androidx.localbroadcastmanager_localbroadcastmanager_1.0.0__localbroadcastmanager-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.0.0/localbroadcastmanager-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.localbroadcastmanager__localbroadcastmanager-1.0.0.pom`.

## androidx.transition:transition:1.6.0

Included published sources: `third_party/runtime-sources/androidx.transition_transition_1.6.0__transition-1.6.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/transition/transition/1.6.0/transition-1.6.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/184a59d7780ba90e35570b93f6513724b08c2168.tar.gz

Revision: `184a59d7780ba90e35570b93f6513724b08c2168`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/transition#1.6.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.transition__transition-1.6.0.pom`.

## androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata-core-ktx_2.10.0__lifecycle-livedata-core-ktx-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core-ktx/2.10.0/lifecycle-livedata-core-ktx-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-core-ktx-2.10.0.pom`.

## androidx.print:print:1.0.0

Included published sources: `third_party/runtime-sources/androidx.print_print_1.0.0__print-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/print/print/1.0.0/print-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.print__print-1.0.0.pom`.

## com.google.guava:guava:33.3.1-android

Included published sources: `third_party/runtime-sources/com.google.guava_guava_33.3.1-android__guava-33.3.1-android-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/guava/guava/33.3.1-android/guava-33.3.1-android-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__guava-33.3.1-android.pom`.

## androidx.compose.animation:animation-core-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.animation_animation-core-android_1.11.4__animation-core-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.11.4/animation-core-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.animation__animation-core-android-1.11.4.pom`.

## androidx.profileinstaller:profileinstaller:1.4.0

Included published sources: `third_party/runtime-sources/androidx.profileinstaller_profileinstaller_1.4.0__profileinstaller-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3bc0fb3beaccef509bb170cd69ddaad907b493b8.tar.gz

Revision: `3bc0fb3beaccef509bb170cd69ddaad907b493b8`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.profileinstaller__profileinstaller-1.4.0.pom`.

## androidx.core:core:1.18.0

Included published sources: `third_party/runtime-sources/androidx.core_core_1.18.0__core-1.18.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core/1.18.0/core-1.18.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/d0311626e34d3399038ccd85c9ea91f7297de350.tar.gz

Revision: `d0311626e34d3399038ccd85c9ea91f7297de350`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/core#1.18.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-1.18.0.pom`.

## androidx.core:core-ktx:1.18.0

Included published sources: `third_party/runtime-sources/androidx.core_core-ktx_1.18.0__core-ktx-1.18.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.18.0/core-ktx-1.18.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/d0311626e34d3399038ccd85c9ea91f7297de350.tar.gz

Revision: `d0311626e34d3399038ccd85c9ea91f7297de350`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/core#1.18.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-ktx-1.18.0.pom`.

## androidx.media3:media3-muxer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-muxer_1.11.1__media3-muxer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-muxer-1.11.1.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-guava_1.10.2__kotlinx-coroutines-guava-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-guava/1.10.2/kotlinx-coroutines-guava-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-guava-1.10.2.pom`.

## androidx.compose.material:material-icons-extended-android:1.7.8

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-icons-extended-android_1.7.8__material-icons-extended-android-1.7.8-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended-android/1.7.8/material-icons-extended-android-1.7.8-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/215cdfd8cb9c0762dd0347c383250644057c367f.tar.gz

Revision: `215cdfd8cb9c0762dd0347c383250644057c367f`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-icons-extended-android-1.7.8.pom`.

## androidx.activity:activity-ktx:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity-ktx_1.13.0__activity-ktx-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.13.0/activity-ktx-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-ktx-1.13.0.pom`.


