@file:OptIn(
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.js.ExperimentalJsExport::class,
)

import kotlin.wasm.unsafe.Pointer

// Test D -- Kotlin/Wasm driving the real CPython 3.14.2 Emscripten build.
//
// Tests A and C used a 4 KB toy compiled by emcc. The question they leave open is whether the same
// two mechanisms survive contact with an interpreter: 10 MB of wasm, 7000+ exports, linked
// -sMAIN_MODULE with a growable memory, whose instantiation Emscripten's own glue controls.
//
// Every function below is a raw wasm export of python.wasm, bound with no type adapters and no JS
// frame. Every pointer is an i32 into the linear memory Kotlin shares with it -- so the Python
// source Kotlin runs is written straight into CPython's heap, and the strings CPython produces are
// read straight out of it. Nothing is copied and nothing crosses into JS.

private const val CPY = "./cpython-wrapper.mjs"

@kotlin.wasm.WasmImport(CPY, "PyRun_SimpleString")
external fun pyRunSimpleString(code: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyImport_AddModule")
external fun pyImportAddModule(name: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyModule_GetDict")
external fun pyModuleGetDict(module: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyDict_GetItemString")
external fun pyDictGetItemString(dict: Int, key: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyLong_AsLong")
external fun pyLongAsLong(obj: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyUnicode_AsUTF8")
external fun pyUnicodeAsUtf8(obj: Int): Int

@kotlin.wasm.WasmImport(CPY, "PyErr_Occurred")
external fun pyErrOccurred(): Int

@kotlin.wasm.WasmImport(CPY, "pmalloc")
external fun cMalloc(size: Int): Int

@kotlin.wasm.WasmImport(CPY, "pfree")
external fun cFree(p: Int)

// ---------------------------------------------------------------------------------------------
// Kotlin owns none of this memory. CPython's malloc hands back an address; Kotlin writes the bytes
// there with plain i32.store. This is the rule from the source-set README made concrete:
// withScopedMemoryAllocator must never appear, because it would allocate from address 0 on top of
// Emscripten's static data.
// ---------------------------------------------------------------------------------------------

private fun allocCString(value: String): Int {
    val addr = cMalloc(value.length + 1)
    var p = Pointer(addr.toUInt())
    for (c in value) {
        p.storeByte(c.code.toByte())
        p += 1
    }
    p.storeByte(0)
    return addr
}

private fun readCString(address: Int): String {
    var len = 0
    while (Pointer((address + len).toUInt()).loadByte().toInt() != 0) len++
    val chars = CharArray(len)
    for (i in 0 until len) {
        chars[i] = (Pointer((address + i).toUInt()).loadByte().toInt() and 0xFF).toChar()
    }
    return chars.concatToString()
}

/** Runs Python source that Kotlin wrote directly into CPython's heap. Returns PyRun's status. */
@JsExport
fun pyExec(source: String): Int {
    val buf = allocCString(source)
    try {
        return pyRunSimpleString(buf)
    } finally {
        cFree(buf)
    }
}

/** Reads an int out of `__main__`'s globals, entirely through direct calls and shared memory. */
@JsExport
fun pyGlobalInt(name: String): Int {
    val mainName = allocCString("__main__")
    val key = allocCString(name)
    try {
        val module = pyImportAddModule(mainName)
        if (module == 0) return -1
        val dict = pyModuleGetDict(module)
        if (dict == 0) return -2
        val obj = pyDictGetItemString(dict, key)
        if (obj == 0) return -3
        return pyLongAsLong(obj)
    } finally {
        cFree(mainName)
        cFree(key)
    }
}

/**
 * Reads a `str` out of `__main__`'s globals. `PyUnicode_AsUTF8` returns a `char*` into CPython's
 * own buffer -- the interesting part is that Kotlin then dereferences that pointer with no copy
 * and no JS, which is the thing the whole shared-memory design is for.
 */
@JsExport
fun pyGlobalString(name: String): String {
    val mainName = allocCString("__main__")
    val key = allocCString(name)
    try {
        val module = pyImportAddModule(mainName)
        if (module == 0) return "<no __main__>"
        val obj = pyDictGetItemString(pyModuleGetDict(module), key)
        if (obj == 0) return "<missing>"
        val utf8 = pyUnicodeAsUtf8(obj)
        if (utf8 == 0) return "<not a str>"
        return readCString(utf8)
    } finally {
        cFree(mainName)
        cFree(key)
    }
}

@JsExport
fun pyErrorPending(): Boolean = pyErrOccurred() != 0

/** Crossing cost against a real interpreter, not a toy: N calls of PyErr_Occurred. */
@JsExport
fun measurePyErrOccurred(iterations: Int): Int {
    var sum = 0
    for (i in 0 until iterations) sum += pyErrOccurred()
    return sum
}

/** The composed alternative's baseline: reading one global, every crossing, N times. */
@JsExport
fun measurePyGlobalInt(name: String, iterations: Int): Int {
    var sum = 0
    for (i in 0 until iterations) sum += pyGlobalInt(name)
    return sum
}

// The cheap fix, and the one that has to be ruled out before composition can be argued for.
// Attribute and module names are repeated literals, so their C strings can be allocated once and
// the address cached -- the same interning that took Android from 2238 ns to 148 ns. Here it costs
// nothing extra to implement because Kotlin writes them through the shared memory anyway.
private val internedStrings = HashMap<String, Int>()

private fun intern(value: String): Int =
    internedStrings.getOrPut(value) { allocCString(value) }

@JsExport
fun measurePyGlobalIntInterned(name: String, iterations: Int): Int {
    val mainName = intern("__main__")
    val key = intern(name)
    var sum = 0
    for (i in 0 until iterations) {
        val module = pyImportAddModule(mainName)
        val dict = pyModuleGetDict(module)
        val obj = pyDictGetItemString(dict, key)
        sum += pyLongAsLong(obj)
    }
    return sum
}

/**
 * The floor: the four CPython calls with no marshalling at all, module and dict hoisted out. What
 * a perfectly composed `pmp_*` shim could remove is the gap between this and the interned figure
 * above -- and no more, because CPython does the rest either way.
 */
@JsExport
fun measurePyGlobalIntHoisted(name: String, iterations: Int): Int {
    val module = pyImportAddModule(intern("__main__"))
    val dict = pyModuleGetDict(module)
    val key = intern(name)
    var sum = 0
    for (i in 0 until iterations) sum += pyLongAsLong(pyDictGetItemString(dict, key))
    return sum
}
