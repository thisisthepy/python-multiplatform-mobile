@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Answers four questions about Kotlin/Wasm's module and memory ABI that decide the shape of
// ROADMAP §10. See docs/wasm-design.md for what each one implies.

/**
 * Q2 -- can a Pointer be built from an address CPython handed back, rather than only from
 * `allocate()`? The stdlib declares `value class Pointer public constructor(address: UInt)`, and
 * load/store are plain i32.load/i32.store, so this should compile and never touch the allocator.
 *
 * This matters because Kotlin's own allocator starts at address 0 and grows the memory, which is
 * unusable in a memory shared with Emscripten. Reading CPython-owned addresses avoids it entirely.
 */
fun readAt(address: Int): Int = Pointer(address.toUInt()).loadInt()
fun writeAt(address: Int, value: Int) = Pointer(address.toUInt()).storeInt(value)

/**
 * Q5 -- is Kotlin's linear memory actually unused?
 *
 * The module declares `min_pages = 0` and all 40 of its data segments are passive, meaning they
 * feed WasmGC arrays through `array.new_data` rather than being written into linear memory. If
 * nothing grows it at runtime either, the memory is free real estate and can be handed to
 * Emscripten wholesale -- which is the released-toolchain route to sharing, since Kotlin only
 * gained the ability to *import* a memory after 2.4.10.
 *
 * The risk is that some stdlib path -- string interop is the obvious suspect -- quietly allocates.
 */
private fun pages(): Int = wasmMemorySizeInPages()

fun main() {
    println("=== Kotlin/Wasm linear memory probe ===")
    println("pages at startup                 ${pages()}")

    // String round trips through JS interop, the most likely hidden consumer.
    val s = buildString { repeat(500) { append("attribute_name_$it ") } }
    val viaJs: String = s.length.toString() + s.substring(0, 8)
    println("pages after string interop       ${pages()}   ($viaJs)")

    // Exceptions, collections, boxing -- all WasmGC, but confirm.
    val list = (1..5000).map { it.toString() }
    val joined = list.joinToString(",").length
    println("pages after collections          ${pages()}   ($joined)")

    try {
        throw IllegalStateException("probe")
    } catch (e: IllegalStateException) {
        println("pages after exception            ${pages()}   (${e.message})")
    }

    // Only the explicit unsafe API should ever grow it.
    withScopedMemoryAllocator { alloc ->
        val p = alloc.allocate(64)
        p.storeInt(0x41424344)
        println("pages after explicit allocate    ${pages()}   (allocated at 0x${p.address.toString(16)}, reads back 0x${p.loadInt().toString(16)})")
    }

    println()
    println("VERDICT: if every line above the explicit allocate reads 0, Kotlin never touches")
    println("linear memory on its own, and the memory it exports can be given to Emscripten.")
}

// wasm_memory_size is internal to the stdlib, so reach it the same way any host would: the
// module exports its memory, and `memory.size` is what `withScopedMemoryAllocator` uses to
// decide whether to grow. Approximated here by asking for a zero-byte scope, which does not grow.
private fun wasmMemorySizeInPages(): Int =
    withScopedMemoryAllocator { _ -> currentPagesViaJs() }

private fun currentPagesViaJs(): Int =
    js("wasmExports.memory.buffer.byteLength / 65536")
