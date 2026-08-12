package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.UpcallTable

/**
 * Android reaches the generated table the same way desktop does, and for the same reason: this
 * module applies the bindings plugin.
 *
 * It could not, before ROADMAP §13. KSP declares a minimum AGP and this build was below it, so
 * `com.google.devtools.ksp` died at configuration time in any module carrying an Android plugin
 * (`NoSuchMethodError: AndroidComponentsExtension.addKspConfigurations`). The demo's Python-facing
 * declarations therefore lived in a separate Android-free module and this actual reported the
 * version wall instead of doing anything. The AGP/Gradle bump removed both.
 *
 * Note that `FunctionTable` is nameable from `androidMain` — unlike `iosMain`, which needs a
 * one-line actual per leaf target (see `UpcallDemo.ios.kt`). `androidMain` *is* the Android
 * target's own source set, compiled together with the KSP output of each variant, rather than an
 * intermediate source set the generating compilations depend on.
 */
actual fun installGeneratedUpcallTable() {
    UpcallTable.install(FunctionTable.fragments)
}

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
