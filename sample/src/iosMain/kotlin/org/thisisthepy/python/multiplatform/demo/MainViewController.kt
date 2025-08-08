package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.ComposeUIViewController
import python.multiplatform.ffi.Python3


fun MainViewController() = ComposeUIViewController {
    App {
        Python3.initialize()
    }
}
