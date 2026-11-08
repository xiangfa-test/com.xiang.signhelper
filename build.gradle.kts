@file:Suppress("UnstableApiUsage")

buildscript {
    dependencies {
        // AGP 9 自带 Kotlin 支持；Miuix 0.9.4 用 Kotlin 2.4.x 编译，
        // 消费方必须抬到同一代 KGP 才能内联它的代码。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

tasks.register<Delete>("clean").configure {
    delete(layout.buildDirectory)
}
