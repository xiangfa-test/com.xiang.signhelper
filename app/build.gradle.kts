@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 版本号方案（用户定）：
 *  - **versionName = 发版时才更新的版本号**（`0.1.0` 起步，手工写在
 *    `version.properties` 的 `name` 里；平时改代码不动它）
 *  - **versionCode = git commit 数**（自动派生，提交越多越大，单调递增）
 *
 * versionCode 从 git 现场取数而不是写死在文件里：提交与构建顺序不会骗人，
 * 也不会出现"改完忘了改版本号"。不在 git 仓库里（导出源码包）时退化为 0。
 */
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = versionProps.getProperty("name").trim()
val appVersionCode: Int = gitCommitCount.coerceAtLeast(0)
val buildTime = System.currentTimeMillis()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf(String::isNotBlank)
        ?: System.getenv(environmentKey)?.takeIf(String::isNotBlank)

val releaseStoreFilePath = signingValue("storeFile", "KEYSTORE_PATH")

android {
    namespace = "com.xiang.signhelper"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.xiang.signhelper"
        // Miuix 0.9.4 / miuix-blur 要求 minSdk 33。
        minSdk = 33
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    signingConfigs {
        // 本机 ~/.android 不可写，AGP 生成不了默认 debug.keystore；
        // 复用 参考项目 那把 PKCS12，debug / release 还能互相覆盖安装。
        if (releaseStoreFilePath != null) {
            val releaseSigning = maybeCreate("release")
            releaseSigning.storeFile = rootProject.file(releaseStoreFilePath)
            releaseSigning.storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
            releaseSigning.keyAlias = signingValue("keyAlias", "KEY_ALIAS")
            releaseSigning.keyPassword = signingValue("keyPassword", "KEY_PASSWORD")

            maybeCreate("debug").apply {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // META-INF/xposed/* 承载现代 Xposed 模块元数据，必须留在 APK 里。
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
            excludes += "/kotlin/**"
            excludes += "/*.txt"
            excludes += "/*.bin"
        }
        dex {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("SignHelper-$appVersionName($appVersionCode)-${variant.name}-$buildTime.apk")
        }
    }
}

dependencies {
    // 现代 Xposed API（libxposed）。运行时由框架提供，绝不能打进 APK。
    compileOnly("io.github.libxposed:api:102.0.0")
    // 现代 Xposed service（远程 SharedPreferences / 作用域请求）—— 需要打进模块 APK。
    implementation("io.github.libxposed:service:102.0.0")

    // Compose Multiplatform runtime（Miuix 基于 1.12.x 构建）。
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Miuix（澎湃 OS / MIUI 设计体系）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    implementation("androidx.core:core-ktx:1.17.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

