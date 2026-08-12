// Standalone. Deliberately NOT part of the main build -- it exists to answer four questions
// about Kotlin/Wasm's module and memory ABI that decide the shape of ROADMAP §10, and it needs a
// newer Kotlin than the library pins (2.0.20). See docs/wasm-design.md.

plugins {
    kotlin("multiplatform") version "2.4.20-Beta2"
}

kotlin {
    wasmJs {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}
