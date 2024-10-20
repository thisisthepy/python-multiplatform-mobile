package org.thisisthepy.python.multiplatform.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import python.multiplatform.ffi.Python3


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            App {
                runOnUiThread {
                    initPython()
                }
            }
        }
    }

    @Synchronized
    fun initPython() {
        Python3.initialize()
    }

    override fun onDestroy() {
        Python3.finalize()
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
