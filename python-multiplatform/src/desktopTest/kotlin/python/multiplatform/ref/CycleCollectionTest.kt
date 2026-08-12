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

class Node(var ref: PyObject? = null) {
    var rawPtr: Long = 0L
    fun close() {
        ref?.close()
        ref = null
    }
}

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
     * Both are checked here by reading `ob_refcnt`, which is the first field of `PyObject` and
     * fixed by the stable ABI. Reading it directly rather than through `sys.getrefcount` keeps
     * the argument's own temporary reference out of the number.
     *
     * The "rises while alive" assertion is not decoration: it is what proves the probe reads a
     * real refcount, so that the "returns afterwards" assertion cannot pass vacuously on a
     * garbage address.
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
            fun typeRefCount(): Long = getLong.invoke(theUnsafe, proxyTypeAddr) as Long

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
}
