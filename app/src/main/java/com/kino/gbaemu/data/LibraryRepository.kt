package com.kino.gbaemu.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Tracks user-picked ROM folders (via Storage Access Framework) and the
 * ROM files discovered inside them. ROMs are only copied out of SAF into
 * the app's private storage lazily, the first time they're launched
 * ([importIfNeeded]) - native code needs a real filesystem path, but we
 * don't want to eagerly copy an entire library the user may never play.
 */
class LibraryRepository(private val context: Context) {

    private val storageDir: File get() = context.filesDir
    private val libraryFile: File get() = File(storageDir, "library.json")
    private val foldersFile: File get() = File(storageDir, "folders.json")
    private val romsDir: File get() = File(storageDir, "roms").apply { mkdirs() }
    private val savesDir: File get() = File(storageDir, "saves").apply { mkdirs() }

    fun watchedFolders(): List<Uri> {
        if (!foldersFile.exists()) return emptyList()
        val array = JSONArray(foldersFile.readText())
        return (0 until array.length()).map { Uri.parse(array.getString(it)) }
    }

    fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val current = watchedFolders().map { it.toString() }.toMutableSet()
        current.add(treeUri.toString())
        foldersFile.writeText(JSONArray(current.toList()).toString())
    }

    fun loadLibrary(): List<RomEntry> {
        if (!libraryFile.exists()) return emptyList()
        val array = JSONArray(libraryFile.readText())
        return (0 until array.length()).map { RomEntry.fromJson(array.getJSONObject(it)) }
    }

    private fun saveLibrary(entries: List<RomEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        libraryFile.writeText(array.toString())
    }

    /** Re-scans every watched folder and merges newly found ROMs into the library. */
    fun rescan(): List<RomEntry> {
        val existingById = loadLibrary().associateBy { it.id }.toMutableMap()
        val foundIds = mutableSetOf<String>()

        for (folderUri in watchedFolders()) {
            val root = DocumentFile.fromTreeUri(context, folderUri) ?: continue
            visit(root, existingById, foundIds)
        }

        // Drop entries whose backing file is gone from every watched folder.
        val merged = existingById.filterKeys { it in foundIds }.values.toList()
        saveLibrary(merged)
        return merged
    }

    private fun visit(dir: DocumentFile, existingById: MutableMap<String, RomEntry>, foundIds: MutableSet<String>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                visit(child, existingById, foundIds)
                continue
            }
            val name = child.name ?: continue
            val isZip = name.endsWith(".zip", ignoreCase = true)
            val isGba = name.endsWith(".gba", ignoreCase = true)
            if (!isZip && !isGba) continue

            val id = stableId(child.uri)
            foundIds.add(id)
            if (existingById.containsKey(id)) continue

            val header = try {
                context.contentResolver.openInputStream(child.uri)?.use {
                    RomHeaderParser.parseFromRomOrZip(it, isZip, fallbackTitle = name.substringBeforeLast('.'))
                }
            } catch (e: Exception) {
                null
            } ?: RomHeader(title = name.substringBeforeLast('.'), gameCode = "????")

            existingById[id] = RomEntry(
                id = id,
                uri = child.uri.toString(),
                displayName = name,
                sizeBytes = child.length(),
                isZip = isZip,
                title = header.title,
                gameCode = header.gameCode,
            )
        }
    }

    /** Copies (and unzips, if needed) a ROM into private storage; returns local file paths. */
    fun importIfNeeded(entry: RomEntry): Pair<File, File> {
        val romFile = File(romsDir, "${entry.id}.gba")
        val saveFile = File(savesDir, "${entry.id}.sav")

        if (!romFile.exists() || romFile.length() == 0L) {
            val uri = Uri.parse(entry.uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (entry.isZip) {
                    ZipInputStream(input).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            if (!e.isDirectory && e.name.endsWith(".gba", ignoreCase = true)) {
                                romFile.outputStream().use { out -> zip.copyTo(out) }
                                break
                            }
                            e = zip.nextEntry
                        }
                    }
                } else {
                    romFile.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }

        if (entry.localRomPath != romFile.absolutePath) {
            val entries = loadLibrary().map {
                if (it.id == entry.id) it.copy(localRomPath = romFile.absolutePath) else it
            }
            saveLibrary(entries)
        }

        return romFile to saveFile
    }

    fun markPlayed(id: String) {
        val entries = loadLibrary().map {
            if (it.id == id) it.copy(lastPlayedAt = System.currentTimeMillis()) else it
        }
        saveLibrary(entries)
    }

    fun removeEntry(id: String) {
        saveLibrary(loadLibrary().filterNot { it.id == id })
    }

    companion object {
        fun stableId(uri: Uri): String {
            val digest = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
