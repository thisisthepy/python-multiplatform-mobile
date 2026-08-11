import com.codingfeline.buildkonfig.compiler.FieldSpec
import java.net.URL
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.io.File
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
println("----------------------------------------------------------------------------------------")
println("                   Build Configuration for Python version $libVersion                   ")
println("----------------------------------------------------------------------------------------")
println()

val licensePath = "src/nativeInterop/cinterop/license"
val libPath = "src/nativeInterop/cinterop/lib"
val libPathForDesktop = "$libPath/desktop"

val downloadDir = layout.buildDirectory.dir("python-standalone").get().asFile

val checksumsFile = rootProject.file("python-checksums.properties")
val pythonArchiveKeys = mutableMapOf<String, File>()

fun verifyChecksum(key: String, archive: File) {
    if (!checksumsFile.exists()) {
        throw GradleException("Checksum lockfile python-checksums.properties not found! Run updatePythonChecksums to generate it.")
    }
    val props = Properties()
    checksumsFile.inputStream().use { props.load(it) }
    
    val expectedHash = props.getProperty(key)
    if (expectedHash == null) {
        throw GradleException("Missing checksum for $key in python-checksums.properties. Run updatePythonChecksums task to accept the current downloaded archive as trusted.")
    }
        
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
        throw GradleException(
            "Checksum mismatch for $archive (key: $key) in python-checksums.properties.\n" +
                "Expected: $expectedHash\n" +
                "Actual:   $actualHash"
        )
    }
}

tasks.register("updatePythonChecksums") {
    doLast {
        println("Updating python-checksums.properties. This accepts currently downloaded archives as trusted.")
        val props = Properties()
        if (checksumsFile.exists()) {
            checksumsFile.inputStream().use { props.load(it) }
        }
        var updated = false
        val digest = MessageDigest.getInstance("SHA-256")
        
        pythonArchiveKeys.forEach { (key, archive) ->
            if (archive.exists()) {
                digest.reset()
                archive.inputStream().use {
                    val buffer = ByteArray(8192)
                    var bytesRead = it.read(buffer)
                    while (bytesRead != -1) {
                        digest.update(buffer, 0, bytesRead)
                        bytesRead = it.read(buffer)
                    }
                }
                val hash = digest.digest().joinToString("") { String.format("%02x", it) }
                props.setProperty(key, hash)
                updated = true
                println("Computed $key = $hash")
            } else {
                println("Skipping $key: archive not downloaded yet at ${archive.name}")
            }
        }
        
        if (updated) {
            checksumsFile.bufferedWriter().use { writer ->
                writer.write("# Python Multiplatform Checksums\\n")
                props.stringPropertyNames().sorted().forEach { k ->
                    writer.write("$k=${props.getProperty(k)}\\n")
                }
            }
        }
    }
}

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
    val lockKey = "$platform-$configuredPythonVersion-$pbsRelease" + (if (pythonFreeThreaded) "-freethreaded" else "")
    pythonArchiveKeys[lockKey] = archive

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
            val astralExpectedHash = sha256sums.lines().find { it.endsWith(assetName) }?.substringBefore(" ")
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
            val astralActualHash = digest.digest().joinToString("") { String.format("%02x", it) }
                
            if (astralExpectedHash != astralActualHash) {
                archive.delete()
                throw GradleException("Checksum mismatch for $assetName. Expected $astralExpectedHash, got $astralActualHash")
            }
            
            verifyChecksum(lockKey, archive)
            
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
    val lockKey = "$platform-$configuredPythonVersion"
    pythonArchiveKeys[lockKey] = archive
    
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
            // Full Sigstore verification is unreasonable in pure Gradle, but we verify against our local lockfile.
            verifyChecksum(lockKey, archive)
            
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
val iosLockKey = "ios-$libVersion-$pythonAppleSupportBuild"
pythonArchiveKeys[iosLockKey] = iosArchive

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
        
        // BeeWare does not provide any checksums or signatures for iOS artifacts, but we verify against our local lockfile.
        verifyChecksum(iosLockKey, iosArchive)

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
                    tasks.named("linkAndroidNativeX64"),
                    downloadAllPythonBuilds
                )
                into("$androidBuildDir/jniLibs/")
                abiList.forEach { abi ->
                    val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
                    from("$downloadDir/extracted/android-$arch/prefix/lib") {
                        include("libpython*.so")
                        include("lib*_python.so")
                        into(abi)
                    }
                }
            }
            val copyAndroidPythonAssets by tasks.creating(Copy::class) {
                dependsOn(downloadAllPythonBuilds)
                into("$androidBuildDir/assets/")
                abiList.forEach { abi ->
                    val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
                    from("$downloadDir/extracted/android-$arch/prefix/include/python$libVersion") {
                        into("$abi/include/python$libVersion")
                    }
                    from("$downloadDir/extracted/android-$arch/prefix/lib/python$libVersion") {
                        exclude("config-$libVersion-$arch-linux-android/")
                        into("$abi/lib/python$libVersion")
                    }
                }
            }
            tasks.whenTaskAdded {
                if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
                    dependsOn(copyAndroidPythonBinaries)
                }
                if (name.endsWith("Assets")) {
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
                        // `path` here is already destination-relative -- `into("lib")` below is
                        // applied before eachFile sees the file, so the leading segment is "lib",
                        // not the platform directory from the `from(...)` source tree. Indexing
                        // parts[0] silently dropped the platform and collapsed every platform's
                        // library onto the same jar entry (DuplicatesStrategy.WARN then kept only
                        // the last one copied, breaking every platform but that one).
                        val parts = path.split("/")
                        val platform = parts[1]
                        val filename = parts.last()
                        path = "lib/$platform/$filename"
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
            val targetExtractDir = when(konanTarget.family) {
                Family.ANDROID -> "$downloadDir/extracted/android-${if (targetABI == "arm64-v8a") "aarch64" else "x86_64"}/prefix"
                Family.IOS -> "$downloadDir/extracted/ios/Python.xcframework"
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }
            val targetIncludePath = when(konanTarget.family) {
                Family.ANDROID -> "$targetExtractDir/include/python$libVersion"
                Family.IOS -> "$targetExtractDir/$targetABI/Python.framework/Headers"
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }

            compilations.getByName("main").cinterops.create("python") {
                headers("$targetIncludePath/Python.h")
                packageName("python.native.ffi.bindings")
                includeDirs(targetIncludePath)
                if (konanTarget.family == Family.IOS) {
                    compilerOpts("-framework", "Python", "-F$targetExtractDir/$targetABI", "-fno-common", "-fvisibility=hidden")
                }
            }

            if (konanTarget.family == Family.ANDROID) {
                compilations.getByName("main").cinterops.create("jni_onload") {
                    defFile("src/artMain/cinterop/jni_onload.def")
                }
            }

            binaries {
                if (konanTarget.family == Family.ANDROID) {
                    sharedLib("multiplatform_python$libVersion") {
                        linkerOpts.addAll(listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-u", "JNI_OnLoad"))

                        linkTaskProvider.configure {
                            val type = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
                            val dest = file("$androidBuildDir/$type/jniLibs/$targetABI/")
                            // doLast, not the configure block itself: a bare copy {} here runs at
                            // CONFIGURATION time, so it stages whatever the previous build left in
                            // outputFile. That is why a changed .def or source produced an APK with
                            // a stale library and an UnsatisfiedLinkError that looked like a code
                            // bug -- the fix had to be built twice for the second run to pick it up.
                            doLast {
                                copy {
                                    from(outputFile)
                                    into(dest)
                                }
                            }
                        }
                        afterEvaluate {
                            val preBuild by tasks.getting
                            preBuild.dependsOn(linkTaskProvider)
                        }
                    }
                    getTest(NativeBuildType.DEBUG).linkerOpts.addAll(listOf(
                        "-L$targetExtractDir/lib/", 
                        "-lpython$libVersion",
                        "-Wl,--allow-shlib-undefined",
                        "-u", "JNI_OnLoad"
                    ))
                } else if (konanTarget.family == Family.IOS) {
                    framework {
                        baseName = "PythonMultiplatform"

                        linkerOpts.addAll(listOf(
                            "-framework", "Python", "-F$targetExtractDir/$targetABI", "-Objc"
                        ))
                    }

                    // The test executable is a separate binary from the framework above,
                    // so it needs its own linker flags to resolve the embedded CPython symbols.
                    // Python.framework has an @rpath-relative install name and is not copied
                    // next to the test binary, so an explicit -rpath is required for dyld to
                    // find it when the test runs on the simulator.
                    getTest(NativeBuildType.DEBUG).linkerOpts.addAll(listOf(
                        "-framework", "Python", "-F$targetExtractDir/$targetABI",
                        "-rpath", "$targetExtractDir/$targetABI"
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
        val jvmTest by creating
        jvmTest.dependsOn(commonTest)
        val desktopMain by getting {
            resources.srcDirs("src/desktopMain/resources")
        }
        val desktopTest by getting
        desktopTest.dependsOn(jvmTest)
        val androidMain by getting
        jvmMain.dependsOn(commonMain)
        desktopMain.dependsOn(jvmMain)
        androidMain.dependsOn(jvmMain)

        // `commonTest` holds the entire object-model suite, and it used to run only on desktop and
        // the iOS simulator -- yet Android is the platform where that model was most recently
        // found broken. It runs here as an *instrumented* test: `androidUnitTest` executes on a
        // host JVM that cannot load the arm64/x86_64 `.so`, so the interpreter is unreachable
        // there. `jvmTest` (not `commonTest` directly) keeps the test hierarchy the mirror of the
        // main one -- desktopTest -> jvmTest -> commonTest, androidInstrumentedTest -> jvmTest.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.junit)
                implementation("androidx.test:runner:1.6.2")
                // On the JVM, `kotlin.test.Test` and the `assert*` functions are `expect`
                // declarations; kotlin-test-junit supplies the actuals, mapping `kotlin.test.Test`
                // onto `org.junit.Test` by typealias. Without it commonTest does not compile here,
                // and -- because AndroidJUnitRunner discovers tests by scanning the dex for
                // `org.junit.Test` -- it is also what makes the runner see these classes at all.
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
        // KGP warns here ("Source Set groups can't depend on 'jvmTest' together as they belong to
        // different Kotlin Source Set Trees") because androidInstrumentedTest lives in the
        // `instrumentedTest` tree while desktopTest lives in `test`. There is no way to share
        // commonTest with an instrumented compilation without crossing that line, and the warning
        // is exactly that: both compilations build the shared sources independently, which is what
        // we want. It is why `forceGC()` needs an `actual` in androidInstrumentedTest as well as
        // in androidUnitTest -- they are two separate compilations of the same target.
        androidInstrumentedTest.dependsOn(jvmTest)

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
    dependsOn(downloadAllPythonBuilds)
    from("$downloadDir/extracted/ios/Python.xcframework/lib/python$libVersion") {
        into("lib/python$libVersion")
    }
    from("$downloadDir/extracted/ios/Python.xcframework/ios-arm64_x86_64-simulator/lib-arm64/python$libVersion") {
        into("lib/python$libVersion")
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
    sourceSets["androidTest"].assets.srcDirs("$androidBuildDir/assets")
    sourceSets["debug"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/debug/jniLibs")
    sourceSets["release"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/release/jniLibs")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        // Instrumented tests are the only way to exercise the Android JNI path at all: the JVM
        // unit-test JVM cannot load the arm64 .so, and every other verification target only
        // compiles rather than runs.
        //
        // The runner is a subclass rather than the stock one because `commonTest` knows nothing
        // about Android: its fixture just calls `Python3.initialize()`, which aborts the *process*
        // ("Failed to import encodings module") unless the stdlib has been unpacked out of the
        // APK assets and PYTHONHOME points at it first. The subclass does that once, before any
        // test class is loaded.
        testInstrumentationRunner = "python.multiplatform.PythonInstrumentationRunner"
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    dependsOn(downloadAllPythonBuilds)
}

val copyDesktopPythonBinariesForTests by tasks.registering(Copy::class) {
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
            path = "lib/$platform/$filename"
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("desktop-test-binaries"))
}

tasks.named<Test>("desktopTest") {
    dependsOn(copyDesktopPythonBinariesForTests)
    classpath += files(layout.buildDirectory.dir("desktop-test-binaries"))
    
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
    
    val platform = if (System.getProperty("os.name").contains("Mac")) {
        if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
    } else if (System.getProperty("os.name").contains("Windows")) {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
    
    val pythonHome = layout.buildDirectory.dir("python-standalone/extracted/$platform/python").get().asFile.absolutePath
    environment("PYTHONHOME", pythonHome)
}
