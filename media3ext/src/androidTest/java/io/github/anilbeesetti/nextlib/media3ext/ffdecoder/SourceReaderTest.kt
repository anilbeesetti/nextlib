package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SourceReaderTest {
    @Test fun seeksPreserveHeadersAndOriginalRangeEnd() {
        val data = ByteArrayDataSource(ByteArray(100))
        val requests = mutableListOf<DataSpec>()
        val source = ReopeningDataSource(object : DataSource by data {
            override fun open(dataSpec: DataSpec): Long { requests += dataSpec; return data.open(dataSpec) }
        })
        val request = DataSpec.Builder().setUri(Uri.parse("https://example.test/video"))
            .setPosition(10).setLength(40).setHttpRequestHeaders(mapOf("Authorization" to "test-token")).build()
        try {
            source.open(request)
            source.read(ByteArray(8), 0, 8)
            source.seek(30)
            assertEquals(30L, requests.last().position)
            assertEquals(20L, requests.last().length)
            assertEquals(request.httpRequestHeaders, requests.last().httpRequestHeaders)
            source.seek(50)
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
            source.seek(10)
            assertEquals(40L, requests.last().length)
        } finally { source.close() }
    }

    @Test(expected = IOException::class) fun rejectsSeekOutsideBoundedRequest() {
        val source = ReopeningDataSource(ByteArrayDataSource(ByteArray(100)))
        try {
            source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(10).setLength(40).build())
            source.seek(51)
        } finally { source.close() }
    }
}
