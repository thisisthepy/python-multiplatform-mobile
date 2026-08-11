package org.thisisthepy.python.multiplatform.demo

import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.multiplatform.ffi.withGIL
import python.native.ffi.PyRun_SimpleString
import python.native.ffi.UpcallStub
import kotlin.system.exitProcess

/**
 * Stands in for a KSP-generated fragment (ROADMAP §7, `docs/upcall-table-design.md`). Hand
 * written because the generator does not exist yet -- this exercises the same
 * [UpcallTable.install] path a generated aggregator would call.
 */
object NativeImageDemoFragment : FunctionTableFragment {
    override val moduleName: String = "native_image_demo"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "demo.answer",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { 42L },
    )
}

/**
 * A headless entry point for the GraalVM native-image build path. `main.kt`'s `fun main() =
 * application { ... }` requires a running AWT/Compose window, which is not what this build path
 * is verifying -- it is verifying that Python can resolve a Kotlin declaration by name and call
 * it, inside a closed-world binary. Kept separate so a Compose window is never a precondition
 * for that question.
 *
 * The manual [UpcallTable.install] call plays the part KSP's generated aggregator will play in
 * production; see `NativeImageDemoFragment`. What is being verified is the interpreter boundary
 * and the reflection-free table surviving native-image's static analysis, not the generator.
 */
fun main() {
    println("=== GraalVM native-image upcall verification ===")

    Python3.initialize()

    UpcallTable.install(listOf(NativeImageDemoFragment))

    val resolveAddr = UpcallStub.resolveHandleStubAddr
    val invokeAddr = UpcallStub.invokeHandleStubAddr
    println("KOTLIN: resolve stub @ 0x${resolveAddr.toString(16)}")
    println("KOTLIN: invoke  stub @ 0x${invokeAddr.toString(16)}")

    // Python resolves "demo.answer" to a handle by name exactly once, then calls back through
    // that handle -- the ObjC-selector-cache shape docs/upcall-design.md argues for, reached
    // here via ctypes.CDLL(None)-equivalent function pointers instead of a generated proxy type.
    // Not a tty under Gradle/native-image, so sys.stdout is block-buffered: without an explicit
    // flush before the process exits (there is no Py_Finalize call on this path), every print()
    // below is silently lost even on success.
    val script = """
        import sys
        import ctypes

        try:
            resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)($resolveAddr)
            invoke = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)($invokeAddr)

            handle = resolve(b"demo.answer")
            print("PYTHON: resolved handle =", handle)
            assert handle != -1, "name lookup failed"

            result = invoke(handle)
            print("PYTHON: invoke result =", result)
            assert result == 42, f"expected 42, got {result}"

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
