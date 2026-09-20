package com.kino.gbaemu.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kino.gbaemu.core.Platform
import java.io.File

/**
 * ROMs, save RAM and save states all live in app-private storage. ROMs are
 * copied in once from wherever the user picks them via SAF: the libretro
 * core loads games by filesystem path (retro_game_info.path), so a
 * content:// URI is not usable directly, and copying once avoids re-deriving
 * a usable path (and re-requesting SAF permissions) on every launch.
 */
class RomRepository(private val context: Context) {

    private val romsRoot = File(context.filesDir, "roms")
    private val savesRoot = File(context.filesDir, "saves")
    private val statesRoot = File(context.filesDir, "states")
    val systemDir: File = File(context.filesDir, "system").apply { mkdirs() }

    fun romsDir(platform: Platform): File = File(romsRoot, platform.name).apply { mkdirs() }
    fun saveDir(platform: Platform): File = File(savesRoot, platform.name).apply { mkdirs() }
    fun statesDir(platform: Platform): File = File(statesRoot, platform.name).apply { mkdirs() }

    fun listRoms(): List<RomEntry> {
        return Platform.entries.flatMap { platform ->
            romsDir(platform).listFiles().orEmpty()
                .filter { it.isFile }
                .map { RomEntry(displayName = it.nameWithoutExtension, platform = platform, romFile = it) }
        }.sortedBy { it.displayName.lowercase() }
    }

    fun deleteRom(entry: RomEntry) {
        entry.romFile.delete()
        File(saveDir(entry.platform), entry.romFile.nameWithoutExtension + ".srm").delete()
    }

    /** Recursively copies every recognized ROM under [treeUri] into app-private storage. Returns how many were imported. */
    fun importFromTree(treeUri: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        var imported = 0
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                val name = child.name ?: continue
                val platform = Platform.fromFileName(name) ?: continue
                val dest = File(romsDir(platform), name)
                if (dest.exists() && dest.length() == child.length()) continue
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                imported++
            }
        }
        return imported
    }
}
