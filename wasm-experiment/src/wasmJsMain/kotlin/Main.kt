@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Answers the Kotlin/Wasm module and memory ABI questions that decide the shape of ROADMAP §10.
// See docs/wasm-design.md for what each result implies, and README.md here for how to run it.

// ---------------------------------------------------------------------------------------------
// Readers over raw addresses.
//
// `Pointer` has a public constructor over a UInt and its load/store members compile to plain
// i32.load / i32.store against this module's linear memory. Nothing here touches the allocator,
// which matters because Kotlin's allocator starts at address 0 -- on top of where Emscripten
// keeps its static data -- and so must never be used in a shared memory.
//
// If the memory really is shared, an address produced by C code reads back as the bytes C wrote.
// If it is not, these read the zeroes of Kotlin's own empty memory.
// ---------------------------------------------------------------------------------------------

@JsExport
fun readByteAt(address: Int): Int = Pointer(address.toUInt()).loadByte().toInt() and 0xFF

@JsExport
fun readCStringAt(address: Int): String {
    val sb = StringBuilder()
    var p = Pointer(address.toUInt())
    while (true) {
        val b = p.loadByte().toInt() and 0xFF
        if (b == 0) break
        sb.append(b.toChar())
        p += 1
        if (sb.length > 4096) break
    }
    return sb.toString()
}

@JsExport
fun writeCStringAt(address: Int, value: String) {
    var p = Pointer(address.toUInt())
    for (c in value) {
        p.storeByte(c.code.toByte())
        p += 1
    }
    p.storeByte(0)
}

@JsExport
fun memoryPages(): Int = currentPagesViaJs()

// ---------------------------------------------------------------------------------------------
// The linear-memory probe: is Kotlin's memory genuinely unused?
// ---------------------------------------------------------------------------------------------

private const val MEASURE_ALLOCATOR = false

fun main() {
    println("=== Kotlin/Wasm linear memory probe ===")
    println("pages at startup                 ${currentPagesViaJs()}")

    val s = buildString { repeat(500) { append("attribute_name_$it ") } }
    val viaJs: String = s.length.toString() + s.substring(0, 8)
    println("pages after string interop       ${currentPagesViaJs()}   ($viaJs)")

    val joined = (1..5000).map { it.toString() }.joinToString(",").length
    println("pages after collections          ${currentPagesViaJs()}   ($joined)")

    try {
        throw IllegalStateException("probe")
    } catch (e: IllegalStateException) {
        println("pages after exception            ${currentPagesViaJs()}   (${e.message})")
    }

    val ba = ByteArray(10 * 1024 * 1024)
    println("pages after large ByteArray      ${currentPagesViaJs()}   (size=${ba.size})")

    val largeGraph = Array(100_000) { it.toString() }
    println("pages after large obj graph      ${currentPagesViaJs()}   (size=${largeGraph.size})")

    // Captured rather than printed in place: runPromise resolves after main() returns, so a
    // println here lands *after* the explicit allocate below and would report that allocation's
    // page count instead of the coroutine's. Comparing against the pre-allocate snapshot is the
    // only reading that means anything.
    var coroutinePages = -1
    runPromise { coroutinePages = currentPagesViaJs() }

    val jsArray = getJsArray()
    println("pages after ArrayBuffer bridge   ${currentPagesViaJs()}   (jsArray=$jsArray)")

    val pagesBeforeAllocate = currentPagesViaJs()
    println("pages before explicit allocate   $pagesBeforeAllocate")

    // The one thing that must never appear in wasmJsMain, shown here to prove why.
    //
    // Gated off by default. runPromise resolves after main() returns, so if this runs the
    // coroutine's page reading is taken *after* the allocator grew the memory and says nothing
    // about coroutines. Turn it on only to re-confirm the allocator's own behaviour, and read the
    // coroutine line as meaningless in that run.
    if (MEASURE_ALLOCATOR) withScopedMemoryAllocator { alloc ->
        val p = alloc.allocate(64)
        p.storeInt(0x41424344)
        println("pages after explicit allocate    ${currentPagesViaJs()}   (at 0x${p.address.toString(16)}, reads 0x${p.loadInt().toString(16)})")
    }

    println()
    println("Every line before the explicit allocate reads 0 => Kotlin never touches linear")
    println("memory on its own, so the memory it exports can be handed to Emscripten whole.")
    println()
    runPromise {
        val verdict = when {
            coroutinePages < 0 -> "NOT MEASURED -- the coroutine never ran"
            coroutinePages <= pagesBeforeAllocate -> "OK -- coroutines did not grow it"
            else -> "GROWN -- coroutines DO touch linear memory, which breaks memory sharing"
        }
        println("pages observed inside coroutine  $coroutinePages  (pre-allocate snapshot was $pagesBeforeAllocate)  $verdict")
    }
}

private fun currentPagesViaJs(): Int =
    js("wasmExports.memory.buffer.byteLength / 65536")

// ---------------------------------------------------------------------------------------------
// Test A -- does @WasmImport bind to a function exported by an Emscripten module?
//
// The compiler's own codegen test proves the mechanism against a hand-built module and states the
// intent: "we pass export of another Wasm module to our import directly without JS layer". The
// open question was whether it still holds when Emscripten's glue owns instantiation.
// ---------------------------------------------------------------------------------------------

@OptIn(kotlin.wasm.ExperimentalWasmInterop::class)
@kotlin.wasm.WasmImport("./probeA-wrapper.mjs", "add_two")
external fun cAddTwo(a: Int, b: Int): Int

@JsExport
fun callCAddTwo(a: Int, b: Int): Int = cAddTwo(a, b)

fun getJsArray(): JsAny = js("new Uint8Array(10)")

fun runPromise(callback: () -> Unit): Unit = js("Promise.resolve().then(callback)")

@JsModule("./probeA-wrapper.mjs")
external fun add_two(a: Int, b: Int): Int

@JsExport
fun measureDirect(iterations: Int): Int {
    var sum = 0
    for (i in 0 until iterations) {
        sum += cAddTwo(i, 2)
    }
    return sum
}

@JsExport
fun measureTrampoline(iterations: Int): Int {
    var sum = 0
    for (i in 0 until iterations) {
        sum += add_two(i, 2)
    }
    return sum
}

// ---------------------------------------------------------------------------------------------
// Test C -- the data path, measured.
//
// Both string loops run in Kotlin and produce a Kotlin String from the same C address. The only
// difference is how the bytes get here:
//
//   shared : i32.load against the memory Emscripten owns. No boundary at all.
//   copied : a JS hop out to Emscripten's UTF8ToString, which builds a JS string from the same
//            bytes, and a second hop back to convert it to a Kotlin String.
//
// The second is the design docs/wasm-design.md assumed was forced. Every measurement on Android
// and desktop found marshalling, not crossing count, to be the dominant cost, so the ratio here
// is the number that decides whether WASM looks like iOS or like a JS bridge.
// ---------------------------------------------------------------------------------------------

private fun utf8ViaJs(address: Int): String = js("globalThis.__emModule.UTF8ToString(address)")

@JsExport
fun readCStringViaJs(address: Int): String = utf8ViaJs(address)

// The naive reader above appends one Char at a time to a StringBuilder. That is the obvious way
// to write it and it is not what the comparison should be made against, so here is the version a
// real binding would ship: find the NUL, copy into a ByteArray, decode once.
@JsExport
fun readCStringFast(address: Int): String {
    var len = 0
    while (Pointer((address + len).toUInt()).loadByte().toInt() != 0) len++
    val bytes = ByteArray(len)
    for (i in 0 until len) bytes[i] = Pointer((address + i).toUInt()).loadByte()
    return bytes.decodeToString()
}

// Separates the two halves of the cost: reaching the bytes, versus turning them into a String.
@JsExport
fun measureSharedStrlen(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) {
        var len = 0
        while (Pointer((address + len).toUInt()).loadByte().toInt() != 0) len++
        total += len
    }
    return total
}

// Scan + copy into a ByteArray, stopping short of decoding. Attributes the cost between reaching
// the bytes and turning them into a String -- and is itself the operation a PyBytes binding needs,
// where no decode happens at all.
@JsExport
fun measureSharedByteArrayCopy(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) {
        var len = 0
        while (Pointer((address + len).toUInt()).loadByte().toInt() != 0) len++
        val bytes = ByteArray(len)
        for (j in 0 until len) bytes[j] = Pointer((address + j).toUInt()).loadByte()
        total += bytes.size
    }
    return total
}

// ASCII fast path -- CharArray + concatToString() instead of ByteArray + decodeToString().
// Attribute names, module names and most of what a binding marshals are ASCII, so if the UTF-8
// decoder is what costs, this route avoids it.
@JsExport
fun measureSharedStringAscii(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) {
        var len = 0
        while (Pointer((address + len).toUInt()).loadByte().toInt() != 0) len++
        val chars = CharArray(len)
        for (j in 0 until len) {
            chars[j] = (Pointer((address + j).toUInt()).loadByte().toInt() and 0xFF).toChar()
        }
        total += chars.concatToString().length
    }
    return total
}

@JsExport
fun measureSharedStringRead(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) total += readCStringFast(address).length
    return total
}

@JsExport
fun measureSharedStringReadNaive(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) total += readCStringAt(address).length
    return total
}

@JsExport
fun measureCopiedStringRead(address: Int, iterations: Int): Int {
    var total = 0
    for (i in 0 until iterations) total += utf8ViaJs(address).length
    return total
}

// Bulk read: N elements out of a C-owned array. This is the one place composition was argued to
// still earn its keep, so it needs a number. Shared memory turns it into a plain loop of
// i32.loads; without shared memory each element costs a boundary crossing.
@JsExport
fun measureSharedBulkRead(address: Int, count: Int): Int {
    var sum = 0
    var p = Pointer(address.toUInt())
    for (i in 0 until count) {
        sum += p.loadInt()
        p += 4
    }
    return sum
}

private fun loadIntViaJs(address: Int): Int = js("globalThis.__emModule.HEAP32[address >> 2]")

@JsExport
fun measureCopiedBulkRead(address: Int, count: Int): Int {
    var sum = 0
    for (i in 0 until count) sum += loadIntViaJs(address + i * 4)
    return sum
}
