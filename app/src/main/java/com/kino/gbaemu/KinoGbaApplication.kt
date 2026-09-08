package com.kino.gbaemu

import android.app.Application
import com.kino.gbaemu.data.CheatsRepository
import com.kino.gbaemu.data.LibraryRepository
import com.kino.gbaemu.data.SaveStateRepository
import com.kino.gbaemu.data.SettingsRepository

/** Minimal hand-rolled service locator; the app is small enough not to need a DI framework. */
class KinoGbaApplication : Application() {
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var saveStateRepository: SaveStateRepository
        private set
    lateinit var cheatsRepository: CheatsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        libraryRepository = LibraryRepository(this)
        settingsRepository = SettingsRepository(this)
        saveStateRepository = SaveStateRepository(this)
        cheatsRepository = CheatsRepository(this)
    }
}
