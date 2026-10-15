pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder RTMP(S) 클라이언트는 JitPack에만 배포된다. 다른 의존성이 이 저장소에서 풀리지 않게 그룹을 제한한다.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.pedroSG94.RootEncoder") }
        }
    }
}

rootProject.name = "InnoLive"
include(":app")
 