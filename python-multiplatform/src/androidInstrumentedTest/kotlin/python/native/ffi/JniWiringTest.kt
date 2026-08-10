package python.native.ffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settles, on a real device, whether the JNI wiring between the Kotlin/Native `@CName` exports and
 * the `external fun` declarations in [bindings] actually passes arguments correctly.
 *
 * The exports take only their declared arguments — none of them accepts a `JNIEnv*`. But `bindings`
 * declares them as ordinary JNI methods, and ART invokes those as
 * `f(JNIEnv*, jobject, args...)`. If that is what happens, every argument arrives shifted by two
 * slots and the first one reads the `JNIEnv` pointer instead of its value.
 *
 * Zero-argument functions survive this by accident, since the callee simply ignores the extra
 * registers. So the check has to pass a value and read it back: [bindings.diagEcho] returns its
 * argument unchanged, which makes the shift immediately visible.
 */
@RunWith(AndroidJUnit4::class)
class JniWiringTest {

    @Test
    fun argumentsArriveUnshifted() {
        val sent = 0x5A5A_1234_5678L
        val got = bindings.diagEcho(sent)
        assertEquals(
            "diagEcho returned a different value than it was given, which means the JNI calling " +
                "convention does not match the @CName export signature",
            sent,
            got
        )
    }

    @Test
    fun zeroArgCallsWorkRegardless() {
        // Expected to pass even with a broken convention — recorded so the contrast with
        // argumentsArriveUnshifted is visible in the results.
        val state = bindings.Py_IsInitialized()
        assertEquals("Py_IsInitialized should report 0 before initialisation", 0, state)
    }
}
