package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * The contract every [Platform] implementation owes, checked on whichever target is running.
 *
 * This exists because `AndroidNativePlatform` shipped with `arch = "Kotlin/Native"`,
 * `version = "Kotlin/Native"` and `versionCode = 0` -- three `TODO`s that compiled, satisfied the
 * interface, and printed `Android Kotlin/Native (SDK 0, Kotlin/Native)` into every measurement
 * header the androidNative binary produced. Nothing was checking that these fields say anything
 * about the machine, so nothing noticed for as long as no androidNative test actually ran.
 *
 * The assertions here are the ones that hold on *any* device, so they can live in `commonTest` and
 * guard all five targets at once. The device-specific half -- that an API 26 emulator and an API 36
 * emulator report *different* values -- cannot be checked from inside a single test process, which
 * only ever sees one device; that check is `AndroidNativeDeviceIdentityTest` in `artTest`, which
 * compares against values the Gradle harness read over adb.
 */
class PlatformIdentityTest {

    /** Values that were once used as stand-ins for a real read, in this file's own history. */
    private val placeholders = setOf("kotlin/native", "unknown", "todo", "", "null")

    @Test
    fun archNamesAMachineAndNotARuntime() {
        val arch = currentPlatform.arch
        assertTrue(
            arch.lowercase() !in placeholders,
            "Platform.arch is a placeholder (\"$arch\"). It has to name the machine the code is " +
                "running on -- `Platform.is64Bit`, `isArm` and `isX86` all pattern-match on it, and " +
                "`manager.kt` picks which CPython to load from `isArm`."
        )
    }

    @Test
    fun versionNamesTheOperatingSystemRelease() {
        val version = currentPlatform.version
        assertTrue(
            version.lowercase() !in placeholders,
            "Platform.version is a placeholder (\"$version\"). Every other implementation puts the " +
                "user-visible OS release here (Build.VERSION.RELEASE, UIDevice.systemVersion, " +
                "os.version)."
        )
    }

    @Test
    fun versionTextAgreesWithVersionCode() {
        val code = currentPlatform.versionCode
        val text = currentPlatform.versionText
        if (code != null && text != null) {
            assertTrue(
                text.contains(code.toString()),
                "versionText (\"$text\") does not mention versionCode ($code); the two are supposed " +
                    "to be the same number, one of them labelled."
            )
        }
    }

    @Test
    fun nameCarriesTheFieldsItIsBuiltFrom() {
        val name = currentPlatform.name
        assertTrue(name.contains(currentPlatform.arch), "'$name' omits arch '${currentPlatform.arch}'")
        assertTrue(name.contains(currentPlatform.version), "'$name' omits version '${currentPlatform.version}'")
        assertTrue(name.contains(currentPlatform.os.toString()), "'$name' omits os '${currentPlatform.os}'")
    }

    @Test
    fun platformTypePredicatesAgreeWithPlatformType() {
        val type = currentPlatform.platformType
        assertEquals(type == PlatformType.JVM, currentPlatform.isJvm, "isJvm disagrees with $type")
        assertEquals(type == PlatformType.Native, currentPlatform.isNative, "isNative disagrees with $type")
    }

    @Test
    fun osPredicatesAgreeWithOs() {
        val os = currentPlatform.os
        assertEquals(os == OSType.Android, currentPlatform.isAndroid, "isAndroid disagrees with $os")
        assertEquals(os == OSType.IOS, currentPlatform.isIos, "isIos disagrees with $os")
    }

    /**
     * Android is the one OS in this tree reached through two entirely separate implementations --
     * `AndroidPlatform` over the ART VM and `AndroidNativePlatform` over cinterop -- and they are
     * supposed to describe the same device the same way. Whichever of the two is running has to
     * satisfy this, so the two cannot drift.
     */
    @Test
    fun androidReportsAnApiLevelAndASupportedAbi() {
        if (!currentPlatform.isAndroid) return

        val code = assertNotNull(
            currentPlatform.versionCode,
            "An Android platform must report an API level; Build.VERSION.SDK_INT and " +
                "android_get_device_api_level() both have one."
        )
        assertTrue(
            code >= 21,
            "API level $code is below this module's minSdk (21), so it is not a level any " +
                "supported device reports -- 0 is what the androidNative placeholder used to return."
        )
        assertEquals(
            "SDK $code", currentPlatform.versionText,
            "Android's versionText spelling is fixed by AndroidPlatform; androidNative has to match it."
        )

        // `abiFilters` and the packaged CPython builds cover exactly arm64-v8a and x86_64, so the
        // kernel machine name can only be aarch64 or x86_64 here -- and `manager.kt` routes on
        // precisely this distinction.
        assertTrue(
            currentPlatform.isArm != currentPlatform.isX86,
            "arch '${currentPlatform.arch}' is classified as neither arm nor x86, or as both."
        )
        assertTrue(
            currentPlatform.is64Bit,
            "arch '${currentPlatform.arch}' is not 64-bit, but only arm64-v8a and x86_64 are built."
        )
    }
}
