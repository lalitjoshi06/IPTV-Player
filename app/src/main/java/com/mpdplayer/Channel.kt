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
    val logoUrl: String = "",
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
        /** Generate a unique, stable favorite key — uses tvgId when available, otherwise playlistName::name */
        fun favoriteKey(tvgId: String, name: String, playlistName: String): String {
            return if (tvgId.isNotBlank()) tvgId.trim() else "${playlistName}::${name.trim()}"
        }

        fun favoriteKey(channel: Channel): String {
            val playlist = channel.sources.firstOrNull()?.playlistName ?: ""
            return favoriteKey(channel.tvgId, channel.name, playlist)
        }

        /** Resolve a list of favorite keys to Channel objects. Checks tvgId first, then composite key (playlist::name), then name, then altIds. Preserves input order. */
        fun resolveFavorites(keys: List<String>, allChannels: List<Channel>): List<Channel> {
            val index = mutableMapOf<String, Channel>()
            allChannels.forEach { ch ->
                val tvg = ch.tvgId.trim().lowercase()
                if (tvg.isNotEmpty()) index.putIfAbsent(tvg, ch)
                index.putIfAbsent("${(ch.sources.firstOrNull()?.playlistName ?: "")}::${ch.name.trim()}".lowercase(), ch)
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
