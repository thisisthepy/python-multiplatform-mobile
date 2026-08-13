package python.multiplatform.ffi.upcall

/**
 * `wasmJsMain`'s `UpcallEntry.publish` installs all five names, as `PyCFunction`s over the single
 * `@WasmExport`ed `pmp_invoke` that an embedding application already declares -- told apart by the
 * op code boxed in each one's `self`, not by a second export.
 *
 * Flipped from `false` only after the ten synchronous contracts below were watched to pass under
 * `wasmJsNodeTest`; the constant is the claim, not the cause. See [publishesProxyEntryPoints]'s
 * "What the wasmJs row used to say, and why it was wrong" for the reasoning it replaced.
 */
actual val publishesProxyEntryPoints: Boolean = true

/**
 * **`import asyncio` traps this wasm instance**, taking the Node process with it rather than
 * raising something a test could assert on. `docs/upcall-async-design.md` §9.5 has the
 * import-by-import measurement: `selectors` is where it goes, and `asyncio` reaches it through it.
 *
 * This is the one half of the proxy surface the dispatcher above did not buy back.
 */
actual val proxyBootstrapSupportsAsyncio: Boolean = false
