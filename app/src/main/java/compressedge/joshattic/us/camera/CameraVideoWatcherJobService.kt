package compressedge.joshattic.us.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import compressedge.joshattic.us.MainActivity
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.QualityPreset
import compressedge.joshattic.us.utils.formatFileSize
import kotlin.concurrent.thread

/**
 * Wakes up when MediaStore's video collection changes. If the newest video looks like a fresh
 * camera recording, shows a notification offering to compress it.
 */
class CameraVideoWatcherJobService : JobService() {

    companion object {
        private const val CHANNEL_ID = "camera_video_watch"

        /** Only react to videos that landed in MediaStore within this window. */
        private const val MAX_AGE_SECONDS = 120L

        /** Keeps one distinct notification (and distinct PendingIntents) per recorded video,
         * so recording several videos in a row stacks a notification for each one instead of
         * replacing the previous one. */
        private fun notificationIdFor(mediaStoreId: Long): Int =
            (2_100_000 + (mediaStoreId % 900_000)).toInt()
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        thread {
            try {
                checkForNewRecording()
            } finally {
                CameraVideoWatcher.schedule(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    /**
     * Looks at every video added since the last one we handled — not just the newest — because
     * JobScheduler coalesces several rapid MediaStore changes (e.g. recording a few videos back
     * to back) into a single job run. Looking only at the single newest row would then silently
     * skip the earlier recordings instead of notifying about each of them.
     */
    private fun checkForNewRecording() {
        val prefs = applicationContext.getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CameraVideoWatcher.PREF_ENABLED, false)) return

        val lastNotifiedId = prefs.getLong(CameraVideoWatcher.PREF_LAST_NOTIFIED_ID, -1L)
        val minDateAdded = (System.currentTimeMillis() / 1000) - MAX_AGE_SECONDS

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        val cursor = applicationContext.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media._ID} > ? AND ${MediaStore.Video.Media.DATE_ADDED} >= ?",
            arrayOf(lastNotifiedId.toString(), minDateAdded.toString()),
            "${MediaStore.Video.Media._ID} ASC"
        ) ?: return

        var highestSeenId = lastNotifiedId
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "video.mp4"
                val bucket = it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)) ?: ""
                val relativePath = try {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)) ?: ""
                } catch (e: Exception) {
                    ""
                }

                highestSeenId = id

                val looksLikeCameraRecording = bucket.equals("Camera", ignoreCase = true) ||
                    relativePath.contains("DCIM/Camera", ignoreCase = true)
                if (!looksLikeCameraRecording) continue

                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                showCompressNotification(id, contentUri, name, size, relativePath)
            }
        }

        if (highestSeenId != lastNotifiedId) {
            prefs.edit().putLong(CameraVideoWatcher.PREF_LAST_NOTIFIED_ID, highestSeenId).apply()
        }
    }

    private fun showCompressNotification(mediaStoreId: Long, uri: Uri, name: String, size: Long, relativePath: String) {
        createNotificationChannel()
        val notificationId = notificationIdFor(mediaStoreId)

        val importIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_IMPORT_AUTO_VIDEO
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(MainActivity.EXTRA_AUTO_DELETE_SOURCE, true)
            putExtra(MainActivity.EXTRA_ORIGINAL_RELATIVE_PATH, relativePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            importIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val expandedText = "$name\n${getString(R.string.notification_camera_watch_quality_label)}"

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_camera_watch_title, formatFileSize(size)))
            .setContentText(getString(R.string.notification_camera_watch_text, name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)

        presetAction(notificationId, uri, name, relativePath, QualityPreset.HIGH, R.string.preset_high)?.let { builder.addAction(it) }
        presetAction(notificationId, uri, name, relativePath, QualityPreset.MEDIUM, R.string.preset_medium)?.let { builder.addAction(it) }
        presetAction(notificationId, uri, name, relativePath, QualityPreset.LOW, R.string.preset_low)?.let { builder.addAction(it) }

        val manager = NotificationManagerCompat.from(applicationContext)
        if (manager.areNotificationsEnabled()) {
            manager.notify(notificationId, builder.build())
        }
    }

    /** One quick-action button per quality preset: tapping it compresses fully in the background. */
    private fun presetAction(
        notificationId: Int,
        uri: Uri,
        name: String,
        relativePath: String,
        preset: QualityPreset,
        labelRes: Int
    ): NotificationCompat.Action? {
        val presetIntent = Intent(applicationContext, CameraWatchCompressReceiver::class.java).apply {
            action = CameraWatchCompressReceiver.ACTION_COMPRESS_PRESET
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(MainActivity.EXTRA_ORIGINAL_RELATIVE_PATH, relativePath)
            putExtra(CameraWatchCompressReceiver.EXTRA_DISPLAY_NAME, name)
            putExtra(CameraWatchCompressReceiver.EXTRA_PRESET, preset.name)
            putExtra(CameraWatchCompressReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        // Distinct request code per notification AND per preset, so each video's three buttons
        // (and the notifications themselves) get independent PendingIntents.
        val requestCode = notificationId * 10 + 1 + preset.ordinal
        val presetPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            presetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, getString(labelRes), presetPendingIntent).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_camera_watch_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
