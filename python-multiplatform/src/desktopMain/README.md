# desktopMain — rules

JVM on macOS, Linux and Windows, reaching CPython through Project Panama.

## Always `invokeExact`, never `invoke`

    // WRONG -- asType adaptation plus boxing of argument and result, on every call
    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invoke(list) as Long

    // RIGHT
    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invokeExact(list) as Long

This is not a micro-optimisation. Converting the bulk of these wrappers took a real C API call
(`PyList_Size` on `sys.path`) from **1015.95 ns to 2.65 ns** — about 380x. Before that, desktop
was the slowest FFI path in the project by two orders of magnitude, on the machine that is
fastest at everything else.

`invokeExact` requires the call site's static signature to match the handle's type exactly, and
it fails at runtime with `WrongMethodTypeException` rather than at compile time. A green build
proves nothing; the tests are the check.

## Describe pointers as `JAVA_LONG`, not `ADDRESS`

Both are 8 bytes and travel in the same register on a 64-bit ABI. `ADDRESS` makes Panama hand
back a `MemorySegment`, which then has to be unwrapped through per-call argument and return
filters — and those filters were reflective `MethodHandle` invocations building a segment on
every call. `JAVA_LONG` deletes them, and it is what makes the handle type
`(long, long, ...) -> long` so that `invokeExact` is reachable at all.

## Reflection belongs at link time, never per call

The backend resolves `java.lang.foreign` or `jdk.incubator.foreign` reflectively so the code
compiles against any JDK without importing either. That is correct and should stay. What must
never happen is reflection reaching the per-call path — that was the other half of the 1015 ns.
Resolve handles once per symbol at startup; after that a call is a handle invocation and
nothing else.

## String ownership

`withUtf8 { }` allocates, passes the address, and frees in a `finally`. Values owned by CPython
(`Py_GetVersion`, `PyUnicode_AsUTF8`) are read and **not** freed — freeing them is a
use-after-free.

Desktop's string marshalling costs about 200 ns per string, measured at 606.74 ns (10.5%) of a
whole `exec` call. That is roughly 12x cheaper than Android's, because Panama allocates
off-heap without crossing the boundary at all.

## Composition is not used here

Composing binder operations natively was measured and rejected for desktop: it would buy ~10%
and cost macOS/Linux Kotlin/Native targets, a packaging path, and permanent Windows asymmetry.
See ROADMAP §6. Android is different — there the same measurement gives 1.8x.

## Never hand-edit `reachability-metadata.json`

It is generated at build time by `generateDesktopReachabilityMetadata`, from `bindings.kt`,
`ShapeDowncalls.desktop.kt` and `Panama.kt`. There is no copy in `src/desktopMain/resources`; only
the non-derivable half (`reflection`, and any `resources` globs) is checked in, at
`python-multiplatform/native-image/reachability-metadata.base.json`.

Under GraalVM's closed world, a `FunctionDescriptor` that is not declared there does not fail the
build — it fails on the first call, with `MissingForeignRegistrationError`. So every new descriptor
has to reach the metadata by construction:

- A new CPython entry point is a `find("sym", ReturnType, Params...)` line in `bindings.kt`, and
  its shape is picked up automatically. A carrier type not in the generator's token table fails the
  build rather than being guessed at.
- A new **upcall** stub builder in `Panama.kt` must carry a `// @UpcallShape(returnType = "...",
  parameterTypes = [...])` marker directly above it; its descriptor is assembled reflectively and
  cannot be read out of the code. The generator refuses to run without the marker.

`ReachabilityMetadataTest` checks the shipped file from the other direction — against the
`MethodHandle.type()`s the loaded classes actually built — so a parser that stops recognising a
declaration form surfaces as a failing test, not as a binary that dies at a customer.

## CPython is loaded from disk, not from the image

`manager.kt` extracts `libpython` from a classpath resource, but a native image bakes registered
resources into the image heap: that copy was 19.4 MB of a 35.4 MB binary, written straight back out
to a temp file at startup. The library resource is therefore *not* registered, and `manager.kt`
falls back to `$PYTHONHOME/lib/` (`PYTHON_MULTIPLATFORM_LIBPYTHON` overrides an absolute path).
`PYTHONHOME` has to point at a real prefix regardless — `Py_Initialize` needs the matching stdlib —
so nothing is lost, and the binary is 16.7 MB. Loading the library out of the same prefix as the
stdlib also removes the version-skew hazard the jar copy had.
