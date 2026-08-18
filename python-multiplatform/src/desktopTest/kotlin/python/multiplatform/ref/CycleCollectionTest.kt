package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.ProxyTypeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ClassLookup
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import python.native.ffi.bindings
import python.native.ffi.Panama
import python.multiplatform.BuildConfig

class Node(var ref: PyObject? = null) {
    var rawPtr: Long = 0L
    fun close() {
        ref?.close()
        ref = null
    }
}

/**
 * Desktop's copy of the cycle-collection suite. The mechanism differs from the other two -- the
 * slots are Panama upcall stubs here, `staticCFunction` on Kotlin/Native, JNI in
 * `artMain/cinterop/jni_onload.def` on ART -- so the three files are independent implementations
 * of the same claim rather than copies. See `commonTest/README.md`.
 *
 * ### Why there is no `settleJvmFinalisation` here
 *
 * The other two copies carry one, because they also carry `testDeallocOnAThreadCPythonCreated` and
 * `testCycleCollectedOnAThreadCPythonCreated`, which run CPython's collector on a
 * `threading.Thread`. `_t.start()`/`_t.join()` release the GIL, and that is the one window a
 * pending cleaner -- blocked in `PyGILState_Ensure` with a `Py_DecRef` owed for some earlier test's
 * leftover proxy -- can land in. Landing there removes a reference from the proxy type between the
 * two readings, and the test then reports a `tp_dealloc` imbalance that does not exist. It is a
 * real failure on ART (`expected:<5> but was:<2>`, three leftovers) and was diagnosed by probing on
 * iOS.
 *
 * Neither of those two tests exists in this file, and no test that remains gives the GIL up between
 * its readings: [testHandleReleasedWhenProxyDiesWithoutCycle] holds one `withGIL` across the whole
 * measurement and never enters the eval loop. So there is no window here for a leftover to land in,
 * and a settle step would have nothing to guard.
 *
 * The debt itself is still worth not creating, which is why [testCycleCollectionByGC] now closes
 * its own proxy below. If a GIL-releasing test is ever added to this file, the settle-and-assert
 * step from the other two copies has to come with it.
 */
class CycleCollectionTest {

    @BeforeTest
    fun setUp() {
        if (!Python3.isInitialized) Python3.initialize()
        // Registered unconditionally. Nesting this inside the isInitialized check meant it only
        // ran when this test happened to be the one that started the interpreter -- true in
        // isolation, false in the suite. Without it ClassLookup.find returns null and tp_traverse
        // returns early having visited nothing, so the cycle is invisible to CPython's collector
        // and the failure looks like "the object was never tracked".
        run {
            ClassLookup.register(python.multiplatform.reflection.ReflectedClass(
                name = Node::class.qualifiedName!!,
                memberNames = emptyList(),
                traverse = { obj, visit ->
                    val node = obj as Node
                    if (node.rawPtr != 0L) {
                        visit(node.rawPtr)
                    }
                }
            ))
        }
    }
    
    @AfterTest
    fun tearDown() {
        HandleTable.releaseAll()
    }
    
    @Test
    fun testCycleCollectionByGC() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")
            
            // 1. Create a Python object of proxy type
            val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")
            
            // 2. Create a Kotlin Node and register it
            val node = Node()
            val handle = HandleTable.register(node).raw
            
            // 3. Set the handle inside the Python object
            val typeDataPtr = bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr)
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val putLong = unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            putLong.invoke(theUnsafe, typeDataPtr, handle)
            
            // 4. Create a cycle
            val method = Class.forName("python.native.ffi.EmbedAPI_desktopKt").getMethod("toNativePointerFromRaw", Long::class.javaPrimitiveType)
            val nativePointer = method.invoke(null, pyObjPtr)
            val ref = PyObject::class.java.getDeclaredConstructors().first { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(nativePointer, false) as PyObject
            node.ref = ref
            node.rawPtr = pyObjPtr
            
            // Verify it's in HandleTable
            assertNotNull(HandleTable.resolveRaw(handle))
            
            println("Triggering GC, pyObjPtr is $pyObjPtr")

            // 5. Trigger GC
            //
            // The return value matters. PyRun_SimpleString fails immediately if the error
            // indicator is already set, so an exception left behind by an earlier test would make
            // gc.collect() silently not run -- which looks exactly like "the object was not
            // tracked", since tp_traverse is only called during a collection.
            val gcRc = bindings.PyRun_SimpleString("import gc; gc.collect()")
            println("gc.collect() rc = $gcRc, errorIndicatorSet = ${bindings.PyErr_Occurred() != 0L}")
            assertEquals(0, gcRc, "gc.collect() did not run -- the error indicator was already set on entry")
            
            println("After GC")
            
            // 6. Verify collection
            // If tp_clear was called, node.ref should be null, and HandleTable should not have the handle.
            val collectedObj = HandleTable.resolveRaw(handle)
            assertTrue(collectedObj == null, "Node was not collected by Python GC!")

            // tp_clear broke the loop but did not free the proxy -- `node` still holds the last
            // reference, and `Node.close()` existed for this and was never called. Left alone, that
            // reference is given back by the JVM cleaner at a moment no test chooses, and only once
            // that thread can take the GIL. Nothing in this file measures across a GIL release, so
            // it lands harmlessly here today (see the class KDoc); the other two copies have a test
            // that does, and on ART that leftover is a reproducible failure. Give it back under the
            // GIL this test is already holding instead.
            node.close()
            node.rawPtr = 0L
        }
    }

    /**
     * The leak `tp_clear` cannot reach: a proxy that dies **without** being in a cycle.
     *
     * `tp_clear` only runs when the cyclic collector decides to break a loop, which is the
     * exceptional case. The ordinary one is a refcount reaching zero, and that goes straight to
     * `tp_dealloc` -- so with no `tp_dealloc` slot the [HandleTable] entry stays rooted for the
     * life of the interpreter, holding its Kotlin object with it. Almost every proxy dies this
     * way, so almost every proxy leaks.
     *
     * The [Node]s here deliberately reference nothing: `rawPtr` stays 0 and `ref` stays null, so
     * there is no cycle for the collector to find and `gc.collect()` is never called. The only
     * thing that can release these handles is `tp_dealloc`.
     *
     * ### The type refcount assertions
     *
     * `tp_dealloc` on a heap type has a second obligation beyond freeing the object: since 3.8
     * every instance holds a strong reference to its type, and the default `subtype_dealloc`
     * releases it. A hand-written `tp_dealloc` that forgets to leaks one type reference per
     * instance; one that releases it twice eventually frees the type out from under the process.
     * Both are checked here by reading the type's reference count out of its object header
     * directly. Reading it rather than calling `sys.getrefcount` keeps the argument's own
     * temporary reference out of the number.
     *
     * ### Two headers, not one
     *
     * `ob_refcnt` sits at offset 0 only on a build with the global lock. Free-threaded CPython
     * lays `PyObject` out differently (`Include/object.h`): offset 0 is `ob_tid`, the owning
     * thread's id, and the count is split between a thread-local `uint32 ob_ref_local` at +12 and
     * an atomic `Py_ssize_t ob_ref_shared` at +16 whose value is shifted left by
     * `_Py_REF_SHARED_SHIFT` (2). `Py_REFCNT` is the sum, and [typeRefCount] reproduces it.
     *
     * An earlier version of this probe read offset 0 unconditionally and therefore reported a
     * thread id on the free-threaded build -- the same ten-digit number for "before" and for
     * "alive", which is what the guard assertion below caught.
     *
     * ### Deferred references have to be materialised before the count means anything
     *
     * Fixing the offset is necessary and not sufficient. A heap type on the free-threaded build
     * carries `_PyGC_BITS_DEFERRED`, and its `ob_ref_shared` is initialised to the deferred
     * sentinel -- measured here on 3.14.7: `ob_gc_bits == 0x41` (`TRACKED | DEFERRED`) and
     * `ob_ref_shared == 0x3ffffffffffffffd`, i.e. a shared count of `PY_SSIZE_T_MAX / 8`. While
     * that bit is set, `PyType_GenericAlloc`'s `_Py_INCREF_TYPE` is a no-op for the owning thread,
     * so creating instances does not move the count at all. Measured: 100 instantiations left all
     * six header words bit-identical, and an eval-loop checkpoint
     * ([Python3.drainPendingReleases]) left them bit-identical too -- there is nothing queued to
     * merge, because the increments were never made.
     *
     * A collection is the one thing that converts deferred references into real ones. After
     * [PyGC_Collect] the same 100 instances show up as exactly 100 on the count, and `tp_dealloc`
     * then gives back exactly 100. So the invariant this test asserts does hold on both builds;
     * free-threaded it is simply not *observable* until a collection has run, which is why one is
     * taken on either side of the instantiation loop below. See
     * `docs/gc-scheduling-investigation.md`.
     *
     * The "rises while alive" assertion is not decoration: it is what proves the probe reads a
     * real refcount, so that the "returns afterwards" assertion cannot pass vacuously on a
     * garbage address -- and it is what caught both of the above.
     */
    @Test
    fun testHandleReleasedWhenProxyDiesWithoutCycle() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val putLong = unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val getLong = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType)
            val getInt = unsafeClass.getMethod("getInt", Long::class.javaPrimitiveType)

            // `Py_REFCNT`, for whichever object header this build actually has. See the KDoc above:
            // offset 0 is `ob_refcnt` only on a build with the global lock.
            fun typeRefCount(): Long =
                if (!BuildConfig.pythonFreeThreaded) {
                    getLong.invoke(theUnsafe, proxyTypeAddr) as Long
                } else {
                    val local = (getInt.invoke(theUnsafe, proxyTypeAddr + 12) as Int).toLong() and 0xFFFF_FFFFL
                    val shared = getLong.invoke(theUnsafe, proxyTypeAddr + 16) as Long
                    local + (shared shr 2)
                }

            // Free-threaded only: materialise whatever deferred references this type already
            // carries, so that `before` and `alive` are read in the same state and the difference
            // between them is the instantiation loop and nothing else. On a build with the global
            // lock the count is always accurate and this would only cost a heap walk.
            if (BuildConfig.pythonFreeThreaded) python.native.ffi.PyGC_Collect()

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = typeRefCount()

            val rounds = 100
            val proxies = LongArray(rounds)
            val handles = LongArray(rounds)

            repeat(rounds) { i ->
                val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
                assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type at round $i")
                proxies[i] = pyObjPtr

                val node = Node() // rawPtr stays 0 and ref stays null: nothing to make a cycle out of
                val handle = HandleTable.register(node).raw
                handles[i] = handle
                putLong.invoke(theUnsafe, bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr), handle)
            }

            assertEquals(
                liveBefore + rounds, HandleTable.liveCount,
                "setup failed: $rounds handles should be rooted while the proxies are alive"
            )
            // The instantiations above were deferred on a free-threaded build; this is what turns
            // them into countable references. Without it `alive` reads exactly `before`.
            if (BuildConfig.pythonFreeThreaded) python.native.ffi.PyGC_Collect()

            val typeRefAlive = typeRefCount()
            assertEquals(
                typeRefBefore + rounds, typeRefAlive,
                "each live instance of a heap type holds one reference to that type, so the count " +
                    "should have risen by exactly $rounds (before: $typeRefBefore, alive: $typeRefAlive). " +
                    "If this fails the ob_refcnt probe below is not reading a refcount and the " +
                    "balance assertion after it would be meaningless"
            )

            // Drop the only reference to each proxy. This takes the refcount to zero, which is
            // tp_dealloc's path and *not* tp_clear's -- the cyclic collector never runs here.
            repeat(rounds) { i -> bindings.Py_DecRef(proxies[i]) }

            val stillRooted = handles.count { HandleTable.resolveRaw(it) != null }
            assertEquals(
                0, stillRooted,
                "$stillRooted of $rounds handles are still rooted after their proxies were " +
                    "deallocated. A proxy that dies without a cycle never runs tp_clear, so " +
                    "without a tp_dealloc slot its HandleTable entry -- and the Kotlin object it " +
                    "holds -- leaks for the life of the interpreter"
            )
            assertEquals(
                liveBefore, HandleTable.liveCount,
                "the table should be back to its starting size once every proxy is gone"
            )
            assertEquals(
                typeRefBefore, typeRefCount(),
                "tp_dealloc must release the instance's reference to its heap type exactly once: " +
                    "a count above $typeRefBefore means it was never released and the type leaks " +
                    "per instance, below means it was released twice and the type will be freed " +
                    "while still in use"
            )
        }
    }

    /**
     * A Python subclass of the proxy heap type is deallocated through CPython's own
     * `subtype_dealloc`, which untracks the instance *before* delegating to the base's
     * `tp_dealloc` -- and [ProxyTypeFactory.tp_dealloc] untracks again, unconditionally. Whether
     * that second untrack is safe decides whether the generated proxy classes can ever subclass
     * this type, which is what `ROADMAP.md` §7 records as the open question blocking real cycle
     * collection.
     *
     * The answer is yes, and CPython says so in the header this repository vendors:
     * `internal/pycore_gc.h` -- *"See also the public PyObject_GC_UnTrack() which accept an object
     * which is not tracked."* The internal variant asserts on it; the public one, which this code
     * calls, is defined to tolerate it.
     *
     * A documented guarantee is not a run, so this exercises the path. The tracking assertion is
     * the part that keeps the test from being vacuous: if the instance were never GC-tracked, no
     * second untrack would happen and the test would pass without testing anything. It fails
     * loudly instead.
     */
    @Test
    fun aPythonSubclassOfTheProxyTypeSurvivesBeingUntrackedTwice() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            // The type object reaches Python as an address, then back to an object through ctypes:
            // there is no other route from a raw `PyTypeObject*` to a usable base class in a script.
            val sys = bindings.PyImport_ImportModule("sys")
            val typeObj = bindings.PyLong_FromLongLong(proxyTypeAddr)
            bindings.PyObject_SetAttrString(sys, "_test_proxy_type_addr", typeObj)
            bindings.Py_DecRef(typeObj)
            bindings.Py_DecRef(sys)

            val script = """
import sys
import ctypes
import gc

proxy_type = ctypes.cast(sys._test_proxy_type_addr, ctypes.py_object).value

class MyProxy(proxy_type):
    pass

instance = MyProxy()

# Without this the test proves nothing: an untracked instance is never untracked twice.
if not gc.is_tracked(instance):
    raise AssertionError('instance is not GC-tracked, so the double untrack never happens')

del instance
gc.collect()
"""
            val rc = bindings.PyRun_SimpleString(script)
            assertEquals(0, rc, "the subclass did not survive collection -- see stderr for the Python side")
        }
    }
}
