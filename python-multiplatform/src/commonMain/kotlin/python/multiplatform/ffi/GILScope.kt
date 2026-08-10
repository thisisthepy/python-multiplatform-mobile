package python.multiplatform.ffi

import python.native.ffi.PyGILState_Ensure
import python.native.ffi.PyGILState_Release
import python.native.ffi.PyEval_SaveThread
import python.native.ffi.PyEval_RestoreThread
import python.native.ffi.NativePointer

public class ThreadGILState(var state: Int = 0, var depth: Int = 0)

public expect fun getThreadGILState(): ThreadGILState
public expect fun setThreadGILState(state: ThreadGILState?)

public inline fun <T> withGIL(block: () -> T): T {
    val tState = getThreadGILState()
    val isOutermost = tState.depth == 0
    if (isOutermost) {
        tState.state = PyGILState_Ensure()
    }
    tState.depth++
    try {
        return block()
    } finally {
        tState.depth--
        if (tState.depth == 0) {
            val stateToRelease = tState.state
            PyGILState_Release(stateToRelease)
        }
    }
}

public inline fun <T> withoutGIL(block: () -> T): T {
    val save = PyEval_SaveThread()
    try {
        return block()
    } finally {
        if (save != null) {
            PyEval_RestoreThread(save)
        }
    }
}
