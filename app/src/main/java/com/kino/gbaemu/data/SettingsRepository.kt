package com.kino.gbaemu.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kino.gbaemu.core.GbaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "kinogba_settings")

enum class ControlSkin { CLASSIC, NEON, MONO }

data class AppSettings(
    val controlSkin: ControlSkin = ControlSkin.NEON,
    val controlOpacity: Float = 0.55f,
    val hapticsEnabled: Boolean = true,
    val showFps: Boolean = false,
    val smoothFiltering: Boolean = false,
    val fastForwardSpeed: Int = 3,
    /** Physical gamepad keycode -> GbaKey ordinal. */
    val controllerMapping: Map<Int, GbaKey> = emptyMap(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val SKIN = stringPreferencesKey("control_skin")
        val OPACITY = floatPreferencesKey("control_opacity")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val SMOOTH = booleanPreferencesKey("smooth_filtering")
        val FF_SPEED = intPreferencesKey("fast_forward_speed")
        val MAPPING = stringPreferencesKey("controller_mapping_json")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            controlSkin = prefs[Keys.SKIN]?.let { runCatching { ControlSkin.valueOf(it) }.getOrNull() }
                ?: ControlSkin.NEON,
            controlOpacity = prefs[Keys.OPACITY] ?: 0.55f,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            showFps = prefs[Keys.SHOW_FPS] ?: false,
            smoothFiltering = prefs[Keys.SMOOTH] ?: false,
            fastForwardSpeed = prefs[Keys.FF_SPEED] ?: 3,
            controllerMapping = prefs[Keys.MAPPING]?.let { decodeMapping(it) } ?: emptyMap(),
        )
    }

    suspend fun setControlSkin(skin: ControlSkin) {
        context.dataStore.edit { it[Keys.SKIN] = skin.name }
    }

    suspend fun setControlOpacity(opacity: Float) {
        context.dataStore.edit { it[Keys.OPACITY] = opacity }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    suspend fun setShowFps(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_FPS] = enabled }
    }

    suspend fun setSmoothFiltering(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMOOTH] = enabled }
    }

    suspend fun setFastForwardSpeed(speed: Int) {
        context.dataStore.edit { it[Keys.FF_SPEED] = speed }
    }

    suspend fun setControllerMapping(mapping: Map<Int, GbaKey>) {
        context.dataStore.edit { it[Keys.MAPPING] = encodeMapping(mapping) }
    }

    private fun encodeMapping(mapping: Map<Int, GbaKey>): String {
        val json = JSONObject()
        mapping.forEach { (keyCode, key) -> json.put(keyCode.toString(), key.name) }
        return json.toString()
    }

    private fun decodeMapping(json: String): Map<Int, GbaKey> {
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { keyCodeStr ->
                val keyCode = keyCodeStr.toIntOrNull() ?: return@mapNotNull null
                val key = runCatching { GbaKey.valueOf(obj.getString(keyCodeStr)) }.getOrNull() ?: return@mapNotNull null
                keyCode to key
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
