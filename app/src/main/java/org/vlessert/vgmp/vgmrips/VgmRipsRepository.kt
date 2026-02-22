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
            Log.d(TAG, "Loaded ${packList.size} packs from dump.json")
            packList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dump.json", e)
            emptyList()
        }
    }
    
    suspend fun search(context: Context, query: String): List<VgmRipsPack> = withContext(Dispatchers.IO) {
        val allPacks = loadPacks(context)
        if (query.isBlank()) return@withContext emptyList()
        
        val lowerQuery = query.lowercase()
        allPacks.filter { pack ->
            pack.title.lowercase().contains(lowerQuery) ||
            pack.composer.lowercase().contains(lowerQuery) ||
            pack.system.lowercase().contains(lowerQuery) ||
            pack.soundChips.lowercase().contains(lowerQuery)
        }.take(50)
    }
}
