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
        // echo0/echo1/echo2 are three separate registrations of the same C body,
        // `jlong f(jlong x) { return x; }`. They are not testing different argument
        // positions -- each takes exactly one argument. So under a correct calling
        // convention all three must return the sentinel, and asserting only the first
        // would hide a failure in the other two.
        val sent = 0x5A5A_1234_5678L
        assertEquals("echo0 must return the value it was sent", sent, bindings.echo0(sent))
        assertEquals("echo1 must return the value it was sent", sent, bindings.echo1(sent))
        assertEquals("echo2 must return the value it was sent", sent, bindings.echo2(sent))
    }

    @Test
    fun zeroArgCallsWorkRegardless() {
        // Expected to pass even with a broken convention — recorded so the contrast with
        // argumentsArriveUnshifted is visible in the results.
        val state = bindings.Py_IsInitialized()
        assertEquals("Py_IsInitialized should report 0 before initialisation", 0, state)
    }

    /**
     * `PyList_GetItemRawF` is the @FastNative twin added so the per-element call of bulk list
     * iteration follows the device axis instead of being pinned to @CriticalNative (see
     * `docs/jni-call-convention-audit.md`). It is a second registration of the same CPython
     * function, so the two must be indistinguishable at every index.
     *
     * This is a wiring check, not a benchmark. Two ways to get it wrong are both caught here: a
     * missing or misspelled table entry leaves the method unregistered and the call throws, and a
     * wrapper written without the leading `JNIEnv*, jclass` shifts both arguments, which makes the
     * returned pointers disagree rather than merely being slow.
     */
    @Test
    fun bothListGetItemConventionsAgree() {
        PythonOnDevice.ensureInitialised()

        val sys = PythonOnDevice.withUtf8("sys") { bindings.PyImport_ImportModuleN(it) }
        org.junit.Assert.assertTrue("could not import sys", sys != 0L)
        val path = PythonOnDevice.withUtf8("path") { bindings.PyObject_GetAttrStringN(sys, it) }
        org.junit.Assert.assertTrue("could not read sys.path", path != 0L)

        val len = bindings.PyList_SizeNormal(path)
        org.junit.Assert.assertTrue("sys.path should be a non-empty list, got len=$len", len > 0)

        for (i in 0 until len) {
            val critical = bindings.PyList_GetItemRaw(path, i)
            val fast = bindings.PyList_GetItemRawF(path, i)
            org.junit.Assert.assertTrue("PyList_GetItemRaw returned null at index $i", critical != 0L)
            assertEquals(
                "PyList_GetItemRawF disagrees with PyList_GetItemRaw at index $i",
                critical,
                fast,
            )
        }
    }

    @Test
    fun cpythonInitialisesCorrectly() {
        python.multiplatform.ffi.PythonTestFixture.withInterpreter {
            android.util.Log.d("JniWiringTest", "Calling Py_IsInitialized()")
            org.junit.Assert.assertNotEquals(0, Py_IsInitialized())
            
            val code = "x = 1 + 1"
            android.util.Log.d("JniWiringTest", "Calling PyRun_SimpleString()")
            org.junit.Assert.assertEquals(0, PyRun_SimpleString(code))
            
            android.util.Log.d("JniWiringTest", "Calling Py_GetVersion()")
            val version = Py_GetVersion()
            android.util.Log.d("JniWiringTest", "Version: $version")
            org.junit.Assert.assertTrue("Version should start with 3.14, got $version", version?.startsWith("3.14") == true)
            
            android.util.Log.d("JniWiringTest", "Calling PyImport_ImportModule()")
            val sysModule = PyImport_ImportModule("sys")
            org.junit.Assert.assertNotNull(sysModule)
        }
    }
}
