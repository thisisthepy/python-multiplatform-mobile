package org.thisisthepy.python.multiplatform.demo.bindings

/**
 * Android has a boundary shim, and it is one entry point short of being able to carry the proxies.
 *
 * The earlier version of this file said Android had no shim at all and that nothing on the JNI
 * path published an address. That is not what the code says: `androidMain`'s
 * `python.native.ffi.UpcallEntry.publish` installs `_pm_resolve`, `_pm_bind`, `_pm_release` and
 * `_pm_cancel` as real `PyMethodDef`s, built by C shims in `artMain/cinterop/jni_onload.def` that
 * call back into `UpcallCallbacks` -- which is exactly why `UpcallThreadAttachTest` can drive
 * upcalls from a `threading.Thread` on a device.
 *
 * What is missing is one name: `_pm_invoke`, the unbound `(handle, args) -> result` form that every
 * call [python.multiplatform.ffi.upcall.PythonProxySource] generates uses, and that its entry-point
 * guard refuses to install without. On iOS and androidNative `UpcallEntry.publish` installs it as a
 * sixth `PyMethodDef`; on ART the `PyMethodDef`s are C, so the missing piece is C:
 *
 * - a `pmp_upcall_invoke_free_meth(PyObject *self, PyObject *args)` beside the existing
 *   `pmp_upcall_invoke_meth`, reading the handle out of `args[0]` and the argument tuple out of
 *   `args[1]` instead of out of `self`, and
 * - one more `{"_pm_invoke", ...}` entry in what `pmp_upcall_publish` installs.
 *
 * No Kotlin is missing: `UpcallCallbacks.invoke(long, long)` is already the right shape and already
 * has a JNI method ID. It is not done because it cannot be *run* without an emulator, and untested
 * C in the JNI shim would be a worse answer than a recorded gap --
 * `python.multiplatform.ffi.upcall.publishesProxyEntryPoints` carries the same note in the test
 * suite, so the day it lands the test that says "this target refuses" fails.
 */
actual fun installPythonProxies(): String =
    "unavailable on Android: the JNI bootstrap publishes _pm_resolve/_pm_bind/_pm_release/" +
        "_pm_cancel but no _pm_invoke, which the generated proxy module needs. The generated " +
        "table itself is installed -- see sections 3 and 4."

/** Not reachable: [installPythonProxies] never installs a proxy on this target. */
actual fun awaitSuspendingDemo(): String =
    "not wired on Android: the proxy module is not installed, so there is no `async def` to await."
