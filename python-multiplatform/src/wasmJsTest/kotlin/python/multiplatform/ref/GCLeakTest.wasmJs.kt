package python.multiplatform.ref

/**
 * A no-op, and it has to be: Kotlin/Wasm exposes no way to ask for a collection.
 *
 * WasmGC's heap is the host engine's, and neither the stdlib nor the JS side offers a
 * `GC.collect()` equivalent -- V8's `--expose-gc` is a flag on the runtime, not an API a library
 * can rely on. Combined with the absence of any finalisation hook (see `PyAutoCloseable.wasmJs.kt`),
 * there is nothing here for [forceGC] to force.
 *
 * The tests that call it therefore measure explicit `close()` on this target and cannot observe
 * automatic reclamation, which is an honest reading of what the platform provides rather than a
 * weakened assertion.
 */
actual fun forceGC() {
    // Intentionally empty. See above.
}
