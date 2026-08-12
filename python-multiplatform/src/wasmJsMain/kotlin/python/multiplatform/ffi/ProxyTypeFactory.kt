package python.multiplatform.ffi

/**
 * Not implemented on `wasmJs`. This is the one part of the surface that is genuinely unbuilt here,
 * rather than built differently.
 *
 * ### Why it is not just a transliteration
 *
 * The other targets hand CPython a raw C function pointer for `tp_traverse`, `tp_clear` and
 * `tp_dealloc` -- `staticCFunction` on Native, an upcall stub on desktop, a `RegisterNatives` entry
 * on Android. Kotlin/Wasm has no way to produce a C function pointer from a lambda at all: a
 * WasmGC reference cannot live in linear memory, which is a spec-level guarantee rather than a
 * maturity gap.
 *
 * ### What it would take, and it is known to work
 *
 * `wasm-experiment/`'s Tests E and F built exactly this and measured it:
 *
 *  * `@WasmExport` puts a Kotlin function in the *wasm* export section with no type adapters, so
 *    what JS receives is an exported wasm function rather than a closure.
 *  * `WebAssembly.Table.prototype.set` accepts it into CPython's own `__indirect_function_table`,
 *    whatever instance produced it. The table index **is** the C function pointer.
 *  * `call_indirect` then reaches Kotlin with no JS in the call path: 3.1 ns, against 10.9 ns for
 *    the `addFunction`-plus-JS-closure route the original design specified.
 *
 * A Kotlin `@WasmExport` registered in a real `PyMethodDef` was called from Python source, saw its
 * arguments intact, and built its return value by calling `PyLong_FromLong` -- a downcall from
 * inside an upcall. So the mechanism is proven; what is missing is the plumbing that turns
 * registration into a build step, plus one hard constraint it imposes.
 *
 * ### The constraint that makes it more than plumbing
 *
 * `call_indirect` is statically typed and does not coerce: calling a `(i32) -> i32` export through
 * a `(i32, i32) -> i32` slot raises `RuntimeError: null function or function signature mismatch` at
 * runtime, not at compile time. So each trampoline shape needs its own `@WasmExport` with exactly
 * the right arity, and registration has to happen from JS at startup (a table index cannot be
 * obtained from inside Kotlin). That is a target-specific initialisation path this target does not
 * have yet -- see ROADMAP §7 and docs/upcall-design.md.
 */
actual object ProxyTypeFactory {
    actual fun createProxyType(): Long =
        throw NotImplementedError(
            "ProxyTypeFactory is not implemented on wasmJs. Upcalls need a Kotlin @WasmExport " +
                "installed into CPython's __indirect_function_table from JS at startup; the " +
                "mechanism is proven in wasm-experiment/ (Tests E and F) but is not wired up here."
        )
}
