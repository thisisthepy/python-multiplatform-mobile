package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.thisisthepy.python.multiplatform.demo.ui.App


/**
 * Not compiled: the `wasmJs` target is commented out in this module's `build.gradle.kts`.
 * `python-multiplatform` has a `wasmJs` target (ROADMAP §10), but it reaches CPython through
 * `python.mjs`/`python.wasm` staged next to the compiled Kotlin, which this app does not stage --
 * so [PythonDemo] would have no interpreter to talk to. Kept, and kept compiling-by-inspection,
 * so that turning the target on is an edit to the build file rather than an archaeology exercise.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
