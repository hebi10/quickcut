# QuickCut 0.4.9 설치 및 소스 안내

요청한 자르기 위치와 영상 복사가 가능한 추천 위치, 추가 삭제 길이를 확인하고 저장 방식을 선택할 수 있습니다.

- 시작·끝과 중간 삭제 구간에 대해 요청·추천 시간을 밀리초 단위로 표시합니다.
- 추천 위치로 고속 저장을 선택한 경우에만 더 잘라냅니다. 정확한 위치로 자동 저장과 취소는 요청한 구간을 유지합니다.
- 고속 저장은 영상 재인코딩으로 자동 전환하지 않습니다. 소리는 필요하면 AAC로 변환합니다.
- 일괄 저장은 파일별 조정 내용과 건너뛸 파일을 표시합니다.
- 긴 영상의 분석을 취소할 수 있고, 실패 후 저장 방식을 다시 고를 때는 불필요한 분석을 반복하지 않습니다.

키프레임 간격에 따라 추가 삭제 길이가 수 초 이상일 수도 있습니다. 모든 형식에 고속 저장을 보장하지 않으며, 안전한 추천을 찾지 못하면 정확한 위치로 자동 저장을 선택할 수 있습니다.

앱 ID는 `com.hebi.quickcut`, 버전은 `0.4.9`, versionCode는 `14`입니다. 기존 배포본과 같은 인증서로 서명했으며 arm64-v8a와 x86_64를 포함합니다. 기존 앱을 삭제하지 않고 업데이트할 수 있습니다.

- 앱 소스: https://github.com/hebi10/quickcut/tree/db7093b1b3957922234f8d1d00ed4f8c2f0e497d
- 소스 트리: `8470318ca2e65c12e726fd6da2c0ba473fd474bd`
- Android 빌드: https://github.com/hebi10/quickcut/actions/runs/39566866428
- Android 검사: https://github.com/hebi10/quickcut/actions/runs/39566866504
- 인증서 SHA-256: `64d8bb097fa2fb39401c5a01026e6fc8d36f5136d5684732c1f310e6affb2d67`

대응 소스 압축과 아래 의존성별 공식 소스 위치를 함께 제공합니다. 개인 서명 키와 비밀번호는 포함하지 않습니다. 자세한 구현·제한은 `docs/25_SAFE_CUT_SUGGESTIONS.md`, 엔진 빌드와 라이선스는 `docs/20_NATIVE_SMART_CUT.md`를 참고하세요.

## 빌드

JDK 17, Android SDK 36, NDK 27.3.13750724, Gradle 9.4.1을 사용합니다.

```bash
bash scripts/build-native-smartcut.sh --host-tests
gradle --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

## 확인한 결과

- JVM 단위 검사 117개, Android 에뮬레이터 검사 123개, 네이티브 영상 검사 63개: 총 303개 통과. 실패·오류·건너뜀 없음.
- 동의한 경우만 추천 위치 적용, 정확한 위치 저장·취소 시 요청 유지, 실제 앱 화면에서 추천을 선택한 뒤 고속 저장되는 동작을 확인했습니다.
- 중간 삭제의 실제 출력과 보존한 영상의 프레임 해시를 비교했고, 고속 전용 저장이 영상을 재인코딩하지 않는지 확인했습니다.
- 같은 서명의 versionCode 13 → 14 업데이트에서 앱 데이터가 유지됐으며, 배포 APK 인증서가 기존 배포본과 일치합니다.
- 배포 APK의 두 ABI 네이티브 라이브러리 해시가 대응 소스의 `source-identity.json`과 일치합니다. CI의 임시 병합 커밋 ID와 배포 커밋 ID는 다를 수 있으며, 소스 트리는 위 값으로 일치합니다.
- Android lint와 HDR·10비트 출력의 외부 디코더 검사를 통과했습니다. 실제 휴대전화에서의 10비트 재생은 별도 확인이 필요합니다.

사용자가 보고한 3시간 원본 오류는 해당 원본을 받지 못해 해결 여부를 확인하지 못했습니다.


---

# QuickCut runtime dependency source locations

The archive contains the complete QuickCut/JNI/modified SmartCut source, pinned native sources, vendored Rust crates, and selected runtime source JARs. Runtime JARs alone do not include every upstream resource, manifest or project build script. Those project sources remain available at the official locations below; this file accompanies the APK/source delivery.

The immutable AndroidX archive URLs contain the whole source tree including project build scripts. Alternatively use `git clone <repository>` followed by `git checkout <commit>`. Do not replace a pinned revision with the current main branch.

## androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-ktx_2.10.0__lifecycle-viewmodel-ktx-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.10.0/lifecycle-viewmodel-ktx-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-ktx-2.10.0.pom`.

## androidx.compose.material:material-icons-extended-android:1.7.8

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-icons-extended-android_1.7.8__material-icons-extended-android-1.7.8-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended-android/1.7.8/material-icons-extended-android-1.7.8-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/215cdfd8cb9c0762dd0347c383250644057c367f.tar.gz

Revision: `215cdfd8cb9c0762dd0347c383250644057c367f`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-icons-extended-android-1.7.8.pom`.

## androidx.savedstate:savedstate-ktx:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-ktx_1.4.0__savedstate-ktx-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-ktx/1.4.0/savedstate-ktx-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-ktx-1.4.0.pom`.

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

## androidx.navigationevent:navigationevent-compose-android:1.0.0

Included published sources: `third_party/runtime-sources/androidx.navigationevent_navigationevent-compose-android_1.0.0__navigationevent-compose-android-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/navigationevent/navigationevent-compose-android/1.0.0/navigationevent-compose-android-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/a336be7aa59aced2a87c402849988939d848fee3.tar.gz

Revision: `a336be7aa59aced2a87c402849988939d848fee3`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.navigationevent__navigationevent-compose-android-1.0.0.pom`.

## androidx.compose.material:material-icons-core-android:1.7.8

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-icons-core-android_1.7.8__material-icons-core-android-1.7.8-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-core-android/1.7.8/material-icons-core-android-1.7.8-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/215cdfd8cb9c0762dd0347c383250644057c367f.tar.gz

Revision: `215cdfd8cb9c0762dd0347c383250644057c367f`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-icons-core-android-1.7.8.pom`.

## androidx.lifecycle:lifecycle-viewmodel-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-android_2.10.0__lifecycle-viewmodel-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-android/2.10.0/lifecycle-viewmodel-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-android-2.10.0.pom`.

## androidx.print:print:1.0.0

Included published sources: `third_party/runtime-sources/androidx.print_print_1.0.0__print-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/print/print/1.0.0/print-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.print__print-1.0.0.pom`.

## androidx.media3:media3-effect:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-effect_1.11.1__media3-effect-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-effect/1.11.1/media3-effect-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-effect-1.11.1.pom`.

## androidx.profileinstaller:profileinstaller:1.4.0

Included published sources: `third_party/runtime-sources/androidx.profileinstaller_profileinstaller_1.4.0__profileinstaller-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3bc0fb3beaccef509bb170cd69ddaad907b493b8.tar.gz

Revision: `3bc0fb3beaccef509bb170cd69ddaad907b493b8`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.profileinstaller__profileinstaller-1.4.0.pom`.

## androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata-core-ktx_2.10.0__lifecycle-livedata-core-ktx-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core-ktx/2.10.0/lifecycle-livedata-core-ktx-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-core-ktx-2.10.0.pom`.

## androidx.exifinterface:exifinterface:1.3.6

Included published sources: `third_party/runtime-sources/androidx.exifinterface_exifinterface_1.3.6__exifinterface-1.3.6-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/00bc5313008ea35c4c78635d300238697527b76b.tar.gz

Revision: `00bc5313008ea35c4c78635d300238697527b76b`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/exifinterface#1.3.6

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.exifinterface__exifinterface-1.3.6.pom`.

## androidx.media3:media3-muxer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-muxer_1.11.1__media3-muxer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-muxer-1.11.1.pom`.

## androidx.lifecycle:lifecycle-viewmodel:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel_2.10.0__lifecycle-viewmodel-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.10.0/lifecycle-viewmodel-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-2.10.0.pom`.

## androidx.lifecycle:lifecycle-viewmodel-compose-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-compose-android_2.10.0__lifecycle-viewmodel-compose-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose-android/2.10.0/lifecycle-viewmodel-compose-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-compose-android-2.10.0.pom`.

## androidx.annotation:annotation-jvm:1.9.1

Included published sources: `third_party/runtime-sources/androidx.annotation_annotation-jvm_1.9.1__annotation-jvm-1.9.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.annotation__annotation-jvm-1.9.1.pom`.

## androidx.annotation:annotation-experimental:1.4.1

Included published sources: `third_party/runtime-sources/androidx.annotation_annotation-experimental_1.4.1__annotation-experimental-1.4.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/cb993e8594b035615bb12e0cb8294769eb7c10d1.tar.gz

Revision: `cb993e8594b035615bb12e0cb8294769eb7c10d1`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/annotation#annotation-experimental-1.4.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.annotation__annotation-experimental-1.4.1.pom`.

## androidx.compose.ui:ui-text-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-text-android_1.11.4__ui-text-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.11.4/ui-text-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-text-android-1.11.4.pom`.

## androidx.lifecycle:lifecycle-common-jvm:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-common-jvm_2.10.0__lifecycle-common-jvm-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.10.0/lifecycle-common-jvm-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-common-jvm-2.10.0.pom`.

## androidx.compose.runtime:runtime-retain-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-retain-android_1.11.4__runtime-retain-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain-android/1.11.4/runtime-retain-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-retain-android-1.11.4.pom`.

## androidx.customview:customview:1.0.0

Included published sources: `third_party/runtime-sources/androidx.customview_customview_1.0.0__customview-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/customview/customview/1.0.0/customview-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.customview__customview-1.0.0.pom`.

## androidx.compose.ui:ui-graphics-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-graphics-android_1.11.4__ui-graphics-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.11.4/ui-graphics-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-graphics-android-1.11.4.pom`.

## androidx.collection:collection-ktx:1.5.0

Included published sources: `third_party/runtime-sources/androidx.collection_collection-ktx_1.5.0__collection-ktx-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/collection/collection-ktx/1.5.0/collection-ktx-1.5.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.collection__collection-ktx-1.5.0.pom`.

## androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-viewmodel-savedstate-android_2.10.0__lifecycle-viewmodel-savedstate-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate-android/2.10.0/lifecycle-viewmodel-savedstate-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-viewmodel-savedstate-android-2.10.0.pom`.

## org.jetbrains:annotations:23.0.0

Included published sources: `third_party/runtime-sources/org.jetbrains_annotations_23.0.0__annotations-23.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0-sources.jar

Publisher's project source location: https://github.com/JetBrains/java-annotations

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains__annotations-23.0.0.pom`.

## androidx.dynamicanimation:dynamicanimation:1.0.0

Included published sources: `third_party/runtime-sources/androidx.dynamicanimation_dynamicanimation_1.0.0__dynamicanimation-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.dynamicanimation__dynamicanimation-1.0.0.pom`.

## androidx.activity:activity-ktx:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity-ktx_1.13.0__activity-ktx-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.13.0/activity-ktx-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-ktx-1.13.0.pom`.

## androidx.compose.ui:ui-geometry-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-geometry-android_1.11.4__ui-geometry-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry-android/1.11.4/ui-geometry-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-geometry-android-1.11.4.pom`.

## androidx.media3:media3-exoplayer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-exoplayer_1.11.1__media3-exoplayer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.1/media3-exoplayer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-exoplayer-1.11.1.pom`.

## androidx.core:core-ktx:1.18.0

Included published sources: `third_party/runtime-sources/androidx.core_core-ktx_1.18.0__core-ktx-1.18.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.18.0/core-ktx-1.18.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/d0311626e34d3399038ccd85c9ea91f7297de350.tar.gz

Revision: `d0311626e34d3399038ccd85c9ea91f7297de350`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/core#1.18.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-ktx-1.18.0.pom`.

## androidx.media3:media3-extractor:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-extractor_1.11.1__media3-extractor-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-extractor-1.11.1.pom`.

## androidx.lifecycle:lifecycle-livedata:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata_2.10.0__lifecycle-livedata-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata/2.10.0/lifecycle-livedata-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-2.10.0.pom`.

## androidx.lifecycle:lifecycle-livedata-core:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-livedata-core_2.10.0__lifecycle-livedata-core-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.10.0/lifecycle-livedata-core-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-livedata-core-2.10.0.pom`.

## androidx.interpolator:interpolator:1.0.0

Included published sources: `third_party/runtime-sources/androidx.interpolator_interpolator_1.0.0__interpolator-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.interpolator__interpolator-1.0.0.pom`.

## androidx.media3:media3-datasource:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-datasource_1.11.1__media3-datasource-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.11.1/media3-datasource-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-datasource-1.11.1.pom`.

## com.google.guava:failureaccess:1.0.2

Included published sources: `third_party/runtime-sources/com.google.guava_failureaccess_1.0.2__failureaccess-1.0.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__failureaccess-1.0.2.pom`.

## androidx.compose.runtime:runtime-annotation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-annotation-android_1.11.4__runtime-annotation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation-android/1.11.4/runtime-annotation-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-annotation-android-1.11.4.pom`.

## androidx.legacy:legacy-support-core-utils:1.0.0

Included published sources: `third_party/runtime-sources/androidx.legacy_legacy-support-core-utils_1.0.0__legacy-support-core-utils-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/legacy/legacy-support-core-utils/1.0.0/legacy-support-core-utils-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.legacy__legacy-support-core-utils-1.0.0.pom`.

## androidx.arch.core:core-runtime:2.2.0

Included published sources: `third_party/runtime-sources/androidx.arch.core_core-runtime_2.2.0__core-runtime-2.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.arch.core__core-runtime-2.2.0.pom`.

## com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava

Verified empty conflict-avoidance artifact: no executable code or resources. The publisher supplies no sources JAR. Binary hash and ZIP-entry verification: `third_party/runtime-sources/guava-listenablefuture-empty-artifact.txt`.

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.pom`.

## androidx.emoji2:emoji2:1.4.0

Included published sources: `third_party/runtime-sources/androidx.emoji2_emoji2_1.4.0__emoji2-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2/1.4.0/emoji2-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/2d25c5e925564cf1f52fb6d2f88088d2dd5becfe.tar.gz

Revision: `2d25c5e925564cf1f52fb6d2f88088d2dd5becfe`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.emoji2__emoji2-1.4.0.pom`.

## androidx.compose.ui:ui-util-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-util-android_1.11.4__ui-util-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util-android/1.11.4/ui-util-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-util-android-1.11.4.pom`.

## androidx.graphics:graphics-path:1.0.1

Included published sources: `third_party/runtime-sources/androidx.graphics_graphics-path_1.0.1__graphics-path-1.0.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/8a05a22af450d589ef911d772a001a49dcb05b71.tar.gz

Revision: `8a05a22af450d589ef911d772a001a49dcb05b71`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path-1.0.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.graphics__graphics-path-1.0.1.pom`.

## androidx.core:core:1.18.0

Included published sources: `third_party/runtime-sources/androidx.core_core_1.18.0__core-1.18.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core/1.18.0/core-1.18.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/d0311626e34d3399038ccd85c9ea91f7297de350.tar.gz

Revision: `d0311626e34d3399038ccd85c9ea91f7297de350`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/core#1.18.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-1.18.0.pom`.

## androidx.media3:media3-transformer:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-transformer_1.11.1__media3-transformer-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-transformer/1.11.1/media3-transformer-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-transformer-1.11.1.pom`.

## androidx.lifecycle:lifecycle-runtime-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-android_2.10.0__lifecycle-runtime-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.10.0/lifecycle-runtime-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-android-2.10.0.pom`.

## androidx.window:window:1.5.0

Included published sources: `third_party/runtime-sources/androidx.window_window_1.5.0__window-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/window/window/1.5.0/window-1.5.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3b3f055fe2da3f4a8a74003f8640b461b1694954.tar.gz

Revision: `3b3f055fe2da3f4a8a74003f8640b461b1694954`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/window#1.5.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.window__window-1.5.0.pom`.

## androidx.media3:media3-database:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-database_1.11.1__media3-database-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-database/1.11.1/media3-database-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-database-1.11.1.pom`.

## androidx.transition:transition:1.6.0

Included published sources: `third_party/runtime-sources/androidx.transition_transition_1.6.0__transition-1.6.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/transition/transition/1.6.0/transition-1.6.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/184a59d7780ba90e35570b93f6513724b08c2168.tar.gz

Revision: `184a59d7780ba90e35570b93f6513724b08c2168`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/transition#1.6.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.transition__transition-1.6.0.pom`.

## androidx.savedstate:savedstate-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-android_1.4.0__savedstate-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-android/1.4.0/savedstate-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-android-1.4.0.pom`.

## androidx.window:window-core-android:1.5.0

Included published sources: `third_party/runtime-sources/androidx.window_window-core-android_1.5.0__window-core-android-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/window/window-core-android/1.5.0/window-core-android-1.5.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/3b3f055fe2da3f4a8a74003f8640b461b1694954.tar.gz

Revision: `3b3f055fe2da3f4a8a74003f8640b461b1694954`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/window#1.5.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.window__window-core-android-1.5.0.pom`.

## androidx.concurrent:concurrent-futures:1.2.0

Included published sources: `third_party/runtime-sources/androidx.concurrent_concurrent-futures_1.2.0__concurrent-futures-1.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.2.0/concurrent-futures-1.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.concurrent__concurrent-futures-1.2.0.pom`.

## androidx.startup:startup-runtime:1.1.1

Included published sources: `third_party/runtime-sources/androidx.startup_startup-runtime_1.1.1__startup-runtime-1.1.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/8337e0c05174a73ea6db825b680f461c33144bf4.tar.gz

Revision: `8337e0c05174a73ea6db825b680f461c33144bf4`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/startup#1.1.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.startup__startup-runtime-1.1.1.pom`.

## androidx.localbroadcastmanager:localbroadcastmanager:1.0.0

Included published sources: `third_party/runtime-sources/androidx.localbroadcastmanager_localbroadcastmanager_1.0.0__localbroadcastmanager-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.0.0/localbroadcastmanager-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.localbroadcastmanager__localbroadcastmanager-1.0.0.pom`.

## com.google.errorprone:error_prone_annotations:2.28.0

Included published sources: `third_party/runtime-sources/com.google.errorprone_error_prone_annotations_2.28.0__error_prone_annotations-2.28.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.errorprone__error_prone_annotations-2.28.0.pom`.

## androidx.activity:activity:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity_1.13.0__activity-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.13.0/activity-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-1.13.0.pom`.

## org.jetbrains.kotlin:kotlin-stdlib:2.3.21

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlin_kotlin-stdlib_2.3.21__kotlin-stdlib-2.3.21-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21-sources.jar

Publisher's project source location: https://github.com/JetBrains/kotlin

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlin__kotlin-stdlib-2.3.21.pom`.

## androidx.recyclerview:recyclerview:1.3.0

Included published sources: `third_party/runtime-sources/androidx.recyclerview_recyclerview_1.3.0__recyclerview-1.3.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/1.3.0/recyclerview-1.3.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/86bf1b167c3ff33791a75eb42b0c8f056eaeab3e.tar.gz

Revision: `86bf1b167c3ff33791a75eb42b0c8f056eaeab3e`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/recyclerview#recyclerview-1.3.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.recyclerview__recyclerview-1.3.0.pom`.

## androidx.tracing:tracing:1.2.0

Included published sources: `third_party/runtime-sources/androidx.tracing_tracing_1.2.0__tracing-1.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.2.0/tracing-1.2.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/538750227118fd2cca14f4de7df81bace1f50dd3.tar.gz

Revision: `538750227118fd2cca14f4de7df81bace1f50dd3`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.tracing__tracing-1.2.0.pom`.

## androidx.compose.animation:animation-core-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.animation_animation-core-android_1.11.4__animation-core-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.11.4/animation-core-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.animation__animation-core-android-1.11.4.pom`.

## androidx.media3:media3-decoder:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-decoder_1.11.1__media3-decoder-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-decoder/1.11.1/media3-decoder-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-decoder-1.11.1.pom`.

## org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-serialization-core-jvm_1.7.3__kotlinx-serialization-core-jvm-1.7.3-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.serialization

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-serialization-core-jvm-1.7.3.pom`.

## androidx.versionedparcelable:versionedparcelable:1.1.1

Included published sources: `third_party/runtime-sources/androidx.versionedparcelable_versionedparcelable_1.1.1__versionedparcelable-1.1.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/9fd278801e06c07a5d230fd7edbb97e16c322949.tar.gz

Revision: `9fd278801e06c07a5d230fd7edbb97e16c322949`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/versionedparcelable#1.1.1

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.versionedparcelable__versionedparcelable-1.1.1.pom`.

## androidx.autofill:autofill:1.0.0

Included published sources: `third_party/runtime-sources/androidx.autofill_autofill_1.0.0__autofill-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/autofill/autofill/1.0.0/autofill-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/34328d238b2999f231e824d2a9149acdbe3d5c00.tar.gz

Revision: `34328d238b2999f231e824d2a9149acdbe3d5c00`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/autofill#1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.autofill__autofill-1.0.0.pom`.

## androidx.compose.foundation:foundation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.foundation_foundation-android_1.11.4__foundation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.11.4/foundation-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.foundation__foundation-android-1.11.4.pom`.

## androidx.compose.ui:ui-unit-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-unit-android_1.11.4__ui-unit-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.11.4/ui-unit-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-unit-android-1.11.4.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-core-jvm_1.10.2__kotlinx-coroutines-core-jvm-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-core-jvm-1.10.2.pom`.

## androidx.compose.animation:animation-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.animation_animation-android_1.11.4__animation-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.11.4/animation-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.animation__animation-android-1.11.4.pom`.

## androidx.media3:media3-ui:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-ui_1.11.1__media3-ui-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-ui/1.11.1/media3-ui-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-ui-1.11.1.pom`.

## androidx.lifecycle:lifecycle-common-java8:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-common-java8_2.10.0__lifecycle-common-java8-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-java8/2.10.0/lifecycle-common-java8-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-common-java8-2.10.0.pom`.

## androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-compose-android_2.10.0__lifecycle-runtime-compose-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.10.0/lifecycle-runtime-compose-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-compose-android-2.10.0.pom`.

## androidx.compose.foundation:foundation-layout-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.foundation_foundation-layout-android_1.11.4__foundation-layout-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.11.4/foundation-layout-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.foundation__foundation-layout-android-1.11.4.pom`.

## androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-runtime-ktx-android_2.10.0__lifecycle-runtime-ktx-android-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx-android/2.10.0/lifecycle-runtime-ktx-android-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-runtime-ktx-android-2.10.0.pom`.

## com.google.guava:guava:33.3.1-android

Included published sources: `third_party/runtime-sources/com.google.guava_guava_33.3.1-android__guava-33.3.1-android-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/guava/guava/33.3.1-android/guava-33.3.1-android-sources.jar

Publisher's project source location: see the included publication POM

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.guava__guava-33.3.1-android.pom`.

## androidx.media3:media3-container:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-container_1.11.1__media3-container-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-container/1.11.1/media3-container-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-container-1.11.1.pom`.

## org.checkerframework:checker-qual:3.43.0

Included published sources: `third_party/runtime-sources/org.checkerframework_checker-qual_3.43.0__checker-qual-3.43.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0-sources.jar

Publisher's project source location: https://github.com/typetools/checker-framework.git

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.checkerframework__checker-qual-3.43.0.pom`.

## androidx.compose.material:material-ripple-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.material_material-ripple-android_1.11.4__material-ripple-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.11.4/material-ripple-android-1.11.4-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material__material-ripple-android-1.11.4.pom`.

## androidx.compose.ui:ui-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.ui_ui-android_1.11.4__ui-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.ui__ui-android-1.11.4.pom`.

## androidx.activity:activity-compose:1.13.0

Included published sources: `third_party/runtime-sources/androidx.activity_activity-compose_1.13.0__activity-compose-1.13.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/475afe04599ffc0893939effda40187916c4ceb0.tar.gz

Revision: `475afe04599ffc0893939effda40187916c4ceb0`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/activity#1.13.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.activity__activity-compose-1.13.0.pom`.

## org.jspecify:jspecify:1.0.0

Included published sources: `third_party/runtime-sources/org.jspecify_jspecify_1.0.0__jspecify-1.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0-sources.jar

Publisher's project source location: https://github.com/jspecify/jspecify/

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jspecify__jspecify-1.0.0.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-android_1.10.2__kotlinx-coroutines-android-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-android-1.10.2.pom`.

## androidx.core:core-viewtree:1.0.0

Included published sources: `third_party/runtime-sources/androidx.core_core-viewtree_1.0.0__core-viewtree-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/core/core-viewtree/1.0.0/core-viewtree-1.0.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.core__core-viewtree-1.0.0.pom`.

## com.google.code.findbugs:jsr305:3.0.2

Included published sources: `third_party/runtime-sources/com.google.code.findbugs_jsr305_3.0.2__jsr305-3.0.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2-sources.jar

Publisher's project source location: https://code.google.com/p/jsr-305/

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.code.findbugs__jsr305-3.0.2.pom`.

## com.google.j2objc:j2objc-annotations:3.0.0

Included published sources: `third_party/runtime-sources/com.google.j2objc_j2objc-annotations_3.0.0__j2objc-annotations-3.0.0-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0-sources.jar

Publisher's project source location: http://github.com/google/j2objc

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/com.google.j2objc__j2objc-annotations-3.0.0.pom`.

## androidx.savedstate:savedstate-compose-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.savedstate_savedstate-compose-android_1.4.0__savedstate-compose-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-android/1.4.0/savedstate-compose-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/56090e32a2b7028588a6dd0c73d706c6dd8dd1ee.tar.gz

Revision: `56090e32a2b7028588a6dd0c73d706c6dd8dd1ee`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.savedstate__savedstate-compose-android-1.4.0.pom`.

## androidx.documentfile:documentfile:1.0.0

Included published sources: `third_party/runtime-sources/androidx.documentfile_documentfile_1.0.0__documentfile-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/documentfile/documentfile/1.0.0/documentfile-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.documentfile__documentfile-1.0.0.pom`.

## androidx.lifecycle:lifecycle-process:2.10.0

Included published sources: `third_party/runtime-sources/androidx.lifecycle_lifecycle-process_2.10.0__lifecycle-process-2.10.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.10.0/lifecycle-process-2.10.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb.tar.gz

Revision: `ee621bea3237b1eab3c48eef4cf7ef7c7943b4bb`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.lifecycle__lifecycle-process-2.10.0.pom`.

## androidx.loader:loader:1.0.0

Included published sources: `third_party/runtime-sources/androidx.loader_loader_1.0.0__loader-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0-sources.jar

Publisher's project source location: http://source.android.com

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.loader__loader-1.0.0.pom`.

## androidx.arch.core:core-common:2.2.0

Included published sources: `third_party/runtime-sources/androidx.arch.core_core-common_2.2.0__core-common-2.2.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.arch.core__core-common-2.2.0.pom`.

## androidx.compose.material3:material3-android:1.4.0

Included published sources: `third_party/runtime-sources/androidx.compose.material3_material3-android_1.4.0__material3-android-1.4.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/5113b82a4ed6416d6344efa50e41db8cb65928ff.tar.gz

Revision: `5113b82a4ed6416d6344efa50e41db8cb65928ff`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.material3__material3-android-1.4.0.pom`.

## androidx.compose.runtime:runtime-saveable-android:1.11.4

Included published sources: `third_party/runtime-sources/androidx.compose.runtime_runtime-saveable-android_1.11.4__runtime-saveable-android-1.11.4-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.11.4/runtime-saveable-android-1.11.4-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/854220f44ea8ea80fee824a6c5a045f39bede289.tar.gz

Revision: `854220f44ea8ea80fee824a6c5a045f39bede289`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.compose.runtime__runtime-saveable-android-1.11.4.pom`.

## androidx.media3:media3-common:1.11.1

Included published sources: `third_party/runtime-sources/androidx.media3_media3-common_1.11.1__media3-common-1.11.1-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.1/media3-common-1.11.1-sources.jar

Full project sources: https://github.com/androidx/media/archive/8c6678b657ede1e7883fc164ef73ed483c7796c3.tar.gz

Revision: `8c6678b657ede1e7883fc164ef73ed483c7796c3`.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.media3__media3-common-1.11.1.pom`.

## androidx.collection:collection-jvm:1.5.0

Included published sources: `third_party/runtime-sources/androidx.collection_collection-jvm_1.5.0__collection-jvm-1.5.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0-sources.jar

Publisher's project source location: https://cs.android.com/androidx/platform/frameworks/support

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.collection__collection-jvm-1.5.0.pom`.

## androidx.customview:customview-poolingcontainer:1.0.0

Included published sources: `third_party/runtime-sources/androidx.customview_customview-poolingcontainer_1.0.0__customview-poolingcontainer-1.0.0-sources.jar`.

Exact Maven sources: https://dl.google.com/dl/android/maven2/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0-sources.jar

Full project sources: https://android.googlesource.com/platform/frameworks/support/+archive/1e0793130863c72dc4a2d02bc975128f3ef0158b.tar.gz

Revision: `1e0793130863c72dc4a2d02bc975128f3ef0158b`.

Official release/version evidence: https://developer.android.com/jetpack/androidx/releases/customview#customview-poolingcontainer-1.0.0

Publication metadata: `third_party/runtime-sources/maven-metadata/androidx.customview__customview-poolingcontainer-1.0.0.pom`.

## org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2

Included published sources: `third_party/runtime-sources/org.jetbrains.kotlinx_kotlinx-coroutines-guava_1.10.2__kotlinx-coroutines-guava-1.10.2-sources.jar`.

Exact Maven sources: https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-guava/1.10.2/kotlinx-coroutines-guava-1.10.2-sources.jar

Publisher's project source location: https://github.com/Kotlin/kotlinx.coroutines

The included POM/module records the exact publication version and available SCM/build metadata; this row does not assert an unverified source commit.

Publication metadata: `third_party/runtime-sources/maven-metadata/org.jetbrains.kotlinx__kotlinx-coroutines-guava-1.10.2.pom`.

