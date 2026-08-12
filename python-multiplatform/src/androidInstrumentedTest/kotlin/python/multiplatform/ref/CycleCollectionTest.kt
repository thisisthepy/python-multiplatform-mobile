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
 * Which thread the last `tp_traverse` upcall arrived on.
 *
 * Test-owned, and that is the point: the lambda [CycleCollectionTest.setUp] registers with
 * [ClassLookup] runs *inside* `ProxyCallbacks.traverse`, which itself runs on whichever thread C
 * called the slot from. Recording the thread there therefore needs no hook in production code.
 *
 * Without it, "the handle was released after a `threading.Thread` ran `gc.collect()`" would only
 * show that the release happened, not that it happened on a thread ART had never seen -- which is
 * the whole claim `pmp_attach`'s `AttachCurrentThreadAsDaemon` branch exists to support.
 */
object TraverseThreadProbe {
    @Volatile
    var lastThreadId: Long = -1L

    @Volatile
    var lastThreadName: String? = null

    fun record() {
        val t = Thread.currentThread()
        lastThreadId = t.id
        lastThreadName = t.name
    }

    fun reset() {
        lastThreadId = -1L
        lastThreadName = null
    }
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
                    TraverseThreadProbe.record()
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

    /**
     * The leak `tp_clear` cannot reach: a proxy that dies **without** being in a cycle.
     *
     * Ported from `desktopTest`, and it is the case that matters most in practice. `tp_clear` only
     * runs when the cyclic collector decides to break a loop, which is the exceptional path. The
     * ordinary one is a refcount reaching zero, and that goes straight to `tp_dealloc` -- so with
     * no `tp_dealloc` slot the [HandleTable] entry stays rooted for the life of the interpreter,
     * holding its Kotlin object with it. Almost every proxy dies this way.
     *
     * The [AndroidCycleNode]s here deliberately reference nothing: `rawPtr` stays 0 and `ref`
     * stays null, so there is no cycle for the collector to find and `gc.collect()` is never
     * called. The only thing that can release these handles is `tp_dealloc`.
     *
     * ### The type refcount assertions
     *
     * `tp_dealloc` on a heap type has a second obligation beyond freeing the object: since 3.8
     * every instance holds a strong reference to its type, and the default `subtype_dealloc`
     * releases it. `pmp_proxy_dealloc` in `jni_onload.def` hand-writes that (`PyObject_Type` for
     * the temporary, then two `Py_DecRef`s), so it can leak one type reference per instance by
     * forgetting, or free the type out from under the process by doing it twice. Both are checked
     * by reading `ob_refcnt` through [bindings.obRefCnt] -- the first field of `PyObject`, fixed
     * there by the stable ABI. `sys.getrefcount` cannot answer this: it counts its own argument's
     * temporary reference.
     *
     * The "rises while alive" assertion is not decoration: it is what proves the probe reads a
     * real refcount, so the "returns afterwards" assertion cannot pass vacuously on an address
     * that happens to hold a stable number.
     */
    @Test
    fun testHandleReleasedWhenProxyDiesWithoutCycle() {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = bindings.obRefCnt(proxyTypeAddr)

            val rounds = 100
            val proxies = LongArray(rounds)
            val handles = LongArray(rounds)

            repeat(rounds) { i ->
                val pyObjPtr = bindings.PyObject_CallObjectN(proxyTypeAddr, 0L)
                assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type at round $i")
                proxies[i] = pyObjPtr

                // rawPtr stays 0 and ref stays null: nothing to make a cycle out of.
                val handle = HandleTable.register(AndroidCycleNode()).raw
                handles[i] = handle
                ProxyTypeFactory.setHandle(pyObjPtr, handle)
            }

            assertEquals(
                liveBefore + rounds, HandleTable.liveCount,
                "setup failed: $rounds handles should be rooted while the proxies are alive",
            )
            val typeRefAlive = bindings.obRefCnt(proxyTypeAddr)
            assertEquals(
                typeRefBefore + rounds, typeRefAlive,
                "each live instance of a heap type holds one reference to that type, so the count " +
                    "should have risen by exactly $rounds (before: $typeRefBefore, alive: " +
                    "$typeRefAlive). If this fails the ob_refcnt probe is not reading a refcount " +
                    "and the balance assertion after it would be meaningless",
            )

            // Drop the only reference to each proxy. This takes the refcount to zero, which is
            // tp_dealloc's path and *not* tp_clear's -- the cyclic collector never runs here.
            repeat(rounds) { i -> bindings.Py_DecRefN(proxies[i]) }

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
                typeRefBefore, bindings.obRefCnt(proxyTypeAddr),
                "tp_dealloc must release the instance's reference to its heap type exactly once: " +
                    "a count above $typeRefBefore means it was never released and the type leaks " +
                    "per instance, below means it was released twice and the type will be freed " +
                    "while still in use",
            )
        }
    }

    /**
     * `tp_dealloc` reached from a thread CPython created, which ART has never seen.
     *
     * Every other test here runs the slots on the instrumentation thread, so `pmp_attach`'s
     * `GetEnv` succeeds and the `AttachCurrentThreadAsDaemon` branch beside it is never taken.
     * `ProxyTypeFactory`'s own documentation records that gap. A `threading.Thread` is a bare
     * pthread, so handing the proxy's last reference to one and letting that thread drop it runs
     * `pmp_proxy_dealloc` -> `pmp_release_handle` -> `pmp_attach` on a thread with no JNIEnv.
     *
     * Two things can go wrong and this test tells them apart from a pass:
     *  - the attach fails, `pmp_release_handle` returns early, and the handle stays rooted;
     *  - the attach succeeds but the detach is missed, in which case ART aborts the process with
     *    "Native thread exiting without having called DetachCurrentThread" when the worker exits
     *    -- which shows up as the whole instrumentation run dying, not as this assertion failing.
     *
     * The proxy is parked in `__main__` so that Python owns the only reference and Kotlin keeps
     * none. Rebinding the name on the worker takes the refcount to zero there.
     */
    @Test
    fun testDeallocOnAThreadCPythonCreated() {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = bindings.obRefCnt(proxyTypeAddr)

            val pyObjPtr = bindings.PyObject_CallObjectN(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val handle = HandleTable.register(AndroidCycleNode()).raw
            ProxyTypeFactory.setHandle(pyObjPtr, handle)

            // Hand the reference to __main__, then drop ours: Python now owns the only one.
            val main = PythonOnDevice.withUtf8("__main__") { bindings.PyImport_ImportModuleN(it) }
            assertTrue(main != 0L, "could not import __main__")
            val globals = PythonOnDevice.withUtf8("__dict__") {
                bindings.PyObject_GetAttrStringN(main, it)
            }
            assertTrue(globals != 0L, "could not read __main__.__dict__")
            val setRc = PythonOnDevice.withUtf8("_pmp_thread_proxy") {
                bindings.PyDict_SetItemStringN(globals, it, pyObjPtr)
            }
            assertEquals(0, setRc, "could not park the proxy in __main__")
            bindings.Py_DecRefN(pyObjPtr)
            bindings.Py_DecRefN(globals)
            bindings.Py_DecRefN(main)

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before the drop")

            // The gc.collect() in the worker is not what drops the proxy -- __main__ still holds
            // it at that point, so it is reachable. It is there to make the worker thread
            // *identify itself*: collecting calls tp_traverse on every tracked object in the
            // generation, this proxy included, and the traverse lambda records its thread. Without
            // it the release below would be observable but the thread it happened on would not.
            //
            // t.join() releases the GIL, so the worker really does run both statements.
            TraverseThreadProbe.reset()
            val here = Thread.currentThread().id
            val script = """
                import gc, threading
                def _pmp_drop():
                    global _pmp_thread_proxy
                    gc.collect()
                    _pmp_thread_proxy = None
                _t = threading.Thread(target=_pmp_drop)
                _t.start()
                _t.join()
            """.trimIndent()
            val rc = PythonOnDevice.withUtf8(script) { bindings.PyRun_SimpleStringN(it) }
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThreadId
            assertTrue(
                seen != -1L,
                "no slot callback reached Kotlin at all while the worker ran, so nothing here " +
                    "says which thread the dealloc below happened on",
            )
            assertTrue(
                seen != here,
                "the slots ran on the instrumentation thread (id $here, " +
                    "${TraverseThreadProbe.lastThreadName}), so CPython's worker did not carry " +
                    "the callback and AttachCurrentThreadAsDaemon was still not exercised",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the proxy's last reference was dropped on a thread CPython created, and the " +
                    "handle is still rooted -- pmp_attach could not give tp_dealloc's callback a " +
                    "JNIEnv on a thread ART had never seen",
            )
            assertEquals(liveBefore, HandleTable.liveCount, "the table did not return to its starting size")
            assertEquals(
                typeRefBefore, bindings.obRefCnt(proxyTypeAddr),
                "tp_dealloc ran on a CPython-created thread but did not balance the instance's " +
                    "reference to its heap type",
            )
        }
    }

    /**
     * The collector itself running on a thread CPython created -- `tp_traverse` and `tp_clear`,
     * where [testDeallocOnAThreadCPythonCreated] covers `tp_dealloc`.
     *
     * `tp_traverse` is the harder of the two over JNI: it calls back into Kotlin for a `jlong[]`
     * and reads it with `GetLongArrayElements`, all on a thread that may have been attached a
     * microsecond earlier.
     */
    @Test
    fun testCycleCollectedOnAThreadCPythonCreated() {
        withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val pyObjPtr = bindings.PyObject_CallObjectN(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val node = AndroidCycleNode()
            val handle = HandleTable.register(node).raw
            ProxyTypeFactory.setHandle(pyObjPtr, handle)

            node.ref = PyObject(assertNotNull(pyObjPtr.toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before collection")

            TraverseThreadProbe.reset()
            val here = Thread.currentThread().id
            val script = """
                import gc, threading
                _t = threading.Thread(target=gc.collect)
                _t.start()
                _t.join()
            """.trimIndent()
            val rc = PythonOnDevice.withUtf8(script) { bindings.PyRun_SimpleStringN(it) }
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThreadId
            assertTrue(
                seen != -1L,
                "tp_traverse never reached Kotlin during the worker's collection, so the cycle " +
                    "was invisible to it -- pmp_attach could not produce a JNIEnv there",
            )
            assertTrue(
                seen != here,
                "tp_traverse ran on the instrumentation thread (id $here, " +
                    "${TraverseThreadProbe.lastThreadName}) rather than on CPython's worker, so " +
                    "AttachCurrentThreadAsDaemon was still not exercised",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected when gc.collect() ran on a CPython-created thread, " +
                    "though it is on the instrumentation thread -- tp_traverse or tp_clear did " +
                    "not reach Kotlin through pmp_attach's AttachCurrentThreadAsDaemon branch",
            )
        }
    }
}
