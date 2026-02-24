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
        
        // Load bundled audio files on first run (NSF, SPC, etc.)
        applicationScope.launch {
            GameLibrary.loadBundledAudioFilesIfNeeded(this@VgmApplication, listOf(
                "Shovel_Knight_Music.nsf",
                "Plok.zip",
                "doom1.zip",
                "doom2.zip"
            ))
        }
        
        // Load bundled tracker files on first run (MOD, XM, S3M, IT, etc.)
        applicationScope.launch {
            GameLibrary.loadBundledTrackerFilesIfNeeded(this@VgmApplication, listOf(
                "2nd_reality.s3m"
            ))
        }
    }
}
