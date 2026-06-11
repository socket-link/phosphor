rootProject.name = "Phosphor"

include(":phosphor-core")
include(":phosphor-lumos")
include(":phosphor-lumos-cli")
include(":phosphor-lumos-compose")
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
        id("org.jetbrains.compose").version("1.7.0")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
