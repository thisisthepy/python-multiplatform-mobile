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
**The whole integration is that one substitution**, done in `build.gradle.kts` against the
generated `*.import-object.mjs`. Emscripten is built exactly as it would be alone: no
`-sIMPORTED_MEMORY`, no binary patching.

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
`PyObject_Length` — and `EmbedAPI.wasmJs.kt` converts at the boundary (`.toInt()` in, `.toLong()`
out, sign-extending because `-1` is the error return).

Getting this wrong is **not** a compile error. Wasm import types are checked exactly at
instantiation, so a mismatch is a `LinkError` that takes out the whole module. `bindings.kt`'s
declarations are therefore read off `python.wasm`'s own type section rather than transliterated
from the `expect`s.

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

**Interning is the optimisation that is actually worth taking, and it is not applied yet.**
`EmbedAPI.wasmJs.kt` allocates and frees a C string on every call that takes one, which is the
259.5 ns row. Attribute and module names are repeated literals; caching their addresses is the
Android fix (2238 ns → 148 ns there) and costs nothing extra here.

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

## Upcalls are proven but not wired up

`ProxyTypeFactory` throws. The mechanism is measured and works — `@WasmExport` produces a raw wasm
export, `WebAssembly.Table.set` accepts it into CPython's own `__indirect_function_table`, and
`call_indirect` reaches Kotlin at **3.1 ns** against 10.9 ns for the `addFunction`-plus-JS-closure
route the original design specified. A Kotlin `@WasmExport` registered in a real `PyMethodDef` was
called from Python and built its return value with `PyLong_FromLong`, a downcall from inside an
upcall.

Two things stop it being a transliteration of the other targets:

* **`call_indirect` is statically typed and does not coerce.** A `(i32) -> i32` export called
  through a `(i32, i32) -> i32` slot raises `RuntimeError: null function or function signature
  mismatch` at runtime, not compile time. Trampoline arity is a correctness requirement.
* **A table index cannot be obtained from inside Kotlin.** Registration has to happen from JS at
  startup, which is a target-specific initialisation path that does not exist yet.

Prefer slots CPython invokes directly (`PyType_FromSpec`) over `PyMethodDef` entries: the latter go
through `_PyEM_TrampolineCall`, whose wasm fast path never installs on this toolchain pair (an
upstream defect — the `EM_JS` initialiser needs `wasmTable`/`wasmMemory` before they exist and the
`LinkError` is swallowed by a bare `catch`). The cost is symmetric, though — every C extension pays
the same JS frame, measured at 14 ns over `abs()`.

## Running the tests

    ./gradlew :python-multiplatform:wasmJsNodeTest

Needs a CPython Emscripten build; `-PwasmPythonDir=` or `PMP_PYTHON_DIR` override the default at
`/Volumes/macMini/wasm-build/cpython314-abi/...`. The task **skips with a message** rather than
failing when it is absent. `build-cpython-abi.sh` in that directory reproduces the build; it matches
`pyemscripten_2026_0` (PEP 783) closely enough to load a compiled PyPI wheel.

`emsdk` is pinned to **5.0.3**, which is what `pyemscripten_2026_0` specifies. Installing another
version replaces `~/emsdk/upstream` **in place** — it is a global setting, not per-project.
