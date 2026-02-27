rootProject.name = "Phosphor"

include(":phosphor-core")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String

        kotlin("multiplatform").version(kotlinVersion)
        id("org.jlleitschuh.gradle.ktlint").version("12.2.0")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
