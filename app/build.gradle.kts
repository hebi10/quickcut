plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStore = providers.environmentVariable("QUICKCUT_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("QUICKCUT_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("QUICKCUT_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("QUICKCUT_KEY_PASSWORD").orNull
val releaseCredentials = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
check(releaseCredentials.all { it.isNullOrBlank() } || releaseCredentials.all { !it.isNullOrBlank() }) {
    "Release signing requires all four QUICKCUT signing environment variables."
}

android {
    namespace = "com.hebi.quickcut"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.hebi.quickcut"
        minSdk = 29
        targetSdk = 36
        // The override is only for the emulator's older-version installation fixture.
        versionCode = providers.gradleProperty("quickcutTestVersionCode").orNull?.toInt() ?: 11
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (!releaseStore.isNullOrBlank()) {
            create("stableRelease") {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // Without credentials this is deliberately unsigned, never debug-signed.
            if (!releaseStore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main") {
        assets.srcDir(rootProject.layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
        assets.srcDir(rootProject.layout.buildDirectory.dir("generated/smartcutAssets").get().asFile)
        jniLibs.srcDir(layout.buildDirectory.dir("generated/nativeLibs").get().asFile)
    }
    packaging.jniLibs.useLegacyPackaging = false

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The reproducible script is run before Gradle by CI and documented local builds.
// Never produce a release that silently omitted the requested native engine.
val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("third_party")) { include("licenses/**") }
    into(rootProject.layout.buildDirectory.dir("generated/licenseAssets"))
}
val verifyNativeSmartCut by tasks.registering {
    doLast {
        listOf("arm64-v8a", "x86_64").forEach { abi ->
            val library = layout.buildDirectory.file(
                "generated/nativeLibs/$abi/libquickcut_smartcut.so"
            ).get().asFile
            check(library.isFile && library.length() > 0L) {
                "Missing $abi smart-cut engine. Run bash scripts/build-native-smartcut.sh before Gradle."
            }
        }
        val notices = rootProject.layout.buildDirectory.file(
            "generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt"
        ).get().asFile
        check(notices.isFile && notices.length() > 0L) {
            "Native dependency notices are missing. Re-run scripts/build-native-smartcut.sh."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNativeSmartCut, prepareLicenseAssets) }

dependencies {
    val media3Version = "1.11.1"
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


