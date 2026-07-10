package com.mpdplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        val gson = Gson()
        var sChannelsJson: String? = null
        var sAllChannelsList: List<Channel>? = null
        var sPlaylists: List<Playlist>? = null
        const val CHANNELS_CACHE_FILE = "channels_cache.json"
    }

    private fun getCachedPlaylists(): List<Playlist> {
        if (sPlaylists == null) {
            val json = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE).getString("playlists_json", "")
            sPlaylists = if (!json.isNullOrEmpty()) gson.fromJson(json, object : TypeToken<List<Playlist>>() {}.type) else emptyList()
        }
        return sPlaylists!!
    }

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var loadingBar: ProgressBar
    private lateinit var channelCount: TextView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var btnSettings: ImageView
    private lateinit var btnSearch: ImageView
    private lateinit var emptyState: View

    private val categoryAdapter = CategoryAdapter()
    private val channelAdapter = ChannelAdapter()
    
    private val allChannels = mutableListOf<Channel>()
    private val categories = mutableListOf<CategoryInfo>()
    private val displayedChannels = mutableListOf<Channel>()
    private val epgUrls = mutableSetOf<String>()
    
    private var selectedCategoryIndex = 0
    // Remember the last selected category by name so returning from PlayerActivity
    // (and Favorites being inserted at the top) keeps the right category selected.
    private var lastSelectedCategoryName: String? = null
    private val mainPrefs by lazy { getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lastSelectedCategoryName = mainPrefs.getString("last_category_name", null)

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        loadingBar = findViewById(R.id.loadingBar)
        channelCount = findViewById(R.id.channelCount)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        btnSettings = findViewById(R.id.btnSettings)
        btnSearch = findViewById(R.id.btnSearch)
        emptyState = findViewById(R.id.emptyState)

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = categoryAdapter

        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter
        rvChannels.isFocusable = true // Ensure channels are focusable

        findViewById<View>(R.id.btnReset).setOnClickListener { resetPlaylist() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnSearch.setOnClickListener { showSearchDialog() }

        loadFromCache()
        // A cold start (onCreate) always needs to (re)fetch EPG, because the
        // in-memory EpgManager is empty after a process restart. We must not
        // gate this on cache existence — the cache file may exist while EPG
        // data does not. Skipping EPG only happens on in-process resume
        // (applyActiveFilter), where EPG is still loaded in memory.
        val cacheExisted = allChannels.isNotEmpty()
        loadAllPlaylists(silent = cacheExisted, refreshEpg = true)
    }

    private fun refreshEpg() {
        if (epgUrls.isEmpty()) return
        val urls = epgUrls.toList()
        val pending = java.util.concurrent.atomic.AtomicInteger(urls.size)
        urls.forEach { url ->
            EpgParser.loadEpg(this, url, { data ->
                EpgManager.updateData(url, data)
                onEpgPartLoaded(pending)
            }, {
                onEpgPartLoaded(pending)
            })
        }
    }

    private fun onEpgPartLoaded(pending: java.util.concurrent.atomic.AtomicInteger) {
        pending.decrementAndGet()
        // Update the list as each EPG source finishes so programs appear progressively.
        mainHandler.post { channelAdapter.notifyDataSetChanged() }
    }

    private fun loadFromCache() {
        val json = readChannelCacheFile() ?: return
        try {
            val cached: List<Channel> = gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
            val activeNames = getCachedPlaylists().filter { it.isActive }.map { it.name }.toSet()

            val filteredChannels = cached.mapNotNull { ch ->
                val activeSources = ch.sources.filter { it.playlistName in activeNames }
                if (activeSources.isNotEmpty()) {
                    ch.sources.clear(); ch.sources.addAll(activeSources); ch
                } else null
            }

            allChannels.clear(); allChannels.addAll(filteredChannels)

            // Restore playlist-to-EPG mapping and filter by active playlists
            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
            val epgMapJson = prefs.getString("cached_epg_playlist_map", "{}")
            try {
                val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                val epgMap: Map<String, Set<String>> = gson.fromJson(epgMapJson, type)
                EpgManager.setPlaylistEpgMap(epgMap)
                epgUrls.clear()
                activeNames.forEach { name ->
                    epgMap[name]?.let { epgUrls.addAll(it) }
                }
            } catch (e: Exception) {
                // Fallback: load flat list (legacy)
                val epgList: List<String> = gson.fromJson(prefs.getString("cached_epg_urls", "[]"), object : TypeToken<List<String>>() {}.type)
                epgUrls.clear(); epgUrls.addAll(epgList)
            }
            updateCategories()
        } catch (e: Exception) {}
    }

    /**
     * Re-filter the already-in-memory channel list by the current set of active
     * playlists. Used on resume instead of re-parsing the whole cache file,
     * which is much cheaper (no disk read / Gson deserialize).
     */
    private fun applyActiveFilter() {
        val activeNames = getCachedPlaylists().filter { it.isActive }.map { it.name }.toSet()
        val filtered = allChannels.filter { ch -> ch.sources.any { it.playlistName in activeNames } }
        allChannels.clear(); allChannels.addAll(filtered)
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        try {
            val type = object : TypeToken<Map<String, Set<String>>>() {}.type
            val epgMap: Map<String, Set<String>> = gson.fromJson(prefs.getString("cached_epg_playlist_map", "{}"), type)
            EpgManager.setPlaylistEpgMap(epgMap)
            epgUrls.clear()
            activeNames.forEach { name -> epgMap[name]?.let { epgUrls.addAll(it) } }
        } catch (e: Exception) {
            val epgList: List<String> = gson.fromJson(prefs.getString("cached_epg_urls", "[]"), object : TypeToken<List<String>>() {}.type)
            epgUrls.clear(); epgUrls.addAll(epgList)
        }
        updateCategories()
    }

    private fun readChannelCacheFile(): String? {
        val file = java.io.File(cacheDir, CHANNELS_CACHE_FILE)
        return if (file.exists()) try { file.readText() } catch (e: Exception) { null } else null
    }

    private fun saveToCache() {
        val epgUrlsCopy = epgUrls.toList()
        val epgMap = EpgManager.getPlaylistEpgMap()
        val channelsCopy = allChannels.toList()
        // Serialize + persist the (potentially huge) channel list on a background
        // thread so the UI never blocks. EPG metadata is small, keep it in prefs.
        Thread {
            try {
                val json = gson.toJson(channelsCopy)
                java.io.File(cacheDir, CHANNELS_CACHE_FILE).writeText(json)
                sChannelsJson = json
                sAllChannelsList = channelsCopy
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to write channel cache", e)
            }
        }.start()
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("cached_epg_urls", gson.toJson(epgUrlsCopy))
            .putString("cached_epg_playlist_map", gson.toJson(epgMap))
            .apply()
    }

    private fun reloadSinglePlaylist(playlistName: String) {
        val playlists = getCachedPlaylists()
        val playlist = playlists.find { it.name == playlistName && it.isActive } ?: return

        loadingBar.visibility = View.VISIBLE
        PlaylistParser.parse(this, playlist.url, playlist.name, { result ->
            synchronized(allChannels) {
                allChannels.removeAll { ch ->
                    ch.sources.any { it.playlistName == playlistName }
                }
                mergeIntoList(allChannels, result.channels)
                // Remove stale EPG URLs for this playlist, add new ones
                EpgManager.getPlaylistEpgMap()[playlistName]?.forEach { epgUrls.remove(it) }
                if (playlist.useEpg && result.epgUrls.isNotEmpty()) {
                    epgUrls.addAll(result.epgUrls)
                    EpgManager.associatePlaylistWithEpg(playlist.name, result.epgUrls)
                } else if (!playlist.useEpg) {
                    EpgManager.associatePlaylistWithEpg(playlist.name, emptyList())
                }
                allChannels.removeAll { it.sources.isEmpty() }
            }
            mainHandler.post {
                loadingBar.visibility = View.GONE
                updateCategories()
                saveToCache()
                refreshEpg()
                Toast.makeText(this@MainActivity, "Reloaded: $playlistName", Toast.LENGTH_SHORT).show()
            }
        }, { err ->
            mainHandler.post {
                loadingBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Failed to reload $playlistName: $err", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun resetPlaylist() {
        startActivity(Intent(this, SetupActivity::class.java).apply { putExtra("force_setup", true) })
        finish()
    }

    override fun onResume() {
        super.onResume()
        sPlaylists = null
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val forceRefresh = prefs.getBoolean("force_refresh", false)
        val reloadSet = prefs.getStringSet("reload_playlists", emptySet()) ?: emptySet()

        if (forceRefresh) {
            prefs.edit().remove("force_refresh").apply()
            allChannels.clear()
            epgUrls.clear()
            loadFromCache()
            loadAllPlaylists(silent = false)
        } else if (reloadSet.isNotEmpty()) {
            prefs.edit().remove("reload_playlists").apply()
            loadFromCache()
            reloadSet.forEach { name -> reloadSinglePlaylist(name) }
        } else {
            // Resume (e.g. returning from player): data is already in memory.
            // Re-filter by active playlists cheaply instead of re-parsing the
            // whole cache file + re-fetching EPG.
            if (allChannels.isEmpty()) loadFromCache() else applyActiveFilter()
        }

        // Keep focus locked in the channel list (not the category menu) when returning.
        if (displayedChannels.isNotEmpty()) {
            rvChannels.requestFocus()
        }
    }

    private fun loadAllPlaylists(silent: Boolean = false, refreshEpg: Boolean = true) {
        val playlists = getCachedPlaylists()
        if (playlists.isEmpty()) {
            if (!silent) resetPlaylist()
            return
        }

        val activePlaylists = playlists.filter { it.isActive }
        val activeNames = activePlaylists.map { it.name }.toSet()

        if (activePlaylists.isEmpty()) {
            allChannels.clear()
            updateCategories()
            return
        }

        if (!silent) loadingBar.visibility = View.VISIBLE

        val tempChannels = mutableListOf<Channel>()
        val tempEpgUrls = mutableSetOf<String>()
        val successNames = mutableSetOf<String>()
        // Snapshot of the currently-loaded (cache-based) channels, so we can fall
        // back to them for any playlist that fails to (re)load this launch.
        val cacheChannels = synchronized(allChannels) { allChannels.toList() }
        val counter = AtomicInteger(activePlaylists.size)

        val finalize: () -> Unit = {
            val successful = synchronized(successNames) { successNames.toSet() }
            // Cached channels belonging to playlists that did NOT load this launch.
            // Merging them back in (deduped by tvgId/name, sources combined) keeps
            // the channel list — and therefore the favorites count — stable even
            // when a playlist is temporarily unreachable, instead of dropping it.
            val failedCache = cacheChannels.filter { ch -> ch.sources.none { it.playlistName in successful } }
            val failedPlaylists = activeNames - successful
            mainHandler.post {
                allChannels.clear()
                allChannels.addAll(tempChannels)
                mergeIntoList(allChannels, failedCache)
                epgUrls.clear()
                epgUrls.addAll(tempEpgUrls)
                // Keep EPG URLs for the playlists that failed to load this launch.
                failedPlaylists.forEach { name -> EpgManager.getPlaylistEpgMap()[name]?.let { epgUrls.addAll(it) } }
                loadingBar.visibility = View.GONE
                updateCategories()
                saveToCache()
                if (refreshEpg) refreshEpg()
            }
        }

        activePlaylists.forEach { playlist ->
            PlaylistParser.parse(this, playlist.url, playlist.name, { result ->
                synchronized(tempChannels) {
                    mergeIntoList(tempChannels, result.channels)
                    successNames.add(playlist.name)
                    if (playlist.useEpg) {
                        tempEpgUrls.addAll(result.epgUrls)
                        EpgManager.associatePlaylistWithEpg(playlist.name, result.epgUrls)
                    }
                }

                if (counter.decrementAndGet() == 0) finalize()
            }, { err ->
                if (counter.decrementAndGet() == 0) finalize()
            })
        }
    }

    private fun mergeIntoList(target: MutableList<Channel>, newList: List<Channel>) {
        val index = HashMap<String, Channel>(target.size)
        target.forEach { ch ->
            if (ch.tvgId.isNotBlank()) index[ch.tvgId.trim().lowercase()] = ch
            index[ch.name.trim().lowercase()] = ch
        }
        newList.forEach { newCh ->
            val newName = newCh.name.trim().lowercase()
            val newKey = newCh.tvgId.trim().lowercase()
            val existing = if (newKey.isNotEmpty()) index[newKey] ?: index[newName] else index[newName]

            if (existing != null) {
                if (newCh.tvgId.isNotBlank()) existing.altIds.add(newCh.tvgId.trim().lowercase())
                existing.altIds.addAll(newCh.altIds)
                newCh.sources.forEach { s -> if (existing.sources.none { it.url == s.url }) existing.sources.add(s) }
            } else {
                if (newCh.tvgId.isNotBlank()) newCh.altIds.add(newCh.tvgId.trim().lowercase())
                target.add(newCh)
                index[newName] = newCh
                if (newKey.isNotEmpty()) index[newKey] = newCh
            }
        }
    }

    private fun updateCategories() {
        val currentName = lastSelectedCategoryName
            ?: if (categories.isNotEmpty() && selectedCategoryIndex < categories.size) categories[selectedCategoryIndex].name else ""
        categories.clear()
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val favJson = prefs.getString("favorites_list", "[]")
        val favorites: List<String> = gson.fromJson(favJson, object : TypeToken<List<String>>() {}.type)
        
        if (allChannels.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            return
        }
        emptyState.visibility = View.GONE

        val favList = Channel.resolveFavorites(favorites, allChannels)
        if (favList.isNotEmpty()) categories.add(CategoryInfo("FAVORITES", favList))

        val grouped = allChannels.groupBy { it.group.ifBlank { "OTHERS" } }
        for ((name, list) in grouped.toSortedMap()) {
            if (name.uppercase() != "FAVORITES") categories.add(CategoryInfo(name.uppercase(), list))
        }
        
        channelCount.text = "Total Channels: ${allChannels.size}"
        categoryAdapter.notifyDataSetChanged()
        
        val newIdx = if (currentName.isNotEmpty()) categories.indexOfFirst { it.name == currentName } else 0
        selectCategory(if (newIdx != -1) newIdx else 0)
    }

    private var categorySelectionRunnable: Runnable? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (rvCategories.hasFocus()) {
                val focused = rvCategories.focusedChild ?: return super.onKeyDown(keyCode, event)
                val pos = rvCategories.getChildAdapterPosition(focused)
                if (pos == categoryAdapter.itemCount - 1) return true
            }
            if (rvChannels.hasFocus()) {
                val focused = rvChannels.focusedChild ?: return super.onKeyDown(keyCode, event)
                val pos = rvChannels.getChildAdapterPosition(focused)
                if (pos == channelAdapter.itemCount - 1) return true
            }
        }
        
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            if (rvCategories.hasFocus()) {
                val focused = rvCategories.focusedChild ?: return super.onKeyDown(keyCode, event)
                val pos = rvCategories.getChildAdapterPosition(focused)
                if (pos == 0) return true
            }
            if (rvChannels.hasFocus()) {
                val focused = rvChannels.focusedChild ?: return super.onKeyDown(keyCode, event)
                val pos = rvChannels.getChildAdapterPosition(focused)
                if (pos == 0) return true
            }
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Cancel any pending category selection when moving to channel list
            categorySelectionRunnable?.let { mainHandler.removeCallbacks(it) }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun selectCategory(index: Int) {
        if (index < 0 || index >= categories.size || isFinishing) return
        val oldIndex = selectedCategoryIndex
        selectedCategoryIndex = index
        val cat = categories[index]
        tvCategoryTitle.text = cat.name
        lastSelectedCategoryName = cat.name
        mainPrefs.edit().putString("last_category_name", cat.name).apply()
        displayedChannels.clear()
        displayedChannels.addAll(cat.channels)
        
        // Show GLOBAL channel count
        channelCount.text = "Total Channels: ${allChannels.size}"
        
        mainHandler.post {
            if (isFinishing) return@post
            // Update channels list
            if (!rvChannels.isComputingLayout) {
                channelAdapter.notifyDataSetChanged()
                // ONLY scroll to top if the user just moved to this category
                // Don't scroll if focus is already in the channel list to avoid jumping
                if (!rvChannels.hasFocus()) {
                    rvChannels.scrollToPosition(0)
                }
            }
            
            // Highlight the selected category
            if (oldIndex != index && !rvCategories.isComputingLayout) {
                // Update only the text colors manually to avoid full rebind/scroll
                for (i in 0 until rvCategories.childCount) {
                    val child = rvCategories.getChildAt(i)
                    val holder = rvCategories.getChildViewHolder(child) as? CategoryAdapter.ViewHolder
                    if (holder != null) {
                        val pos = holder.bindingAdapterPosition
                        holder.name.setTextColor(if (pos == selectedCategoryIndex) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt())
                    }
                }
            }
        }
    }

    private fun toggleFavorite(channel: Channel) {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("favorites_list", "[]")
        val favorites: MutableList<String> = try {
            gson.fromJson(json, object : TypeToken<MutableList<String>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
        
        val key = Channel.favoriteKey(channel)
        val oldKey = channel.tvgId.ifBlank { channel.name }
        if (oldKey != key) favorites.remove(oldKey)
        
        val isFavoriteGroup = categories.getOrNull(selectedCategoryIndex)?.name == "FAVORITES"

        if (isFavoriteGroup) {
            // Ensure key is migrated from old format before showing reorder dialog
            if (oldKey != key && favorites.contains(oldKey) && !favorites.contains(key)) {
                favorites.remove(oldKey)
                favorites.add(key)
                prefs.edit().putString("favorites_list", gson.toJson(favorites)).apply()
            }
            showFavoriteReorderDialog(channel, key)
        } else {
            if (favorites.contains(key)) {
                favorites.remove(key)
                Toast.makeText(this, "Removed: ${channel.name}", Toast.LENGTH_SHORT).show()
            } else {
                favorites.add(key)
                Toast.makeText(this, "Added to Favorites: ${channel.name}", Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putString("favorites_list", gson.toJson(favorites)).apply()
            updateCategories()
        }
    }

    private fun showFavoriteReorderDialog(channel: Channel, key: String) {
        val options = arrayOf("Move Up", "Move Down", "Remove from Favorites")
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(channel.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        moveFavorite(key, -1)
                        showFavoriteReorderDialog(channel, key) // Reopen
                    }
                    1 -> {
                        moveFavorite(key, 1)
                        showFavoriteReorderDialog(channel, key) // Reopen
                    }
                    2 -> {
                        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
                        val favorites: MutableList<String> = gson.fromJson(prefs.getString("favorites_list", "[]"), object : TypeToken<MutableList<String>>() {}.type)
                        favorites.remove(key)
                        favorites.remove(channel.tvgId.ifBlank { channel.name })
                        prefs.edit().putString("favorites_list", gson.toJson(favorites)).apply()
                        updateCategories()
                    }
                }
            }.show()
    }

    private fun moveFavorite(key: String, direction: Int) {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("favorites_list", "[]")
        val favorites: MutableList<String> = try {
            gson.fromJson(json, object : TypeToken<MutableList<String>>() {}.type)
        } catch (e: Exception) {
            return
        }
        
        val currentIndex = favorites.indexOf(key)
        if (currentIndex == -1) return
        
        val newIndex = currentIndex + direction
        if (newIndex in 0 until favorites.size) {
            val item = favorites.removeAt(currentIndex)
            favorites.add(newIndex, item)
            prefs.edit().putString("favorites_list", gson.toJson(favorites)).apply()
            
            // Refresh without resetting selection
            val currentCategoryName = categories[selectedCategoryIndex].name
            categories.clear()
            val favList = Channel.resolveFavorites(favorites, allChannels)
            if (favList.isNotEmpty()) categories.add(CategoryInfo("FAVORITES", favList))
            val grouped = allChannels.groupBy { it.group.ifBlank { "OTHERS" } }
            for ((name, list) in grouped.toSortedMap()) {
                if (name.uppercase() != "FAVORITES") categories.add(CategoryInfo(name.uppercase(), list))
            }
            
            val newIdx = categories.indexOfFirst { it.name == currentCategoryName }
            if (newIdx != -1) {
                selectedCategoryIndex = newIdx
                displayedChannels.clear()
                displayedChannels.addAll(categories[newIdx].channels)
                channelAdapter.notifyDataSetChanged()
                categoryAdapter.notifyDataSetChanged()
            }
            
            // Re-focus the moved item
            mainHandler.postDelayed({
                val pos = displayedChannels.indexOfFirst { Channel.favoriteKey(it) == key }
                if (pos != -1) {
                    val vh = rvChannels.findViewHolderForAdapterPosition(pos)
                    vh?.itemView?.requestFocus()
                }
            }, 50)
        }
    }

    override fun onPause() {
        super.onPause()
        categorySelectionRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun showSearchDialog() {
        val builder = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
        builder.setTitle("Search Channels")
        val input = EditText(this)
        input.hint = "Enter channel name..."
        input.setTextColor(0xFFFFFFFF.toInt())
        builder.setView(input)
        builder.setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) {
                val filtered = allChannels.filter { it.name.contains(query, ignoreCase = true) }
                if (filtered.isNotEmpty()) {
                    // Temporarily inject search results into categories
                    val searchCat = CategoryInfo("SEARCH: $query", filtered)
                    val existingSearch = categories.indexOfFirst { it.name.startsWith("SEARCH:") }
                    if (existingSearch != -1) categories.removeAt(existingSearch)
                    categories.add(0, searchCat)
                    categoryAdapter.notifyDataSetChanged()
                    selectCategory(0)
                    rvChannels.requestFocus()
                } else {
                    Toast.makeText(this, "No channels found for '$query'", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        val dialog = builder.create()
        dialog.show()
    }

    data class CategoryInfo(val name: String, val channels: List<Channel>)

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_side_channel, parent, false)
        )
        @UnstableApi
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = categories[position]
            holder.name.text = cat.name
            holder.count.text = cat.channels.size.toString()
            holder.itemView.isSelected = (position == selectedCategoryIndex)
            holder.name.setTextColor(if (position == selectedCategoryIndex) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt())
            
            holder.itemView.setOnClickListener { selectCategory(position) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                    // Prevent focus jumps during fast navigation
                    categorySelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                    categorySelectionRunnable = Runnable { 
                        if (!isFinishing && holder.itemView.hasFocus()) {
                            selectCategory(position)
                        }
                    }.also { mainHandler.postDelayed(it, 500) } // 500ms delay for fast scrolling stability
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }
        override fun getItemCount() = categories.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvChannelName)
            val count: TextView = v.findViewById(R.id.tvCategoryCount)
            init { v.isFocusable = true; v.isFocusableInTouchMode = true }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        )
        @UnstableApi
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ch = displayedChannels[position]
            holder.name.text = ch.name
            
            val playlistName = if (ch.sources.isNotEmpty()) ch.sources[0].playlistName else null
            val epg = EpgManager.getEpgForChannel(ch, playlistName)

            val current = epg?.getCurrentProgram()
            if (current != null) {
                holder.epg.text = "LIVE: ${current.title}"
                holder.epg.visibility = View.VISIBLE
            } else {
                holder.epg.visibility = View.GONE
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, PlayerActivity::class.java)
                intent.putExtra("channelName", ch.name)
                intent.putExtra("mpdUrl", ch.mpdUrl)
                intent.putExtra("licenseUrl", ch.licenseUrl)
                intent.putExtra("drmType", ch.drmType)
                intent.putExtra("channelTvgId", ch.tvgId)
                val categoryName = if (selectedCategoryIndex >= 0 && selectedCategoryIndex < categories.size) {
                    categories[selectedCategoryIndex].name
                } else ""
                intent.putExtra("categoryName", categoryName)
                intent.putExtra("channelsJson", gson.toJson(displayedChannels))
                intent.putExtra("epgUrls", epgUrls.toTypedArray())
                startActivity(intent)
            }
            holder.itemView.setOnLongClickListener {
                toggleFavorite(ch)
                true
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) v.animate().scaleX(1.02f).scaleY(1.05f).setDuration(150).start()
                else v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        override fun getItemCount() = displayedChannels.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.channelName)
            val epg: TextView = v.findViewById(R.id.currentProgramInfo)
            init { v.isFocusable = true; v.isFocusableInTouchMode = true }
        }
    }
}
