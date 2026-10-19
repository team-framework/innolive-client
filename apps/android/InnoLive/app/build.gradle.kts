import java.util.Properties

plugins {
    // AGP 버전은 buildSrc 의존성(libs.versions.toml의 agp)으로 정합니다.
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    // RootEncoder 로그의 스트림 키 노출을 막습니다(buildSrc/RedactLibraryLogsPlugin.kt).
    id("innolive.redact-library-logs")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
// Opt-in minified instrumentation uses the local debug certificate, never release signing.
val privacyReleaseTest = providers.gradleProperty("privacyReleaseTest").orNull == "true"

if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

android {
    if (privacyReleaseTest) testBuildType = "release"
    namespace = "com.framework.innolive"
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.framework.innolive"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PRIVACY_PERF_DIAGNOSTICS", privacyReleaseTest.toString())
        buildConfigField(
            "String",
            "INNOLIVE_SERVER_URL",
            "\"${localProperties.getProperty("INNOLIVE_SERVER_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID", "")}\"",
        )
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (privacyReleaseTest) {
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("privacy-instrumentation-rules.pro")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val cameraXVersion = "1.6.1"

    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.credentials:credentials:1.7.0-alpha02")
    implementation("androidx.credentials:credentials-play-services-auth:1.7.0-alpha02")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation("com.github.pedroSG94.RootEncoder:rtmp:2.8.1")
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20251224")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    if (privacyReleaseTest) implementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "innolive.android.resDir",
        layout.projectDirectory.dir("src/main/res").asFile.absolutePath,
    )
}
