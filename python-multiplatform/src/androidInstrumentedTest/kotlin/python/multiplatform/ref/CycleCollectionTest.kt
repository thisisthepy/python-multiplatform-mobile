package python.multiplatform.ref

import python.multiplatform.ffi.ProxyTypeFactory
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.native.ffi.PythonOnDevice
import python.native.ffi.bindings
import python.native.ffi.toNativePointer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Kotlin object that holds a Python object. Both the wrapper (for ownership) and the raw
 * pointer (for `tp_traverse`, which has to hand CPython the bare pointer) are kept.
 */
class AndroidCycleNode {
    var ref: PyObject? = null
    var rawPtr: Long = 0L
}

/**
 * Same shape as [AndroidCycleNode], but deliberately never registered with [ClassLookup]. The
 * negative control for [CycleCollectionTest.testCycleCollectionByGC].
 */
class UnregisteredAndroidCycleNode {
    var ref: PyObject? = null
    var rawPtr: Long = 0L
}

/**
 * Android's counterpart of `desktopTest`'s and `iosSimulatorArm64Test`'s `CycleCollectionTest`:
 * a Kotlin object holds a Python object, the Python object holds that Kotlin object through its
 * handle, and the loop is breakable only by CPython's cycle collector reaching across the
 * boundary through the proxy type's `tp_traverse`/`tp_clear`.
 *
 * Android reaches those slots differently from either of the other two. There is no Panama and
 * no `staticCFunction` here, so the slots are C functions in `artMain/cinterop/jni_onload.def`
 * that call back into `python.multiplatform.ffi.ProxyCallbacks` over JNI. What this test
 * establishes, beyond the collection itself, is that a JNI upcall issued from inside a running
 * CPython collection works at all.
 *
 * Instrumented, not a unit test: `androidUnitTest` runs on a host JVM that cannot load the
 * arm64/x86_64 `.so`, so the interpreter -- and with it `JNI_OnLoad`, and with it the
 * registration of `proxyCreateType` -- is unreachable there.
 */
class CycleCollectionTest {

    @BeforeTest
    fun setUp() {
        PythonOnDevice.ensureInitialised()

        // Registered unconditionally, and deliberately not behind an "is the interpreter already
        // up?" check. Nesting registration inside such a check makes it run only when this test
        // is the one that starts the interpreter -- true in isolation, false in a full suite run,
        // and on Android the runner has already initialised it before any test class loads, so
        // it would be false always. Without the registration ClassLookup.find returns null,
        // tp_traverse reports nothing, and the cycle is invisible to CPython's collector. The
        // failure then looks exactly like "the object was never GC-tracked", which is a
        // different bug. Both desktop and iOS hit this.
        ClassLookup.register(
            ReflectedClass(
                name = AndroidCycleNode::class.qualifiedName!!,
                memberNames = emptyList(),
                traverse = { obj, visit ->
                    val node = obj as AndroidCycleNode
                    if (node.rawPtr != 0L) visit(node.rawPtr)
                },
            )
        )
    }

    @AfterTest
    fun tearDown() {
        HandleTable.releaseAll()
    }

    @Test
    fun testCycleCollectionByGC() {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(
                proxyTypeAddr != 0L,
                "Proxy type creation failed -- PyType_FromSpec returned NULL, or proxyCreateType " +
                    "was never registered by JNI_OnLoad",
            )

            // 1. An instance of the proxy type. PyType_FromSpec gave it Py_TPFLAGS_HAVE_GC, so
            //    object.__new__ allocates it through the GC and tracks it.
            val pyObjPtr = bindings.PyObject_CallObjectN(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            // 2. A Kotlin object, rooted in the handle table -- a root CPython cannot see, and
            //    therefore the half of the cycle only tp_clear can drop.
            val node = AndroidCycleNode()
            val handle = HandleTable.register(node).raw

            // 3. Store the handle in the proxy's relative type data (negative basicsize, reached
            //    through PyObject_GetTypeData on the C side).
            ProxyTypeFactory.setHandle(pyObjPtr, handle)
            assertEquals(
                handle, ProxyTypeFactory.handleOf(pyObjPtr),
                "the handle did not survive the round trip through relative type data",
            )

            // 4. Close the loop: the Kotlin object now holds the Python object that holds it.
            node.ref = PyObject(assertNotNull(pyObjPtr.toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before collection")

            // 5. Collect.
            //
            // The return value matters. PyRun_SimpleString fails immediately if the error
            // indicator is already set, so an exception left behind by an earlier test would make
            // gc.collect() silently not run -- which looks exactly like "the object was not
            // tracked", since tp_traverse only runs during a collection.
            val gcRc = PythonOnDevice.withUtf8("import gc; gc.collect()") {
                bindings.PyRun_SimpleStringN(it)
            }
            assertEquals(
                0, gcRc,
                "gc.collect() did not run -- the error indicator was already set on entry " +
                    "(errorIndicatorSet=${bindings.PyErr_Occurred() != 0L})",
            )

            // 6. tp_clear ran if and only if the collector saw the cycle, and tp_clear is the
            //    only thing that releases this handle.
            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected: the handle is still live after gc.collect()",
            )
        }
    }

    /**
     * The negative control for [testCycleCollectionByGC].
     *
     * Built identically, except the Kotlin class is not in [ClassLookup], so `tp_traverse`
     * reports nothing. The collector then sees a proxy whose one reference is external, decides
     * it is reachable, and never calls `tp_clear` -- so the handle stays live.
     *
     * Without this, [testCycleCollectionByGC] could pass for the wrong reason: `resolveRaw`
     * returning null proves the handle was released, not that *the collector* released it.
     */
    @Test
    fun testHandleSurvivesWhenTraverseReportsNothing() {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val pyObjPtr = bindings.PyObject_CallObjectN(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val node = UnregisteredAndroidCycleNode()
            assertNull(
                ClassLookup.find(UnregisteredAndroidCycleNode::class.qualifiedName!!),
                "this control only means anything while the class stays unregistered",
            )
            val handle = HandleTable.register(node).raw

            ProxyTypeFactory.setHandle(pyObjPtr, handle)

            node.ref = PyObject(assertNotNull(pyObjPtr.toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr

            val gcRc = PythonOnDevice.withUtf8("import gc; gc.collect()") {
                bindings.PyRun_SimpleStringN(it)
            }
            assertEquals(0, gcRc, "gc.collect() did not run")

            assertNotNull(
                HandleTable.resolveRaw(handle),
                "the handle was released even though tp_traverse reported no reference -- " +
                    "something other than the cycle collector is clearing handles",
            )
            // Keeps `node` (and with it the Python object) alive across the collection, so the
            // assertion above is about the collector's decision and not about ART's GC.
            assertEquals(pyObjPtr, node.rawPtr)
        }
    }

    /**
     * The JNI upcall is the part of this platform's design that has no counterpart on desktop or
     * Kotlin/Native, so it is worth pinning separately from the collection itself.
     *
     * [ProxyCallbacks.traverse] is what `tp_traverse` calls; calling it directly proves the
     * Kotlin side reports the right pointers, independently of whether the collector ever gets
     * there. A failure here and a pass in [testCycleCollectionByGC] would be contradictory; a
     * pass here and a failure there localises the problem to the C side or to GC tracking.
     */
    @Test
    fun traverseReportsTheHeldPointerForARegisteredClass() {
        val node = AndroidCycleNode()
        node.rawPtr = 0x1234_5678L
        val handle = HandleTable.register(node).raw

        assertTrue(
            python.multiplatform.ffi.ProxyCallbacks.traverse(handle).contentEquals(longArrayOf(0x1234_5678L)),
            "ProxyCallbacks.traverse did not report the pointer the registered traverse visits",
        )

        val unregistered = UnregisteredAndroidCycleNode()
        val unregisteredHandle = HandleTable.register(unregistered).raw
        assertEquals(
            0,
            python.multiplatform.ffi.ProxyCallbacks.traverse(unregisteredHandle).size,
            "an unregistered class must report nothing",
        )

        assertEquals(
            0, python.multiplatform.ffi.ProxyCallbacks.traverse(0L).size,
            "the null handle must report nothing",
        )
    }
}
