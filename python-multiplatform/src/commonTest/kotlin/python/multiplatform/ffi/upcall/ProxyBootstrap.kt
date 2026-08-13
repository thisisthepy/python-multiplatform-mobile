package python.multiplatform.ffi.upcall

/**
 * Whether this target's upcall bootstrap publishes the two raw entry points the *generated proxy
 * module* needs -- `_pm_resolve` and `_pm_invoke` -- into `__main__`.
 *
 * This is a per-target constant rather than something read back out of Python at run time, and
 * that is the whole point of it. Reading `'_pm_invoke' in globals()` would make
 * [PythonProxyInstallTest] agree with whatever the bootstrap happens to do today: a target that
 * silently stopped publishing would take the "documented refusal" branch and stay green. That is
 * exactly the failure this file exists because of -- `PythonProxyInstallTest` lived in
 * `desktopTest` alone, so the four other targets were never asked the question at all, and
 * `UpcallEntry.publish` and [PythonProxySource] disagreed about the bootstrap for as long as the
 * generator has existed.
 *
 * `true` on the targets whose boundary shim publishes both names:
 *
 * | target | bootstrap |
 * |---|---|
 * | desktop | `ctypes.CFUNCTYPE` over the Panama upcall stubs in `UpcallStub` |
 * | iOS, androidNative | `PyMethodDef`s installed by `nativeMain`'s `UpcallEntry.publish` |
 * | Android/ART | the same `PyMethodDef`s, with C shims in `jni_onload.def` behind `ml_meth` |
 *
 * ### What the ART row cost, and why the note that used to be here was wrong
 *
 * It said one C function was missing: a `pmp_upcall_invoke_free_meth` beside the existing
 * `pmp_upcall_invoke_meth`, reading the handle out of `args[0]` and the tuple out of `args[1]` and
 * published as `_pm_invoke`. That was true, and it was not the whole of it.
 *
 * With only that added, every test in [PythonProxyInstallTest] got *past* the generated module's
 * entry-point guard and failed one line later -- on `pmp_api26` and `pmp_api36` alike, 11 of 11 --
 * with `TypeError: bad argument type for built-in operation`. `pmp_upcall_resolve_meth` read its
 * argument with `PyUnicode_AsUTF8` alone, and [PythonProxySource]'s `_pm_lookup` sends `bytes`,
 * because desktop reaches its resolver through `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p`
 * refuses a `str` outright. `nativeMain`'s `pmResolveMethod` had already been taught both spellings
 * for exactly that reason; the ART shim had not, and nothing had ever asked it to -- the only caller
 * it had, `androidInstrumentedTest`'s `bindUpcallOrNull`, passes a `str`.
 *
 * The note could not have found that, because it was written from reading and the second half only
 * exists at run time. It took an emulator, which is the same shape of gap this file exists to close.
 *
 * `false`, with what it would need, on:
 *
 * - **wasmJs.** Nothing crosses into Python here but a *bound* callable: `UpcallEntry.bind` builds
 *   one over the single `@WasmExport`ed `pmp_invoke`, and resolution happens in Kotlin. Publishing
 *   `_pm_resolve`/`_pm_invoke` would need new `@WasmExport`s of their own -- and, because
 *   `@WasmExport` is honoured only in the compilation that produces the `.wasm`, they would have to
 *   be declared by the *embedding application* (the three-line `wasmJsTest/UpcallExports.kt` shape),
 *   not by this library. The async half cannot follow even then: `import asyncio` traps in this
 *   CPython build, so every suspending proxy would fail at its first `await`.
 */
expect val publishesProxyEntryPoints: Boolean
