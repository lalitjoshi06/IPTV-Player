package com.mpdplayer

data class EpgProgram(
    val title: String,
    val start: Long,
    val stop: Long,
    val description: String = "",
    val category: String = ""
)

data class EpgData(
    val channelId: String,
    val programs: List<EpgProgram>
) {
    fun getCurrentProgram(): EpgProgram? {
        val now = System.currentTimeMillis() / 1000
        return programs.find { now in it.start until it.stop }
    }

    fun getNextProgram(): EpgProgram? {
        val now = System.currentTimeMillis() / 1000
        val idx = programs.indexOfFirst { now in it.start until it.stop }
        return if (idx >= 0 && idx + 1 < programs.size) programs[idx + 1] else null
    }
}
