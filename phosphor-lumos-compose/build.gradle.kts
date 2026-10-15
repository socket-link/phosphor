import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.compose")
}

val phosphorVersion: String by project

group = "link.socket"
version = phosphorVersion

val hasInMemorySigningCredentials =
    providers.gradleProperty("signingInMemoryKey").isPresent &&
        providers.gradleProperty("signingInMemoryKeyId").isPresent &&
        providers.gradleProperty("signingInMemoryKeyPassword").isPresent
val hasFileSigningCredentials =
    providers.gradleProperty("signing.secretKeyRingFile").isPresent &&
        providers.gradleProperty("signing.keyId").isPresent &&
        providers.gradleProperty("signing.password").isPresent
val hasGpgSigningCredentials = providers.gradleProperty("signing.gnupg.keyName").isPresent
val hasSigningCredentials =
    hasInMemorySigningCredentials ||
        hasFileSigningCredentials ||
        hasGpgSigningCredentials
val isPublishingToMavenLocal =
    gradle.startParameter.taskNames.any {
        it == "publishToMavenLocal" || it.endsWith(":publishToMavenLocal")
    }

kotlin {
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    androidTarget {
        publishLibraryVariants("release")

        // 11, not the JVM target's 21: Kotlin refuses to inline from a newer JVM target than
        // the caller's, and Android modules (Socket's app among them) still compile for 11.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js(IR) {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    val xcf = XCFramework()
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())

    iosTargets.forEach {
        it.binaries.framework {
            baseName = "phosphor-lumos-compose"
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":phosphor-lumos"))
                implementation(project(":phosphor-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation(project(":phosphor-core"))
            }
        }
        val jvmTest by getting {
            dependencies {
                // Test-only, and only on the JVM (phosphor-trace has no wasm target):
                // the PHO-42 pixel-equivalence test projects real v2 traces.
                implementation(project(":phosphor-trace"))
                // Test-only Skia native runtime for the host OS, so jvmTest can paint
                // LumosCanvas into an ImageBitmap and read pixels back (PHO-70).
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "link.socket.phosphor.lumos.compose"
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    coordinates("link.socket", "phosphor-lumos-compose", version.toString())

    pom {
        name.set("Phosphor Lumos Compose")
        description.set(
            "Compose Multiplatform DTOs and Canvas rendering surface for Phosphor's " +
                "framework-free Lumos voxel-orb visualization.",
        )
        url.set("https://github.com/socket-link/phosphor")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("socket-link")
                name.set("Socket Link")
                url.set("https://github.com/socket-link")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/socket-link/phosphor.git")
            developerConnection.set("scm:git:ssh://git@github.com:socket-link/phosphor.git")
            url.set("https://github.com/socket-link/phosphor")
        }

        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/socket-link/phosphor/issues")
        }
    }
}

plugins.withId("signing") {
    extensions.configure<SigningExtension>("signing") {
        if (hasGpgSigningCredentials) {
            useGpgCmd()
        }
    }
}

tasks.withType<Sign>().configureEach {
    enabled = hasSigningCredentials && !isPublishingToMavenLocal
}
