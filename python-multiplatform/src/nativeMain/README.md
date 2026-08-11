# nativeMain — rules

Kotlin/Native sources shared by iOS **and** androidNative. Reaches CPython through cinterop, in
the same process, with no boundary to cross.

## Shared with iOS — check before adding anything

`artMain` (androidNative-only) depends on this, and so does `iosMain`. Anything added here is
compiled for iPhones too. JNI exports, `@CName` symbols intended for ART, and anything else
Android-shaped belong in `artMain`.

## There is no boundary here

Kotlin and CPython are in one binary. A call is a call. This is the fastest platform in the
project for FFI, and composition — which exists to avoid crossings — buys nothing. `actual`
implementations of composed operations should just do the work directly.

The same is true in the other direction: an upcall from Python into Kotlin is a plain function
call, not a runtime transition. The upcall cost that shapes the JVM design does not exist here.

## Lifetime

`PyAutoCloseable` uses `kotlin.native.ref.createCleaner`, which takes the resource as an
explicit argument precisely so the cleanup block does not capture the object. Keep it that way:
a cleanup action that reaches the wrapper keeps the wrapper reachable, and then it never runs.
