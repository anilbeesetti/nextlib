package io.github.anilbeesetti.nextlib.mediainfo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.FileNotFoundException
import java.io.Closeable

/**
 * A lightweight retriever for artwork and thumbnails.
 *
 * Similar to [android.media.MediaMetadataRetriever], but limited to thumbnail-centric APIs.
 */
class MediaThumbnailRetriever : Closeable {

    private var nativeHandle: Long = 0L

    fun setDataSource(filePath: String) {
        reset()
        nativeHandle = nativeCreateFromPath(filePath)
        require(nativeHandle != 0L) { "Unable to open media source from path." }
    }

    fun setDataSource(descriptor: ParcelFileDescriptor) {
        reset()
        nativeHandle = nativeCreateFromFD(descriptor.fd)
        require(nativeHandle != 0L) { "Unable to open media source from file descriptor." }
    }

    fun setDataSource(context: Context, uri: Uri) {
        when {
            uri.scheme?.lowercase()?.startsWith("http") == true -> setDataSource(uri.toString())
            else -> {
                val path = PathUtil.getPath(context, uri)
                if (path != null) {
                    setDataSource(path)
                } else {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            setDataSource(descriptor)
                        } ?: error("Unable to open media source from uri.")
                    } catch (e: FileNotFoundException) {
                        throw IllegalArgumentException("Unable to open media source from uri.", e)
                    }
                }
            }
        }
    }

    fun getEmbeddedPicture(): ByteArray? {
        val handle = requireHandle()
        return nativeGetEmbeddedPicture(handle)
    }

    /**
     * Returns a frame at [timeUs] microseconds. Pass -1 to use a default representative frame.
     */
    fun getFrameAtTime(timeUs: Long = -1L): Bitmap? {
        val handle = requireHandle()
        return nativeGetFrameAtTime(handle, timeUs)
    }

    /**
     * Returns a decoded frame by zero-based [frameIndex].
     */
    fun getFrameAtIndex(frameIndex: Int): Bitmap? {
        require(frameIndex >= 0) { "frameIndex must be >= 0" }
        val handle = requireHandle()
        return nativeGetFrameAtIndex(handle, frameIndex)
    }

    override fun close() {
        reset()
    }

    fun release() {
        reset()
    }

    private fun requireHandle(): Long {
        check(nativeHandle != 0L) { "Data source is not set. Call setDataSource(...) first." }
        return nativeHandle
    }

    private fun reset() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    @Keep
    private external fun nativeCreateFromPath(filePath: String): Long

    @Keep
    private external fun nativeCreateFromFD(fileDescriptor: Int): Long

    @Keep
    private external fun nativeGetEmbeddedPicture(handle: Long): ByteArray?

    @Keep
    private external fun nativeGetFrameAtTime(handle: Long, timeUs: Long): Bitmap?

    @Keep
    private external fun nativeGetFrameAtIndex(handle: Long, frameIndex: Int): Bitmap?

    @Keep
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("mediainfo")
        }
    }
}
