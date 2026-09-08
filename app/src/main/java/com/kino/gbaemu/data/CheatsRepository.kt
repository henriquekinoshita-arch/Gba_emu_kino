package com.kino.gbaemu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CheatCode(
    val id: String,
    val name: String,
    val code: String,
    val enabled: Boolean,
)

/** Per-ROM cheat code storage (Game Genie / Action Replay strings, parsed natively by mGBA). */
class CheatsRepository(context: Context) {
    private val cheatsDir = File(context.filesDir, "cheats").apply { mkdirs() }

    private fun file(romId: String) = File(cheatsDir, "$romId.json")

    fun list(romId: String): List<CheatCode> {
        val f = file(romId)
        if (!f.exists()) return emptyList()
        val array = JSONArray(f.readText())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CheatCode(
                id = o.getString("id"),
                name = o.getString("name"),
                code = o.getString("code"),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }

    private fun save(romId: String, cheats: List<CheatCode>) {
        val array = JSONArray()
        cheats.forEach {
            array.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("code", it.code)
                    put("enabled", it.enabled)
                },
            )
        }
        file(romId).writeText(array.toString())
    }

    fun add(romId: String, name: String, code: String) {
        val cheats = list(romId).toMutableList()
        cheats.add(CheatCode(id = UUID.randomUUID().toString(), name = name, code = code, enabled = true))
        save(romId, cheats)
    }

    fun remove(romId: String, id: String) {
        save(romId, list(romId).filterNot { it.id == id })
    }

    fun setEnabled(romId: String, id: String, enabled: Boolean) {
        save(romId, list(romId).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
}
