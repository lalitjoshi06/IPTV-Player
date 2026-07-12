package com.mpdplayer

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var btnChannels: Button
    private lateinit var btnMediaSettings: ImageView

    private lateinit var sideChannelList: View
    private lateinit var rvSideChannels: RecyclerView
    private lateinit var sideCategoryList: View
    private lateinit var rvSideCategories: RecyclerView
    private lateinit var sideCategoryTitle: TextView

    private lateinit var gestureDetector: GestureDetector
    private lateinit var errorOverlay: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentMpdUrl: String = ""
    private var currentLicenseUrl: String = ""
    private var currentDrmType: String = ""
    private var currentName: String = ""
    private var currentTvgId: String = ""
    
    private var allChannels: List<Channel> = emptyList()
    private var currentCategoryChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentCategory: String = ""
    private var currentIndex: Int = -1
    private var currentSourceIndex: Int = 0
    private var lastPlayedChannel: Channel? = null
    private var sideFocusedPosition: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 3
    private var errorRecoveryScheduled = false

    @Volatile
    private var lastLicenseError: String? = null

    @Volatile
    private var currentRequestHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    )

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val builder = request.newBuilder()
            
            // Apply the current request headers. These come exclusively from the
            // playlist (KODIPROP stream_headers / URL-pipe headers) and the
            // default User-Agent set in playChannel.
            currentRequestHeaders.forEach { (k, v) ->
                builder.header(k, v)
            }
            
            // JioTV Heuristics: Only apply if not provided in playlist
            if (url.contains("jiotv") || url.contains("jio.com")) {
                if (!currentRequestHeaders.containsKey("Origin")) builder.header("Origin", "https://www.jiotv.com")
                if (!currentRequestHeaders.containsKey("Referer")) builder.header("Referer", "https://www.jiotv.com/")
                // Jio HLS often requires a specific Mobile User-Agent
                val currentUA = currentRequestHeaders["User-Agent"] ?: ""
                if (currentUA.contains("Windows") || currentUA.isEmpty()) {
                    builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36")
                }
                builder.header("X-Requested-With", "com.jio.jiotv")
            }

            // Force headers for Akamai/TataPlay segments (if not in playlist)
            if (url.contains("akamaized.net") || url.contains("bpaicatchup") || url.contains("tataplay") || url.contains("workers.dev")) {
                if (!currentRequestHeaders.containsKey("Origin")) builder.header("Origin", "https://watch.tataplay.com")
                if (!currentRequestHeaders.containsKey("Referer")) builder.header("Referer", "https://watch.tataplay.com/")
            }

            val finalRequest = builder.build()
            val response = chain.proceed(finalRequest)
            if (!response.isSuccessful) {
                Log.e("PlayerActivity", "HTTP Error: ${response.code} for $url")
                if (url.contains("127.0.0.1") || url.contains("localhost")) {
                    Log.e("PlayerActivity", "WARNING: Request to localhost detected. This will fail on real devices unless a local proxy is running.")
                }
                val sb = StringBuilder("Headers sent: ")
                finalRequest.headers.names().forEach { name ->
                    if (!name.contains("Token", true) && !name.contains("Auth", true) && !name.contains("Cookie", true)) {
                        sb.append("$name=${finalRequest.header(name)}, ")
                    }
                }
                Log.e("PlayerActivity", sb.toString())
            }
            response
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
            playChannel(currentMpdUrl, currentLicenseUrl, currentDrmType)
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
        btnChannels = findViewById(R.id.btnChannels)
        btnMediaSettings = findViewById(R.id.btnMediaSettings)

        sideChannelList = findViewById(R.id.sideChannelList)
        rvSideChannels = findViewById(R.id.rvSideChannels)
        sideCategoryList = findViewById(R.id.sideCategoryList)
        rvSideCategories = findViewById(R.id.rvSideCategories)
        sideCategoryTitle = findViewById(R.id.sideCategoryTitle)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)

        rvSideChannels.layoutManager = LinearLayoutManager(this)
        rvSideCategories.layoutManager = LinearLayoutManager(this)

        // Unified Remote OK and Phone Tap support:
        playerView.setOnClickListener {
            if (sideChannelList.visibility == View.VISIBLE || sideCategoryList.visibility == View.VISIBLE) {
                sideChannelList.visibility = View.GONE
                sideCategoryList.visibility = View.GONE
                playerView.requestFocus()
            } else if (bottomInfoBar.visibility == View.VISIBLE) {
                bottomInfoBar.visibility = View.GONE
                playerView.requestFocus()
            } else {
                showInfoBar()
            }
        }

        // Phone / touch support: tapping the video shows/hides controls.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.performClick()
                return true
            }
        })
        playerView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        // Tapping the panel title on a phone opens the category list (no D-pad).
        sideCategoryTitle.setOnClickListener { showSideCategoryList() }

        infoBtnFav.setOnClickListener { toggleCurrentFavorite() }
        btnSource.setOnClickListener { showSourceDialog() }
        btnChannels.setOnClickListener { showSideChannelList() }
        btnMediaSettings.setOnClickListener { showMediaSettingsDialog() }

        // Make selection background in downbar more visible
        val barButtons = listOf(infoBtnFav, btnSource, btnChannels, btnMediaSettings)
        barButtons.forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                    // Higher opacity (75%) white for the selection so it pops 
                    // against the 50% transparent bar.
                    v.setBackgroundColor(0xC0FFFFFF.toInt()) 
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    v.setBackgroundColor(0x00000000.toInt())
                }
            }
        }
    }

    private fun parseIntentData() {
        currentName = intent.getStringExtra("channelName") ?: ""
        currentMpdUrl = intent.getStringExtra("mpdUrl") ?: ""
        currentLicenseUrl = intent.getStringExtra("licenseUrl") ?: ""
        currentDrmType = intent.getStringExtra("drmType") ?: ""
        currentTvgId = intent.getStringExtra("channelTvgId") ?: ""
        
        val json = intent.getStringExtra("channelsJson") ?: ""
        if (json.isNotEmpty()) {
            allChannels = MainActivity.gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
            currentCategory = intent.getStringExtra("categoryName") ?: ""
            if (currentCategory.isNotEmpty()) filterChannelsByCategory(currentCategory) else currentCategoryChannels = allChannels
            // Resolve the played channel by its actual URL first (robust against
            // blank tvgId/name matches that would otherwise snap index to 0).
            currentIndex = currentCategoryChannels.indexOfFirst { it.mpdUrl == currentMpdUrl }
            if (currentIndex < 0) currentIndex = currentCategoryChannels.indexOfFirst { ch ->
                (currentTvgId.isNotBlank() && ch.tvgId == currentTvgId) ||
                (currentName.isNotBlank() && ch.name == currentName)
            }
            if (currentIndex < 0) currentIndex = 0
            if (currentIndex in currentCategoryChannels.indices) currentChannel = currentCategoryChannels[currentIndex]
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
        } else if (category.equals("OTHERS", true) || category.equals("OTHER", true)) {
            // Channels with a blank group are shown under the "OTHERS" pseudo-category.
            val others = allChannels.filter { it.group.isBlank() }
            if (others.isNotEmpty()) others else allChannels
        } else {
            val filtered = allChannels.filter { it.group.equals(category, true) }
            // Fallback: if the group name doesn't match exactly (e.g. whitespace/case
            // differences), keep the full list so the selected channel is still present.
            if (filtered.isNotEmpty()) filtered else allChannels
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
                currentDrmType = channel.sources[idx].drmType
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

    private fun playChannel(mpdUrl: String, licenseUrl: String, drmType: String = currentDrmType, startPos: Long = 0) {
        currentMpdUrl = mpdUrl
        currentLicenseUrl = licenseUrl
        currentDrmType = drmType
        lastLicenseError = null

        // Reject protocols ExoPlayer core cannot play (RTSP, RTMP, UDP/RTP
        // multicast, etc.) up front so we show a clear message instead of
        // looping on a generic failure.
        if (isUnsupportedProtocol(currentMpdUrl)) {
            showErrorOverlay(
                "Unsupported Stream",
                "This source uses a protocol that is not supported by the player:\n$currentMpdUrl\n\nSupported: HLS (.m3u8), DASH (.mpd), Smooth Streaming, and progressive files."
            )
            return
        }

        // Per-stream request headers (used by the OkHttp interceptor for media
        // segments). DRM license request headers are carried on the media item's
        // DrmConfiguration instead.
        val sourceHeaders = currentChannel?.sources?.getOrNull(currentSourceIndex)?.headers
        val headers = sourceHeaders?.toMutableMap() ?: mutableMapOf()
        
        // Use standard Chrome Windows User-Agent by default if not in playlist
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        }

        // Origin/Referer (and any other headers) come from the playlist's
        // per-source headers (KODIPROP stream_headers). Nothing provider-specific
        // is hard coded here, so a bare global player honors only what the
        // playlist declares.

        currentRequestHeaders = headers

        val drmConfiguration = if (licenseUrl.isNotBlank()) {
            val drmUuid = when (drmType) {
                "playready" -> C.PLAYREADY_UUID
                "clearkey" -> C.CLEARKEY_UUID
                else -> C.WIDEVINE_UUID
            }
            val effectiveLicenseUri = resolveLicenseUri(drmType, licenseUrl)
            MediaItem.DrmConfiguration.Builder(drmUuid)
                .setLicenseUri(effectiveLicenseUri)
                .setLicenseRequestHeaders(headers)
                .setMultiSession(true)
                .setForceDefaultLicenseUri(true)
                .setPlayClearContentWithoutKey(true)
                .build()
        } else null

        // Reuse a single ExoPlayer instance across channel switches so pressing a
        // channel starts (near) instantly — we no longer rebuild renderers, audio
        // focus and the DRM provider on every switch. The DRM provider is
        // stateless and reads its config from the media item being played.
        ensurePlayer()
        player?.stop()
        
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(currentMpdUrl)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(currentName).build())

        // Determine container type. The playlist may declare it explicitly via
        // KODIPROP manifest_type (e.g. MPD streams served from URLs without a
        // ".mpd"/"dash" marker). That takes precedence; otherwise we infer from
        // the URL. DRM (licenseUrl) must NOT influence container detection.
        val sourceManifestType = currentChannel?.sources?.getOrNull(currentSourceIndex)?.manifestType?.lowercase()
        val resolvedMime = when (sourceManifestType) {
            "mpd", "dash" -> MimeTypes.APPLICATION_MPD
            "hls", "m3u8" -> MimeTypes.APPLICATION_M3U8
            "smoothstreaming", "smooth", "ism" -> MimeTypes.APPLICATION_SS
            else -> resolveStreamMimeType(currentMpdUrl)
        }
        if (resolvedMime != null) {
            mediaItemBuilder.setMimeType(resolvedMime)
        }

        if (drmConfiguration != null) {
            mediaItemBuilder.setDrmConfiguration(drmConfiguration)
        }

        player?.setMediaItem(mediaItemBuilder.build())

        if (startPos > 0) player?.seekTo(startPos)
        player?.prepare()
        player?.play()
        updateInfoBarUI()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            bufferingBar.visibility = View.GONE
            if (playbackState == Player.STATE_READY) bufferPercentage.visibility = View.GONE
            if (playbackState == Player.STATE_READY) {
                retryCount = 0
                bufferPercentage.visibility = View.GONE
                hideErrorOverlay()
                savePreferredSource(currentMpdUrl)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PlayerActivity", "Playback Error: ${error.errorCodeName} (${error.errorCode})")
            if (errorRecoveryScheduled) return

            showErrorForPlaybackException(error)

            val channel = currentChannel
            if (retryCount < 1) {
                retryCount++
                errorRecoveryScheduled = true
                mainHandler.postDelayed({
                    errorRecoveryScheduled = false
                    playChannel(currentMpdUrl, currentLicenseUrl, currentDrmType)
                }, 500)
            } else if (channel != null && currentSourceIndex + 1 < channel.sources.size) {
                errorRecoveryScheduled = true
                currentSourceIndex++
                val nextSource = channel.sources[currentSourceIndex]
                mainHandler.post {
                    Toast.makeText(this@PlayerActivity, "Source failed. Switching...", Toast.LENGTH_SHORT).show()
                    playChannel(nextSource.url, nextSource.licenseUrl, nextSource.drmType)
                    errorRecoveryScheduled = false
                }
            } else if (retryCount < maxRetries) {
                retryCount++
                val pos = player?.currentPosition ?: 0
                playChannel(currentMpdUrl, currentLicenseUrl, currentDrmType, pos)
            } else {
                runOnUiThread { Toast.makeText(this@PlayerActivity, "Playback failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * Build (once) and reuse a single ExoPlayer instance across channel switches.
     * The DRM session manager provider is stateless: it derives the DRM config
     * from the media item being played, so switching between DRM and non-DRM
     * streams works without rebuilding the player. Faster startup buffer values
     * keep channel changes and key-rotation recovery quick.
     */
    private fun ensurePlayer() {
        if (player != null) return
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,  // min buffer
                40000,  // max buffer
                700,    // buffer for playback (fast, near-instant start)
                1200    // buffer for rebuffering (fast key-rotation recovery)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setDrmSessionManagerProvider { mediaItem -> buildDrmSessionManager(mediaItem) }
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()

        playerView.player = player
        player?.addListener(playerListener)
    }

    private fun buildDrmSessionManager(mediaItem: MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager {
        val drmConfig = mediaItem.localConfiguration?.drmConfiguration
            ?: return androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED

        val headers = drmConfig.licenseRequestHeaders
        val callback = if (drmConfig.scheme == C.CLEARKEY_UUID) {
            object : androidx.media3.exoplayer.drm.MediaDrmCallback {
                val defaultCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(drmConfig.licenseUri.toString(), dataSourceFactory)

                override fun executeProvisionRequest(uuid: UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
                    return defaultCallback.executeProvisionRequest(uuid, request)
                }

                override fun executeKeyRequest(uuid: UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
                    headers.forEach { (k, v) -> defaultCallback.setKeyRequestProperty(k, v) }
                    val response = try {
                        defaultCallback.executeKeyRequest(uuid, request)
                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "License request failed for ${drmConfig.licenseUri}", e)
                        lastLicenseError = e.message ?: e.javaClass.simpleName
                        throw e
                    }
                    val responseString = String(response.data).replace("\u0000", "").trim()
                    Log.d("PlayerActivity", "ClearKey Raw Response: $responseString")

                    if (responseString.startsWith("{") || responseString.contains("\"keys\"")) {
                        Log.d("PlayerActivity", "ClearKey: Response is valid JSON, passing through")
                        val cleanJson = responseString.toByteArray(Charsets.UTF_8)
                        return androidx.media3.exoplayer.drm.MediaDrmCallback.Response(cleanJson)
                    }

                    return try {
                        val delimiters = charArrayOf(':', ',', '|')
                        val parts = responseString.split(*delimiters).filter { it.isNotBlank() }
                        if (parts.size >= 2) {
                            val kid = parts[parts.size - 2].trim().replace("\"", "")
                            val k = parts[parts.size - 1].trim().replace("\"", "")
                            fun isHex(s: String) = s.matches(Regex("^[0-9a-fA-F]+$"))
                            val finalKid = if (isHex(kid)) hexToBase64Url(kid) else kid
                            val finalKey = if (isHex(k)) hexToBase64Url(k) else k
                            val json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$finalKey\",\"kid\":\"$finalKid\"}],\"type\":\"temporary\"}"
                            Log.d("PlayerActivity", "ClearKey Transformed JSON: $json")
                            androidx.media3.exoplayer.drm.MediaDrmCallback.Response(json.toByteArray())
                        } else {
                            Log.e("PlayerActivity", "Could not parse ClearKey response: $responseString")
                            response
                        }
                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "Error transforming ClearKey response", e)
                        response
                    }
                }
            }
        } else {
            val inner = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(drmConfig.licenseUri.toString(), dataSourceFactory)
            headers.forEach { (k, v) -> inner.setKeyRequestProperty(k, v) }
            object : androidx.media3.exoplayer.drm.MediaDrmCallback {
                override fun executeProvisionRequest(uuid: UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
                    return inner.executeProvisionRequest(uuid, request)
                }
                override fun executeKeyRequest(uuid: UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
                    return try {
                        inner.executeKeyRequest(uuid, request)
                    } catch (e: Exception) {
                        lastLicenseError = e.message ?: e.javaClass.simpleName
                        throw e
                    }
                }
            }
        }

        return androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(drmConfig.scheme, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)
    }

    /**
     * Show a friendly, user-visible error overlay on the player screen.
     */
    private fun showErrorOverlay(title: String, message: String) {
        errorTitle.text = title
        errorMessage.text = message
        errorOverlay.visibility = View.VISIBLE
    }

    private fun hideErrorOverlay() {
        errorOverlay.visibility = View.GONE
    }

    /**
     * Protocols that the ExoPlayer core library cannot play. These require
     * either a separate (often deprecated) extension or a local proxy, so we
     * reject them explicitly with a clear message.
     */
    private fun isUnsupportedProtocol(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("rtsp") ||
                lower.startsWith("rtmp") ||
                lower.startsWith("rtp://") ||
                lower.startsWith("udp://") ||
                lower.startsWith("igmp://") ||
                lower.startsWith("rtp@") ||
                lower.startsWith("udp@")
    }

    /**
     * Resolve the container MIME type from the stream URL.
     * Returns null when the type cannot be inferred, in which case ExoPlayer's
     * default sniffing is used for progressive/unknown containers.
     * DRM (licenseUrl) is deliberately NOT considered here — a stream's
     * container is independent of whether it is encrypted.
     */
    private fun resolveStreamMimeType(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.endsWith("m3u8") || lower.contains("hls") ->
                MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") || lower.contains("dash") ->
                MimeTypes.APPLICATION_MPD
            lower.contains(".ism") || lower.contains("smoothstreaming") || lower.contains("/manifest") ->
                MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    /**
     * Map a Media3 PlaybackException to a human-readable message and show it.
     * Distinguishes unreachable stream hosts from unreachable license servers.
     */
    private fun showErrorForPlaybackException(error: PlaybackException) {
        // During key rotation the player transiently fails to acquire a new
        // license. This is expected and recovers on the automatic retry, so we
        // must NOT show the error overlay — just let the buffering/percentage
        // indicator reflect the (re)loading state as usual.
        if (error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED) {
            return
        }

        val host = try {
            android.net.Uri.parse(currentMpdUrl).host ?: currentMpdUrl
        } catch (e: Exception) { currentMpdUrl }

        val licenseHost = if (currentLicenseUrl.isNotBlank()) {
            try {
                android.net.Uri.parse(currentLicenseUrl).host ?: currentLicenseUrl
            } catch (e: Exception) { currentLicenseUrl }
        } else null

        val (title, message) = when {
            // License / DRM acquisition failures
            error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
            (lastLicenseError != null && currentLicenseUrl.isNotBlank()) -> {
                val detail = lastLicenseError ?: error.message ?: error.errorCodeName
                val lh = licenseHost ?: currentLicenseUrl
                "License Server Unreachable" to
                    "Could not reach the license server ($lh).\n$detail"
            }
            // Host / network unreachable for the stream
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                "Cannot Reach Stream" to
                    "The stream host ($host) is unreachable.\nCheck the playlist URL or your connection."
            }
            // Other IO errors (404, 403, etc.)
            error.errorCode >= 2000 && error.errorCode < 3000 -> {
                val subType = error.cause?.message ?: error.message ?: error.errorCodeName
                "Stream Error" to
                    "Failed to load the stream from ($host).\n$subType"
            }
            else -> {
                "Playback Error" to
                    "${error.errorCodeName}${lastLicenseError?.let { " (license: $it)" } ?: ""}"
            }
        }

        showErrorOverlay(title, message)
    }

    /**
     * Resolve the license URI for the given DRM system.
     * For ClearKey, the KODIPROP license_key is typically the raw JSON key set
     * or a KID:KEY hex pair, which Media3 expects wrapped in a data: URI.
     */
    private fun resolveLicenseUri(drmType: String, licenseUrl: String): String {
        return if (drmType == "clearkey" && !licenseUrl.startsWith("data:") && !licenseUrl.startsWith("http")) {
            buildClearKeyUri(licenseUrl)
        } else {
            licenseUrl
        }
    }

    private fun buildClearKeyUri(licenseKey: String): String {
        val json = if (licenseKey.trim().startsWith("{")) {
            licenseKey
        } else {
            try {
                val keysArray = JSONArray()
                licenseKey.split(",").forEach { pair ->
                    val parts = pair.split(":")
                    if (parts.size == 2) {
                        val keyObj = JSONObject()
                        keyObj.put("kty", "oct")
                        keyObj.put("kid", hexToBase64Url(parts[0].trim()))
                        keyObj.put("k", hexToBase64Url(parts[1].trim()))
                        keysArray.put(keyObj)
                    }
                }
                val result = JSONObject()
                result.put("keys", keysArray)
                result.put("type", "temporary")
                result.toString()
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Failed to build ClearKey JSON", e)
                licenseKey
            }
        }
        val encoded = android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:application/json;base64,$encoded"
    }

    private fun hexToBase64Url(hex: String): String {
        val cleanHex = hex.replace(" ", "").replace("-", "")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in 0 until cleanHex.length step 2) {
            bytes[i / 2] = cleanHex.substring(i, i + 2).toInt(16).toByte()
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun showInfoBar() {
        bottomInfoBar.visibility = View.VISIBLE
        btnChannels.requestFocus()
        mainHandler.removeCallbacks(hideInfoBarRunnable)
        mainHandler.postDelayed(hideInfoBarRunnable, 4000)
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
                playChannel(source.url, source.licenseUrl, source.drmType)
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

        // Keep the side-list selection in sync with the channel that is really
        // playing, so reopening the side list highlights the correct channel
        // (e.g. after switching via LEFT / switch-to-last). Callers that already
        // set currentIndex (next/prev, list click) are left unchanged here.
        val idx = currentCategoryChannels.indexOfFirst { it.mpdUrl == channel.mpdUrl }
        if (idx >= 0) currentIndex = idx

        // Update UI INSTANTLY so user sees what they are switching to
        updateInfoBarUI()
        showInfoBar()

        currentSourceIndex = 0
        if (channel.sources.isNotEmpty()) {
            currentMpdUrl = channel.sources[0].url
            currentLicenseUrl = channel.sources[0].licenseUrl
            currentDrmType = channel.sources[0].drmType
        }

        loadPreferredSource()
        playChannel(currentMpdUrl, currentLicenseUrl, currentDrmType)
    }

    private fun switchToLastChannel() {
        lastPlayedChannel?.let { switchChannel(it); Toast.makeText(this, "Back to ${it.name}", Toast.LENGTH_SHORT).show() }
    }

    private fun showSideChannelList() {
        sideChannelList.visibility = View.VISIBLE
        sideCategoryList.visibility = View.GONE
        // Make sure focus goes to an item, never to the RecyclerView container itself.
        rvSideChannels.isFocusable = false
        rvSideChannels.adapter = SideChannelAdapter()
        rvSideChannels.post {
            if (currentCategoryChannels.isEmpty()) return@post
            val idx = currentIndex.coerceIn(0, currentCategoryChannels.size - 1)
            sideFocusedPosition = idx
            rvSideChannels.scrollToPosition(idx)
            // Focus the actual item (retry once laid out). If it can't be focused,
            // the first item receives focus because the container is not focusable.
            rvSideChannels.post {
                val vh = rvSideChannels.findViewHolderForAdapterPosition(idx)
                if (vh != null) vh.itemView.requestFocus() else rvSideChannels.getChildAt(0)?.requestFocus()
            }
        }
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
            // Allow UP/DOWN/OK to reach the RecyclerView/Items for navigation/selection
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
                    mainHandler.postDelayed(hideInfoBarRunnable, 4000)
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
            val ch = currentCategoryChannels[position]

            holder.tvName.text = ch.name
            holder.tvCount.visibility = View.GONE
            holder.tvName.setTextColor(if (position == currentIndex) 0xFF4CAF50.toInt() else 0xFFFFFFFF.toInt())

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    sideFocusedPosition = holder.bindingAdapterPosition
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }

            holder.itemView.setOnClickListener {
                currentIndex = holder.bindingAdapterPosition
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
