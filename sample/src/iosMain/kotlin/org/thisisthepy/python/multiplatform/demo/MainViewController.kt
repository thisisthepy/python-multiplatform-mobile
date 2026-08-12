package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.ComposeUIViewController
import org.thisisthepy.python.multiplatform.demo.ui.App


/**
 * The interpreter is brought up *before* the first composition rather than from inside it. The
 * old form passed `{ Python3.initialize() }` as `App`'s content lambda, so initialisation ran
 * from inside a composable body -- on every recomposition, and only once the user had expanded
 * the section that hosted it.
 *
 * On iOS the shipped framework carries no stdlib, so `PYTHONHOME` has to point at one before this
 * runs; see `python-multiplatform/src/iosMain/README.md`.
 */
fun MainViewController() = run {
    // Outside the content lambda on purpose: that lambda is `@Composable`, so anything in it runs
    // again on every recomposition.
    PythonDemo.start()
    ComposeUIViewController { App() }
}
