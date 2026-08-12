@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package python.multiplatform.ffi

/**
 * The three `@WasmExport` trampolines CPython's type slots point at.
 *
 * **This file is here rather than in `wasmJsMain` because it has to be.** `@WasmExport` is honoured
 * only in the compilation that produces the `.wasm`; a library is compiled to a klib and linked in,
 * and its annotations do not reach the export section. Measured, not assumed: the identical
 * annotation on the identical function exports from this source set and does not from `wasmJsMain`,
 * where the test binary's export section came out holding `startUnitTests` and nothing else.
 *
 * So this is not test scaffolding. It is the file **an application embedding this library must
 * write for itself**, reproduced here so that the suite exercises the real path rather than a
 * shortcut only tests can take. `ProxyTypeExportNames` documents it as the contract it is.
 *
 * Each body delegates straight to [ProxyType] -- all the logic, and everything that could ever need
 * changing, stays in the library.
 *
 * The arities are load-bearing. `call_indirect` is statically typed and does not coerce, so a
 * trampoline whose shape does not match the C signature of the slot it fills raises
 * `RuntimeError: null function or function signature mismatch` when CPython calls it, at runtime
 * and nowhere near here.
 */

@kotlin.wasm.WasmExport("pmp_tp_traverse")
fun pmpTpTraverse(selfPtr: Int, visitPtr: Int, argPtr: Int): Int =
    ProxyType.traverse(selfPtr, visitPtr, argPtr)

@kotlin.wasm.WasmExport("pmp_tp_clear")
fun pmpTpClear(selfPtr: Int): Int = ProxyType.clear(selfPtr)

@kotlin.wasm.WasmExport("pmp_tp_dealloc")
fun pmpTpDealloc(selfPtr: Int) = ProxyType.dealloc(selfPtr)
