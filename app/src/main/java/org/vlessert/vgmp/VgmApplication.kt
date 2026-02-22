package org.vlessert.vgmp

import android.app.Application
import org.vlessert.vgmp.library.GameLibrary

class VgmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GameLibrary.init(this)
    }
}
