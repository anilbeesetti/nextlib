package io.github.anilbeesetti.nextlib.mediainfo

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException

class MediaInfoBuilder(private val context: Context) {

    private var hasError: Boolean = false
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

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

    private fun onError() {
        this.hasError = true
    }

    private fun onMediaInfoFound(
        fileFormatName: String,
        duration: Long
    ) {
        this.fileFormatName = fileFormatName
        this.duration = duration
    }

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

    private external fun nativeCreateFromFD(fileDescriptor: Int)

    private external fun nativeCreateFromPath(filePath: String)

    init {
        System.loadLibrary("mediainfo")
    }
}


internal object PathUtil {
    /*
     * Gets the file path of the given Uri.
     */
    @SuppressLint("NewApi")
    fun getPath(context: Context, uri: Uri): String? {
        var uri = uri
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        // Uri is different in versions after KITKAT (Android 4.4), we need to
        // deal with different Uris.
        if (DocumentsContract.isDocumentUri(context.applicationContext, uri)) {
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
            } else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                uri = if (id.startsWith("raw:/")) {
                    return id.replace("raw:/", "file:///")
                } else {
                    if (id.startsWith("msf:")) {
                        // Case: Android 10 emulator, a video file downloaded via Chrome app.
                        // No knowledge how to reconstruct the file path. So just fail fast.
                        return null
                    }
                    ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), id.toLong()
                    )
                }
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]
                if ("image" == type) {
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                selection = "_id=?"
                selectionArgs = arrayOf(split[1])
            }
        }
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                val column_index = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                if (cursor.moveToFirst()) {
                    return cursor.getString(column_index)
                }
            } catch (e: Exception) {
            }
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}


