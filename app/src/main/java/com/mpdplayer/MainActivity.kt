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

@UnstableApi
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
        rvChannels.isFocusable = true

        findViewById<View>(R.id.btnReset).setOnClickListener { resetPlaylist() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnSearch.setOnClickListener { showSearchDialog() }

        // 1. Load cache in background so the UI appears instantly (not blocked
        //    by file I/O, Gson deserialization, and category grouping).
        loadingBar.visibility = View.VISIBLE
        Thread {
            try {
                val json = readChannelCacheFile()
                if (json != null) {
                    val cached: List<Channel> = gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
                    val playlists = getCachedPlaylists()
                    val activeNames = playlists.filter { it.isActive }.map { it.name }.toSet()
                    val filteredChannels = cached.mapNotNull { ch ->
                        val activeSources = ch.sources.filter { it.playlistName in activeNames }
                        if (activeSources.isNotEmpty()) {
                            ch.sources.clear(); ch.sources.addAll(activeSources); ch
                        } else null
                    }
                    val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
                    val epgMapJson = prefs.getString("cached_epg_playlist_map", "{}")
                    val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                    val epgMap: Map<String, Set<String>> = try { gson.fromJson(epgMapJson, type) } catch(e: Exception) { emptyMap() }
                    
                    mainHandler.post {
                        synchronized(allChannels) { allChannels.clear(); allChannels.addAll(filteredChannels) }
                        EpgManager.setPlaylistEpgMap(epgMap)
                        epgUrls.clear()
                        activeNames.forEach { name -> epgMap[name]?.let { epgUrls.addAll(it) } }
                        updateCategoriesSync()
                        refreshEpg()
                        loadingBar.visibility = View.GONE
                        loadAllPlaylists(silent = true, refreshEpg = false)
                    }
                } else {
                    mainHandler.post {
                        loadingBar.visibility = View.GONE
                        loadAllPlaylists(silent = false, refreshEpg = true)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load cache async", e)
                mainHandler.post {
                    loadingBar.visibility = View.GONE
                    loadAllPlaylists(silent = false, refreshEpg = true)
                }
            }
        }.start()
    }

    private fun loadFromCacheSync() {
        try {
            val json = readChannelCacheFile() ?: return
            val cached: List<Channel> = gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
            val playlists = getCachedPlaylists()
            val activeNames = playlists.filter { it.isActive }.map { it.name }.toSet()

            val filteredChannels = cached.mapNotNull { ch ->
                val activeSources = ch.sources.filter { it.playlistName in activeNames }
                if (activeSources.isNotEmpty()) {
                    ch.sources.clear()
                    ch.sources.addAll(activeSources)
                    ch
                } else null
            }

            synchronized(allChannels) {
                allChannels.clear()
                allChannels.addAll(filteredChannels)
            }

            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
            val epgMapJson = prefs.getString("cached_epg_playlist_map", "{}")
            val type = object : TypeToken<Map<String, Set<String>>>() {}.type
            val epgMap: Map<String, Set<String>> = try { gson.fromJson(epgMapJson, type) } catch(e: Exception) { emptyMap() }
            
            EpgManager.setPlaylistEpgMap(epgMap)
            epgUrls.clear()
            activeNames.forEach { name -> epgMap[name]?.let { epgUrls.addAll(it) } }
            
            // Initial synchronous update for instant display
            updateCategoriesSync()
            refreshEpg()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load cache sync", e)
        }
    }

    private fun updateCategoriesSync() {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        migrateFavoritesIfNeeded()
        val favJson = prefs.getString("favorites_list", "[]")
        val favorites: List<String> = gson.fromJson(favJson, object : TypeToken<List<String>>() {}.type)
        
        if (allChannels.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            return
        }
        emptyState.visibility = View.GONE

        val snapshot = synchronized(allChannels) { allChannels.toList() }
        val favList = Channel.resolveFavorites(favorites, snapshot)
        
        val newCategories = mutableListOf<CategoryInfo>()
        if (favList.isNotEmpty()) newCategories.add(CategoryInfo("FAVORITES", favList))

        val grouped = snapshot.groupBy { it.group.ifBlank { "OTHERS" } }
        for ((name, list) in grouped.toSortedMap()) {
            if (name.uppercase() != "FAVORITES") newCategories.add(CategoryInfo(name.uppercase(), list))
        }
        
        categories.clear()
        categories.addAll(newCategories)
        channelCount.text = "Total Channels: ${snapshot.size}"
        categoryAdapter.notifyDataSetChanged()
        
        val currentName = lastSelectedCategoryName
            ?: if (categories.isNotEmpty() && selectedCategoryIndex < categories.size) categories[selectedCategoryIndex].name else ""
        val newIdx = if (currentName.isNotEmpty()) categories.indexOfFirst { it.name == currentName } else 0
        selectCategory(if (newIdx != -1) newIdx else 0)
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
        if (pending.decrementAndGet() == 0) {
            mainHandler.post { 
                if (!isFinishing) channelAdapter.notifyDataSetChanged() 
            }
        }
    }

    private fun updateCategories() {
        // Wrapper to perform grouping in background after network refresh
        Thread {
            if (isFinishing) return@Thread
            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
            migrateFavoritesIfNeeded()
            val favJson = prefs.getString("favorites_list", "[]")
            val favorites: List<String> = gson.fromJson(favJson, object : TypeToken<List<String>>() {}.type)
            
            val snapshot = synchronized(allChannels) { allChannels.toList() }
            if (snapshot.isEmpty()) {
                mainHandler.post { 
                    if (!isFinishing) {
                        categories.clear()
                        categoryAdapter.notifyDataSetChanged()
                        emptyState.visibility = View.VISIBLE
                    }
                }
                return@Thread
            }

            val newCategories = mutableListOf<CategoryInfo>()
            val favList = Channel.resolveFavorites(favorites, snapshot)
            if (favList.isNotEmpty()) newCategories.add(CategoryInfo("FAVORITES", favList))

            val grouped = snapshot.groupBy { it.group.ifBlank { "OTHERS" } }
            for ((name, list) in grouped.toSortedMap()) {
                if (name.uppercase() != "FAVORITES") newCategories.add(CategoryInfo(name.uppercase(), list))
            }

            mainHandler.post {
                if (isFinishing) return@post
                emptyState.visibility = View.GONE
                val currentName = lastSelectedCategoryName
                    ?: if (categories.isNotEmpty() && selectedCategoryIndex < categories.size) categories[selectedCategoryIndex].name else ""
                
                categories.clear()
                categories.addAll(newCategories)
                channelCount.text = "Total Channels: ${snapshot.size}"
                categoryAdapter.notifyDataSetChanged()
                
                val newIdx = if (currentName.isNotEmpty()) categories.indexOfFirst { it.name == currentName } else 0
                selectCategory(if (newIdx != -1) newIdx else 0)
            }
        }.start()
    }

    private fun applyActiveFilter() {
        val activeNames = getCachedPlaylists().filter { it.isActive }.map { it.name }.toSet()
        if (activeNames.isEmpty()) {
            allChannels.clear()
            updateCategoriesSync()
            return
        }

        synchronized(allChannels) {
            val filtered = allChannels.filter { ch -> 
                val activeSources = ch.sources.filter { it.playlistName in activeNames }
                if (activeSources.isNotEmpty()) {
                    ch.sources.clear()
                    ch.sources.addAll(activeSources)
                    true
                } else false
            }
            allChannels.clear()
            allChannels.addAll(filtered)
        }
        
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        try {
            val type = object : TypeToken<Map<String, Set<String>>>() {}.type
            val epgMapJson = prefs.getString("cached_epg_playlist_map", "{}")
            val epgMap: Map<String, Set<String>> = gson.fromJson(epgMapJson, type)
            EpgManager.setPlaylistEpgMap(epgMap)
            epgUrls.clear()
            activeNames.forEach { name -> epgMap[name]?.let { epgUrls.addAll(it) } }
        } catch (e: Exception) {
            val epgList: List<String> = try { gson.fromJson(prefs.getString("cached_epg_urls", "[]"), object : TypeToken<List<String>>() {}.type) } catch(ex: Exception) { emptyList() }
            epgUrls.clear(); epgUrls.addAll(epgList)
        }
        updateCategoriesSync()
    }

    private fun readChannelCacheFile(): String? {
        val file = java.io.File(cacheDir, CHANNELS_CACHE_FILE)
        return if (file.exists()) try { file.readText() } catch (e: Exception) { null } else null
    }

    private fun saveToCache() {
        val channelsCopy = synchronized(allChannels) { allChannels.toList() }
        val epgUrlsCopy = epgUrls.toList()
        val epgMap = EpgManager.getPlaylistEpgMap()
        
        Thread {
            try {
                val json = gson.toJson(channelsCopy)
                java.io.File(cacheDir, CHANNELS_CACHE_FILE).writeText(json)
                sChannelsJson = json
                sAllChannelsList = channelsCopy
                
                val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("cached_epg_urls", gson.toJson(epgUrlsCopy))
                    .putString("cached_epg_playlist_map", gson.toJson(epgMap))
                    .apply()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to write channel cache", e)
            }
        }.start()
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
                if (isFinishing) return@post
                loadingBar.visibility = View.GONE
                updateCategories()
                saveToCache()
                refreshEpg()
                Toast.makeText(this@MainActivity, "Reloaded: $playlistName", Toast.LENGTH_SHORT).show()
            }
        }, { err ->
            mainHandler.post {
                if (isFinishing) return@post
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
            synchronized(allChannels) { allChannels.clear() }
            epgUrls.clear()
            loadFromCacheSync()
            loadAllPlaylists(silent = false)
        } else if (reloadSet.isNotEmpty()) {
            prefs.edit().remove("reload_playlists").apply()
            loadFromCacheSync()
            reloadSet.forEach { name -> reloadSinglePlaylist(name) }
        } else {
            if (allChannels.isEmpty()) loadFromCacheSync() else applyActiveFilter()
        }

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
            synchronized(allChannels) { allChannels.clear() }
            updateCategoriesSync()
            return
        }

        if (!silent) loadingBar.visibility = View.VISIBLE

        val tempChannels = mutableListOf<Channel>()
        val tempEpgUrls = mutableSetOf<String>()
        val successNames = mutableSetOf<String>()
        val cacheChannels = synchronized(allChannels) { allChannels.toList() }
        val counter = AtomicInteger(activePlaylists.size)

        val finalize: () -> Unit = {
            val successful = synchronized(successNames) { successNames.toSet() }
            val failedCache = cacheChannels.filter { ch -> ch.sources.none { it.playlistName in successful } }
            val failedPlaylists = activeNames - successful
            
            Thread {
                val finalChannels = mutableListOf<Channel>()
                finalChannels.addAll(tempChannels)
                mergeIntoList(finalChannels, failedCache)
                
                mainHandler.post {
                    if (isFinishing) return@post
                    synchronized(allChannels) {
                        allChannels.clear()
                        allChannels.addAll(finalChannels)
                    }
                    epgUrls.clear()
                    epgUrls.addAll(tempEpgUrls)
                    failedPlaylists.forEach { name -> EpgManager.getPlaylistEpgMap()[name]?.let { epgUrls.addAll(it) } }
                    loadingBar.visibility = View.GONE
                    updateCategories()
                    saveToCache()
                    if (refreshEpg) refreshEpg()
                }
            }.start()
        }

        activePlaylists.forEach { playlist ->
            PlaylistParser.parse(this, playlist.url, playlist.name, { result ->
                synchronized(tempChannels) {
                    mergeIntoList(tempChannels, result.channels)
                    synchronized(successNames) { successNames.add(playlist.name) }
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
        val nameIndex = HashMap<String, Channel>()
        target.forEach { ch ->
            val nk = ch.name.trim().lowercase()
            if (nk.isNotEmpty()) nameIndex.putIfAbsent(nk, ch)
        }

        newList.forEach { newCh ->
            val newNameKey = newCh.name.trim().lowercase()
            val existing = if (newNameKey.isNotEmpty()) nameIndex[newNameKey] else null

            if (existing != null && existing !== newCh) {
                synchronized(existing) {
                    newCh.sources.forEach { s ->
                        if (existing.sources.none { it.url == s.url }) {
                            existing.sources.add(s)
                        }
                    }
                    if (existing.tvgId.isBlank() && newCh.tvgId.isNotBlank()) existing.tvgId = newCh.tvgId.trim()
                    if (existing.group.isBlank() && newCh.group.isNotBlank()) existing.group = newCh.group
                    if (existing.logoUrl.isBlank() && newCh.logoUrl.isNotBlank()) existing.logoUrl = newCh.logoUrl
                }
            } else {
                target.add(newCh)
                val nk = newCh.name.trim().lowercase()
                if (nk.isNotEmpty()) nameIndex.putIfAbsent(nk, newCh)
            }
        }
    }

    private var favoritesMigrated = false
    private fun migrateFavoritesIfNeeded() {
        if (favoritesMigrated || allChannels.isEmpty()) return
        favoritesMigrated = true
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val raw: List<String> = try {
            gson.fromJson(prefs.getString("favorites_list", "[]"), object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { return }
        if (raw.isEmpty()) return

        val index = mutableMapOf<String, Channel>()
        val snapshot = synchronized(allChannels) { allChannels.toList() }
        snapshot.forEach { ch ->
            val tvg = ch.tvgId.trim().lowercase()
            if (tvg.isNotEmpty()) index.putIfAbsent(tvg, ch)
            ch.sources.forEach { src ->
                val composite = "${src.playlistName}::${ch.name.trim()}".lowercase()
                index.putIfAbsent(composite, ch)
            }
            index.putIfAbsent(ch.name.trim().lowercase(), ch)
            ch.altIds.forEach { id -> index.putIfAbsent(id.trim().lowercase(), ch) }
        }

        val seen = mutableSetOf<String>()
        val migrated = mutableListOf<String>()
        raw.forEach { k ->
            val key = k.trim().lowercase()
            if (key.isEmpty()) return@forEach
            val ch = index[key]
            val finalKey = if (ch != null) Channel.favoriteKey(ch) else k
            if (seen.add(finalKey)) migrated.add(finalKey)
        }
        if (migrated != raw) {
            prefs.edit().putString("favorites_list", gson.toJson(migrated)).apply()
        }
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
        channelCount.text = "Total Channels: ${allChannels.size}"
        
        mainHandler.post {
            if (isFinishing) return@post
            if (!rvChannels.isComputingLayout) {
                channelAdapter.notifyDataSetChanged()
                if (!rvChannels.hasFocus()) {
                    rvChannels.scrollToPosition(0)
                }
            }
            
            if (oldIndex != index && !rvCategories.isComputingLayout) {
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

    private fun showFavoriteOptions(channel: Channel, position: Int) {
        val options = arrayOf("Move Up", "Move Down", "Remove from Favorites")
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(channel.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> moveFavorite(position, position - 1)
                    1 -> moveFavorite(position, position + 1)
                    2 -> toggleFavorite(channel)
                }
            }
            .show()
    }

    private fun moveFavorite(fromIndex: Int, toIndex: Int) {
        if (toIndex < 0 || toIndex >= displayedChannels.size) return
        val item = displayedChannels.removeAt(fromIndex)
        displayedChannels.add(toIndex, item)
        val newFavOrder = displayedChannels.map { Channel.favoriteKey(it) }
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("favorites_list", gson.toJson(newFavOrder)).apply()
        
        val favIdx = categories.indexOfFirst { it.name == "FAVORITES" }
        if (favIdx >= 0) {
            categories[favIdx] = CategoryInfo("FAVORITES", displayedChannels.toList())
        }
        
        channelAdapter.notifyItemMoved(fromIndex, toIndex)
        
        rvChannels.post {
            rvChannels.scrollToPosition(toIndex)
            rvChannels.post {
                val vh = rvChannels.findViewHolderForAdapterPosition(toIndex)
                vh?.itemView?.requestFocus()
                showFavoriteOptions(item, toIndex)
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
        if (favorites.contains(key)) {
            favorites.remove(key)
            Toast.makeText(this, "Removed: ${channel.name}", Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(key)
            Toast.makeText(this, "Added to Favorites: ${channel.name}", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putString("favorites_list", gson.toJson(favorites)).apply()
        
        val savedChannelPos = rvChannels.focusedChild?.let { rvChannels.getChildAdapterPosition(it) } ?: -1
        
        updateCategoriesSync()
        
        rvChannels.post {
            val pos = if (savedChannelPos in 0 until displayedChannels.size) savedChannelPos else 0
            if (pos > 0) rvChannels.scrollToPosition(pos)
            rvChannels.post {
                val vh = rvChannels.findViewHolderForAdapterPosition(pos)
                vh?.itemView?.requestFocus()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        categorySelectionRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        EpgManager.clearAll()
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
                    categorySelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                    categorySelectionRunnable = Runnable { 
                        if (!isFinishing && holder.itemView.hasFocus()) {
                            selectCategory(position)
                        }
                    }.also { mainHandler.postDelayed(it, 500) }
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
                val catName = if (selectedCategoryIndex >= 0 && selectedCategoryIndex < categories.size) {
                    categories[selectedCategoryIndex].name
                } else ""
                if (catName == "FAVORITES") {
                    showFavoriteOptions(ch, position)
                } else {
                    toggleFavorite(ch)
                    Toast.makeText(this@MainActivity, "Updated Favorites", Toast.LENGTH_SHORT).show()
                }
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
