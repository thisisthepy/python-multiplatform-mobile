# The repositories around this one, and what each owes

`/Volumes/macMini/thisisthepy` holds several repositories that are meant to compose into one
product. Until now none of them referenced the others in writing — this repository's `ROADMAP.md`
mentions `pypackpack`, `toolchain`, `pythonx` and `chaquopy` exactly zero times — so this file
records the intended relationships, what each repository actually contains today, and what has to
be built before the parts can meet.

Everything here was read from the working copies on 2026-08-14. Claims that were inferred rather
than observed are marked as such.

---

## 1. The intended shape

    pypackpack (ppp)     the features: Python acquisition, dependency resolution, cross-compilation,
                         bundling. A Kotlin/JVM library that also ships as a GraalVM native CLI.

    toolchain            the Gradle interface to ppp, and nothing more than that.
                         A developer applies one plugin; toolchain translates the Gradle DSL into
                         ppp middleware calls.

    PythonMultiplatform  the binder: CPython embedded in Kotlin Multiplatform, the object model,
                         the FFI layer, upcalls, downcalls, the generated function table.

    pythonx-compose      Compose bound into Python, so a UI can be written in Python.

    PyREPL               reference implementation for turning a Kotlin namespace into `.pyi` stubs,
                         and — see below — the actual origin of `toolchain`.

The division of labour that matters: **ppp owns the work, toolchain owns the Gradle vocabulary,
this repository owns the language boundary.** A feature that acquires a Python distribution or
resolves a dependency belongs in ppp even if a Gradle task is what triggers it.

---

## 2. What is actually there

### `pypackpack`

Roughly 4,900 lines of Kotlin, a clikt CLI compiled to a GraalVM native image, with real `uv`
integration. `docs/SPEC.md` (483 lines, added in the most recent commit) marks every feature
`Implemented` / `Partially implemented` / `Not yet implemented`.

Working: `init`, `python use/list/find/install/uninstall`, `package add/remove/sync/tree`,
`target list/add/remove`, per-package dependency operations with platform markers, and `build`
driving Meson for C/C++.

Placeholder — verified by line count, all two to four lines: the whole `bundle/` tree, the whole
`deploy/` tree, every compile backend except Meson (Clang, MSVC, NDK, XCode, Emscripten, Cargo),
and all of `compile/middleware/{external,transcompile,minification}`.

It is already designed to be called from Gradle. `dependency/frontend/BaseInterface.kt` declares
`enum FrontendType { CLI, GRADLE }` and `Gradle.kt` returns the same middleware the CLI uses. The
stable surface is `dependency/middleware/BaseInterface.kt` — `initProject`, `addDependencies`,
`syncDependencies`, `addTargets`, `installPythonVersion`, and so on — plus
`compile/middleware/BaseInterface.kt`'s `compile`.

**But `packpack/build.gradle.kts` applies no `maven-publish` and declares no publication.** Nothing
outside that build can resolve it. Until that changes, "toolchain is the Gradle interface to ppp"
cannot be implemented at all.

### `toolchain`

A Kotlin Gradle plugin, id `org.thisisthepy.python.multiplatform`, about 1,240 lines. It registers
a `python` extension and three tasks. What the tasks do today: copy a local folder into
`src/main/assets/python`, copy `build/pythonLibraries` into `build/pythonBundle/libs`, zip that,
and shell out `uv install -r` — which is not a valid uv command.

**`toolchain` shares git history with `PyREPL`.** Commits `f806656`, `5f3f932`, `1b32d03`,
`654ef7c` exist in both with identical hashes; `dba17dd` extracted PyREPL's meta-generation out of
`app/build.gradle.kts` into `toolchain/.../meta/createMetaClass.kt`. So the pyi generator to study
is already here, in a mutated form — see §4.

The target API is `(플러그인예시)build.gradle.kts` at the repo root: 276 lines describing
`compileSdk`, per-platform declarations, `packaging { embedLevel, hotReload, codePush }`,
`buildTypes { compileLevel }`, `projectFlavors`, `buildFeatures { metaclass, compose }`, and source
sets carrying both `implementation("pyzmq")` and `integration("pycomposeui")` — the latter being a
Kotlin-dependent Python package that needs a `KLIBDEPENS` file in its wheel.

Of that spec the DSL data classes exist for roughly 60%, and **none of `hotReload`, `codePush`,
`buildTypes`, `projectFlavors`, `buildFeatures`, `metaDirs`, `libDirs` or `integration()` is read by
any task.** `PythonPlugin.kt` consumes only `sourceSets.*.dependencies.implementations`.
`dsl/PythonConfiguration.kt` is dead code — its extension is never registered.

`toolchain/usage-example/` does not apply the plugin, has no `python { }` block, and is commented
out of `settings.gradle.kts`. **There is currently no executable definition of the target API.**

`toolchain-legacy` is a different lineage: a fork of `kivy/kivy-ios` in pure Python that
cross-compiles CPython and native wheels for android/ios/macos/linux/mingw, last worked on in
February 2024. Nothing moved from it into `toolchain`; its job is what ppp is rebuilding in Kotlin
with uv, crossenv and meson instead of kivy recipes.

### `pythonx-compose`

Two generations coexist, and the one the notebook demonstrates is in neither of them.

`pythonx/compose/` is the chaquopy generation: a `Composable` decorator class, `ComposeApp`, and
material3 wrappers — 37 files of which 28 are empty. It binds through `jclass(...)` and
`from java import jclass`, and it reaches **AndroidX directly**:

    from androidx.compose.material3 import TextKt
    try:    self.__kotlin_composable = TextKt.Text
    except:
        for name, obj in TextKt.__dict__.items():
            if name.startswith("Text-"):
                self.__kotlin_composable = obj; break

That trailing `-` is Kotlin's value-class mangling suffix (`Text-fLXpl1I`), which chaquopy exposes
verbatim. There are 26 such scans across 8 files. It then calls the function passing Compose's
synthetic `$composer` and `$changed` by hand.

`pythonx/compose/lite/` is a desktop JVM generation using JPype and hand-written Kotlin wrappers.

`test/` still depends on chaquopy 15.0.0 and contains a stale in-repo prototype of this
repository's binder.

### `PyREPL`

The Kotlin-to-pyi converter lives inline in `app/build.gradle.kts`, 627 lines. It walks each Kotlin
source set's resolved artifacts, reads the jars with **ASM** (not kotlinx-metadata), and writes one
`__init__.pyi` per Java package under `src/<sourceSet>/generated/meta/`, with `...` bodies and a
JNI-ish type map. It drops private members, `<init>`, `Companion`, and **any member whose name
contains `-`** — that is, it discards the mangled ones rather than scanning for them. Collisions
between `commonMain` and a platform source set are resolved by renaming both and re-exporting.

It is stubs only; the runtime underneath is chaquopy `jclass`. Its purpose is IDE completion.

`toolchain`'s `createMetaClass.kt` is the same code mutated into a *runtime* generator: it emits
`.py` with `from java import *`, `__metaclass__ = jclass(__name__)`, real delegating bodies, and a
Kotlin-to-dunder name map. **For a chaquopy-free future, PyREPL's stub-only version is the one to
keep.**

### Not part of this chain

`Gemstone` belongs to a different organisation and does not depend on any of these; its README says
it is *scheduled* to adopt Python Multiplatform. `reference/` and `cpython/` are not repositories —
vendored material and prebuilt CPython artefacts.

---

## 3. The notebook is PyREPL's, not pythonx-compose's

`UI.ipynb` imports `pythonx.compose.runtime`, `pythonx.compose.material3`, `pythonx.compose.ui`
and `pythonx.compose.layout`, and documents signatures like `Text(text, color, font_size)`,
`Button(onclick, enabled, corner_radius, color, content)`, `Column/Row/Spacer/TextField`. It also
requires `main.App` as a live object, `App.messages.getValue()/setValue()`, and
`main.App.update(NewComposable)` for hot-swapping the UI from a Jupyter cell.

**`main.App`, `App.update`, `DefaultIcons`, `Column`, `Row`, `Spacer` and `TextField` do not exist
in `pythonx-compose`.** They exist in PyREPL, under `app/src/androidMain/python/`, in a simpler
design that does **no name matching**: it binds hand-written Kotlin wrappers by exact `@JvmName`
(`Material3Kt.SimpleTextWidget`) and passes the composer explicitly.

So if the goal is "the notebook runs on our stack", the port target is PyREPL's `app/`, and
`pythonx-compose` is the more ambitious, largely unfinished generalisation of it.

---

## 4. What blocks the parts from meeting

Ordered so that each unblocks the next.

**1. `@Composable` cannot be an exposed callable.** The generator emits
`callable = { args -> Owner.fn(args[0] as X) }` and `ExposedCallable.callable` is typed
`(Array<Any?>) -> Any?`. A `@Composable` function can only be called from a `@Composable` context,
so a generated entry for a widget would not compile, and `BindingPolicy` has no `@Composable`
handling at all. Every widget in `pycomposeui` is `@Composable`. Nothing in `pythonx-compose` can
leave chaquopy until this is designed. *(read from source; not runtime-verified.)* This is the
smallest and most blocking item, and it is entirely inside this repository.

**2. `packpack` must be publishable.** One file. Without it the stated architecture is
unimplementable.

**3. A Python-facing upcall module.** Today the only Python-side surface is the generated proxy
module plus, in a demo, raw `ctypes` pointers. `pythonx-compose` needs Kotlin namespaces to be
**importable**, so `_material3 = jclass("...Material3Kt")` becomes an ordinary import. Most of
this exists: generated modules are injected into `sys.modules` under their Kotlin fully-qualified
name. What is missing is a `sys.meta_path` finder so a name resolves on demand rather than only
after an eager `install()`, the `pythonx` prefix, and generated `.pyi` beside it.

**4. One CPython acquisition path, not two.** This repository's `stagePythonHome` downloads from
python-build-standalone into a Gradle cache; ppp's `python install` downloads from this
repository's own GitHub releases into `~/.pypackpack/python/<version>`. They disagree on source and
destination, and ppp's own `list`/`find` disagree with its `install` about the destination.
Per the stated architecture the surviving one is ppp's.

**5. ppp's `bundle` stage**, at least bundle-type `resource`, which its SPEC defines as the handoff
format to python-multiplatform and toolchain. It is the missing seam between the two halves of the
system, and all four bundlers are three-line placeholders.

**6. Port PyREPL's pyi generator into toolchain**, replacing the chaquopy-coupled variant, and fix
its hardcoded `\\` path separators, which break on macOS and Linux.

**7. A `usage-example` that applies the plugin**, so the target API stops being only a document.

---

## 5. What each repository owes

### `PythonMultiplatform` (this one)

- A `@Composable`-capable callable shape, with its own `CallableKind` or an opt-in annotation the
  generator recognises. Blocking item 1 above.
- A Python-facing module for resolving and invoking table entries, replacing `jclass`.
- Opaque Kotlin object round-tripping for Compose's `Composer`. `HandleTable` and
  `ObjectReference` exist; nothing hands them to Python.
- Python callables passed into Kotlin and **stored across recomposition** — `content=lambda: ...`
  and `onclick=...` are stored in Compose's slot table and re-invoked later. The invoke path
  exists; that lifetime is untested.
- Keep the KSP wiring. It encodes AGP and KSP knowledge that versions with the processor — the
  camel-case `Test` matcher exists because `kspAndroidTestDebug` once silently shadowed the real
  table. toolchain should *apply* this plugin, not absorb it.
- Hand `stageWasmBrowserRuntime` to toolchain, and retire `stagePythonHome` in favour of ppp.

### `toolchain`

- Become the Gradle vocabulary and nothing else: translate the DSL into ppp middleware calls.
- Delete its own implementations of what ppp owns — `InstallDependenciesTask`,
  `BuildPythonArtifactTask`, `AssemblePythonPackageTask`, and the Android assets copy.
- Finish what ppp has no equivalent for: the DSL surface itself, the `integration()` dependency
  type with `KLIBDEPENS`, and Kotlin-to-pyi generation.
- Replace `createMetaClass.kt` with PyREPL's stub generator.
- Make `usage-example` apply the plugin.
- Issues: #2 (plugin and build tools — only "basic plugin structure" is ticked), #1 (a
  Python-only CLI, `tcl install pythonx-compose`).

### `pypackpack`

- Publish to Maven so toolchain can depend on it.
- Fix the `python install` / `list` / `find` destination disagreement before anything is built on
  top of it.
- Implement `bundle`, at least bundle-type `resource`.
- Known issues its own docs record: uv marker mismatch between `add` and `remove --target`, no CLI
  pass-through flags, meson and ninja never auto-installed.

### `pythonx-compose`

- Delete the raw-AndroidX branch. A KSP-generated table cannot cover `androidx.compose.material3`,
  because KSP only sees modules that apply the bindings plugin — a third-party binary artefact can
  never have a fragment. The name-prefix scan has no table equivalent and must go, not be ported.
- Adopt the PyREPL shape instead: a hand-written `pycomposeui` module of `@Composable` wrappers,
  which *is* exactly what the table serves. Apply the bindings plugin to it and the wrappers
  become importable — with the mangling gone, because KSP reads the source declaration name.

  **The Python surface is an import statement, not a resolve call.** Exposing
  `UpcallTable.resolve("SimpleTextWidget")` would repeat chaquopy's `jclass` mistake in a new
  spelling. This repository already gets most of the way there: `PythonProxySource` injects each
  generated module into `sys.modules` under its Kotlin fully-qualified name, so
  `from fixture.library import greet` works with no import hook at all — CPython's import
  machinery takes a `sys.modules` hit for the full dotted name before consulting any finder.
  What is missing is the `pythonx` prefix, laziness, and stubs; see §5's entry for this
  repository.

  **The explicit accessors still have to exist alongside it.** An import statement cannot cover a
  name computed at runtime, and it cannot express which language a symbol comes from when that
  matters. So the base import library also provides per-language class handles —
  `JClass`/`JavaClass` for a JVM class, `KClass`/`KotlinClass` for a Kotlin declaration,
  `ObjcClass` for an Objective-C class — with the import hook implemented on top of them rather
  than beside them. (Naming note: `KClass` collides with `kotlin.reflect.KClass` in every
  conversation about this, even though the Python name is its own namespace. Worth settling
  early.)
- Replace `remember_saveable`'s dispatch on `PyObject.toString()` of a type name with this
  repository's `PyValue` type tags.
- Decide whether the port target is `pythonx-compose` or PyREPL's `app/`. The notebook's API is
  PyREPL's.

---

## 5b. The Python import surface — decided

Four questions were open about what Python code should look like. They are settled.

**A Kotlin fully-qualified name means the original Kotlin.** `import androidx.compose.material3`
reaches real AndroidX, not a wrapper of ours wearing its name.

This is reachable, and the mechanism is the one PyREPL already uses: **the Gradle plugin reads the
resolved dependency artefacts.** For each Kotlin source set it makes a resolvable copy of the
`implementation` and `api` configurations and walks the resulting jars with ASM. AndroidX is an
ordinary jar in that set, so it is covered like anything else.

The two producers are therefore split by *what they look at*, not by whether something is
reachable:

    KSP                 the consumer's own source — declarations it can see being compiled
    artefact walker     everything the build resolves — third-party jars, AndroidX included

Both run at build time under the same applied plugin. An earlier draft of this file claimed
AndroidX could never be reached because KSP does not see it; that conflated one producer's limit
with the system's.

What genuinely differs per platform is what a *jar* means. On JVM and Android the artefacts are
jars and the walker applies directly. On iOS, androidNative and wasm the artefacts are klibs, and
whether the same walk is possible there — and what a Kotlin declaration from a klib can be bound
to at runtime with no JVM underneath — is the open question, not AndroidX.

**Dynamic binding is a removed option, not a missing one.** The 2024 design document
(*PyComposeUI*, the open-source contest report) specifies a dynamic binder — Java/Kotlin
Reflection on the JVM target, `ctypes` and `pyobjc` on Kotlin/Native — with a generated meta
package existing only so an IDE can infer types against it. That is history. The binder was built
statically instead, and the reason is the same one §7 records for choosing a build-time table:
a GraalVM native image is a closed world and Kotlin/Native has no reflection, so a name that only
exists at runtime cannot be reached on the targets this library has to support.

So there is one axis, not two. Bindings are produced at build time, by KSP for the consumer's own
source and by an artefact walk for everything the build resolves. Anyone reading the 2024 document
will find a dynamic path described as the plan; it was dropped deliberately, and the meta package
that document treats as a typing aid is, here, generated beside bindings that are themselves
static.

**`pythonx.*` is ours.** Whatever we wrap or add lives under that prefix. Kotlin fully-qualified
names point at the original; `pythonx` points at our Pythonic layer. The two namespaces do not mix.

**`JClass`/`JavaClass`, `KClass`/`KotlinClass`, `ObjcClass` work only where the platform has the
thing they name.** No stubs, no substitutes, no forced uniformity across targets — a target that
has no JVM has no `JClass`, and says so.

**`.pyi` generation belongs to the Gradle plugin**, the way PyREPL did it: read the resolved
artefacts at build time and emit stubs per source set. It is not a sibling of `PythonProxySource`,
which fills `sys.modules` at runtime; these are different products for different consumers, one for
the interpreter and one for the IDE. Long term that generator belongs to `toolchain` — see §5.

---

## 6. Where this file should live

It describes five repositories and sits in one of them, because that is the only one with an
active working copy and a documentation habit. If the organisation grows a place for cross-repo
documents, this belongs there, and what stays here is the last section's first entry.
