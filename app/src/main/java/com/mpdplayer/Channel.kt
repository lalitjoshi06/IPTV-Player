package com.mpdplayer

data class StreamSource(
    val url: String,
    val licenseUrl: String = "",
    val playlistName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val drmType: String = "",
    val manifestType: String = ""
)

data class Channel(
    var name: String,
    var logoUrl: String = "",
    var group: String = "",
    var tvgId: String = "",
    val channelNumber: String = "",
    val sources: MutableList<StreamSource> = mutableListOf(),
    var isFavorite: Boolean = false,
    val altIds: MutableSet<String> = mutableSetOf()
) {
    val mpdUrl: String get() = sources.firstOrNull()?.url ?: ""
    val licenseUrl: String get() = sources.firstOrNull()?.licenseUrl ?: ""
    val drmType: String get() = sources.firstOrNull()?.drmType ?: ""
    
    fun getAllLookupIds(): List<String> {
        val ids = mutableSetOf<String>()
        if (tvgId.isNotBlank()) ids.add(tvgId.trim().lowercase())
        altIds.forEach { if (it.isNotBlank()) ids.add(it.trim().lowercase()) }
        return ids.toList()
    }

    companion object {
        fun normalizedName(name: String): String {
            return name.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        /** Generate a unique, stable favorite key using normalized name as the anchor.
         *  Prepend tvgId prefix when available so keys with same tvgId but different names are distinct.
         *  When no tvgId, use purely name-based key that never changes regardless of playlist/source changes. */
        fun favoriteKey(tvgId: String, name: String, playlistName: String): String {
            val norm = normalizedName(name)
            return if (tvgId.isNotBlank()) "tk:${tvgId.trim().lowercase()}:n:$norm" else "nk:$norm"
        }

        fun favoriteKey(channel: Channel): String {
            return favoriteKey(channel.tvgId, channel.name, "")
        }

        /** Resolve stored favorite keys to actual Channel objects.
         *  Builds multiple index maps from allChannels keyed by ALL possible representations,
         *  then for each stored key tries every strategy to find a matching channel.
         *  Works with old formats (plain tvgId, name::channel, composite) AND new dual-format keys. */
        fun resolveFavorites(keys: List<String>, allChannels: List<Channel>): List<Channel> {
            // Build lookup maps from channels
            val byExactKey = HashMap<String, Channel>()   // "tk:...","src:...::...", raw names
            val byNormalizedName = LinkedHashMap<String, Channel>()  // normalized name -> channel
            val byRawTvgId = HashMap<String, Channel>()    // plain lowercase tvgId
            val byAltId = HashMap<String, Channel>()       // all altIds
            
            for (ch in allChannels) {
                val norm = normalizedName(ch.name)
                
                // tvgId indexed under multiple formats
                if (ch.tvgId.isNotBlank()) {
                    val tvgLower = ch.tvgId.trim().lowercase()
                    byRawTvgId[tvgLower] = ch
                    byExactKey["tk:$tvgLower:n:$norm"] = ch
                }
                
                // Composite keys: src::playlistName::channelName (all lowercase)
                for (src in ch.sources) {
                    val composite = "src:${src.playlistName.lowercase()}:${ch.name.trim().lowercase()}"
                    byExactKey[composite] = ch
                }
                
                // Raw lowercase name
                val rawLower = ch.name.trim().lowercase()
                byExactKey[rawLower] = ch
                
                // Normalized names (both raw and prefixed)
                if (norm.isNotEmpty()) {
                    byNormalizedName.putIfAbsent(norm, ch)
                    byExactKey["nk:$norm"] = ch
                }
                
                // altIds: both raw and normalized forms
                for (id in ch.altIds) {
                    val idClean = id.trim().lowercase()
                    byAltId[idClean] = ch
                    val idNorm = normalizedName(id)
                    if (idNorm.isNotEmpty()) {
                        byExactKey[idNorm] = ch
                    }
                }
            }

            return keys.mapNotNull { rawKey ->
                val key = rawKey.trim()
                if (key.isEmpty()) return@mapNotNull null
                
                var result: Channel? = null
                
                // Strategy 1: direct lookup in any index
                if (result == null) result = byExactKey[key]
                if (result == null) result = byNormalizedName[key]
                if (result == null) result = byRawTvgId[key]
                if (result == null) result = byAltId[key]
                
                // Strategy 2: parse composite key format "tk:xxx:n:yyy" or "nk:yyy"
                if (result == null && ':' in key) {
                    val parts = key.split(':')
                    when {
                        parts.size >= 4 && parts[0] == "tk" -> {
                            val tvgCandidate = parts[1]
                            result = byRawTvgId[tvgCandidate] ?: byNormalizedName[parts[3]]
                        }
                        parts.size >= 2 && parts[0] == "nk" -> {
                            result = byNormalizedName[parts.subList(1, parts.size).joinToString(":")]
                        }
                        key.startsWith("src:") || key.startsWith("tk:") || key.startsWith("nk:") -> {
                            // Unknown composite prefix — try extracting the normalized name part
                            for (i in parts.indices) {
                                if (parts[i] == "n" && i + 1 < parts.size) {
                                    result = byNormalizedName[parts[i + 1]]
                                    break
                                }
                            }
                        }
                        else -> {
                            result = byNormalizedName[parts.joinToString("").replace(Regex("[^a-z0-9]"), "")]
                        }
                    }
                }
                
                // Strategy 3: handle old dual-format keys stored as "tvgId|normalizedName" 
                if (result == null && '|' in key) {
                    val parts = key.split('|')
                    for (part in parts) {
                        val p = part.trim()
                        if (p.isEmpty()) continue
                        if (result == null) result = byRawTvgId[p]
                        if (result == null) result = byNormalizedName[p]
                        if (result == null) result = byAltId[p] ?: byExactKey[p]
                    }
                }
                
                // Strategy 4: extract normalized name from any key format
                if (result == null) {
                    val normCandidate = normalizedName(key)
                    if (normCandidate.isNotEmpty()) result = byNormalizedName[normCandidate]
                }
                
                // Strategy 5: iterate all channels and check if stored key matches any representation
                if (result == null) {
                    for ((k, c) in byRawTvgId) {
                        if (key.contains(k)) { result = c; break }
                    }
                }
                
                // Strategy 6: plain substring match against normalized names 
                if (result == null) {
                    val strippedKey = key.replace(Regex("[^a-z0-9]"), "")
                    if (strippedKey.length >= 4) {
                        for ((norm, c) in byNormalizedName) {
                            if (norm.contains(strippedKey) && norm.length >= 6) { result = c; break }
                        }
                    }
                }

                result ?: allChannels.find { 
                    it.tvgId.equals(key.trim(), ignoreCase = true)
                        || normalizedName(it.name) == key.replace(Regex("[^a-z0-9]"), "")
                }
            }.let { 
                // Deduplicate preserving order (important for old dual-format keys resolving to same channel)
                val seen = linkedSetOf<String>()
                it.filter { ch -> seen.add(ch.name) } 
            }
        }
    }
}
