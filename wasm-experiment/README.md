# wasm-experiment

Standalone. Not part of the main build, and deliberately on a newer Kotlin (2.4.10) than the
library pins (2.0.20), because the questions it answers are about Kotlin/Wasm's module and memory
ABI rather than about this library's code.

It exists to decide the shape of ROADMAP §10. Results are recorded in `docs/wasm-design.md`;
this file records how to reproduce them.

```bash
cd wasm-experiment
./gradlew wasmJsNodeDevelopmentRun            # runs the linear-memory probe
```

Then inspect the emitted module -- the import/export/memory/data sections are what most of the
findings come from:

```bash
python3 - <<'PY'
f = open("build/compileSync/wasmJs/main/developmentExecutable/kotlin/wasm-experiment.wasm","rb").read()
# section 5 = memory (min pages), 11 = data (passive vs active), 2 = imports, 7 = exports
PY
```

## What it established

| | result |
|---|---|
| `Pointer(addr)` from an arbitrary address | **compiles and runs** -- public constructor, load/store are raw i32 ops |
| Kotlin's linear memory at startup | **0 pages**, all 40 data segments passive (WasmGC, not linear) |
| grows during string interop / collections / exceptions | **no** -- stays 0 |
| grows on explicit `withScopedMemoryAllocator` | yes, to 2 pages, **allocating at address 0** |
| `wasmJs` module memory | **exported**, not imported, through 2.4.10 |

The last row corrects a reading of the compiler at master, where
`importWasmMemoryInsteadOfExport = isWasmJsTarget` makes the module import its memory instead.
That is not in any released version, so the released route to sharing runs the other way: build
CPython with Emscripten's `-sIMPORTED_MEMORY` and hand it the memory Kotlin exports.

That is safe precisely because of rows two and three -- Kotlin has nothing in that memory and
never puts anything there, so long as `withScopedMemoryAllocator` is never called. Row four is
why that caveat is load-bearing: the allocator starts at address 0, on top of where Emscripten
keeps its static data.
