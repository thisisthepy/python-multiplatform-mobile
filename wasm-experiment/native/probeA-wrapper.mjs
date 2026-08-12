// Exposes an Emscripten module's export as a plain ES module binding, which is the shape
// @WasmImport(module, name) resolves against. Mirrors the compiler's own callingWasmDirectly.kt
// test, except the exporting module is Emscripten output rather than a hand-built one.
//
// The top-level await here is what makes Test C work at all: ES module evaluation is depth-first,
// so by the time Kotlin's generated `wasm-experiment.import-object.mjs` (which imports this file)
// evaluates its own body, Emscripten is fully instantiated and `wasmMemory` is a real
// WebAssembly.Memory. That is the whole ordering argument -- Emscripten first, Kotlin second.
import factory from './probeA.mjs';
const m = await factory();

export const add_two = m._add_two;
export const str_len = m._str_len;
export default m._add_two;

// Test C additions: the memory Emscripten defined, and the module itself.
export const wasmMemory = m.wasmMemory;
export const mod = m;
