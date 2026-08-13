package python.multiplatform.ffi.upcall

/** `nativeMain`'s `UpcallEntry.publish` installs both names as real `PyMethodDef`-backed builtins. */
actual val publishesProxyEntryPoints: Boolean = true
