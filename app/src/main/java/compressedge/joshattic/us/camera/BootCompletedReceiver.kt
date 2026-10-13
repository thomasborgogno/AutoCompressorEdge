package compressedge.joshattic.us.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-registers the camera video watcher job after a reboot or app update. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(CameraVideoWatcher.PREF_ENABLED, false)) {
            CameraVideoWatcher.schedule(context)
        }
    }
}
