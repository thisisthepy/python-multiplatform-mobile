@file:OptIn(
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.js.ExperimentalJsExport::class,
)

import kotlin.wasm.unsafe.Pointer

// Test E -- upcalls. The last structurally unknown thing in ROADMAP §10.
//
// Every other platform re-enters Kotlin from CPython through something ordinary: a plain function
// call on iOS, a JNI callback on Android, a Panama upcall stub on desktop. The design doc (§5, §7
// step 3) assumed WASM would be the odd one out -- Kotlin function -> @JsExport -> a JS closure ->
// Emscripten's addFunction -> a table index -- and wrote off the whole path as "goes through JS,
// unmeasured, and likely worse than every other platform".
//
// What this file tests is whether that detour is necessary at all. @WasmExport puts a Kotlin
// function in the *wasm* export section with no type adapters, so the value JS gets back is an
// exported wasm function, not a closure. WebAssembly.Table.set accepts any exported wasm function
// regardless of which instance it came from -- so the question is whether Emscripten's
// call_indirect will accept it and reach Kotlin with no JS frame in between.
//
// Two shapes are exported for each mechanism:
//   * a trivial (i32, i32) -> i32, to isolate the crossing cost
//   * one that does what a real trampoline does -- look up Kotlin state by the context integer,
//     and write a result into CPython's linear memory -- to check that the WasmGC heap and the
//     shared memory are both reachable from a frame CPython pushed.

// ---------------------------------------------------------------------------------------------
// Mechanism 1: @WasmExport -- a raw wasm export, dropped straight into Emscripten's table
// ---------------------------------------------------------------------------------------------

@kotlin.wasm.WasmExport("kotlin_upcall_add")
fun kotlinUpcallAdd(a: Int, b: Int): Int = a + b

/** One argument, to check that the trampoline's arity dispatch can tell the shapes apart. */
@kotlin.wasm.WasmExport("kotlin_upcall_one")
fun kotlinUpcallOne(a: Int): Int = a + 1

/**
 * The realistic trampoline shape. `ctx` is the routing integer CPython would carry in `self` or a
 * closure; the Kotlin object it names lives on the WasmGC heap, and the answer is written back into
 * CPython's linear memory. If this works from a frame CPython pushed, "routing by data" survives
 * on WASM unchanged.
 */
private val registry = HashMap<Int, String>()

@kotlin.wasm.WasmExport("kotlin_upcall_route")
fun kotlinUpcallRoute(ctx: Int, outAddr: Int): Int {
    val value = registry[ctx] ?: return -1
    var p = Pointer(outAddr.toUInt())
    for (c in value) {
        p.storeByte(c.code.toByte())
        p += 1
    }
    p.storeByte(0)
    return value.length
}

/** Allocates on the WasmGC heap from inside the upcall, which is the thing most likely to break. */
@kotlin.wasm.WasmExport("kotlin_upcall_alloc")
fun kotlinUpcallAlloc(n: Int): Int {
    val list = ArrayList<Int>(n)
    for (i in 0 until n) list.add(i)
    return list.sum()
}

@JsExport
fun registerUpcallValue(ctx: Int, value: String) {
    registry[ctx] = value
}

// ---------------------------------------------------------------------------------------------
// Mechanism 2: @JsExport + addFunction -- the shape §5/§7 assumed was the only one
// ---------------------------------------------------------------------------------------------

@JsExport
fun kotlinUpcallAddViaJs(a: Int, b: Int): Int = a + b
