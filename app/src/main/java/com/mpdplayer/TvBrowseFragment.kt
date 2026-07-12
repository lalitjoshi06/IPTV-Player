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
                // Merge strictly by exact name (case-insensitive). tvg-id is NOT used:
                // it is not globally unique across providers (e.g. one provider's
                // News24 and another's Z Marathi can share a tvg-id), so matching by
                // tvg-id wrongly glued unrelated channels together.
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

    private fun getFavoritesPrefs(): Set<String> {
        val prefs = requireContext().getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    fun toggleFavoriteForSelected() {
        val channel = selectedChannel ?: return
        val prefs = requireContext().getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val favs = getFavoritesPrefs().toMutableSet()
        val key = Channel.favoriteKey(channel)
        val oldKey = channel.tvgId.ifBlank { channel.name }
        if (oldKey != key) favs.remove(oldKey)
        
        if (favs.contains(key)) {
            favs.remove(key)
            Toast.makeText(requireContext(), "Removed from Favorites", Toast.LENGTH_SHORT).show()
        } else {
            favs.add(key)
            Toast.makeText(requireContext(), "Added to Favorites", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet("favorites", favs).apply()
        setupAdapter()
    }

    @UnstableApi
    private fun setupAdapter() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val favorites = getFavoritesPrefs()

        val favList = Channel.resolveFavorites(favorites.toList(), allChannels)
        if (favList.isNotEmpty()) {
            rowsAdapter.add(ListRow(HeaderItem("★ FAVORITES"), createChannelAdapter(favList)))
        }

        val grouped = allChannels.groupBy { it.group.ifBlank { "Other" } }
        for ((groupName, groupChannels) in grouped) {
            rowsAdapter.add(ListRow(HeaderItem(groupName), createChannelAdapter(groupChannels)))
        }

        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Channel) {
                val isFavorite = getFavoritesPrefs().contains(Channel.favoriteKey(item))
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

        startEntranceTransition()
    }

    private fun createChannelAdapter(groupChannels: List<Channel>): ArrayObjectAdapter {
        val presenterSelector = ClassPresenterSelector()
        presenterSelector.addClassPresenter(Channel::class.java, ChannelPresenter(epgData))
        return ArrayObjectAdapter(presenterSelector).apply { addAll(0, groupChannels) }
    }
}
