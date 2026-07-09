package com.mpdplayer

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL

object PlaylistParser {

    private fun mapDrmType(raw: String): String = when {
        raw.contains("widevine", true) -> "widevine"
        raw.contains("playready", true) -> "playready"
        raw.contains("clearkey", true) || raw.contains("w3c", true) -> "clearkey"
        else -> ""
    }

    /**
     * Resolve the DRM system + license URL from KODIPROP values.
     * Supports pipe-separated multi-system entries (license_type/license_key),
     * preferring Widevine > PlayReady > ClearKey.
     */
    private fun resolveDrm(typeRaw: String, keyRaw: String): Pair<String, String> {
        if (typeRaw.isBlank()) {
            // If type is missing but key looks like ClearKey (KID:KEY), assume ClearKey
            return if (keyRaw.contains(":") && !keyRaw.startsWith("http")) {
                "clearkey" to keyRaw
            } else {
                "" to keyRaw
            }
        }

        if (typeRaw.contains("|")) {
            val types = typeRaw.split("|")
            val keys = keyRaw.split("|")
            val preferred = listOf("widevine", "playready", "clearkey")
            for (pref in preferred) {
                types.forEachIndexed { i, t ->
                    if (mapDrmType(t) == pref && i < keys.size) {
                        return mapDrmType(t) to keys[i].trim()
                    }
                }
            }
        }
        return mapDrmType(typeRaw) to keyRaw
    }


    data class PlaylistResult(
        val channels: List<Channel>,
        val epgUrls: List<String>
    )

    fun parse(context: Context, playlistUrl: String, playlistName: String, callback: (PlaylistResult) -> Unit, error: (String) -> Unit) {
        Thread {
            try {
                val channels = mutableListOf<Channel>()
                val inputStream: InputStream = when {
                    playlistUrl.startsWith("http") -> URL(playlistUrl).openStream()
                    playlistUrl.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(playlistUrl))!!
                    else -> File(playlistUrl).inputStream()
                }
                
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

                var currentName = ""
                var currentLogo = ""
                var currentGroup = ""
                var currentTvgId = ""
                var currentLicenseUrl = ""
                var currentDrmTypeRaw = ""
                val currentHeaders = mutableMapOf<String, String>()
                val currentAltIds = mutableSetOf<String>()
                val epgUrls = mutableListOf<String>()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue

                    when {
                        l.startsWith("#EXTM3U", ignoreCase = true) -> {
                            val regex = Regex("(x-tvg-url|url-tvg)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                            val urlsRaw = regex.find(l)?.groupValues?.getOrNull(2) ?: ""
                            if (urlsRaw.isNotEmpty()) {
                                val split = urlsRaw.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
                                epgUrls.addAll(split)
                            }
                        }

                        l.startsWith("#EXTINF:") -> {
                            val tvgId = Regex("tvg-id=\"([^\"]*)\"").find(l)?.groupValues?.getOrNull(1) ?: ""
                            val logo = Regex("tvg-logo=\"([^\"]*)\"").find(l)?.groupValues?.getOrNull(1) ?: ""
                            val group = Regex("group-title=\"([^\"]*)\"").find(l)?.groupValues?.getOrNull(1) ?: ""
                            val name = l.substringAfterLast(",").trim()

                            currentTvgId = tvgId
                            currentLogo = logo
                            currentGroup = group
                            currentName = name
                            currentAltIds.clear()
                            if (tvgId.isNotBlank()) currentAltIds.add(tvgId.trim())
                        }

                        l.startsWith("#KODIPROP:") && l.contains("license_key") -> {
                            currentLicenseUrl = l.substringAfter("=").trim()
                        }

                        l.startsWith("#KODIPROP:") && l.contains("license_type") -> {
                            currentDrmTypeRaw = l.substringAfter("=").trim()
                        }

                        l.startsWith("#KODIPROP:") && l.contains("stream_headers") -> {
                            val raw = l.substringAfter("=").trim()
                            raw.split("|").forEach { pair ->
                                val eq = pair.indexOf("=")
                                if (eq > 0) currentHeaders[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                            }
                        }

                        l.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                            val opt = l.substringAfter(":").trim()
                            if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                                currentHeaders["User-Agent"] = opt.substringAfter("=").trim()
                            } else if (opt.startsWith("http-referrer=", ignoreCase = true)) {
                                currentHeaders["Referer"] = opt.substringAfter("=").trim()
                            }
                        }

                        !l.startsWith("#") && l.isNotBlank() -> {
                            var streamUrl = l.trim()
                            
                            // Extract headers from URL if present (e.g., http://url|Header1=Val1&Header2=Val2)
                            if (streamUrl.contains("|")) {
                                val parts = streamUrl.split("|")
                                streamUrl = parts[0].trim()
                                if (parts.size > 1) {
                                    parts[1].split("&").forEach { pair ->
                                        val eq = pair.indexOf("=")
                                        if (eq > 0) {
                                            currentHeaders[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                                        }
                                    }
                                }
                            }

                            if (currentName.isNotBlank() && streamUrl.isNotBlank()) {
                                val (drmType, licenseUrl) = resolveDrm(currentDrmTypeRaw, currentLicenseUrl)
                                val channel = Channel(
                                    name = currentName,
                                    logoUrl = currentLogo,
                                    group = currentGroup,
                                    tvgId = currentTvgId,
                                    sources = mutableListOf(StreamSource(streamUrl, licenseUrl, playlistName, currentHeaders.toMap(), drmType))
                                )
                                channel.altIds.addAll(currentAltIds)
                                channels.add(channel)
                            }
                            currentName = ""
                            currentLicenseUrl = ""
                            currentDrmTypeRaw = ""
                            currentHeaders.clear()
                            currentAltIds.clear()
                        }
                    }
                }
                reader.close()
                callback(PlaylistResult(channels, epgUrls))
            } catch (e: Exception) {
                error(e.message ?: "Parse failed")
            }
        }.start()
    }
}
