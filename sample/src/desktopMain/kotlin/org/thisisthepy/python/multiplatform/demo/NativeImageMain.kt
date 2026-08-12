package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_EXCLUDED_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.withGIL
import python.native.ffi.PyRun_SimpleString
import python.native.ffi.UpcallStub
import kotlin.system.exitProcess

/** How many times [DemoCounter.press] runs before Python is asked what the count is. */
private const val PRESSES = 7

/**
 * A headless entry point for the GraalVM native-image build path. `main.kt`'s `fun main() =
 * application { ... }` requires a running AWT/Compose window, which is not what this build path
 * is verifying -- it is verifying that Python can resolve a Kotlin declaration by name and call
 * it, inside a closed-world binary. Kept separate so a Compose window is never a precondition
 * for that question.
 *
 * The table this resolves against is **generated**: [installGeneratedUpcallTable] installs
 * `python.multiplatform.generated.FunctionTable`, which `python-multiplatform-ksp` emitted from
 * `ExposedToPython.kt` because `build.gradle.kts` applies the bindings plugin. This file used to
 * carry a hand-written `FunctionTableFragment` with a comment saying the generator did not exist
 * yet; a hand-written fragment keeps passing whether or not KSP ran at all, so what the
 * native-image check measured was narrower than what it appeared to measure.
 *
 * [DemoCounter.press] is called [PRESSES] times first, so the number Python reads back is one
 * this process produced rather than a constant a stub could also have returned.
 */
fun main() {
    println("=== GraalVM native-image upcall verification ===")

    Python3.initialize()

    installGeneratedUpcallTable()
    println("KOTLIN: table = ${upcallTableSummary()}")
    repeat(PRESSES) { DemoCounter.press() }

    val resolveAddr = UpcallStub.resolveHandleStubAddr
    val invokeAddr = UpcallStub.invokeHandleStubAddr
    println("KOTLIN: resolve stub @ 0x${resolveAddr.toString(16)}")
    println("KOTLIN: invoke  stub @ 0x${invokeAddr.toString(16)}")

    // Python resolves the entry to a handle by name exactly once, then calls back through that
    // handle -- the ObjC-selector-cache shape docs/upcall-design.md argues for, reached here via
    // ctypes function pointers instead of a generated proxy type.
    // Not a tty under Gradle/native-image, so sys.stdout is block-buffered: without an explicit
    // flush before the process exits (there is no Py_Finalize call on this path), every print()
    // below is silently lost even on success.
    val script = """
        import sys
        import ctypes

        try:
            resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)($resolveAddr)
            invoke = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)($invokeAddr)

            handle = resolve(b"$UPCALL_ENTRY_NAME")
            print("PYTHON: resolved handle =", handle)
            assert handle != -1, "name lookup failed"

            result = invoke(handle)
            print("PYTHON: invoke result =", result)
            assert result == $PRESSES, f"expected $PRESSES, got {result}"

            excluded = resolve(b"$UPCALL_EXCLUDED_NAME")
            print("PYTHON: @PythonInternal entry resolves to", excluded)
            assert excluded == -1, "an opted-out declaration reached the table"

            print("PYTHON: UPCALL_OK")
        finally:
            sys.stdout.flush()
            sys.stderr.flush()
    """.trimIndent()

    val status = withGIL { PyRun_SimpleString(script) }
    if (status != 0) {
        println("KOTLIN: PyRun_SimpleString reported failure (status=$status)")
        exitProcess(1)
    }
    println("KOTLIN: upcall verification finished")
}
