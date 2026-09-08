package com.kino.gbaemu.data

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Parsed fields from the 192-byte GBA cartridge header. */
data class RomHeader(
    val title: String,
    val gameCode: String,
)

private const val HEADER_TITLE_OFFSET = 0xA0
private const val HEADER_TITLE_LENGTH = 12
private const val HEADER_CODE_LENGTH = 4
private const val HEADER_TOTAL_NEEDED = HEADER_TITLE_OFFSET + HEADER_TITLE_LENGTH + HEADER_CODE_LENGTH

object RomHeaderParser {

    /**
     * Reads the header directly out of [stream], which must be positioned at
     * the start of the raw .gba image. Consumes (or skips) exactly
     * [HEADER_TOTAL_NEEDED] bytes.
     */
    fun parse(stream: InputStream, fallbackTitle: String): RomHeader {
        val header = ByteArray(HEADER_TOTAL_NEEDED)
        var read = 0
        while (read < header.size) {
            val n = stream.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        if (read < HEADER_TOTAL_NEEDED) {
            return RomHeader(title = fallbackTitle, gameCode = "????")
        }
        val title = decodeAscii(header, HEADER_TITLE_OFFSET, HEADER_TITLE_LENGTH).ifBlank { fallbackTitle }
        val code = decodeAscii(header, HEADER_TITLE_OFFSET + HEADER_TITLE_LENGTH, HEADER_CODE_LENGTH)
        return RomHeader(title = title, gameCode = code.ifBlank { "????" })
    }

    /** Parses either a raw .gba stream or the first .gba entry inside a .zip. */
    fun parseFromRomOrZip(stream: InputStream, isZip: Boolean, fallbackTitle: String): RomHeader {
        if (!isZip) {
            return parse(stream, fallbackTitle)
        }
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".gba", ignoreCase = true)) {
                    return parse(zip, fallbackTitle)
                }
                entry = zip.nextEntry
            }
        }
        return RomHeader(title = fallbackTitle, gameCode = "????")
    }

    private fun decodeAscii(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt().toChar()
            if (b.code == 0) break
            if (b.code in 0x20..0x7E) sb.append(b)
        }
        return sb.toString().trim()
    }
}
