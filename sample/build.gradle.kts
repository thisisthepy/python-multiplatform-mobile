import java.io.ByteArrayOutputStream
import org.apache.tools.ant.util.TeeOutputStream
import org.gradle.internal.classpath.Instrumented
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_ARM64
import org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_X64
import org.jetbrains.kotlin.konan.target.KonanTarget.IOS_ARM64
import org.jetbrains.kotlin.konan.target.KonanTarget.IOS_SIMULATOR_ARM64
import org.jetbrains.kotlin.konan.target.KonanTarget.IOS_X64


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.graalvm.native)

    // The one id the plugin advertises, applied to a module that also carries an Android plugin
    // -- which is the whole of ROADMAP §13. It applies `com.google.devtools.ksp`, and KSP 2.3.11
    // declares `MINIMUM_SUPPORTED_AGP_VERSION = 8.10.0`; against this build's former AGP 8.5.2 it
    // died at configuration time with
    //
    //   java.lang.NoSuchMethodError: 'void com.android.build.api.variant
    //       .AndroidComponentsExtension.addKspConfigurations(boolean)'
    //     at com.google.devtools.ksp.gradle.KspConfigurations$3$1.execute(KspConfigurations.kt:114)
    //
    // in *any* module carrying an Android plugin, so the demo was split into `:sample` and an
    // Android-free `:sample-bindings` to get a generated table at all. AGP is 8.10.1 now (and
    // Gradle 8.11.1, which AGP 8.10 requires), so the split is gone and this module holds its own
    // Python-facing declarations again -- `src/*/kotlin/.../demo/bindings/`.
    //
    // `ksp-fixtures/android` is the regression test. It is the first fixture to apply an Android
    // plugin, which is exactly why nothing caught this.
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    // Re-enabled: the comment above this used to say "currently not supported by Python
    // Multiplatform", which was true when it was written (the library had no wasmJs target at
    // all) and has not been true since ROADMAP §10 -- `:python-multiplatform:wasmJsNodeTest` has
    // run green for a while. Sections 5-6 (proxies) became reachable specifically after
    // `4472f83a` taught the library to install the generated proxy module on wasm using `self` as
    // the dispatcher, with no new `@WasmExport` needed per callable.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "demo.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val targetABI = when(iosTarget.konanTarget) {
            ANDROID_ARM64 -> "arm64-v8a"
            ANDROID_X64 -> "x86_64"
            IOS_ARM64 -> "ios-arm64"
            IOS_X64 -> "ios-arm64_x86_64-simulator"
            IOS_SIMULATOR_ARM64 -> "ios-arm64_x86_64-simulator"
            else -> throw RuntimeException("Unsupported ABI: ${iosTarget.konanTarget}")
        }
        iosTarget.binaries.framework {
            baseName = "Demo"

            linkerOpts.addAll(listOf(
                "-framework", "Python", "-F$projectDir/build/xcode-frameworks/Python.xcframework/$targetABI", "-Objc"
            ))

            //export(projects.pythonMultiplatform)
        }
    }
    sourceSets {
        val desktopMain by getting
        
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            api(projects.pythonMultiplatform)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        val wasmJsMain by getting
        wasmJsMain.dependencies {
            // `kotlinx.browser.document` used to be bundled into the wasm stdlib; it moved to
            // this artifact, and without it `main.kt`'s `document.body` does not resolve.
            implementation(libs.kotlinx.browser)
        }
    }
}

val prepareIosFrameworks by tasks.registering(Sync::class) {
    dependsOn(":python-multiplatform:downloadPython_ios")
    
    val samplePythonVersion = project.findProperty("pythonVersion")?.toString() ?: rootProject.version.toString()
    val extractedIosDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone/extracted/$samplePythonVersion/ios")
    
    from(extractedIosDir)
    into(layout.buildDirectory.dir("xcode-frameworks"))
}

tasks.matching { it.name.startsWith("link") && it.name.contains("Ios") }.configureEach {
    dependsOn(prepareIosFrameworks)
}

android {
    namespace = "org.thisisthepy.python.multiplatform.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.thisisthepy.python.multiplatform.demo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

pythonBindings {
    // `role` is not stated: `com.android.application` is applied, so the plugin infers `app` and
    // this module aggregates. That inference is half the reason the plugin exists.

    // In-repo consumer: the processor is a project here rather than published coordinates.
    processor.set(projects.pythonMultiplatformKsp)

    // Exposure is a blacklist, so applying the plugin offers *every* public declaration in this
    // module to Python -- including the Compose UI. A `@Composable` function may only be called
    // from another composable, and the generated fragment's entry is an ordinary lambda, so a
    // scanned `@Composable` is a compile failure of generated code rather than a useless entry.
    // The demo's Python-facing surface lives in `...demo.bindings`; the UI package is the one
    // that has to be kept out.
    excludePackages.set(listOf("org.thisisthepy.python.multiplatform.demo.ui"))
}

compose.desktop {
    application {
        mainClass = "org.thisisthepy.python.multiplatform.demo.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.thisisthepy.python.multiplatform.demo"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {  // Compiler Settings
    options.compilerArgs.addAll(listOf("--enable-preview", "--add-modules=jdk.incubator.foreign"))
}

tasks.withType<JavaExec>().configureEach {  // JVM Execution Settings
    // `jdk.incubator.foreign` only exists on JDK 16-18; on the JDK 21 this project actually
    // targets (see python-multiplatform's desktopTest javaLauncher), `--add-modules` naming a
    // module that does not exist aborts the JVM before main() runs at all. java.lang.foreign
    // is a preview API on 21 and needs only `--enable-preview`.
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.withType<Test>().configureEach {  // Test Settings
    jvmArgs(
        "--enable-preview",
        "--add-modules=jdk.incubator.foreign",
        "--enable-native-access=ALL-UNNAMED"
    )
}

// Runs NativeImageMain.kt on the plain JVM -- the sanity check that has to pass before the same
// entry point is worth pointing native-image at.
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
val pythonMultiplatformDesktopJar = project(":python-multiplatform").tasks.named("desktopJar")

// python-multiplatform's own desktopTest task derives this the same way; kept as a single
// definition here so the JVM sanity-check task and the native-image binary agree on it.
val hostPlatform = when {
    System.getProperty("os.name").contains("Mac") ->
        if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
    System.getProperty("os.name").contains("Windows") -> "windows-x86_64"
    else -> "linux-x86_64"
}
// The extraction tree is keyed by CPython version and desktop flavour (see python-multiplatform's
// `extractedDir` / `desktopFlavourSuffix` for why the guard needs that), so this has to be derived
// the same way rather than hardcoding a flat path -- otherwise the sample points PYTHONHOME at a
// directory that only exists for the default configuration.
val samplePythonVersion = project.findProperty("pythonVersion")?.toString() ?: rootProject.version.toString()
val sampleFlavourSuffix =
    if (project.findProperty("pythonFreeThreaded")?.toString()?.toBoolean() == true) "-freethreaded" else ""
val pythonHomeForHost = project(":python-multiplatform").layout.buildDirectory
    .dir("python-standalone/extracted/$samplePythonVersion/$hostPlatform$sampleFlavourSuffix/python")

tasks.register<JavaExec>("runNativeImageUpcallDemo") {
    group = "verification"
    description = "Runs the upcall verification entry point on the plain JVM (pre native-image sanity check)"
    dependsOn(desktopMainCompilation.compileTaskProvider, pythonMultiplatformDesktopJar)
    mainClass.set("org.thisisthepy.python.multiplatform.demo.NativeImageMainKt")
    // libpython is packaged as a resource only by python-multiplatform's `desktopJar` task (see
    // its Jar block copying `lib/<platform>/libpython*`); the project dependency's default
    // runtime variant resolves to raw class/resource directories that never went through that
    // packaging step, so manager.kt's classpath resource lookup fails without the jar itself.
    classpath = files(
        desktopMainCompilation.output.allOutputs,
        pythonMultiplatformDesktopJar,
        desktopMainCompilation.runtimeDependencyFiles,
    )
    // Py_Initialize() aborts with "Failed to import encodings module" unless PYTHONHOME points
    // at a prefix with a `lib/python3.14` stdlib -- the standalone build embeds the path from the
    // machine that built it, not this one. Mirrors python-multiplatform's own desktopTest task.
    environment("PYTHONHOME", pythonHomeForHost.get().asFile.absolutePath)
}

// `:sample:run` (Compose Desktop's own task) needs the same two things the task above needs, and
// used to get neither: `Py_Initialize()` aborts with "Failed to import encodings module" without
// a PYTHONHOME holding a real stdlib, and `manager.loadLibPython` finds no `libpython` on the
// classpath because a project dependency resolves to class directories rather than to
// `desktopJar` -- it falls back to `$PYTHONHOME/lib`, which only exists once the archive has been
// extracted. Both fixed here rather than in `runNativeImageUpcallDemo`'s style (an explicit
// classpath), because `run`'s classpath is Compose's to build.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(":python-multiplatform:downloadAllPythonBuilds")
    (this as? JavaExec)?.environment("PYTHONHOME", pythonHomeForHost.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------
// GraalVM native-image (ROADMAP §7's "does the upcall path survive a closed-world binary"
// question). Wiring lineage traced from /Volumes/macMini/thisisthepy/compose-graal-hello, which
// is a plain `kotlin("jvm")` project.
//
// The `org.graalvm.buildtools.native` plugin (applied above via `libs.plugins.graalvm.native`)
// is kept for its `javaToolchains`-based toolchain resolution, but its automatic task wiring
// (`nativeCompile`, `nativeRun`, ...) never appears here: that wiring is registered only when the
// `java`/`application` plugin's `sourceSets.main` exists, and a Kotlin Multiplatform `jvm()`
// target does not create one -- confirmed by `graalvmNative.binaries.create("main") { ... }`
// configuring successfully (the `NativeImageOptions` DSL itself has no such dependency) while no
// `nativeCompile`/`nativeRun`/`nativeBuild` task is ever registered by the plugin to consume it.
// This is a wiring gap in the plugin's Kotlin-Multiplatform support, not a limitation of
// native-image itself, so the build calls `native-image` directly instead of going through the
// plugin's lifecycle tasks. Points only at NativeImageMain.kt, not at MainKt's Compose/AWT window
// -- see NativeImageMain.kt for why those are deliberately different entry points.
// ---------------------------------------------------------------------------------------------

val nativeImageJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val nativeImageOutputDir = layout.buildDirectory.dir("native/nativeCompile")

val nativeCompile by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds upcall-native-demo with GraalVM native-image from NativeImageMain.kt"
    dependsOn(desktopMainCompilation.compileTaskProvider, pythonMultiplatformDesktopJar)

    val classpathProvider = files(
        desktopMainCompilation.output.allOutputs,
        pythonMultiplatformDesktopJar,
        desktopMainCompilation.runtimeDependencyFiles,
    )
    val outDir = nativeImageOutputDir
    val launcher = nativeImageJavaLauncher

    inputs.files(classpathProvider)
    outputs.dir(outDir)

    doFirst {
        outDir.get().asFile.mkdirs()
    }

    executable = launcher.get().metadata.installationPath.asFile.resolve("bin/native-image").absolutePath
    args(
        "-cp", classpathProvider.asPath,
        "--no-fallback",
        "--enable-native-access=ALL-UNNAMED",
        "-o", outDir.get().asFile.resolve("upcall-native-demo").absolutePath,
        "org.thisisthepy.python.multiplatform.demo.NativeImageMainKt",
    )
}

tasks.register<Exec>("runNativeUpcallDemo") {
    group = "verification"
    description = "Runs the native-image binary built by nativeCompile and checks the upcall path"
    dependsOn(nativeCompile)
    executable = nativeImageOutputDir.get().asFile.resolve("upcall-native-demo").absolutePath
    environment("PYTHONHOME", pythonHomeForHost.get().asFile.absolutePath)

    // A zero exit status is not the same as a working upcall path. `PyRun_SimpleString` reports
    // failure for an exception raised inside the script, but a Python interpreter that never got
    // as far as running the script -- or a `print` lost to a missing flush -- exits 0 with the
    // marker absent. Since this task is the only thing standing between a missing reachability
    // registration and a shipped binary that dies on first call, it has to assert on the marker
    // itself rather than on the exit code.
    val captured = ByteArrayOutputStream()
    standardOutput = TeeOutputStream(System.out, captured)
    errorOutput = TeeOutputStream(System.err, captured)

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        // Two markers, because the binary now verifies two layers. `UPCALL_OK` is the raw boundary
        // (resolve a name, invoke a handle); `PROXY_OK` is the generated Python proxy module on top
        // of it -- a Kotlin class constructed from Python, a companion on a metaclass, and `await`
        // over a `suspend fun`. The second binds two more Panama stub shapes than the first, so a
        // reachability gap can pass `UPCALL_OK` and die immediately afterwards.
        val missing = listOf("PYTHON: UPCALL_OK", "PYTHON: PROXY_OK").filterNot(text::contains)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "upcall-native-demo exited successfully but never printed ${missing.joinToString(" or ") { "`$it`" }}. " +
                    "The Python -> Kotlin upcall path did not complete inside the native image. " +
                    "A `MissingForeignRegistrationError` here means the shipped " +
                    "reachability-metadata.json is missing a descriptor -- regenerate it with " +
                    "`:python-multiplatform:generateDesktopReachabilityMetadata` and check that " +
                    "the new signature's shape is derivable by GenerateReachabilityMetadata.\n" +
                    "--- output ---\n$text"
            )
        }
    }
}
