@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.multiplatform.ref

import kotlinx.cinterop.*
import python.multiplatform.ffi.ProxyTypeFactory
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.native.ffi.toNativePointer
import python.native.ffi.bindings.PyErr_Occurred
import python.native.ffi.bindings.PyObject as CPyObject
import python.native.ffi.bindings.PyObject_CallObject
import python.native.ffi.bindings.PyObject_GetTypeData
import python.native.ffi.bindings.PyRun_SimpleString
import python.native.ffi.bindings.PyTypeObject
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
class CycleNode {
    var ref: PyObject? = null
    var rawPtr: Long = 0L
}

/**
 * Same shape as [CycleNode], but deliberately never registered with [ClassLookup]. It is the
 * negative control for [CycleCollectionTest.testCycleCollectionByGC]: without a registration
 * `tp_traverse` reports nothing, so the collector cannot see the loop and the handle survives.
 */
class UnregisteredCycleNode {
    var ref: PyObject? = null
    var rawPtr: Long = 0L
}

/**
 * The Kotlin/Native counterpart of `desktopTest`'s `CycleCollectionTest`: a Kotlin object holds a
 * Python object, the Python object holds that Kotlin object through its handle, and the loop is
 * only breakable by CPython's cycle collector reaching across the boundary through the proxy
 * type's `tp_traverse`/`tp_clear`.
 *
 * Lives in `iosSimulatorArm64Test` rather than a shared native test source set because there is
 * no shared one: `src/nativeTest` exists but is not wired into the hierarchy in
 * `build.gradle.kts`, and every native target carries its own copy of the `forceGC()` actual --
 * which would be a duplicate-actual error if `nativeTest` were on the path.
 */
class CycleCollectionTest {

    @BeforeTest
    fun setUp() {
        // Registered unconditionally, and deliberately not behind an "is the interpreter already
        // up?" check. Nesting registration inside such a check makes it run only when this test
        // is the one that starts the interpreter -- true in isolation, false in a full suite run.
        // Without the registration ClassLookup.find returns null, tp_traverse returns early
        // having visited nothing, and the cycle is invisible to CPython's collector. The failure
        // then looks exactly like "the object was never GC-tracked", which is a different bug.
        ClassLookup.register(
            ReflectedClass(
                name = CycleNode::class.qualifiedName!!,
                memberNames = emptyList(),
                traverse = { obj, visit ->
                    val node = obj as CycleNode
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
    fun testCycleCollectionByGC() = PythonTestFixture.withInterpreter {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")
            val proxyType = proxyTypeAddr.toCPointer<PyTypeObject>()
            assertNotNull(proxyType)

            // 1. An instance of the proxy type. PyType_FromSpec gave it Py_TPFLAGS_HAVE_GC, so
            //    object.__new__ allocates it through the GC and tracks it.
            val pyObjPtr = PyObject_CallObject(proxyTypeAddr.toCPointer<CPyObject>(), null)
            assertNotNull(pyObjPtr, "Failed to instantiate proxy type")

            // 2. A Kotlin object, rooted in the handle table -- which is a root CPython cannot
            //    see, and therefore the half of the cycle only tp_clear can drop.
            val node = CycleNode()
            val handle = HandleTable.register(node).raw

            // 3. Store the handle in the proxy's relative type data.
            val typeData: CPointer<LongVar> =
                assertNotNull(PyObject_GetTypeData(pyObjPtr, proxyType), "no type data on the proxy")
                    .reinterpret()
            typeData.pointed.value = handle

            // 4. Close the loop: the Kotlin object now holds the Python object that holds it.
            node.ref = PyObject(assertNotNull(pyObjPtr.toLong().toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr.toLong()

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before collection")

            // 5. Collect.
            //
            // The return value matters. PyRun_SimpleString fails immediately if the error
            // indicator is already set, so an exception left behind by an earlier test would make
            // gc.collect() silently not run -- which looks exactly like "the object was not
            // tracked", since tp_traverse only runs during a collection.
            val gcRc = PyRun_SimpleString("import gc; gc.collect()")
            assertEquals(
                0, gcRc,
                "gc.collect() did not run -- the error indicator was already set on entry " +
                    "(errorIndicatorSet=${PyErr_Occurred() != null})",
            )

            // 6. tp_clear ran if and only if the collector saw the cycle, and tp_clear is the only
            //    thing that releases this handle.
            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected: the handle is still live after gc.collect()",
            )
        }
    }

    /**
     * The negative control for [testCycleCollectionByGC].
     *
     * Built identically, except the Kotlin class is not in [ClassLookup], so `tp_traverse` returns
     * without reporting the reference. The collector then sees a proxy whose one reference is
     * external, decides it is reachable, and never calls `tp_clear` -- so the handle stays live.
     *
     * Without this, [testCycleCollectionByGC] could pass for the wrong reason: `resolveRaw`
     * returning null proves the handle was released, not that *the collector* released it. This
     * test is the other half of that claim, and it is the exact failure the registration comment
     * in [setUp] is about -- an unregistered class produces a live handle that looks like an
     * untracked object.
     */
    @Test
    fun testHandleSurvivesWhenTraverseReportsNothing() = PythonTestFixture.withInterpreter {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            val proxyType = assertNotNull(proxyTypeAddr.toCPointer<PyTypeObject>())

            val pyObjPtr = PyObject_CallObject(proxyTypeAddr.toCPointer<CPyObject>(), null)
            assertNotNull(pyObjPtr, "Failed to instantiate proxy type")

            val node = UnregisteredCycleNode()
            assertNull(
                ClassLookup.find(UnregisteredCycleNode::class.qualifiedName!!),
                "this control only means anything while the class stays unregistered",
            )
            val handle = HandleTable.register(node).raw

            val typeData: CPointer<LongVar> =
                assertNotNull(PyObject_GetTypeData(pyObjPtr, proxyType), "no type data on the proxy")
                    .reinterpret()
            typeData.pointed.value = handle

            node.ref = PyObject(assertNotNull(pyObjPtr.toLong().toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr.toLong()

            val gcRc = PyRun_SimpleString("import gc; gc.collect()")
            assertEquals(0, gcRc, "gc.collect() did not run")

            assertNotNull(
                HandleTable.resolveRaw(handle),
                "the handle was released even though tp_traverse reported no reference -- " +
                    "something other than the cycle collector is clearing handles",
            )
            // Keeps `node` (and with it the Python object) alive across the collection, so the
            // assertion above is about the collector's decision and not about Kotlin's GC.
            assertEquals(pyObjPtr.toLong(), node.rawPtr)
        }
    }
}
