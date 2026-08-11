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

---

# Why WASM is parked, and what it would look like

Recorded so the reasoning survives. Nothing here is implemented.

## The blocker is Kotlin's toolchain, not WebAssembly

Every other platform calls CPython directly — cinterop on iOS and androidNative, Panama on
desktop, JNI on Android. Kotlin/Wasm cannot: `wasmJs` offers only JS interop and `wasmWasi`
only WASI syscalls, and neither can link a C library.

Kotlin/Native once had a `wasm32` target that went through LLVM and emitted linear-memory
wasm — the same memory model Emscripten's CPython uses, which is exactly what would have made
cinterop work. It was deprecated in 1.8.20 and removed in 1.9.20. So the JS bridge is a
consequence of a toolchain decision, not of WebAssembly.

**The deeper barrier is memory, not calls.** Two wasm modules can call each other directly if
the host wires module B's exports to module A's imports at instantiation — JS is needed once,
not per call. But Kotlin/Wasm is WasmGC: its objects live on a managed heap, and it has no way
to read another module's linear memory. CPython's entire API is pointers into exactly that
memory. So JS is not a convenience in the call path — it is where the marshalling has to
happen.

JetBrains has a **Multiple Memory** proposal under consideration that would address this, and a
C-interop example PR against `kotlin-wasm-examples` was opened in March 2024. Neither has a
committed date, and neither appears on the published roadmap, so neither can be planned around.

## emscripten, not wasi

| | `wasm32-emscripten` | `wasm32-wasi` |
|---|---|---|
| CPython support | Tier 3 from 3.14 (PEP 776) | **Tier 2** |
| bridge between the two modules | **JS exists** | none — needs Component Model, which Kotlin does not emit |
| C extension modules | **dlopen works** | no dlopen in preview1; extensions must be linked in |
| matches this project | **browser; the sample already targets `wasmJs`** | server/edge |

The decisive row is the third. This project exists to share libraries, and on WASI a user could
not add a compiled package after the fact — the interpreter would have to be rebuilt. That
guts the goal on that platform, which outweighs WASI's better support tier.

The second row is nearly as decisive: WASI has no JS host, so there is no mechanism at all to
connect a Kotlin/Wasm module to a CPython module there.

## The shape it would take

Put our C code inside CPython's module, so it can handle pointers, and keep the boundary to
integers.

    +- Emscripten module (one linear memory) -----+
    |  CPython + our C shim                       |   pointers handled here
    +---------------------+-----------------------+
                          | i32 only
                    +-----+-----+
                    |  JS glue  |                     string copying only
                    +-----+-----+
                          |
                 +--------+---------+
                 |  Kotlin/Wasm     |
                 +------------------+

Pointers are 32-bit on wasm32, so a `PyObject*` fits in a Kotlin `Int`. The shim exposes
composed operations, the same ones measured on Android:

    int  pmp_getattr(int obj, int namePtr);              // lookup + error handling, one crossing
    int  pmp_exec(int codePtr);                          // __main__ + globals + run
    int  pmp_list_items(int list, int outPtr, int cap);  // bulk: fills a caller buffer
    int  pmp_str_utf8(int obj, int outLenPtr);
    void pmp_decref(int obj);
    int  pmp_scratch(void);                              // reusable buffer address, once

**Interning applies here and matters more.** A Kotlin string lives on the WasmGC heap and a C
string in linear memory, so copying costs a JS hop — but attribute and module names are
repeated literals, so caching the linear-memory address makes every call after the first cost
nothing. This is the same fix measured on Android at 2238 ns → 148 ns, against a hop that is
more expensive here than JNI's.

**Bulk is where composition earns its keep on this platform.** It was rejected on desktop and
superseded by interning on Android; here it is the only mitigation left, because
`Module.HEAP32.subarray(p, p+n)` fetches N items in one hop where per-element access would cost
N.

Nothing above changes the layering: `wasmJsMain` supplies `actual`s for the same EmbedAPI
`expect`s, and the object model does not move.

## What survives if the toolchain improves

If Multiple Memory lands, the C shim stays — composition is wanted for its own sake — and only
the JS glue is replaced by direct memory access. The `actual` signatures do not change. That
bounds the throwaway work to a few dozen lines of glue, which is the argument for this shape
over waiting indefinitely.

## Unresolved

- `NativePointer` holds `Any`, so a wasm32 `Int` boxes on every conversion, and
  `toRawValue(): Long` needs sign-extension care.
- CPython must be built with our shim and an explicit `EXPORTED_FUNCTIONS` list; a stock
  Pyodide build strips the C API by dead-code elimination. That is a second build pipeline.
- Upcalls: `addFunction(jsFn, sig)` gives a callable function pointer, but re-entering
  Kotlin/Wasm from it goes through JS. Unmeasured, and likely worse than every other platform.
- Lifetime: whether Kotlin/Wasm offers a GC hook equivalent to `Cleaner`/`createCleaner` has not
  been checked. Without one, only explicit `close()` works.

## Start condition

Not now. `wasmJsMain`'s `actual`s cannot be written until it is settled what they implement —
§1/§4 (GIL and automatic release) and §7 (upcall shape) are both in flight, and building against
them now means building twice.

## Packaging: a side module, against our own CPython

Two independent choices, easily conflated:

1. **How our C code is packaged** — statically linked into CPython, or a side module loaded by
   `dlopen` at import time.
2. **Where CPython comes from** — built by us with Emscripten, or Pyodide's distribution.

### Side module, on both counts of its own

| | statically linked | **side module** |
|---|---|---|
| changing one binding | rebuild all of CPython | rebuild our module |
| release cadence | tied to CPython's | independent |
| shape | a CPython fork, effectively | an ordinary extension module (`PyInit_*`) |
| runtime | no indirection | dynamic-link indirection (small) |

Having to rebuild the interpreter to change a binding is the same trap this project already hit
elsewhere — a `.def` edit needing a forced link and staging pass before it reached a device. An
extension module is what CPython already has a mechanism for; we are not doing anything unusual.

### Our own CPython, and no attempt at Pyodide compatibility

The earlier draft argued for keeping Pyodide compatibility in reach, on the grounds that Pyodide
*is* the package ecosystem on the web. That argument does not survive contact with how Pyodide
wheels are tagged: `cp312-cp312-pyodide_2024_0_wasm32` pins the Python version, the Emscripten
version **and** Pyodide's own ABI together. What you get is not "the Python ecosystem" but
"packages someone built for exactly this Pyodide release".

So the choice is not ecosystem-versus-no-ecosystem. It is whose tag to be locked to, and
targeting Pyodide would mean the web platform tracking their release cadence while every other
platform is pinned to 3.14 from source. That is a real cost for a conditional benefit.

The loss from building our own is narrower than it first appears:

| | on our own build |
|---|---|
| pure-Python wheels (`py3-none-any`) — no ABI tag | work unchanged |
| C extensions (numpy, pandas, lxml, cryptography) | must be built for our ABI |

And the second row is true of Pyodide too, for anything outside the list they happen to have
built. Pyodide's advantage is a prebuilt catalogue, not a different rule.

**Decision: our own Emscripten build of CPython 3.14, packaged against by a side module.**
Compiled third-party extensions are a known limitation on this platform, recorded rather than
solved.

### Correction: PEP 783 unbinds the tag from Pyodide

The section above is right about the decision and wrong about its cost. It was written from the
`pyodide_2024_0_wasm32` tag and concluded that any wheel compatibility means being locked to a
Pyodide release. That stopped being true.

[PEP 783 — Emscripten Packaging](https://peps.python.org/pep-0783/) is **Accepted**. It replaces
the Pyodide-owned tag with a standard platform tag series that PyPI accepts:

```
pyodide_2024_0_wasm32          Pyodide's tag, Pyodide's platform
pyemscripten_2026_0_wasm32     standard platform tag (PEP 783)
```

Three things follow, and they change the trade-off rather than the conclusion.

**The platform is versioned per Python feature release, not per distribution release.** The
current series is `pyemscripten_2024_0` (3.12), `pyemscripten_2025_0` (3.13), `pyemscripten_2026_0`
(3.14). The earlier worry — "the web platform tracks Pyodide's cadence while every other platform
is pinned to 3.14" — does not apply: the platform version *is* the Python version. Pinning to 3.14
from source and claiming `pyemscripten_2026_0` are the same act. The patch component exists as
an escape hatch the PEP hopes never to use.

**Compatibility is a build recipe, not a distribution.** The PEP states that the tags "can be used
by Python interpreters compiled and linked with the specified version of Emscripten and with the
specified ABI-sensitive flags". So our own build can claim the platform. The ABI-sensitive surface
the PEP enumerates:

| | |
|---|---|
| Emscripten compiler version | pinned per platform version |
| statically linked libraries | fixed set |
| stack unwinding ABI | fixed selection |
| dependency lookup handling | specified |
| `-pthread` | prohibited |
| `-sWASM_BIGINT` | required |

`-sWASM_BIGINT` is worth noting for us specifically: it is what makes an i64 cross the JS boundary
as a BigInt rather than being split into two i32s, which is the same `NativePointer` width question
§4 above raises. The platform requires the setting we would have wanted anyway.

**The full specification currently lives in Pyodide's documentation**, which the PEP references
normatively while noting the wording was chosen to be "more forwards compatible to a future where
the definition of the platform moves upstream". So Pyodide still *authors* the spec even though it
no longer *owns* the tag. That is a real residual coupling, but it binds a flag list, not a runtime.

#### What this changes

| | earlier draft | with PEP 783 |
|---|---|---|
| what the tag pins | Pyodide release | Python version + a published flag set |
| our own build + C-extension wheels | mutually exclusive | **compatible, if we match the flags** |
| Pyodide as a dependency | the price of wheels | not required |
| pure-Python wheels | work unchanged | work unchanged |

The decision stands — our own Emscripten CPython 3.14 — but the recorded limitation was
overstated. Compiled extensions are not lost by building our own; they are lost only if we build
with flags that diverge from `pyemscripten_2026_0`. **Matching that flag set should be a build
constraint from the start**, because retrofitting it means rebuilding the interpreter.

Evidence that the path is real rather than paper: [pypa/packaging #804](https://github.com/pypa/packaging/pull/804)
implements the tag handling, [pypi/warehouse #19804](https://github.com/pypi/warehouse/pull/19804)
adds PyPI support, [maturin #3163](https://github.com/pyo3/maturin/pull/3163) adds it to a build
backend, and [pydantic publishes Emscripten wheels to PyPI under it](https://pydantic.dev/articles/emscripten-wheels-pydantic).

#### Still unverified

The exact flag list for `pyemscripten_2026_0` — Emscripten version above all — has not been read
off Pyodide's ABI page; it returned 403 to automated fetching and needs a manual read before the
build script is written. Until then, treat "we can match the platform" as a plan, not a fact.

---

## Correction: the JS layer is not mandatory

Everything above assumes Kotlin/Wasm can only reach CPython through JavaScript — that crossings
cost a JS hop and that string data must be copied by JS between two isolated memories. Reading
the Kotlin compiler shows both halves of that are wrong on the `wasmJs` target. This section
records what was checked and where it stops.

### 1. Kotlin can call another wasm module's exports directly

`@WasmImport(module, name)` and `@WasmExport(name)` exist in the stdlib
(`libraries/stdlib/wasm/src/kotlin/wasm/Annotations.kt`), gated behind
`@ExperimentalWasmInterop` since 2.2. The annotation doc says the function is imported "without
type adapters". The compiler's own codegen test `callingWasmDirectly.kt` states the intent
outright:

> Here we pass export of another Wasm module to our import directly without JS layer.
> This enables strict type check without JS conversions.

So `pmp_getattr(i32, i32) -> i32` can be a direct wasm-to-wasm call. JS is still involved in
*instantiating* the modules and wiring the import, but not in the call. This is exactly the
narrow integer interface the shape above already proposes — it just costs less than assumed.

The constraint is the one that makes it work: no type adapters means wasm primitive types only.
No strings, no objects. Which is what the composed shim signature already looks like.

### 2. On `wasmJs`, Kotlin *imports* its linear memory

`WasmCompiledModuleFragment.createAndExportMemory` branches on a flag that
`wasmCompiler.kt` sets as `importWasmMemoryInsteadOfExport = isWasmJsTarget`:

| target | what the module does with its memory |
|---|---|
| `wasmWasi` | **defines and exports** it — `WasmExport.Memory("memory", …)`, and the source comments that the name is a WASI ABI convention |
| `wasmJs` | **imports** it, as `WasmImportDescriptor("intrinsics", "memory")`, declared with 0 initial pages |

That inverts the premise. A `wasmJs` module does not own a memory it defines; the host hands one
in. And `kotlin.wasm.unsafe.Pointer` is an `i32` into precisely that memory.

So the question "can Kotlin read CPython's `char*`" reduces to "can the host supply Emscripten's
memory as that import" — at the wasm level, both are just a `WebAssembly.Memory` in an import
object, and Emscripten exposes its own as `Module.wasmMemory`. **If that works, string
marshalling does not merely get cheaper, it disappears** — which matters more here than anywhere
else, because every measurement on Android and desktop found marshalling, not crossings, to be
the dominant cost.

### 3. The blocker is the allocator, not the memory

`kotlin/wasm/unsafe/MemoryAllocation.kt` creates the top-level allocator as
`ScopedMemoryAllocator(0, parent = null)` and extends it with `wasm_memory_grow`. **It starts at
address 0 and assumes it owns everything.** In Kotlin's own memory that is correct. In a memory
shared with Emscripten, address 0 is the null page and the low region holds Emscripten's static
data, so `withScopedMemoryAllocator` would hand out addresses CPython already owns and corrupt
the heap.

This does not sink the idea, but it bounds it:

- **Reading and writing CPython-owned buffers** — addresses that CPython allocated and handed
  back as `i32` — is safe.
- **Allocating from Kotlin inside the shared memory** is not, unless the allocator can be given
  a base address it does not currently accept.

Whether a `Pointer` can be constructed from an arbitrary `Int` returned by the shim, rather than
only from `allocate()`, has not been checked and decides whether the safe half is usable at all.

### 4. The impossible direction stays impossible, and is already handled

None of this changes that a WasmGC reference cannot be stored in linear memory — that is a
spec-level guarantee, not a maturity gap, so CPython can never hold a Kotlin object pointer in a
`PyObject` field. The answer there remains an index into a Kotlin-side table, which is what
`docs/object-lifetime.md` already specifies for Python→Kotlin. Kotlin/Wasm even blesses the
mechanism: `JsReference<T>` passes a Kotlin object as an opaque reference.

### What this changes

| | as recorded above | after reading the compiler |
|---|---|---|
| Kotlin → CPython call | JS hop | **direct wasm call** (`@WasmImport`) |
| string marshalling | JS copies between two memories | **possibly none** — if the memory is shared |
| Kotlin allocating in that memory | assumed fine | **unsafe** — allocator starts at 0 |
| CPython holding a Kotlin object | impossible | impossible (unchanged) |

The composed-shim design does not change shape. What changes is its cost, and the reason to build
it: composition was proposed here to amortise JS hops, and if the hops are gone it has to justify
itself on crossing count alone — the same argument that closed desktop composition in ROADMAP §6.

### Unverified, in the order that decides the design

1. Can the host supply Emscripten's `wasmMemory` as Kotlin's `("intrinsics", "memory")` import?
   The generated JS glue builds that import object; whether it can be overridden is unknown.
2. Can `Pointer` be constructed from an arbitrary `Int`? If not, the read-only half is unusable
   and only `@WasmImport` survives.
3. Is `("intrinsics", "memory")` stable? It is an internal name, not public API.
4. Does `@WasmImport` work when the exporting module is an Emscripten `MAIN_MODULE`, whose
   exports are ordinary wasm functions but whose instantiation Emscripten's own glue controls?

None of these needs code in this repo to answer; all four are answerable with a small standalone
experiment, and together they decide whether WASM looks like iOS or like the JS-bridge design.

---

## Measured: what the released toolchain actually does

The section above was written from the Kotlin compiler at master and got one thing wrong in a way
that matters. `wasm-experiment/` builds a trivial `wasmJs` module on Kotlin 2.4.10, runs it, and
parses the emitted binary. Findings, in the order they change the design.

### The memory import is master-only; the released direction is the opposite

`importWasmMemoryInsteadOfExport = isWasmJsTarget` is in the compiler source but **not in any
released version**. Parsing the 2.4.10 output: the only length-prefixed `intrinsics` import is
`intrinsics.tag` (the exception tag), there is no memory import, and the export section contains
`memory`. Same on 2.2.20.

So Kotlin does not import a memory today. It exports one.

**That inverts the plan, and the inverted plan is better** — because it needs nothing unreleased
on either side. Emscripten has `-sIMPORTED_MEMORY`, which makes the CPython module import
`env.memory` rather than define it. Instantiate Kotlin first, take `exports.memory`, grow it to
Emscripten's initial size, hand it over as `env.memory`. Both halves are shipped features.

### Kotlin's linear memory is empty, and stays empty

This is what makes handing it over safe, and it is measured rather than assumed:

```
pages at startup                 0
pages after string interop       0
pages after collections          0
pages after exception            0
pages after explicit allocate    2   (allocated at 0x0)
```

The module declares `min_pages = 0`, and all 40 of its data segments are **passive** — they
initialise WasmGC arrays through `array.new_data` and are never placed in linear memory. String
interop was the obvious suspect for a hidden consumer and it is not one; Kotlin strings are
WasmGC arrays and cross to JS as `externref`.

So a `wasmJs` module's linear memory is unused space that happens to be attached to it. Giving it
to Emscripten costs Kotlin nothing.

### The allocator hazard is real but avoidable

The last probe line confirms the source reading: `withScopedMemoryAllocator` grew the memory and
allocated at **address 0**, exactly on top of where Emscripten keeps its static data. In a shared
memory this corrupts the heap on first use.

The mitigation is not a workaround, it is the natural design: **CPython allocates, Kotlin only
reads.** `Pointer(addr)` has a public constructor and its `loadInt`/`storeByte` members compile to
plain `i32.load`/`i32.store` with no allocator involvement — confirmed by compiling and running
it. Every address we would ever dereference comes back from a C call.

`withScopedMemoryAllocator` must therefore never appear in `wasmJsMain`. That belongs in the
source-set README as a hard rule, in the same category as desktop's "always `invokeExact`".

### What the picture looks like now

| | earlier draft | measured |
|---|---|---|
| Kotlin → CPython call | JS hop | direct wasm call (`@WasmImport`) |
| who owns the shared memory | CPython, imported by Kotlin (master-only) | **Kotlin exports it, Emscripten imports it** |
| Kotlin reading `char*` | impossible | `Pointer(addr).loadByte()`, no JS, no allocator |
| string marshalling | dominant cost | **none, if the memory is shared** |
| CPython holding a Kotlin object | impossible | impossible (unchanged — handle index) |

If this holds end to end, WASM stops being the outlier and lands close to the iOS shape: no
boundary for data, a direct call for control, and a handle table for the one direction WasmGC
forbids. The composition argument weakens accordingly — it was proposed here to amortise JS hops,
and without them it must justify itself on crossing count alone, which is what closed desktop
composition in ROADMAP §6.

### Still unverified

1. **Does `@WasmImport` bind to an Emscripten module's export?** The mechanism is proven by the
   compiler's own `callingWasmDirectly.kt` codegen test, but against a hand-built module, not one
   whose instantiation Emscripten's glue controls. Needs `emsdk`, which is not installed here.
2. **Does Emscripten accept a memory that starts at 0 pages and is grown by JS before
   instantiation?** `-sIMPORTED_MEMORY` normally pairs with a memory JS created at the right size.
3. **Does anything in a larger Kotlin program touch linear memory?** The probe covers strings,
   collections and exceptions on a trivial module. Coroutines, `ByteArray` interop and the JS
   `ArrayBuffer` bridges are untested.

---

## Proven end to end: Kotlin and CPython-side C can share one memory

The section above listed three unverified items and said the first needed `emsdk`. It is
installed, both experiments are built and run, and both pass. `wasm-experiment/` reproduces them.

### Test A — `@WasmImport` binds to an Emscripten export

Kotlin called `add_two` compiled by `emcc` and got 42. The compiler emits a real wasm import,
`(import "./probeA-wrapper.mjs" "add_two" (func …))`, and wires the ES module into the import
object. The value supplied prints as `function 2() { [native code] }` — a raw wasm export, not a
JS wrapper — so the call is wasm-to-wasm with no JS frame, which is what the compiler's own
`callingWasmDirectly.kt` test intends.

### Test B — Emscripten imports the memory Kotlin exports

```
C: get_static_message() -> 0x400
Kotlin: readCStringAt(same address) -> "hello-from-the-cpython-side"      PASS
C: alloc_message() -> 0x10628
Kotlin: writeCStringAt(addr, "written-by-kotlin")
C: str_len(addr) -> 17, UTF8ToString(addr) -> "written-by-kotlin"         PASS
```

Both directions, static data and `malloc`'d heap. **No copying and no JS in the data path.**
Kotlin dereferences a C address with `Pointer(addr)` and gets the bytes C put there.

### The single blocker, and it is one value

Emscripten refused Kotlin's memory at first:

```
LinkError: memory import has no maximum limit, expected at most 4294967295
```

Kotlin emits `WasmLimits(0, null)` — no maximum — and wasm requires a supplied memory's limits to
sit inside the importer's, so an unbounded memory can never satisfy any Emscripten import.
Patching section 5 to `{min: 0, max: 32768}` made everything link and both directions work.
Nothing else about the pairing needed changing.

**That is a compiler-chosen constant, not a design problem.** It is worth a YouTrack issue; a
`-Xwasm-memory-maximum`-style knob, or simply emitting the wasm32 ceiling, would remove the need
to post-process the binary. Until then a build step can patch it, which is ugly but bounded — the
memory section is six bytes and nothing in the format holds an absolute offset.

### The constraint that shapes the design

A and B cannot be combined in one instantiation graph while Kotlin owns the memory:

```
Kotlin      @WasmImport          needs Emscripten's exports at instantiation time
Emscripten  -sIMPORTED_MEMORY    needs Kotlin's memory before that
```

Wasm imports are supplied up front, so that is a cycle. Three ways out:

1. **Kotlin imports the memory instead of exporting it.** No cycle — Emscripten instantiates
   first, then Kotlin receives both the memory and the functions. This is
   `importWasmMemoryInsteadOfExport = isWasmJsTarget`, present at master and in no release. It is
   the configuration this design wants, and the second reason to talk to JetBrains.
2. **JS trampolines for calls, shared memory for data.** Available today. Costs a JS hop per
   call and keeps the data path free — and the data path is the half that dominated every
   measurement on Android and desktop, so this captures most of the win.
3. Copy through JS, as originally designed.

### What §10 looks like now

| | original design | proven |
|---|---|---|
| control path | JS hop per call | direct wasm call, **or** JS trampoline if sharing memory |
| data path | JS copies between two memories | **none — one memory** |
| string marshalling | dominant cost, needs interning | **absent** |
| composition | needed to amortise JS hops | **weakly motivated** — same argument that closed §6 |
| CPython holding a Kotlin object | impossible | impossible — handle index, per `object-lifetime.md` |

WASM stops being the outlier. With option 2 it is roughly Android's shape with a cheaper data
path; with option 1 it is close to iOS.

### Rules this produces for `wasmJsMain`

- **`withScopedMemoryAllocator` must never be called.** It allocates at address 0, on top of
  Emscripten's static data. Measured: the probe grew the memory to 2 pages and handed back `0x0`.
  CPython allocates; Kotlin only dereferences addresses it was given.
- `Pointer(addr)` is the only way in, and its public constructor plus raw `i32.load`/`i32.store`
  members make that free.

### Still open

- Whether a larger Kotlin program leaves linear memory alone. The probe covers strings,
  collections and exceptions; coroutines and `ByteArray`/`ArrayBuffer` bridges are untested, and
  any one of them touching linear memory would break sharing.
- The cost of option 2's JS trampoline, unmeasured.
- Upcalls. `addFunction` still routes through JS to re-enter WasmGC, and nothing here changes
  that; §7 still needs measuring before `wasmJsMain` is written.
