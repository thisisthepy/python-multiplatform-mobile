package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.native.ffi.UpcallEntry

/**
 * iOS has a boundary shim after all -- and it is not `ctypes`.
 *
 * `bindings/UpcallDemo.ios.kt` next door still says the shim is desktop-only, and that was true of
 * *this sample*. It has not been true of the library since `python.native.ffi.UpcallEntry` landed
 * in `nativeMain`: `UpcallEntry.publish` installs real `PyMethodDef`-backed builtins into a
 * namespace, which is the only route on this target because this project's `Python.framework`
 * ships no `_ctypes` (it exports `PyInit__abc` through `PyInit_time` and carries no `lib-dynload`),
 * so `ctypes.CDLL(None)` -- desktop's whole approach -- cannot be used here in either direction.
 *
 * ### The one thing that does not line up, and the shim for it
 *
 * `UpcallEntry.publish` installs `_pm_resolve`, `_pm_bind`, `_pm_release` and `_pm_cancel`, and
 * deliberately not `_pm_invoke`: on this target a handle is turned into a *callable* by `_pm_bind`
 * and invoked as an ordinary Python function, which is what keeps the handle out of Python's hands
 * as a separate argument.
 *
 * [PythonProxySource] wants the other arrangement, and disagrees on two counts:
 *
 * 1. Its entry-point guard refuses to install unless `_pm_invoke` is in `globals()`, and all of its
 *    generated calls are `_pm_invoke(handle, args)`. `UpcallEntry` publishes no such name. Its own
 *    support source also **redefines `_pm_bind`** to mean name -> handle rather than
 *    handle -> callable, so the native one has to be captured before it is overwritten.
 * 2. That support calls `_pm_resolve(name.encode('utf-8'))` -- **bytes**, which is what desktop's
 *    `ctypes.CFUNCTYPE(c_long, c_char_p)` needs. `UpcallEntry`'s `METH_O` entry point reads its
 *    argument with `PyUnicode_AsUTF8`, so bytes leave a `TypeError` pending and it returns NULL.
 *
 * Both are silent on desktop, because desktop's shim happens to bind exactly the names and the
 * argument types the generator was written against. Nothing outside `desktopTest` has ever called
 * [PythonProxySource.install], so nothing has ever exercised the other convention.
 *
 * Bridged here rather than left broken, because the bridge is short and shows exactly what the
 * library would have to do: capture the native `_pm_bind`/`_pm_resolve` under other names *before*
 * the generated support overwrites the first, then express the generator's two expectations in
 * terms of them.
 *
 * **Not verified at run time.** This target is compile-checked only in the work that added it --
 * running it needs a simulator, which was out of scope. Treat the report string as what the code
 * will say, not as something that has been seen.
 */
actual fun installPythonProxies(): String = try {
    val globals = Python3.import("__main__").dict
    if (!UpcallEntry.publish(globals.pointer)) {
        "the PyMethodDef bootstrap could not be published"
    } else {
        Python3.exec(
            """
            # Captured before PythonProxySource's support source rebinds `_pm_bind` to
            # name -> handle. `_pm_native_bind` is the handle -> callable form UpcallEntry
            # installed, and it is the only way to reach the invoke entry point on this target.
            _pm_native_bind = _pm_bind
            _pm_native_resolve = _pm_resolve


            def _pm_invoke(_h, _a):
                return _pm_native_bind(_h)(*_a)


            def _pm_resolve(_n):
                # The generated support calls `_pm_resolve(name.encode('utf-8'))` -- bytes,
                # because desktop's ctypes shim declares `c_char_p`. UpcallEntry's METH_O
                # entry point reads its argument with `PyUnicode_AsUTF8`, so bytes leave a
                # TypeError pending and the call returns NULL. Decoded back here.
                return _pm_native_resolve(_n.decode('utf-8') if isinstance(_n, bytes) else _n)
            """.trimIndent(),
        )
        val source = PythonProxySource.install()
        // `_PmModule` is support scaffolding and `_pm_t_N` is a generated metaclass; neither is a
        // proxy for a Kotlin type, which is what this number is meant to say.
        val classes = source.lineSequence().count {
            it.startsWith("class ") && !it.startsWith("class _Pm") && !it.startsWith("class _pm_t_")
        }
        "installed over PyMethodDef: ${source.lineSequence().count()} lines, $classes proxy classes"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * Not wired on iOS.
 *
 * The fast-path `await` ([awaitFastPathDemo]) is shared and runs here; this one needs a Kotlin
 * thread to resume the parked continuation while the interpreter drives its loop, and starting one
 * from Kotlin/Native means a `Worker` and a decision about which memory model the parked lambda
 * crosses. That is a real design question for the library rather than something to improvise in a
 * sample, so it says so instead of pretending.
 */
actual fun awaitSuspendingDemo(): String =
    "not wired on iOS: resuming a parked continuation needs a Kotlin/Native worker, " +
        "which this sample does not start. The fast-path await above does run here."
