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
        id("org.jetbrains.dokka").version("2.1.0")
        id("com.vanniktech.maven.publish").version("0.30.0")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
