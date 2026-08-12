# wasmJsMain — rules

Kotlin/Wasm reaching CPython 3.14 built for `wasm32-emscripten`, through `@WasmImport` against a
**shared linear memory**. Not a JavaScript bridge: there is no JS frame in either the call path or
the data path.

Everything below is measured. `wasm-experiment/` reproduces the measurements and
`docs/wasm-design.md` records how they were arrived at, including the several conclusions that were
wrong before they were run.

## Never call `withScopedMemoryAllocator`

    // WRONG -- allocates from address 0 upward, on top of Emscripten's static data
    withScopedMemoryAllocator { allocator -> allocator.allocate(n) }

    // RIGHT -- CPython allocates, Kotlin dereferences what it was handed
    val address = Wasm.allocUtf8(value)      // goes through CPython's own malloc
    try { ... } finally { Wasm.freeUtf8(address) }

It is the only allocator Kotlin/Wasm offers, and it assumes it owns the whole memory. Measured: it
grew the memory to two pages and handed back `0x0`. On the memory we share with Emscripten that is
the null page and the static data region, so the first use corrupts CPython's heap.

This is this target's equivalent of desktop's "always `invokeExact`" — the one rule that is not a
preference.

`Pointer(addr)` is the way in. Its constructor is public and `loadByte`/`storeByte`/`loadInt`
compile to plain `i32.load`/`i32.store` with no allocator involved, so reading an address CPython
returned is free.

## The memory is shared because Kotlin *imports* it

Kotlin 2.4.20-Beta2 declares `intrinsics.memory` as an **import** (`min=0, max=none`) rather than
defining one. 2.4.10 and earlier exported one instead, which could not be made to work: an
Emscripten memory import and a Kotlin `@WasmImport` need each other's instantiation to come first.

A wasm memory import accepts any memory whose limits sit inside its own, and an unbounded import
accepts everything — so `intrinsics.memory` takes Emscripten's `Module.wasmMemory` unchanged.
**The whole data-path integration is that one substitution**, done in `build.gradle.kts` against
the generated `*.import-object.mjs`. Emscripten is built exactly as it would be alone: no
`-sIMPORTED_MEMORY`, no binary patching.

(There is now a second substitution, on the generated *entry* module, and it exists for upcalls
rather than for the memory — see "Upcalls work" below. This section used to say "the whole
integration" without qualification, and that stopped being true when `ProxyTypeFactory` was wired
up.)

Handing the memory over costs nothing, because Kotlin never uses it: `min_pages = 0`, all data
segments passive (they feed WasmGC arrays through `array.new_data`), and the page count was
measured at 0 across string interop, collections, exceptions, a 10 MiB `ByteArray`, a 100k-object
graph, the `ArrayBuffer` bridge and coroutines.

It also survives growth. CPython links `-sALLOW_MEMORY_GROWTH`, and the memory *will* grow
underneath Kotlin. A JS `TypedArray` view detaches when that happens; a wasm memory import does not.

## `Py_ssize_t` is 32-bit here, and it is the only ABI divergence

`EmbedAPI.kt` types `Py_ssize_t` as Kotlin `Long`, which is right on every other target. On wasm32
it is `i32`. **Fourteen functions are affected** — `PyList_New/Size/GetItem/SetItem/Insert`,
`PyTuple_New/Size/GetItem/GetSlice/SetItem`, `PyDict_Size`, `PySet_Size`, `PyObject_Size`,
`PyObject_Length` — and `EmbedAPI.wasmJs.kt` converts at the boundary through two named functions
rather than inline casts, because the two directions fail in opposite ways:

- `Int.pySsizeToLong()` **sign-extends**. `-1` is the error return of nearly every function in the
  group, and the unsigned widening that is *correct* for pointers here would turn it into
  4294967295 — a value that passes `if (n < 0)` and is then used as a length.
- `Long.toPySsize()` **range-checks instead of truncating**. `PyList_New(0x1_0000_0000)` would
  otherwise become `PyList_New(0)` and hand back an empty list: a wrong answer rather than a
  failure. Nothing outside `Int` can be legitimate — wasm32's whole address space is 4 GiB.

### Two guards, because the two halves fail differently

Getting the **declaration** wrong is not a compile error. Wasm import types are checked exactly and
at *instantiation*, so a mismatch is a `LinkError` that takes out the whole module: zero tests run
and the message names neither the function nor the type.

    ./gradlew :python-multiplatform:verifyWasmAbiSignatures

parses `python.wasm`'s own type, import, function and export sections and compares every
`@WasmImport` in `bindings.kt` against it. It runs before `wasmJsNodeTest` automatically. Deliberately
breaking one declaration produces:

    wasmJs ABI mismatch -- 1 problem(s). Each is a LinkError at instantiation ...
      PyList_Size: bindings.kt declares (i32) -> (i64), python.wasm has (i32) -> (i32)

This is the same shape as `generateDesktopReachabilityMetadata`: derived from the artefact that
decides, on every run, never from a checked-in copy of the answer. It also asserts that every
`external fun` in the file was parsed — an earlier version required an explicit return type and so
silently skipped the three `Unit`-returning declarations, which is precisely the "green test
measuring its own scope" failure ROADMAP §2 records.

Getting the **conversion** wrong links cleanly, passes every test, and is silently wrong for one
value in a million. That half is `wasmJsTest/.../WasmPySsizeTBoundaryTest`.

## Pointers are 32-bit, so widen through `UInt`

    actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer().toUInt().toLong()

A bare `Int.toLong()` sign-extends. Any address at or above 2 GiB has bit 31 set, and Emscripten's
memory can grow to 4 GiB, so that would turn a valid pointer into a negative `Long` that no longer
round-trips.

## Strings: never `decodeToString()` on a large buffer

Measured, both routes ending with a Kotlin `String` built from the same C address:

| ns per read | 27 B | 4000 B |
|---|---|---|
| `CharArray` + `concatToString()` (ASCII) | **103** | 5 875 |
| `ByteArray` + `decodeToString()` | 405 | **58 271** |
| `UTF8ToString` through JS | 125 | 3 827 |

`decodeToString()` costs about 13 ns/byte on long input against 0.2 ns/byte on short. `Wasm`
therefore scans once — deciding length and ASCII-ness together — and decodes into a `CharArray`
either way.

**Do not assume a JS crossing costs a string copy.** Kotlin/Wasm compiles with
`builtins: ['js-string']`, so a Kotlin `String` *is* a JS string. The earlier claim in this
project's design notes that shared memory makes marshalling "disappear" was wrong for exactly that
reason: the copy it expected to save had never existed.

`char*` → `ByteArray` (the `PyBytes` case) is unambiguous, though: shared memory, no decode, and no
JS route that avoids a copy.

## No composed C shim, and none is wanted

The design once specified `pmp_getattr`, `pmp_exec`, `pmp_list_items` and friends to amortise JS
hops. The hops are gone, so composition has to justify itself on crossing count alone. Measured
against the real interpreter, reading one global 200,000 times:

| | ns |
|---|---|
| naive | 259.5 |
| + interned C strings | 185.4 |
| + module/dict hoisted | **65.2** |
| one crossing, direct `@WasmImport` | **2.9** |

75% comes off through pure-Kotlin optimisation. A composed shim could merge the two remaining calls
and save 2.9 ns. Same verdict ROADMAP §6 reached for desktop, with a wider margin — and it removes
a second build pipeline.

## String arguments: two routes, and the reason each is cheap is *not* the reason on Android

Every `actual` taking a `String` used to `malloc`, encode, copy and `free` per call. All 59
argument positions across 49 functions now go through one of two routes, chosen per argument the
way `desktopMain` and `androidMain` choose — and, checked against them, with the identical verdict
on all 59:

    Wasm.internedUtf8    repeated identifiers: attribute, method, module, type names, dict keys
    Wasm.scratchUtf8     arbitrary content: source text, messages, docs, paths, user data

**The judgement carries over; the reasoning behind it does not.** Measured here, 200 000
iterations, `WasmMarshallingOverheadTest`:

| ns per argument | `"version"` (7 chars) | `"some.deeply.qualified.module.name.with_a_long_attribute"` (54) |
|---|---|---|
| `malloc` + `encodeToByteArray` + copy + `free` (what this target used to do) | **82.7** | |
| `malloc` + `free` alone | 13.0 | |
| `encodeToByteArray` alone | 59.2 | |
| `Wasm.scratchUtf8` — reused buffer, direct write | **30.6** | **157.0** |
| `Wasm.internedUtf8` — cache hit | **22.2** | **26.4** |

And end to end, every row back to back in one run so that only the marshalling differs:

| `PyObject_GetAttrString(sys, "version")` | ns |
|---|---|
| through `bindings`, C string allocated per call | 288.8 |
| through `bindings`, scratch | 221.4 |
| through `bindings`, interned | 237.2 |
| **through the `actual`**, C string allocated per call | **258.0** |
| **through the `actual`**, interned | **229.2** |

Three things in there, two of them against what the other platforms would predict:

- **The expensive part was the intermediate `ByteArray`, not the encode.** `encodeToByteArray()`
  plus a copy is 59.2 ns; writing the same bytes straight into linear memory is what makes scratch
  30.6 ns. A WasmGC array allocation per C API call was most of the cost, and shared memory is what
  lets it go — the destination is CPython's own heap, so there is no staging buffer. That is the
  *opposite* of the original design note's claim that shared memory removes a string copy. It
  removes the allocation.
- **Interning's value is entirely in the string length, and the crossover is high.** For a
  7-character name it is worth 8 ns over scratch — nothing like Android's 15x (2238 → 148 ns).
  For a 54-character one it is worth **6x** (157.0 → 26.4), because a cache hit is flat in the
  length and an encode is linear in it. Both are true, and reporting only the short case would have
  argued interning is barely worth having.
- **The end-to-end gap is smaller than the standalone gap**, because the rest of
  `PyObject_GetAttrString` is CPython's own work. 258.0 → 229.2 ns through the `actual`.

One negative result, recorded because the obvious reading of it is wrong: `BenchmarkTest`'s shared
`PyObject_GetAttrString` row **did not move** across this change (376.6 ns before, 384.0 ns after).
That is a comparison between two whole suite runs on a machine doing other things, against a
per-call difference of ~29 ns; the in-run A/B rows above are the measurement, and the cross-run
figure is not usable for a change this size in either direction.

**Do not read `scratchUtf8`'s address as durable.** Four slots, rotating; the fifth call overwrites
the first. Four is chosen against the surface — the most any function here stages at once is three
(`PyErr_WarnExplicit`, `PyImport_ExecCodeModuleWithPathnames`). A re-entrant call that marshals four
more strings while these are live would overwrite them, exactly as on desktop. `allocUtf8`/`freeUtf8`
remains for buffers the caller owns, and `ProxyTypeFactory`'s type name is one.

## No `EXPORTED_FUNCTIONS` list is needed

`-sMAIN_MODULE` is `LINKABLE`, so Emscripten exports the whole Stable ABI and rejects an explicit
list. All 310 C symbols `EmbedAPI.kt` declares were confirmed present in the ABI build's 8287
exports. What the build *does* need is `wasmExports,wasmMemory` added to
`-sEXPORTED_RUNTIME_METHODS`; without them the exports are in the binary but unreachable from JS,
so there is nothing to hand `@WasmImport`. Neither setting is in PEP 783's ABI-sensitive list.

## There is no automatic reclamation

The Kotlin/Wasm stdlib has no finalisation hook — checked directly against
`kotlin-stdlib-wasm-js-2.4.20-Beta2.klib`, which contains no `FinalizationRegistry`, no `WeakRef`
and no `Cleaner`. So `registerCleaner` implements explicit `close()` only, `forceGC()` in
`wasmJsTest` is a genuine no-op, and a `PyObject` dropped without `close()` leaks a reference where
it would have been collected on every other target.

This is the one place where this target is behind rather than merely different. `GCLeakTest`'s
three cases fail here for that reason, and they are left failing rather than weakened.

## Upcalls work, and cycles are collected

`ProxyTypeFactory` builds a real `PyType_FromSpec` heap type with `tp_traverse`, `tp_clear` and
`tp_dealloc` filled by Kotlin. `wasmJsTest/.../WasmCycleCollectionTest` runs the same pair of cases
`desktopTest/CycleCollectionTest` does and both pass: a Kotlin↔Python cycle is broken by
`gc.collect()`, and 100 proxies that die *without* a cycle release their `HandleTable` entries and
leave the heap type's refcount exactly where it started.

The call path has no JavaScript in it. `@WasmExport` produces a raw wasm export;
`WebAssembly.Table.prototype.set` accepts it into CPython's own `__indirect_function_table` — a
funcref is a funcref whatever instance produced it — and the index **is** the C function pointer.
CPython reaches Kotlin through `call_indirect` at 3.1 ns, against 10.9 ns for the
`addFunction`-plus-JS-closure route the original design specified.

### Three things this costs, all of them structural

**1. The executable module must declare the trampolines. The library cannot.**
`@WasmExport` is honoured only in the compilation that produces the `.wasm`. Measured, not inferred:
the identical annotation on the identical function exports from `wasmJsTest` and does not from
`wasmJsMain`, whose klib is linked in — the test binary's export section came out holding
`startUnitTests` and nothing else. So an application has to write three delegating lines itself; see
`ProxyTypeExportNames`, and `wasmJsTest/.../ProxyTypeExports.kt`, which is that file. Having the
Gradle plugin generate it is the obvious next step and is not done.

**2. Registration is a JS startup step, so the build patches the entry module.**
A table index cannot be obtained from inside Kotlin, and `WebAssembly.Table.set` needs the funcref
out of `wasmInstance.exports`. `cpython.mjs` cannot fetch that itself — it is imported *by* Kotlin's
import object, so importing the entry module back would be an ES cycle across a top-level await. The
`doFirst` in `build.gradle.kts` therefore appends `pmpSetKotlinExports(exports)` to the generated
entry module. That is a **second** substitution; this file used to say the integration was one.

**3. Kotlin/Wasm has no `call_indirect`, so calling a C function *pointer* goes through JS.**
`tp_traverse` is handed a `visitproc` and `tp_dealloc` has to reach its type's `tp_free`; both are
table indices. `pmpCallVisit`/`pmpCallFree` in `cpython.mjs` do `table.get(i)(...)`. Both are cold —
inside a cyclic collection, or a proxy's deallocation — and neither is on the downcall path or on
the path CPython uses to *enter* Kotlin.

**`call_indirect` is statically typed and does not coerce.** A `(i32) -> i32` export called through a
`(i32, i32, i32) -> i32` slot raises `RuntimeError: null function or function signature mismatch` at
runtime, not compile time. Trampoline arity is a correctness requirement, which is why the three
signatures are written out in `ProxyType`'s documentation next to the C ones.

Prefer slots CPython invokes directly (`PyType_FromSpec`) over `PyMethodDef` entries: the latter go
through `_PyEM_TrampolineCall`, whose wasm fast path never installs on this toolchain pair (an
upstream defect — the `EM_JS` initialiser needs `wasmTable`/`wasmMemory` before they exist and the
`LinkError` is swallowed by a bare `catch`). The cost is symmetric, though — every C extension pays
the same JS frame, measured at 14 ns over `abs()`.

One incidental trap worth recording: **`Module.UTF8ToString` is not available.** This build's
`-sEXPORTED_RUNTIME_METHODS` lists `wasmExports` and `wasmMemory` and nothing else, deliberately,
because both are outside PEP 783's ABI-sensitive set. Reaching for any other Emscripten runtime
helper from the glue fails as `undefined is not a function` inside a wasm import, which arrives in
Kotlin as a bare `JsException` with nothing in it. `cpython.mjs` reads C strings off
`M.wasmMemory.buffer` itself.

## Running the tests

    ./gradlew :python-multiplatform:wasmJsNodeTest

Needs a CPython Emscripten build; `-PwasmPythonDir=` or `PMP_PYTHON_DIR` override the default at
`/Volumes/macMini/wasm-build/cpython314-abi/...`. The task **skips with a message** rather than
failing when it is absent. `build-cpython-abi.sh` in that directory reproduces the build; it matches
`pyemscripten_2026_0` (PEP 783) closely enough to load a compiled PyPI wheel.

`verifyWasmAbiSignatures` runs first, automatically, and skips the same way. Run it alone when
`bindings.kt` changes — it is a second or two and it turns "0 tests ran, `LinkError`" into a line
naming the function.

Current state: **214 tests, 3 failed** — the three `GCLeakTest` cases below, and nothing else.

`emsdk` is pinned to **5.0.3**, which is what `pyemscripten_2026_0` specifies. Installing another
version replaces `~/emsdk/upstream` **in place** — it is a global setting, not per-project.
