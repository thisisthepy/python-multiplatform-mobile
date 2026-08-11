import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // The Native target that actually verifies `.klib` fragment discovery
    // (docs/upcall-table-design.md §8's open question). Compile-only here: androidNativeArm64
    // targets a device/emulator this workspace does not run tests against.
    androidNativeArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
    }
}

dependencies {
    // KSP on Kotlin/Multiplatform is applied per target
    // (docs/upcall-table-design.md §11 "KSP for KMP") -- there is no single `ksp(...)` that
    // reaches every target at once.
    add("kspDesktop", projects.pythonMultiplatformKsp)
    add("kspAndroidNativeArm64", projects.pythonMultiplatformKsp)
}

ksp {
    arg("python.multiplatform.role", "library")
    arg("python.multiplatform.moduleName", "ksp_fixture_library")
}
