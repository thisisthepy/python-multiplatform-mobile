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
}

kotlin {
    /** Uncomment this block to enable WebAssembly support (currently not supported by Python Multiplatform)
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "sample"
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
    */
    
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
    }
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
val pythonHomeForHost = project(":python-multiplatform").layout.buildDirectory
    .dir("python-standalone/extracted/$hostPlatform/python")

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
}
