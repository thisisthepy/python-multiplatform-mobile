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
}
