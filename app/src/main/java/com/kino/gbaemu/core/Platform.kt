package com.kino.gbaemu.core

/** A supported console. Drives which core .so gets loaded and how ROMs are recognized. */
enum class Platform(val coreLibraryName: String, val extensions: List<String>) {
    GBA(coreLibraryName = "kino_core_gba", extensions = listOf("gba")),
    NDS(coreLibraryName = "kino_core_nds", extensions = listOf("nds"));

    companion object {
        fun fromFileName(name: String): Platform? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
