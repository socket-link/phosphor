import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// PHO-69 spike: a DOM/SVG adapter of LumosCanvasFrame. Throwaway; nothing here merges.
// No npm package, no React, no Compose: the only dependencies are the Phosphor modules.
plugins {
    kotlin("multiplatform")
}

group = "link.socket"
version = "spike"

kotlin {
    applyDefaultHierarchyTemplate()

    js(IR) {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":phosphor-lumos"))
                implementation(project(":phosphor-core"))
                // Not :phosphor-lumos-compose: depending on it for LumosCanvasFrame / ComposeLattice dragged
                // Skiko's `./skiko.mjs` import into the wasm link and webpack failed. Both files are copied
                // into webMain/.../frame for the spike; the real change lifts them into :phosphor-lumos.
                // SurfaceStyle is @Serializable; its companion's supertype must resolve here.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                // Kotlin/Wasm ships kotlinx.browser and org.w3c.dom as a library, not in the stdlib.
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3.1")
            }
        }
    }
}
