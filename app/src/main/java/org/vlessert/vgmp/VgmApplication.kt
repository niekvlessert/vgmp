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
        
        // Load all bundled files sequentially to avoid race conditions with VgmEngine
        // The VgmEngine has shared state, so parallel loading can cause tag corruption
        applicationScope.launch {
            // Load bundled audio files on first run (NSF, SPC, PSF, etc.)
            GameLibrary.loadBundledAudioFilesIfNeeded(this@VgmApplication, listOf(
                "Shovel_Knight_Music.nsf",
                "Plok.zip",
                "Doom 1.zip",
                "Doom 2.zip",
                "FF7_psf.rar"
            ))
            
            // Load bundled tracker files on first run (MOD, XM, S3M, IT, etc.)
            GameLibrary.loadBundledTrackerFilesIfNeeded(this@VgmApplication, listOf(
                "2nd_reality.s3m"
            ))
            
            // Load Doom 1 MUS files (1993)
            GameLibrary.loadBundledMusFilesIfNeeded(
                this@VgmApplication, 
                listOf(
                    "doom1_mus/D_BUNNY.lmp",
                    "doom1_mus/D_E1M1.lmp",
                    "doom1_mus/D_E1M2.lmp",
                    "doom1_mus/D_E1M3.lmp",
                    "doom1_mus/D_E1M4.lmp",
                    "doom1_mus/D_E1M5.lmp",
                    "doom1_mus/D_E1M6.lmp",
                    "doom1_mus/D_E1M7.lmp",
                    "doom1_mus/D_E1M8.lmp",
                    "doom1_mus/D_E1M9.lmp",
                    "doom1_mus/D_E2M1.lmp",
                    "doom1_mus/D_E2M2.lmp",
                    "doom1_mus/D_E2M3.lmp",
                    "doom1_mus/D_E2M4.lmp",
                    "doom1_mus/D_E2M5.lmp",
                    "doom1_mus/D_E2M6.lmp",
                    "doom1_mus/D_E2M7.lmp",
                    "doom1_mus/D_E2M8.lmp",
                    "doom1_mus/D_E2M9.lmp",
                    "doom1_mus/D_E3M1.lmp",
                    "doom1_mus/D_E3M2.lmp",
                    "doom1_mus/D_E3M3.lmp",
                    "doom1_mus/D_E3M4.lmp",
                    "doom1_mus/D_E3M5.lmp",
                    "doom1_mus/D_E3M6.lmp",
                    "doom1_mus/D_E3M7.lmp",
                    "doom1_mus/D_E3M8.lmp",
                    "doom1_mus/D_E3M9.lmp",
                    "doom1_mus/D_INTER.lmp",
                    "doom1_mus/D_INTRO.lmp",
                    "doom1_mus/D_INTROA.lmp",
                    "doom1_mus/D_VICTOR.lmp"
                ),
                gameName = "Doom",
                year = "1993"
            )
            
            // Load Doom 2 MUS files (1994)
            GameLibrary.loadBundledMusFilesIfNeeded(
                this@VgmApplication, 
                listOf(
                    "doom2_mus/D_ADRIAN.lmp",
                    "doom2_mus/D_AMPIE.lmp",
                    "doom2_mus/D_BETWEE.lmp",
                    "doom2_mus/D_COUNT2.lmp",
                    "doom2_mus/D_COUNTD.lmp",
                    "doom2_mus/D_DDTBL2.lmp",
                    "doom2_mus/D_DDTBL3.lmp",
                    "doom2_mus/D_DDTBLU.lmp",
                    "doom2_mus/D_DEAD.lmp",
                    "doom2_mus/D_DEAD2.lmp",
                    "doom2_mus/D_DM2INT.lmp",
                    "doom2_mus/D_DM2TTL.lmp",
                    "doom2_mus/D_DOOM.lmp",
                    "doom2_mus/D_DOOM2.lmp",
                    "doom2_mus/D_EVIL.lmp",
                    "doom2_mus/D_IN_CIT.lmp",
                    "doom2_mus/D_MESSAG.lmp",
                    "doom2_mus/D_MESSG2.lmp",
                    "doom2_mus/D_OPENIN.lmp",
                    "doom2_mus/D_READ_M.lmp",
                    "doom2_mus/D_ROMER2.lmp",
                    "doom2_mus/D_ROMERO.lmp",
                    "doom2_mus/D_RUNNI2.lmp",
                    "doom2_mus/D_RUNNIN.lmp",
                    "doom2_mus/D_SHAWN.lmp",
                    "doom2_mus/D_SHAWN2.lmp",
                    "doom2_mus/D_SHAWN3.lmp",
                    "doom2_mus/D_STALKS.lmp",
                    "doom2_mus/D_STLKS2.lmp",
                    "doom2_mus/D_STLKS3.lmp",
                    "doom2_mus/D_TENSE.lmp",
                    "doom2_mus/D_THE_DA.lmp",
                    "doom2_mus/D_THEDA2.lmp",
                    "doom2_mus/D_THEDA3.lmp",
                    "doom2_mus/D_ULTIMA.lmp"
                ),
                gameName = "Doom II",
                year = "1994"
            )
        }
    }
}
