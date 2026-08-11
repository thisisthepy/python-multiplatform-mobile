package python.multiplatform.ref

/**
 * `actual` for `commonTest`'s `expect fun forceGC()`, for the instrumented (on-device)
 * compilation. `androidUnitTest` has its own copy: the two are separate compilations of the same
 * target, so each needs its own actual, and only this one ever runs against a live interpreter.
 *
 * The GIL is dropped around `System.gc()` on purpose. The cleaner runs on a *different* thread and
 * has to take the GIL through `PyGILState_Ensure` before it can call `Py_DecRef`; holding it here
 * while waiting for that to happen deadlocks the wait against the thing it is waiting for.
 */
actual fun forceGC() {
    val gilState = python.native.ffi.PyEval_SaveThread()!!
    try {
        System.gc()
        Thread.sleep(200) // bounded wait to let cleaner threads run
    } finally {
        python.native.ffi.PyEval_RestoreThread(gilState)
    }
}
