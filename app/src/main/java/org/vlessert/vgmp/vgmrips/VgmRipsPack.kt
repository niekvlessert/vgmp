package org.vlessert.vgmp.vgmrips

data class VgmRipsPack(
    val title: String,
    val composer: String,
    val system: String,
    val soundChips: String,
    val tracks: String,
    val playingTime: String,
    val packAuthor: String,
    val packVersion: String,
    val lastUpdate: String,
    val images: List<String>,
    val zipUrl: String,
    val zipSize: Long
) {
    // Convert HTTP to HTTPS for images
    val imageUrl: String?
        get() = if (images.isNotEmpty()) {
            "https://vgmrips.net/packs/images/large/${images.first()}"
        } else null
    
    // Convert HTTP to HTTPS for downloads
    val safeZipUrl: String
        get() = if (zipUrl.startsWith("http://")) {
            zipUrl.replace("http://", "https://")
        } else zipUrl
    
    val displayInfo: String
        get() = buildString {
            if (system.isNotEmpty()) append("System: $system\n")
            if (composer.isNotEmpty()) append("Composer: $composer\n")
            if (playingTime.isNotEmpty()) append("Time: $playingTime\n")
            if (soundChips.isNotEmpty()) append("Chips: $soundChips")
        }.trim()
}
