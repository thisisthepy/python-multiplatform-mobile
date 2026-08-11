# Upcall Table Design: Fragment Generation, Collection, and Tree Shaking

This document resolves the two open questions from
[`upcall-design.md`](upcall-design.md) and ROADMAP.md §7:

1. How do per-module function-table fragments find each other at runtime,
   given that Kotlin/Native has no `ServiceLoader` and no useful reflection?
2. How do we avoid defeating dead-code elimination?

## Status of claims

| Claim | Basis |
|---|---|
| KSP in app module sees generated code from library modules | **Demonstrated.** See `ksp-experiment/` |
| `getDeclarationsFromPackage` is the discovery API | **Demonstrated** |
| Two-round processing lets the app discover its own fragment too | **Demonstrated** |
| Kotlin/Native LLVM DCE operates at function-level granularity | Web research; not measured in this project |
| Lambda references are eliminated when unreachable | Web research (LLVM `GlobalDCE`); not measured |
| Binary-size cost of a full table on K/N | **Not measured** |
| `@EagerInitialization` is deprecated and should not be used | Confirmed (deprecated, slated for removal) |

---

## 1  Fragment generation (per module)

A KSP processor scans every source file in the current compilation unit.
For each eligible `public` declaration it records the qualified name, the
callable reference, arity, parameter-type tags, and return-type tag.

The processor emits a **fragment object** into a well-known package:

```kotlin
// generated in package: python.multiplatform.generated.fragments
// file: Fragment_com_example_mylib.kt

package python.multiplatform.generated.fragments

internal object Fragment_com_example_mylib : FunctionTableFragment {
    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "com.example.mylib.greet",
            callable = { args -> greet(args[0] as String) },
            arity = 1,
            paramTypes = listOf(ParamType.STRING),
            returnType = ReturnType.STRING,
        ),
        // ...
    )
}
```

### What is scanned

Per [`binding-policy.md`](binding-policy.md):

| Declaration | Included | Notes |
|---|---|---|
| `public` top-level functions | ✅ | |
| `public` classes + constructors | ✅ | |
| `public` member functions | ✅ | |
| `public` properties | ✅ | getter; setter only for `var` |
| `companion object` members | ✅ | exposed as static methods |
| `internal` / `private` / `protected` | ❌ | `internal` is `Modifier.INTERNAL` in KSP |
| `suspend` functions | ❌ | hidden `Continuation` parameter |
| `inline` + `reified` | ❌ | reified type info lost after compilation |
| extension functions | ❌ | no receiver available from Python |
| compiler-generated (`copy`, `componentN`, ...) | ❌ | |
| `expect` declarations | ❌ | `actual` is found on the platform side |
| annotated `@PythonInternal` | ❌ | opt-out annotation |
| library's own packages (`python.multiplatform.*`) | ❌ | excluded by KSP option |

### What the fragment holds

Each `ExposedCallable` carries:

- **`name`**: the Python-visible qualified name
- **`callable`**: a lambda wrapping the target — *not* a `KFunction` reference
  (see §4 on tree shaking for why)
- **`arity`**: parameter count
- **`paramTypes`**: list of type tags (`INT`, `FLOAT`, `STRING`, `BYTES`, `OBJECT`)
- **`returnType`**: return-type tag

The lambda form `{ args -> greet(args[0] as String) }` is deliberate. On the JVM,
`KFunction.call()` boxes arguments and does per-call arity/type checking (50–100 ns
overhead per call, measured in this project's benchmarks). On Kotlin/Native,
`KFunction.call()` is not available at all. A generated lambda that directly invokes
the target function:

- avoids boxing on both platforms
- compiles to a direct call on Kotlin/Native (the compiler inlines non-capturing lambdas)
- creates a **static, traceable reference** that the linker can reason about (see §4)

On the JVM, the generated code can use `MethodHandle` instead of a lambda for even
lower overhead, but this is an optimisation within the same design — the fragment
shape stays the same.

### Fragment naming convention

The well-known package is `python.multiplatform.generated.fragments`.

Fragment objects are named `Fragment_<sanitised_module_name>`, where the module name
is passed as a KSP option `python.multiplatform.moduleName`. Periods and hyphens are
replaced with underscores.

This naming convention is not load-bearing for discovery — the aggregator finds
fragments by scanning the entire well-known package — but it prevents collisions
and makes generated code legible.

---

## 2  Fragment collection (the aggregator)

### The problem, concretely

On Kotlin/Native:

- There is no `ServiceLoader`.
- A top-level `object` that nobody references is never initialised (lazy init
  is the default since K/N 1.7.20; `@EagerInitialization` is deprecated).
- Runtime reflection cannot enumerate class members.

So a fragment that registers itself in its own initialiser simply never runs.
The fragments must be referenced explicitly by something that *is* reachable.

### The solution: a build-time aggregator in the app module

This was the suspected answer. **It is now verified experimentally.**

A throwaway project at `ksp-experiment/` (outside `python-multiplatform/src`)
demonstrates the complete flow:

```
ksp-experiment/
├── ksp-processor/    — the KSP processor (fragment + aggregator)
├── library-module/   — a library with public functions
└── app-module/       — the application, depends on library-module
```

The library module's KSP generates `Fragment_library_module` into the well-known
package. The app module's KSP:

1. **Round 1**: generates `Fragment_app_module` for the app's own functions.
2. **Round 2**: calls `resolver.getDeclarationsFromPackage("...fragments")`
   and finds **both** `Fragment_app_module` (just generated) and
   `Fragment_library_module` (compiled into the library's JAR on the classpath).
3. Generates a `FunctionTable` that explicitly references every fragment.

**Verified output** (exit code 0):
```
Entries discovered: [experiment.app.appFunctionOne, experiment.app.main,
                     experiment.library.libraryFunctionOne,
                     experiment.library.libraryFunctionTwo]
```

### Why this works

KSP processes one module at a time and cannot see other modules' *source* files.
But `resolver.getDeclarationsFromPackage` queries the compilation classpath, which
includes compiled `.class` files (or `.klib` metadata on Native) from dependencies.

When the library module builds:
1. KSP generates `Fragment_library_module.kt` in the well-known package
2. The Kotlin compiler compiles it into the library's JAR (or `.klib`)

When the app module builds:
1. The library JAR/klib is on the compilation classpath
2. KSP's `getDeclarationsFromPackage` sees the compiled `Fragment_library_module`
3. The aggregator generates a `FunctionTable` with explicit references to all fragments

The generated `FunctionTable` looks like:

```kotlin
package python.multiplatform.generated

object FunctionTable {
    private val entries: Map<String, ExposedCallable> = buildMap {
        Fragment_library_module.entries().forEach { put(it.name, it) }
        Fragment_app_module.entries().forEach { put(it.name, it) }
    }

    fun lookup(name: String): ExposedCallable? = entries[name]
}
```

Because `FunctionTable` textually references every `Fragment_*` object, the
Kotlin/Native compiler sees them as reachable. No `ServiceLoader`, no
`@EagerInitialization`, no runtime reflection needed.

### Platform uniformity

The same aggregator design works on all four targets:

| Platform | Fragment discovery | Why it works |
|---|---|---|
| **JVM (Desktop)** | classpath (JAR) | `getDeclarationsFromPackage` sees JARs |
| **Android** | classpath (JAR/AAR) | same mechanism |
| **GraalVM Native Image** | classpath at build time | generated table is statically reachable → no reflection config needed |
| **Kotlin/Native (iOS)** | klib metadata | `getDeclarationsFromPackage` sees `.klib` dependencies |

On JVM/Android, `ServiceLoader` *could* work as a fallback, but using the same
aggregator everywhere eliminates a platform split and avoids runtime classpath
scanning.

### What the user has to do

**Library author:**
```kotlin
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("python.multiplatform:ksp-processor:$version")
}
ksp {
    arg("python.multiplatform.role", "library")
    arg("python.multiplatform.moduleName", "my_library")
}
```

**App developer:**
```kotlin
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    implementation("com.example:my-kotlin-library:1.0")
    ksp("python.multiplatform:ksp-processor:$version")
}
ksp {
    arg("python.multiplatform.role", "app")
    arg("python.multiplatform.moduleName", "my_app")
}
```

Ideally the `role` distinction could be inferred (the app module is the one that
produces a final binary / framework / APK), but KSP has no built-in way to detect
this. A Gradle plugin wrapping the KSP configuration could set the role
automatically based on `plugins.hasPlugin("application")` or
`plugins.hasPlugin("com.android.application")`.

### Discovery key: well-known package, not annotation

The aggregator does **not** scan for annotations. It calls
`resolver.getDeclarationsFromPackage("python.multiplatform.generated.fragments")`
and filters for objects whose name starts with `Fragment_`.

This is simpler and faster than annotation scanning (`getSymbolsWithAnnotation`),
which KSP documents as potentially expensive because it must scan the entire
classpath.

An alternative — keying on a marker interface `FunctionTableFragment` — would also
work. The aggregator could filter by supertype instead of name prefix. Either way,
the well-known package is the primary discovery mechanism.

---

## 3  What about `getDeclarationsFromPackage` being `@KspExperimental`?

`resolver.getDeclarationsFromPackage` is annotated `@KspExperimental` in KSP 2.x.
This means it may change in future KSP releases. However:

1. It is the *documented* API for cross-module discovery. KSP's own documentation
   recommends it for this use case.
2. No alternative exists that is non-experimental and works cross-module.
3. It has been stable in practice since KSP 1.x.

If it is removed or changed, the fallback is resource-file-based discovery: each
module's KSP writes a metadata file (e.g., JSON) to `META-INF/`, and the aggregator
reads those files from the classpath. This is more complex but equally viable.
The current design should abstract the discovery step so that switching mechanisms
does not change the fragment or table shape.

---

## 4  Tree shaking

### The problem

If the aggregator generates a `FunctionTable` that references every fragment, and
every fragment references every `public` callable in its module, then:

- The linker sees the entire public API as reachable
- Dead-code elimination cannot remove unused functions
- An app that calls three Kotlin functions from Python ships the entire library

This is the opposite of pay-for-what-you-use.

### Analysis: how bad is it, actually?

Before optimising, it is worth understanding the cost structure:

**What a fragment reference actually keeps alive:**

A fragment entry like `ExposedCallable("greet", { args -> greet(args[0] as String) }, ...)`
creates a lambda that closes over nothing (it calls the function by name). On
Kotlin/Native, non-capturing lambdas compile to singleton objects. The cost per
entry is:

| Component | Approximate cost |
|---|---|
| The lambda object (singleton) | ~16–32 bytes |
| The string literal (name) | ~40–80 bytes |
| The `ExposedCallable` wrapper | ~32 bytes |
| **The target function itself** | **varies — this is the real cost** |

The metadata overhead per entry is ~100–150 bytes. For a library with 300 public
functions, that's ~30–45 KB of metadata. This is noise.

The real cost is keeping the target functions alive. If `greet()` calls
`formatMessage()` which calls `translateString()` which pulls in an i18n library,
then the reference to `greet` keeps the entire dependency chain alive.

**But:** Kotlin/Native's LLVM backend performs function-level DCE with
`-ffunction-sections` + `--gc-sections`. Each function occupies its own section.
If `greet()` is referenced but `calculateTax()` is not, only `greet()` and its
transitive dependencies survive. The fragments reference every function, but the
linker still eliminates functions that are not referenced *by other code* in the
binary.

Wait — **that reasoning is wrong.** The fragment *does* reference every function
via the lambda. The lambda `{ args -> greet(args[0] as String) }` is a static
reference to `greet`. If the fragment is reachable (which it is, via the
aggregator), then *every lambda in it* is reachable, and therefore *every target
function* is reachable.

### The fundamental tension

The blacklist model ("all public unless opted out") means the fragment references
everything public. The aggregator references every fragment. Therefore the full
table keeps the entire public surface alive by construction.

There is no way to have all three of:
1. Blacklist model (all public exposed by default)
2. Full table referenced at build time (needed for K/Native)
3. Tree shaking of unused entries

You must give up at least one. The options:

### Option A: Accept the cost

**How:** Keep the current design unchanged. Every public function is in the table,
the table is reachable, nothing is eliminated.

**Cost:** For a library with 300 public functions averaging 50 bytes of machine
code each, that's ~15 KB of code that cannot be eliminated. For a library with
larger functions or transitive dependencies, the cost grows. On Kotlin/Native
where the baseline binary is 2–5 MB (Kotlin runtime + GC), 15–100 KB may be
acceptable.

**Advantages:**
- Simplest design
- No user-facing complexity
- Python can call any public function without configuration

**When this is right:** When the library is small or medium-sized, or when the
app uses most of the library's surface anyway.

**Measurement needed:** Build a release Kotlin/Native binary with and without the
full table and measure the delta. If it's under 100 KB for a realistic library,
accept it and move on. **This has not been done.**

### Option B: Fine-grained fragments

**How:** Instead of one fragment per module, generate one fragment per file (or
per class). The aggregator still references all of them, but if the linker can
trace through the `FunctionTable.entries` map and determine that certain
`Fragment_*` objects are never *looked up* at runtime, it could eliminate them.

**Problem:** The linker cannot do this. `FunctionTable` calls
`Fragment_X.entries()` unconditionally during its initialisation. All fragments
are reachable regardless of which names are actually looked up at runtime.

Making fragments lazily initialised (`by lazy { ... }`) would defer the call,
but `lazy` still captures a reference to the lambda that calls
`Fragment_X.entries()`, so the fragment is still reachable.

**Verdict:** Fine-grained fragments alone do not help. The aggregator's explicit
reference is the root cause.

### Option C: Opt-in at the call site (Python side)

**How:** Instead of generating a table of everything, require the Python code
(or a configuration file) to declare which Kotlin functions it wants to use.
The KSP processor reads this configuration and generates a table containing
only those entries.

```python
# python_bindings.txt (read by KSP)
com.example.mylib.greet
com.example.mylib.Calculator
```

**Advantages:**
- Perfect tree shaking: only referenced functions survive
- Binary contains exactly what is needed

**Disadvantages:**
- Contradicts the blacklist model ("all public, minus opt-out")
- User must maintain a manifest — fragile, especially during development
- Breaks the "write Kotlin, import from Python" promise

**Verdict:** This is a different product. The user request says the blacklist
model is not to be re-litigated.

### Option D: Lazy table with runtime resolution (JVM only)

**How:** On the JVM (where dead-code elimination is not an issue because classes
are loaded lazily), skip the aggregator entirely. Use `ServiceLoader` or simple
classpath scanning to discover fragments at runtime.

On Kotlin/Native, keep the aggregator but accept the cost (Option A).

**Advantages:**
- JVM apps pay no tree-shaking cost (not that they care — JVM doesn't tree-shake)
- Kotlin/Native pays the cost, but that's where it matters least (the library
  and app are typically small on iOS)

**Disadvantages:**
- Platform split in the collection mechanism
- `ServiceLoader` on JVM adds classpath scanning overhead at import time

**Verdict:** Not worth the complexity. The aggregator is simpler on all platforms.

### Option E: Two-tier table (recommended)

**How:** Split the table into a **skeleton** (names and metadata only) and a
**body** (the callable lambdas). The skeleton is always present. The body is
loaded lazily, per fragment, on first access.

```kotlin
// Generated skeleton — always present, small
object Fragment_mylib_Skeleton {
    val names: List<String> = listOf(
        "com.example.mylib.greet",
        "com.example.mylib.calculate",
    )
}

// Generated body — loaded only when a name from this fragment is actually called
object Fragment_mylib_Body {
    fun resolve(name: String): ((Array<Any?>) -> Any?)? = when (name) {
        "com.example.mylib.greet" -> { args -> greet(args[0] as String) }
        "com.example.mylib.calculate" -> { args -> calculate(args[0] as Long, args[1] as Long) }
        else -> null
    }
}
```

The `FunctionTable` references all skeletons (keeping them alive — ~100 bytes
per entry, names and metadata only). When Python does a `lookup("com.example.mylib.greet")`,
the table identifies which fragment owns the name and calls `Fragment_mylib_Body.resolve()`
for the first time, which triggers lazy initialisation of the body object.

**Key insight:** If no name from `Fragment_mylib` is ever looked up, the body
object is never initialised, and the lambdas inside it are never created.
**But the body class is still on the classpath and still reachable in principle.**

On the JVM, this is fine — class loading is lazy, so `Fragment_mylib_Body` is
never loaded if never referenced at runtime.

On Kotlin/Native, `object` initialisation is lazy (since 1.7.20), so the body's
`when` expression is never evaluated if the object is never accessed. **However,**
the body class itself is still compiled into the binary. The lambdas inside the
`when` arms are only created when that arm is executed, but the function references
they point to must still be compiled.

So on Kotlin/Native, Option E reduces the *runtime* cost (no lambda objects for
unused fragments) but does **not** reduce the *binary size*. The compiled code for
`greet()` is still in the binary because the `when` arm statically references it.

**Verdict:** Marginal improvement. Not worth the complexity unless runtime memory
is the concern rather than binary size.

### Recommended approach

**Start with Option A (accept the cost). Measure before optimising.**

The reasoning:

1. **The cost may be small.** The metadata overhead is ~100–150 bytes per entry.
   The function code itself varies, but on Kotlin/Native with a 2–5 MB baseline
   binary, even 100 KB of "extra" code is 2–5% — noticeable but not fatal.

2. **The cost may be unavoidable.** Given the constraints (blacklist model, no
   reflection, build-time table), there may be no mechanism that keeps a static
   reference to a function while allowing the linker to remove it. This is a
   fundamental property of static linking.

3. **The measurement is easy.** Build a Kotlin/Native release binary with and
   without the table. Diff the sizes. If the delta is under 100 KB for a
   realistic library (50–200 public functions), the problem is not worth solving.

4. **If the cost is unacceptable**, the only real mitigation is to change the
   exposure model — from "all public" to "explicitly annotated" — which is a
   product decision, not a technical one. The document noted in the user request
   says this is not to be re-litigated, so it stays as Option A for now.

---

## 5  Detailed flow: from `ksp(...)` to Python `import`

### Build time

```
┌──────────────┐    KSP     ┌────────────────────────────────────────┐
│ library-mod  │ ────────── │ Fragment_mylib.kt                      │
│ src/         │  (role=    │   package ...generated.fragments       │
│  mylib/*.kt  │  library)  │   object Fragment_mylib {              │
│              │            │     fun entries() = listOf(...)        │
│              │            │   }                                    │
└──────────────┘            └────────────────────────────────────────┘
        │                                       │
        │ compile                               │ compiled into JAR/klib
        ▼                                       ▼
┌──────────────┐            ┌────────────────────────────────────────┐
│ app-module   │    KSP     │ FunctionTable.kt                       │
│ depends on   │ ────────── │   package ...generated                 │
│ library-mod  │  (role=    │   object FunctionTable {               │
│              │   app)     │     private val entries = buildMap {   │
│              │            │       Fragment_mylib.entries()...      │
│              │            │       Fragment_app.entries()...        │
│              │            │     }                                  │
│              │            │     fun lookup(name) = entries[name]   │
│              │            │   }                                    │
└──────────────┘            └────────────────────────────────────────┘
```

### Runtime

```
Python: from kotlin import greet
  │
  ├─ CPython import mechanism invokes _kotlin_bridge module init
  │
  ├─ Module init calls FunctionTable (which initialises the object,
  │  pulling in all fragments)
  │
  ├─ Python: greet("world")
  │    │
  │    ├─ tp_getattro on the module proxy
  │    │    First time: FunctionTable.lookup("greet") → handle
  │    │    Cached in proxy's __dict__
  │    │
  │    ├─ Invoke handle with marshalled args
  │    │    JVM: MethodHandle.invokeExact(...)
  │    │    K/N: direct function call (lambda inlined)
  │    │
  │    └─ Marshal return value back to Python
  │
  └─ Result: "Hello, world!"
```

---

## 6  KSP processor design

The project ships a single KSP processor module. It contains one
`SymbolProcessorProvider` that delegates to two internal processors
based on a KSP option:

| Option | Value | Processor | What it does |
|---|---|---|---|
| `python.multiplatform.role` | `library` | `FragmentProcessor` | Scan module, emit fragment |
| `python.multiplatform.role` | `app` | `AppProcessor` | Emit fragment (round 1), then discover all fragments and emit `FunctionTable` (round 2) |

Both processors use the same fragment-generation logic. The `AppProcessor`
additionally runs the aggregation step in KSP's second processing round
(triggered by the new file generated in round 1).

### KSP options

| Option | Required | Description |
|---|---|---|
| `python.multiplatform.role` | Yes | `library` or `app` |
| `python.multiplatform.moduleName` | Yes | Sanitised module name for the fragment |
| `python.multiplatform.excludePackages` | No | Comma-separated packages to skip (e.g., the library's own packages) |

### Multi-round processing

The `AppProcessor` exploits KSP's multi-round processing:

1. **Round 1:** Generate `Fragment_<app_module>` for the app's own functions.
   This produces a new file, which triggers round 2.
2. **Round 2:** Call `resolver.getDeclarationsFromPackage("...fragments")`.
   This now sees both the freshly generated app fragment and all dependency
   fragments (compiled into JARs/klibs on the classpath). Generate `FunctionTable`.

This was verified in the experiment: the round-2 query correctly returns
declarations from both sources.

---

## 7  What a Gradle plugin would do

The KSP options (`role`, `moduleName`, `excludePackages`) are mechanical.
A Gradle plugin (`python-multiplatform-bindings`) could:

1. Apply the KSP plugin automatically
2. Add the processor dependency
3. Infer the role from the module type:
   - `com.android.application` or `application` plugin → `app`
   - everything else → `library`
4. Derive `moduleName` from the Gradle project path
5. Set `excludePackages` based on the module's own `group`/`namespace`

This would reduce the user's configuration to:

```kotlin
plugins {
    id("python-multiplatform-bindings")
}
```

This is a usability concern for later. The KSP processor works without it.

---

## 8  What was NOT verified

### Kotlin/Native target with KSP

The experiment uses JVM-only modules. KSP processes Kotlin/Multiplatform
modules identically for all targets (it operates on Kotlin IR, not
platform-specific bytecode), so the fragment generation should work the
same way on Native targets. However, this was not tested end-to-end.

Specifically not tested:
- Whether `getDeclarationsFromPackage` works correctly when the dependency
  is a `.klib` rather than a `.jar`
- Whether the generated `FunctionTable` compiles on Kotlin/Native targets
- Whether the aggregator's explicit references actually prevent the K/N
  linker from stripping the fragments

**Recommendation:** Before implementing, build a minimal KMP project
(commonMain + nativeMain) with the same two-module structure and verify
compilation on at least one Native target (e.g., `macosArm64`).

### Transitive dependencies

Only tested direct dependency (app → library). Whether
`getDeclarationsFromPackage` discovers fragments from transitive
dependencies (app → libA → libB, where libB has fragments) was not tested.

It should work because transitive classes are on the compilation classpath,
but "should work" has been wrong three times in this project.

### Published Maven artifacts

Only tested with `project()` dependencies. When a library is published to
Maven and consumed as `implementation("group:artifact:version")`, the
generated fragment classes are in the published JAR. KSP should see them
the same way. Not tested.

### Binary-size impact

The actual KB cost of keeping unused functions alive on Kotlin/Native has
not been measured. This is the most important open measurement.

### GraalVM Native Image compatibility

The aggregator design avoids reflection entirely — `FunctionTable` has
static references to all fragments, which have static references to all
callables. Under GraalVM's closed-world analysis, this should work without
any `reflect-config.json`. Not tested.

---

## 9  Alternatives considered and rejected

### `@EagerInitialization`

Kotlin/Native's `@EagerInitialization` forces a top-level property to
initialise at program startup. This could make fragments self-registering.

**Rejected because:**
- Deprecated and slated for removal
- Does not solve the discovery problem (who calls `register()`?)
- Defeats tree shaking by definition (if it's eagerly initialised, it's reachable)

### Resource files (META-INF)

Each module's KSP writes a metadata file (e.g., JSON listing function names)
to `META-INF/python-multiplatform/`. The aggregator reads these resources
from the classpath.

**Not needed because** `getDeclarationsFromPackage` works for this use case.
Resource files are more complex (need classpath I/O in the processor) and
less type-safe (JSON parsing vs. direct class references).

**However**, resource files remain a viable fallback if
`getDeclarationsFromPackage` is removed or changed (it is `@KspExperimental`).
The design should keep the discovery step abstract enough to swap.

### Manual registration

Require the user to call `FunctionTable.register(Fragment_mylib)` in their
`main()`. Simplest possible design.

**Rejected because** it contradicts the "add the KSP plugin and nothing else"
goal. Every new library dependency would require a new registration call.

### Compiler plugin instead of KSP

A Kotlin compiler plugin (IR plugin) could inject fragment registration
directly, without generating source files. More powerful but:

- Much harder to write and maintain
- Compiler plugin API is less stable than KSP
- Not needed — KSP is sufficient for this use case

---

## 10  Summary of the design

| Aspect | Decision |
|---|---|
| Fragment generation | KSP processor per module, one fragment object per module |
| Fragment package | `python.multiplatform.generated.fragments` |
| Discovery mechanism | `resolver.getDeclarationsFromPackage` in the app module's KSP |
| Aggregation | `FunctionTable` generated in app module, explicit references to all fragments |
| Platform uniformity | Same mechanism on JVM, Android, GraalVM, and Kotlin/Native |
| Tree shaking | Accept the cost for now; measure before optimising |
| User experience | Add KSP plugin + processor dependency; set role and module name |
| Callable representation | Generated lambda (not `KFunction.call`) |
| Discovery key | Well-known package name, not annotation |

## 11  Open questions for implementation

1. **Measure binary-size impact on Kotlin/Native.** Build a release binary with
   and without a 200-entry table. If the delta is under 100 KB, close the tree-shaking
   item.

2. **Verify `.klib` discovery.** Run the aggregator experiment on a KMP project with
   a Native target to confirm `getDeclarationsFromPackage` works with `.klib`
   dependencies.

3. **Decide annotation name.** The user request says `@PythonHidden`;
   [`binding-policy.md`](binding-policy.md) says `@PythonInternal`. Pick one before
   implementation.

4. **Handle `main()`.** The experiment's aggregator picked up `main()` as a public
   function. The processor should exclude entry points (functions named `main` with
   no parameters or `Array<String>` parameter).

5. **Incremental KSP.** The aggregator generates `FunctionTable` with
   `Dependencies(false)` (no dependencies tracked). This means any change to any
   source file triggers re-aggregation. This is correct but potentially slow.
   Investigate whether tracking fragment files as dependencies enables incremental
   aggregation.

6. **KSP for KMP.** On Kotlin Multiplatform, KSP must be applied per target
   (e.g., `kspIosArm64`, `kspAndroid`). The Gradle plugin (§7) should handle this
   automatically. Also, generating into `commonMain` requires `kspCommonMainMetadata`
   configuration, which has known quirks with single-target projects.
