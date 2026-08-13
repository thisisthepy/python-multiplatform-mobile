# iosMain — rules

iOS-specific sources. Depends on `nativeMain`; CPython arrives as `Python.xcframework`.

## The framework ships no standard library

`Python.xcframework` contains the interpreter binary and headers and nothing else.
`Py_Initialize()` needs `PYTHONHOME` to point at the stdlib on disk, and there are two different
ways it fails to if that's wrong — which one you get depends on *what's running*, not just on what
`PYTHONHOME` says, and only one of them is easy to diagnose.

**A missing or empty prefix aborts.** `Py_Initialize()` calls `Py_FatalError()` and kills the
process with `Fatal Python error: Failed to import encodings module` on stderr. No Kotlin
exception, no interpreter left afterward, but at least it is fast and it says what happened.
`Python3.initialize()` runs a pre-flight check before `Py_Initialize()` is ever reached (see
`python.multiplatform.env.PythonHomeCheck`) that catches this shape of misconfiguration and turns
it into a catchable `IllegalStateException` naming the path instead.

**A `PYTHONHOME` under a sandboxed path can hang instead — with 0% CPU and nothing in the
console.** Reproduced directly (ROADMAP, 2026-08-13, "PYTHONHOME on an external volume hangs the
app instead of failing it"): the *installed simulator app* parked forever inside `open$NOCANCEL`
importing `encodings` from a path under `/Volumes/` — a blank window, no sandbox denial, no Python
error anywhere in the log. `PythonHomeCheck`'s own filesystem probe cannot promise to catch this
either: it reads the same directory through the same kind of syscall CPython's import machinery
does, and a path whose access is *parked* rather than *denied* can park the check identically.

**The recipe below only works for the test binary, not the app — do not assume it carries over.**
The build extracts the stdlib from the BeeWare support archive into `build/python-stdlib/`, and
the simulator *test* task points `SIMCTL_CHILD_PYTHONHOME` at it directly (`simctl` only forwards
environment variables prefixed `SIMCTL_CHILD_`, which is what makes the test binary see it at
all). That path is still under this repo's workspace, which lives on an external volume (see
CLAUDE.md) — fine for the test binary, which `simctl` launches with fewer sandbox restrictions
than an *installed app*, but exactly the shape that hangs an app rather than starting it. An app
needs its stdlib staged **inside its own bundle or installed container**, with `PYTHONHOME`
pointed at that installed path, not at the workspace — see the reproduction recipe in ROADMAP for
the two extra steps (`rsync`ing the stdlib into the built `.app` and resolving
`SIMCTL_CHILD_PYTHONHOME` from `simctl get_app_container` after install) this repository does not
yet automate.

## No boundary, in either direction

Python and Kotlin share one binary. Downcalls are direct cinterop calls, and an upcall from
Python into Kotlin is a plain function call. The composition and calling-convention work that
shapes the Android and desktop designs has no counterpart here.
