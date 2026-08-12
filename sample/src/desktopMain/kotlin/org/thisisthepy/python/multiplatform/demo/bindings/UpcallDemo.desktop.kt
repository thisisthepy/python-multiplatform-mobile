package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.native.ffi.UpcallStub

/** `Py_eval_input`: compile an expression rather than a sequence of statements. */
private const val PY_EVAL_INPUT: Int = 258

/**
 * Resolve once, invoke many. Both handles are computed here and live on in `__main__`, so every
 * call afterwards passes an integer and never a string again -- which is the whole argument of
 * `docs/upcall-design.md` (a selector is fast because it is interned, not because a table
 * exists).
 *
 * `ctypes` stands in for the generated proxy type a finished binder would install. Two stub
 * shapes are in play and the difference is the point:
 *
 * - `(long) -> long` reaches zero-argument entries and carries nothing but the handle. That is
 *   all this demo could do before ROADMAP §13.
 * - `(long, PyObject *) -> PyObject *` is the argument-carrying trampoline. It is the shape a
 *   `PyCFunction` slot takes, so the same stub serves the proxy type when that lands.
 *
 * `internal` rather than `private` because `UpcallDemo.desktop.kt` next door calls it, and
 * `internal` rather than `public` because a `public` top-level function in this module would be
 * scanned into the generated table -- exposure is a blacklist here.
 */
internal fun installCtypesBridge() {
    val resolveAddr = UpcallStub.resolveHandleStubAddr
    val invokeAddr = UpcallStub.invokeHandleStubAddr
    val invokeWithArgsAddr = UpcallStub.invokeWithArgsStubAddr
    Python3.exec(
        """
        import ctypes

        _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)($resolveAddr)
        _pm_invoke = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)($invokeAddr)
        _pm_invoke_args = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
            $invokeWithArgsAddr
        )
        _pm_handle = _pm_resolve(b"$UPCALL_ENTRY_NAME")
        _pm_args_handle = _pm_resolve(b"$UPCALL_ARGS_ENTRY_NAME")
        """.trimIndent(),
    )
}

actual fun callKotlinFromPython(): String = try {
    val globals = Python3.import("__main__").dict
    val handle = Python3.eval("_pm_handle", PY_EVAL_INPUT, globals, globals).toString()
    if (handle == "-1") {
        "the name was not in the table (handle -1)"
    } else {
        val value = Python3.eval("_pm_invoke(_pm_handle)", PY_EVAL_INPUT, globals, globals)
        // The argument-carrying call: Python builds a real tuple, Kotlin reads a String and a
        // Long out of it and hands a String back.
        val described = Python3.eval(
            "_pm_invoke_args(_pm_args_handle, ('presses x3 = ', 3))",
            PY_EVAL_INPUT, globals, globals,
        )
        "handle $handle -> $value  ·  with args -> $described"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}
