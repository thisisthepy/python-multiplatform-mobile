# WebAssembly (WASM) Feasibility and Design

This document details the feasibility and architectural design for supporting WebAssembly (WASM) in this Kotlin Multiplatform library, specifically addressing the challenges of embedding CPython 3.13 in a Kotlin/Wasm environment.

## 1. Kotlin/Wasm Target Reality

Based on the project's Kotlin version **2.0.20**:
- **Supported Targets:** Kotlin provides both `wasmJs` (for the browser and Node.js with JS host) and `wasmWasi` (for standalone Wasm environments). Both are classified as Alpha in 2.0.20 (though `wasmJs` matures in later 2.x releases).
- **JS Interop:** Kotlin/Wasm uses the WebAssembly Garbage Collection (WasmGC) proposal. It interoperates with JavaScript via `external` declarations and the `JsAny` type hierarchy.
  - **Strings:** Translated seamlessly between Kotlin `String` and JavaScript `string`. They are not passed as `const char*` raw pointers.
  - **Pointers & Opaque Handles:** WebAssembly linear memory pointers (like `PyObject*`) are simply 32-bit integers (`Int` or JS `Number`). They cannot be directly mapped to Kotlin's WasmGC objects. 
  - **Callbacks:** Kotlin lambdas or `@JsExport` functions are exposed as JavaScript function objects, not raw C function pointers.

## 2. The Two Candidate Approaches for `wasmJs`

Since Kotlin/Wasm and a WASM CPython build are separate modules that do not share linear memory, they must communicate through the JS host environment. 

### (a) Pyodide's High-Level JS API
This involves using `pyodide.runPython`, `pyodide.globals`, and `PyProxy` objects.
- **API Coverage:** Almost **none** of the ~330 `expect` C-API functions can be implemented this way. Pyodide abstracts away the C API. 
- **Object Model:** This approach completely destroys the `PyObject`/`PyType` model the library is built on. WASM would have to use `JsAny` wrappers of `PyProxy` instead of `NativePointer`.
- **Verdict:** **Impractical.** It would result in a fundamentally different architecture and semantics for WASM compared to Android, Desktop, and Native.

### (b) Raw CPython C Symbols Exported from a WASM Build
This involves reaching Emscripten's `EXPORTED_FUNCTIONS` (e.g., `Module._PyList_Size`), passing memory addresses as 32-bit integers.
- **API Coverage:** Can implement the entire `expect` surface, mapping 1-to-1 with the Stable ABI.
- **Object Model:** Preserves the exact same `NativePointer` and `PyObject` semantics as other platforms.
- **Per-Call Cost:** High. Every call must cross from Kotlin WasmGC -> JS -> Emscripten Linear Memory. However, since the signature census shows only 14 simple ABI shapes, this crossing can be tightly optimized.

## 3. Does Pyodide Export What We Need?

**No, a stock Pyodide build does not export the full Stable ABI.** 
Pyodide relies on Emscripten's dead-code elimination. Symbols not used by Pyodide's internal bindings or explicitly listed in `EXPORTED_FUNCTIONS` are stripped. Furthermore:
- **CPython 3.13 Status:** The `wasm32-emscripten` build was dropped from official CPython support in 3.13. (Pyodide maintains it out-of-tree). `wasm32-wasi` was promoted to Tier 2 support.
- **Custom Build Required:** To use the raw C API approach, we would need to generate a custom Emscripten build (or a custom Pyodide distribution) that explicitly adds our ~330 required C API symbols to `EXPORTED_FUNCTIONS` (or use `EMSCRIPTEN_KEEPALIVE`).

## 4. Pointer Width and `NativePointer`

WASM32 uses **32-bit pointers**. 
- The current abstraction `data class NativePointer(val address: Any)` uses `Any`. Passing a 32-bit integer pointer into `Any` will box it (as a `Double` on JS or a boxed primitive in WasmGC), adding allocation overhead.
- The `toRawValue(): Long` signature assumes 64-bit addresses. Since a 32-bit unsigned integer fits perfectly into a 64-bit `Long`, the abstraction mathematically holds. However, Kotlin/Wasm code implementing `toRawValue()` will need to cast the JS/Wasm 32-bit `Int` to a Kotlin `Long` carefully to avoid sign-extension bugs.

## 5. Upcalls on WASM (Python calling Kotlin)

Python expects a raw C function pointer (an integer address pointing to executable memory). We can preserve the "routing by data" design from other platforms, but the plumbing is different:
1. We write our ~4 static trampoline shapes in Kotlin and export them to JS using `@JsExport`.
2. In JS glue code, we use Emscripten's `addFunction(jsFunction, signature)` to inject the JS function into Emscripten's WebAssembly function table.
3. `addFunction` returns an integer (the table index). This integer *is* the C function pointer.
4. We pass this integer to CPython (e.g., in `tp_new` or `PyCFunction_NewEx`). When CPython calls it, Emscripten routes the call out to JS, which calls our Kotlin WasmGC function. The context pointer (`self` or `closure`) is passed as an integer, allowing us to route to the correct Kotlin object.

## 6. `wasmWasi`

**Separate implementations are required.** 
Pyodide and Emscripten rely entirely on JavaScript glue code. If the project targets non-browser WASM via `wasmWasi` (pairing with CPython's Tier 2 `wasm32-wasi`), there is no JS host. 
Currently, Kotlin/Wasm cannot dynamically link directly to an arbitrary C WebAssembly module without a JS host bridge, as the Wasm Component Model and native static linking are not yet mature enough in the Kotlin toolchain. Thus, `wasmWasi` is genuinely a second, entirely different port that is currently **blocked** by Kotlin/Wasm tooling limitations.

## 7. Recommendation and Staged Plan

**Recommendation:** Pursue **Approach (b) — Raw C Symbols via JS Bridge** for the `wasmJs` target. It is the only path that preserves the multiplatform object model. Ignore `wasmWasi` until Wasm Component Model linking matures in Kotlin.

**Caveat:** WASM will have permanently higher per-call overhead than Native/JVM due to the WasmGC ↔ JS ↔ Emscripten boundary crossings.

### Staged Plan
1. **Toolchain Customization:** Create a custom Pyodide/Emscripten build script that passes all ~330 `EmbedAPI` functions to Emscripten's `EXPORTED_FUNCTIONS`.
2. **Downcalls (Kotlin to Python):** Write a Kotlin `external` module that binds to `Module._Py*` functions on the Emscripten JS object. Implement `EmbedAPI.wasmJs.kt` to call these.
3. **Upcalls (Python to Kotlin):** Use Emscripten's `addFunction` via JS interop to register 4 static Kotlin `@JsExport` trampolines, returning function pointers to hand to Python.
4. **Pointer Optimization:** If boxing in `NativePointer(val address: Any)` proves too slow in WasmGC, consider refactoring the `expect` class to use a concrete `Long` across all platforms (which JVM/Native already use natively) to avoid allocations.

---

## Addendum: correcting two framing errors

### Kotlin/Native once had a wasm target, and it was removed

The analysis above assumed Kotlin/Wasm is the only route. That is true today, but not for a
fundamental reason.

Kotlin/Native used to ship a `wasm32` target built through LLVM, producing linear-memory
WebAssembly with a C ABI — the same memory model an Emscripten build of CPython uses. That target
could in principle have used `cinterop`, exactly as the iOS target does today. JetBrains
**deprecated it in Kotlin 1.8.20 and removed it in 1.9.20**, in favour of the Kotlin/Wasm
toolchain, which skips LLVM and is built on WasmGC.

The consequence is that the JS bridge is forced by *tooling*, not by WebAssembly itself:

- Kotlin/Wasm is WasmGC-based, so its memory model differs from C's linear memory.
- Kotlin/Wasm has no `cinterop` at all. `wasmJs` offers JS interop; `wasmWasi` offers WASI syscalls.

This project targets Kotlin 2.0.20, so the `wasm32` target is long gone and pinning to 1.9.10 is
not viable — the rest of the toolchain requires 2.0.x.

### The dependency is CPython's own WASM support, not Pyodide

Pyodide is a downstream distribution carrying its own patches. A library that intends to be a
standard multiplatform binding should build CPython from source for a WASM target rather than
depend on Pyodide.

Where CPython's own support actually stands:

| Target | CPython 3.13 | CPython 3.14 |
|---|---|---|
| `wasm32-wasi` | Tier 2 (officially supported) | Tier 2 |
| `wasm32-emscripten` | Not a PEP 11 platform | Tier 3, via PEP 776 |

[PEP 776](https://peps.python.org/pep-0776/) is Active, targets 3.14, and formalises Emscripten at
Tier 3. Three of its details bear directly on this design:

1. **Only static linking is supported.** The PEP states it is only supported to statically link the
   interpreter; dynamic linking is unsupported because `EM_JS` functions behave differently in
   dynamic builds. CPython therefore cannot be dynamically loaded into another WebAssembly module.
2. **The build produces an `.mjs` plus `.wasm` pair.** A JavaScript runtime layer is intrinsic to
   the Emscripten build; it is not something an integration can route around.
3. **No official python.org distribution is mandated** — binaries continue to come from downstream,
   i.e. Pyodide. Avoiding Pyodide as a *dependency* therefore means building CPython from source
   ourselves, not that an upstream binary exists to use instead.

Because Kotlin/Wasm cannot statically link C code, and CPython cannot be dynamically linked into
another module, the browser architecture is necessarily three parts: the CPython Emscripten module,
the Kotlin/Wasm module, and JavaScript coordinating them. That holds regardless of which approach
is taken.

`wasm32-wasi` is the better-supported target on the CPython side, but there is no JavaScript host to
act as glue, and Kotlin/Wasm's `wasmWasi` cannot link a C module. Bridging the two awaits the WASM
Component Model.

### Practical consequence

Official Emscripten support arrives in **CPython 3.14**. This project is pinned to 3.13, where
Emscripten has no PEP 11 status at all. WASM support therefore implies moving the embedded
interpreter to 3.14 or later.
