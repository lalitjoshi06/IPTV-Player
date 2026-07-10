package com.mpdplayer

data class Playlist(
    val name: String,
    var url: String,
    var isActive: Boolean = true,
    var useEpg: Boolean = true
)
