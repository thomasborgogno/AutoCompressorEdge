package compressedge.joshattic.us.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.edit
import compressedge.joshattic.us.camera.CameraVideoWatcher
import java.io.File

/** Saves a local file into MediaStore's video collection at a given RELATIVE_PATH. */
object MediaStoreVideoSaver {

    fun saveToRelativePath(context: Context, file: File, relativePath: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = context.contentResolver.insert(collection, values) ?: return false

            // Saving our own compressed output into a watched folder (e.g. DCIM/Camera) would
            // otherwise look like a fresh recording to CameraVideoWatcherJobService and
            // re-trigger its notification. Mark this row as already handled first.
            try {
                context.getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE).edit {
                    putLong(CameraVideoWatcher.PREF_LAST_NOTIFIED_ID, ContentUris.parseId(itemUri))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            context.contentResolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
