package python.multiplatform.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * KSP option names. Copied rather than imported: these are the processor's
 * `python.multiplatform.ksp.Constants`, and this plugin ships as a separate build so that a
 * consumer resolves it from the plugin portal without also resolving the processor's compile
 * classpath. Changing one without the other shows up in `ksp-fixtures` as an unwired module.
 */
private const val OPTION_ROLE = "python.multiplatform.role"
private const val OPTION_MODULE_NAME = "python.multiplatform.moduleName"
private const val OPTION_EXCLUDE_PACKAGES = "python.multiplatform.excludePackages"

private const val ROLE_LIBRARY = "library"
private const val ROLE_APP = "app"

/**
 * Which KSP configurations the processor belongs on.
 *
 * KSP creates one configuration per *compilation*, plus several of its own internal ones. The
 * processor belongs on each target's main compilation and nowhere else:
 *
 * - a `...Test` compilation would scan the test sources and emit a *second* `Fragment_<module>`
 *   under the same name, in the same package, in the same module;
 * - `kspCommonMainMetadata` would do the same for `commonMain`, which every platform target
 *   already compiles;
 * - `kspPluginClasspath*` and `...ProcessorClasspath` are KSP's own plumbing;
 * - the bare `ksp` configuration is the single-platform one. Adding to it from a multiplatform
 *   project fails outright -- "The 'ksp' configuration is deprecated in Kotlin Multiplatform
 *   projects. Please use target-specific configurations like 'kspJvm' instead" -- so
 *   [isMultiplatform] decides whether it counts.
 *
 * **A test compilation is not always a name that ends in `Test`.** On a module carrying an Android
 * plugin, KSP names its configurations after AGP's source sets, and AGP puts the build type last:
 *
 *     main          kspAndroid              kspAndroidDebug              kspAndroidRelease
 *     unit test     kspAndroidTest          kspAndroidTestDebug          kspAndroidTestRelease
 *     instrumented  kspAndroidAndroidTest   kspAndroidAndroidTestDebug   kspAndroidAndroidTestRelease
 *     testFixtures  kspAndroidTestFixtures  kspAndroidTestFixturesDebug  kspAndroidTestFixturesRelease
 *
 * so `endsWith("Test")` caught only the variant-less three of those nine. The processor landed on
 * `kspAndroidTestDebug`, scanned the *test* sources, and emitted a second `Fragment_<module>` --
 * and a second `FunctionTable` -- into the test compilation, where they shadowed the real ones
 * from `main`, because a compilation's own generated sources win over its classpath. The symptom
 * is a table holding the test classes and nothing the module actually exposes.
 * `ksp-fixtures/android` is what surfaced this; no module without an Android plugin can.
 *
 * `Test` is therefore matched as a camel-case *word* rather than as a suffix: it must start a word
 * and end one. A target or product flavour genuinely named `testing` is not a test compilation and
 * must keep the processor.
 *
 * Matching on names rather than walking the Kotlin extension's targets keeps this plugin free of
 * a Kotlin Gradle plugin dependency, and so free of its version. The observed name set is pinned
 * in `WiringTest`.
 */
private val TEST_WORD = Regex("(?:^|[a-z0-9])Test(?:[A-Z]|$)")

internal fun isBindingKspConfiguration(name: String, isMultiplatform: Boolean): Boolean {
    if (!name.startsWith("ksp")) return false
    if (name == "ksp") return !isMultiplatform
    if (name == "kspCommonMainMetadata") return false
    if (name.startsWith("kspPluginClasspath")) return false
    if (name.endsWith("ProcessorClasspath")) return false
    if (TEST_WORD.containsMatchIn(name)) return false
    return true
}

/**
 * The module name the generated fragment object is named after.
 *
 * Every fragment in every artifact lands in one package (`docs/upcall-table-design.md` §1), so
 * the name has to be unique across artifacts, not just within a build -- two independent
 * libraries both called `:core` would otherwise emit the same object into the same package. The
 * Maven group is what already carries that uniqueness, so it goes in front.
 */
internal fun deriveModuleName(group: String, path: String, name: String): String {
    val projectPart = path.removePrefix(":").ifBlank { name }
    val raw = if (group.isBlank()) projectPart else "$group.$projectPart"
    return raw.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

/**
 * `app` for the module that produces the final binary, `library` for everything else.
 *
 * Only one module in a build may aggregate: the aggregator emits `FunctionTable` into a fixed
 * package, so two of them collide. Gradle has no general notion of "the module that ships", and
 * a Kotlin Multiplatform module that builds a framework or a native executable carries no plugin
 * that distinguishes it -- hence the explicit `role` override on the extension, which is what
 * `ksp-fixtures/app` uses.
 */
internal fun inferRole(hasApplicationPlugin: Boolean, hasAndroidApplicationPlugin: Boolean): String =
    if (hasApplicationPlugin || hasAndroidApplicationPlugin) ROLE_APP else ROLE_LIBRARY

/** Everything a consumer may want to override; every one of them has a working default. */
interface PythonBindingsExtension {
    /** `"library"` or `"app"`. Defaults to [inferRole]. */
    val role: Property<String>

    /** Defaults to [deriveModuleName] over the project's group and path. */
    val moduleName: Property<String>

    /** Package prefixes to keep out of the table, on top of the library's own. */
    val excludePackages: ListProperty<String>

    /**
     * The processor artifact. Defaults to this plugin's own group/version coordinates; an
     * in-repo consumer sets it to the project instead:
     * `pythonBindings { processor.set(projects.pythonMultiplatformKsp) }`.
     */
    val processor: Property<Any>

    /**
     * Whether to stage a CPython prefix for this machine and point `PYTHONHOME` at it on every
     * `JavaExec` and `Test` task. Defaults to true — see [PythonHomeStaging] for why the library
     * cannot supply one at runtime, and ROADMAP §15e item 4 for the gap it closes.
     *
     * It is already a no-op whenever `PYTHONHOME` is set in the environment, so setting this to
     * false is for the case where a build must not reach the network at all: the first build on a
     * machine downloads ~25 MB from python-build-standalone. Every later build, and every other
     * project on the same machine, reuses it.
     */
    val stagePythonHome: Property<Boolean>

    /** CPython version to stage. Defaults to the one this library was built against. */
    val pythonVersion: Property<String>

    /** python-build-standalone release tag to stage from. Defaults to the one this library used. */
    val pythonBuildStandaloneRelease: Property<String>

    /** Whether to stage a free-threaded build. Defaults to what this library was built against. */
    val pythonFreeThreaded: Property<Boolean>
}

/**
 * ROADMAP §7's last open wiring item: what used to be `add("kspDesktop", ...)`,
 * `add("kspAndroidNativeArm64", ...)` and two `ksp { arg(...) }` lines per module, once per
 * target, is now one plugin.
 *
 *     plugins {
 *         id("io.github.thisisthepy.python.multiplatform.bindings")
 *     }
 *
 * A module that produces the final binary adds `pythonBindings { role.set("app") }` unless it
 * applies `application` or `com.android.application`, which are inferred.
 *
 * The consumer's own `org.jetbrains.kotlin.multiplatform` (or `.jvm`/`.android`) plugin version
 * has to satisfy two independent floors, verified against a real external consumer outside this
 * repository (ROADMAP §15f):
 *
 * - **API-compatible with the pinned KSP Gradle plugin** (`libs.versions.ksp` in this build --
 *   currently 2.3.11). Too old a Kotlin Gradle Plugin and `kspKotlin<Target>` fails at
 *   configuration time with a raw `NoSuchMethodError`-shaped message
 *   (`KotlinJvmCompilerOptions.getJvmDefault()` on Kotlin 2.1.0) -- KSP's plugin calls an API the
 *   older Kotlin Gradle Plugin does not have.
 * - **New enough to read the published library's metadata version.** `python-multiplatform` is
 *   built with this repo's own Kotlin version (currently 2.4.20-Beta2, `libs.versions.kotlin`).
 *   A consumer's Kotlin *compiler* one or more feature releases behind that fails
 *   `compileKotlin<Target>` with "was compiled with an incompatible version of Kotlin ... can
 *   read versions up to X.Y" once it reaches classes from the dependency -- this happens even
 *   when the KSP-vs-KGP floor above is already satisfied (observed with Kotlin 2.2.20).
 *
 * The version this repo itself uses is the only combination exercised; there is no published
 * compatibility matrix beyond it.
 */
class PythonBindingsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("pythonBindings", PythonBindingsExtension::class.java)

        project.pluginManager.apply("com.google.devtools.ksp")

        // `matching` is live, so targets declared after this plugin is applied -- which is the
        // normal case, since `kotlin { ... }` comes after `plugins { ... }` -- are still covered.
        // The multiplatform check is inside the predicate, not hoisted: `matching` evaluates it
        // when a configuration is realised, so this holds even if the Kotlin plugin is applied
        // after this one.
        project.configurations.matching {
            isBindingKspConfiguration(it.name, project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
        }.configureEach {
            dependencies.addLater(
                project.provider {
                    project.dependencies.create(extension.processor.getOrElse(DEFAULT_PROCESSOR_COORDINATES))
                },
            )
        }

        configurePythonHomeStaging(project, extension)

        project.afterEvaluate {
            setKspArg(
                OPTION_ROLE,
                extension.role.getOrElse(
                    inferRole(
                        hasApplicationPlugin = plugins.hasPlugin("application"),
                        hasAndroidApplicationPlugin = plugins.hasPlugin("com.android.application"),
                    ),
                ),
            )
            setKspArg(
                OPTION_MODULE_NAME,
                extension.moduleName.getOrElse(deriveModuleName(group.toString(), path, name)),
            )
            val excluded = extension.excludePackages.getOrElse(emptyList())
            if (excluded.isNotEmpty()) setKspArg(OPTION_EXCLUDE_PACKAGES, excluded.joinToString(","))
        }
    }

    /**
     * Registers `stagePythonHome` and hands its result to every task that launches a JVM.
     *
     * The prefix path is computed here rather than carried as a `Provider`, and it can be: it is a
     * pure function of the version, the release, the host and the Gradle user home, none of which
     * need the task to have run. That keeps `environment(...)` a plain string set at configuration
     * time — the value is in the child process's environment before its JVM starts, which is the
     * only way CPython's `getenv(3)` and [python.multiplatform.env.PythonHomeCheck]'s
     * `System.getenv` can be guaranteed to read the same thing.
     *
     * Skipped entirely when `PYTHONHOME` is already set, so a consumer with their own prefix keeps
     * it, and when the host is not a platform this library ships for — the latter warns rather
     * than fails, because a project may well be building only its Android or iOS targets there.
     */
    private fun configurePythonHomeStaging(project: Project, extension: PythonBindingsExtension) {
        val enabled = extension.stagePythonHome.getOrElse(true)
        // `providers.environmentVariable`, not `System.getenv`: the latter reads the *daemon's*
        // environment, and a daemon is reused across invocations that do not share one. A user
        // running `PYTHONHOME=/their/prefix ./gradlew run` against a daemon that was started
        // without it would otherwise be told, silently, that they had set nothing -- and have
        // their prefix replaced by a staged one on the very task they were configuring.
        if (!shouldStagePythonHome(project.providers.environmentVariable("PYTHONHOME").orNull, enabled)) return

        val platform = hostDesktopPlatform(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
        )
        if (platform == null) {
            project.logger.info(
                "python-multiplatform: not staging a CPython prefix -- no python-build-standalone " +
                    "distribution for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}.",
            )
            return
        }

        val version = extension.pythonVersion.getOrElse(DEFAULT_PYTHON_VERSION)
        val release = extension.pythonBuildStandaloneRelease.getOrElse(DEFAULT_PBS_RELEASE)
        val freeThreaded = extension.pythonFreeThreaded.getOrElse(DEFAULT_PYTHON_FREE_THREADED)

        // Shared across every project on the machine: the prefix is 24 MB of files that are
        // identical for every consumer of a given release, and a per-project copy would pay for it
        // once per checkout.
        val cacheRoot = java.io.File(project.gradle.gradleUserHomeDir, PythonHomeStaging.CACHE_DIRECTORY)
        val flavour = if (freeThreaded) "-freethreaded" else ""
        val destination = java.io.File(cacheRoot, "$version+$release$flavour/$platform")
        val prefix = java.io.File(destination, "python")

        val stage = project.tasks.register("stagePythonHome", StagePythonHomeTask::class.java) {
            group = "python"
            description = "Downloads and unpacks a CPython prefix for PYTHONHOME to point at."
            pythonVersion.set(version)
            pbsRelease.set(release)
            this.platform.set(platform)
            this.freeThreaded.set(freeThreaded)
            destinationDir.set(destination)
            downloadDir.set(java.io.File(cacheRoot, "archives"))
        }

        val home = prefix.absolutePath
        project.tasks.withType(org.gradle.api.tasks.JavaExec::class.java).configureEach {
            dependsOn(stage)
            environment("PYTHONHOME", home)
        }
        project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            dependsOn(stage)
            environment("PYTHONHOME", home)
        }
    }

    /**
     * `ksp { arg(key, value) }` without a compile-time reference to `KspExtension` -- see the
     * `runtimeOnly` comment in this build's `build.gradle.kts` for why there cannot be one.
     * A missing method here means KSP changed its extension API, which is worth a loud failure at
     * configuration time rather than a silently unconfigured processor.
     */
    private fun Project.setKspArg(key: String, value: String) {
        val ksp = extensions.findByName("ksp")
            ?: error("the KSP extension is missing: com.google.devtools.ksp was not applied")
        val arg = ksp.javaClass.methods.firstOrNull { method ->
            method.name == "arg" &&
                method.parameterCount == 2 &&
                method.parameterTypes.all { it == String::class.java }
        } ?: error("KspExtension has no arg(String, String); python-multiplatform-gradle-plugin needs updating")
        arg.invoke(ksp, key, value)
    }
}
