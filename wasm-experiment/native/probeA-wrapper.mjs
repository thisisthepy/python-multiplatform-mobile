// Exposes an Emscripten module's export as a plain ES module binding, which is the shape
// @WasmImport(module, name) resolves against. Mirrors the compiler's own callingWasmDirectly.kt
// test, except the exporting module is Emscripten output rather than a hand-built one.
import factory from './probeA.mjs';
const m = await factory();
export const add_two = m._add_two;
export const str_len = m._str_len;
