package io.github.anilbeesetti.nextlib.mediainfo

import android.graphics.Bitmap

class FrameLoader internal constructor(private var frameLoaderContextHandle: Long) {

    fun loadFrameInto(bitmap: Bitmap, durationMillis: Long): Boolean {
        require(frameLoaderContextHandle != -1L)
        return nativeLoadFrame(frameLoaderContextHandle, durationMillis, bitmap)
    }

    fun release() {
        nativeRelease(frameLoaderContextHandle)
        frameLoaderContextHandle = -1
    }

    companion object {
        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativeLoadFrame(handle: Long, durationMillis: Long, bitmap: Bitmap): Boolean
    }
}