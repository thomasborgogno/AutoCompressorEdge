package compressedge.joshattic.us.camera

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import compressedge.joshattic.us.R
import compressedge.joshattic.us.utils.MediaDeleter

/**
 * Transparent activity opened by the "Delete" button of the camera-watch notification. It only
 * hosts a system confirmation dialog; on confirm the video is permanently deleted (no trash).
 */
class CameraWatchDeleteActivity : Activity() {

    companion object {
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: ""
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        AlertDialog.Builder(this)
            .setTitle(R.string.notification_camera_watch_delete_title)
            .setMessage(getString(R.string.notification_camera_watch_delete_message, name))
            .setPositiveButton(R.string.notification_camera_watch_delete) { _, _ ->
                val deleted = MediaDeleter.deleteOriginalVideo(applicationContext, uri)
                if (deleted) {
                    if (notificationId != -1) {
                        CameraWatchNotifications.cancel(applicationContext, notificationId)
                    }
                    Toast.makeText(applicationContext, R.string.delete_original_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, R.string.delete_original_failed, Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
