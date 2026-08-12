package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.UpcallTable

/**
 * `installGeneratedUpcallTable` is **not** here, and cannot be.
 *
 * KSP writes its output into the *leaf* target's source set --
 * `build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin` and one sibling per
 * target -- and `iosMain` is an intermediate source set those leaves depend on, not the other way
 * round. So `python.multiplatform.generated.FunctionTable` is unresolvable from here for the same
 * reason it is unresolvable from `commonMain`, and the actual lives in
 * `iosX64Main`/`iosArm64Main`/`iosSimulatorArm64Main` as a one-liner each.
 *
 * Worth knowing before designing anything that wants to touch the generated table from shared
 * code: it is reachable only from the source set of the target that generated it.
 */

/**
 * The generated table is real here -- same processor, same fragments, discovered through the
 * `.klib` rather than a jar. What does not exist yet is the *boundary shim*: desktop's
 * `python.native.ffi.UpcallStub` is a pair of Panama upcall stubs and iOS has no equivalent
 * written, even though it is the platform where one should be cheapest (Python and Kotlin share
 * one binary, so a `@CName`-exported resolve/invoke pair needs no marshalling layer at all).
 *
 * So this calls the generated entry from the Kotlin side and says so. Reporting it as a call
 * Python made would hide the one part of ROADMAP §7 that is still open.
 */
actual fun callKotlinFromPython(): String {
    val handle = UpcallTable.resolve(UPCALL_ENTRY_NAME)
    if (!handle.isValid) return "the name was not in the table (handle -1)"
    val value = UpcallTable.invoke(handle, emptyArray())
    return "table hit: handle ${handle.raw} -> $value  (Kotlin-side call; " +
        "the boundary shim is desktop-only today)"
}
