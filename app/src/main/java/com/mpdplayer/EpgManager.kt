package com.mpdplayer

import android.os.Handler
import android.os.Looper
import android.util.Log

object EpgManager {
    // Stores: EPG_URL -> (Channel_Key -> EpgData)
    private val epgSourceData = mutableMapOf<String, Map<String, EpgData>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    // Map: PlaylistName -> List of EPG URLs from that playlist
    private val playlistToEpgUrls = mutableMapOf<String, Set<String>>()

    fun updateData(sourceUrl: String, newData: Map<String, EpgData>) {
        if (newData.isEmpty()) return
        synchronized(epgSourceData) {
            epgSourceData[sourceUrl] = newData
            Log.d("EpgManager", "EPG updated for $sourceUrl. Keys: ${newData.size}")
        }
        mainHandler.post { listeners.forEach { it() } }
    }

    fun associatePlaylistWithEpg(playlistName: String, urls: List<String>) {
        synchronized(playlistToEpgUrls) {
            playlistToEpgUrls[playlistName] = urls.toSet()
        }
    }

    fun getEpgForChannel(channel: Channel, preferredPlaylistName: String? = null): EpgData? {
        val lookupIds = channel.getAllLookupIds()
        val nameLower = channel.name.lowercase().trim()
        val cleanName = nameLower.replace("hd", "").replace("sd", "").replace("india", "").trim()
        
        synchronized(epgSourceData) {
            // 1. Determine preferred EPG URLs for this playlist
            val preferredUrls = if (preferredPlaylistName != null) {
                playlistToEpgUrls[preferredPlaylistName] ?: emptySet()
            } else emptySet()

            // 2. Try lookup in preferred EPG sources first
            preferredUrls.forEach { url ->
                epgSourceData[url]?.let { dataMap ->
                    findInMap(dataMap, lookupIds, nameLower, cleanName)?.let { return it }
                }
            }

            // 3. Fallback: Search all other EPG sources
            epgSourceData.forEach { (url, dataMap) ->
                if (url !in preferredUrls) {
                    findInMap(dataMap, lookupIds, nameLower, cleanName)?.let { return it }
                }
            }
            
            return null
        }
    }

    private fun findInMap(dataMap: Map<String, EpgData>, lookupIds: List<String>, nameLower: String, cleanName: String): EpgData? {
        // Try IDs first (most accurate)
        for (id in lookupIds) {
            dataMap[id]?.let { return it }
        }
        // Try Names
        dataMap[nameLower]?.let { return it }
        if (cleanName.length > 2) {
            dataMap[cleanName]?.let { return it }
        }
        return null
    }

    // Legacy support
    fun getEpgForChannel(tvgId: String, channelName: String): EpgData? {
        val id = tvgId.trim().lowercase()
        val name = channelName.lowercase().trim()
        val clean = name.replace("hd", "").replace("sd", "").trim()
        
        synchronized(epgSourceData) {
            epgSourceData.values.forEach { dataMap ->
                dataMap[id]?.let { return it }
                dataMap[name]?.let { return it }
                dataMap[clean]?.let { return it }
            }
        }
        return null
    }

    fun getPlaylistEpgMap(): Map<String, Set<String>> {
        synchronized(playlistToEpgUrls) {
            return playlistToEpgUrls.toMap()
        }
    }

    fun setPlaylistEpgMap(map: Map<String, Set<String>>) {
        synchronized(playlistToEpgUrls) {
            playlistToEpgUrls.clear()
            playlistToEpgUrls.putAll(map)
        }
    }

    fun clearAll() {
        synchronized(epgSourceData) {
            epgSourceData.clear()
        }
        mainHandler.post { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}
