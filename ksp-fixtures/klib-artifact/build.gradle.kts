import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The artefact walker end to end: a real third-party jar the build resolves, walked at build time,
 * installed into the same `UpcallTable` KSP's own fragments go into, and imported from Python.
 *
 * `docs/ecosystem.md` §5b names two producers of bindings and splits them by *what they look at* --
 * KSP the consumer's own source, the walker everything the build resolves. `:ksp-fixtures:app`
 * covers the first. Nothing covered the second, which is why `androidx.compose.material3` was
 * believed to be unreachable.
 *
 * Deliberately a module of its own rather than another target in `:ksp-fixtures:app`: that module's
 * `CommonInstallTest` asserts `UpcallTable.moduleNames` **exactly**, and a walked artefact appearing
 * there would be a change to what `FunctionTable` means rather than an addition beside it. Keeping
 * the two apart in the build is the same judgement `ArtifactTableRenderingTest` records for the
 * generated code.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    // One target. The walker reads jars, and jars are what a JVM target resolves; whether the same
    // walk is possible over a `.klib` is `docs/ecosystem.md` §5b's open question and is not
    // answered by adding a Native target that would silently bind nothing.
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidNativeArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        val desktopMain by getting {
            dependencies {
                // A real dependency, declared the ordinary way. Nothing about it knows it is going
                // to be walked, which is the whole claim: KSP cannot see inside it, and the walker
                // does not need it to cooperate.
                implementation(libs.junit)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val androidNativeArm64Main by getting {
            dependencies {
                // Coroutines is a good klib to scan
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val androidNativeArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    // Neither `application` nor `com.android.application` is applied, so the aggregating role has to
    // be stated -- this module needs a `FunctionTable` to install *beside* `ArtifactTable`.
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("desktopCompileClasspath")
    artifactSourceSet.set("desktopMain")
    // Narrow on purpose. `junit.runner.Version.id()` answers `"4.13.2"`, which is the artefact's own
    // version and therefore cannot be produced by anything except the artefact's own code -- the
    // end-to-end test reads exactly that string back out of Python.
    artifactIncludePackages.set(listOf("junit.runner", "junit.framework"))
}

/**
 * The same JVM the library's own `desktopTest` needs: JDK 21 with the preview FFM API, and a
 * `java.library.path` for the `libpython` that `manager.loadLibPython` extracts out of
 * `python-multiplatform`'s desktop jar next to the working directory.
 *
 * `PYTHONHOME` is *not* set here -- the bindings plugin's `stagePythonHome` already points every
 * `Test` task at a staged CPython prefix, and this is the first fixture that actually exercises it.
 */
tasks.named<Test>("desktopTest") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
}
