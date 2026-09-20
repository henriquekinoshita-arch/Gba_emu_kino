package com.kino.gbaemu

import android.app.Application
import com.kino.gbaemu.data.RomRepository

class KinoApplication : Application() {
    lateinit var romRepository: RomRepository
        private set

    override fun onCreate() {
        super.onCreate()
        romRepository = RomRepository(this)
    }
}
