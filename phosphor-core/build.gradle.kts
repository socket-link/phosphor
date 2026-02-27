import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
    id("signing")
}

val phosphorVersion: String by project

group = "link.socket"
version = phosphorVersion

kotlin {
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    js(IR) {
        browser()
        binaries.executable()
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    val xcf = XCFramework()
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())

    iosTargets.forEach {
        it.binaries.framework {
            baseName = "phosphor-core"
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

signing {
    useGpgCmd()
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    coordinates("link.socket", "phosphor-core", version.toString())

    pom {
        name.set("Phosphor")
        description.set(
            "Kotlin Multiplatform rendering library that translates cognitive state " +
                "into visible light — ASCII luminance, color ramps, particle physics, " +
                "and 3D waveform surfaces.",
        )
        url.set("https://github.com/socket-link/phosphor")
        inceptionYear.set("2025")

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
