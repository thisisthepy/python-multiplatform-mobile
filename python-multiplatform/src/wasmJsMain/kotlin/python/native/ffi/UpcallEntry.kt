@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi

import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.CallableHandle

/** `METH_VARARGS` from `methodobject.h`. Flag values are ABI, not header-version-dependent. */
private const val METH_VARARGS = 0x0001

/**
 * `struct PyMethodDef { const char *ml_name; PyCFunction ml_meth; int ml_flags; const char *ml_doc; }`
 * -- four 4-byte fields on wasm32, unlike `nativeMain`'s where `ml_meth` alone is a real pointer.
 */
private const val METHODDEF_SIZE = 16

/**
 * The wasmJs half of the upcall boundary: [invokeMethod] is what CPython calls, reached the way
 * `docs/upcall-design.md` names for this target and ROADMAP §11 already measured --
 * `@WasmExport` plus `Table.set`, 3.1 ns/call through `call_indirect`. Everything past `self`/`args`
 * is [UpcallTrampoline], which is `commonMain` and knows nothing about wasm.
 *
 * `@WasmExport` is honoured only in the compilation that produces the `.wasm`, so the export
 * itself cannot live here -- the identical constraint `ProxyTypeFactory.kt` records for its three
 * type slots. `wasmJsTest/.../UpcallExports.kt` is the three-line file an application embedding
 * this library has to write; this object holds everything that could ever need changing.
 *
 * ### A note on the GIL, for callers reading this rather than `nativeMain`'s equivalent
 *
 * [invokeMethod] is reached *from* C (`call_indirect`, with CPython already holding the GIL the
 * way any `PyCFunction` call does), so [handleOf] takes its own unconditional
 * `PyGILState_Ensure`/`Release` pair rather than trusting `withGIL`'s nesting depth -- the same
 * rule `UpcallTrampoline`'s own `attached` documents. [bind], in contrast, is called *from*
 * Kotlin, so it uses the ordinary [python.multiplatform.ffi.withGIL]. Both are safe to call
 * repeatedly on their own; what is not safe, and cost an afternoon to isolate, is a bare C API
 * call made directly by a *caller* after either one returns -- `PyGILState_Release` genuinely
 * detaches the thread here once the nesting count reaches zero, unlike a build where the main
 * thread stays implicitly attached forever. Every raw call after [bind] returns must go through
 * `withGIL` too; `UpcallEntryTest` is written that way for exactly this reason.
 */
object UpcallEntry {

    /**
     * `PyObject *(PyObject *self, PyObject *args)` -- the one C shape argument passing needs, and
     * the shape [bind] builds a `PyMethodDef` around. `self` carries the callable handle as a
     * boxed `PyLong`, the same convention `nativeMain`'s `UpcallEntry.pmInvokeMethod` uses, so that
     * when the generated proxy type lands, `self` takes the handle's place and no new shape
     * appears then either.
     *
     * A bad or unbound `self` becomes [CallableHandle.NONE], which [UpcallTrampoline] rejects with
     * Python's error indicator set -- not a crash, and not a call to whatever handle 0 happens to
     * resolve to.
     */
    fun invokeMethod(selfPtr: Int, argsPtr: Int): Int =
        try {
            // Widened the same way every raw wasm32 address crossing this boundary is: `toUInt()`
            // first so an address at or above 2 GiB does not sign-extend into a negative Long.
            UpcallTrampoline.invoke(handleOf(selfPtr), argsPtr.toUInt().toLong()).toInt()
        } catch (t: Throwable) {
            0
        }

    /**
     * A **new** reference to a Python callable that invokes [handle], or `null`.
     *
     * `PyCFunction_NewEx` takes its own reference to `self`, so the `PyLong` boxed here is
     * released immediately afterwards -- keeping it would leak one integer per bound callable.
     */
    fun bind(handle: Long): NativePointer? = withGIL {
        val boxedSelf = PyLong_FromLongLong(handle) ?: return@withGIL null
        try {
            python.native.ffi.bindings.PyCFunction_NewEx(invokeDef, boxedSelf.toPlatformPointer(), 0)
                .toNativePointerFromRaw()
        } finally {
            Py_DecRef(boxedSelf)
        }
    }

    /**
     * The `PyMethodDef *` every callable [bind] hands out points at, built once.
     *
     * Allocated through CPython's own `malloc` and never freed -- a `PyCFunction` object stores
     * the `PyMethodDef *` it was built from rather than copying it, so freeing this would leave
     * every bound callable pointing at released memory. One struct for the life of the process,
     * exactly as `ProxyTypeFactory`'s `PyType_Spec`/`PyType_Slot`s are.
     */
    private val invokeDef: Int by lazy {
        buildMethodDef("pm_invoke", METH_VARARGS, registerUpcall("pmp_invoke"))
    }

    /** Reads the callable handle out of `self`, under its own GIL -- C calls this with none held. */
    private fun handleOf(selfPtr: Int): Long {
        if (selfPtr == 0) return CallableHandle.NONE.raw
        val state = PyGILState_Ensure()
        return try {
            val raw = PyLong_AsLongLong(NativePointer(selfPtr))
            if (PyErr_Occurred() != null) {
                PyErr_Clear()
                CallableHandle.NONE.raw
            } else {
                raw
            }
        } finally {
            PyGILState_Release(state)
        }
    }

    /**
     * Puts the Kotlin `@WasmExport` named [name] into CPython's `__indirect_function_table` and
     * returns the index, which is the C function pointer -- the same call `ProxyTypeFactory` makes
     * for its three type slots, and the same failure modes.
     */
    private fun registerUpcall(name: String): Int {
        val fp = python.native.ffi.bindings.pmpRegisterUpcall(Wasm.internedUtf8(name))
        if (fp >= 0) return fp
        throw IllegalStateException(
            "could not register wasm export '$name' as an upcall entry point " +
                "(pmpRegisterUpcall returned $fp) -- see ProxyTypeFactory.registerUpcall for what " +
                "each negative code means; the wasmJsTest entry module must declare '$name' as a " +
                "delegating @WasmExport, the same way UpcallExports.kt does"
        )
    }

    private fun buildMethodDef(name: String, flags: Int, meth: Int): Int {
        val def = python.native.ffi.bindings.malloc(METHODDEF_SIZE)
        if (def == 0) throw OutOfMemoryError("malloc($METHODDEF_SIZE) failed for PyMethodDef")
        storeInt(def, Wasm.internedUtf8(name))
        storeInt(def + 4, meth)
        storeInt(def + 8, flags)
        storeInt(def + 12, 0)
        return def
    }

    private fun storeInt(address: Int, value: Int) {
        Pointer(address.toUInt()).storeInt(value)
    }
}
