package org.vlessert.vgmp

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.vlessert.vgmp.library.GameLibrary

class VgmApplication : Application() {
    private val applicationScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        GameLibrary.init(this)
        
        // Load bundled NSF file on first run
        applicationScope.launch {
            GameLibrary.loadBundledAudioFilesIfNeeded(this@VgmApplication, listOf("Shovel_Knight_Music.nsf"))
        }
    }
}
