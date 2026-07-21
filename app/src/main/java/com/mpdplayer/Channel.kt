package com.mpdplayer

import java.util.HashMap

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

        private var lastResolvedChannels: List<Channel>? = null
        private var lookupIndex: Map<String, Channel>? = null

        /** Resolve stored favorite keys to actual Channel objects.
         *  Builds multiple index maps from allChannels keyed by ALL possible representations,
         *  then for each stored key tries every strategy to find a matching channel.
         *  Works with old formats (plain tvgId, name::channel, composite) AND new dual-format keys. */
        fun resolveFavorites(keys: List<String>, allChannels: List<Channel>): List<Channel> {
            if (keys.isEmpty()) return emptyList()
            
            // Build/reuse lookup index
            val index = if (allChannels === lastResolvedChannels && lookupIndex != null) {
                lookupIndex!!
            } else {
                val newIndex = HashMap<String, Channel>(allChannels.size * 3)
                for (ch in allChannels) {
                    val norm = normalizedName(ch.name)
                    
                    if (ch.tvgId.isNotBlank()) {
                        val tvgLower = ch.tvgId.trim().lowercase()
                        newIndex[tvgLower] = ch
                        newIndex["tk:$tvgLower:n:$norm"] = ch
                    }
                    
                    for (src in ch.sources) {
                        newIndex["src:${src.playlistName.lowercase()}:${ch.name.trim().lowercase()}"] = ch
                    }
                    
                    newIndex[ch.name.trim().lowercase()] = ch
                    if (norm.isNotEmpty()) {
                        newIndex.putIfAbsent(norm, ch)
                        newIndex["nk:$norm"] = ch
                    }
                    
                    for (id in ch.altIds) {
                        val idClean = id.trim().lowercase()
                        newIndex[idClean] = ch
                        val idNorm = normalizedName(id)
                        if (idNorm.isNotEmpty()) newIndex[idNorm] = ch
                    }
                }
                lastResolvedChannels = allChannels
                lookupIndex = newIndex
                newIndex
            }

            return keys.mapNotNull { rawKey ->
                val key = rawKey.trim()
                if (key.isEmpty()) return@mapNotNull null
                
                var result: Channel? = index[key]
                if (result == null && ':' in key) {
                    val parts = key.split(':')
                    if (parts.size >= 4 && parts[0] == "tk") {
                        result = index[parts[1]] ?: index[key] // Fallback to full key if parts fail
                    } else if (parts.size >= 2 && parts[0] == "nk") {
                        val content = key.substringAfter(':')
                        result = index[content] ?: index[key]
                    }
                }
                
                if (result == null) {
                    val normCandidate = normalizedName(key)
                    if (normCandidate.isNotEmpty()) result = index[normCandidate]
                }

                result ?: allChannels.find { 
                    it.tvgId.equals(key.trim(), ignoreCase = true)
                        || normalizedName(it.name) == normalizedName(key)
                }
            }.distinctBy { it.name }
        }
    }
}
