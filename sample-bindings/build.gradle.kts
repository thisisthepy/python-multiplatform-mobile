import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The demo's Python-facing Kotlin, kept in a module of its own for one reason: it is the module
 * that can apply the bindings plugin.
 *
 * `:sample` applies `com.android.application`, and KSP 2.3.11 requires AGP >= 8.10 while this
 * build is on 8.5.2 (see the comment in `sample/build.gradle.kts`). No Android plugin here, so
 * KSP runs, `python.multiplatform.generated.FunctionTable` is generated, and `:sample` reaches it
 * from the source sets whose targets this module has.
 *
 * Once AGP moves, this module folds back into `:sample` and its `build.gradle.kts` becomes the
 * three lines below, added to the app's own.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)

    // ROADMAP §7's last wiring item: one id in place of the KSP plugin, an
    // `add("ksp<Target>", ...)` per target and a `ksp { arg(...) }` block.
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64())

    sourceSets {
        commonMain.dependencies {
            api(projects.pythonMultiplatform)
        }
    }
}

pythonBindings {
    // This module produces no binary of its own, so the plugin would infer `library` -- but the
    // aggregated `FunctionTable` has to exist somewhere, and `:sample` cannot host it (above).
    role.set("app")

    // In-repo consumer: the processor is a project here rather than published coordinates.
    processor.set(projects.pythonMultiplatformKsp)
}
