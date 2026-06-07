package com.example.state

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaStateManager {
    data class MediaInfo(
        val title: String = "No Active Media",
        val artist: String = "Waiting for playback...",
        val isPlaying: Boolean = false,
        val albumArt: Bitmap? = null,
        val packageName: String = ""
    )

    private val _currentMedia = MutableStateFlow(MediaInfo())
    val currentMedia: StateFlow<MediaInfo> = _currentMedia.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    fun updateMediaInfo(info: MediaInfo) {
        _currentMedia.value = info
    }

    fun updateServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
    }
}
