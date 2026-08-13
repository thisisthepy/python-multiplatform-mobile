package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.thisisthepy.python.multiplatform.demo.ui.App


/**
 * The interpreter comes up before the canvas for the same reason desktop's `main.kt` brings it up
 * before the window: every section of the screen reads from it. Unlike desktop, there is no
 * `PYTHONHOME` to hand it -- `PythonHomeCheck.wasmJs.kt` makes that check a no-op on this target,
 * because the stdlib is baked into Emscripten's virtual filesystem at build time rather than
 * looked up from an environment variable, and `python.wasm`/`python.mjs` have to be staged next
 * to the compiled Kotlin for [python.multiplatform.ffi.Python3.initialize] to reach an interpreter
 * at all -- `python-multiplatform/build.gradle.kts`'s `KotlinJsTest` `doFirst` is where that
 * staging exists today, wired to the library's own node test task and not yet to this app's
 * browser bundle.
 *
 * Sections 5 and 6 (a Python-constructed Kotlin class, its companion) are new here --
 * `ProxyDemo.wasmJs.kt`'s doc says why. Section 7 (`await`) is deliberately never reached: see
 * `ui/App.kt`'s `AwaitSection`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    PythonDemo.start()
    println("runtime : ${PythonDemo.runtimeSummary()}")
    println("eval    : ${PythonDemo.DEFAULT_EXPRESSION} -> ${PythonDemo.evaluate(PythonDemo.DEFAULT_EXPRESSION)}")
    println("table   : ${UpcallDemo.tableSummary()}")
    println("upcall  : ${UpcallDemo.callFromPython()}")
    println("proxies : ${PythonDemo.proxyInstallReport()}")
    println(PythonDemo.classProxy())
    println(PythonDemo.staticSurface())
    // Deliberately not called: PythonDemo.awaitFastPath() and PythonDemo.awaitSuspending() both
    // reach `import asyncio`, which traps this wasm instance rather than raising. See
    // `ProxyDemo.wasmJs.kt`'s `awaitSuspendingDemo` doc.
    println("await   : skipped on wasmJs -- import asyncio traps this target, see ProxyDemo.wasmJs.kt")

    ComposeViewport(document.body!!) {
        App()
    }
}
