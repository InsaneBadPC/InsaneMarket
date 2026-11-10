package cz.insanebadpc.insanemarket.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test
    fun formatsZero() {
        assertEquals("0 B", ByteFormat.bytes(0))
        assertEquals("0 B", ByteFormat.bytes(-5))
    }

    @Test
    fun formatsKilobytesAndMegabytes() {
        assertEquals("1.5 KB", ByteFormat.bytes(1536))
        assertEquals("1.0 MB", ByteFormat.bytes(1024L * 1024L))
        assertEquals("2.0 GB", ByteFormat.bytes(2L * 1024L * 1024L * 1024L))
    }
}
