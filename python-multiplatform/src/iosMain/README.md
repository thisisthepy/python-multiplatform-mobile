# iosMain — rules

iOS-specific sources. Depends on `nativeMain`; CPython arrives as `Python.xcframework`.

## The framework ships no standard library

`Python.xcframework` contains the interpreter binary and headers and nothing else.
`Py_Initialize()` aborts the process with "Failed to import encodings module" unless the stdlib
is on disk and `PYTHONHOME` points at it.

The build extracts it from the BeeWare support archive into `build/python-stdlib/`, and the
simulator test task passes it through — note that `simctl` only forwards environment variables
prefixed `SIMCTL_CHILD_`, which is what makes the test binary see it.

## No boundary, in either direction

Python and Kotlin share one binary. Downcalls are direct cinterop calls, and an upcall from
Python into Kotlin is a plain function call. The composition and calling-convention work that
shapes the Android and desktop designs has no counterpart here.
