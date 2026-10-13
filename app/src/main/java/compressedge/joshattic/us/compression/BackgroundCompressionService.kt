package compressedge.joshattic.us.compression

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import compressedge.joshattic.us.MainActivity
import compressedge.joshattic.us.R
import compressedge.joshattic.us.utils.MediaDeleter
import compressedge.joshattic.us.utils.MediaStoreVideoSaver
import compressedge.joshattic.us.utils.formatFileSize
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps a compression running while the app is in the
 * background. Experimental: results may vary across devices/OEMs.
 *
 * Headless compressions started from the camera-watch notification (ACTION_START) are queued
 * and run one at a time — the device's hardware encoder is typically single-session — so tapping
 * a preset on several pending notifications compresses all of them in turn instead of dropping
 * all but the first.
 */
@OptIn(UnstableApi::class)
class BackgroundCompressionService : Service() {

    companion object {
        const val ACTION_START = "compressedge.joshattic.us.action.START_BACKGROUND_COMPRESSION"
        const val ACTION_START_BATCH = "compressedge.joshattic.us.action.START_BACKGROUND_COMPRESSION_BATCH"

        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_VIDEO_MIME = "extra_video_mime"
        const val EXTRA_OUTPUT_HEIGHT = "extra_output_height"
        const val EXTRA_OUTPUT_FPS = "extra_output_fps"
        const val EXTRA_ORIGINAL_WIDTH = "extra_original_width"
        const val EXTRA_ORIGINAL_HEIGHT = "extra_original_height"
        const val EXTRA_ORIGINAL_FPS = "extra_original_fps"
        const val EXTRA_ORIGINAL_SIZE = "extra_original_size"
        const val EXTRA_TARGET_BITRATE = "extra_target_bitrate"
        const val EXTRA_AUDIO_BITRATE = "extra_audio_bitrate"
        const val EXTRA_AUDIO_CODEC = "extra_audio_codec"
        const val EXTRA_REMOVE_AUDIO = "extra_remove_audio"
        const val EXTRA_AUDIO_VOLUME = "extra_audio_volume"

        /** Set only for fully headless compressions (e.g. triggered from a notification action):
         * on success, save the output to this MediaStore RELATIVE_PATH and delete the source. */
        const val EXTRA_AUTO_SAVE_RELATIVE_PATH = "extra_auto_save_relative_path"
        const val EXTRA_AUTO_DELETE_SOURCE_URI = "extra_auto_delete_source_uri"

        /** Notification id to use for this headless job's progress/done notifications, so
         * several queued jobs each keep their own distinct notification. */
        const val EXTRA_RESULT_NOTIFICATION_ID = "extra_result_notification_id"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "background_compression"
        private const val DONE_NOTIFICATION_TIMEOUT_MS = 10 * 60 * 1000L

        /** Stops an in-flight background compression. */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, BackgroundCompressionService::class.java))
        }
    }

    private data class HeadlessJob(
        val params: CompressionExecutor.Params,
        val outputPath: String,
        val originalSize: Long,
        val notificationId: Int,
        val autoSaveRelativePath: String?,
        val autoDeleteSourceUri: Uri?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var lastProgressPercent = -1
    private var cancelled = false

    private val headlessQueue = ArrayDeque<HeadlessJob>()
    private var activeHeadlessJob: HeadlessJob? = null
    private var batchRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            enqueueHeadlessJob(intent)
        } else if (intent?.action == ACTION_START_BATCH) {
            startBatchCompression(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        progressJob?.cancel()
        progressJob = null
        transformer?.cancel()
        transformer = null
        headlessQueue.clear()
        activeHeadlessJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun parseHeadlessJob(intent: Intent): HeadlessJob? {
        val inputUriString = intent.getStringExtra(EXTRA_INPUT_URI)
        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (inputUriString.isNullOrBlank() || path.isNullOrBlank()) return null

        val autoDeleteSourceUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_AUTO_DELETE_SOURCE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_AUTO_DELETE_SOURCE_URI)
        }

        val params = CompressionExecutor.Params(
            inputUri = Uri.parse(inputUriString),
            outputPath = path,
            videoMimeType = intent.getStringExtra(EXTRA_VIDEO_MIME) ?: MimeTypes.VIDEO_H265,
            outputHeight = intent.getIntExtra(EXTRA_OUTPUT_HEIGHT, 0),
            outputFps = intent.getIntExtra(EXTRA_OUTPUT_FPS, 0),
            originalWidth = intent.getIntExtra(EXTRA_ORIGINAL_WIDTH, 0),
            originalHeight = intent.getIntExtra(EXTRA_ORIGINAL_HEIGHT, 0),
            originalFps = intent.getFloatExtra(EXTRA_ORIGINAL_FPS, 30f),
            targetBitrate = intent.getIntExtra(EXTRA_TARGET_BITRATE, 2_000_000),
            audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000),
            audioCodec = intent.getStringExtra(EXTRA_AUDIO_CODEC) ?: MimeTypes.AUDIO_AAC,
            removeAudio = intent.getBooleanExtra(EXTRA_REMOVE_AUDIO, false),
            audioVolume = intent.getFloatExtra(EXTRA_AUDIO_VOLUME, 1f),
            onHdrToneMap = {
                BackgroundCompressionManager.setHdrWarning(getString(R.string.warning_hdr_tone_mapped))
            }
        )

        return HeadlessJob(
            params = params,
            outputPath = path,
            originalSize = intent.getLongExtra(EXTRA_ORIGINAL_SIZE, 0L),
            notificationId = intent.getIntExtra(EXTRA_RESULT_NOTIFICATION_ID, NOTIFICATION_ID),
            autoSaveRelativePath = intent.getStringExtra(EXTRA_AUTO_SAVE_RELATIVE_PATH),
            autoDeleteSourceUri = autoDeleteSourceUri
        )
    }

    private fun enqueueHeadlessJob(intent: Intent) {
        val job = parseHeadlessJob(intent)
        if (job == null) {
            stopServiceIfIdle()
            return
        }
        headlessQueue.addLast(job)
        if (activeHeadlessJob == null) {
            processNextHeadlessJob()
        }
    }

    private fun processNextHeadlessJob() {
        if (activeHeadlessJob != null || transformer != null) return
        val next = headlessQueue.poll()
        if (next == null) {
            stopServiceIfIdle()
            return
        }
        activeHeadlessJob = next
        runHeadlessJob(next)
    }

    private fun stopServiceIfIdle() {
        if (activeHeadlessJob == null && headlessQueue.isEmpty() && !batchRunning) {
            // DETACH, not REMOVE: a done/failed notification may have just been posted under the
            // same id that startForeground() used — REMOVE would delete it instantly.
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun runHeadlessJob(job: HeadlessJob) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                job.notificationId,
                buildNotification(job.notificationId, getString(R.string.notification_bg_starting), null),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                job.notificationId,
                buildNotification(job.notificationId, getString(R.string.notification_bg_starting), null)
            )
        }
        BackgroundCompressionManager.setRunning(job.originalSize)
        lastProgressPercent = -1

        transformer = CompressionExecutor.execute(
            applicationContext,
            job.params,
            onCompleted = { finalSize ->
                val uri = Uri.fromFile(File(job.outputPath))
                BackgroundCompressionManager.complete(uri, finalSize)
                val doneText = doneDetailsText(job.originalSize, finalSize)
                val autoSaveRelativePath = job.autoSaveRelativePath
                val autoDeleteSourceUri = job.autoDeleteSourceUri
                if (autoSaveRelativePath != null && autoDeleteSourceUri != null) {
                    // Keep this job "active" (and the service alive) while this runs, otherwise
                    // moving on to the next queued job too early could race it.
                    scope.launch(Dispatchers.IO) {
                        val outputFile = File(job.outputPath)
                        if (outputFile.exists() &&
                            MediaStoreVideoSaver.saveToRelativePath(applicationContext, outputFile, autoSaveRelativePath)
                        ) {
                            MediaDeleter.deleteOriginalVideo(applicationContext, autoDeleteSourceUri)
                        }
                        withContext(Dispatchers.Main.immediate) {
                            finishHeadlessJob(job, doneText)
                        }
                    }
                } else {
                    finishHeadlessJob(job, doneText)
                }
            },
            onError = { exportException ->
                if (cancelled) {
                    finishHeadlessJob(job, null)
                    return@execute
                }
                val message = CompressionExecutor.errorMessage(applicationContext, exportException)
                BackgroundCompressionManager.fail(message, exportException.stackTraceToString())
                finishHeadlessJob(job, getString(R.string.notification_bg_failed))
            }
        )

        progressJob = scope.launch {
            while (transformer != null && BackgroundCompressionManager.state.value.isRunning) {
                val progressHolder = ProgressHolder()
                val status = transformer?.getProgress(progressHolder)
                if (status != null && status != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    val currentSize = if (File(job.outputPath).exists()) File(job.outputPath).length() else 0L
                    BackgroundCompressionManager.updateProgress(progressHolder.progress / 100f, currentSize)
                    val percent = progressHolder.progress
                    if (percent / 5 != lastProgressPercent) {
                        lastProgressPercent = percent / 5
                        updateNotification(job.notificationId, percent)
                    }
                }
                delay(200)
            }
        }
    }

    private fun finishHeadlessJob(job: HeadlessJob, notificationText: String?) {
        progressJob?.cancel()
        progressJob = null
        transformer = null
        if (notificationText != null) {
            showDoneNotification(job.notificationId, notificationText)
        } else {
            NotificationManagerCompat.from(this).cancel(job.notificationId)
        }
        activeHeadlessJob = null
        processNextHeadlessJob()
    }

    private fun doneDetailsText(originalSize: Long, compressedSize: Long): String {
        if (originalSize <= 0L) return getString(R.string.notification_bg_done)
        val percentSaved = (((originalSize - compressedSize) * 100L) / originalSize).toInt().coerceAtLeast(0)
        return getString(
            R.string.notification_bg_done_details,
            formatFileSize(originalSize),
            formatFileSize(compressedSize),
            percentSaved
        )
    }

    private fun finishCompression(notificationText: String?) {
        progressJob?.cancel()
        progressJob = null
        transformer = null
        batchRunning = false
        if (notificationText != null) {
            showDoneNotification(NOTIFICATION_ID, notificationText)
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopServiceIfIdle()
    }

    private fun buildNotification(notificationId: Int, contentText: String, progressPercent: Int?): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdateNotification(contentText, progressPercent, contentIntent())
        } else {
            buildLegacyProgressNotification(contentText, progressPercent, contentIntent())
        }
    }

    private fun contentIntent(context: Context = this): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Android 16+ Live Updates: the platform [Notification.ProgressStyle] notification is
     * promoted to the lockscreen/status bar, and on Samsung One UI 8 it appears in the
     * Now Bar through the same Android 16 API. Promotion is requested via
     * the "android.requestPromotedOngoing" notification extra and the
     * POST_PROMOTED_NOTIFICATIONS manifest permission.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("InlinedApi")
    private fun buildLiveUpdateNotification(
        contentText: String,
        progressPercent: Int?,
        contentIntent: PendingIntent
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
        if (progressPercent != null) {
            progressStyle.setProgress(progressPercent)
            progressStyle.setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification))
        } else {
            progressStyle.setProgressIndeterminate(true)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setExtras(liveUpdateExtras(contentText))
            .setShortCriticalText(contentText)
            .setStyle(progressStyle)
            .build()
    }

    @SuppressLint("InlinedApi")
    private fun liveUpdateExtras(contentText: String): Bundle {
        return Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            samsungNowBarExtras(contentText)?.let { putAll(it) }
        }
    }

    private fun buildLegacyProgressNotification(
        contentText: String,
        progressPercent: Int?,
        contentIntent: PendingIntent
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        samsungNowBarExtras(contentText)?.let { builder.setExtras(it) }
        return builder.build()
    }

    /**
     * Samsung extras: consumed by Samsung's SystemUI. One UI 7 Now Bar is whitelist-only; One UI
     * 8/8.5 surfaces the Android 16 Live Updates (see [buildLiveUpdateNotification]) in the Now Bar,
     * so these extras are included as a best effort on both notification paths.
     */
    private fun samsungNowBarExtras(contentText: String): Bundle? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        return Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1)
            putString("android.ongoingActivityNoti.primaryInfo", getString(R.string.notification_bg_title))
            putString("android.ongoingActivityNoti.secondaryInfo", contentText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", getString(R.string.notification_bg_title))
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", contentText)
        }
    }

    private fun progressText(progressPercent: Int?): String =
        if (progressPercent != null) "$progressPercent%" else getString(R.string.notification_bg_starting)

    private fun updateNotification(notificationId: Int, percent: Int) {
        val notification = buildNotification(notificationId, progressText(percent), percent)
        notifyCompressionNotification(notificationId, notification)
    }

    private fun showDoneNotification(notificationId: Int, contentText: String) {
        notifyCompressionNotification(notificationId, buildDoneNotification(this, contentText))
    }

    /** No progress bar, auto-dismisses after 10 minutes if the user doesn't tap it first. */
    private fun buildDoneNotification(context: Context, contentText: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_bg_done_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setTimeoutAfter(DONE_NOTIFICATION_TIMEOUT_MS)
            .build()
    }

    private fun notifyCompressionNotification(notificationId: Int, notification: Notification) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(notificationId, notification)
        }
    }

    private fun startBatchCompression(intent: Intent) {
        val batch = BackgroundCompressionManager.pendingBatch
        if (batch.isEmpty()) {
            stopServiceIfIdle()
            return
        }
        val totalOriginalSize = intent.getLongExtra(EXTRA_ORIGINAL_SIZE, 0L)
        batchRunning = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NOTIFICATION_ID, getString(R.string.notification_bg_starting), null),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NOTIFICATION_ID, getString(R.string.notification_bg_starting), null)
            )
        }

        scope.launch(Dispatchers.IO) {
            var anyErrors = false
            var lastUri: Uri? = null
            val completedUris = mutableListOf<Uri>()

            for ((index, params) in batch.withIndex()) {
                if (!isActive || cancelled) break

                try {
                    val finalSize = CompressionExecutor.executeSuspend(applicationContext, params) { holder, _ ->
                        val overallProgress = (index.toFloat() + (holder.progress / 100f)) / batch.size.toFloat()

                        val currentOutputSize = if (File(params.outputPath).exists()) File(params.outputPath).length() else 0L
                        BackgroundCompressionManager.updateProgress(overallProgress, currentOutputSize, params.inputUri, index)

                        val currentPercent = (overallProgress * 100).toInt()
                        if (currentPercent != lastProgressPercent) {
                            lastProgressPercent = currentPercent
                            updateNotification(NOTIFICATION_ID, currentPercent)
                        }
                    }

                    val uri = Uri.fromFile(File(params.outputPath))
                    lastUri = uri
                    completedUris.add(uri)
                } catch (e: Exception) {
                    val errorMsg = if (e is androidx.media3.transformer.ExportException) {
                        CompressionExecutor.errorMessage(applicationContext, e)
                    } else {
                        e.localizedMessage ?: getString(R.string.error_unknown)
                    }
                    if (e !is kotlinx.coroutines.CancellationException) {
                        BackgroundCompressionManager.fail(errorMsg, e.stackTraceToString())
                        updateNotification(NOTIFICATION_ID, 0)
                    }
                    anyErrors = true
                    break
                }
            }

            if (!anyErrors && isActive && !cancelled) {
                val totalOutputSize = completedUris.sumOf { File(it.path!!).length() }
                BackgroundCompressionManager.complete(
                    lastUri ?: Uri.parse(""),
                    totalOutputSize,
                    completedUris
                )
                finishCompression(doneDetailsText(totalOriginalSize, totalOutputSize))
            } else if (cancelled) {
                finishCompression(null)
            } else {
                finishCompression(getString(R.string.notification_bg_failed))
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
