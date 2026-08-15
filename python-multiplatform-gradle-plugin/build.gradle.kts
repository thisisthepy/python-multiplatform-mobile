// Imported rather than written as `java.util.Properties`: inside a Kotlin DSL build script, `java`
// resolves to the `JavaPluginExtension` accessor, not to the package root.
import java.util.Properties

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "io.github.thisisthepy"
version = "3.13.0" // kept in step with the root build's version, which the processor is published under

dependencies {
    // The plugin applies KSP itself, so the KSP Gradle plugin has to be on its runtime classpath
    // -- but deliberately *not* on its compile classpath.
    //
    // `kotlin-dsl` compiles against Gradle's embedded Kotlin (2.0.20 on Gradle 8.11.1; it was 1.9
    // on the 8.9 this build used before ROADMAP §13), and KSP 2.3.11 is compiled with Kotlin 2.3:
    // "Class 'com.google.devtools.ksp.gradle.KspExtension' was compiled with an incompatible
    // version of Kotlin ... the compiler version 1.9.0 can read versions up to 2.0.0". The
    // Gradle bump narrowed that gap but did not close it -- 2.0.20 reads up to 2.1 -- so
    // `runtimeOnly` still applies. It keeps the id resolvable (plugin ids are resolved from a
    // classpath resource, not from compiled types) while the two `ksp { arg(...) }` calls go
    // through `PythonBindingsPlugin.setKspArg`.
    runtimeOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")

    // The artefact walker (`docs/ecosystem.md` §5b's second producer) reads compiled class files
    // out of the jars the build resolves. ASM is a *plugin* dependency and deliberately not a
    // library one: nothing at runtime reads bytecode, and the walk happens once, at build time.
    //
    // Gradle bundles ASM, but under `org.gradle.internal.impldep.org.objectweb.asm` -- a relocated
    // internal package with no compatibility promise -- so this is a real coordinate rather than a
    // reach into Gradle's own copy.
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")

    // `KlibScanner`'s reader: `org.jetbrains.kotlin.library.abi.LibraryAbiReader`, which decodes a
    // `.klib`'s ABI protobuf into Kotlin-typed declarations. A klib is what a Kotlin/Native target
    // resolves where a JVM target resolves a jar, so this is to `KlibScanner` exactly what ASM plus
    // `kotlin-metadata-jvm` are to `ArtifactScanner` -- and a *plugin* dependency for the same
    // reason both of those are: the walk happens once, at build time, and nothing this plugin emits
    // into a consumer's build re-reads a klib at run time.
    //
    // **It is 55.6 MiB (58,251,465 bytes), by far the largest thing on this classpath, so where that
    // lands was measured rather than assumed:**
    //   - not in the library. `python-multiplatform` does not depend on this plugin;
    //     `:python-multiplatform:dependencies` reports `kotlin-compiler-embeddable:2.0.20` in no
    //     configuration at all, `desktopRuntimeClasspath` included.
    //   - not on a consumer's *application* classpath. `:ksp-fixtures:app:dependencies` reports zero
    //     occurrences of `2.0.20`. (It does report `kotlin-compiler-embeddable:2.4.0` and
    //     `2.4.20-Beta2` under `kotlinCompilerPluginClasspath*` and the commonizer's configurations
    //     -- KGP's own, present before this dependency existed.)
    //   - it *is* on a consumer's **buildscript** classpath: `implementation` becomes `runtime` scope
    //     in this plugin's published POM (checked in the generated `pom-default.xml`), so applying
    //     the plugin downloads it into the Gradle cache and puts it on the plugin classloader.
    //
    // Gradle 8.11.1 ships a byte-identical copy in its own `lib/` (same SHA-256), but plugin
    // classloaders see a filtered view of Gradle's runtime -- `org.gradle.*` API packages, not
    // `org.jetbrains.kotlin.library.abi` -- so this is a real coordinate rather than a reach into
    // Gradle's own copy, for the same reason the ASM lines above are.
    //
    // ### Does not survive a consumer's classloader hierarchy called directly -- fixed with isolation
    //
    // Called from `PythonArtifactBindingsTask`'s/`PythonStubsTask`'s own classloader,
    // `KlibScanner.scanKlib` fails at execution with `NoSuchMethodError:
    // KotlinLibraryImplKt.createKotlinLibrary$default(...)`. Not a version conflict Gradle could
    // resolve -- `buildEnvironment` reports this coordinate at 2.0.20 on that project's plugin
    // classpath, correctly. It is **class mixing across classloader scopes**, measured by printing
    // each class's `CodeSource` from inside the task:
    //
    //     org.jetbrains.kotlin.library.abi.LibraryAbiReader                 <- 2.0.20, this jar
    //     org.jetbrains.kotlin.library.ToolingSingleFileKlibResolveStrategy <- 2.0.20, this jar
    //     org.jetbrains.kotlin.library.impl.KotlinLibraryImplKt             <- kotlin-util-klib 2.4.20-Beta2
    //     org.jetbrains.kotlin.konan.file.ZipFileSystemAccessor             <- kotlin-util-io 2.4.20-Beta2
    //
    // The last two come from the **root project's** buildscript scope, which the Kotlin Gradle Plugin
    // populates and which is a *parent* of every subproject's plugin classloader. Parent-first
    // delegation hands out `kotlin-util-klib`'s half of `org.jetbrains.kotlin.library.*` at KGP's
    // version and this jar's half at 2.0.20, and the halves do not fit. Any consumer that declares
    // KGP in its root `plugins {}` block reproduces it.
    //
    // Aligning the version does not fix it -- tried both ways:
    //   - `2.4.20-Beta2` alone does not compile: Gradle's embedded Kotlin is 2.0.20 and that jar's
    //     modules carry metadata 2.2.0-2.4.0 ("expected version is 2.0.0").
    //   - with `-Xskip-metadata-version-check` it compiles and gets further, then fails with
    //     `NoClassDefFoundError: org/jetbrains/kotlin/protobuf/Internal$EnumLite` -- the parent's
    //     `kotlin-util-klib` needs the protobuf runtime shaded *inside this jar*, in the child scope
    //     it cannot see. The mixing is symmetric; neither version wins.
    //
    // **Fixed with real isolation**: `KlibScanWorkAction`
    // (`python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/artifact
    // /KlibScanWorkAction.kt`) runs every `KlibScanner` call behind a `WorkerExecutor`
    // `classLoaderIsolation` whose explicit classpath is this plugin's own classloader's `.urLs` --
    // the child scope only, none of the root buildscript scope above it. An isolated worker
    // classloader's parent is Gradle's own minimal worker infrastructure, not that root scope, so
    // `org.jetbrains.kotlin.library.*` has exactly one source inside the worker. Confirmed against a
    // real consumer build, not only this plugin's own unit tests:
    // `:ksp-fixtures:klib-artifact:generatePythonArtifactBindings` and `:generatePythonStubs` both
    // pass and emit the walker's real output (`kotlinx.coroutines.flow.internal.checkIndexOverflow`).
    //
    // Pinned to 2.0.20 for the reason the `kotlin-metadata-jvm` comment below spells out, and that
    // pin is also the version floor `KlibScanner`'s KDoc measures its extension-receiver decline
    // against.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.20")

    // Reads `@Metadata`'s `d1`/`d2` payload -- the facts ASM's view of a class file cannot reach:
    // a multi-file facade's part classes, an extension function's receiver, a value class's
    // underlying type and accessor, `suspend`. See `ArtifactScanner`'s KDoc. Also a *plugin*
    // dependency, for the same reason ASM is: the walk happens once, at build time, and nothing
    // this plugin emits into a consumer's own build re-reads bytecode at runtime.
    //
    // Pinned to "2.0.20" rather than `libs.versions.kotlin` (2.4.20-Beta2): this module's own
    // `compileKotlin` runs on Gradle's *embedded* Kotlin compiler (2.0.20 on Gradle 8.11.1, see the
    // `kotlin-dsl` comment above), which cannot read a dependency whose own metadata a newer
    // compiler wrote -- "the actual metadata version is 2.4.0, but the compiler version 2.0.0 can
    // read versions up to 2.1.0". The library's own read/write logic tolerates a wide range of
    // *target* metadata versions regardless of which release of the library does the reading, so an
    // older `kotlin-metadata-jvm` reading androidx's or kotlin-stdlib's newer-than-2.0.20 metadata is
    // not the same constraint -- see `ArtifactScannerTest`'s real-jar cases, which this version reads
    // correctly.
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.0.20")

    testImplementation(kotlin("test"))
}

/**
 * A real third-party jar for `ArtifactScannerTest` to walk, kept off the test *classpath* on
 * purpose.
 *
 * `tasks.test` runs on the JUnit Platform and `kotlin-test` picks its framework from what it finds
 * on the classpath; putting JUnit 4 there as well is exactly the kind of skew that makes the whole
 * suite report zero tests without failing. A separate configuration gives the test a file path and
 * nothing else -- which is all a walker needs, and is also how a consumer's build will hand it
 * artefacts.
 */
val walkerFixtureJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    walkerFixtureJar(libs.junit) { isTransitive = false }
}

/**
 * A real third-party klib for [KlibScannerTest], the klib counterpart of [walkerFixtureJar].
 *
 * `kotlinx-coroutines-core-androidnativearm64` is Kotlin/Native's per-target leaf module -- an
 * ordinary Maven artifact (a `.klib` file at a GAV coordinate, no Kotlin Gradle Plugin variant
 * resolution involved) despite the platform in its name, so a plain resolvable configuration reaches
 * it the same way [walkerFixtureJar] reaches a jar. `isTransitive = false` for the same reason too:
 * the test wants one file, not `atomicfu` and `kotlin-stdlib` pulled in behind it.
 */
val walkerFixtureKlib: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    walkerFixtureKlib("org.jetbrains.kotlinx:kotlinx-coroutines-core-androidnativearm64:1.8.1") { isTransitive = false }
}

gradlePlugin {
    plugins {
        create("pythonMultiplatformBindings") {
            id = "io.github.thisisthepy.python.multiplatform.bindings"
            implementationClass = "python.multiplatform.gradle.PythonBindingsPlugin"
            displayName = "Python Multiplatform bindings"
            description = "Wires the python-multiplatform KSP processor into every Kotlin target of a module."
        }
    }
}

/**
 * The default processor coordinates have to name this build's own group and version, and a
 * hand-copied string would drift the first time either changes. Generating a one-line source file
 * keeps it derived.
 */
val generateCoordinates = tasks.register("generateCoordinates") {
    val outputDir = layout.buildDirectory.dir("generated/coordinates")
    // Plain `group`/`version` here resolve against the task itself (`Task.getGroup()` is a task
    // category label, always null on this task; `Task` has no `version` property so that one
    // falls through to the project by luck) -- not against the project, even though this lambda
    // reads as if it were project-scoped. Qualifying both is what makes the generated coordinates
    // name this build's actual group instead of the literal string "null".
    val coordinates = "${project.group}:python-multiplatform-ksp:${project.version}"

    // The CPython distribution the *library* was built against, read from the root build's own
    // `gradle.properties` rather than restated here. `StagePythonHomeTask` stages this exact
    // upstream build for a consumer, and `desktopJar` carries the `libpython` taken from it, so a
    // second copy of these values that drifted would pair a stdlib with an interpreter from a
    // different build. The fallbacks match `python-multiplatform/build.gradle.kts`'s own, for the
    // case where this build is checked out without the root's properties beside it.
    val rootProperties = Properties().apply {
        val file = rootDir.resolve("../gradle.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    val pythonVersionValue = rootProperties.getProperty("pythonVersion") ?: "3.14.7"
    val pbsReleaseValue = rootProperties.getProperty("pythonBuildStandaloneRelease") ?: "20260807"
    val freeThreadedValue = rootProperties.getProperty("pythonFreeThreaded")?.toBoolean() ?: false

    // `python-multiplatform` itself is versioned "$pythonVersion-alpha01" -- see that build's own
    // `val libraryVersion` -- which is *not* this plugin build's `project.version` (kept in step by
    // hand, see the top of this file). The wasm-runtime zip is published under the library's
    // version, alongside the library artifact it has to pair with, so the coordinate has to be
    // built the same way rather than reusing this plugin's own version.
    val libraryVersionValue = "$pythonVersionValue-alpha01"
    val wasmRuntimeCoordinatesValue = "${project.group}:python-multiplatform-wasm-runtime:$libraryVersionValue"

    inputs.property("coordinates", coordinates)
    inputs.property("pythonVersion", pythonVersionValue)
    inputs.property("pbsRelease", pbsReleaseValue)
    inputs.property("pythonFreeThreaded", freeThreadedValue)
    inputs.property("wasmRuntimeCoordinates", wasmRuntimeCoordinatesValue)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("python/multiplatform/gradle/ProcessorCoordinates.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            // GENERATED by the plugin build. Do not edit.
            package python.multiplatform.gradle

            internal const val DEFAULT_PROCESSOR_COORDINATES: String = "$coordinates"

            /** The CPython release the `libpython` bundled in `desktopJar` was taken from. */
            internal const val DEFAULT_PYTHON_VERSION: String = "$pythonVersionValue"

            /** The python-build-standalone release tag that published it. */
            internal const val DEFAULT_PBS_RELEASE: String = "$pbsReleaseValue"

            internal const val DEFAULT_PYTHON_FREE_THREADED: Boolean = $freeThreadedValue

            /**
             * `python-multiplatform`'s `wasmRuntime` publication -- see ROADMAP §10. Versioned
             * with the library, not with this plugin, because that is the artifact it has to
             * agree with about which CPython build is inside.
             */
            internal const val DEFAULT_WASM_RUNTIME_COORDINATES: String = "$wasmRuntimeCoordinatesValue"
            """.trimIndent() + "\n",
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateCoordinates)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(walkerFixtureJar).withPropertyName("walkerFixtureJar").withPathSensitivity(PathSensitivity.NAME_ONLY)
    // Resolved here rather than inside the test so the test never has to know a repository, a
    // coordinate or a cache layout -- it reads one system property holding one path.
    systemProperty("python.multiplatform.walkerFixtureJar", walkerFixtureJar.singleFile.absolutePath)
    inputs.files(walkerFixtureKlib).withPropertyName("walkerFixtureKlib").withPathSensitivity(PathSensitivity.NAME_ONLY)
    systemProperty("python.multiplatform.walkerFixtureKlib", walkerFixtureKlib.singleFile.absolutePath)
}
