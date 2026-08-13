# Python Multiplatform

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows%20%7C%20wasmJs-lightgrey.svg)](#supporting-multiplatforms)

<!--
CI workflows have been added to this repository, but a build/CI badge is intentionally omitted for now.
The workflows have not yet been observed running successfully on GitHub Actions.
Adding a badge before verifying a green build would be asserting a state we haven't seen.
Once the CI is proven to run and pass, the badge can be added here — pointing at this repository.

(Previously, there were broken badges here pointing to different repositories or carrying template placeholders).
-->



### Description

A multiplatform solution to use Python with Kotlin interoperably.

Thanks to many contributors who develop dependent packages for this python-kotlin library production.


#### Supporting multiplatforms:

- Android (arm64, x86_64) with the [official CPython Android builds](https://www.python.org/downloads/)
- iOS (arm64, simulator) with [Python-Apple-support](https://github.com/beeware/Python-Apple-support) (≤ 3.14) or python.org's official XCframework (≥ 3.15)
- macOS (arm64, x86_64) with [Python Standalone Builds](https://github.com/astral-sh/python-build-standalone)
- Linux (x86_64) with [Python Standalone Builds](https://github.com/astral-sh/python-build-standalone) — the download/build wiring targets it, but the test suite has never actually been run on Linux in this repository (no Linux CI run yet, no local report); see ROADMAP §14b.
- Windows (x86_64) with [Python Standalone Builds](https://github.com/astral-sh/python-build-standalone) — same caveat as Linux, unverified.
- wasmJs (browser, Node) with CPython built here for `wasm32-emscripten` — see `docs/wasm-design.md`

The desktop target (macOS/Linux/Windows) is one Kotlin/JVM target reached through Panama at
runtime, not three separate Kotlin/Native targets — so "Linux/Windows support" above means the
build downloads and links against the right archive for that OS, not that the suite has been
observed passing there.

The interpreter is not vendored into this repository. Gradle downloads it per platform,
verifies it, and extracts it at build time — see `docs/python-version-acquisition.md`. The
version is set in `gradle.properties`.

** Since Xcode only runs on macOS, you need macOS to build this repo for iOS.


### Template ToDo list
- [x] Bring python embed API for Kotlin/JVM targets (Windows, Linux, macOS, Android).
- [x] Bring python embed API for Kotlin/Native targets (iOS).
- [x] Python interop API (Binder) for Kotlin side.
- [x] Kotlin interop API (Binder) for Python side. (Python calling Kotlin works and is tested down
      to a GraalVM native image — but cross-boundary reference cycles are not collected yet, and
      calling still goes through a generated `_pm_bind`-style table rather than plain
      `obj.method(x)` syntax. See ROADMAP §7 and §14b.)

___

## Build Manually

#### (1) Clone this repo

    git clone https://github.com/thisisthepy/python-multiplatform PythonMultiplatform

Checkout `develop` for the active development line, or a tagged release for a specific version.
(A previous revision of this section named a different repository,
`python-multiplatform-mobile`, and `@develop`/`@python3.13`-style refs that are not valid git clone
syntax — corrected against this repository's actual `origin` remote and layout.)

#### (2) Build the Gradle project

This is a Kotlin Multiplatform project targeting Android, iOS, desktop (macOS/Linux/Windows) and
wasmJs. The two modules that matter for building and trying it out:

* `python-multiplatform/` — the library itself: the FFI layer (`EmbedAPI`, one `expect`/`actual`
  per platform), the object model built on top of it (`PyObject` and friends), and the
  Python → Kotlin upcall machinery. See `python-multiplatform/src/commonMain/README.md` for the
  layering rules and `docs/architecture.md` for the design.
* `sample/` — a Compose Multiplatform demo app exercising the real API (not template boilerplate):
  `Python3.initialize()`, publishing a Kotlin-built `PyList` into Python, evaluating an expression,
  and a Python → Kotlin upcall through the generated table. Run it with `./gradlew :sample:run`
  (desktop) — see `sample/src/commonMain/.../PythonDemo.kt` for what it does and ROADMAP §13 for
  what it demonstrates on each platform.

There is no `composeApp` module in this repository — that name is Compose Multiplatform's default
project-template layout, and this repository does not use it. `iosApp/` is a real directory here
(the Xcode project for the sample's iOS entry point).

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
and [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform).

---

## Use Pre-Built Package

**Unverified — likely not published anywhere yet.** This section previously listed Maven Central
and JitPack coordinates as though the library were already published there. Checked during a
2026-08-13 documentation audit:

- Maven Central: `https://repo1.maven.org/maven2/io/github/thisisthepy/` returns `404 Not Found`
  (no `io.github.thisisthepy` group present at all).
- JitPack: its build API (`https://jitpack.io/api/builds/com.github.thisisthepy/python-multiplatform-mobile`)
  reports no build record for that artifact.
- The version this section quoted (`0.0.1`) does not match what the build actually produces —
  `python-multiplatform/build.gradle.kts` derives the library version from the configured Python
  version plus an `-alpha01` suffix (e.g. `3.14.7-alpha01`), not a hand-set `0.0.1`.

Both are negative results, not a documented "we don't publish this" decision — so treat this
section as aspirational until a release is actually confirmed on one of these, rather than as
instructions that work today. Until then, build from source per "Build Manually" above.

---

## Usage

**The example previously here did not match any version of this library's API** — it called
`Python3Library()`, `Pointer`, and raw `Py_*`/`py!!.Py_*` functions with force-unwrapped nullables
on undeclared `python`/`py`/`mathModule` variables, none of which exist in this codebase. It looks
like an early sketch of a design this library did not end up taking. Replaced below with the
object-model API that exists today, matching `sample/src/commonMain/.../PythonDemo.kt` (the
sample's actual startup code — see it for the fuller version, including the upcall half):

```kotlin
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.collections.PyList

fun main() {
    Python3.initialize()

    // Kotlin -> Python: a real Python list of real Python ints, published as a global in
    // __main__ rather than assembled by formatting a string of Python source.
    val numbers = PyList.fromList(listOf(2L, 3L, 5L, 7L, 11L).map { PyInt.from(it) })
    Python3.import("__main__").setAttr("kotlin_numbers", numbers)

    // Python -> Kotlin: evaluate an expression and read the result back through the object
    // model. `258` is Py_eval_input, CPython's own compiler-mode token for "a single expression"
    // (as opposed to Py_file_input's "a sequence of statements", which `Python3.exec` uses).
    val globals = Python3.import("__main__").dict
    val result: PyObject = Python3.eval("sum(kotlin_numbers) * 2", 258, globals, globals)
    println("${result.Type.name}: $result")   // int: 56

    // Statements, for side effects rather than a value:
    Python3.exec("print('hello from python')")

    // No close()/Py_DecRef anywhere above, and none is missing: every wrapper registers a
    // cleaner in its own constructor, and the collector releases the underlying CPython
    // reference once the Kotlin wrapper itself becomes unreachable (ROADMAP §4).
}
```

### Android: call `PythonBootstrap.initialize` instead of `Python3.initialize`

Android is the one platform where the example above is not the whole story, because CPython reads
its standard library off the filesystem and the Android artifact
(`io.github.thisisthepy:python-multiplatform-android`, which the root coordinate resolves to for an
Android consumer) ships that library inside the APK, as `assets/<abi>/lib/python<X.Y>/`.
`Py_Initialize()` cannot read a stdlib out of an asset archive, so it has to be unpacked to
app-private storage once and `PYTHONHOME` has to point at the prefix.

`PythonBootstrap` (in `androidMain`, so it comes with the AAR) does that and then starts the
interpreter. It is the *only* extra step:

```kotlin
import python.multiplatform.env.PythonBootstrap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonBootstrap.initialize(this)
        // CPython is up; everything in the Usage example above works from here.
    }
}
```

Idempotent, so it is safe from every entry point an app has — an `Activity`, an `Application`, a
`WorkManager` worker. It returns a `Staging` describing what it did (`unpacked`, `abi`,
`fileCount`, `bytes`, `elapsedMillis`, `prefix`, `stdlibDir`) for apps that want to show a
first-launch progress state; ignore it otherwise.

**What it costs.** 804 files, 20,147,280 bytes. Measured from a real app's `onCreate` on both
emulators — a fresh install, then a relaunch:

| | API 26 | API 36 |
|---|---|---|
| first launch (unpacks) | 237 ms | 480 ms |
| every later launch (stamp check) | 4 ms | 28 ms |

(`PythonBootstrapTest` measures the same two paths inside an already-warm instrumentation process
and sees 71–103 ms and 1 ms. The cold-start figures above are the ones a host app actually pays.)

The skip is decided by a stamp file naming the library version, the ABI, and the host app's
`versionCode`/`lastUpdateTime` — written *after* the last file, so a copy interrupted by the app
being killed cannot be mistaken for a complete one, and an APK upgrade that restages assets over a
surviving `filesDir` re-unpacks even when the Python version has not moved. Probing the result for
one entry (`encodings/` exists, the directory is non-empty) gets both of those wrong; that is what
this replaced.

If you need the pieces separately — staging without starting the interpreter, or a prefix somewhere
other than `filesDir` — `PythonBootstrap.stageStdlib(context, prefix)` is the first half, and
`initialize` takes the same `prefix` parameter.

If `Py_Initialize()` is reached without any of this, it does not fail — it **aborts the process**
with `Fatal Python error: Failed to import encodings module`. `Python3.initialize` runs a
`PYTHONHOME` pre-flight check that turns most of the ways this goes wrong into a catchable
`IllegalStateException` first, but the check cannot run before it is reached; `PythonBootstrap` is
what stops it being reached.

What the artifact does *not* carry, deliberately: CPython's own `test` package and its C headers.
See `copyAndroidPythonAssets` in `python-multiplatform/build.gradle.kts` for the measurements
behind that (it is 38% of the AAR) and ROADMAP §15d for the packaging decision.

`Python3.exec` is deliberately built on `PyRun_String` rather than `PyRun_SimpleString`: the latter
calls `PyErr_Print()` internally, which prints *and clears* a failure before Kotlin ever gets a
chance to inspect it — so a Python-level exception would always surface as a generic message
instead of the real exception type. A failure from `exec`/`eval` instead throws a `PyException`
carrying the real Python exception.
