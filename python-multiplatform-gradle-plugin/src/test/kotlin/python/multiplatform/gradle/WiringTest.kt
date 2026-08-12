package python.multiplatform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three decisions the plugin makes for the user, as pure functions.
 *
 * The plugin's real proof is `ksp-fixtures`, which applies it and runs its generated table; these
 * pin the parts that would otherwise only be observable as a missing fragment or a duplicate one.
 */
class WiringTest {

    // Observed on `:ksp-fixtures:app` (`gradlew dependencies`), Kotlin 2.4.20-Beta2 + KSP 2.3.11.
    private val observedConfigurations = listOf(
        "ksp",
        "kspAndroidNativeArm64",
        "kspAndroidNativeArm64Test",
        "kspCommonMainKotlinMetadataProcessorClasspath",
        "kspCommonMainMetadata",
        "kspDesktop",
        "kspDesktopTest",
        "kspKotlinAndroidNativeArm64ProcessorClasspath",
        "kspKotlinDesktopProcessorClasspath",
        "kspPluginClasspath",
        "kspPluginClasspathNonEmbeddable",
        "kspTestKotlinAndroidNativeArm64ProcessorClasspath",
        "kspTestKotlinDesktopProcessorClasspath",
    )

    @Test
    fun theProcessorGoesOnEveryTargetsMainCompilationAndNowhereElse() {
        assertEquals(
            listOf("kspAndroidNativeArm64", "kspDesktop"),
            observedConfigurations.filter { isBindingKspConfiguration(it, isMultiplatform = true) },
        )
    }

    @Test
    fun theBareKspConfigurationIsForSinglePlatformProjectsOnly() {
        // Adding to it from a multiplatform project is a hard failure: "The 'ksp' configuration
        // is deprecated in Kotlin Multiplatform projects" -- observed against the fixtures.
        assertFalse(isBindingKspConfiguration("ksp", isMultiplatform = true))
        assertTrue(isBindingKspConfiguration("ksp", isMultiplatform = false))
    }

    @Test
    fun testCompilationsAreExcludedOrTheModuleWouldEmitASecondFragmentUnderTheSameName() {
        assertFalse(isBindingKspConfiguration("kspDesktopTest", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspAndroidNativeArm64Test", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspTest", isMultiplatform = false))
    }

    @Test
    fun theMetadataCompilationIsExcludedForTheSameReason() {
        // commonMain is compiled again by every platform target, so processing it separately
        // would produce a duplicate `Fragment_<module>` for one module.
        assertFalse(isBindingKspConfiguration("kspCommonMainMetadata", isMultiplatform = true))
    }

    @Test
    fun kspsOwnInternalClasspathConfigurationsAreNotDependencyTargets() {
        assertFalse(isBindingKspConfiguration("kspPluginClasspath", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspKotlinDesktopProcessorClasspath", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspTestKotlinDesktopProcessorClasspath", isMultiplatform = true))
    }

    @Test
    fun moduleNameCarriesTheGroupBecauseFragmentObjectsShareOnePackageAcrossAllArtifacts() {
        // Two independent libraries both called `:core` would otherwise both emit
        // `python.multiplatform.generated.fragments.Fragment_core` and collide on one classpath.
        assertEquals(
            "io_github_thisisthepy_ksp_fixtures_app",
            deriveModuleName(group = "io.github.thisisthepy", path = ":ksp-fixtures:app", name = "app"),
        )
        assertEquals(
            "com_example_core",
            deriveModuleName(group = "com.example", path = ":core", name = "core"),
        )
    }

    @Test
    fun moduleNameFallsBackToTheProjectNameForARootProjectOrAGrouplessBuild() {
        assertEquals("my_app", deriveModuleName(group = "", path = ":", name = "my-app"))
        assertEquals("com_example_my_app", deriveModuleName(group = "com.example", path = ":", name = "my-app"))
    }

    @Test
    fun moduleNameIsAlwaysAValidKotlinIdentifierSuffix() {
        val derived = deriveModuleName(group = "com.example-corp", path = ":some module", name = "some module")
        assertTrue(derived.all { it.isLetterOrDigit() || it == '_' }, derived)
    }

    @Test
    fun roleIsInferredFromWhetherTheModuleProducesTheFinalBinary() {
        // Only the module that links the binary may aggregate: two aggregators on one classpath
        // means two `FunctionTable` objects in one package.
        assertEquals("app", inferRole(hasApplicationPlugin = true, hasAndroidApplicationPlugin = false))
        assertEquals("app", inferRole(hasApplicationPlugin = false, hasAndroidApplicationPlugin = true))
        assertEquals("library", inferRole(hasApplicationPlugin = false, hasAndroidApplicationPlugin = false))
    }
}
