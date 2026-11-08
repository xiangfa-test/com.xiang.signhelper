@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 离线兜底仓库：libxposed api/interface/service 102.0.0 的原始构件。
        maven {
            name = "BundledOffline"
            url = uri(rootDir.resolve("maven-local"))
            metadataSources {
                mavenPom()
                artifact()
            }
        }
    }
}

include(":app")
rootProject.name = "SignHelper"
