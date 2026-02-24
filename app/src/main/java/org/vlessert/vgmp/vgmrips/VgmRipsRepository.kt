package org.vlessert.vgmp.vgmrips

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "VgmRipsRepository"

object VgmRipsRepository {
    private var packs: List<VgmRipsPack>? = null
    
    // Common sound chips for filtering
    val SOUND_CHIPS = listOf(
        "All Chips",
        "YM2612", "YM2151", "YM2203", "YM2608", "YM3812", "YMF262", "YMF278B",
        "SN76489", "AY-3-8910", "AY8910",
        "NES APU", "FDS", "MMC5",
        "SID", "TED",
        "OPN", "OPNA", "OPL", "OPL2", "OPL3", "OPL4",
        "SCC", "SCC+", "MSX-MUSIC", "MSX-AUDIO",
        "HuC6280", "PC Engine",
        "K054539", "K053260", "K051649",
        "C140", "C352",
        "RF5C68", "RF5C164",
        "QSound",
        "ES5503", "ES5506",
        "OKIM6258", "OKIM6295",
        "Sega PCM", "Multi-PCM", "PCM",
        "DAC"
    )
    
    suspend fun loadPacks(context: Context): List<VgmRipsPack> = withContext(Dispatchers.IO) {
        packs?.let { return@withContext it }
        
        try {
            val inputStream = context.assets.open("dump.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            reader.close()
            
            val jsonArray = JSONArray(stringBuilder.toString())
            val packList = mutableListOf<VgmRipsPack>()
            
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val pack = VgmRipsPack(
                    title = json.optString("Title", json.optString("topic_title", "")),
                    composer = json.optString("Composer", "Unknown"),
                    system = json.optString("System", json.optString("topic_desc", "")),
                    soundChips = json.optString("Sound Chips", ""),
                    tracks = json.optString("Tracks", ""),
                    playingTime = json.optString("Playing time", ""),
                    packAuthor = json.optString("Pack author", ""),
                    packVersion = json.optString("Pack version", ""),
                    lastUpdate = json.optString("Last Update", ""),
                    images = run {
                        val imgArray = json.optJSONArray("images")
                        if (imgArray == null) emptyList()
                        else (0 until imgArray.length()).map { imgArray.getString(it) }
                    },
                    zipUrl = json.optString("zip_url", ""),
                    zipSize = json.optLong("zip_size", 0)
                )
                packList.add(pack)
            }
            
            packs = packList
            packList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dump.json", e)
            emptyList()
        }
    }
    
    suspend fun search(context: Context, query: String, chipFilter: String = "All Chips"): List<VgmRipsPack> = withContext(Dispatchers.IO) {
        val allPacks = loadPacks(context)
        if (query.isBlank() && chipFilter == "All Chips") return@withContext emptyList()
        
        val lowerQuery = query.lowercase()
        val lowerChip = chipFilter.lowercase()
        
        allPacks.filter { pack ->
            val matchesQuery = query.isBlank() || 
                pack.title.lowercase().contains(lowerQuery) ||
                pack.composer.lowercase().contains(lowerQuery) ||
                pack.system.lowercase().contains(lowerQuery)
            
            val matchesChip = chipFilter == "All Chips" || 
                pack.soundChips.lowercase().contains(lowerChip) ||
                // Handle common aliases
                (lowerChip == "ym2612" && pack.soundChips.lowercase().contains("ym2612")) ||
                (lowerChip == "ym2151" && pack.soundChips.lowercase().contains("ym2151")) ||
                (lowerChip == "sn76489" && (pack.soundChips.lowercase().contains("sn76489") || pack.soundChips.lowercase().contains("psg"))) ||
                (lowerChip == "ay-3-8910" && (pack.soundChips.lowercase().contains("ay-3-8910") || pack.soundChips.lowercase().contains("ay8910"))) ||
                (lowerChip == "nes apu" && (pack.soundChips.lowercase().contains("nes") || pack.soundChips.lowercase().contains("2a03"))) ||
                (lowerChip == "scc" && pack.soundChips.lowercase().contains("scc")) ||
                (lowerChip == "opn" && (pack.soundChips.lowercase().contains("opn") || pack.soundChips.lowercase().contains("ym2203"))) ||
                (lowerChip == "opna" && (pack.soundChips.lowercase().contains("opna") || pack.soundChips.lowercase().contains("ym2608"))) ||
                (lowerChip == "opl2" && (pack.soundChips.lowercase().contains("opl2") || pack.soundChips.lowercase().contains("ym3812"))) ||
                (lowerChip == "opl3" && (pack.soundChips.lowercase().contains("opl3") || pack.soundChips.lowercase().contains("ymf262")))
            
            matchesQuery && matchesChip
        }.take(50)
    }
}
