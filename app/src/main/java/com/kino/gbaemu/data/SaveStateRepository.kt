package com.kino.gbaemu.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

data class SaveStateSlot(
    val index: Int,
    val exists: Boolean,
    val timestamp: Long,
    val thumbnailPath: String?,
)

/**
 * Numbered save-state slots per ROM. mGBA's own directory-based slot API
 * needs a build feature (ENABLE_DIRECTORIES) this minimal Android build
 * doesn't enable, so the native side just (de)serializes raw state blobs
 * and this class owns naming, slot bookkeeping and thumbnails instead.
 */
class SaveStateRepository(context: Context) {
    private val statesDir = File(context.filesDir, "states").apply { mkdirs() }

    private fun slotFile(romId: String, slot: Int) = File(statesDir, "${romId}_slot$slot.state")
    private fun thumbFile(romId: String, slot: Int) = File(statesDir, "${romId}_slot$slot.png")

    fun listSlots(romId: String, slotCount: Int = SLOT_COUNT): List<SaveStateSlot> =
        (0 until slotCount).map { slot ->
            val file = slotFile(romId, slot)
            val thumb = thumbFile(romId, slot)
            SaveStateSlot(
                index = slot,
                exists = file.exists(),
                timestamp = if (file.exists()) file.lastModified() else 0L,
                thumbnailPath = if (thumb.exists()) thumb.absolutePath else null,
            )
        }

    fun save(romId: String, slot: Int, bytes: ByteArray, thumbnail: Bitmap?) {
        slotFile(romId, slot).writeBytes(bytes)
        if (thumbnail != null) {
            FileOutputStream(thumbFile(romId, slot)).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    fun load(romId: String, slot: Int): ByteArray? {
        val file = slotFile(romId, slot)
        return if (file.exists()) file.readBytes() else null
    }

    fun delete(romId: String, slot: Int) {
        slotFile(romId, slot).delete()
        thumbFile(romId, slot).delete()
    }

    companion object {
        const val SLOT_COUNT = 4
    }
}
