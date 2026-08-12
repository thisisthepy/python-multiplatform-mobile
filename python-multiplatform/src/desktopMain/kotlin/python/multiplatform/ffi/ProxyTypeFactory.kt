package python.multiplatform.ffi

import java.lang.invoke.MethodHandles
import python.native.ffi.Panama
import python.native.ffi.bindings
import python.native.ffi.downcallII_I
import python.native.ffi.downcallI_V
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ClassLookup

/** `Py_tp_free` from CPython's `typeslots.h`. Slot ids are ABI, not header-version-dependent. */
private const val PY_TP_FREE = 74

object ProxyType {

    // Resolved once at class initialisation rather than per callback.
    //
    // These used to be looked up inside each slot: Class.forName, getDeclaredField,
    // setAccessible and getMethod on every single call, then Method.invoke with a boxed Long.
    // tp_clear runs only during a collection so that was merely wasteful; tp_dealloc runs on
    // *every* proxy death, which is the common case, so the same shape there would put four
    // reflective lookups on the hot path of the object model.
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val getLongMethod = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType)
    private val putLongMethod =
        unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)

    private fun peekLong(addr: Long): Long = getLongMethod.invoke(theUnsafe, addr) as Long
    private fun pokeLong(addr: Long, value: Long) { putLongMethod.invoke(theUnsafe, addr, value) }

    /**
     * Takes the [HandleTable] root out of [selfPtr]'s handle slot and drops it, returning the
     * handle it found (or 0).
     *
     * Zeroing the slot *before* releasing is what makes the two paths that call this --
     * [tp_clear] on the cyclic route and [tp_dealloc] on the ordinary one -- safe to run one
     * after the other. A cycle takes both: `tp_clear` breaks the loop, the refcount then falls to
     * zero, and `tp_dealloc` follows. The second call finds 0 and does nothing.
     *
     * [HandleTable.release] is independently idempotent (it bumps the slot generation, so a
     * repeat of the same handle no longer matches), which is the backstop rather than the
     * mechanism -- and the reason a double release cannot hijack a slot that has since been
     * reissued to someone else.
     */
    private fun takeHandle(selfPtr: Long): Long {
        val handleRaw = bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (handleRaw == 0L) return 0L
        val handle = peekLong(handleRaw)
        if (handle == 0L) return 0L
        pokeLong(handleRaw, 0L)
        HandleTable.release(python.multiplatform.reflection.ObjectReference(handle))
        return handle
    }

    @JvmStatic
    fun tp_traverse(selfPtr: Long, visitPtr: Long, argPtr: Long): Int {
        // CPython이 이 콜백을 부를 때 이미 GIL을 쥔 상태이므로
        // 다시 PyGILState_Ensure(withGIL)를 호출하지 않는다.
        // 이 콜백 안에서 GIL을 놓아서도 안 된다.
        val handleRaw = bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (handleRaw == 0L) return 0
        val handle = peekLong(handleRaw)

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
        //
        // obj.close() 를 부르면 Py_DecRef 가 불려서 남의 포인터 혹은
        // 자기 자신의 포인터를 이중 해제(Double Free)할 수 있음.
        // CPython의 순환 수집기가 알아서 정리하므로 HandleTable.release() 만 부름.
        takeHandle(selfPtr)
        return 0
    }

    /**
     * `void tp_dealloc(PyObject *self)` -- the slot [tp_clear] is not a substitute for.
     *
     * `tp_clear` runs only when the cyclic collector decides to break a loop. A proxy whose
     * refcount simply reaches zero never goes near it, and that is the ordinary case: cycles are
     * the exception. Without this slot every such proxy left its [HandleTable] entry rooted
     * forever, holding a Kotlin object that nothing could ever reach again.
     *
     * Unlike `tp_clear`, this one owns the object's memory. Overriding `tp_dealloc` replaces
     * CPython's `subtype_dealloc` outright, so everything that function does for a GC'd heap type
     * has to be done here:
     *
     *  - **untrack first.** The type carries `Py_TPFLAGS_HAVE_GC`, so the instance is on the
     *    collector's list. Freeing it while still linked leaves the collector walking released
     *    memory.
     *  - **free through the type's own `tp_free`.** `PyType_GetSlot` is the only way to reach it
     *    under abi3, and it must come from `Py_TYPE(self)` rather than from [proxyTypePtr]: the
     *    type is declared `Py_TPFLAGS_BASETYPE`, so Python code may subclass it and inherit this
     *    very function.
     *  - **release the instance's reference to its type.** Since 3.8 an instance of a heap type
     *    holds one, and `subtype_dealloc` gives it back. Forgetting leaks the type once per
     *    instance; doing it twice frees the type while it is still in use. `CycleCollectionTest`
     *    measures `ob_refcnt` across 100 create/destroy rounds precisely to pin this down.
     *
     * `Py_TYPE` is a macro, so the type is fetched with `PyObject_Type` -- a real stable-ABI
     * function that hands back a *new* reference. Hence the two decrements: one for that
     * temporary, one for the instance's.
     *
     * CPython holds the GIL here, as with the other two slots; nothing in this function may take
     * or release it.
     */
    @JvmStatic
    fun tp_dealloc(selfPtr: Long) {
        if (selfPtr == 0L) return
        val type = bindings.PyObject_Type(selfPtr)

        // A Throwable escaping a Panama upcall stub terminates the VM, and it would do so having
        // already untracked the object but not yet freed it. The release is the only part of this
        // that runs Kotlin logic capable of throwing, so it is the only part guarded; the free
        // below then still happens.
        try {
            bindings.PyObject_GC_UnTrack(selfPtr)
            takeHandle(selfPtr)
        } catch (t: Throwable) {
            // Nowhere to report it: the only channel out of a deallocation is not returning.
        }

        if (type != 0L) {
            val tpFree = bindings.PyType_GetSlot(type, PY_TP_FREE)
            if (tpFree != 0L) {
                downcallI_V(tpFree, selfPtr)
                bindings.Py_DecRef(type) // the reference the instance held on its heap type
            }
            bindings.Py_DecRef(type) // the reference PyObject_Type just handed us
        }
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

        // Four 16-byte PyType_Slot entries -- traverse, clear, dealloc, sentinel -- with room to
        // spare, so adding a fifth does not silently write past the block.
        val slotsAddr = allocateMemory.invoke(theUnsafe, 16L * 8) as Long
        // Slot 0: Py_tp_traverse (71)
        putInt.invoke(theUnsafe, slotsAddr, 71)
        val traverseStub = Panama.createUpcallStubIII_I(MethodHandles.lookup().unreflect(ProxyType::class.java.getMethod("tp_traverse", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)))
        putLong.invoke(theUnsafe, slotsAddr + 8, traverseStub)

        // Slot 1: Py_tp_clear (51)
        putInt.invoke(theUnsafe, slotsAddr + 16, 51)
        val clearStub = Panama.createUpcallStubI_I(MethodHandles.lookup().unreflect(ProxyType::class.java.getMethod("tp_clear", Long::class.javaPrimitiveType)))
        putLong.invoke(theUnsafe, slotsAddr + 24, clearStub)

        // Slot 2: Py_tp_dealloc (52). Not 50 -- that is Py_tp_call, which an earlier comment here
        // named by mistake. Without this the type inherits subtype_dealloc, which frees the
        // object correctly and knows nothing about the handle it was carrying.
        putInt.invoke(theUnsafe, slotsAddr + 32, 52)
        val deallocStub = Panama.createUpcallStubI_V(MethodHandles.lookup().unreflect(ProxyType::class.java.getMethod("tp_dealloc", Long::class.javaPrimitiveType)))
        putLong.invoke(theUnsafe, slotsAddr + 40, deallocStub)

        // Slot 3: sentinel
        putInt.invoke(theUnsafe, slotsAddr + 48, 0)
        putLong.invoke(theUnsafe, slotsAddr + 56, 0L)

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
