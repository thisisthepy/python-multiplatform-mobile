package python.multiplatform.ffi.upcall

/** Only a *bound* callable crosses into Python here; see [publishesProxyEntryPoints]'s wasmJs row. */
actual val publishesProxyEntryPoints: Boolean = false
