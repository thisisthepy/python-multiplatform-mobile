package python.native.ffi

import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable

/**
 * What the C entry points in `artMain/cinterop/jni_onload.def` call on every upcall.
 *
 * ### Why the boundary runs this way round
 *
 * Desktop turns a Kotlin method into a C function pointer with a Panama upcall stub, and
 * Kotlin/Native does the same with `staticCFunction`. Android has neither: `androidMain` is
 * Kotlin/JVM and the JVM it runs on is ART, which has no Panama. A `PyMethodDef`'s `ml_meth` has
 * to be a real C function pointer, so the entry points are C functions in `jni_onload.def` and
 * *they* call *this*, through `CallStaticLongMethod` against a class registered in `JNI_OnLoad`.
 *
 * That is the same inversion [python.multiplatform.ffi.ProxyCallbacks] already makes for
 * `tp_traverse`/`tp_clear`, and this file is deliberately its twin: same registration mechanism,
 * same `pmp_attach` on the C side, same rule that nothing may be thrown back into C.
 *
 * ### What is *not* here
 *
 * None of the marshalling. Turning a Python argument tuple into an `Array<Any?>` and a Kotlin
 * result back into a `PyObject *` is [UpcallTrampoline], which is `commonMain` and shared with
 * every other platform -- `docs/upcall-design.md`'s "what each platform still owes" is only the
 * address-publishing step, and on Android that step is the C shim plus these three methods.
 *
 * ### Rules these must obey
 *
 * - **Nothing may be thrown.** The caller is C reached from CPython. A Kotlin exception left
 *   pending across a JNI return aborts the ART runtime outright, so each of these is total: a
 *   failure leaves as `0` / `CallableHandle.NONE` / `0`, and the C side turns that into a Python
 *   error indicator. [UpcallTrampoline.invoke] already catches everything; the guards here are for
 *   the call itself, including a `<clinit>` that fails.
 * - **The GIL is taken by [UpcallTrampoline], not here.** CPython holds it when it calls a
 *   `PyCFunction`, but the trampoline still takes its own `PyGILState_Ensure`/`Release` pair
 *   unconditionally -- `commonMain/README.md`'s rule for any entry point reached from C, since the
 *   nesting-depth counter records only scopes Kotlin opened. `PyGILState_Ensure` is reentrant, so
 *   the second one costs a counter bump.
 * - **Reference conventions belong to the trampoline too.** Arguments arrive borrowed and the
 *   result leaves as a new reference; nothing on this file's path touches a refcount.
 */
object UpcallCallbacks {

    /**
     * `pm_invoke`: a resolved handle plus a borrowed `PyObject *` argument tuple, returning a
     * **new** reference (or `0` with Python's error indicator set).
     *
     * @param callableHandle a raw [CallableHandle]; `-1` for a `self` that was not a valid one.
     */
    @JvmStatic
    fun invoke(callableHandle: Long, argsTuple: Long): Long =
        try {
            UpcallTrampoline.invoke(callableHandle, argsTuple)
        } catch (t: Throwable) {
            0L
        }

    /**
     * `_pm_resolve`: a name resolved once, an integer from then on.
     *
     * Returns `CallableHandle.NONE.raw` (-1) for a name nothing claims, exactly as
     * [UpcallTable.resolve] does; raising `AttributeError` is Python's job. Touches no C API, so
     * unlike [invoke] it needs no GIL.
     */
    @JvmStatic
    fun resolve(name: String?): Long =
        try {
            if (name == null) CallableHandle.NONE.raw else UpcallTable.resolve(name).raw
        } catch (t: Throwable) {
            CallableHandle.NONE.raw
        }

    /**
     * `_pm_release`: what a proxy's `tp_dealloc` calls.
     *
     * 1 if it released a live entry, 0 for a handle already released or never issued. A double
     * release must stay a no-op: the slot may belong to someone else by then.
     */
    @JvmStatic
    fun release(objectHandle: Long): Int =
        try {
            UpcallTrampoline.releaseObject(objectHandle)
        } catch (t: Throwable) {
            0
        }
}

/**
 * Android's half of the upcall boundary: the few lines that give Python the *address* of an entry
 * point. Named to match `nativeMain`'s `python.native.ffi.UpcallEntry`, because it is the same
 * thing reached by this platform's own mechanism.
 *
 * The entry points themselves are C (see [UpcallCallbacks] for why), so all that is left here is
 * asking for them to be installed under the GIL.
 */
object UpcallEntry {

    /**
     * Installs `_pm_resolve`, `_pm_bind` and `_pm_release` into [namespace] (a Python dict).
     *
     * That trio is the whole bootstrap: everything after it is Python calling Python objects.
     * `pm_invoke` is deliberately *not* published -- it is only ever reached through the callable
     * `_pm_bind` returns, which is what keeps the handle out of Python's hands as a separate
     * argument.
     *
     * Callable from Kotlin without the GIL; takes it for the duration.
     *
     * @return true if all three landed.
     */
    fun publish(namespace: NativePointer): Boolean {
        // Forces UpcallCallbacks' <clinit> -- and with it UpcallTrampoline's and UpcallTable's --
        // here rather than inside a running upcall. Otherwise the first class initialisation on
        // this path happens on whatever thread CPython called from, while it holds the GIL, which
        // is a strictly worse place for it; ProxyTypeFactory.createProxyType does the same for
        // ProxyCallbacks and for the same reason. Resolving a name nothing claims is a pure
        // lookup that returns -1.
        UpcallCallbacks.resolve(null)
        return withGIL { bindings.upcallPublish(namespace.toRawValue()) != 0 }
    }
}
