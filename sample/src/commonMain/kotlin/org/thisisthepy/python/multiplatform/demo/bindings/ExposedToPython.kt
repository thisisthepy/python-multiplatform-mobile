package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.PythonInternal

/**
 * The Kotlin surface this app offers to Python.
 *
 * **Nothing here is registered by hand.** This module applies
 * `id("io.github.thisisthepy.python.multiplatform.bindings")`, which runs `python-multiplatform-ksp`
 * over these sources at build time; every public declaration it finds becomes an entry in the
 * generated `python.multiplatform.generated.FunctionTable`, which [installGeneratedUpcallTable]
 * hands to [python.multiplatform.reflection.UpcallTable]. Exposure is a blacklist (ROADMAP §7,
 * `docs/binding-policy.md`): being `public` is all it takes, and opting *out* is what needs an
 * annotation.
 *
 * The Python-visible name of an entry is its Kotlin qualified name, which is why
 * [UPCALL_ENTRY_NAME] can be written out as a literal for the UI and the native-image check.
 */
object DemoCounter {

    private var count: Long = 0

    /** Called from the Compose button. Ordinary Kotlin state; Python is not involved. */
    fun press() {
        count += 1
    }

    /**
     * The zero-argument entry, kept because `NativeImageMain`'s closed-world check resolves and
     * calls exactly this one through the older `(long) -> long` stub pair.
     */
    fun presses(): Long = count

    /**
     * The entry that takes arguments -- the half that did not exist until
     * `python.multiplatform.ffi.upcall.UpcallTrampoline` did (ROADMAP §13).
     *
     * A `String` and a `Long` in, a `String` out: three marshalling decisions the boundary makes
     * from the `TypeTag`s the generated table has carried all along. Nothing about this
     * declaration is special -- it is exposed for being `public`, like everything else here.
     */
    fun describe(prefix: String, multiplier: Long): String = "$prefix${count * multiplier}"

    /**
     * Exposed to nobody. `@PythonInternal` is the opt-out, and this entry must be absent from the
     * generated table; the demo screen and `NativeImageMain` both assert on that, so the
     * annotation losing its effect would be visible rather than silent.
     */
    @PythonInternal
    fun internalDetail(): Long = -1L
}

/** The name [DemoCounter.presses] is registered under. */
const val UPCALL_ENTRY_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.presses"

/** The name [DemoCounter.describe] is registered under. */
const val UPCALL_ARGS_ENTRY_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.describe"

/** The name [DemoCounter.internalDetail] *would* carry if `@PythonInternal` did nothing. */
const val UPCALL_EXCLUDED_NAME: String =
    "org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter.internalDetail"
