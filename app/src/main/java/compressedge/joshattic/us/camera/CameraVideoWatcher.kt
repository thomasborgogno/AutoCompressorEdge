package compressedge.joshattic.us.camera

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore

/**
 * Schedules a [CameraVideoWatcherJobService] that wakes up whenever MediaStore's video
 * collection changes (JobInfo.TriggerContentUri), so we can react to a camera recording
 * without running a persistent foreground service.
 */
object CameraVideoWatcher {
    const val PREF_ENABLED = "camera_watch_enabled"
    const val PREF_LAST_NOTIFIED_ID = "camera_watch_last_notified_id"

    private const val JOB_ID = 2001

    fun schedule(context: Context) {
        val jobScheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val componentName = ComponentName(context, CameraVideoWatcherJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .setTriggerContentUpdateDelay(1_500)
            .setTriggerContentMaxDelay(10_000)
            .build()
        jobScheduler.schedule(jobInfo)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
}
