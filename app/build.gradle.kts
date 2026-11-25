import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

android {
    namespace = "io.easypi.mobile"
    compileSdk = 36

    defaultConfig {
        // Huawei's online package reputation service flags the legacy
        // io.easypi.mobile identity even when the same signed binary is clean
        // under a fresh identity. Keep this application ID stable for all
        // releases after 0.1.4.
        applicationId = "io.easypi.mobile.client"
        minSdk = 30
        targetSdk = 36
        versionCode = 10
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USE_GOOGLE_DNS_FALLBACK", "true")
        ndk {
            val configuredAbis = providers.gradleProperty("easypiAbis")
                .orElse("arm64-v8a")
                .get()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            abiFilters += configuredAbis
        }
    }
    buildFeatures { buildConfig = true; compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Build-SecureRelease.ps1 signs the unsigned release with an
            // external production key and a verified certificate lineage.
            // Never silently fall back to the debug key for a release.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.all { it.useJUnit() } }
    lint { disable += "AndroidGradlePluginVersion" }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreKtx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.work.runtimeKtx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.stdlib)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.toolingPreview)
    implementation(libs.androidx.activity.compose)
    implementation(files("libs/easypinative.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}
