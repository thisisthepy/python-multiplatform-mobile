package python.multiplatform.ffi

import java.lang.invoke.MethodHandles
import python.native.ffi.Panama
import python.native.ffi.bindings
import python.native.ffi.downcallII_I
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ClassLookup

object ProxyType {
    @JvmStatic
    fun tp_traverse(selfPtr: Long, visitPtr: Long, argPtr: Long): Int {
        // CPython이 이 콜백을 부를 때 이미 GIL을 쥔 상태이므로
        // 다시 PyGILState_Ensure(withGIL)를 호출하지 않는다.
        // 이 콜백 안에서 GIL을 놓아서도 안 된다.
        val handleRaw = bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val handle = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType).invoke(theUnsafe, handleRaw) as Long
        
        // These early returns are silent by design -- an unregistered class simply has nothing to
        // traverse. That silence cost an afternoon once: a test registered its class only on the
        // path where it also initialised the interpreter, so in a full suite run traverse returned
        // here and the cycle stayed invisible, which reads identically to "the object was never
        // GC-tracked". If a cycle is not collected, check registration before suspecting the slots.
        val obj = HandleTable.resolveRaw(handle) ?: return 0
        val cls = ClassLookup.find(obj::class.qualifiedName ?: "") ?: return 0
        if (!cls.hasTraverse) return 0
        
        var err = 0
        cls.traverse(obj) { pyObjPtr_ ->
            if (err == 0 && pyObjPtr_ != 0L) {
                val res = downcallII_I(visitPtr, pyObjPtr_, argPtr)
                if (res != 0L) err = res.toInt()
            }
        }
        return err
    }
    
    @JvmStatic
    fun tp_clear(selfPtr: Long): Int {
        // CPython이 이 콜백을 부를 때 이미 GIL을 쥔 상태이므로
        // 다시 PyGILState_Ensure(withGIL)를 호출하지 않는다.
        // 여기서 다른 Python 객체의 참조(refcount)를 섣불리 감소시키면(close 호출 등) 
        // 이중 해제 버그가 발생할 수 있으므로 Kotlin 측 연결(HandleTable)만 해제한다.
        val handleRaw = bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val handle = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType).invoke(theUnsafe, handleRaw) as Long
        
        if (handle != 0L) {
            // obj.close() 를 부르면 Py_DecRef 가 불려서 남의 포인터 혹은 
            // 자기 자신의 포인터를 이중 해제(Double Free)할 수 있음. 
            // CPython의 순환 수집기가 알아서 정리하므로 HandleTable.release() 만 부름.
            HandleTable.release(python.multiplatform.reflection.ObjectReference(handle))
            unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(theUnsafe, handleRaw, 0L)
        }
        return 0
    }
    
    var proxyTypePtr: Long = 0L
}

actual object ProxyTypeFactory {
    actual fun createProxyType(): Long {
        if (ProxyType.proxyTypePtr != 0L) return ProxyType.proxyTypePtr
        
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocateMemory = unsafeClass.getMethod("allocateMemory", Long::class.javaPrimitiveType)
        val putInt = unsafeClass.getMethod("putInt", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val putLong = unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        
        val slotsAddr = allocateMemory.invoke(theUnsafe, 16L * 4) as Long
        // Slot 0: Py_tp_traverse (71)
        putInt.invoke(theUnsafe, slotsAddr, 71)
        val traverseStub = Panama.createUpcallStubIII_I(MethodHandles.lookup().unreflect(ProxyType::class.java.getMethod("tp_traverse", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)))
        putLong.invoke(theUnsafe, slotsAddr + 8, traverseStub)
        
        // Slot 1: Py_tp_clear (51)
        putInt.invoke(theUnsafe, slotsAddr + 16, 51)
        val clearStub = Panama.createUpcallStubI_I(MethodHandles.lookup().unreflect(ProxyType::class.java.getMethod("tp_clear", Long::class.javaPrimitiveType)))
        putLong.invoke(theUnsafe, slotsAddr + 24, clearStub)
        
        // Slot 2: Py_tp_dealloc (50) - we must have a dealloc that calls PyObject_ClearWeakRefs and tp_free, but wait, PyType_FromSpec provides a default dealloc if not specified? 
        // Let's just define end.
        putInt.invoke(theUnsafe, slotsAddr + 32, 0)
        putLong.invoke(theUnsafe, slotsAddr + 40, 0L)
        
        val specAddr = allocateMemory.invoke(theUnsafe, 32L) as Long
        putLong.invoke(theUnsafe, specAddr, Panama.allocateUtf8String("KotlinProxy"))
        // negative basicsize means it's appended to the end of the base object (PyObject)
        putInt.invoke(theUnsafe, specAddr + 8, -8) // 8 bytes for the handle
        putInt.invoke(theUnsafe, specAddr + 12, 0)
        putInt.invoke(theUnsafe, specAddr + 16, (1 shl 10) or (1 shl 14)) // Py_TPFLAGS_BASETYPE | Py_TPFLAGS_HAVE_GC
        putLong.invoke(theUnsafe, specAddr + 24, slotsAddr)
        
        ProxyType.proxyTypePtr = python.multiplatform.ffi.withGIL {
            bindings.PyType_FromSpec(specAddr)
        }
        return ProxyType.proxyTypePtr
    }
}
