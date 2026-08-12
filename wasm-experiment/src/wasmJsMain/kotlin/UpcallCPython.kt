@file:OptIn(
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.js.ExperimentalJsExport::class,
)

import kotlin.wasm.unsafe.Pointer

// Test F -- the upcall against the real interpreter.
//
// Test E showed that Emscripten-compiled C can reach a Kotlin @WasmExport through a table entry.
// That is not yet the thing the design needs to know, because CPython does not call PyCFunctions
// with a bare call_indirect. It is built with -DPY_CALL_TRAMPOLINE, and
// Include/internal/pycore_emscripten_trampoline.h routes every METH_VARARGS call through
// _PyEM_TrampolineCall, which dispatches on arity with a wasm-gc ref.test
// (Python/emscripten_trampoline_inner.c) inside a separate wasm module of CPython's own.
//
// So the question this file answers is whether a Kotlin function survives that: registered in a
// PyMethodDef as an ordinary PyCFunction, found by the trampoline's type test, and called from
// Python source. And whether, from inside that call, Kotlin can call back down into CPython --
// which is what any real trampoline has to do to build its return value.

private const val CPY = "./cpython-wrapper.mjs"

@kotlin.wasm.WasmImport(CPY, "PyCFunction_NewEx")
external fun pyCFunctionNewEx(mlDef: Int, self: Int, module: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyLong_FromLong")
external fun pyLongFromLong(value: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyTuple_Size")
external fun pyTupleSize(tuple: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyTuple_GetItem")
external fun pyTupleGetItem(tuple: Int, index: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyDict_SetItemString")
external fun pyDictSetItemString(dict: Int, key: Int, value: Int): Int

private const val METH_VARARGS = 0x0001
private const val METH_O = 0x0008

private fun cString(value: String): Int {
    val addr = cMalloc(value.length + 1)
    var p = Pointer(addr.toUInt())
    for (c in value) {
        p.storeByte(c.code.toByte())
        p += 1
    }
    p.storeByte(0)
    return addr
}

/** Kotlin-side state the upcall reaches, to prove the WasmGC heap is live inside a CPython frame. */
private var upcallCount = 0

@JsExport
fun pyCallbackCount(): Int = upcallCount

/**
 * The trampoline itself, in the exact shape CPython's METH_VARARGS slot expects:
 * `PyObject* (*)(PyObject *self, PyObject *args)` -- which on wasm32 is `(i32, i32) -> i32`.
 *
 * It does everything a real binding trampoline would: touches Kotlin heap state, reads the argument
 * tuple through the Stable ABI, and builds a new reference to return. The last part is a *downcall
 * issued from inside an upcall*, so both directions are live on the same stack.
 */
@kotlin.wasm.WasmExport("kotlin_pycfunction")
fun kotlinPyCFunction(self: Int, args: Int): Int {
    upcallCount++
    var sum = 0
    val n = pyTupleSize(args)
    for (i in 0 until n) sum += pyLongAsLong(pyTupleGetItem(args, i))
    return pyLongFromLong(sum + 1)
}

/** The minimal shape, for measuring the crossing without tuple work in the way. */
@kotlin.wasm.WasmExport("kotlin_pycfunction_bare")
fun kotlinPyCFunctionBare(self: Int, args: Int): Int {
    upcallCount++
    return pyLongFromLong(0)
}

/**
 * METH_O rather than METH_VARARGS. The wasm signature is identical -- `PyObject* (*)(PyObject*,
 * PyObject*)` either way -- but CPython does not build an argument tuple for METH_O. This is the
 * variant that can be compared against a C builtin like `abs`, which is also METH_O, so the
 * difference between them is the crossing and nothing else.
 */
@kotlin.wasm.WasmExport("kotlin_pycfunction_o")
fun kotlinPyCFunctionO(self: Int, arg: Int): Int {
    upcallCount++
    return pyLongFromLong(pyLongAsLong(arg) + 1)
}

/**
 * Four arguments, which matches none of the four shapes
 * Python/emscripten_trampoline_inner.c tests for. This is a discriminator, not a feature:
 *
 *   * the wasm trampoline runs four ref.tests, all fail, and CPython raises
 *     SystemError("Handler takes too many arguments")
 *   * the JS fallback would instead do `wasmTable.get(func)(self, args, NULL)` and get an answer
 *
 * So which error comes back says which trampoline is live, and therefore whether a JS frame sits
 * in the upcall path. Nothing else observable distinguishes them.
 */
@kotlin.wasm.WasmExport("kotlin_pycfunction_4")
fun kotlinPyCFunction4(a: Int, b: Int, c: Int, d: Int): Int {
    upcallCount++
    return pyLongFromLong(a + b + c + d)
}

/**
 * Builds a PyMethodDef around a function-pointer table index and binds the resulting callable into
 * `__main__`. JS has to supply the index because only the host can put a funcref in the table --
 * that one integer is the entire JS involvement, and it happens once per trampoline, not per call.
 *
 *     PyMethodDef { const char *ml_name; PyCFunction ml_meth; int ml_flags; const char *ml_doc; }
 *
 * All four fields are 4 bytes on wasm32, so the struct is 16. It is deliberately never freed:
 * CPython keeps the pointer for the lifetime of the callable.
 */
@JsExport
fun installPyCallback(name: String, funcPointer: Int, methO: Boolean = false): Int {
    val namePtr = cString(name)
    val def = cMalloc(16)
    val p = Pointer(def.toUInt())
    p.storeInt(namePtr)
    (p + 4).storeInt(funcPointer)
    (p + 8).storeInt(if (methO) METH_O else METH_VARARGS)
    (p + 12).storeInt(0)

    val callable = pyCFunctionNewEx(def, 0, 0)
    if (callable == 0) return -1
    val module = pyImportAddModule(cString("__main__"))
    if (module == 0) return -2
    val dict = pyModuleGetDict(module)
    if (dict == 0) return -3
    return pyDictSetItemString(dict, namePtr, callable)
}
