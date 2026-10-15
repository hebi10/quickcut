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

    defaultConfig {
        applicationId = "com.hebi.quickcut"
        minSdk = 29
        targetSdk = 36
        // The override is only for the emulator's older-version installation fixture.
        versionCode = providers.gradleProperty("quickcutTestVersionCode").orNull?.toInt() ?: 6
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

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


