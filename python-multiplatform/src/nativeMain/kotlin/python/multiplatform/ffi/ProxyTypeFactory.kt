package python.multiplatform.ffi

/**
 * Not implemented on Kotlin/Native yet.
 *
 * Lives in `nativeMain` rather than `iosMain` because `nativeMain` is shared with androidNative;
 * an actual under `iosMain` alone leaves the androidNative compilation without one, and the
 * failure reads as "expect declaration has no actual in module <commonMain> for Native" from a
 * target nobody was building.
 *
 * The desktop implementation builds the type with `PyType_FromSpec`, a negative basicsize for the
 * relative type data holding the handle, and `Py_TPFLAGS_HAVE_GC` with real tp_traverse/tp_clear
 * slots. Porting it here needs an equivalent for the two upcall stubs, which on Kotlin/Native is
 * `staticCFunction` rather than Panama.
 */
actual object ProxyTypeFactory {
    actual fun createProxyType(): Long =
        TODO("Cycle-collecting proxy type is desktop-only; needs staticCFunction stubs on Native")
}
