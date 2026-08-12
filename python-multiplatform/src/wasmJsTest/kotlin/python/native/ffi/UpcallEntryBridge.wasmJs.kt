package python.native.ffi

import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.UpcallTable

/**
 * wasmJs's half of `UpcallEntryTest`'s (`commonTest`) contract.
 *
 * Unlike the other three targets, wasmJs has no separate published *resolver* -- only
 * `pmp_invoke` crosses through `call_indirect` (`@WasmExport` plus `Table.set`, 3.1 ns/call, per
 * ROADMAP §11). So resolution happens in Kotlin, the same [UpcallTable.resolve] every platform's
 * bootstrap ultimately calls, and only the resulting callable -- built by [UpcallEntry.bind] over
 * the real `@WasmExport`ed entry point -- crosses into Python, installed as `_pm_bound`. Every raw
 * C API call here goes through [withGIL], not as a style preference: `UpcallEntry`'s own
 * `PyGILState_Ensure`/`Release` pair genuinely detaches this thread once its nesting count returns
 * to zero, unlike a build where the main thread stays implicitly attached forever.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    val handle = UpcallTable.resolve(name)
    if (!handle.isValid) {
        withGIL { python.multiplatform.ffi.Python3.exec("_pm_bound = None") }
        return false
    }
    val callable = UpcallEntry.bind(handle.raw)
        ?: error("UpcallEntry.bind returned null for a handle UpcallTable.resolve just returned")
    withGIL {
        val globals = PythonTestFixture.mainGlobals()
        try {
            check(PyDict_SetItemString(globals.pointer, "_pm_bound", callable) == 0) {
                "could not install '_pm_bound' into __main__'s globals"
            }
        } finally {
            // PyDict_SetItemString takes its own reference; this one was ours to give up.
            Py_DecRef(callable)
        }
    }
    return true
}

/**
 * Drops the [python.multiplatform.reflection.HandleTable] root behind [handle], reusing the
 * marshaller's own release -- there being no separate published release entry point on this
 * target either.
 */
actual fun releaseUpcallHandle(handle: Long): Int =
    python.multiplatform.ffi.upcall.UpcallTrampoline.releaseObject(handle)
