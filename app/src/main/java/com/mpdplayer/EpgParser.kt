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
        val now = System.currentTimeMillis() / 1000
        file.bufferedWriter().use { w ->
            w.write(map.size.toString()); w.append('\n')
            for ((key, data) in map) {
                val safeKey = key.replace(FS, ' ').replace('\n', ' ')
                // Memory Optimization: Filter old programs before writing to cache
                val futureProgs = data.programs.filter { it.stop >= now }.take(5)
                
                w.write(safeKey); w.append(FS); w.write(futureProgs.size.toString()); w.append('\n')
                for (p in futureProgs) {
                    w.write(p.start.toString()); w.append(FS)
                    w.write(p.stop.toString()); w.append(FS)
                    w.write(p.title.replace(FS, ' ').replace('\n', ' ')); w.append('\n')
                }
            }
        }
    }

    private fun readParsedCache(file: File): Map<String, EpgData>? {
        val result = mutableMapOf<String, EpgData>()
        val now = System.currentTimeMillis() / 1000
        try {
            file.bufferedReader().use { reader ->
                val countStr = reader.readLine() ?: return null
                val count = countStr.toIntOrNull() ?: return null
                
                repeat(count) {
                    val headerLine = reader.readLine() ?: return@repeat
                    val fsIndex = headerLine.indexOf(FS)
                    if (fsIndex == -1) return@repeat
                    
                    val key = headerLine.substring(0, fsIndex)
                    val progCount = headerLine.substring(fsIndex + 1).toIntOrNull() ?: 0
                    
                    val programs = ArrayList<EpgProgram>(progCount)
                    repeat(progCount) {
                        val line = reader.readLine() ?: return@repeat
                        
                        // Optimized parsing without split()
                        var current = 0
                        var next = line.indexOf(FS, current)
                        if (next == -1) return@repeat
                        val start = line.substring(current, next).toLongOrNull() ?: 0L
                        
                        current = next + 1
                        next = line.indexOf(FS, current)
                        if (next == -1) return@repeat
                        val stop = line.substring(current, next).toLongOrNull() ?: 0L
                        
                        current = next + 1
                        next = line.indexOf(FS, current)
                        val title = if (next != -1) line.substring(current, next) else line.substring(current)
                        
                        if (stop >= now) {
                            programs.add(EpgProgram(title, start, stop, "", ""))
                        }
                    }
                    if (programs.isNotEmpty()) {
                        result[key] = EpgData(key, programs)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EpgParser", "Error reading parsed cache: ${e.message}")
            file.delete()
            return null
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
        val now = System.currentTimeMillis() / 1000
        Log.d("EpgParser", "Building EPG Map. Programs: ${programsRaw.size}, Channels: ${channelMap.size}")

        // Normalization helper: lowercase, remove non-alphanumeric
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "").trim()

        for ((epgId, progs) in byChannel) {
            val programs = progs.mapNotNull { raw ->
                val startTs = parseXmltvTime(raw.start) ?: return@mapNotNull null
                val stopTs = parseXmltvTime(raw.stop) ?: return@mapNotNull null
                
                // Memory Optimization: Skip programs that have already ended
                if (stopTs < now) return@mapNotNull null
                
                // Memory Optimization: Discard description and category (not used in UI)
                EpgProgram(raw.title, startTs, stopTs, "", "")
            }.sortedBy { it.start }
             .take(5) // Memory Optimization: Only keep current + next 4 programs

            if (programs.isEmpty()) continue
            val data = EpgData(epgId, programs)
            
            // 1. PRIMARY: Map by XML channel ID (lowercase)
            val key = epgId.lowercase().trim()
            result[key] = data
            
            // 2. Map normalized version of the epgId
            val normId = normalize(key)
            if (normId.isNotEmpty() && result[normId] == null) {
                result[normId] = data
            }
            
            // 3. EXTRA: If XML ID is like "ts840", also map "840"
            val digits = key.filter { it.isDigit() }
            if (digits.isNotEmpty() && digits != key && digits.length > 1) {
                if (result[digits] == null) result[digits] = data
            }
            
            // 4. Map all display names for this channel ID
            channelMap[epgId]?.displayNames?.forEach { name ->
                val clean = name.lowercase().trim()
                if (clean.isNotEmpty()) {
                    if (result[clean] == null) result[clean] = data
                    
                    // Fuzzy match (ignore HD/SD and special chars)
                    val fuzzy = normalize(clean).replace("hd", "").replace("sd", "").replace("india", "")
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
