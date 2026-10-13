package compressedge.joshattic.us.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * Deletes original camera recordings once they've been compressed.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (granted via a dedicated system settings screen) because the
 * file was written by the camera app, not by us, so scoped storage blocks a plain
 * ContentResolver.delete() without it.
 */
object MediaDeleter {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun createAllFilesAccessIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /** Best-effort delete; returns true if the file no longer exists afterwards. */
    fun deleteOriginalVideo(context: Context, uri: Uri): Boolean {
        if (!hasAllFilesAccess()) return false

        try {
            val path = queryFilePath(context, uri)
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    context.contentResolver.delete(uri, null, null)
                    return true
                }
            }
            return context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun queryFilePath(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
