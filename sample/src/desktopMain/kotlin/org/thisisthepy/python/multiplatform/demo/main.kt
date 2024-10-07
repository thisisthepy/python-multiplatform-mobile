package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import python.multiplatform.ffi.Python3


fun main() = application {
    Python3.initialize()
    Window(
        onCloseRequest = { Python3.finalize(); exitApplication() },
        title = "PythonMultiplatform",
    ) {
        App()
    }
}
