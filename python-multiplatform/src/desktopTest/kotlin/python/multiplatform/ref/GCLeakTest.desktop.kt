package python.multiplatform.ref

/**
 * Nudges the collector and gives the cleaner thread a chance to run.
 *
 * Deliberately does NOT touch the GIL. `Python3.initialize()` parks the main thread state with
 * PyEval_SaveThread, and `withGIL` attaches per call through PyGILState_Ensure/Release, so by
 * the time a test body calls this the thread holds nothing to release. An earlier version
 * called PyEval_SaveThread here anyway and restored it afterwards; mixing raw thread-state
 * parking with the refcounted PyGILState scheme on one thread corrupted the interpreter and
 * crashed the process inside _PyObject_ClearFreeLists during Py_Finalize.
 *
 * Collection is not deterministic on the JVM, so this is best-effort and bounded: the caller
 * loops until it observes what it is waiting for or gives up.
 */
actual fun forceGC() {
    val canary = java.lang.ref.WeakReference(Any())
    var attempts = 0
    while (canary.get() != null && attempts < 20) {
        System.gc()
        Thread.sleep(10)
        attempts++
    }
    // The canary being gone means a collection happened; the cleaner thread still has to be
    // scheduled and run its action, which is a separate event.
    Thread.sleep(50)
}

actual val cleanerReleasesAutomatically: Boolean = true
