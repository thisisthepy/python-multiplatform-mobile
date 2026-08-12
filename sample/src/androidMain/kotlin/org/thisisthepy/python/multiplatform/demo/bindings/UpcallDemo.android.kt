package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.UpcallTable

/**
 * Android reaches the generated table the same way desktop does, and for the same reason: this
 * module applies the bindings plugin.
 *
 * It could not, before ROADMAP §13. KSP declares a minimum AGP and this build was below it, so
 * `com.google.devtools.ksp` died at configuration time in any module carrying an Android plugin
 * (`NoSuchMethodError: AndroidComponentsExtension.addKspConfigurations`). The demo's Python-facing
 * declarations therefore lived in a separate Android-free module and the install `actual` here
 * reported the version wall instead of doing anything. The AGP/Gradle bump removed both.
 *
 * That `actual` is gone from this file now, and not because Android lost the ability to write it:
 * `androidMain` *is* the Android target's own source set, so it could always name `FunctionTable`
 * directly, unlike `iosMain`. It is gone because the processor generates it (`@InstallsUpcallTable`
 * in `UpcallDemo.kt`), which removes the per-platform special case rather than the platform.
 */

/**
 * The call is made from Kotlin, exactly as on iOS, and says so.
 *
 * The *table* is real and generated here. What is missing on Android is the boundary shim:
 * desktop's `python.native.ffi.UpcallStub` is a pair of Panama upcall stubs, and nothing
 * equivalent exists for the JNI path yet. Reporting this as a call Python made would hide the one
 * part of ROADMAP §7 that is still open.
 */
actual fun callKotlinFromPython(): String {
    val handle = UpcallTable.resolve(UPCALL_ENTRY_NAME)
    if (!handle.isValid) return "the name was not in the table (handle -1)"
    val value = UpcallTable.invoke(handle, emptyArray())
    return "table hit: handle ${handle.raw} -> $value  (Kotlin-side call; " +
        "the boundary shim is desktop-only today)"
}
