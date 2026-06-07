package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import com.example.MainActivity
import com.example.state.MediaStateManager
import com.example.state.PreferencesManager

import android.media.session.MediaSession

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession as Media3Session
import androidx.media3.common.MediaItem

class MediaListenerService : NotificationListenerService() {

    private var isTrackingSetup = false
    private lateinit var mediaSessionManager: MediaSessionManager
    private val registeredControllers = mutableMapOf<String, MediaController>()
    private var mediaSession: MediaSession? = null
    private var audioPlaybackManager: AudioPlaybackManager? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateActiveSession()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateActiveSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        setupLocalMediaSession()
        audioPlaybackManager = AudioPlaybackManager(this)
    }

    private fun setupLocalMediaSession() {
        try {
            mediaSession = MediaSession(this, "RavanaLightSession").apply {
                isActive = true
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        triggerPlayPause()
                    }

                    override fun onPause() {
                        triggerPlayPause()
                    }

                    override fun onSkipToNext() {
                        triggerNext()
                    }

                    override fun onSkipToPrevious() {
                        triggerPrevious()
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLocalSessionState(isPlaying: Boolean, title: String?, artist: String?, albumArt: Bitmap?) {
        if (isPlaying && title != null) {
            audioPlaybackManager?.play()
        } else {
            audioPlaybackManager?.pause()
        }

        val session = mediaSession ?: return
        try {
            if (title == null) {
                val state = PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 1.0f)
                    .build()
                session.setPlaybackState(state)
                session.setMetadata(null)
                return
            }

            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "")
            if (albumArt != null) {
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt)
            }
            session.setMetadata(metaBuilder.build())

            val rawState = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val stateBuilder = PlaybackState.Builder()
                .setState(rawState, 0, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
            session.setPlaybackState(stateBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        MediaStateManager.updateServiceConnected(true)

        val prefs = PreferencesManager(this)
        if (prefs.isListenerEnabled) {
            setupMediaSessionTracking()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupMediaSessionTracking()
        MediaStateManager.updateServiceConnected(false)
        if (instance == this) {
            instance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupMediaSessionTracking()
        try {
            audioPlaybackManager?.release()
            audioPlaybackManager = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        MediaStateManager.updateServiceConnected(false)
        if (instance == this) {
            instance = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val action = intent?.action
        if (action != null) {
            val prefs = PreferencesManager(this)
            when (action) {
                ACTION_TOGGLE_LISTENER -> {
                    if (prefs.isListenerEnabled) {
                        setupMediaSessionTracking()
                        updateActiveSession()
                    } else {
                        cleanupMediaSessionTracking()
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.cancel(NOTIFICATION_ID)
                    }
                }
                ACTION_TOGGLE_FROM_NOTIFICATION -> {
                    toggleListenerState(this, false)
                }
                ACTION_PLAY_PAUSE -> triggerPlayPause()
                ACTION_NEXT -> triggerNext()
                ACTION_PREV -> triggerPrevious()
            }
        }
        return START_STICKY
    }

    private fun setupMediaSessionTracking() {
        if (isTrackingSetup) return
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MediaListenerService::class.java)

            // Register session change listener
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, componentName)

            // Query initial list
            val initialControllers = mediaSessionManager.getActiveSessions(componentName)
            updateControllers(initialControllers ?: emptyList())

            isTrackingSetup = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupMediaSessionTracking() {
        if (!isTrackingSetup) return
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // ignore
        }

        // Unregister callbacks
        registeredControllers.forEach { (_, controller) ->
            try {
                controller.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
        registeredControllers.clear()
        isTrackingSetup = false
    }

    private fun updateControllers(controllers: List<MediaController>) {
        // Unregister from old
        registeredControllers.forEach { (_, controller) ->
            try {
                controller.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
        registeredControllers.clear()

        // Register new
        controllers.forEach { controller ->
            val pkg = controller.packageName ?: "unknown"
            registeredControllers[pkg] = controller
            try {
                controller.registerCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }

        updateActiveSession()
    }

    private fun getActiveController(): MediaController? {
        val prefs = PreferencesManager(this)
        val targetPkg = prefs.activeMediaPackage
        val ourPkg = packageName ?: ""

        if (targetPkg == "all" || targetPkg.isBlank()) {
            val playing = registeredControllers.values.find {
                it.packageName != ourPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (playing != null) return playing

            return registeredControllers.values.firstOrNull { it.packageName != ourPkg }
        } else {
            val playingTarget = registeredControllers.values.find {
                it.packageName == targetPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (playingTarget != null) return playingTarget

            val targetController = registeredControllers.values.find {
                it.packageName == targetPkg
            }
            if (targetController != null) return targetController

            return null
        }
    }

    private fun updateActiveSession() {
        val controller = getActiveController()
        if (controller == null) {
            MediaStateManager.updateMediaInfo(
                MediaStateManager.MediaInfo(
                    title = "No active media",
                    artist = "Waiting for playback...",
                    isPlaying = false,
                    albumArt = null,
                    packageName = ""
                )
            )
            updateLocalSessionState(false, null, null, null)
            val prefs = PreferencesManager(this)
            if (prefs.isListenerEnabled) {
                showServiceNotification("No active media", "Waiting for playback...", false, null)
            }
            return
        }

        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Title"

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: "Unknown Artist"

        var artBitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (artBitmap == null) {
            artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }

        val state = controller.playbackState
        val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING

        // Sync state flow
        MediaStateManager.updateMediaInfo(
            MediaStateManager.MediaInfo(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                albumArt = artBitmap,
                packageName = controller.packageName ?: ""
            )
        )

        // Update local session state
        updateLocalSessionState(isPlaying, title, artist, artBitmap)

        // Show/Update Notification
        showServiceNotification(title, artist, isPlaying, artBitmap)
    }

    private fun showServiceNotification(title: String, artist: String, isPlaying: Boolean, albumArt: Bitmap?) {
        val prefs = PreferencesManager(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!prefs.isListenerEnabled) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val prevIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, MediaControlsReceiver::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, MediaControlsReceiver::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            this,
            3,
            Intent(this, MediaControlsReceiver::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getBroadcast(
            this,
            4,
            Intent(this, MediaControlsReceiver::class.java).apply { action = ACTION_TOGGLE_FROM_NOTIFICATION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                playPauseIcon, "Play/Pause", playPauseIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", nextIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", toggleIntent
            ).build()
        )

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)

        val token = mediaSession?.sessionToken ?: getActiveController()?.sessionToken
        if (token != null) {
            mediaStyle.setMediaSession(token)
        }

        builder.setStyle(mediaStyle)

        val notification = builder.build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Media Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the system playback status and media controls."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun triggerPlayPause() {
        val controller = getActiveController() ?: return
        try {
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls?.pause()
            } else {
                controller.transportControls?.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerNext() {
        val controller = getActiveController() ?: return
        try {
            controller.transportControls?.skipToNext()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerPrevious() {
        val controller = getActiveController() ?: return
        try {
            controller.transportControls?.skipToPrevious()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val CHANNEL_ID = "ravana_light_media_channel"
        const val NOTIFICATION_ID = 4056

        const val ACTION_PLAY_PAUSE = "com.amazon.mp3.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.amazon.mp3.ACTION_PREV"
        const val ACTION_NEXT = "com.amazon.mp3.ACTION_NEXT"
        const val ACTION_TOGGLE_LISTENER = "com.amazon.mp3.ACTION_TOGGLE_LISTENER"
        const val ACTION_TOGGLE_FROM_NOTIFICATION = "com.amazon.mp3.ACTION_TOGGLE_FROM_NOTIFICATION"

        private var instance: MediaListenerService? = null

        fun toggleListenerState(context: Context, enabled: Boolean) {
            val prefs = PreferencesManager(context)
            prefs.isListenerEnabled = enabled
            
            val componentName = ComponentName(context, MediaListenerService::class.java)
            val pm = context.packageManager
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    componentName,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(componentName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            val srv = instance
            if (srv != null) {
                if (enabled) {
                    srv.setupMediaSessionTracking()
                    srv.updateActiveSession()
                } else {
                    srv.cleanupMediaSessionTracking()
                    val manager = srv.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                    
                    MediaStateManager.updateMediaInfo(
                        MediaStateManager.MediaInfo(
                            title = "No active media",
                            artist = "Waiting for playback...",
                            isPlaying = false,
                            albumArt = null,
                            packageName = ""
                        )
                    )
                    srv.updateLocalSessionState(false, null, null, null)
                }
            }
            
            try {
                com.example.ToggleWidgetProvider.updateAllWidgets(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun playPauseActiveSession() {
            instance?.triggerPlayPause()
        }

        fun skipToNextActiveSession() {
            instance?.triggerNext()
        }

        fun skipToPreviousActiveSession() {
            instance?.triggerPrevious()
        }
    }
}

class AudioPlaybackManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: Media3Session? = null
    private val silentFile = java.io.File(context.cacheDir, "silent.wav")

    init {
        ensureSilentWavExists()
        try {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = false
                val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(silentFile))
                setMediaItem(mediaItem)
                prepare()
            }
            exoPlayer?.let { player ->
                mediaSession = Media3Session.Builder(context, player)
                    .setId("AppAudioSyncSession")
                    .build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureSilentWavExists() {
        if (!silentFile.exists()) {
            try {
                writeSilentWav(silentFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun writeSilentWav(file: java.io.File) {
        val sampleRate = 8000
        val durationSeconds = 1
        val numChannels = 1
        val bitsPerSample = 8
        val dataSize = sampleRate * durationSeconds * numChannels * (bitsPerSample / 8)
        val totalSize = 36 + dataSize
        
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        header[12] = 'f'.code.toByte() // fmt
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        header[16] = 16 
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        header[20] = 1 
        header[21] = 0
        
        header[22] = numChannels.toByte()
        header[23] = 0
        
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        header[32] = 1 
        header[33] = 0
        
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        
        header[36] = 'd'.code.toByte() // data
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        
        java.io.FileOutputStream(file).use { outputStream ->
            outputStream.write(header)
            val data = ByteArray(dataSize) { 128.toByte() }
            outputStream.write(data)
        }
    }

    fun play() {
        try {
            exoPlayer?.playWhenReady = true
            exoPlayer?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pause() {
        try {
            exoPlayer?.playWhenReady = false
            exoPlayer?.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            mediaSession?.release()
            exoPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
