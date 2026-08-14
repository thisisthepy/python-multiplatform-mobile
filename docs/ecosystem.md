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
`Button(onclick, …)` — quoted as the notebook writes it, which is **not** the convention: the
library spells that parameter `on_click`, and `docs/pythonx-adapter-design.md` §3 settles it in the
library's favour — plus `Column/Row/Spacer/TextField`. It also
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

## 5. What each repository owes (Implementation Goals per Repository)

This section documents the current state, language/build system/distribution artifact, ecosystem goals, gaps to goal, and dependency direction for each of the 5 repositories in `/Volumes/macMini/thisisthepy/`, based on direct inspection of working trees on 2026-08-14.

### `PythonMultiplatform` (this repository)

- **Observed Current State**:
  - Embedded CPython 3.13 FFI binder (`python-multiplatform/src/commonMain/kotlin/.../EmbedAPI.kt`, platform implementations in `EmbedAPI.desktop.kt`, `bindings.kt`, JNI/Panama/cinterop).
  - Kotlin object model hierarchy (`python-multiplatform/src/commonMain/kotlin/.../PyObject.kt` and wrappers).
  - Code generators: KSP processor (`python-multiplatform-ksp/`) producing `FunctionTableFragment`s, and Gradle plugin (`python-multiplatform-gradle-plugin/src/main/kotlin/.../ArtifactBindingGenerator.kt`) scanning resolved dependency JARs via ASM for `ArtifactTable` fragments.
  - Multiplatform target support: Desktop JVM/Panama, Android JNI, iOS Native Cinterop, androidNativeArm64 (`:python-multiplatform:compileKotlinAndroidNativeArm64`).
  - CPython binary download and SHA-256 lockfile / Sigstore verification (`python-checksums.properties`, `python-multiplatform/build.gradle.kts`).
- **Language / Build System / Distribution**:
  - Kotlin Multiplatform (Kotlin 2.0+), Java 21, C/C++ FFI.
  - Gradle (`build.gradle.kts`, `settings.gradle.kts`, included build `python-multiplatform-gradle-plugin`).
  - Maven artifact publication (`id("maven-publish")` in `python-multiplatform/build.gradle.kts`, published under group `io.github.thisisthepy:python-multiplatform`).
- **Goal in Ecosystem**:
  - Core language boundary and binder between Kotlin Multiplatform and CPython.
  - Manages low-level FFI, object reference handles, upcall/downcall function tables, GIL lifecycle, and proxy injection into CPython `sys.modules`.
- **Gap to Goal**:
  - `@Composable`-capable callable shape: `ExposedCallable` typed `(Array<Any?>) -> Any?` cannot invoke `@Composable` functions taking synthetic `$composer` / `$changed` parameters. Needs dedicated `CallableKind` or opt-in annotation handling.
  - Python-side `sys.meta_path` finder for lazy import resolution of Kotlin namespaces (`pythonx.*` or FQCNs) upon import.
  - Opaque Kotlin object round-tripping for Compose `Composer` (`HandleTable` and `ObjectReference` exist, but runtime hand-off to Python needs verification).
  - Lifetime and storage of Python callables passed into Kotlin across Compose recompositions (`content=lambda: ...`, `on_click=...`).
  - Retire `stagePythonHome` in favor of `pypackpack`'s Python distribution management.
  - Hand `stageWasmBrowserRuntime` logic to `toolchain`.
- **Dependency Direction**:
  - `PythonMultiplatform` has no dependencies on other repos in the ecosystem.
  - `pythonx-compose` and consumer applications depend on `PythonMultiplatform` runtime and its Gradle/KSP bindings plugin.
- **Unverified**:
  - Behavior of WASM browser runtime (`wasm-experiment/`) under production web bundlers.

---

### `toolchain`

- **Observed Current State**:
  - Plugin project structure (`toolchain/src/main/kotlin/.../PythonPlugin.kt`) registering `python` extension and 3 tasks (`InstallDependenciesTask`, `BuildPythonArtifactTask`, `AssemblePythonPackageTask`).
  - Naive, self-contained task implementations in `bundle/` (`AssemblePythonPackageTask.kt`, `BuildPythonArtifactTask.kt`): copying local directory to `src/main/assets/python`, zipping to `build/pythonBundle/libs`, and executing `uv install -r` (which is an invalid `uv` command).
  - DSL definitions in `dsl/` (`DSLBuild.kt`, `DSLCore.kt`, `DSLPackaging.kt`, `DSLPlatforms.kt`, `PythonConfiguration.kt`) covering ~60% of target spec `(플러그인예시)build.gradle.kts`.
  - Mutated PyREPL generator `toolchain/src/main/kotlin/.../dependency/lang/createMetaClass.kt` producing runtime `.py` files using `from java import *`.
- **Language / Build System / Distribution**:
  - Kotlin (`kotlin-dsl`, `java-gradle-plugin`).
  - Gradle (`build.gradle.kts`, `settings.gradle.kts`).
  - Gradle Plugin published to Maven (`org.thisisthepy.python.multiplatform` plugin ID).
- **Goal in Ecosystem**:
  - Developer-facing Gradle DSL vocabulary (`python { ... }`) and nothing else.
  - Translates Gradle DSL configuration into `pypackpack` middleware API calls.
  - Owns Kotlin-to-`.pyi` stub generation (porting PyREPL's ASM-based stub generator).
  - Handles `integration()` dependency types with `KLIBDEPENS`.
- **Gap to Goal**:
  - **Delegate to `pypackpack`**: Currently delegates nothing to `pypackpack` because `pypackpack` is not published to Maven. All tasks must be rewritten to invoke `pypackpack` middleware.
  - **Delete redundant tasks**: Remove internal naive tasks (`InstallDependenciesTask`, `BuildPythonArtifactTask`, `AssemblePythonPackageTask`, and asset copy).
  - **Wire up DSL**: DSL blocks in `dsl/` (`hotReload`, `codePush`, `buildTypes`, `projectFlavors`, `buildFeatures`, `metaDirs`, `libDirs`, `integration()`) are not read by any task. `PythonConfiguration.kt` extension is dead code (never registered).
  - **Replace stub generator**: Replace runtime `createMetaClass.kt` with PyREPL's ASM-based build-time `.pyi` generator, fixing hardcoded Windows path separators (`\\`).
  - **Executable specification**: `usage-example/` does not apply the plugin and is commented out in `settings.gradle.kts`.
- **Dependency Direction**:
  - `toolchain` -> `pypackpack` (calls `pypackpack` JVM library API).
  - Applied by end-user Kotlin Multiplatform projects.
- **Unverified**:
  - Behavior of `integration()` configuration with `KLIBDEPENS` wheel metadata (only documented in DSL spec).

---

### `pypackpack`

- **Observed Current State**:
  - ~4,900 lines of Kotlin implementing CLI subcommands in `packpack/src/main/kotlin/.../cli/`: `init`, `python use/list/find/install/uninstall`, `package add/remove/sync/tree`, `target list/add/remove`, `add`/`remove`/`sync`/`tree` with target markers (`platform_system`, `platform_machine`).
  - `uv` integration (`dependency/backend/external/UV.kt`) for downloading uv and managing `DevEnv` / `CrossEnv` venvs.
  - `build` subcommand driving Meson CLI (`compile/backend/external/Meson.kt`) for C/C++ extension modules (`py.extension_module()`) and Python sources (`py.install_sources()`).
  - Architecture ready for dual usage (`dependency/frontend/BaseInterface.kt` has `enum FrontendType { CLI, GRADLE }` and `Gradle.kt`).
  - Specification in `docs/SPEC.md` marking feature statuses.
- **Language / Build System / Distribution**:
  - Kotlin JVM (Kotlin 2.3.0 / Java 21), Clikt CLI, Ktor client, Ktoml, zstd-jni.
  - Gradle (`packpack/build.gradle.kts`, GraalVM Native Image plugin `org.graalvm.buildtools.native`).
  - GraalVM native binary executable (`pypackpack`), intended to also publish as a Kotlin/JVM Maven library (`org.thisisthepy.python.multiplatform:packpack`).
- **Goal in Ecosystem**:
  - Owns all heavy lifting for Python packaging: Python distribution acquisition/management, dependency resolution (`uv`), cross-compilation environment (`crossenv`), C/C++/Rust extension compilation, bundling into resource/wheel formats.
  - Serves as both a standalone CLI and an internal library for `toolchain`.
- **Gap to Goal**:
  - **Publish to Maven**: `packpack/build.gradle.kts` lacks `maven-publish` plugin and publication block. Nothing outside can resolve `pypackpack` until published.
  - **Implement `bundle` stage**: All bundlers in `bundle/` (`BinaryBundler.kt`, `FatWheelBundler.kt`, `SingleWheelBundler.kt`, `WheelPatchBundler.kt`) are 2-4 line empty placeholders. Bundle type `resource` (needed by `PythonMultiplatform` and `toolchain`) must be implemented.
  - **Fix destination disagreement**: `python install` downloads to `<project>/.venv` or `<project>/<target>`, while `python list`/`find`/`uninstall` search `~/.pypackpack/python/<version>`.
  - **Auto-install build tools**: `Meson.installMeson()` exists but is never invoked before build execution.
  - **CLI pass-through flags**: `add`/`remove`/`sync`/`tree` hardcode `extraArgs = null`.
  - Non-Meson compiler backends (Clang, MSVC, NDK, XCode, Emscripten, Cargo) and compilation/minification middleware (Nuitka, Cython, Lpython) are empty placeholders.
- **Dependency Direction**:
  - `pypackpack` has no dependencies on `toolchain` or `pythonx-compose`.
  - `toolchain` depends on `pypackpack` as a Maven library.
- **Unverified**:
  - Wheel patch generation (`WheelPatchBundler.kt`) and incremental upload logic (placeholder files only).

---

### `pythonx-compose`

- **Observed Current State**:
  - Intended Python UI framework wrapping Kotlin Compose Multiplatform.
  - Two unmerged legacy generations:
    1. Chaquopy branch (`pythonx/compose/`): 37 files (28 empty placeholders), using `from java import jclass` and scanning mangled AndroidX Kotlin symbols (`TextKt.Text-fLXpl1I`) via `__dict__` reflection.
    2. Desktop JVM branch (`pythonx/compose/lite/`): uses JPype with hand-written Kotlin wrappers.
  - Demo notebook `UI.ipynb` target API works against PyREPL's `app/src/androidMain/python/` implementation (`main.App`, `App.update`, `DefaultIcons`, `Column`, `Row`, `Spacer`, `TextField`), NOT `pythonx-compose`'s current codebase.
- **Language / Build System / Distribution**:
  - Python (3.8+).
  - `setuptools` (`pyproject.toml` building package `pycomposeui` version 0.0.1).
  - PyPI / wheel package (`pycomposeui`).
- **Goal in Ecosystem**:
  - Provide Pythonic Compose wrappers (`pythonx.compose.*`) for writing UIs in Python.
  - Adapt Kotlin Compose bindings generically via `__getattr__` and caching (mapping named arguments, threading composer, adapting `content=` lambdas) rather than hand-writing wrapper functions per widget.
- **Gap to Goal**:
  - Delete raw-AndroidX dynamic reflection branch (`TextKt.Text-fLXpl1I`) and JPype dependency.
  - Migrate runtime to `PythonMultiplatform`'s static upcall table and Python proxy injection (`sys.modules`), replacing `jclass`.
  - Port PyREPL's widget and runtime model (`app/src/androidMain/python/`) into `pythonx-compose`.
  - Implement generic Python adapter (`__getattr__` on `pythonx.compose.material3` resolving underlying `androidx.compose.material3` bindings lazily).
  - Replace string-based type name parsing in `remember_saveable` with `PythonMultiplatform`'s `PyValue` type tags.
- **Dependency Direction**:
  - `pythonx-compose` -> `PythonMultiplatform` (depends on `PythonMultiplatform`'s FFI binding surface and Python proxy module at runtime).
  - Uses `.pyi` stubs emitted by `toolchain` / `PythonMultiplatform` plugin for editor autocompletion.
- **Unverified**:
  - Full hot-reload integration with Jupyter notebook server outside `UI.ipynb` static cells.

---

### `toolchain-legacy`

- **Observed Current State**:
  - Legacy build toolchain source tree (`toolchain/` directory) derived from Kivy (`kivy-ios` and `python-for-android`), last worked on in February 2024.
  - Python CLI entry points in `setup.py`: `toolchain`, `toolchain_targetver`, `toolchain_targetos`.
  - Cross-compiles CPython 3.11 and native recipes (NumPy, OpenSSL, Kivy) for Android, iOS, macOS, Linux, and Windows (mingw).
  - Depends on external forks: `thisisthepy/toolchain-ios` and `thisisthepy/toolchain-android`.
- **Language / Build System / Distribution**:
  - Pure Python (3.9+).
  - `setup.py` (package name `toolchain`, version `3.11.0.1`).
  - PyPI package / Python CLI tool (`pip install git+https://...`).
- **Goal in Ecosystem**:
  - **Legacy reference implementation only.**
  - Kept for historical reference to understand pre-2024 CPython cross-compilation and recipe management.
  - Replaced entirely by `pypackpack` (cross-compilation and packaging in Kotlin via `uv`/`crossenv`/`meson`) and `toolchain` (Gradle plugin interface).
- **Gap to Goal**:
  - Deprecated/superseded. No active development or gap to close — retained for historical reference only.
- **Dependency Direction**:
  - None. Independent legacy Python CLI tool.
- **Unverified**:
  - Modern Xcode 15+ / NDK 26+ compatibility of legacy recipes (untested).

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

**Update — the walker now exists on one path, and both of those questions have answers.**
`python-multiplatform-gradle-plugin` gained `generatePythonArtifactBindings`, and
`ksp-fixtures/artifact` carries a resolved `junit:junit:4.13.2` through ASM, a generated
`FunctionTableFragment`, `UpcallTable` and `PythonProxySource` to
`from junit.runner.Version import id` answering `"4.13.2"`. ROADMAP §16 records it in full; three
things in it change what this section says:

- **The walker's fragments are a second aggregator (`ArtifactTable`), not additions to KSP's
  `FunctionTable`.** One `UpcallTable`, one fragment interface, one Python namespace — but
  `FunctionTable` keeps meaning "every module in this graph compiled with the processor", which is
  what `ksp-fixtures/app` asserts and what a consumer's own table should not silently outgrow.
  Install site: `UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)`.
- **ASM alone does not reach `androidx.compose.material3`.** It reaches Java statics and Kotlin
  `@JvmStatic`s. A Kotlin *top-level* function compiles onto a package-private multi-file part
  (`kotlin/text/StringsKt__IndentKt`) behind a facade Kotlin cannot name, and its extension receiver
  is indistinguishable from an ordinary parameter in bytecode. The `@Metadata` *kind* is readable
  with ASM; the payload that carries the Kotlin names is `d1`/`d2` and needs `kotlin-metadata-jvm`.
  So AndroidX is reachable, as this section says — one library short of it, not one design short.
- **klib is the easier half, not the harder one.**
  `org.jetbrains.kotlin.library.abi.LibraryAbiReader`, already in the `kotlin-compiler-embeddable`
  this build pins, read `python-multiplatform-iosX64Main.klib` and returned 441 top-level
  declarations under their Kotlin qualified names, with `isSuspend`,
  `hasExtensionReceiverParameter`, value parameters and Kotlin (unerased) types. There is no facade
  problem because a klib records the Kotlin declaration; and since a generated fragment is Kotlin
  source compiled into the consumer's own binary with the klib on its compile classpath, the *call*
  is an ordinary Kotlin call with no reflection. The open question that remains is not feasibility
  but cost: `@ExperimentalLibraryAbiReader`, and a ~60 MB compiler artefact on the plugin classpath
  versioned against the consumer's Kotlin rather than the plugin's.

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

**The wrapping happens in Python, not in Kotlin.** `pythonx.compose.material3` is Python code that
uses the generated `androidx.compose.material3` bindings and presents a Pythonic API over them.
There is no hand-written Kotlin wrapper module in between — that is what the existing
`io.github.thisisthepy.pycomposeui` `RuntimeKt` and `Runtime_androidKt` are, and they are the shape
being moved away from.

This settles a question that otherwise looks hard. A `@Composable` function is, after compilation,
an ordinary function taking `$composer` and `$changed`; a binding generated from the artefact
exposes that signature as it is, and the Python wrapper passes the composer as a value — which is
what `pythonx-compose`'s `Composable` class already does. So nothing on the Kotlin side needs a
`@Composable` callable type, and the upcall trampolines do not have to preserve a `@Composable`
context.

**`pythonx` adapts generically; it does not wrap function by function.** Writing a Python wrapper
for every Composable is the cost that made the existing `pythonx-compose` 37 files of which 28 are
empty. The conversions are rules, not per-function decisions: a Pythonic name maps to the original,
the composer and change flags are threaded the same way every time, and a `content=` callable is
adapted the same way every time. So the layer should resolve on demand — a module `__getattr__`
that finds the corresponding binding, adapts it once and caches it — rather than enumerate.

That trades away IDE completion, which is exactly why the plugin generates `.pyi` from the same
metadata: the stubs carry the Pythonic names and signatures the adapter will produce, so an editor
sees a fully enumerated surface while the runtime enumerates nothing.

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
