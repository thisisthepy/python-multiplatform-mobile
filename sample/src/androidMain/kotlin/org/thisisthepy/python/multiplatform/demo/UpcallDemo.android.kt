package org.thisisthepy.python.multiplatform.demo

/**
 * Not wired, and the reason is a build constraint rather than anything about Android at run time.
 *
 * The generated table lives in `:sample-bindings`, which has no Android target because a module
 * carrying an Android plugin cannot apply the bindings plugin at these versions: KSP 2.3.11
 * requires AGP >= 8.10 and this build is on AGP 8.5.2 (`NoSuchMethodError:
 * AndroidComponentsExtension.addKspConfigurations`). See `sample/build.gradle.kts`.
 *
 * The runtime half is not the problem -- `python.multiplatform.reflection.UpcallTable` is
 * `commonMain` and works here. What Android cannot get today is the *generated* fragment, and a
 * hand-written one would hide exactly the thing this demo exists to show.
 */
actual object UpcallDemo {
    actual val available: Boolean = false
    actual val entryName: String = "(none)"
    actual fun install() = Unit
    actual fun press() = Unit
    actual fun callFromPython(): String = UNAVAILABLE
    actual fun tableSummary(): String = UNAVAILABLE
    actual fun optOutHeld(): Boolean = false

    private const val UNAVAILABLE =
        "unavailable on Android: the bindings plugin needs AGP >= 8.10 (this build is on 8.5.2)"
}
