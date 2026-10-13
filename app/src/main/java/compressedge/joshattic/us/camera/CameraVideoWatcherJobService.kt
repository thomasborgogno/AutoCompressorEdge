package compressedge.joshattic.us.camera

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlin.concurrent.thread

/**
 * Wakes up when MediaStore's video collection changes. If the newest video looks like a fresh
 * camera recording, shows a notification offering to compress it.
 */
class CameraVideoWatcherJobService : JobService() {

    companion object {
        /** Only react to videos that landed in MediaStore within this window. */
        private const val MAX_AGE_SECONDS = 120L
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
                CameraWatchNotifications.show(applicationContext, id, contentUri, name, size, relativePath)
            }
        }

        if (highestSeenId != lastNotifiedId) {
            prefs.edit().putLong(CameraVideoWatcher.PREF_LAST_NOTIFIED_ID, highestSeenId).apply()
        }
    }
}
