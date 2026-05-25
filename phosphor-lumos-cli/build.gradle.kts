import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":phosphor-lumos"))
                api(project(":phosphor-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    coordinates("link.socket", "phosphor-lumos-cli", version.toString())

    pom {
        name.set("Phosphor Lumos CLI")
        description.set(
            "JVM terminal DTOs and future CLI renderer surface for Phosphor's " +
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
