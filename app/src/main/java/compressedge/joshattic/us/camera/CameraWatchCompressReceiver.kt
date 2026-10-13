package compressedge.joshattic.us.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import compressedge.joshattic.us.MainActivity
import compressedge.joshattic.us.compression.BackgroundCompressionService
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.model.QualityPreset
import compressedge.joshattic.us.model.QualityPresetConfigStore
import compressedge.joshattic.us.utils.VideoMetadataReader
import java.io.File
import kotlin.concurrent.thread

/**
 * Handles the "High/Medium/Low" quick-action buttons on the camera-watch notification. The whole
 * flow (reading metadata, planning the compression, running it, saving the result back and
 * deleting the original) happens entirely in the background — no UI is ever shown.
 */
class CameraWatchCompressReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPRESS_PRESET = "compressedge.joshattic.us.action.COMPRESS_PRESET_FROM_NOTIFICATION"
        const val EXTRA_PRESET = "extra_preset"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPRESS_PRESET) return

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return

        val relativePath = intent.getStringExtra(MainActivity.EXTRA_ORIGINAL_RELATIVE_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: (Environment.DIRECTORY_DCIM + "/Camera/")
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "video.mp4"
        val preset = try {
            QualityPreset.valueOf(intent.getStringExtra(EXTRA_PRESET) ?: return)
        } catch (e: Exception) {
            return
        }

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            CameraWatchNotifications.cancel(context, notificationId)
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                startHeadlessCompression(appContext, uri, relativePath, displayName, preset, notificationId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startHeadlessCompression(
        context: Context,
        uri: Uri,
        relativePath: String,
        displayName: String,
        preset: QualityPreset,
        notificationId: Int
    ) {
        val metadata = VideoMetadataReader.read(context, uri)
        if (metadata.size <= 0L || metadata.durationMs <= 0L) return

        val config = QualityPresetConfigStore.load(context, preset)
        val videoCodec = VideoMetadataReader.pickDefaultVideoCodec()

        val targetResolutionHeight = config.resolutionShortSide
        val targetFps = if (config.targetFps > 0 && metadata.fps >= config.targetFps) config.targetFps else 0
        val targetMb = (metadata.size / (1024.0 * 1024.0) * config.sizeRatio).toFloat().coerceAtLeast(0.1f)

        // Mirrors CompressorViewModel.applyPreset()'s plan so a notification tap behaves like
        // picking the same preset in the app.
        val plan = CompressorUiState(
            originalSize = metadata.size,
            originalWidth = metadata.width,
            originalHeight = metadata.height,
            originalBitrate = metadata.bitrate,
            originalAudioBitrate = metadata.audioBitrate,
            originalFps = metadata.fps,
            durationMs = metadata.durationMs,
            videoCodec = videoCodec,
            targetResolutionHeight = targetResolutionHeight,
            targetFps = targetFps,
            targetSizeMb = targetMb,
            audioBitrate = config.audioBitrate,
            removeAudio = false
        ).autoAdjust(targetMb, lockAudioBitrate = true, allowUpward = false)

        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()
        val baseName = displayName.substringBeforeLast(".").ifBlank { "Compressed_${System.currentTimeMillis()}" }
        val codecSuffix = VideoMetadataReader.codecSuffix(videoCodec)
        var outputFile = File(outputDir, "${baseName}_${codecSuffix}.mp4")
        var counter = 1
        while (outputFile.exists()) {
            outputFile = File(outputDir, "${baseName}_${codecSuffix} ($counter).mp4")
            counter++
        }

        val serviceIntent = Intent(context, BackgroundCompressionService::class.java).apply {
            action = BackgroundCompressionService.ACTION_START
            putExtra(BackgroundCompressionService.EXTRA_INPUT_URI, uri.toString())
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_PATH, outputFile.absolutePath)
            putExtra(BackgroundCompressionService.EXTRA_VIDEO_MIME, videoCodec)
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_HEIGHT, plan.targetResolutionHeight)
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_FPS, plan.targetFps)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_WIDTH, metadata.width)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_HEIGHT, metadata.height)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_FPS, metadata.fps)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_SIZE, metadata.size)
            putExtra(BackgroundCompressionService.EXTRA_TARGET_BITRATE, plan.targetBitrate)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_BITRATE, plan.audioBitrate)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_CODEC, MimeTypes.AUDIO_AAC)
            putExtra(BackgroundCompressionService.EXTRA_REMOVE_AUDIO, false)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_VOLUME, 1f)
            putExtra(BackgroundCompressionService.EXTRA_AUTO_SAVE_RELATIVE_PATH, relativePath)
            putExtra(BackgroundCompressionService.EXTRA_AUTO_DELETE_SOURCE_URI, uri)
            if (notificationId != -1) {
                putExtra(BackgroundCompressionService.EXTRA_RESULT_NOTIFICATION_ID, notificationId)
            }
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
