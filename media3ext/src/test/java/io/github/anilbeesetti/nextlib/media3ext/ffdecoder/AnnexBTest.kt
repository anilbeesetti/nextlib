package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.IOException

class AnnexBTest {
    @Test fun convertsLengthPrefixedNals() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 103, 0, 0, 0, 1, 104, 7),
            annexB(byteArrayOf(0, 1, 103, 0, 2, 104, 7), 2))
    }
    @Test(expected = IOException::class) fun rejectsTruncatedPayload() { annexB(byteArrayOf(0, 0, 0, 9, 1), 4) }
    @Test(expected = IOException::class) fun rejectsTruncatedLength() { annexB(byteArrayOf(0), 4) }
    @Test(expected = IOException::class) fun rejectsUnsignedLengthOverflow() { annexB(byteArrayOf(-1, -1, -1, -1), 4) }
}

class VorbisInitializationTest {
    @Test fun splitsDecoderHeaders() {
        val headers = vorbisInitialization(byteArrayOf(2, 2, 2, 1, 9, 3, 8, 5, 7))
        assertArrayEquals(byteArrayOf(1, 9), headers[0])
        assertArrayEquals(byteArrayOf(5, 7), headers[1])
    }
    @Test(expected = IOException::class) fun rejectsTruncatedLacing() { vorbisInitialization(byteArrayOf(2, -1)) }
    @Test(expected = IOException::class) fun rejectsOutOfBoundsHeaders() { vorbisInitialization(byteArrayOf(2, 100, 2, 1)) }
}
