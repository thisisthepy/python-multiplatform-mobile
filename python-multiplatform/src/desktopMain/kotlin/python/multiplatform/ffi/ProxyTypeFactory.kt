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

/** `Py_tp_members` from `typeslots.h`. */
private const val PY_TP_MEMBERS = 72

/** `Py_T_LONG` from `descrobject.h` -- an 8-byte signed integer member, the same width as the
 *  handle slot [ProxyType.takeHandle] already reads and writes by hand. */
private const val PY_T_LONG = 2

/** `Py_RELATIVE_OFFSET` from `descrobject.h` (CPython 3.12+): the member's `offset` is relative to
 *  the defining type's own extra storage rather than to byte 0 of the object, which is what makes
 *  it safe under a Python subclass that adds fields of its own ahead of it in memory. */
private const val PY_RELATIVE_OFFSET = 8

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

        // Room for eight 16-byte PyType_Slot entries; five are used -- traverse, clear, dealloc,
        // members, sentinel -- with room to spare so a sixth does not silently write past the block.
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

        // Slot 3: Py_tp_members (72) -- the handle slot, exposed to Python itself. `PyMemberDef` on
        // every 64-bit target this ships for (macos-aarch64/x86_64, linux-x86_64, windows-x86_64):
        //
        //     struct PyMemberDef { const char *name; int type; Py_ssize_t offset; int flags;
        //                          const char *doc; };
        //
        // name@0(8) type@8(4) [pad 4] offset@16(8) flags@24(4) [pad 4] doc@32(8) = 40 bytes,
        // and the array needs a second, all-zero entry -- PyMemberDef's own required NUL-name
        // terminator, the same contract PyMethodDef and PyType_Slot arrays already carry here.
        //
        // `Py_RELATIVE_OFFSET` (3.12+, `descrobject.h`) is what makes `offset = 0` mean "the start
        // of *this type's* relative data" rather than "byte 0 of the object" -- the same base
        // `PyObject_GetTypeData` computes in `takeHandle`/`tp_traverse` above, so a Python-level
        // `self._pm_handle = h` on an instance of this type (or a subclass of it) writes exactly
        // the 8 bytes those two already read and write by hand. Without the flag a member offset is
        // absolute, and a Python subclass adding its own fields ahead of this one in memory would
        // make every member on every base type in the MRO wrong by however much the subclass added
        // -- which is precisely the multiple/deep-inheritance case this type exists to allow
        // (`Py_TPFLAGS_BASETYPE`, and `work/untrack`'s proof that a subclass survives deallocation).
        val membersAddr = allocateMemory.invoke(theUnsafe, 40L * 2) as Long
        putLong.invoke(theUnsafe, membersAddr, Panama.allocateUtf8String("_pm_handle"))
        putInt.invoke(theUnsafe, membersAddr + 8, PY_T_LONG)
        putLong.invoke(theUnsafe, membersAddr + 16, 0L)
        putInt.invoke(theUnsafe, membersAddr + 24, PY_RELATIVE_OFFSET)
        putLong.invoke(theUnsafe, membersAddr + 32, 0L)
        putLong.invoke(theUnsafe, membersAddr + 40, 0L) // sentinel: name
        putInt.invoke(theUnsafe, membersAddr + 48, 0) // sentinel: type
        putLong.invoke(theUnsafe, membersAddr + 56, 0L) // sentinel: offset
        putInt.invoke(theUnsafe, membersAddr + 64, 0) // sentinel: flags
        putLong.invoke(theUnsafe, membersAddr + 72, 0L) // sentinel: doc

        putInt.invoke(theUnsafe, slotsAddr + 48, PY_TP_MEMBERS)
        putLong.invoke(theUnsafe, slotsAddr + 56, membersAddr)

        // Slot 4: sentinel
        putInt.invoke(theUnsafe, slotsAddr + 64, 0)
        putLong.invoke(theUnsafe, slotsAddr + 72, 0L)

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

    /**
     * Publishes [createProxyType]'s type into `__main__` as `_pm_proxy_base`.
     *
     * `PyObject_SetAttrString` takes the type's own address directly as the value -- a
     * `PyTypeObject*` is a valid `PyObject*`, its header is the same one every other object here
     * has -- so this needs no `ctypes` round trip through an integer and back, unlike the
     * once-off proof in `CycleCollectionTest`'s subclass-safety test. `PyObject_SetAttrString`
     * does not steal the reference it is given; the type is never freed anyway (see
     * [createProxyType]'s own spec/slots allocations, held for the process), so nothing here needs
     * an extra incref to keep it alive under `__main__`.
     */
    actual fun installGcBase(): Boolean {
        val type = createProxyType()
        if (type == 0L) return false
        return python.multiplatform.ffi.withGIL {
            val main = bindings.PyImport_AddModule("__main__")
            if (main == 0L) return@withGIL false
            bindings.PyObject_SetAttrString(main, "_pm_proxy_base", type) == 0
        }
    }
}
