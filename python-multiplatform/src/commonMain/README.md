# commonMain — rules

The object model and the `expect` declarations every platform implements.

## Layering — the object model must not reach past the FFI layer

    python.multiplatform.ffi        PyObject, PyType, collections, conversion
            |  may call
            v
    python.native.ffi               EmbedAPI: expect/actual over the C API
            |  may call (platform implementations only)
            v
    python.native.ffi.bindings      JNI externals / Panama handles

`PyObject` and friends call EmbedAPI functions and nothing below them. `bindings` is a platform
detail that only `EmbedAPI.<platform>.kt` may know about — it does not exist at all on the
native targets, which use cinterop.

A composed operation is a platform implementation difference, so it belongs in the FFI layer as
an `expect`/`actual`, **not** in the object model. Composition therefore does not require
reopening `PyObject` as `expect`/`actual`. See `docs/architecture.md`.

## Every C API call needs the GIL

Including reference counting. `withPython { }` and `withGIL { }` attach a thread state; a call
made without one is undefined behaviour, and the resulting segfault lands far from its cause.

This is true on free-threaded builds too. Dropping the global lock removes contention, not the
requirement that a thread be attached before touching any object.

## Reference conventions, stated at every call site

CPython's C API is inconsistent about ownership and getting it wrong is silent. Write which one
applies:

- `PyTuple_SetItem`, `PyList_SetItem` **steal** — do not release afterwards
- `PyTuple_GetItem`, `PyDict_GetItem` **borrow** — do not release; incref to keep
- most `PyObject_*` return **new references** — release when done

`RefCountTest` checks the balance in both directions. One too few leaks; one too many frees an
object still in use, and that crash surfaces somewhere unrelated.

## `expect` declarations cannot be `inline` in an intermediate source set

Combining `expect inline fun` with an intermediate source set's `expect`/`actual` crashes Kotlin
2.0.20 with `Internal error in file lowering`. 132 of the 147 `inline` markers here already
carry a compiler warning saying inlining gains nothing; drop the marker rather than working
around the crash.
