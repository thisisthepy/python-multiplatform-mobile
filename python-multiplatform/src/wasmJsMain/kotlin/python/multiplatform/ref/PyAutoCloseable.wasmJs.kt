package python.multiplatform.ref

import python.native.ffi.NativePointer

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

/**
 * Explicit `close()` only. **There is no automatic reclamation on this target.**
 *
 * Every other platform has a GC hook to hang the decref on -- `java.lang.ref.Cleaner` on desktop,
 * a `PhantomReference` on older Android, `kotlin.native.ref.createCleaner` on iOS and androidNative.
 * The Kotlin/Wasm stdlib exposes no equivalent: WasmGC has no finalisation API, and the JS
 * `FinalizationRegistry` is not bound by the stdlib. Reaching it would mean handing the wrapper to
 * JS as a `JsReference`, which is unmeasured and would put a JS object in the lifetime path of
 * every `PyObject` -- so it is left undone rather than done speculatively.
 *
 * The consequence is a real limitation, recorded rather than papered over: a `PyObject` that is
 * dropped without `close()` leaks its reference on `wasmJs`, where it would have been collected
 * elsewhere. `use { }` and the explicit-close paths behave identically to every other platform.
 *
 * No atomics: `-pthread` is prohibited by `pyemscripten_2026_0`, so this target is single-threaded
 * and a plain flag is sufficient where `NativePlatformCleaner` needs an `AtomicInt`.
 */
actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner = WasmPlatformCleaner(pointer, closeAction)

private class WasmPlatformCleaner(
    private val pointer: NativePointer,
    private val closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            closeAction(pointer)
        }
    }
}
