package io.github.anilbeesetti.nextlib.mediainfo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import java.io.FileNotFoundException

class MediaInfoBuilder(private val context: Context) {

    private var hasError: Boolean = false

    private var fileFormatName: String? = null
    private var duration: Long? = null
    private var videoStream: VideoStream? = null
    private var audioStreams = mutableListOf<AudioStream>()
    private var subtitleStreams = mutableListOf<SubtitleStream>()


    fun from(filePath: String) = apply {
        nativeCreateFromPath(filePath)
    }

    fun from(descriptor: ParcelFileDescriptor) = apply {
        nativeCreateFromFD(descriptor.fd)
    }

    fun from(uri: Uri) = apply {
        when {
            uri.scheme?.lowercase()?.startsWith("http") == true -> from(uri.toString())
            else -> {
                val path = PathUtil.getPath(context, uri)
                if (path != null) {
                    from(path)
                } else {
                    try {
                        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        if (descriptor != null) {
                            from(descriptor)
                        }
                    } catch (e: FileNotFoundException) {
                        Log.w("error", e)
                    }
                }
            }
        }
    }

    fun build(): MediaInfo? {
        return if (!hasError) {
            MediaInfo(
                fileFormatName!!,
                duration!!,
                videoStream,
                audioStreams,
                subtitleStreams
            )
        } else null
    }

    /**
     * JNI FUNCTIONS: functions to use in jni to build [MediaInfo] object.
     */

    /* Used from JNI */
    @Keep
    @SuppressWarnings("UnusedPrivateMember")
    private fun onError() {
        this.hasError = true
    }

    /* Used from JNI */
    @Keep
    @SuppressWarnings("UnusedPrivateMember")
    private fun onMediaInfoFound(
        fileFormatName: String,
        duration: Long
    ) {
        this.fileFormatName = fileFormatName
        this.duration = duration
    }

    /* Used from JNI */
    @Keep
    @SuppressWarnings("UnusedPrivateMember")
    private fun onVideoStreamFound(
        index: Int,
        title: String,
        codecName: String,
        language: String?,
        disposition: Int,
        bitRate: Long,
        frameRate: Double,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (videoStream == null) {
            videoStream = VideoStream(
                index = index,
                title = title,
                codecName = codecName,
                language = language,
                disposition = disposition,
                bitRate = bitRate,
                frameRate = frameRate,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        }
    }

    /* Used from JNI */
    @Keep
    @SuppressWarnings("UnusedPrivateMember")
    private fun onAudioStreamFound(
        index: Int,
        title: String,
        codecName: String,
        language: String?,
        disposition: Int,
        bitRate: Long,
        sampleFormat: String?,
        sampleRate: Int,
        channels: Int,
        channelLayout: String?
    ) {
        audioStreams.add(
            AudioStream(
                index = index,
                title = title,
                codecName = codecName,
                language = language,
                disposition = disposition,
                sampleFormat = sampleFormat,
                sampleRate = sampleRate,
                bitRate = bitRate,
                channels = channels,
                channelLayout = channelLayout
            )
        )
    }

    /* Used from JNI */
    @Keep
    @SuppressWarnings("UnusedPrivateMember")
    private fun onSubtitleStreamFound(
        index: Int,
        title: String,
        codecName: String,
        language: String?,
        disposition: Int
    ) {
        subtitleStreams.add(
            SubtitleStream(
                index = index,
                title = title,
                codecName = codecName,
                language = language,
                disposition = disposition
            )
        )
    }

    @Keep
    private external fun nativeCreateFromFD(fileDescriptor: Int)

    @Keep
    private external fun nativeCreateFromPath(filePath: String)

    init {
        System.loadLibrary("mediainfo")
    }
}


