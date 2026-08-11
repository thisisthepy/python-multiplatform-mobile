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
    androidNativeArm64 {
        // Mirrors :python-multiplatform's own androidNativeArm64 test linker config
        // (build.gradle.kts) -- this module's test binary needs the same CPython symbols
        // resolved at final link time, since python-multiplatform's klib only declares them via
        // cinterop and does not itself embed a static libpython. This is a throwaway measurement
        // wiring: only linkDebugTestAndroidNativeArm64 needs it (docs/upcall-table-design.md
        // §11.1's tree-shaking measurement), not the ordinary JVM-only fixture test path.
        val downloadDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone").get().asFile
        val libVersion = (project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString())
            .split('.').subList(0, 2).joinToString(".")
        val targetExtractDir = "$downloadDir/extracted/android-aarch64/prefix"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-Wl,--allow-shlib-undefined"),
        )
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
                implementation(projects.kspFixtures.library)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

dependencies {
    add("kspDesktop", projects.pythonMultiplatformKsp)
    add("kspAndroidNativeArm64", projects.pythonMultiplatformKsp)
}

ksp {
    arg("python.multiplatform.role", "app")
    arg("python.multiplatform.moduleName", "ksp_fixture_app")
}
