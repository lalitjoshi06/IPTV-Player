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
        /** Generate a unique, stable favorite key — prefers tvgId, falls back to normalized name. */
        fun favoriteKey(tvgId: String, name: String, playlistName: String): String {
            if (tvgId.isNotBlank()) return tvgId.trim().lowercase()
            // Normalize name: lowercase and remove all non-alphanumeric characters
            return name.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        fun favoriteKey(channel: Channel): String {
            return favoriteKey(channel.tvgId, channel.name, "")
        }

        /** Normalize a channel name for cross-playlist matching: lowercase and strip all non-alphanumeric characters. */
        fun normalizedName(name: String): String {
            return name.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        /** Resolve a list of favorite keys to Channel objects. Checks tvgId first, then all composite keys (playlist::name), then name, then altIds. Preserves input order. */
        fun resolveFavorites(keys: List<String>, allChannels: List<Channel>): List<Channel> {
            val index = mutableMapOf<String, Channel>()
            allChannels.forEach { ch ->
                val tvg = ch.tvgId.trim().lowercase()
                if (tvg.isNotEmpty()) index.putIfAbsent(tvg, ch)
                
                // Index ALL possible composite keys for this channel so favorites are stable 
                // regardless of which source is currently "first" after filtering/merging.
                ch.sources.forEach { src ->
                    val compositeKey = "${src.playlistName}::${ch.name.trim()}".lowercase()
                    index.putIfAbsent(compositeKey, ch)
                }

                index.putIfAbsent(ch.name.trim().lowercase(), ch)
                ch.altIds.forEach { id -> index.putIfAbsent(id.trim().lowercase(), ch) }
            }
            return keys.mapNotNull { key ->
                val k = key.trim().lowercase()
                if (k.isEmpty()) null else index[k]
            }
        }
    }
}
