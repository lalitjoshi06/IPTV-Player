package com.mpdplayer

import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object EpgParser {

    // Single background thread: avoids CPU saturation (parallel XML parsing) on weak TV boxes.
    private val executor = Executors.newSingleThreadExecutor()

    // 6 hour cache window for both raw XML and parsed EPG.
    private const val CACHE_TTL_MS = 6 * 3600 * 1000L

    // Unit separator (0x1F) used as the field delimiter in the parsed cache.
    private const val FS = '\u241f'

    private data class EpgChannelMap(
        val id: String,
        val displayNames: MutableList<String>
    )

    fun loadEpg(
        context: android.content.Context,
        epgUrl: String,
        onComplete: (Map<String, EpgData>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (epgUrl.isBlank()) return

        executor.execute {
            try {
                val hash = epgUrl.hashCode()
                val parsedCache = File(context.cacheDir, "epg_parsed_$hash.dat")
                val xmlCache = File(context.cacheDir, "epg_cache_$hash.xml")
                val now = System.currentTimeMillis()

                // 1. Fast path: load already-parsed EPG (skips XML parsing entirely).
                if (parsedCache.exists() && (now - parsedCache.lastModified()) < CACHE_TTL_MS) {
                    val cached = readParsedCache(parsedCache)
                    if (cached != null && cached.isNotEmpty()) {
                        Log.d("EpgParser", "Loaded parsed cache: $epgUrl")
                        onComplete(cached)
                        return@execute
                    }
                }

                // 2. Medium path: raw XML on disk is still fresh -> re-parse from disk (no download).
                var epgMap: Map<String, EpgData>? = null
                if (xmlCache.exists() && (now - xmlCache.lastModified()) < CACHE_TTL_MS) {
                    try {
                        epgMap = xmlCache.inputStream().use { parseXmltv(it) }
                    } catch (e: Exception) {
                        epgMap = null
                    }
                }

                // 3. Slow path: download (and GZIP if needed), cache raw XML, then parse.
                if (epgMap == null || epgMap.isEmpty()) {
                    Log.d("EpgParser", "Downloading EPG: $epgUrl")
                    val connection = URL(epgUrl).openConnection()
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000

                    val inputStream = connection.getInputStream()
                    val decompressed = if (epgUrl.lowercase().contains(".gz")) GZIPInputStream(inputStream) else inputStream

                    FileOutputStream(xmlCache).use { out ->
                        decompressed.copyTo(out)
                    }
                    decompressed.close()

                    epgMap = xmlCache.inputStream().use { parseXmltv(it) }
                }

                // Persist parsed result so next boot is instant.
                if (epgMap.isNotEmpty()) {
                    try { writeParsedCache(parsedCache, epgMap) } catch (e: Exception) { /* non-fatal */ }
                    onComplete(epgMap)
                } else {
                    onError("Empty EPG")
                }
            } catch (e: Exception) {
                Log.e("EpgParser", "EPG Load Error: ${e.message}")
                onError(e.message ?: "EPG failed")
            }
        }
    }

    /**
     * Compact, fast-to-read serialization of the parsed EPG map.
     * Field delimiter = 0x1F (unit separator); record delimiter = newline.
     * Control characters inside text fields are replaced with spaces to stay safe.
     */
    private fun writeParsedCache(file: File, map: Map<String, EpgData>) {
        file.bufferedWriter().use { w ->
            w.write(map.size.toString()); w.append('\n')
            for ((key, data) in map) {
                val safeKey = key.replace(FS, ' ').replace('\n', ' ')
                w.write(safeKey); w.append(FS); w.write(data.programs.size.toString()); w.append('\n')
                for (p in data.programs) {
                    w.write(p.start.toString()); w.append(FS)
                    w.write(p.stop.toString()); w.append(FS)
                    w.write(p.title.replace(FS, ' ').replace('\n', ' ')); w.append(FS)
                    w.write(p.description.replace(FS, ' ').replace('\n', ' ')); w.append(FS)
                    w.write(p.category.replace(FS, ' ').replace('\n', ' ')); w.append('\n')
                }
            }
        }
    }

    private fun readParsedCache(file: File): Map<String, EpgData>? {
        val result = mutableMapOf<String, EpgData>()
        file.bufferedReader().useLines { seq ->
            val iter = seq.iterator()
            if (!iter.hasNext()) return null
            val count = iter.next().toIntOrNull() ?: return null
            repeat(count) {
                if (!iter.hasNext()) return@repeat
                val header = iter.next().split(FS)
                if (header.size < 2) return@repeat
                val key = header[0]
                val progCount = header[1].toIntOrNull() ?: 0
                val programs = mutableListOf<EpgProgram>()
                repeat(progCount) {
                    if (!iter.hasNext()) return@repeat
                    val f = iter.next().split(FS)
                    if (f.size >= 5) {
                        val start = f[0].toLongOrNull() ?: 0L
                        val stop = f[1].toLongOrNull() ?: 0L
                        programs.add(EpgProgram(f[2], start, stop, f[3], f[4]))
                    }
                }
                if (programs.isNotEmpty()) result[key] = EpgData(key, programs)
            }
        }
        return if (result.isNotEmpty()) result else null
    }

    private fun parseXmltv(inputStream: InputStream): Map<String, EpgData> {
        val channelMap = mutableMapOf<String, EpgChannelMap>()
        val programsRaw = mutableListOf<ProgrammeRaw>()

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentTag = ""
        var currentChannel: EpgChannelMap? = null
        var currentProgram: ProgrammeRaw? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = tagName
                    if (tagName == "channel") {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        currentChannel = EpgChannelMap(id, mutableListOf())
                    } else if (tagName == "programme") {
                        val ch = parser.getAttributeValue(null, "channel") ?: ""
                        val start = parser.getAttributeValue(null, "start") ?: ""
                        val stop = parser.getAttributeValue(null, "stop") ?: ""
                        currentProgram = ProgrammeRaw(ch.trim(), start, stop, "", "", "")
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "display-name" -> currentChannel?.displayNames?.add(text)
                            "title" -> currentProgram?.title = text
                            "desc" -> currentProgram?.description = text
                            "category" -> currentProgram?.category = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "channel") {
                        currentChannel?.let { channelMap[it.id] = it }
                        currentChannel = null
                    } else if (tagName == "programme") {
                        currentProgram?.let { programsRaw.add(it) }
                        currentProgram = null
                    }
                    currentTag = ""
                }
            }
            try { eventType = parser.next() } catch (e: Exception) { break }
        }
        return buildEpgDataByTvgId(channelMap, programsRaw)
    }

    private data class ProgrammeRaw(val channel: String, val start: String, val stop: String, var title: String, var description: String, var category: String)

    private fun buildEpgDataByTvgId(channelMap: Map<String, EpgChannelMap>, programsRaw: List<ProgrammeRaw>): Map<String, EpgData> {
        val result = mutableMapOf<String, EpgData>()
        val byChannel = programsRaw.groupBy { it.channel }
        Log.d("EpgParser", "Building EPG Map. Programs: ${programsRaw.size}, Channels: ${channelMap.size}")

        for ((epgId, progs) in byChannel) {
            val programs = progs.mapNotNull { raw ->
                val startTs = parseXmltvTime(raw.start) ?: return@mapNotNull null
                val stopTs = parseXmltvTime(raw.stop) ?: return@mapNotNull null
                EpgProgram(raw.title, startTs, stopTs, raw.description, raw.category)
            }.sortedBy { it.start }

            if (programs.isEmpty()) continue
            val data = EpgData(epgId, programs)
            
            // 1. PRIMARY: Map by XML channel ID (lowercase)
            val key = epgId.lowercase().trim()
            result[key] = data
            
            // 2. EXTRA: If XML ID is like "ts840", also map "840"
            val digits = key.filter { it.isDigit() }
            if (digits.isNotEmpty() && digits != key && digits.length > 1) {
                if (result[digits] == null) result[digits] = data
            }
            
            // 3. Map all display names for this channel ID
            channelMap[epgId]?.displayNames?.forEach { name ->
                val clean = name.lowercase().trim()
                if (clean.isNotEmpty()) {
                    if (result[clean] == null) result[clean] = data
                    
                    // Fuzzy match (ignore HD/SD)
                    val fuzzy = clean.replace("hd", "").replace("sd", "").replace("india", "").trim()
                    if (fuzzy.isNotEmpty() && fuzzy.length > 2 && result[fuzzy] == null) {
                        result[fuzzy] = data
                    }
                }
            }
        }
        Log.d("EpgParser", "Map built with ${result.size} lookup keys")
        return result
    }

    private fun parseXmltvTime(time: String): Long? {
        try {
            val clean = time.trim()
            if (clean.length < 14) return null
            val year = clean.substring(0, 4).toInt()
            val month = clean.substring(4, 6).toInt()
            val day = clean.substring(6, 8).toInt()
            val hour = clean.substring(8, 10).toInt()
            val min = clean.substring(10, 12).toInt()
            val sec = clean.substring(12, 14).toInt()

            var offsetSeconds = 0
            val offsetIndex = clean.indexOfAny(charArrayOf('+', '-'), 14)
            if (offsetIndex != -1) {
                val sign = if (clean[offsetIndex] == '+') 1 else -1
                val offsetPart = clean.substring(offsetIndex + 1).trim()
                if (offsetPart.length >= 4) {
                    val offH = offsetPart.substring(0, 2).toIntOrNull() ?: 0
                    val offM = offsetPart.substring(2, 4).toIntOrNull() ?: 0
                    offsetSeconds = sign * (offH * 3600 + offM * 60)
                }
            }

            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.set(year, month - 1, day, hour, min, sec)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            return (calendar.timeInMillis / 1000) - offsetSeconds
        } catch (e: Exception) { return null }
    }

    private fun String.indexOfAny(chars: CharArray, startIndex: Int): Int {
        for (i in startIndex until this.length) {
            if (this[i] in chars) return i
        }
        return -1
    }
}
