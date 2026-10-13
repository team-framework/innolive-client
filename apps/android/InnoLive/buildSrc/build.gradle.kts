plugins {
    `kotlin-dsl`
}

dependencies {
    // buildSrc가 AGP를 클래스패스에 올립니다. 앱과 루트 빌드는 버전 없이 플러그인 ID만 씁니다.
    implementation(libs.android.gradle.plugin)
    implementation(libs.asm)
}

gradlePlugin {
    plugins {
        register("redactLibraryLogs") {
            id = "innolive.redact-library-logs"
            implementationClass = "RedactLibraryLogsPlugin"
        }
    }
}
