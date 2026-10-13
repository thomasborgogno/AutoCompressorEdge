package compressedge.joshattic.us.utils

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.common.MimeTypes

data class VideoMetadata(
    val size: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val audioBitrate: Int,
    val fps: Float,
    val durationMs: Long,
    val videoMime: String?
)

/**
 * Reads the metadata needed to plan a compression, without requiring a ViewModel/UI — used by
 * fully headless flows (e.g. compressing directly from a notification action).
 */
object VideoMetadataReader {

    fun read(context: Context, uri: Uri): VideoMetadata {
        var size = 0L
        var width = 0
        var height = 0
        var bitrate = 0
        var audioBitrate = 0
        var fps = 30f
        var durationMs = 0L
        var videoMime: String? = null

        try {
            audioBitrate = getAudioBitrate(context, uri)
            val (trackMime, trackWidth, trackHeight, trackFrameRate) = getVideoTrackInfo(context, uri)
                ?: Quad(null, 0, 0, 0f)
            videoMime = trackMime

            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                size = it.statSize
            }

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: trackWidth
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: trackHeight

            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            fps = fpsStr?.toFloatOrNull() ?: 0f
            if (fps <= 0f && trackFrameRate > 0f) {
                fps = trackFrameRate
            }
            if (fps <= 0f) {
                fps = 30f
            }

            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return VideoMetadata(size, width, height, bitrate, audioBitrate, fps, durationMs, videoMime)
    }

    private data class Quad(val mime: String?, val width: Int, val height: Int, val frameRate: Float)

    private fun getAudioBitrate(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    return format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return 0
    }

    private fun getVideoTrackInfo(context: Context, uri: Uri): Quad? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    var frameRate = 0f
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (e: Exception) {
                            try {
                                frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            } catch (ignored: Exception) {}
                        }
                    }
                    return Quad(mime, width, height, frameRate)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return null
    }

    /** True if a hardware encoder for [mimeType] exists on this device. */
    fun hasHardwareEncoder(mimeType: String): Boolean {
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (info.isSoftwareOnly) continue
                } else if (info.name.lowercase().startsWith("c2.android")) {
                    continue
                }
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /** Picks H.265 when the device can hardware-encode it, otherwise falls back to H.264. */
    fun pickDefaultVideoCodec(): String {
        return if (hasHardwareEncoder(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
    }

    /** Short codec label used in default output filenames (e.g. "H265"). */
    fun codecSuffix(mimeType: String): String {
        return when (mimeType) {
            MimeTypes.VIDEO_H265 -> "H265"
            MimeTypes.VIDEO_H264 -> "H264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            else -> mimeType.substringAfterLast("/").uppercase()
        }
    }
}
