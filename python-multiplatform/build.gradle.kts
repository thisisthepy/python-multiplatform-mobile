import com.codingfeline.buildkonfig.compiler.FieldSpec
import java.net.URL
import java.io.FileOutputStream
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget.*
import org.jetbrains.kotlin.konan.target.linker


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id("com.codingfeline.buildkonfig").version("0.15.2")
    //id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}


val configuredPythonVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
val pythonFreeThreaded = project.findProperty("pythonFreeThreaded")?.toString()?.toBoolean() ?: false
val pbsRelease = project.findProperty("pythonBuildStandaloneRelease")?.toString() ?: "20260807"
val pythonAppleSupportBuild = project.findProperty("pythonAppleSupportBuild")?.toString() ?: "b10"

val pythonVersion = configuredPythonVersion
val libraryVersion = "$pythonVersion-alpha01"
version = libraryVersion

buildkonfig {
    packageName = project.name.lowercase().replace("-", ".")
    objectName = "BuildConfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "pythonVersion", pythonVersion)
        buildConfigField(FieldSpec.Type.STRING, "libraryVersion", libraryVersion)
    }
}

val libVersion = pythonVersion.split('.').subList(0, 2).joinToString(".")
// Mobile fallback: since python-build-standalone lacks iOS/Android, we use 3.13 for mobile
val mobileLibVersion = "3.13"

println("----------------------------------------------------------------------------------------")
println("                   Build Configuration for Python version $libVersion                   ")
println("----------------------------------------------------------------------------------------")
println()

val includePath = "src/nativeInterop/cinterop/include"
val licensePath = "src/nativeInterop/cinterop/license"
val libPath = "src/nativeInterop/cinterop/lib"
val libPathForDesktop = "$libPath/desktop"
val libPathForAndroid = "$libPath/android"
val libPathForIOS = "$libPath/ios/Python.xcframework"

val downloadDir = layout.buildDirectory.dir("python-standalone").get().asFile

val desktopTargets = mapOf(
    "macos-aarch64" to "aarch64-apple-darwin",
    "macos-x86_64" to "x86_64-apple-darwin",
    "linux-x86_64" to "x86_64-unknown-linux-gnu",
    "windows-x86_64" to "x86_64-pc-windows-msvc"
)

val downloadTasks = desktopTargets.map { (platform, pbsTarget) ->
    val flavour = if (pythonFreeThreaded) "freethreaded-install_only" else "install_only"
    val assetName = "cpython-$configuredPythonVersion+$pbsRelease-$pbsTarget-$flavour.tar.gz"
    val url = "https://github.com/astral-sh/python-build-standalone/releases/download/$pbsRelease/$assetName"
    val archive = file("$downloadDir/$assetName")
    val extractDir = file("$downloadDir/extracted/$platform")

    val taskName = "downloadPython_${platform.replace("-", "_")}"
    tasks.register(taskName) {
        inputs.property("url", url)
        outputs.dir(extractDir)
        
        doLast {
            if (!archive.exists()) {
                println("Downloading $url")
                archive.parentFile.mkdirs()
                URL(url).openStream().use { input ->
                    FileOutputStream(archive).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val sha256Url = "https://github.com/astral-sh/python-build-standalone/releases/download/$pbsRelease/SHA256SUMS"
            val shaFile = file("$downloadDir/SHA256SUMS-$pbsRelease")
            if (!shaFile.exists()) {
                URL(sha256Url).openStream().use { input ->
                    FileOutputStream(shaFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val sha256sums = shaFile.readText()
            val expectedHash = sha256sums.lines().find { it.endsWith(assetName) }?.substringBefore(" ")
                ?: throw GradleException("Checksum for $assetName not found in SHA256SUMS")
            
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use {
                val buffer = ByteArray(8192)
                var bytesRead = it.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = it.read(buffer)
                }
            }
            val actualHash = digest.digest().joinToString("") { String.format("%02x", it) }
                
            if (expectedHash != actualHash) {
                archive.delete()
                throw GradleException("Checksum mismatch for $assetName. Expected $expectedHash, got $actualHash")
            }
            
            val isEmpty = extractDir.list()?.isEmpty() ?: true
            if (isEmpty) {
                println("Extracting $archive to $extractDir")
                copy {
                    from(tarTree(resources.gzip(archive)))
                    into(extractDir)
                }
            }
        }
    }
}

val androidTargets = mapOf(
    "android-aarch64" to "aarch64",
    "android-x86_64" to "x86_64"
)

val androidDownloadTasks = androidTargets.map { (platform, arch) ->
    val url = "https://www.python.org/ftp/python/$configuredPythonVersion/python-$configuredPythonVersion-$arch-linux-android.tar.gz"
    val archive = file("$downloadDir/python-$configuredPythonVersion-$arch-linux-android.tar.gz")
    val extractDir = file("$downloadDir/extracted/$platform")
    
    val taskName = "downloadPython_${platform.replace("-", "_")}"
    tasks.register(taskName) {
        inputs.property("url", url)
        outputs.dir(extractDir)
        
        doLast {
            if (!archive.exists()) {
                println("Downloading $url")
                archive.parentFile.mkdirs()
                URL(url).openStream().use { input ->
                    FileOutputStream(archive).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // python.org provides sigstore signatures (.sig, .crt, .sigstore) but no plain SHA256SUMS.
            // Gradle-based verification is unreasonable without external tooling, so we skip checksum verification here.
            
            val isEmpty = extractDir.list()?.isEmpty() ?: true
            if (isEmpty) {
                println("Extracting $archive to $extractDir")
                copy {
                    from(tarTree(resources.gzip(archive)))
                    into(extractDir)
                }
            }
        }
    }
}

val iosUrl = "https://github.com/beeware/Python-Apple-support/releases/download/$libVersion-$pythonAppleSupportBuild/Python-$libVersion-iOS-support.$pythonAppleSupportBuild.tar.gz"
val iosArchive = file("$downloadDir/Python-$libVersion-iOS-support.$pythonAppleSupportBuild.tar.gz")
val iosExtractDir = file("$downloadDir/extracted/ios")

val downloadPython_ios = tasks.register("downloadPython_ios") {
    inputs.property("url", iosUrl)
    outputs.dir(iosExtractDir)
    
    doLast {
        if (!iosArchive.exists()) {
            println("Downloading $iosUrl")
            iosArchive.parentFile.mkdirs()
            URL(iosUrl).openStream().use { input ->
                FileOutputStream(iosArchive).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        // BeeWare does not provide any checksums or signatures for iOS artifacts, so verification is skipped.

        val isEmpty = iosExtractDir.list()?.isEmpty() ?: true
        if (isEmpty) {
            println("Extracting $iosArchive to $iosExtractDir")
            copy {
                from(tarTree(resources.gzip(iosArchive)))
                into(iosExtractDir)
            }
        }
    }
}

val downloadAllPythonBuilds by tasks.registering {
    dependsOn(downloadTasks)
    dependsOn(androidDownloadTasks)
    dependsOn(downloadPython_ios)
}

val androidBuildDir = "$projectDir/build/android"

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
        afterEvaluate {
            val abiList = listOf("arm64-v8a", "x86_64")
            val copyAndroidPythonBinaries by tasks.creating(Copy::class) {
                dependsOn(
                    tasks.named("linkAndroidNativeArm64"),
                    tasks.named("linkAndroidNativeX64")
                )
                into("$androidBuildDir/jniLibs/")
                abiList.forEach {
                    from("$libPathForAndroid/$it") {
                        include("libpython*.*.so")
                        include("lib*_python.so")
                        into(it)
                    }
                }
            }
            val copyAndroidPythonAssets by tasks.creating(Copy::class) {
                into("$androidBuildDir/assets/")
                abiList.forEach {
                    from(includePath) {
                        into("$it/include/python$mobileLibVersion")  // include
                    }
                }
                abiList.forEach {
                    from("$libPathForAndroid/$it/python$mobileLibVersion") {
                        exclude("config-$mobileLibVersion-aarch64-linux-android/")
                        exclude("config-$mobileLibVersion-x86_64-linux-android/")
                        into("$it/lib/python$mobileLibVersion")  // python stdlib
                    }
                }
            }
            tasks.whenTaskAdded {
                if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
                    dependsOn(copyAndroidPythonBinaries)
                }
                if (name.startsWith("package") && name.endsWith("Assets")) {
                    dependsOn(copyAndroidPythonAssets)
                }
            }

        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        tasks.withType<AbstractCopyTask> {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        tasks.withType<ProcessResources> {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        tasks.withType<Jar> {
            duplicatesStrategy = DuplicatesStrategy.WARN
            from(licensePath) {
                into("META-INF/LICENSE")
            }
            if (configuredPythonVersion == "3.13.0" && !pythonFreeThreaded) {
                from(libPathForDesktop) {
                    include("windows-*/*")
                    include("linux-*/*")
                    include("macos-*/*")
                    into("lib")
                }
            } else {
                dependsOn(downloadAllPythonBuilds)
                from("$downloadDir/extracted") {
                    include("macos-*/python/lib/libpython*.dylib")
                    include("linux-*/python/lib/libpython*.so*")
                    include("windows-*/python/python*.dll")
                    include("windows-*/python/vcruntime*.dll")
                    eachFile {
                        val parts = path.split("/")
                        val platform = parts[0]
                        val filename = parts.last()
                        path = "$platform/$filename"
                    }
                    into("lib")
                    includeEmptyDirs = false
                }
            }
        }
    }

    /* Supported platforms
     * https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
     */
    listOf(
        iosArm64(), iosSimulatorArm64(), iosX64(),
        androidNativeArm64(), androidNativeX64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val targetABI = when(konanTarget) {
                ANDROID_ARM64 -> "arm64-v8a"
                ANDROID_X64 -> "x86_64"
                IOS_ARM64 -> "ios-arm64"
                IOS_X64 -> "ios-arm64_x86_64-simulator"
                IOS_SIMULATOR_ARM64 -> "ios-arm64_x86_64-simulator"
                else -> throw RuntimeException("Unsupported ABI: $konanTarget")
            }
            val targetLibPath = when(konanTarget.family) {
                Family.ANDROID -> libPathForAndroid
                Family.IOS -> libPathForIOS
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }

            compilations.getByName("main").cinterops.create("python") {
                headers("$includePath/Python.h")
                packageName("python.native.ffi.bindings")
                includeDirs(includePath)
                if (konanTarget.family == Family.IOS) {
                    compilerOpts("-framework", "Python", "-F$projectDir/$targetLibPath/$targetABI", "-fno-common", "-fvisibility=hidden")
                }
            }

            binaries {
                if (konanTarget.family == Family.ANDROID) {
                    sharedLib("multiplatform_python$mobileLibVersion") {
                        linkerOpts.addAll(listOf("-L$projectDir/$targetLibPath/$targetABI/", "-lpython$mobileLibVersion"))

                        linkTaskProvider.configure {
                            val type = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
                            copy {
                                from(outputFile)
                                into(file("$androidBuildDir/$type/jniLibs/$targetABI/"))
                            }
                        }
                        afterEvaluate {
                            val preBuild by tasks.getting
                            preBuild.dependsOn(linkTaskProvider)
                        }
                    }
                } else if (konanTarget.family == Family.IOS) {
                    framework {
                        baseName = "PythonMultiplatform"

                        linkerOpts.addAll(listOf(
                            "-framework", "Python", "-F$projectDir/$targetLibPath/$targetABI", "-Objc"
                        ))
                    }

                    // The test executable is a separate binary from the framework above,
                    // so it needs its own linker flags to resolve the embedded CPython symbols.
                    // Python.framework has an @rpath-relative install name and is not copied
                    // next to the test binary, so an explicit -rpath is required for dyld to
                    // find it when the test runs on the simulator.
                    getTest(NativeBuildType.DEBUG).linkerOpts.addAll(listOf(
                        "-framework", "Python", "-F$projectDir/$targetLibPath/$targetABI",
                        "-rpath", "$projectDir/$targetLibPath/$targetABI"
                    ))
                }
            }
        }
    }
    
    sourceSets {
        val jvmMain by creating
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val desktopMain by getting {
            resources.srcDirs("src/desktopMain/resources")
        }
        val androidMain by getting
        jvmMain.dependsOn(commonMain)
        desktopMain.dependsOn(jvmMain)
        androidMain.dependsOn(jvmMain)

        val nativeMain by creating
        nativeMain.dependsOn(commonMain)
        try {  // when not on macOS
            val iosMain by creating  // This will fail on macOS
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            iosX64Main.dependsOn(iosMain)
            iosArm64Main.dependsOn(iosMain)
            iosSimulatorArm64Main.dependsOn(iosMain)
        } finally {
            val iosMain by getting
            iosMain.dependsOn(nativeMain)
        }

        val artMain by creating {
            kotlin.srcDir("src/artMain/kotlin")
        }
        val androidNativeX64Main by getting
        val androidNativeArm64Main by getting
        artMain.dependsOn(nativeMain)
        androidNativeX64Main.dependsOn(artMain)
        androidNativeArm64Main.dependsOn(artMain)
    }
}

/**
 * The iOS `Python.framework` ships only the interpreter binary and headers — it carries no
 * standard library. `Py_Initialize()` therefore aborts the process with
 * "Fatal Python error: Failed to import encodings module" unless PYTHONHOME points at a
 * prefix containing `lib/python3.13`.
 *
 * The simulator distribution archive does contain that stdlib, so unpack it into the build
 * directory and hand its location to the test binary.
 */
val extractIosSimulatorStdlib by tasks.registering(Copy::class) {
    val archive = rootProject.file("binary/arm64-iphonesimulator.zip")
    onlyIf { archive.exists() }
    from(zipTree(archive)) {
        include("arm64-iphonesimulator/lib/python3.13/**")
        eachFile { path = path.removePrefix("arm64-iphonesimulator/") }
    }
    into(layout.buildDirectory.dir("python-stdlib/ios-simulator"))
    includeEmptyDirs = false
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    dependsOn(extractIosSimulatorStdlib)
    val pythonHome = layout.buildDirectory.dir("python-stdlib/ios-simulator").get().asFile.absolutePath
    // simctl only forwards variables into the spawned process when they carry this prefix.
    environment("SIMCTL_CHILD_PYTHONHOME", pythonHome)
    environment("PYTHONHOME", pythonHome)
}

android {
    namespace = "python.multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].assets.srcDirs("src/androidMain/assets", "$androidBuildDir/assets")
    sourceSets["debug"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/debug/jniLibs")
    sourceSets["release"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/release/jniLibs")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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

publishing {
    publications.withType<MavenPublication>().configureEach {
        //artifact(javadocJar)
        groupId = groupId
        artifactId = artifactId
        version = version

        pom {
            name.set("python-multiplatform")
            description.set("A multiplatform solution to use Python with Kotlin interoperably.")
            url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            licenses {
                license {
                    //name.set("The Apache License, Version 2.0")
                    //url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thisisthepy")
                    name.set("thisisthepy")
                    email.set("thisisthepy@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:github.com/thisisthepy/python-multiplatform-mobile.git")
                developerConnection.set("scm:git:ssh://github.com/thisisthepy/python-multiplatform-mobile.git")
                url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            }
        }
    }
}

//signing {
//    useInMemoryPgpKeys(
//        rootProject.extra["signing_key_id"] as String,
//        rootProject.extra["signing_secret_key"] as String,
//        rootProject.extra["signing_password"] as String
//    )
//    sign(publishing.publications)
//}


fun downloadPythonBuilds() {
    // Moved to task `downloadAllPythonBuilds`
}
