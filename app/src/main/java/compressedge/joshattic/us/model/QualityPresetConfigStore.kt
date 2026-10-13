package compressedge.joshattic.us.model

import android.content.Context
import org.json.JSONObject

/**
 * Reads the user's saved quality preset configs directly from SharedPreferences, mirroring
 * CompressorViewModel's loadQualityPresetConfig — used by flows that run without a ViewModel
 * (e.g. compressing directly from a notification action).
 */
object QualityPresetConfigStore {

    private val DEFAULTS = mapOf(
        QualityPreset.HIGH to QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000),
        QualityPreset.MEDIUM to QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000),
        QualityPreset.LOW to QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000)
    )

    private fun prefKey(preset: QualityPreset): String? = when (preset) {
        QualityPreset.HIGH -> "preset_high"
        QualityPreset.MEDIUM -> "preset_medium"
        QualityPreset.LOW -> "preset_low"
        QualityPreset.CUSTOM -> null
    }

    fun load(context: Context, preset: QualityPreset): QualityPresetConfig {
        val default = DEFAULTS[preset] ?: QualityPresetConfig()
        val key = prefKey(preset) ?: return default
        val prefs = context.getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
        val str = prefs.getString(key, null) ?: return default
        return try {
            val obj = JSONObject(str)
            QualityPresetConfig(
                resolutionShortSide = obj.getInt("resolutionShortSide"),
                targetFps = obj.getInt("targetFps"),
                sizeRatio = obj.getDouble("sizeRatio").toFloat(),
                audioBitrate = obj.getInt("audioBitrate"),
                label = when {
                    !obj.has("label") || obj.isNull("label") -> null
                    else -> obj.optString("label", "").takeIf { it.isNotBlank() }
                }
            )
        } catch (e: Exception) {
            default
        }
    }
}
