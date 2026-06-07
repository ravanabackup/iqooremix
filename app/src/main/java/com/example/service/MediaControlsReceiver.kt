package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaControlsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            MediaListenerService.ACTION_PLAY_PAUSE -> {
                MediaListenerService.playPauseActiveSession()
            }
            MediaListenerService.ACTION_NEXT -> {
                MediaListenerService.skipToNextActiveSession()
            }
            MediaListenerService.ACTION_PREV -> {
                MediaListenerService.skipToPreviousActiveSession()
            }
            MediaListenerService.ACTION_TOGGLE_FROM_NOTIFICATION -> {
                MediaListenerService.toggleListenerState(context, false)
            }
        }
    }
}
