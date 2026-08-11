package python.multiplatform.ref

actual fun forceGC() {
    val gilState = python.native.ffi.PyEval_SaveThread()!!
    try {
        System.gc()
        Thread.sleep(200) // bounded wait to let cleaner threads run
    } finally {
        python.native.ffi.PyEval_RestoreThread(gilState)
    }
}
