# wasm-experiment

Standalone. Not part of the main build, and on a newer Kotlin (2.4.20-Beta2) than the library
pinned when this started, because what it tests is Kotlin/Wasm's module and memory ABI rather than
this library.

It decides the shape of ROADMAP §10. Conclusions live in `docs/wasm-design.md`; this file records
how to reproduce them and what each result was.

```bash
./native/run.sh                 # needs ~/emsdk; uses the Node that Gradle downloaded
RUN_LEGACY_B=1 ./native/run.sh  # additionally runs the historical Test B (see below)
```

Currently **43 PASS, 0 FAIL**. Tests D and F need a CPython build at `$PMP_PYTHON_DIR`, which
defaults to the ABI-matched one (`/Volumes/macMini/wasm-build/build-cpython-abi.sh`); they skip if
it is absent and pass against the stock build too.

Emscripten is pinned to **5.0.3**, which is what `pyemscripten_2026_0` specifies (Pyodide
`Makefile.envs` at tag `314.0.4`). The experiment does not depend on the version; the pin exists so
this and the CPython build agree.

## Results

**Test A — `@WasmImport` binds to an Emscripten module's export.** PASS.
Kotlin called `add_two` compiled by `emcc` and got 42. The value handed to the import object
prints as `function 2() { [native code] }` — a raw wasm export, not a JS wrapper — so the call is
wasm-to-wasm with no JS frame, as the compiler's own `callingWasmDirectly.kt` test intends.

**Test C — direct calls *and* a shared linear memory, in one instantiation graph.** PASS.
This is the configuration the design wanted and had written off. See below for why it became
available.

```
Emscripten's memory object     Memory, 258 pages
Kotlin's intrinsics.memory     Memory, 258 pages         PASS   same buffer
Kotlin called cAddTwo(40, 2) -> 42                       PASS   @WasmImport, memory shared
C: get_static_message() -> 0x400
Kotlin: readCStringAt(that address) -> "hello-from-the-cpython-side"    PASS
Kotlin: writeCStringAt(malloc'd addr, "written-by-kotlin")
C: str_len -> 17, UTF8ToString -> "written-by-kotlin"     PASS
C: malloc(64 MiB) -> 258 pages -> 1026 pages
   the memory grew, and Kotlin still reads pre-growth addresses and writes past the old end  PASS
```

The growth check matters because CPython links with `-sALLOW_MEMORY_GROWTH
-sINITIAL_MEMORY=20971520`, so it will happen in production. A JS `TypedArray` view detaches on
growth; Kotlin holds the memory as a wasm import and does not.

**Test D — the same two mechanisms against real CPython 3.14.2.** PASS.
Tests A and C use a 4 KB toy compiled by `emcc`. Test D uses a 10 MB `python.wasm` with 8191
exports, linked `-sMAIN_MODULE` with a growable memory, whose instantiation Emscripten's glue owns.

```
python.wasm exports        8191
Kotlin's intrinsics.memory == CPython's memory (320 pages)              PASS
Kotlin: pyExec("answer = 6 * 7; greeting = ...") -> 0                   PASS
Kotlin: pyGlobalInt("answer") -> 42                                     PASS
Kotlin: pyGlobalString("greeting") -> "hello-from-cpython-42"           PASS
Kotlin: pyExec("bytearray(48 MiB)") -> 0,  320 -> 934 pages             PASS
        pre-growth addresses and strings still read correctly           PASS
```

Kotlin wrote the Python source directly into CPython's heap with `Pointer.storeByte` on an address
`malloc` returned, and dereferenced `PyUnicode_AsUTF8`'s `char*` to read the result back.

It needs a CPython build at `$PMP_PYTHON_DIR` relinked with `wasmExports,wasmMemory` added to
`-sEXPORTED_RUNTIME_METHODS` — without those the 8191 exports are in the binary but unreachable from
JS, so there is nothing to hand `@WasmImport`. Neither setting is in PEP 783's ABI-sensitive list.
The test skips if the build is absent. The default is now the ABI-matched build
(`cpython314-abi/...`, 8287 exports); the figures above are from the stock one, and both pass.

**Test E — upcalls: Emscripten-side C re-entering Kotlin/Wasm.** PASS.
The design (§5, §7 step 3) specifies `@JsExport` + a JS closure + `addFunction`. `@WasmExport`
gives a raw wasm export instead, and `WebAssembly.Table.set` accepts it into Emscripten's own
`__indirect_function_table` — so `call_indirect` reaches Kotlin with no JS in the path. JS is used
once, at registration.

```
String(raw.kotlin_upcall_add)  ->  function 5097() { [native code] }
table.grow(1) -> 2;  table.set(2, kotlin_upcall_add) accepted
C: call_fp(2, 40, 2) -> 42                                              PASS
C: call_fp(route, ctx=7, out) -> "routed-through-a-cpython-frame"       PASS
```

10,000,000 indirect calls issued from inside C, so no JS is in the timed loop:

| | ns/call |
|---|---|
| C → C (control, same module) | 0.7 |
| **C → Kotlin, `table.set`** | **3.1** |
| C → Kotlin, `addFunction` + JS closure | 10.9 |

`call_indirect` is statically typed: calling a `(i32)->i32` export through a `(i32,i32)->i32` slot
traps rather than coercing. Trampoline arity is a correctness requirement.

**Test F — the same against the real interpreter, as a `PyMethodDef`.** PASS.
A Kotlin `@WasmExport` registered as an ordinary `PyCFunction`, called from Python source, building
its return value by calling `PyLong_FromLong` — a downcall from inside an upcall.

```
table.grow(1) -> 13518;  table.set(13518, kotlin_pycfunction) accepted
Kotlin: pyExec("cb_result = kotlin_cb(41)") -> 0,  pyGlobalInt("cb_result") -> 42   PASS
type(kotlin_cb).__name__ = "builtin_function_or_method"                            PASS
1000 calls in a Python loop, variadic args, error paths                            PASS
```

It also found an upstream defect. CPython's `PY_CALL_TRAMPOLINE` tries to install a wasm trampoline
and silently keeps its JS fallback, because the `EM_JS` initialiser needs `wasmTable`/`wasmMemory`
before they exist and the resulting `LinkError` is swallowed by a bare `catch (e) {}`. A
four-argument Kotlin export discriminates the two (the wasm one would raise `Handler takes too many
arguments`; the JS one calls it anyway) and says JS. So today every `PyCFunction` call crosses a JS
frame — a C extension's as much as Kotlin's. Measured like for like on `METH_O`:

| | ns/call |
|---|---|
| pure Python `def py_cb(x): return x+1` | 59 |
| C builtin `abs(x)` | 73 |
| Kotlin `kotlin_cb_o(x)` | 87 |

14 ns of that is the crossing. See `docs/wasm-design.md` for the diagnosis and why
`-sIMPORTED_MEMORY` does not fix it.

**Composition, measured against the interpreter.** Reading one global from `__main__`, 200,000
times, each row adding a pure-Kotlin optimisation:

| | ns |
|---|---|
| naive | 259.5 |
| + interned C strings | 185.4 |
| + module/dict hoisted | **65.2** |
| one crossing (`PyErr_Occurred`, direct) | **2.9** |

75% comes off without shipping any C. A composed `pmp_getattr` could merge the two remaining calls
and save 2.9 ns. Same verdict as ROADMAP §6 reached on desktop: **no shim.**

**Test B — Emscripten importing a memory Kotlin exports.** Historical. It passed on Kotlin 2.4.10
after patching the binary's memory maximum, and its premise — that Kotlin *defines and exports* a
linear memory — stopped being true in 2.4.20-Beta2. Kept behind `RUN_LEGACY_B=1`; it will not pass
on the current toolchain, and `patch-memory-max.py` is no longer part of the default run.

## Why the cycle disappeared

On 2.4.10 the two halves could not be combined:

```
Kotlin      @WasmImport         needs Emscripten's exports at instantiation
Emscripten  -sIMPORTED_MEMORY   needs Kotlin's memory before that          -> cycle
```

2.4.20-Beta2 **imports** its linear memory instead of defining it, which reverses the second arrow.
Parsing the binary:

```
import  './probeA-wrapper.mjs' . 'add_two'   kind=0  func         <- direct wasm call
import  'intrinsics' . 'memory'              kind=3  global externref
import  'intrinsics' . 'memory'              kind=2  memory  min=0  max=none
memory definitions                           0
non-function exports                         none
```

`max=none` is what makes this direction trivially satisfiable — a wasm import accepts any memory
whose limits sit inside its own, and an unbounded import accepts everything. The old direction was
unsatisfiable for the mirror-image reason, which is what `patch-memory-max.py` was for.

Instantiation order comes free from ES modules: `wasm-experiment.import-object.mjs` imports
`probeA-wrapper.mjs`, which has a top-level `await factory()`, so Emscripten is fully instantiated
before Kotlin's import object is built. `patch-import-object.py` then makes the only edit the
integration needs:

```js
intrinsics: { memory: new WebAssembly.Memory({ initial: 0 }), … }   // placeholder
                    -> memory: <probeA-wrapper>.wasmMemory
```

Emscripten is compiled exactly as it would be on its own — no `-sIMPORTED_MEMORY`, no binary
patching. Kotlin is the side that adapts.

## Measurements

Node 24, Emscripten 5.0.3, Kotlin 2.4.20-Beta2, one process each.

**Control path** — 10,000,000 calls of the same trivial C function:

| | ns/call |
|---|---|
| direct `@WasmImport` | **5.0** (stable across runs) |
| JS trampoline | 13.6 – 16.9 (2.7x – 3.4x) |

**Data path** — both loops run in Kotlin and end with a Kotlin `String` from the same C address:

| ns per read | 27 bytes | 4000 bytes |
|---|---|---|
| shared: scan to NUL, build nothing | 193 | 1 649 |
| shared: `ByteArray` copy, no decode | 399 | 4 875 |
| shared: `ByteArray` + `decodeToString()` | 405 | **58 271** |
| shared: `CharArray` + `concatToString()` (ASCII) | **103** | 5 875 |
| shared: `StringBuilder.append(Char)` per byte | 311 | 51 348 |
| copied: `UTF8ToString` through JS | 125 | **3 827** |

**The "marshalling disappears" claim does not survive this.** Kotlin/Wasm compiles with
`builtins: ['js-string']`, so a Kotlin `String` *is* a JS string and `UTF8ToString`'s result needs
no conversion — the copy the design expected to pay for was already gone. And
`ByteArray.decodeToString()` is pathological on long input (about 13 ns/byte at 4000 bytes against
0.2 ns/byte at 27), while `concatToString()` on a `CharArray` costs a tenth of it.

**Bulk** — 1000 `i32` out of a C-owned array:

| | ns per element |
|---|---|
| shared memory, `Pointer.loadInt()` | **0.7** |
| through JS, `Module.HEAP32[a >> 2]` | 7.1 |

## Rules this produces for `wasmJsMain`

- **`withScopedMemoryAllocator` must never be called.** Measured: it grows the memory and allocates
  at address `0x0`, on top of Emscripten's static data. CPython allocates; Kotlin only dereferences
  addresses it was given. `Pointer(addr)` is the way in, and its public constructor plus raw
  `i32.load`/`i32.store` members make that free.
- **`char*` → `String`: `CharArray` + `concatToString()` for ASCII, `UTF8ToString` for anything
  long or non-ASCII. Never `decodeToString()` on a large buffer.**
- **`char*` → `ByteArray`: shared memory.** No decode involved, and no JS route that avoids a copy.
- The linear memory is safe to share: `min_pages = 0`, all data segments passive (they feed WasmGC
  arrays via `array.new_data`), and the page count stays 0 across string interop, collections,
  exceptions, a 10 MiB `ByteArray`, a 100k-object graph, the `ArrayBuffer` bridge and coroutines.

## The ABI-matched CPython, and the wheel

`/Volumes/macMini/wasm-build/build-cpython-abi.sh` builds CPython 3.14.2 to match
`pyemscripten_2026_0` (PEP 783) in `cpython314-abi/`, leaving the stock build in `cpython314/` for
comparison. It verifies itself at the end — exported wasm tags, and the platform version.

The claim it proves:

```
$ PYTHONPATH=<extracted wheel> .../cpython314-abi/.../python.sh -c "import pydantic_core; ..."
IMPORTED pydantic_core 2.48.0            # pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl
validate_python(42) -> 42
raised ValidationError
```

and the negative control, the same wheel on the stock build:

```
LinkError: Import #204 "env" "__cpp_exception": tag import requires a WebAssembly.Tag
```

Two things are worth knowing before repeating it:

- **The unwinding ABI is the only thing that gated the wheel.** `-fwasm-exceptions
  -sSUPPORT_LONGJMP=wasm` at compile *and* link; the check is that `python.wasm` grows a tag
  section exporting `__cpp_exception` and `__c_longjmp`. lzma, zstd and OpenSSL are still missing
  and did not matter here.
- **`PYEMSCRIPTEN_PLATFORM_VERSION` cannot be set through the Makefile without corrupting it.**
  `sysconfig` coerces integer-looking values and Python accepts `int('2026_0') == 20260`, so
  `packaging` emits `pyemscripten_20260_wasm32`. The fix is one name in `sysconfig._ALWAYS_STR`,
  which exists for exactly this reason.
