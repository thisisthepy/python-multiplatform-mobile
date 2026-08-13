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
 *
 * `false`, with what each would need, on:
 *
 * - **wasmJs.** Nothing crosses into Python here but a *bound* callable: `UpcallEntry.bind` builds
 *   one over the single `@WasmExport`ed `pmp_invoke`, and resolution happens in Kotlin. Publishing
 *   `_pm_resolve`/`_pm_invoke` would need new `@WasmExport`s of their own -- and, because
 *   `@WasmExport` is honoured only in the compilation that produces the `.wasm`, they would have to
 *   be declared by the *embedding application* (the three-line `wasmJsTest/UpcallExports.kt` shape),
 *   not by this library. The async half cannot follow even then: `import asyncio` traps in this
 *   CPython build, so every suspending proxy would fail at its first `await`.
 * - **Android/ART.** `androidMain`'s `UpcallEntry.publish` installs `_pm_resolve`, `_pm_bind`,
 *   `_pm_release` and `_pm_cancel` through C shims in `artMain/cinterop/jni_onload.def`, and there
 *   is no `_pm_invoke` among them. What is missing is only the C half: a
 *   `pmp_upcall_invoke_free_meth(self, args)` beside the existing `pmp_upcall_invoke_meth` that
 *   reads the handle out of `args[0]` and the argument tuple out of `args[1]`, and calls the
 *   `UpcallCallbacks.invoke(long, long)` method ID `jni_onload.def` already holds. No Kotlin is
 *   missing on that target -- [python.native.ffi.UpcallCallbacks.invoke] is already the right
 *   shape. It is not done here because it cannot be *run* here: the Android path needs an
 *   emulator, and untested C in the JNI shim is worse than a recorded gap.
 */
expect val publishesProxyEntryPoints: Boolean
