package compressedge.joshattic.us.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import compressedge.joshattic.us.MainActivity
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.QualityPreset
import compressedge.joshattic.us.utils.formatFileSize

/**
 * Posts the "new recording, want to compress it?" notifications and keeps them under an
 * explicit group with a real summary notification.
 *
 * Without an app-declared group, Android's own notification manager auto-bundles several
 * notifications from the same app into a system-generated "Aggregate_AlertingSection" summary —
 * which on some OEM SystemUI builds (observed on this Samsung device) has a rendering glitch
 * where the whole shade blanks out momentarily right as it's pulled down. Declaring our own
 * group + summary tells the system we're handling the grouping ourselves, so it doesn't impose
 * its own.
 */
object CameraWatchNotifications {
    private const val CHANNEL_ID = "camera_video_watch"
    private const val GROUP_KEY = "compressedge.joshattic.us.camera_watch_group"
    private const val SUMMARY_NOTIFICATION_ID = 2_099_999

    /** Keeps one distinct notification (and distinct PendingIntents) per recorded video, so
     * recording several videos in a row stacks a notification for each one instead of replacing
     * the previous one. */
    fun notificationIdFor(mediaStoreId: Long): Int =
        (2_100_000 + (mediaStoreId % 900_000)).toInt()

    fun show(context: Context, mediaStoreId: Long, uri: Uri, name: String, size: Long, relativePath: String) {
        createNotificationChannel(context)
        val notificationId = notificationIdFor(mediaStoreId)

        val importIntent = Intent(context, MainActivity::class.java).apply {
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
            context,
            notificationId,
            importIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val expandedText = "$name\n${context.getString(R.string.notification_camera_watch_quality_label)}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_camera_watch_title, formatFileSize(size)))
            .setContentText(context.getString(R.string.notification_camera_watch_text, name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(GROUP_KEY)

        presetAction(context, notificationId, uri, name, relativePath, QualityPreset.HIGH, R.string.preset_high)?.let { builder.addAction(it) }
        presetAction(context, notificationId, uri, name, relativePath, QualityPreset.MEDIUM, R.string.preset_medium)?.let { builder.addAction(it) }
        presetAction(context, notificationId, uri, name, relativePath, QualityPreset.LOW, R.string.preset_low)?.let { builder.addAction(it) }

        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(notificationId, builder.build())
        }
        updateGroupSummary(context)
    }

    /** Cancels one notification from the group (e.g. a preset button was tapped) and refreshes
     * the group summary to match what's left. */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
        updateGroupSummary(context)
    }

    private fun updateGroupSummary(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val activeCount = try {
            manager.activeNotifications.count { it.id != SUMMARY_NOTIFICATION_ID && it.groupKey.contains(GROUP_KEY) }
        } catch (e: Exception) {
            0
        }

        if (activeCount == 0) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_camera_watch_channel_name))
            .setContentText(context.getString(R.string.notification_camera_watch_summary_text, activeCount))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
        }
    }

    /** One quick-action button per quality preset: tapping it compresses fully in the background. */
    private fun presetAction(
        context: Context,
        notificationId: Int,
        uri: Uri,
        name: String,
        relativePath: String,
        preset: QualityPreset,
        labelRes: Int
    ): NotificationCompat.Action? {
        val presetIntent = Intent(context, CameraWatchCompressReceiver::class.java).apply {
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
            context,
            requestCode,
            presetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(labelRes), presetPendingIntent).build()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_camera_watch_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
