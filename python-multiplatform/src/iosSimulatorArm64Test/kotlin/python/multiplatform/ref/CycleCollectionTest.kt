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
import python.native.ffi.bindings.PyDict_SetItemString
import python.native.ffi.bindings.PyImport_ImportModule
import python.native.ffi.bindings.PyObject_GetAttrString
import python.native.ffi.bindings.PyRun_SimpleString
import python.native.ffi.bindings.PyTypeObject
import python.native.ffi.bindings.Py_DecRef
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
 * Which thread the last `tp_traverse` upcall arrived on, as a `pthread_t`.
 *
 * Test-owned, and that is the point: the lambda [CycleCollectionTest.setUp] registers with
 * [ClassLookup] runs *inside* `proxyTraverse`, which itself runs on whichever thread CPython
 * called the slot from. Recording the thread there therefore needs no hook in production code.
 *
 * Without it, "the handle was released after a `threading.Thread` ran `gc.collect()`" would only
 * show that the release happened -- not that a Kotlin/Native callback survived being entered from
 * a thread the runtime had never seen, which is the case `ProxyTypeFactory`'s own documentation
 * records as untested.
 *
 * A plain `var` in a shared `object`: under Kotlin/Native's current memory model that is one
 * global visible from every thread, and every write to it happens with the GIL held.
 */
object TraverseThreadProbe {
    var lastThread: Long = -1L

    fun record() {
        lastThread = platform.posix.pthread_self()?.rawValue?.toLong() ?: 0L
    }

    fun reset() {
        lastThread = -1L
    }
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
                    TraverseThreadProbe.record()
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

    /**
     * The leak `tp_clear` cannot reach: a proxy that dies **without** being in a cycle.
     *
     * Ported from `desktopTest`, and it is the case that matters most in practice. `tp_clear` only
     * runs when the cyclic collector decides to break a loop, which is the exceptional path. The
     * ordinary one is a refcount reaching zero, and that goes straight to `tp_dealloc` -- so with
     * no `tp_dealloc` slot the [HandleTable] entry stays rooted for the life of the interpreter,
     * holding its Kotlin object with it. Almost every proxy dies this way.
     *
     * The [CycleNode]s here deliberately reference nothing: `rawPtr` stays 0 and `ref` stays null,
     * so there is no cycle for the collector to find and `gc.collect()` is never called. The only
     * thing that can release these handles is `tp_dealloc`.
     *
     * ### The type refcount assertions
     *
     * `tp_dealloc` on a heap type has a second obligation beyond freeing the object: since 3.8
     * every instance holds a strong reference to its type, and the default `subtype_dealloc`
     * releases it. Kotlin/Native's `proxyDealloc` hand-writes that (`PyObject_Type` for the
     * temporary, then two `Py_DecRef`s), so it can leak one type reference per instance by
     * forgetting, or free the type out from under the process by doing it twice. Both are checked
     * by reading `ob_refcnt`, the first field of `PyObject` and fixed there by the stable ABI --
     * `sys.getrefcount` cannot be used because it counts its own argument's temporary.
     *
     * The "rises while alive" assertion is not decoration: it is what proves the probe reads a
     * real refcount, so the "returns afterwards" assertion cannot pass vacuously on an address
     * that happens to hold a stable number.
     */
    @Test
    fun testHandleReleasedWhenProxyDiesWithoutCycle() = PythonTestFixture.withInterpreter {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")
            val proxyType = assertNotNull(proxyTypeAddr.toCPointer<PyTypeObject>())

            // The type object's own ob_refcnt, as a plain 64-bit load at the type's address.
            val typeHeader = assertNotNull(proxyTypeAddr.toCPointer<LongVar>())
            fun typeRefCount(): Long = typeHeader.pointed.value

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = typeRefCount()

            val rounds = 100
            val proxies = LongArray(rounds)
            val handles = LongArray(rounds)

            repeat(rounds) { i ->
                val pyObjPtr = assertNotNull(
                    PyObject_CallObject(proxyTypeAddr.toCPointer<CPyObject>(), null),
                    "Failed to instantiate proxy type at round $i",
                )
                proxies[i] = pyObjPtr.toLong()

                // rawPtr stays 0 and ref stays null: nothing to make a cycle out of.
                val handle = HandleTable.register(CycleNode()).raw
                handles[i] = handle
                val typeData: CPointer<LongVar> =
                    assertNotNull(PyObject_GetTypeData(pyObjPtr, proxyType), "no type data").reinterpret()
                typeData.pointed.value = handle
            }

            assertEquals(
                liveBefore + rounds, HandleTable.liveCount,
                "setup failed: $rounds handles should be rooted while the proxies are alive",
            )
            val typeRefAlive = typeRefCount()
            assertEquals(
                typeRefBefore + rounds, typeRefAlive,
                "each live instance of a heap type holds one reference to that type, so the count " +
                    "should have risen by exactly $rounds (before: $typeRefBefore, alive: " +
                    "$typeRefAlive). If this fails the ob_refcnt probe is not reading a refcount " +
                    "and the balance assertion after it would be meaningless",
            )

            // Drop the only reference to each proxy. This takes the refcount to zero, which is
            // tp_dealloc's path and *not* tp_clear's -- the cyclic collector never runs here.
            repeat(rounds) { i -> Py_DecRef(proxies[i].toCPointer<CPyObject>()) }

            val stillRooted = handles.count { HandleTable.resolveRaw(it) != null }
            assertEquals(
                0, stillRooted,
                "$stillRooted of $rounds handles are still rooted after their proxies were " +
                    "deallocated. A proxy that dies without a cycle never runs tp_clear, so " +
                    "without a tp_dealloc slot its HandleTable entry -- and the Kotlin object it " +
                    "holds -- leaks for the life of the interpreter",
            )
            assertEquals(
                liveBefore, HandleTable.liveCount,
                "the table should be back to its starting size once every proxy is gone",
            )
            assertEquals(
                typeRefBefore, typeRefCount(),
                "tp_dealloc must release the instance's reference to its heap type exactly once: " +
                    "a count above $typeRefBefore means it was never released and the type leaks " +
                    "per instance, below means it was released twice and the type will be freed " +
                    "while still in use",
            )
        }
    }

    /**
     * `tp_dealloc` reached from a thread CPython created, which no Kotlin code has ever entered.
     *
     * Every other test here runs the slots on the thread that called into Python, so the
     * Kotlin/Native runtime is already attached to it and the callback is an ordinary one.
     * `ProxyTypeFactory`'s own documentation records the gap: "a Kotlin/Native callback entered
     * from a thread with no attached runtime is a different situation from the one the test
     * covers". A `threading.Thread` is a bare pthread, so handing the proxy's last reference to
     * one and letting that thread drop it is the way to run the slot there.
     *
     * The proxy is parked in `__main__` so that Python owns the only reference; the Kotlin side
     * keeps none. Rebinding the name on the worker thread takes the refcount to zero there, which
     * is `tp_dealloc` on a thread the Kotlin runtime has never seen.
     */
    @Test
    fun testDeallocOnAThreadCPythonCreated() = PythonTestFixture.withInterpreter {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            val proxyType = assertNotNull(proxyTypeAddr.toCPointer<PyTypeObject>())
            val typeHeader = assertNotNull(proxyTypeAddr.toCPointer<LongVar>())

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = typeHeader.pointed.value

            val pyObjPtr = assertNotNull(
                PyObject_CallObject(proxyTypeAddr.toCPointer<CPyObject>(), null),
                "Failed to instantiate proxy type",
            )
            val handle = HandleTable.register(CycleNode()).raw
            val typeData: CPointer<LongVar> =
                assertNotNull(PyObject_GetTypeData(pyObjPtr, proxyType), "no type data").reinterpret()
            typeData.pointed.value = handle

            // Hand the reference to __main__, then drop ours: Python now owns the only one.
            val main = assertNotNull(PyImport_ImportModule("__main__"), "no __main__")
            val globals = assertNotNull(PyObject_GetAttrString(main, "__dict__"), "no __main__.__dict__")
            assertEquals(
                0, PyDict_SetItemString(globals, "_pmp_thread_proxy", pyObjPtr),
                "could not park the proxy in __main__",
            )
            Py_DecRef(pyObjPtr)
            Py_DecRef(globals)
            Py_DecRef(main)

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before the drop")

            // The gc.collect() in the worker is not what drops the proxy -- __main__ still holds
            // it at that point, so it is reachable. It is there to make the worker thread
            // *identify itself*: collecting calls tp_traverse on every tracked object in the
            // generation, this proxy included, and the traverse lambda records its pthread_t.
            // Without it the release below would be observable but the thread it happened on
            // would not.
            //
            // t.join() releases the GIL, so the worker really does run both statements.
            TraverseThreadProbe.reset()
            val here = platform.posix.pthread_self()?.rawValue?.toLong() ?: 0L
            val rc = PyRun_SimpleString(
                """
                import gc, threading
                def _pmp_drop():
                    global _pmp_thread_proxy
                    gc.collect()
                    _pmp_thread_proxy = None
                _t = threading.Thread(target=_pmp_drop)
                _t.start()
                _t.join()
                """.trimIndent()
            )
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThread
            assertTrue(
                seen != -1L,
                "no slot callback reached Kotlin at all while the worker ran, so nothing here " +
                    "says which thread the dealloc below happened on",
            )
            assertTrue(
                seen != here,
                "the slots ran on the thread that has been running Kotlin all along " +
                    "(pthread $here), so CPython's worker did not carry the callback and the " +
                    "unattached-thread case is still untested",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the proxy's last reference was dropped on a thread CPython created, and the " +
                    "handle is still rooted -- tp_dealloc's callback did not reach Kotlin from " +
                    "that thread",
            )
            assertEquals(liveBefore, HandleTable.liveCount, "the table did not return to its starting size")
            assertEquals(
                typeRefBefore, typeHeader.pointed.value,
                "tp_dealloc ran on a CPython-created thread but did not balance the instance's " +
                    "reference to its heap type",
            )
        }
    }

    /**
     * The collector itself running on a thread CPython created -- `tp_traverse` and `tp_clear`,
     * where [testDeallocOnAThreadCPythonCreated] covers `tp_dealloc`.
     *
     * Same cycle as [testCycleCollectionByGC], except `gc.collect()` is called from a
     * `threading.Thread` instead of from the thread that has been running Kotlin all along.
     */
    @Test
    fun testCycleCollectedOnAThreadCPythonCreated() = PythonTestFixture.withInterpreter {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            val proxyType = assertNotNull(proxyTypeAddr.toCPointer<PyTypeObject>())

            val pyObjPtr = assertNotNull(
                PyObject_CallObject(proxyTypeAddr.toCPointer<CPyObject>(), null),
                "Failed to instantiate proxy type",
            )
            val node = CycleNode()
            val handle = HandleTable.register(node).raw
            val typeData: CPointer<LongVar> =
                assertNotNull(PyObject_GetTypeData(pyObjPtr, proxyType), "no type data").reinterpret()
            typeData.pointed.value = handle

            node.ref = PyObject(assertNotNull(pyObjPtr.toLong().toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr.toLong()

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before collection")

            TraverseThreadProbe.reset()
            val here = platform.posix.pthread_self()?.rawValue?.toLong() ?: 0L
            val rc = PyRun_SimpleString(
                """
                import gc, threading
                _t = threading.Thread(target=gc.collect)
                _t.start()
                _t.join()
                """.trimIndent()
            )
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThread
            assertTrue(
                seen != -1L,
                "tp_traverse never reached Kotlin during the worker's collection, so the cycle " +
                    "was invisible to it",
            )
            assertTrue(
                seen != here,
                "tp_traverse ran on the thread that has been running Kotlin all along " +
                    "(pthread $here) rather than on CPython's worker, so the unattached-thread " +
                    "case is still untested",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected when gc.collect() ran on a CPython-created thread, " +
                    "though it is on the thread that has been running Kotlin -- tp_traverse or " +
                    "tp_clear did not reach Kotlin from a thread with no attached runtime",
            )
        }
    }
}
