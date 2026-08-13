package python.multiplatform.ffi.upcall

/** The JNI shim publishes `_pm_bind` but no `_pm_invoke`; see [publishesProxyEntryPoints]'s ART row. */
actual val publishesProxyEntryPoints: Boolean = false
