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

// =================================================================================================
// GraalVM reachability metadata for the desktop (Panama/FFM) surface.
//
// This file used to be a checked-in static resource. Under `native-image`'s closed world every
// `FunctionDescriptor` that `Linker.downcallHandle`/`Linker.upcallStub` will ever see has to be
// declared up front; anything missing does not fail the build, it fails at *runtime* with
// `MissingForeignRegistrationError`. A checked-in list therefore rots silently: adding one
// `find("PyX", Integer.TYPE, P, P, P)` line to `bindings.kt` with a shape nothing else uses
// produces a binary that builds clean and dies on first call. It had already rotted: the
// checked-in list carried one upcall descriptor while `Panama` builds three, so `ProxyTypeFactory`
// would have died in any image that reached it. See ROADMAP §7.
//
// So the shapes are derived from the sources that define them:
//
//   * `bindings.kt`               -- one `find(symbol, returnType, params...)` per CPython entry
//                                    point; `Panama.findSymbol` turns each into a descriptor.
//   * `ShapeDowncalls.desktop.kt` -- the 14-entry shared shape vocabulary, declared as
//                                    `Panama.unboundDowncallHandle(intArgs, floatArgs, kind)`.
//   * `Panama.kt`                 -- `findSym("malloc"/"free", ...)` plus the upcall stub
//                                    builders, whose descriptors are spelled out in
//                                    `// @UpcallShape(...)` markers next to each builder.
//
// Every one of those is a *source-of-truth* read, and each parse is bounded by a count assertion
// (see `check(...)` calls below): a declaration form the parser does not recognise fails the
// build rather than silently dropping a descriptor. `ReachabilityMetadataTest` then re-checks the
// generated file against the `MethodHandle.type()`s the code actually produces at runtime, which
// is a verification path that shares no code with this parser.
// =================================================================================================

abstract class GenerateReachabilityMetadata : DefaultTask() {

    @get:InputFile
    abstract val bindingsSource: RegularFileProperty

    @get:InputFile
    abstract val shapeVocabularySource: RegularFileProperty

    @get:InputFile
    abstract val panamaSource: RegularFileProperty

    /** Hand-authored entries (resources, reflection) that no source file can imply. */
    @get:InputFile
    abstract val baseMetadata: RegularFileProperty

    @get:Input
    abstract val relativePath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** A descriptor: FFM carrier names, e.g. `long (long, int)`. */
    private data class Descriptor(val returnType: String, val parameterTypes: List<String>)

    @TaskAction
    fun generate() {
        val downcalls = LinkedHashSet<Descriptor>()
        downcalls += parseBindings(bindingsSource.get().asFile.readText())
        downcalls += parseShapeVocabulary(shapeVocabularySource.get().asFile.readText())

        val panamaText = panamaSource.get().asFile.readText()
        downcalls += parsePanamaDowncalls(panamaText)
        val upcalls = parseUpcallShapes(panamaText)

        val json = buildString {
            append("{\n")
            append("  \"foreign\": {\n")
            append("    \"downcalls\": [\n")
            append(renderDescriptors(downcalls.sortedWith(descriptorOrder)))
            append("    ],\n")
            append("    \"upcalls\": [\n")
            append(renderDescriptors(upcalls.sortedWith(descriptorOrder)))
            append("    ]\n")
            append("  }")
            for ((key, value) in readBaseEntries()) {
                append(",\n  \"").append(key).append("\": ")
                append(indentContinuation(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(value))))
            }
            append("\n}\n")
        }

        val target = outputDirectory.get().asFile.resolve(relativePath.get())
        target.parentFile.mkdirs()
        target.writeText(json)
        logger.lifecycle(
            "Reachability metadata: ${downcalls.size} downcall + ${upcalls.size} upcall descriptors -> $target"
        )
    }

    private val descriptorOrder =
        compareBy<Descriptor>({ it.parameterTypes.size }, { it.returnType }, { it.parameterTypes.joinToString(",") })

    private fun renderDescriptors(items: List<Descriptor>): String = items.joinToString(",\n", postfix = "\n") {
        val params = it.parameterTypes.joinToString(", ") { p -> "\"$p\"" }
        "      { \"returnType\": \"${it.returnType}\", \"parameterTypes\": [$params] }"
    }

    /** JsonOutput indents by 4; the rest of this document uses 2, nested one level under the root. */
    private fun indentContinuation(pretty: String): String = pretty.lines()
        .joinToString("\n") { line ->
            if (line.isEmpty()) line
            else " ".repeat(2 + line.takeWhile { it == ' ' }.length / 2) + line.trimStart()
        }
        .trimStart()

    @Suppress("UNCHECKED_CAST")
    private fun readBaseEntries(): Map<String, Any?> {
        val parsed = groovy.json.JsonSlurper().parse(baseMetadata.get().asFile) as Map<String, Any?>
        // `_`-prefixed keys are documentation for the humans editing the base file; native-image
        // validates the document against a schema, so they must not reach the generated output.
        return parsed.filterKeys { !it.startsWith("_") }
    }

    // ---- bindings.kt -----------------------------------------------------------------------

    /**
     * `P` is `Panama.POINTER_TYPE` (`Long.TYPE`), and `LongLong`/`Double` are the `java.lang`
     * aliases `bindings.kt` imports. A token outside this table is a new carrier type whose FFM
     * layout this task cannot guess -- failing is the point.
     */
    private val javaTypeTokens = mapOf(
        "P" to "long",
        "Void.TYPE" to "void",
        "Integer.TYPE" to "int",
        "LongLong.TYPE" to "long",
        "Long.TYPE" to "long",
        "Double.TYPE" to "double",
        "Float.TYPE" to "float",
        "Short.TYPE" to "short",
        "Byte.TYPE" to "byte",
        "Character.TYPE" to "char",
        "Boolean.TYPE" to "boolean",
        "POINTER_TYPE" to "long",
    )

    private fun carrier(token: String, where: String): String = javaTypeTokens[token]
        ?: throw GradleException(
            "Unknown carrier type `$token` in $where. Add it to GenerateReachabilityMetadata's " +
                "javaTypeTokens table (with the FFM layout name native-image expects) before using it."
        )

    private fun parseBindings(text: String): Set<Descriptor> {
        val declarations = Regex("""val\s+\w+Handle\s*:\s*MethodHandle""").findAll(text).count()
        val callSites = Regex("""=\s*find\(""").findAll(text).count()
        val matches = Regex("""=\s*find\("(\w+)"((?:\s*,\s*[\w.]+)*)\s*\)""").findAll(text).toList()

        check(declarations == callSites) {
            "bindings.kt declares $declarations MethodHandle fields but has $callSites `= find(` " +
                "assignments; every declared handle must be initialised by a `find(...)` call for " +
                "its descriptor to be derivable."
        }
        check(matches.size == callSites) {
            "bindings.kt has $callSites `= find(` assignments but only ${matches.size} of them " +
                "match the expected `find(\"symbol\", ReturnType, ParamType...)` form. The " +
                "unmatched ones would be missing from the reachability metadata."
        }

        return matches.mapTo(LinkedHashSet()) { match ->
            val symbol = match.groupValues[1]
            val tokens = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val types = tokens.map { carrier(it, "bindings.kt find(\"$symbol\", ...)") }
            Descriptor(types.first(), types.drop(1))
        }
    }

    // ---- ShapeDowncalls.desktop.kt ---------------------------------------------------------

    private fun parseShapeVocabulary(text: String): Set<Descriptor> {
        val occurrences = Regex("""Panama\.unboundDowncallHandle\(""").findAll(text).count()
        val matches = Regex(
            """Panama\.unboundDowncallHandle\(\s*(\d+)\s*,\s*(\d+)\s*,\s*ReturnKind\.(\w+)\s*\)"""
        ).findAll(text).toList()

        check(matches.size == occurrences) {
            "ShapeDowncalls.desktop.kt has $occurrences `Panama.unboundDowncallHandle(` calls but " +
                "only ${matches.size} match the literal `(intArgs, floatArgs, ReturnKind.X)` form."
        }

        return matches.mapTo(LinkedHashSet()) { match ->
            val intArgs = match.groupValues[1].toInt()
            val floatArgs = match.groupValues[2].toInt()
            val returnType = when (val kind = match.groupValues[3]) {
                "VOID" -> "void"
                "LONG" -> "long"
                "DOUBLE" -> "double"
                else -> throw GradleException("Unknown ReturnKind.$kind in ShapeDowncalls.desktop.kt")
            }
            // `buildShape` lays out intArgs JAVA_LONGs followed by floatArgs JAVA_DOUBLEs. The
            // leading target-address argument is not part of the descriptor: it is bound by
            // `filterArguments`, so it exists on the MethodHandle but not on the FunctionDescriptor.
            Descriptor(returnType, List(intArgs) { "long" } + List(floatArgs) { "double" })
        }
    }

    // ---- Panama.kt -------------------------------------------------------------------------

    private fun parsePanamaDowncalls(text: String): Set<Descriptor> =
        Regex("""findSym\("(\w+)"\s*,\s*([\w.]+)\s*,\s*arrayOf\(([^)]*)\)\s*\)""")
            .findAll(text)
            .mapTo(LinkedHashSet()) { match ->
                val symbol = match.groupValues[1]
                val returnType = carrier(match.groupValues[2], "Panama.kt findSym(\"$symbol\", ...)")
                val params = match.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { carrier(it, "Panama.kt findSym(\"$symbol\", ...)") }
                Descriptor(returnType, params)
            }

    /**
     * Upcall descriptors are assembled reflectively inside the stub builders, so there is no
     * literal to read. Each builder therefore carries a `// @UpcallShape(...)` marker, and a
     * builder without one fails the build -- that is what stops a fourth stub shape from being
     * added without reaching the metadata.
     */
    private fun parseUpcallShapes(text: String): Set<Descriptor> {
        val lines = text.lines()
        val markerRegex = Regex(
            """//\s*@UpcallShape\(\s*returnType\s*=\s*"(\w+)"\s*,\s*parameterTypes\s*=\s*\[([^\]]*)]\s*\)"""
        )
        val builderRegex = Regex("""val\s+buildUpcallStub\w*\s*:""")

        val result = LinkedHashSet<Descriptor>()
        lines.forEachIndexed { index, line ->
            if (!builderRegex.containsMatchIn(line)) return@forEachIndexed
            val marker = (maxOf(0, index - 4) until index)
                .reversed()
                .firstNotNullOfOrNull { markerRegex.find(lines[it]) }
                ?: throw GradleException(
                    "Panama.kt:${index + 1} declares an upcall stub builder with no " +
                        "`// @UpcallShape(returnType = \"...\", parameterTypes = [...])` marker " +
                        "above it. Without the marker its FunctionDescriptor cannot reach the " +
                        "reachability metadata, and native-image would fail at runtime with " +
                        "MissingForeignRegistrationError."
                )
            val params = marker.groupValues[2].split(',')
                .map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            result += Descriptor(marker.groupValues[1], params)
        }
        check(result.isNotEmpty()) { "Panama.kt declares no upcall stub builders -- parser is out of date." }
        return result
    }
}

val nativeImageMetadataPath =
    "META-INF/native-image/org.thisisthepy/python-multiplatform/reachability-metadata.json"

val generateDesktopReachabilityMetadata by tasks.registering(GenerateReachabilityMetadata::class) {
    group = "native"
    description = "Derives the GraalVM foreign (FFM) reachability metadata from the desktop FFI sources"
    bindingsSource.set(layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/bindings.kt"))
    shapeVocabularySource.set(
        layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/ShapeDowncalls.desktop.kt")
    )
    panamaSource.set(layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/Panama.kt"))
    baseMetadata.set(layout.projectDirectory.file("native-image/reachability-metadata.base.json"))
    relativePath.set(nativeImageMetadataPath)
    outputDirectory.set(layout.buildDirectory.dir("generated/native-image-metadata/desktop"))
}

kotlin {
    // ROADMAP §10. The target is a leaf directly under `commonMain` -- deliberately not under an
    // intermediate source set, because `EmbedAPI.kt`'s `expect inline fun`s crash the compiler when
    // combined with an intermediate `expect`/`actual` (see docs/architecture.md).
    //
    // `nodejs()` rather than `browser()`: the tests have to drive a real CPython Emscripten build,
    // and Node can load `python.wasm` off the filesystem. There is no webpack step to fight.
    //
    // What makes this target able to reach CPython at all is that Kotlin 2.4.20-Beta2 *imports* its
    // linear memory (`intrinsics.memory`) instead of defining one. Emscripten's memory is handed in
    // there, so a `PyObject*` is an address Kotlin can dereference directly. See docs/wasm-design.md
    // and `wasm-experiment/`.
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

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
            tasks.configureEach {
                if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
                    dependsOn(copyAndroidPythonBinaries)
                }
                if (name != "copyAndroidPythonAssets" && name.endsWith("Assets")) {
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
        val hostPlatform = when {
            System.getProperty("os.name").contains("Mac") ->
                if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
            System.getProperty("os.name").contains("Windows") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        tasks.withType<ProcessResources> {
            duplicatesStrategy = DuplicatesStrategy.WARN
            inputs.property("hostPlatform", hostPlatform)
            // The metadata carries no `${hostPlatform}` placeholder today: the CPython library is
            // no longer registered as an image resource (see native-image/reachability-metadata.
            // base.json for why, and manager.kt for the filesystem sidecar that replaced it). The
            // substitution is kept because any platform-scoped `resources` glob added back needs
            // it -- a native image bundles one platform, and the jar carries all four.
            filesMatching("**/reachability-metadata.json") {
                filter { line ->
                    line.replace("\${hostPlatform}", hostPlatform)
                }
            }
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
            // `reachability-metadata.json` is generated, not checked in -- see
            // GenerateReachabilityMetadata above. Wiring it as a resource srcDir (through the
            // task's own output provider, so the dependency is inferred) is what puts it on the
            // native-image classpath at META-INF/native-image/...
            resources.srcDir(generateDesktopReachabilityMetadata.flatMap { it.outputDirectory })
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

        // wasmJs sits directly under commonMain, as a sibling of jvmMain and nativeMain. It shares
        // nothing with either: there is no JNI, no Panama and no cinterop here, only `@WasmImport`
        // against CPython's own wasm exports.
        val wasmJsMain by getting
        wasmJsMain.dependsOn(commonMain)
        val wasmJsTest by getting
        wasmJsTest.dependsOn(commonTest)

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

// =================================================================================================
// ROADMAP §10 -- staging CPython next to the wasmJs test bundle.
//
// Three things have to be true before a `wasmJs` test can reach the interpreter, and none of them
// is something the Kotlin/Wasm toolchain does on its own:
//
//   1. `python.mjs`/`python.wasm` must sit next to the compiled Kotlin, because `cpython.mjs`
//      (src/wasmJsMain/resources) imports the glue by relative specifier.
//   2. `cpython.mjs` needs the *real* build directory, not the staging copy: Emscripten derives
//      `sys.prefix` from `thisProgram` and mounts the host filesystem through NODEFS, so the stdlib
//      is found at its original path. That path is handed over in a generated `cpython-config.mjs`
//      rather than an environment variable, because `KotlinJsTest` does not forward one.
//   3. The generated import object must hand Kotlin's `intrinsics.memory` import Emscripten's
//      memory instead of the placeholder the compiler emits.
//
// (3) is the whole integration, and it is one expression. Kotlin 2.4.20-Beta2 *imports* its linear
// memory declared `min=0, max=none`; a wasm memory import accepts any memory whose limits sit
// inside its own, so an unbounded import accepts Emscripten's bounded one. Emscripten is built
// exactly as it would be alone -- no `-sIMPORTED_MEMORY`, no binary patching. Kotlin adapts.
// Ordering comes free from ES modules: the import-object module imports `cpython.mjs`, which has a
// top-level `await`, so Emscripten is fully instantiated before the import object is built.
//
// The CPython build itself is not produced here. It is `/Volumes/macMini/wasm-build/
// build-cpython-abi.sh` -- CPython 3.14.2 matched to `pyemscripten_2026_0` (PEP 783), relinked with
// `wasmExports,wasmMemory` added to `-sEXPORTED_RUNTIME_METHODS`. Without those two the 8287 wasm
// exports are present in the binary but unreachable from JS, so there is nothing to hand
// `@WasmImport`; neither appears in PEP 783's ABI-sensitive list.
// =================================================================================================

val wasmPythonDir: String = (project.findProperty("wasmPythonDir")?.toString()
    ?: System.getenv("PMP_PYTHON_DIR")
    ?: "/Volumes/macMini/wasm-build/cpython314-abi/cross-build/wasm32-emscripten/build/python")

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (!name.startsWith("wasmJs")) return@configureEach

    val pythonDir = file(wasmPythonDir)
    // The npm project the Kotlin/Wasm node runner actually executes out of, which is under the
    // ROOT build directory rather than this module's.
    val stagingDir = rootProject.layout.buildDirectory
        .dir("wasm/packages/${rootProject.name}-${project.name}-test/kotlin")

    onlyIf {
        val present = pythonDir.resolve("python.mjs").exists()
        if (!present) {
            logger.lifecycle(
                "SKIPPING $name -- no CPython Emscripten build at $pythonDir. " +
                    "Build one with /Volumes/macMini/wasm-build/build-cpython-abi.sh, or point " +
                    "-PwasmPythonDir / PMP_PYTHON_DIR at an existing one."
            )
        }
        present
    }

    doFirst {
        val dir = stagingDir.get().asFile
        copy {
            from(pythonDir) { include("python.mjs", "python.wasm") }
            into(dir)
        }
        dir.resolve("cpython-config.mjs").writeText(
            "// Generated by build.gradle.kts. The interpreter's real build directory: Emscripten\n" +
                "// derives sys.prefix from `thisProgram` and reaches the stdlib through NODEFS, so\n" +
                "// this must be the original path and not the staging copy next to it.\n" +
                "export const PYTHON_DIR = ${groovy.json.JsonOutput.toJson(pythonDir.absolutePath)};\n"
        )

        val importObject = dir.listFiles()?.firstOrNull { it.name.endsWith(".import-object.mjs") }
            ?: throw GradleException("No *.import-object.mjs in $dir -- the Kotlin/Wasm output layout changed.")
        val text = importObject.readText()

        // The compiler names the namespace import after a base64 of the module specifier, so it is
        // read out rather than assumed.
        val ns = Regex("""import \* as (\w+) from ['"]\./cpython\.mjs['"];""").find(text)?.groupValues?.get(1)
            ?: throw GradleException(
                "${importObject.name} does not import ./cpython.mjs. That import is emitted because " +
                    "bindings.kt declares @WasmImport against it; if it is gone, the binding module " +
                    "changed."
            )
        val placeholder = Regex("""memory:\s*new WebAssembly\.Memory\(\{[^}]*}\)""")
        if (!placeholder.containsMatchIn(text)) {
            // Already patched (the task is not up-to-date-aware) or the glue shape changed. Only
            // the latter is a problem, and it shows up as a LinkError on the memory import.
            if (!text.contains("memory: $ns.wasmMemory")) {
                throw GradleException(
                    "${importObject.name} has no `intrinsics.memory` placeholder to replace. " +
                        "Kotlin used to emit `new WebAssembly.Memory({ initial: 0 })` there; if that " +
                        "changed, docs/wasm-design.md's integration step needs revisiting."
                )
            }
        } else {
            importObject.writeText(placeholder.replace(text, "memory: $ns.wasmMemory"))
            logger.lifecycle("Pointed ${importObject.name}'s intrinsics.memory at Emscripten's wasmMemory")
        }
    }
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
