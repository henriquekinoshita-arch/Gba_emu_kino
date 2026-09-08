package com.kino.gbaemu.data

import org.json.JSONObject

data class RomEntry(
    val id: String,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val isZip: Boolean,
    val title: String,
    val gameCode: String,
    val lastPlayedAt: Long = 0L,
    val localRomPath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("displayName", displayName)
        put("sizeBytes", sizeBytes)
        put("isZip", isZip)
        put("title", title)
        put("gameCode", gameCode)
        put("lastPlayedAt", lastPlayedAt)
        put("localRomPath", localRomPath)
    }

    companion object {
        fun fromJson(json: JSONObject): RomEntry = RomEntry(
            id = json.getString("id"),
            uri = json.getString("uri"),
            displayName = json.getString("displayName"),
            sizeBytes = json.optLong("sizeBytes", 0L),
            isZip = json.optBoolean("isZip", false),
            title = json.optString("title", json.optString("displayName")),
            gameCode = json.optString("gameCode", "????"),
            lastPlayedAt = json.optLong("lastPlayedAt", 0L),
            localRomPath = json.optString("localRomPath").ifEmpty { null },
        )
    }
}
