import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

// The DOM/SVG surface for Lumos: Kotlin/JS only, an ES module, no npm package, no React, no Compose.
// PHO-69 measured the same adapter from Kotlin/Wasm at 2–3× the cost a frame — every setAttribute
// crosses the wasm/JS boundary as a string — so there is no wasmJs target. Not published to Maven:
// what ships is `jsBrowserDistribution`'s `phosphor-lumos-dom.mjs`, imported by a page.
plugins {
    kotlin("multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

val phosphorVersion: String by project

group = "link.socket"
version = phosphorVersion

// Per-test timeout for the mocha runner; mocha's default is 2 s.
val testTimeout = "60s"

kotlin {
    js(IR) {
        browser {
            webpackTask {
                // An ES module a page imports, not a UMD global: webpack.config.d/es-module.js turns
                // on the output-module experiment this library type needs.
                mainOutputFileName.set("phosphor-lumos-dom.mjs")
                output.library = null
                output.libraryTarget = "module"
            }
            testTask {
                // Mocha on Node: the tests drive the surface and the driver through fakes, so they
                // need no browser.
                useMocha { timeout = testTimeout }
            }
        }
        useEsModules()
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":phosphor-lumos"))
                implementation(project(":phosphor-core"))
                // SurfaceStyle and LumosConfig are @Serializable; their companions' supertypes must resolve here.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// PHO-88's budget for the bundle a page loads: 110 KB gzipped before PHO-87 dropped
// kotlinx-datetime, 70 KB after. Nothing from Skiko or Compose may reach it.
val bundleBudgetBytes = 70 * 1024

val checkBundleSize by tasks.registering {
    group = "verification"
    description =
        "Fails if the production bundle is over ${bundleBudgetBytes / 1024} KB gzipped or carries Skiko or Compose."
    dependsOn("jsBrowserDistribution")

    val distribution = layout.buildDirectory.dir("dist/js/productionExecutable")
    inputs.dir(distribution)

    doLast {
        val scripts =
            distribution.get().asFile.listFiles()
                .orEmpty()
                .filter { it.isFile && (it.name.endsWith(".mjs") || it.name.endsWith(".js")) }
                .sortedBy { it.name }
        if (scripts.isEmpty()) throw GradleException("No scripts in ${distribution.get().asFile}")

        var raw = 0L
        var gzipped = 0L
        for (script in scripts) {
            val bytes = script.readBytes()
            val packed =
                ByteArrayOutputStream().also { out ->
                    object : GZIPOutputStream(out) {
                        init {
                            def.setLevel(Deflater.BEST_COMPRESSION)
                        }
                    }.use { it.write(bytes) }
                }.size()
            raw += bytes.size
            gzipped += packed
            println("  %-32s raw %8.1f KB   gzip %7.1f KB".format(script.name, bytes.size / 1024.0, packed / 1024.0))

            val text = bytes.decodeToString()
            for (forbidden in listOf("skiko", "androidx.compose")) {
                if (text.contains(forbidden, ignoreCase = true)) {
                    throw GradleException(
                        "${script.name} carries \"$forbidden\": a Compose dependency reached the bundle",
                    )
                }
            }
        }
        println("  %-32s raw %8.1f KB   gzip %7.1f KB".format("total", raw / 1024.0, gzipped / 1024.0))
        if (gzipped > bundleBudgetBytes) {
            throw GradleException(
                "The bundle is ${gzipped / 1024} KB gzipped, over the ${bundleBudgetBytes / 1024} KB budget",
            )
        }
    }
}
