package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StringMarshallingTest {

    @Test
    fun testInterning() {
        val s1 = "intern_me"
        val addr1 = internedUtf8(s1)
        val addr2 = internedUtf8(s1)
        assertEquals(addr1, addr2, "Repeated interning of equal strings must return the same address")

        val s2 = "another_string"
        val addr3 = internedUtf8(s2)
        assertNotEquals(addr1, addr3, "Different strings must return different addresses")

        val read1 = ffiReadUtf8(addr1)
        assertEquals(s1, read1, "Round trip must produce same string")
    }

    @Test
    fun testScratchEncoding() {
        val s = "scratch_me"
        val addr = encodeScratchUtf8(s)
        val read = ffiReadUtf8(addr)
        assertEquals(s, read, "Scratch buffer round trip must produce same string")
    }

    @Test
    fun testNonAsciiFallback() {
        val s = "non_ascii_🔥_test_ç"
        val addr1 = internedUtf8(s)
        assertEquals(s, ffiReadUtf8(addr1), "Non-ASCII interned string round trip failed")
        
        val addr2 = encodeScratchUtf8(s)
        assertEquals(s, ffiReadUtf8(addr2), "Non-ASCII scratch string round trip failed")
    }
}
