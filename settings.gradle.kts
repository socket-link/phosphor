rootProject.name = "Phosphor"

include(":phosphor-core")
include(":phosphor-lumos")
include(":phosphor-lumos-cli")
include(":phosphor-lumos-compose")
include(":phosphor-lumos-dom")
include(":phosphor-lumos-surface")
include(":phosphor-trace")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String

        kotlin("multiplatform").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
        kotlin("plugin.compose").version(kotlinVersion)
        id("org.jlleitschuh.gradle.ktlint").version("12.2.0")
        id("org.jetbrains.dokka").version("2.1.0")
        id("com.vanniktech.maven.publish").version("0.30.0")
        // Socket links Compose 1.10.1. Building against an older runtime publishes Native/web klibs
        // that read Compose's `$stable` properties directly, which 1.10 made private (PHO-91).
        id("org.jetbrains.compose").version("1.10.1")
        // Socket's Android build runs this AGP on the same Kotlin and Gradle versions. 8.13 is the
        // last AGP line Gradle 8.13 runs; AGP 9 needs Gradle 9 and moves KMP libraries to
        // com.android.kotlin.multiplatform.library.
        id("com.android.library").version("8.13.2")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
