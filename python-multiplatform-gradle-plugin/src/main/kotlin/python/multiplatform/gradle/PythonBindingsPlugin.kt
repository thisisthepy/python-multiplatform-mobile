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
