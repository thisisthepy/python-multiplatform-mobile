# wasm-experiment

Standalone. Not part of the main build, and on a newer Kotlin (2.4.10) than the library pins
(2.0.20), because what it tests is Kotlin/Wasm's module and memory ABI rather than this library.

It decides the shape of ROADMAP §10. Conclusions live in `docs/wasm-design.md`; this file records
how to reproduce them and what each result was.

```bash
./native/run.sh          # needs ~/emsdk; uses the Node that Gradle downloaded
```

## Results

**Test A — `@WasmImport` binds to an Emscripten module's export.** PASS.
Kotlin called `add_two` compiled by `emcc` and got 42. The value handed to the import object
prints as `function 2() { [native code] }` — a raw wasm export, not a JS wrapper — so the call is
wasm-to-wasm with no JS frame, as the compiler's own `callingWasmDirectly.kt` test intends.

**Test B — Emscripten can import the memory Kotlin exports.** PASS, after one patch.

```
C: get_static_message() -> 0x400
Kotlin: readCStringAt(same address) -> "hello-from-the-cpython-side"      PASS
C: alloc_message() -> 0x10628
Kotlin: writeCStringAt(addr, "written-by-kotlin")
C: str_len(addr) -> 17, UTF8ToString(addr) -> "written-by-kotlin"         PASS
```

Both directions. No copying and no JS in the data path.

**The patch, and why it is the whole blocker.** Kotlin emits its memory as `WasmLimits(0, null)`
— no maximum. Wasm requires a supplied memory's limits to sit inside the importer's, and
Emscripten always declares a maximum, so an unbounded memory can never satisfy it:

```
LinkError: memory import has no maximum limit
```

`patch-memory-max.py` rewrites section 5 to `{min: 0, max: 32768}` and everything links. Nothing
else about the pairing needed changing. **This is one value the compiler chooses**, and it is
worth a YouTrack issue rather than a design workaround.

**The linear memory is free to give away.** Measured, not assumed: `min_pages = 0`, all 40 data
segments passive (they feed WasmGC arrays via `array.new_data`), and the page count stays 0
across string interop, collections and exceptions. Only `withScopedMemoryAllocator` grows it —
and it allocates at **address 0**, on top of Emscripten's static data, which is why that function
must never appear in `wasmJsMain`.

## The one structural constraint

A and B cannot be combined in a single instantiation graph while Kotlin owns the memory:

```
Kotlin   @WasmImport      needs Emscripten's exports at instantiation
Emscripten  -sIMPORTED_MEMORY   needs Kotlin's memory before that
```

Wasm imports are supplied up front, so that is a cycle. Three ways out, in order of preference:

1. **Kotlin imports the memory instead.** No cycle — instantiate Emscripten first, then Kotlin
   with both the memory and the functions. `importWasmMemoryInsteadOfExport = isWasmJsTarget`
   exists in the compiler at master but is in no released version.
2. **JS trampolines for the calls, shared memory for the data.** Costs a JS hop per call but
   keeps the data path free, which is the half that dominated every measurement on the other
   platforms.
3. Give up sharing and copy through JS — the original design.
