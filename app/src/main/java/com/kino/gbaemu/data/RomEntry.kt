package com.kino.gbaemu.data

import com.kino.gbaemu.core.Platform
import java.io.File

/** A ROM already imported into app-private storage, ready to be loaded by [Platform.coreLibraryName]. */
data class RomEntry(
    val displayName: String,
    val platform: Platform,
    val romFile: File,
) {
    val id: String get() = "${platform.name}/${romFile.name}"
}
