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

## Attaching is not enough — someone has to reach an eval-loop checkpoint

`_Py_HandlePending` is the only place CPython merges the free-threaded build's deferred
reference-count queue, processes QSBR-deferred frees, runs a *scheduled* cyclic collection and
drains pending calls and signals. Nothing in the C API calls it; only the `_CHECK_PERIODIC` uop
that opens every Python-level frame does. So an embedder that never executes bytecode never
reaches one, and a reference the cleaner gave back on a free-threaded build stays unreclaimed
however long you wait — the count is right, the memory is not returned.

`Python3.drainPendingReleases()` reaches one, by calling a cached empty Python function
(measured 355 ns, against 11 µs for `exec("pass")`). It also runs automatically once every
`Python3.autoDrainInterval` outermost `withGIL` scopes, which defaults to on for free-threaded
builds and off otherwise. **Never take a checkpoint from a cleaner**: the queue belongs to the
thread that *owns* the object, so it would drain nothing while running Python exactly where §1
proved that deadlocks. See ROADMAP §9.

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
