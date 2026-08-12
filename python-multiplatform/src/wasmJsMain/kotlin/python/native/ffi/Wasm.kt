@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi

import kotlin.wasm.unsafe.Pointer
import python.native.ffi.bindings.free
import python.native.ffi.bindings.malloc

/**
 * The linear-memory side of the boundary. `Panama` is desktop's equivalent; this is smaller because
 * there is no boundary to cross for data.
 *
 * Kotlin's `intrinsics.memory` import is pointed at Emscripten's `Module.wasmMemory`, so CPython's
 * heap and Kotlin's linear memory are one `WebAssembly.Memory`. A `char*` CPython hands back is an
 * address `Pointer(addr)` reads directly -- no copy, no JS frame, and it keeps working after the
 * memory grows (a JS `TypedArray` view would detach; a wasm memory import does not).
 *
 * ### The rule that shapes every function here
 *
 * **`withScopedMemoryAllocator` must never be called on this target.** It is the only allocator
 * Kotlin/Wasm offers and it allocates from address 0 upward -- measured handing back `0x0` after
 * growing the memory by two pages -- which on a shared memory is directly on top of Emscripten's
 * static data. So CPython allocates and Kotlin only dereferences. [allocUtf8] goes through
 * CPython's own `malloc` for that reason.
 *
 * See docs/wasm-design.md and `wasm-experiment/README.md` for the measurements.
 */
object Wasm {

    /**
     * Stages [value] as a NUL-terminated UTF-8 string in CPython's heap and returns its address.
     * The caller owns it and must pass it to [freeUtf8].
     *
     * `malloc`, not `PyMem_Malloc`: the latter requires an attached thread state, and a C string
     * often has to be staged before the call that attaches one.
     */
    fun allocUtf8(value: String): Int {
        val bytes = value.encodeToByteArray()
        val address = malloc(bytes.size + 1)
        if (address == 0) throw OutOfMemoryError("malloc(${bytes.size + 1}) failed in CPython's heap")
        var p = Pointer(address.toUInt())
        for (b in bytes) {
            p.storeByte(b)
            p += 1
        }
        p.storeByte(0)
        return address
    }

    fun freeUtf8(address: Int) {
        if (address != 0) free(address)
    }

    /** Length of the NUL-terminated string at [address], in bytes. */
    fun strlen(address: Int): Int {
        var n = 0
        while (Pointer((address + n).toUInt()).loadByte().toInt() != 0) n++
        return n
    }

    /**
     * Reads the NUL-terminated UTF-8 string at [address], or null if [address] is 0 -- which is how
     * every `String?`-returning C API function reports failure.
     *
     * Two routes, and which one is taken matters more than it looks. Measured over 27- and
     * 4000-byte strings:
     *
     * | ns per read | 27 B | 4000 B |
     * |---|---|---|
     * | `CharArray` + `concatToString()` | **103** | 5 875 |
     * | `ByteArray` + `decodeToString()` | 405 | **58 271** |
     *
     * `decodeToString()` costs about 13 ns/byte on long input against 0.2 ns/byte on short, so it
     * is never used here. The ASCII scan below is one pass that also decides the fast path, and the
     * multi-byte path decodes into the same `CharArray` rather than falling back to it.
     */
    fun readUtf8String(address: Int): String? {
        if (address == 0) return null
        var length = 0
        var ascii = true
        while (true) {
            val b = Pointer((address + length).toUInt()).loadByte().toInt() and 0xFF
            if (b == 0) break
            if (b >= 0x80) ascii = false
            length++
        }
        if (length == 0) return ""

        val chars = CharArray(length)
        if (ascii) {
            for (i in 0 until length) {
                chars[i] = (Pointer((address + i).toUInt()).loadByte().toInt() and 0xFF).toChar()
            }
            return chars.concatToString()
        }
        return decodeUtf8(address, length, chars)
    }

    /** Reads [length] bytes at [address]. The `PyBytes`/`PyByteArray` case: no decode involved. */
    fun readBytes(address: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = Pointer((address + i).toUInt()).loadByte()
        return out
    }

    /**
     * UTF-8 decode straight out of linear memory into [out].
     *
     * Hand-rolled rather than `ByteArray.decodeToString()` for the cost reason above, and rather
     * than JS `UTF8ToString` because that would put a JS frame back in the data path. Malformed
     * input is replaced with U+FFFD rather than throwing: a `char*` that fails to decode is
     * CPython handing back something unexpected, and losing the rest of an error message to an
     * exception is worse than showing a replacement character.
     */
    private fun decodeUtf8(address: Int, length: Int, out: CharArray): String {
        fun byteAt(i: Int) = Pointer((address + i).toUInt()).loadByte().toInt() and 0xFF

        var i = 0
        var o = 0
        while (i < length) {
            val b0 = byteAt(i)
            var cp: Int
            val size: Int
            when {
                b0 < 0x80 -> { cp = b0; size = 1 }
                b0 and 0xE0 == 0xC0 -> { cp = b0 and 0x1F; size = 2 }
                b0 and 0xF0 == 0xE0 -> { cp = b0 and 0x0F; size = 3 }
                b0 and 0xF8 == 0xF0 -> { cp = b0 and 0x07; size = 4 }
                else -> { cp = -1; size = 1 }
            }
            if (cp < 0 || i + size > length) {
                out[o++] = '�'
                i += 1
                continue
            }
            var valid = true
            for (k in 1 until size) {
                val bk = byteAt(i + k)
                if (bk and 0xC0 != 0x80) { valid = false; break }
                cp = (cp shl 6) or (bk and 0x3F)
            }
            if (!valid) {
                out[o++] = '�'
                i += 1
                continue
            }
            i += size
            if (cp <= 0xFFFF) {
                out[o++] = cp.toChar()
            } else {
                // Astral plane: one code point becomes two UTF-16 units. `out` was sized from the
                // byte count, and a 4-byte sequence yields 2 chars, so it can never overflow.
                val v = cp - 0x10000
                out[o++] = (0xD800 or (v shr 10)).toChar()
                out[o++] = (0xDC00 or (v and 0x3FF)).toChar()
            }
        }
        return out.concatToString(0, o)
    }
}
