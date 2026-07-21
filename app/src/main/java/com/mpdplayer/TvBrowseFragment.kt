package com.mpdplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.atomic.AtomicInteger

@androidx.media3.common.util.UnstableApi
class TvBrowseFragment : BrowseSupportFragment() {

    private val allChannels = mutableListOf<Channel>()
    private val epgData = mutableMapOf<String, EpgData>()
    private val epgUrls = mutableSetOf<String>()
    private var selectedChannel: Channel? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = "TV-Player"
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.colorAccent)

        prepareEntranceTransition()
        loadAllPlaylists()
    }

    private fun loadAllPlaylists() {
        val prefs = requireContext().getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("playlists_json", "") ?: ""
        if (json.isEmpty()) return

        val playlists: List<Playlist> = Gson().fromJson(json, object : TypeToken<List<Playlist>>() {}.type)
        if (playlists.isEmpty()) return

        allChannels.clear()
        epgUrls.clear()
        val counter = AtomicInteger(playlists.size)

        playlists.forEach { playlist ->
            PlaylistParser.parse(requireContext(), playlist.url, playlist.name, { result ->
                mergeChannels(result.channels)
                if (playlist.useEpg) {
                    epgUrls.addAll(result.epgUrls)
                }
                
                if (counter.decrementAndGet() == 0) {
                    Handler(Looper.getMainLooper()).post {
                        setupAdapter()
                        loadAllEpgs()
                    }
                }
            }, { 
                if (counter.decrementAndGet() == 0) {
                    Handler(Looper.getMainLooper()).post { setupAdapter() }
                }
            })
        }
    }

    private fun mergeChannels(newList: List<Channel>) {
        synchronized(allChannels) {
            newList.forEach { newCh ->
                val existing = allChannels.find { it.name.trim().equals(newCh.name.trim(), true) }
                if (existing != null) {
                    newCh.sources.forEach { s -> if (existing.sources.none { it.url == s.url }) existing.sources.add(s) }
                } else {
                    allChannels.add(newCh)
                }
            }
        }
    }

    private fun loadAllEpgs() {
        epgUrls.forEach { url ->
            EpgParser.loadEpg(requireContext(), url, { epgMap ->
                Handler(Looper.getMainLooper()).post {
                    epgData.putAll(epgMap)
                    setupAdapter()
                }
            }, { })
        }
    }

    private fun getFavoritesList(): MutableList<String> {
        val prefs = requireContext().getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("favorites_list", "[]") ?: "[]"
        return try {
            Gson().fromJson(json, object : TypeToken<MutableList<String>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveFavoritesList(list: List<String>) {
        val prefs = requireContext().getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("favorites_list", Gson().toJson(list)).apply()
    }

    fun toggleFavoriteForSelected() {
        val channel = selectedChannel ?: return
        val favorites = getFavoritesList()
        val key = Channel.favoriteKey(channel)
        val oldKey = channel.tvgId.ifBlank { channel.name }
        
        val wasFav = favorites.contains(key) || (oldKey != key && favorites.contains(oldKey))
        
        if (wasFav) {
            favorites.remove(key)
            if (oldKey != key) favorites.remove(oldKey)
            Toast.makeText(requireContext(), "Removed from Favorites", Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(key)
            Toast.makeText(requireContext(), "Added to Favorites", Toast.LENGTH_SHORT).show()
        }
        saveFavoritesList(favorites)
        setupAdapter()
    }

    @UnstableApi
    private fun setupAdapter() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val favorites = getFavoritesList()

        if (allChannels.isNotEmpty()) {
            val favList = Channel.resolveFavorites(favorites, allChannels)
            if (favList.isNotEmpty()) {
                rowsAdapter.add(ListRow(HeaderItem("★ FAVORITES"), createChannelAdapter(favList)))
            }

            val grouped = allChannels.groupBy { it.group.ifBlank { "Other" } }
            for ((groupName, groupChannels) in grouped) {
                rowsAdapter.add(ListRow(HeaderItem(groupName), createChannelAdapter(groupChannels)))
            }
        }

        adapter = rowsAdapter

        if (allChannels.isNotEmpty()) {
            setOnItemViewClickedListener { _, item, _, _ ->
                if (item is Channel) {
                    val isFavorite = getFavoritesList().contains(Channel.favoriteKey(item))
                    startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra("channelName", item.name)
                        putExtra("mpdUrl", item.mpdUrl)
                        putExtra("licenseUrl", item.licenseUrl)
                        putExtra("drmType", item.drmType)
                        putExtra("channelTvgId", item.tvgId)
                        putExtra("epgUrls", epgUrls.toTypedArray())
                        putExtra("categoryName", if (isFavorite) "FAVORITES" else item.group)
                        putExtra("channelsJson", Gson().toJson(allChannels))
                    })
                }
            }

            setOnItemViewSelectedListener { _, item, _, _ ->
                if (item is Channel) selectedChannel = item
            }
        }

        startEntranceTransition()
    }

    private fun createChannelAdapter(groupChannels: List<Channel>): ArrayObjectAdapter {
        val presenterSelector = ClassPresenterSelector()
        presenterSelector.addClassPresenter(Channel::class.java, ChannelPresenter(epgData))
        return ArrayObjectAdapter(presenterSelector).apply { addAll(0, groupChannels) }
    }
}
