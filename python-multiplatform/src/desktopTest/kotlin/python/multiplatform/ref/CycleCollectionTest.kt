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
}
