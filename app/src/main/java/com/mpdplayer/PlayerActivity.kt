package com.mpdplayer

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var bufferingBar: ProgressBar
    private lateinit var bufferPercentage: TextView
    
    private lateinit var bottomInfoBar: View
    private lateinit var infoChannelNumber: TextView
    private lateinit var infoChannelName: TextView
    private lateinit var infoSourceName: TextView
    private lateinit var infoCurrentProgram: TextView
    private lateinit var infoNextProgram: TextView
    private lateinit var infoClock: TextView
    private lateinit var playerTopClock: TextView
    private lateinit var infoBtnFav: ImageView
    private lateinit var btnSource: Button
    private lateinit var btnMediaSettings: ImageView

    private lateinit var sideChannelList: View
    private lateinit var rvSideChannels: RecyclerView
    private lateinit var sideCategoryList: View
    private lateinit var rvSideCategories: RecyclerView
    private lateinit var sideCategoryTitle: TextView

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentMpdUrl: String = ""
    private var currentLicenseUrl: String = ""
    private var currentName: String = ""
    private var currentTvgId: String = ""
    
    private var allChannels: List<Channel> = emptyList()
    private var currentCategoryChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentCategory: String = ""
    private var currentIndex: Int = -1
    private var currentSourceIndex: Int = 0
    private var lastPlayedChannel: Channel? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 3
    private var errorRecoveryScheduled = false

    @Volatile
    private var currentRequestHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
    )

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            currentRequestHeaders.forEach { (k, v) -> builder.header(k, v) }
            chain.proceed(builder.build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        initUI()
        parseIntentData()
        
        if (currentMpdUrl.isNotBlank()) {
            loadPreferredSource()
            playChannel(currentMpdUrl, currentLicenseUrl)
        }
        
        loadAllEpgs()
        startClock()
        showInfoBar()
    }

    private fun initUI() {
        playerView = findViewById(R.id.playerView)
        bufferingBar = findViewById(R.id.bufferingBar)
        bufferPercentage = findViewById(R.id.bufferPercentage)
        bottomInfoBar = findViewById(R.id.bottomInfoBar)
        infoChannelNumber = findViewById(R.id.infoChannelNumber)
        infoChannelName = findViewById(R.id.infoChannelName)
        infoSourceName = findViewById(R.id.infoSourceName)
        infoCurrentProgram = findViewById(R.id.infoCurrentProgram)
        infoNextProgram = findViewById(R.id.infoNextProgram)
        infoClock = findViewById(R.id.infoClock)
        playerTopClock = findViewById(R.id.playerTopClock)
        infoBtnFav = findViewById(R.id.infoBtnFav)
        btnSource = findViewById(R.id.btnSource)
        btnMediaSettings = findViewById(R.id.btnMediaSettings)

        sideChannelList = findViewById(R.id.sideChannelList)
        rvSideChannels = findViewById(R.id.rvSideChannels)
        sideCategoryList = findViewById(R.id.sideCategoryList)
        rvSideCategories = findViewById(R.id.rvSideCategories)
        sideCategoryTitle = findViewById(R.id.sideCategoryTitle)

        rvSideChannels.layoutManager = LinearLayoutManager(this)
        rvSideCategories.layoutManager = LinearLayoutManager(this)

        infoBtnFav.setOnClickListener { toggleCurrentFavorite() }
        btnSource.setOnClickListener { showSourceDialog() }
        btnMediaSettings.setOnClickListener { showMediaSettingsDialog() }
    }

    private fun parseIntentData() {
        currentName = intent.getStringExtra("channelName") ?: ""
        currentMpdUrl = intent.getStringExtra("mpdUrl") ?: ""
        currentLicenseUrl = intent.getStringExtra("licenseUrl") ?: ""
        currentTvgId = intent.getStringExtra("channelTvgId") ?: ""
        
        val json = intent.getStringExtra("channelsJson") ?: ""
        if (json.isNotEmpty()) {
            allChannels = MainActivity.gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
            currentCategory = intent.getStringExtra("categoryName") ?: ""
            if (currentCategory.isNotEmpty()) filterChannelsByCategory(currentCategory) else currentCategoryChannels = allChannels
            currentIndex = currentCategoryChannels.indexOfFirst { it.tvgId == currentTvgId || it.name == currentName }
            if (currentIndex >= 0) currentChannel = currentCategoryChannels[currentIndex]
        }
        sideCategoryTitle.text = currentCategory
    }

    private fun filterChannelsByCategory(category: String) {
        currentCategory = category
        currentCategoryChannels = if (category == "FAVORITES") {
            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("favorites_list", "[]")
            val favs: List<String> = MainActivity.gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
            Channel.resolveFavorites(favs, allChannels)
        } else {
            allChannels.filter { it.group.equals(category, true) }
        }
    }

    private fun loadPreferredSource() {
        val channel = currentChannel ?: return
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val prefUrl = prefs.getString("pref_source_${Channel.favoriteKey(channel)}", "")
        if (!prefUrl.isNullOrEmpty()) {
            val idx = channel.sources.indexOfFirst { it.url == prefUrl }
            if (idx >= 0) {
                currentSourceIndex = idx
                currentMpdUrl = channel.sources[idx].url
                currentLicenseUrl = channel.sources[idx].licenseUrl
            }
        }
    }

    private fun savePreferredSource(url: String) {
        val channel = currentChannel ?: return
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("pref_source_${Channel.favoriteKey(channel)}", url).apply()
    }

    private fun startClock() {
        val sdfTop = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val sdfBottom = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val runnable = object : Runnable {
            override fun run() {
                val now = Date()
                playerTopClock.text = sdfTop.format(now)
                infoClock.text = sdfBottom.format(now)
                
                if (player?.playbackState == Player.STATE_BUFFERING) {
                    val pct = player?.bufferedPercentage ?: 0
                    bufferPercentage.text = "$pct%"
                    bufferPercentage.visibility = View.VISIBLE
                } else {
                    bufferPercentage.visibility = View.GONE
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(runnable)
    }

    private fun playChannel(mpdUrl: String, licenseUrl: String, startPos: Long = 0) {
        currentMpdUrl = mpdUrl
        currentLicenseUrl = licenseUrl
        
        player?.release()
        player = null
        
        // Ultra-Fast loading optimization: extremely low buffer requirements for instant startup
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000,  // min buffer (reduced to 2s)
                10000, // max buffer (reduced to 10s)
                250,   // buffer for playback (reduced to 250ms)
                500    // buffer for rebuffering (reduced to 500ms)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()

        playerView.player = player
        
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(mpdUrl)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(currentName).build())

        // Apply per-stream headers from KODIPROP, fall back to generic User-Agent
        val sourceHeaders = currentChannel?.sources?.getOrNull(currentSourceIndex)?.headers
        currentRequestHeaders = if (sourceHeaders != null && sourceHeaders.isNotEmpty()) sourceHeaders else
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")

        val lowerUrl = mpdUrl.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("m3u8") || lowerUrl.contains("hls")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (lowerUrl.contains(".mpd") || lowerUrl.contains("dash") || licenseUrl.isNotBlank()) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (licenseUrl.isNotBlank()) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl)
                    .setMultiSession(true)
                    .setForceDefaultLicenseUri(true)
                    .setPlayClearContentWithoutKey(true)
                    .build()
            )
        }

        player?.setMediaItem(mediaItemBuilder.build())
        
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingBar.visibility = View.GONE
                if (playbackState == Player.STATE_READY) bufferPercentage.visibility = View.GONE
                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                    bufferPercentage.visibility = View.GONE
                    savePreferredSource(currentMpdUrl)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlayerActivity", "Playback Error: ${error.errorCodeName} (${error.errorCode})")
                if (errorRecoveryScheduled) return
                
                val channel = currentChannel
                if (retryCount < 1) {
                    retryCount++
                    errorRecoveryScheduled = true
                    mainHandler.postDelayed({
                        errorRecoveryScheduled = false
                        playChannel(currentMpdUrl, currentLicenseUrl)
                    }, 500)
                } else if (channel != null && currentSourceIndex + 1 < channel.sources.size) {
                    errorRecoveryScheduled = true
                    currentSourceIndex++
                    val nextSource = channel.sources[currentSourceIndex]
                    mainHandler.post {
                        Toast.makeText(this@PlayerActivity, "Source failed. Switching...", Toast.LENGTH_SHORT).show()
                        playChannel(nextSource.url, nextSource.licenseUrl)
                        errorRecoveryScheduled = false
                    }
                } else if (retryCount < maxRetries) {
                    retryCount++
                    val pos = player?.currentPosition ?: 0
                    playChannel(currentMpdUrl, currentLicenseUrl, pos)
                } else {
                    runOnUiThread { Toast.makeText(this@PlayerActivity, "Playback failed", Toast.LENGTH_LONG).show() }
                }
            }
        })

        if (startPos > 0) player?.seekTo(startPos)
        player?.prepare()
        player?.play()
        updateInfoBarUI()
    }

    private fun showInfoBar() {
        bottomInfoBar.visibility = View.VISIBLE
        infoBtnFav.requestFocus()
        mainHandler.removeCallbacks(hideInfoBarRunnable)
        mainHandler.postDelayed(hideInfoBarRunnable, 8000)
    }

    private val hideInfoBarRunnable = Runnable { 
        bottomInfoBar.visibility = View.GONE 
        playerView.requestFocus()
    }

    private fun updateInfoBarUI() {
        infoChannelName.text = currentName
        infoChannelNumber.text = (currentIndex + 1).toString()
        
        val channel = currentChannel
        val playlistName = if (channel != null && currentSourceIndex < channel.sources.size) {
            channel.sources[currentSourceIndex].playlistName
        } else null
        
        val epg = if (channel != null) EpgManager.getEpgForChannel(channel, playlistName) else null
        if (epg != null) {
            val current = epg.getCurrentProgram()
            infoCurrentProgram.text = current?.title ?: "No Program Information"
            infoNextProgram.text = epg.getNextProgram()?.let { "Next: ${it.title}" } ?: ""
        } else {
            infoCurrentProgram.text = "No EPG Data Found"
            infoNextProgram.text = ""
        }
        
        val favorites = getFavoritesList()
        val key = if (channel != null) Channel.favoriteKey(channel) else currentTvgId.ifBlank { currentName }
        infoBtnFav.setImageResource(if (favorites.contains(key)) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
        
        currentChannel?.let { 
            btnSource.visibility = if (it.sources.size > 1) View.VISIBLE else View.GONE 
            if (currentSourceIndex < it.sources.size) {
                infoSourceName.text = "SOURCE: ${it.sources[currentSourceIndex].playlistName}"
                infoSourceName.visibility = View.VISIBLE
            }
        }
    }

    private fun showSourceDialog() {
        val channel = currentChannel ?: return
        val names = channel.sources.mapIndexed { index, source -> (if (index == currentSourceIndex) "▶ " else "") + (source.playlistName.ifBlank { "Source ${index + 1}" }) }.toTypedArray()
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Switch Source")
            .setItems(names) { _, which ->
                currentSourceIndex = which
                val source = channel.sources[which]
                savePreferredSource(source.url)
                playChannel(source.url, source.licenseUrl)
            }
            .show()
    }

    private fun showMediaSettingsDialog() {
        val options = arrayOf("Aspect Ratio", "Audio Tracks", "Video Tracks")
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Media Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAspectRatioDialog()
                    1 -> showTrackSelectionDialog(C.TRACK_TYPE_AUDIO)
                    2 -> showTrackSelectionDialog(C.TRACK_TYPE_VIDEO)
                }
            }
            .show()
    }

    private fun showAspectRatioDialog() {
        val modes = arrayOf("Fit", "Fill", "Zoom")
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Aspect Ratio")
            .setItems(modes) { _, which ->
                playerView.resizeMode = when (which) {
                    0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            }
            .show()
    }

    private fun showTrackSelectionDialog(trackType: Int) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) { Toast.makeText(this, "No tracks available", Toast.LENGTH_SHORT).show(); return }
        
        val trackNames = mutableListOf<String>()
        val trackIndices = mutableListOf<Pair<Int, Int>?>() // Use null for AUTO
        
        // Add AUTO option for Video
        if (trackType == C.TRACK_TYPE_VIDEO) {
            val isAuto = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO).not() && 
                         p.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }
            trackNames.add((if (isAuto) "▶ " else "") + "AUTO (Adaptive)")
            trackIndices.add(null)
        }
        
        groups.forEachIndexed { groupIndex, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                
                val info = StringBuilder()
                if (isSelected) info.append("▶ ")
                
                if (trackType == C.TRACK_TYPE_AUDIO) {
                    val lang = format.language ?: "und"
                    val label = format.label ?: lang
                    info.append(label.uppercase())
                    if (format.bitrate > 0) info.append(" (${format.bitrate / 1000} kbps)")
                    if (format.channelCount > 0) info.append(" [${format.channelCount}ch]")
                } else {
                    if (format.width > 0 && format.height > 0) info.append("${format.width}x${format.height}")
                    if (format.bitrate > 0) info.append(" (${String.format("%.1f", format.bitrate / 1000000f)} Mbps)")
                    if (format.frameRate > 0) info.append(" @${format.frameRate.toInt()}fps")
                }
                
                trackNames.add(info.toString())
                trackIndices.add(groupIndex to i)
            }
        }
        
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(if (trackType == C.TRACK_TYPE_AUDIO) "Select Audio" else "Select Video Quality")
            .setItems(trackNames.toTypedArray()) { _, which ->
                val selection = trackIndices[which]
                if (selection == null) {
                    // Reset to AUTO: clear overrides for this track type
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(trackType)
                        .build()
                } else {
                    val (gIdx, tIdx) = selection
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(groups[gIdx].mediaTrackGroup, tIdx))
                        .build()
                }
            }
            .show()
    }

    private fun loadAllEpgs() {
        val urls = intent.getStringArrayExtra("epgUrls") ?: return
        urls.forEach { url ->
            EpgParser.loadEpg(this, url, { data ->
                EpgManager.updateData(url, data)
                runOnUiThread { updateInfoBarUI() }
            }, {})
        }
    }

    private fun nextChannel() {
        if (currentCategoryChannels.isEmpty()) return
        currentIndex = (currentIndex + 1) % currentCategoryChannels.size
        switchChannel(currentCategoryChannels[currentIndex])
    }

    private fun prevChannel() {
        if (currentCategoryChannels.isEmpty()) return
        currentIndex = if (currentIndex <= 0) currentCategoryChannels.size - 1 else currentIndex - 1
        switchChannel(currentCategoryChannels[currentIndex])
    }

    private fun switchChannel(channel: Channel) {
        if (currentChannel != null && currentChannel != channel) lastPlayedChannel = currentChannel
        currentChannel = channel
        currentName = channel.name
        currentTvgId = channel.tvgId
        
        // Update UI INSTANTLY so user sees what they are switching to
        updateInfoBarUI()
        showInfoBar()
        
        currentSourceIndex = 0
        if (channel.sources.isNotEmpty()) {
            currentMpdUrl = channel.sources[0].url
            currentLicenseUrl = channel.sources[0].licenseUrl
        }
        
        loadPreferredSource()
        playChannel(currentMpdUrl, currentLicenseUrl)
    }

    private fun switchToLastChannel() {
        lastPlayedChannel?.let { switchChannel(it); Toast.makeText(this, "Back to ${it.name}", Toast.LENGTH_SHORT).show() }
    }

    private fun showSideChannelList() {
        sideChannelList.visibility = View.VISIBLE
        sideCategoryList.visibility = View.GONE
        rvSideChannels.adapter = SideChannelAdapter()
        mainHandler.postDelayed({
            rvSideChannels.scrollToPosition(currentIndex)
            mainHandler.postDelayed({ rvSideChannels.findViewHolderForAdapterPosition(currentIndex)?.itemView?.requestFocus() ?: rvSideChannels.requestFocus() }, 100)
        }, 100)
    }

    private fun showSideCategoryList() {
        sideCategoryList.visibility = View.VISIBLE
        sideChannelList.visibility = View.GONE
        if (categories.isEmpty()) {
            categories = allChannels.map { it.group.ifBlank { "OTHER" } }.distinct().sorted()
            if (getFavoritesList().isNotEmpty()) categories = listOf("FAVORITES") + categories
        }
        rvSideCategories.adapter = SideCategoryAdapter()
        rvSideCategories.post { rvSideCategories.requestFocus() }
    }

    private fun getFavoritesList(): MutableList<String> {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("favorites_list", "[]")
        return try { MainActivity.gson.fromJson(json, object : TypeToken<MutableList<String>>() {}.type) } catch (e: Exception) { mutableListOf() }
    }

    private fun toggleCurrentFavorite() {
        val channel = currentChannel ?: return
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val favorites = getFavoritesList()
        val key = Channel.favoriteKey(channel)
        val oldKey = channel.tvgId.ifBlank { channel.name }
        if (oldKey != key) favorites.remove(oldKey)
        if (favorites.contains(key)) favorites.remove(key) else favorites.add(key)
        prefs.edit().putString("favorites_list", MainActivity.gson.toJson(favorites)).apply()
        updateInfoBarUI()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 1. Check side panels FIRST to allow navigation and selection in lists
        if (sideChannelList.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) { 
                sideChannelList.visibility = View.GONE
                playerView.requestFocus()
                return true 
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { 
                sideChannelList.visibility = View.GONE
                showSideCategoryList()
                return true 
            }
            // Allow UP/DOWN/OK to reach the RecyclerView
            return super.onKeyDown(keyCode, event)
        }
        
        if (sideCategoryList.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) { 
                sideCategoryList.visibility = View.GONE
                playerView.requestFocus()
                return true 
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { 
                sideCategoryList.visibility = View.GONE
                showSideChannelList()
                return true 
            }
            // Allow UP/DOWN/OK to reach the RecyclerView
            return super.onKeyDown(keyCode, event)
        }
        
        // 2. Handle global channel switching (Universal UP/DOWN) when panels are closed
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            nextChannel()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            prevChannel()
            return true
        }

        // 3. Handle Info Bar navigation
        if (bottomInfoBar.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    mainHandler.removeCallbacks(hideInfoBarRunnable)
                    mainHandler.postDelayed(hideInfoBarRunnable, 8000)
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    bottomInfoBar.visibility = View.GONE
                    playerView.requestFocus()
                    return true
                }
            }
        } else {
            // Shortcuts when everything is hidden
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showSideChannelList(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { switchToLastChannel(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showInfoBar(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { player?.stop(); finish(); return true }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private inner class SideChannelAdapter : RecyclerView.Adapter<SideChannelAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_side_channel, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return
            val ch = currentCategoryChannels[currentPos]
            
            holder.tvName.text = ch.name
            holder.tvCount.visibility = View.GONE
            holder.tvName.setTextColor(if (currentPos == currentIndex) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt())
            
            holder.itemView.setOnFocusChangeListener { view, hasFocus -> 
                if (hasFocus) view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start() 
                else view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start() 
            }
            
            holder.itemView.setOnClickListener { 
                currentIndex = currentPos
                switchChannel(ch)
                sideChannelList.visibility = View.GONE
                playerView.requestFocus() 
            }
        }
        override fun getItemCount() = currentCategoryChannels.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { 
            val tvName: TextView = v.findViewById(R.id.tvChannelName)
            val tvCount: TextView = v.findViewById(R.id.tvCategoryCount)
        }
    }

    private inner class SideCategoryAdapter : RecyclerView.Adapter<SideCategoryAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_side_channel, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val cat = categories[position]
            holder.tvName.text = cat
            holder.tvCount.visibility = View.GONE
            holder.tvName.setTextColor(if (cat == currentCategory) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt())
            
            holder.itemView.setOnFocusChangeListener { view, hasFocus -> 
                if (hasFocus) view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start() 
                else view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start() 
            }
            
            holder.itemView.setOnClickListener { 
                filterChannelsByCategory(cat)
                showSideChannelList() 
            }
        }
        override fun getItemCount() = categories.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { 
            val tvName: TextView = v.findViewById(R.id.tvChannelName)
            val tvCount: TextView = v.findViewById(R.id.tvCategoryCount)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        player?.stop()
        player?.release()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
