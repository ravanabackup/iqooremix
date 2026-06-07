package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.MediaListenerService
import com.example.state.MediaStateManager
import com.example.state.PreferencesManager
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class MicVolumeListener(
    private val context: Context,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted")
            return
        }

        stop()

        recordJob = scope.launch(Dispatchers.IO) {
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                withContext(Dispatchers.Main) {
                    onError("Failed to get audio buffer size")
                }
                return@launch
            }

            val bufferSize = (minBufSize * 2).coerceAtLeast(1024)
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    onError("Security exception: microphone permission required")
                }
                return@launch
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Failed to initialize audio recorder: ${e.localizedMessage}")
                }
                return@launch
            }

            val recorder = audioRecord
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    onError("AudioRecord initialization failed")
                }
                recorder?.release()
                audioRecord = null
                return@launch
            }

            try {
                recorder.startRecording()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Failed to start recording: ${e.localizedMessage}")
                }
                recorder.release()
                audioRecord = null
                return@launch
            }

            val buffer = ShortArray(bufferSize)
            var lastUpdate = 0L

            while (isActive && audioRecord != null) {
                val readSize = recorder.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    // Compute absolute maximum or RMS
                    var sum = 0.0
                    for (i in 0 until readSize) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = Math.sqrt(sum / readSize)
                    
                    // Normalize RMS to a fraction between 0.0 and 1.0
                    val maxPossibleRms = 8000.0
                    val normalized = (rms / maxPossibleRms).coerceIn(0.0, 1.0).toFloat()

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 33) { // limit updates to 30fps
                        lastUpdate = now
                        withContext(Dispatchers.Main) {
                            onAmplitudeChanged(normalized)
                        }
                    }
                } else if (readSize < 0) {
                    withContext(Dispatchers.Main) {
                        onError("Error reading audio data: $readSize")
                    }
                    break
                }
                delay(10)
            }

            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            } catch (e: Exception) {
                // Ignore
            }
            audioRecord = null
        }
    }

    fun stop() {
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
    }
}

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap? = null
)

fun getInstalledLauncherApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return try {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos.map { resolveInfo ->
            val name = resolveInfo.loadLabel(pm).toString()
            val pkg = resolveInfo.activityInfo.packageName
            val iconBitmap = try {
                val drawable = pm.getApplicationIcon(pkg)
                val width = drawable.intrinsicWidth.coerceAtLeast(48).coerceAtMost(144)
                val height = drawable.intrinsicHeight.coerceAtLeast(48).coerceAtMost(144)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } catch (e: Exception) {
                null
            }
            InstalledApp(name, pkg, iconBitmap)
        }.distinctBy { it.packageName }
         .sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }
}

class MainActivity : ComponentActivity() {

    private val isNotificationAccessGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { PreferencesManager(context) }
            var appThemeMode by remember { mutableStateOf(prefs.appThemeMode) }

            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "app_theme_mode") {
                        appThemeMode = prefs.appThemeMode
                    }
                }
                val sharedPrefs = context.getSharedPreferences("ravana_light_prefs", Context.MODE_PRIVATE)
                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val isDark = when (appThemeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = isDark, dynamicColor = false) {
                var selectedTab by remember { mutableStateOf("Home") }
                val isAccessGranted by isNotificationAccessGranted
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        BottomNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            "Home" -> {
                                RavanaLightDashboard(
                                    isAccessGranted = isAccessGranted,
                                    onRequestAccess = { openNotificationAccessSettings() }
                                )
                            }
                            "Mic" -> {
                                MicDashboard()
                            }
                            "Stats" -> {
                                StatsDashboard()
                            }
                            "Logs" -> {
                                LogsDashboard()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNotificationAccessGranted.value = checkNotificationAccess()
    }

    private fun checkNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (ex: Exception) {
                // fallback
            }
        }
    }
}

@Composable
fun RavanaLightDashboard(
    isAccessGranted: Boolean,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaInfo by MediaStateManager.currentMedia.collectAsStateWithLifecycle()
    val isServiceConnected by MediaStateManager.isServiceConnected.collectAsStateWithLifecycle()
    
    val prefs = remember { PreferencesManager(context) }
    var isListenerToggleEnabled by remember { mutableStateOf(prefs.isListenerEnabled) }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(listOf(InstalledApp("All Player Sessions", "all", null))) }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            val appsList = listOf(InstalledApp("All Player Sessions", "all", null)) + getInstalledLauncherApps(context)
            installedApps = appsList
        }
    }

    var isRhythmSyncEnabled by remember { mutableStateOf(prefs.isRhythmSyncEnabled) }
    var rhythmSyncStyle by remember { mutableStateOf(prefs.rhythmSyncStyle) }
    var rhythmColorMode by remember { mutableStateOf(prefs.rhythmColorMode) }
    var rhythmSensitivity by remember { mutableStateOf(prefs.rhythmSensitivity) }
    var rhythmBrightness by remember { mutableStateOf(prefs.rhythmBrightness) }
    var activeMediaPackage by remember { mutableStateOf(prefs.activeMediaPackage) }
    var appThemeMode by remember { mutableStateOf(prefs.appThemeMode) }

    LaunchedEffect(Unit) {
        if (prefs.isListenerEnabled) {
            setServiceEnabledState(context, true)
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "listener_enabled" -> isListenerToggleEnabled = prefs.isListenerEnabled
                "active_media_package" -> activeMediaPackage = prefs.activeMediaPackage
                "app_theme_mode" -> appThemeMode = prefs.appThemeMode
            }
        }
        val sharedPrefs = context.getSharedPreferences("ravana_light_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Request standard notification permission on api 33+
    var hasPostNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPostNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
            permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // App Identity Header (M3 Top app block styling)
        AppHeaderSection(isServiceConnected && isListenerToggleEnabled)

        // Notification Access Check Panel
        if (!isAccessGranted) {
            PermissionRequiredCard(onRequestAccess = onRequestAccess)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
            NotificationPermissionRequiredCard {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
        }

        // Feature Toggle Card (Tonal Purple Container / #EADDFF)
        MasterToggleCard(
            isEnabled = isListenerToggleEnabled,
            onValueChange = { newValue ->
                isListenerToggleEnabled = newValue
                setServiceEnabledState(context, newValue)
            }
        )

        // Live Access indicators matching HTML spec
        StatusInfoSection(isAccessGranted = isAccessGranted)

        // System Player Center Cockpit (#FFFFFF layout with outline #CAC4D0)
        val activePlaying = isAccessGranted && isListenerToggleEnabled && mediaInfo.packageName.isNotEmpty() &&
                (activeMediaPackage == "all" || mediaInfo.packageName == activeMediaPackage)
        
        PlaybackCockpit(
            mediaInfo = mediaInfo,
            isActive = activePlaying,
            isRhythmEnabled = isRhythmSyncEnabled,
            rhythmStyle = rhythmSyncStyle,
            colorMode = rhythmColorMode,
            rhythmSensitivity = rhythmSensitivity,
            rhythmBrightness = rhythmBrightness,
            onPrevClick = { MediaListenerService.skipToPreviousActiveSession() },
            onPlayPauseClick = { MediaListenerService.playPauseActiveSession() },
            onNextClick = { MediaListenerService.skipToNextActiveSession() }
        )

        // Dedicated Sound Suite Sync (Music Rhythm) Panel
        SoundSuiteSynchronizationCard(
            isEnabled = isRhythmSyncEnabled,
            onEnabledChanged = {
                isRhythmSyncEnabled = it
                prefs.isRhythmSyncEnabled = it
            },
            syncStyle = rhythmSyncStyle,
            onStyleChanged = {
                rhythmSyncStyle = it
                prefs.rhythmSyncStyle = it
            },
            colorMode = rhythmColorMode,
            onColorModeChanged = {
                rhythmColorMode = it
                prefs.rhythmColorMode = it
            },
            sensitivity = rhythmSensitivity,
            onSensitivityChanged = {
                rhythmSensitivity = it
                prefs.rhythmSensitivity = it
            },
            brightness = rhythmBrightness,
            onBrightnessChanged = {
                rhythmBrightness = it
                prefs.rhythmBrightness = it
            },
            isPlaying = activePlaying && mediaInfo.isPlaying,
            title = mediaInfo.title,
            artist = mediaInfo.artist
        )

        // Preferences & Personalization Panel
        PreferencesPersonalizationCard(
            currentApp = activeMediaPackage,
            installedApps = installedApps,
            onAppSelected = {
                activeMediaPackage = it
                prefs.activeMediaPackage = it
            },
            currentTheme = appThemeMode,
            onThemeSelected = {
                appThemeMode = it
                prefs.appThemeMode = it
            }
        )
        
        // Aesthetic Brand Footer
        BrandFooter()
    }
}

fun getSelectedAuraColors(
    colorMode: String, 
    title: String, 
    artist: String
): List<Color> {
    return when (colorMode) {
        "album_art" -> {
            if (title.isNotEmpty() && title != "No Active Media" && title != "No active media") {
                val hashValue = (title + artist).hashCode()
                val h1 = kotlin.math.abs(hashValue % 360).toFloat()
                val h2 = (h1 + 180f) % 360f
                listOf(
                    Color.hsv(h1, 0.85f, 0.95f),
                    Color.hsv(h2, 0.75f, 0.90f)
                )
            } else {
                listOf(Color(0xFF6750A4), Color(0xFF03DAC6)) // Default Purple & Cyan
            }
        }
        "cyber_neon" -> listOf(Color(0xFF00F0FF), Color(0xFFFF007F)) // Frost Cyan & Electric Pink
        "space_violet" -> listOf(Color(0xFF6750A4), Color(0xFF3F51B5)) // Indigo Purple & Royal Blue
        "magma_flame" -> listOf(Color(0xFFFF3D00), Color(0xFFFFC107)) // Flame Red & Amber Orange
        else -> listOf(Color(0xFF6750A4), Color(0xFF00F0FF))
    }
}

@Composable
fun AppSelectorDialog(
    installedApps: List<InstalledApp>,
    onDismissRequest: () -> Unit,
    onAppSelected: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, installedApps) {
        installedApps.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .shadow(12.dp, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Select Target App",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search system apps...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                    },
                    singleLine = true
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onAppSelected(app.packageName)
                                    onDismissRequest()
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (app.packageName == "all") MaterialTheme.colorScheme.primaryContainer 
                                        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = "${app.name} icon",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = if (app.packageName == "all") "♫" else app.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = if (app.packageName == "all") MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun PreferencesPersonalizationCard(
    currentApp: String,
    installedApps: List<InstalledApp>,
    onAppSelected: (String) -> Unit,
    currentTheme: String,
    onThemeSelected: (String) -> Unit
) {
    var showAppDialog by remember { mutableStateOf(false) }

    if (showAppDialog) {
        AppSelectorDialog(
            installedApps = installedApps,
            onDismissRequest = { showAppDialog = false },
            onAppSelected = onAppSelected
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp))
            .testTag("preferences_personalization_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "System Source & Theme Binding",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            Text(
                text = "BIND TO TARGET MUSIC PLAYER",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            val selectedAppObj = remember(currentApp, installedApps) {
                installedApps.find { it.packageName == currentApp }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { showAppDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedAppObj?.icon != null) {
                        Image(
                            bitmap = selectedAppObj.icon,
                            contentDescription = "${selectedAppObj.name} icon",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val appName = if (currentApp == "all") "All Player Sessions" else (selectedAppObj?.name ?: currentApp)
                        Text(
                            text = if (currentApp == "all") "♫" else appName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    val appName = if (currentApp == "all") "All Player Sessions" else (selectedAppObj?.name ?: currentApp)
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = if (currentApp == "all") "listening to any active system media source" else currentApp,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Change target app",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "APPLICATION MODE THEME",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "light" to "Light Mode",
                    "dark" to "Dark Mode",
                    "system" to "Follow System"
                ).forEach { (mode, label) ->
                    val isSelected = currentTheme == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onThemeSelected(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SoundSuiteSynchronizationCard(
    isEnabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    syncStyle: String,
    onStyleChanged: (String) -> Unit,
    colorMode: String,
    onColorModeChanged: (String) -> Unit,
    sensitivity: Float,
    onSensitivityChanged: (Float) -> Unit,
    brightness: Float,
    onBrightnessChanged: (Float) -> Unit,
    isPlaying: Boolean,
    title: String,
    artist: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp))
            .testTag("sound_suite_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Sound Suite Sync",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 9.sp
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Dynamic audio-synchronized light show",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            if (isEnabled) {
                Text(
                    text = "AURA PREVIEW",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                val auraColors = getSelectedAuraColors(colorMode, title, artist)
                val transition = rememberInfiniteTransition(label = "preview_neon")
                
                val ringRotation by if (isPlaying) {
                    transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (3200 / sensitivity).toInt().coerceAtLeast(800),
                                easing = LinearEasing
                            )
                        ),
                        label = "preview_rotation"
                    )
                } else {
                    remember { mutableStateOf(0f) }
                }

                val previewScale by if (isPlaying && syncStyle == "pulse") {
                    transition.animateFloat(
                        initialValue = 0.94f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (500 / sensitivity).toInt().coerceAtLeast(150),
                                easing = FastOutSlowInEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "preview_scale"
                    )
                } else {
                    remember { mutableStateOf(1.0f) }
                }

                val previewAlpha by if (isPlaying && syncStyle == "neon_glow") {
                    transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (800 / sensitivity).toInt().coerceAtLeast(200),
                                easing = LinearEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "preview_alpha"
                    )
                } else {
                    remember { mutableStateOf(0.9f) }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF111012)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(135.dp)
                            .border(1.5.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
                            .background(Color(0xFF222025), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .background(Color(0xFF131214), CircleShape)
                                    .border(1.dp, Color(0xFF49454F), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPlaying) {
                                    val brushValue = if (syncStyle == "aurora_wave") {
                                        Brush.sweepGradient(
                                            colors = auraColors,
                                            center = Offset.Unspecified
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            colors = auraColors
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .graphicsLayer { rotationZ = ringRotation }
                                            .size(54.dp * previewScale)
                                            .shadow(
                                                elevation = (20.dp * brightness),
                                                shape = CircleShape,
                                                clip = false,
                                                ambientColor = auraColors.first(),
                                                spotColor = auraColors.last()
                                            )
                                            .border(
                                                width = if (syncStyle == "aurora_wave") 5.dp else 3.dp,
                                                brush = brushValue,
                                                shape = CircleShape
                                            )
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .border(1.5.dp, Color(0xFF49454F), CircleShape)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFF1D1B20), CircleShape)
                                        .border(0.5.dp, Color(0xFF49454F), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(Color(0xFF09090A), CircleShape)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = if (isPlaying) "MONSTER HALO SYNC" else "STANDBY ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 7.5.sp,
                                    letterSpacing = 1.sp,
                                    color = if (isPlaying) auraColors.first().copy(alpha = previewAlpha) else Color(0x88FFFFFF)
                                )
                            )

                            if (isPlaying) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(3) {
                                        Box(
                                            modifier = Modifier
                                                .size(3.dp)
                                                .background(auraColors.last().copy(alpha = previewAlpha), CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "SYNC STYLE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "neon_glow" to "Breathing",
                        "aurora_wave" to "Aurora Wave",
                        "pulse" to "Flash Pulse"
                    ).forEach { styleOpt ->
                        val isSelected = syncStyle == styleOpt.first
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onStyleChanged(styleOpt.first) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = styleOpt.second,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "AURA COLORS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "album_art" to "Adaptive",
                        "cyber_neon" to "Cyber Neon",
                        "space_violet" to "Space Violet",
                        "magma_flame" to "Magma Flame"
                    ).forEach { colorOpt ->
                        val isSelected = colorMode == colorOpt.first
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onColorModeChanged(colorOpt.first) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = colorOpt.second,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Frequency Reaction Sensitivity",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = "${(sensitivity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = onSensitivityChanged,
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            thumbColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ambient Glow Brightness",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = "${(brightness * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Slider(
                        value = brightness,
                        onValueChange = onBrightnessChanged,
                        valueRange = 0.1f..1.0f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            thumbColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sound Suite Sync is disabled. Enable to activate real-time LED and screen-edge halo visualizations under music beat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun AppHeaderSection(isServiceActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF6750A4), RoundedCornerShape(12.dp))
                    .shadow(1.dp, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width / 2, 0f)
                        lineTo(size.width / 2, size.height)
                        moveTo(0f, size.height / 2)
                        lineTo(size.width, size.height / 2)
                        moveTo(size.width * 0.15f, size.height * 0.15f)
                        lineTo(size.width * 0.85f, size.height * 0.85f)
                        moveTo(size.width * 0.15f, size.height * 0.85f)
                        lineTo(size.width * 0.85f, size.height * 0.15f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }

            Text(
                text = "Ravana Light Remix",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1D1B20)
                )
            )
        }

        // Live Status Badge representation
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFFF3EDF7), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by if (isServiceActive) {
                transition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )
            } else {
                remember { mutableStateOf(1f) }
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (isServiceActive) Color(0xFF4CAF50) else Color(0xFF6C757D),
                        CircleShape
                    )
            )
        }
    }
}

@Composable
fun StatusInfoSection(isAccessGranted: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isAccessGranted) Color(0xFF4CAF50) else Color(0xFFE53935), CircleShape)
                )
                Text(
                    text = if (isAccessGranted) "Notification Access: Granted" else "Notification Access: Action Needed",
                    color = Color(0xFF49454F),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF6750A4), CircleShape)
                )
                Text(
                    text = "Media3 Session: Synchronized",
                    color = Color(0xFF49454F),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
fun PermissionRequiredCard(onRequestAccess: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFFF8A8A), RoundedCornerShape(28.dp))
            .testTag("permission_warning_card"),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF1F1)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Access Required",
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(36.dp)
            )

            Text(
                text = "Notification Access Required",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            )

            Text(
                text = "Ravana Light Remix needs 'Notification Access' permission to communicate with background music apps (Spotify, YouTube, Soundcloud, etc.) and bind its media control widget system-wide.",
                color = Color(0xFF1D1B20),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onRequestAccess,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("grant_permission_button")
            ) {
                Text(
                    text = "GRANT ACCESS IN SETTINGS",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }
}

@Composable
fun NotificationPermissionRequiredCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFFFB74D), RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF8E1)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Alert",
                tint = Color(0xFFE65100),
                modifier = Modifier.size(32.dp)
            )

            Text(
                text = "Media Notification Access Needed",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
            )

            Text(
                text = "To show the ongoing playback controllers in your notification drawer, please enable notifications.",
                color = Color(0xFF1D1B20),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("ENABLE NOTIFICATIONS", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MasterToggleCard(
    isEnabled: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp))
            .testTag("master_toggle_card"),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFEADDFF)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Listener Service",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF21005D)
                    )
                )
                Text(
                    text = "Active foreground session monitoring",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF21005D).copy(alpha = 0.7f)
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onValueChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF6750A4),
                    uncheckedThumbColor = Color(0xFF49454F),
                    uncheckedTrackColor = Color(0xFFF3EDF7)
                ),
                modifier = Modifier.testTag("master_switch")
            )
        }
    }
}

@Composable
fun PlaybackCockpit(
    mediaInfo: MediaStateManager.MediaInfo,
    isActive: Boolean,
    isRhythmEnabled: Boolean,
    rhythmStyle: String,
    colorMode: String,
    rhythmSensitivity: Float,
    rhythmBrightness: Float,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp, 
                if (isActive && mediaInfo.isPlaying) Color(0xFF6750A4) else Color(0xFFCAC4D0), 
                RoundedCornerShape(28.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFFFF)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (isActive) {
                // Header state label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "NOW DETECTED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = Color(0xFF6750A4)
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (mediaInfo.packageName.isNotEmpty()) mediaInfo.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() } else "System Media",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF49454F),
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                // Artwork Frame with dynamic halo sync
                val auraColors = getSelectedAuraColors(colorMode, mediaInfo.title, mediaInfo.artist)
                val transition = rememberInfiniteTransition(label = "cockpit_aura")
                
                val rotationAngle by if (isRhythmEnabled && mediaInfo.isPlaying) {
                    transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (4000 / rhythmSensitivity).toInt().coerceAtLeast(800),
                                easing = LinearEasing
                            )
                        ),
                        label = "aura_rotation"
                    )
                } else {
                    remember { mutableStateOf(0f) }
                }

                val pulseScale by if (isRhythmEnabled && mediaInfo.isPlaying) {
                    transition.animateFloat(
                        initialValue = 0.95f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (600 / rhythmSensitivity).toInt().coerceAtLeast(150),
                                easing = FastOutSlowInEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "aura_pulse"
                    )
                } else {
                    remember { mutableStateOf(1.0f) }
                }

                Box(
                    modifier = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRhythmEnabled && mediaInfo.isPlaying) {
                        // Ambient bloom background glow
                        Box(
                            modifier = Modifier
                                .size(160.dp * pulseScale)
                                .graphicsLayer { rotationZ = rotationAngle }
                                .shadow(
                                    elevation = (24.dp * rhythmBrightness),
                                    shape = RoundedCornerShape(24.dp),
                                    clip = false,
                                    ambientColor = auraColors.first(),
                                    spotColor = auraColors.last()
                                )
                                .background(
                                    Brush.sweepGradient(
                                        colors = auraColors
                                    ),
                                    shape = RoundedCornerShape(24.dp)
                                )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .shadow(2.dp, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFFF3EDF7)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mediaInfo.albumArt != null) {
                            Image(
                                bitmap = mediaInfo.albumArt.asImageBitmap(),
                                contentDescription = "Album Art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF6750A4).copy(alpha = 0.1f),
                                                Color(0xFF6750A4).copy(alpha = 0.3f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "♫",
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 72.sp,
                                        color = Color(0xFF6750A4)
                                    )
                                )
                            }
                        }

                        // Overlay rotating border
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isRhythmEnabled && mediaInfo.isPlaying) {
                                        Modifier.graphicsLayer { rotationZ = rotationAngle }
                                    } else {
                                        Modifier
                                    }
                                )
                                .border(
                                    width = if (isRhythmEnabled && mediaInfo.isPlaying) 2.dp else 1.dp,
                                    brush = if (isRhythmEnabled && mediaInfo.isPlaying) {
                                        Brush.sweepGradient(colors = auraColors)
                                    } else {
                                        androidx.compose.ui.graphics.SolidColor(Color(0xFFCAC4D0))
                                    },
                                    shape = RoundedCornerShape(24.dp)
                                )
                        )
                    }
                }

                // Metadata Frame
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = mediaInfo.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D1B20)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = mediaInfo.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                // Visualizer Row using M3 purple colors
                LiveVisualizer(isPlaying = mediaInfo.isPlaying)

                // Tactical Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviousControl(onClick = onPrevClick)
                    Spacer(modifier = Modifier.width(32.dp))
                    PlayPauseControl(isPlaying = mediaInfo.isPlaying, onClick = onPlayPauseClick)
                    Spacer(modifier = Modifier.width(32.dp))
                    NextControl(onClick = onNextClick)
                }

            } else {
                // Standby state matching style
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFF3EDF7), CircleShape)
                            .border(1.dp, Color(0xFFCAC4D0), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "♫",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 42.sp,
                                color = Color(0xFF6750A4)
                            )
                        )
                    }

                    Text(
                        text = "Ravana Standing By",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF1D1B20),
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Initiate music playback in external apps (Spotify, YouTube Music, SoundCloud etc.). Ravana Light Remix will seamlessly bind controls here instantly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LiveVisualizer(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val barCount = 4
    val transition = rememberInfiniteTransition(label = "sound_bars")
    
    val heights = (0 until barCount).map { index ->
        val duration = when(index) {
            0 -> 650
            1 -> 450
            2 -> 800
            else -> 550
        }
        val targetVal = when(index) {
            0 -> 35.dp
            1 -> 45.dp
            2 -> 25.dp
            else -> 40.dp
        }
        if (isPlaying) {
            transition.animateValue(
                initialValue = 10.dp,
                targetValue = targetVal,
                typeConverter = Dp.VectorConverter,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
        } else {
            remember { mutableStateOf(8.dp) }
        }
    }

    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { heightVal ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(heightVal.value)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF6750A4), Color(0xFFEADDFF))
                        ),
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
fun PlayPauseControl(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .background(
                Color(0xFF6750A4),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .testTag("play_pause_control"),
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(5.dp, 18.dp).background(Color.White, RoundedCornerShape(1.dp)))
                Box(modifier = Modifier.size(5.dp, 18.dp).background(Color.White, RoundedCornerShape(1.dp)))
            }
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

enum class ArrowDirection { LEFT, RIGHT }

@Composable
fun TriangleArrow(
    direction: ArrowDirection,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(size)) {
        val path = androidx.compose.ui.graphics.Path()
        if (direction == ArrowDirection.RIGHT) {
            path.moveTo(0f, 0f)
            path.lineTo(this.size.width, this.size.height / 2f)
            path.lineTo(0f, this.size.height)
        } else {
            path.moveTo(this.size.width, 0f)
            path.lineTo(0f, this.size.height / 2f)
            path.lineTo(this.size.width, this.size.height)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
fun NextControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("next_control"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriangleArrow(direction = ArrowDirection.RIGHT, size = 12.dp, color = Color(0xFF1D1B20))
            Spacer(modifier = Modifier.width(1.dp))
            TriangleArrow(direction = ArrowDirection.RIGHT, size = 12.dp, color = Color(0xFF1D1B20))
            Spacer(modifier = Modifier.width(2.dp))
            Box(modifier = Modifier.size(2.dp, 12.dp).background(Color(0xFF1D1B20), RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun PreviousControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("previous_control"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(2.dp, 12.dp).background(Color(0xFF1D1B20), RoundedCornerShape(1.dp)))
            Spacer(modifier = Modifier.width(2.dp))
            TriangleArrow(direction = ArrowDirection.LEFT, size = 12.dp, color = Color(0xFF1D1B20))
            Spacer(modifier = Modifier.width(1.dp))
            TriangleArrow(direction = ArrowDirection.LEFT, size = 12.dp, color = Color(0xFF1D1B20))
        }
    }
}

@Composable
fun BrandFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RAVANA LIGHT SOUND SUITE • v1.0",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color(0xFF49454F)
            )
        )
    }
}

@Composable
fun BottomNavigationBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            val selectedIndicatorColor = MaterialTheme.colorScheme.secondaryContainer
            val unselectedIndicatorColor = Color.Transparent
            val selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer
            val unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant

            BottomTabButton(
                label = "Home",
                isSelected = selectedTab == "Home",
                iconContent = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(
                                if (selectedTab == "Home") selectedIndicatorColor else unselectedIndicatorColor,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Home",
                            tint = if (selectedTab == "Home") selectedIconColor else unselectedIconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                onClick = { onTabSelected("Home") }
            )

            BottomTabButton(
                label = "Mic Sync",
                isSelected = selectedTab == "Mic",
                iconContent = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(
                                if (selectedTab == "Mic") selectedIndicatorColor else unselectedIndicatorColor,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
                            val activeColor = if (selectedTab == "Mic") selectedIconColor else unselectedIconColor
                            
                            // Draw microphone cup (rounded capsule)
                            drawRoundRect(
                                color = activeColor,
                                topLeft = Offset(size.width * 0.35f, size.height * 0.15f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.45f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f, size.width * 0.15f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                            
                            // Draw the stand curve
                            drawArc(
                                color = activeColor,
                                startAngle = 0f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(size.width * 0.2f, size.height * 0.25f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.45f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                            
                            // Draw vertical line from arc to base
                            drawLine(
                                color = activeColor,
                                start = Offset(size.width * 0.5f, size.height * 0.7f),
                                end = Offset(size.width * 0.5f, size.height * 0.85f),
                                strokeWidth = 2.dp.toPx()
                            )
                            
                            // Draw base line
                            drawLine(
                                color = activeColor,
                                start = Offset(size.width * 0.3f, size.height * 0.85f),
                                end = Offset(size.width * 0.7f, size.height * 0.85f),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                },
                onClick = { onTabSelected("Mic") }
            )

            BottomTabButton(
                label = "Stats",
                isSelected = selectedTab == "Stats",
                iconContent = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(
                                if (selectedTab == "Stats") selectedIndicatorColor else unselectedIndicatorColor,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
                            val activeColor = if (selectedTab == "Stats") selectedIconColor else unselectedIconColor
                            drawLine(
                                color = activeColor,
                                start = Offset(2.dp.toPx(), size.height - 2.dp.toPx()),
                                end = Offset(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = activeColor,
                                start = Offset(4.dp.toPx(), size.height - 4.dp.toPx()),
                                end = Offset(4.dp.toPx(), 4.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = activeColor,
                                start = Offset(8.dp.toPx(), size.height - 4.dp.toPx()),
                                end = Offset(8.dp.toPx(), size.height - 14.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                },
                onClick = { onTabSelected("Stats") }
            )

            BottomTabButton(
                label = "Logs",
                isSelected = selectedTab == "Logs",
                iconContent = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(
                                if (selectedTab == "Logs") selectedIndicatorColor else unselectedIndicatorColor,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
                            val activeColor = if (selectedTab == "Logs") selectedIconColor else unselectedIconColor
                            val spacing = 5.dp.toPx()
                            drawLine(
                                color = activeColor,
                                start = Offset(0f, 2.dp.toPx()),
                                end = Offset(size.width, 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = activeColor,
                                start = Offset(0f, 2.dp.toPx() + spacing),
                                end = Offset(size.width * 0.7f, 2.dp.toPx() + spacing),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = activeColor,
                                start = Offset(0f, 2.dp.toPx() + spacing * 2),
                                end = Offset(size.width * 0.9f, 2.dp.toPx() + spacing * 2),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                },
                onClick = { onTabSelected("Logs") }
            )
        }
    }
}

@Composable
fun BottomTabButton(
    label: String,
    isSelected: Boolean,
    iconContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconContent()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color(0xFF1D1B20) else Color(0xFF49454F).copy(alpha = 0.6f)
            )
        )
    }
}

@Composable
fun StatsDashboard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Stream Stats",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B20)
                )
            )
            Text(
                text = "Active session telemetry logs",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF49454F)
                )
            )
        }

        // Live stream chart mockup using Canvas
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Bandwidth & Performance Index",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D1B20)
                )
                )
                
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFFF3EDF7), RoundedCornerShape(12.dp))
                ) {
                    // Draw custom smooth area line diagram using theme colors
                    val points = listOf(20f, 40f, 35f, 75f, 60f, 90f, 85f)
                    val step = size.width / (points.size - 1)
                    val maxVal = 100f
                    val scaleY = size.height / maxVal
                    
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(0f, size.height - points[0] * scaleY)
                    for (i in 1 until points.size) {
                        path.lineTo(i * step, size.height - points[i] * scaleY)
                    }
                    
                    drawPath(
                        path = path,
                        color = Color(0xFF6750A4),
                        style = Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }
        }

        // Row of quick statistics cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Total Playtime", style = MaterialTheme.typography.labelSmall, color = Color(0xFF49454F))
                    Text("142 min", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1D1B20))
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Service Status", style = MaterialTheme.typography.labelSmall, color = Color(0xFF49454F))
                    Text("Optimal", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF6750A4))
                }
            }
        }
    }
}

@Composable
fun LogsDashboard(modifier: Modifier = Modifier) {
    val items = listOf(
        Pair("08:30:37Z", "Media Session Connected Successfully"),
        Pair("08:29:12Z", "Active system player synced: Spotify"),
        Pair("08:21:05Z", "Notification Service registered in background"),
        Pair("08:15:00Z", "Audio grabber initialized and standing by"),
        Pair("08:02:44Z", "Ravana Light Remix Sound Suite daemon started-up")
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "System Logs",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B20)
                )
            )
            Text(
                text = "Session binding historical events",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF49454F)
                )
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items.forEach { log ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = log.first,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF6750A4),
                            modifier = Modifier
                                .background(Color(0xFFEADDFF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = log.second,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1D1B20),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun MicDashboard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // Check permission state
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    var isRecording by remember { mutableStateOf(false) }
    var rawAmplitude by remember { mutableFloatStateOf(0.0f) }
    var micError by remember { mutableStateOf<String?>(null) }

    var sensitivity by remember { mutableFloatStateOf(2.0f) }
    var brightness by remember { mutableFloatStateOf(0.8f) }
    var selectedColorMode by remember { mutableStateOf("cyber_neon") }

    val amplitude = (rawAmplitude * sensitivity).coerceIn(0.0f, 1.0f)

    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(isRecording, hasMicPermission) {
        var micListener: MicVolumeListener? = null
        if (isRecording && hasMicPermission) {
            micError = null
            micListener = MicVolumeListener(
                context = context,
                onAmplitudeChanged = { newAmp ->
                    rawAmplitude = newAmp
                },
                onError = { err ->
                    micError = err
                    isRecording = false
                }
            )
            micListener.start(coroutineScope)
        } else {
            rawAmplitude = 0.0f
        }
        onDispose {
            micListener?.stop()
        }
    }

    // Spring scale logic for dynamic halo bloom
    val animatedScale by animateFloatAsState(
        targetValue = 1.12f + amplitude * 1.2f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pulsing_scale"
    )

    // Continuous rotation logic
    val infiniteTransition = rememberInfiniteTransition(label = "halo_rotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotating_glow"
    )
    val finalRotation = if (isRecording) rotationAngle else 0f

    val auraColors = getSelectedAuraColors(selectedColorMode, "", "")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Dashboard Title Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Microphone Beat Sync",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B20)
                )
            )
            Text(
                text = "Ambient decibel sound sync suite",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF49454F)
                )
            )
        }

        // Handle permission state card
        if (!hasMicPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFF4B400).copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .testTag("mic_permission_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9E6)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🎤 Microphone Access Required",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB06000)
                        )
                    )
                    Text(
                        text = "To synchronize LEDs and light rings with real-time room audio/mic feed, please grant microphone permissions below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6D4C00)
                    )
                    Button(
                        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4B400)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Microphone Access", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Displays the main halo light centered box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color(0xFFCAC4D0).copy(alpha = 0.6f), RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "VIVO iQOO MONSTER HALO PREVIEW",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        text = "Real-time LED ring emulator",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF49454F)
                        )
                    )
                }

                // Interactive Simulated Vivo Smartphone Back
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0C0A0E)),
                    contentAlignment = Alignment.Center
                ) {
                    // Futuristic gaming circuit grid layout overlays
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val activeColor = if (isRecording) auraColors.first() else Color(0xFF2C2735)
                        
                        // Cyber vertical strip guidelines
                        drawLine(
                            color = activeColor.copy(alpha = 0.12f),
                            start = Offset(size.width * 0.5f, 0f),
                            end = Offset(size.width * 0.5f, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                        
                        // Cyber accents corner lines
                        drawCircle(
                            color = activeColor.copy(alpha = 0.08f),
                            center = Offset(size.width * 0.5f, size.height * 0.35f),
                            radius = size.width * 0.45f
                        )
                    }

                    // Luxury Glass Smartphone Chassis Mock
                    Box(
                        modifier = Modifier
                            .width(165.dp)
                            .height(265.dp)
                            .border(1.5.dp, Color(0xFF2D2A35), RoundedCornerShape(24.dp))
                            .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black)
                            .background(Color(0xFF15131A), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))

                            // Monster Halo Ring (Circular Camera Ring module)
                            Box(
                                modifier = Modifier.size(96.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isRecording) {
                                    // High-fidelity halo glow bloom radiating outward
                                    Box(
                                        modifier = Modifier
                                            .size(82.dp)
                                            .graphicsLayer {
                                                scaleX = animatedScale
                                                scaleY = animatedScale
                                                rotationZ = finalRotation
                                            }
                                            .shadow(
                                                elevation = (38.dp * brightness * (1.2f + amplitude)),
                                                shape = CircleShape,
                                                ambientColor = auraColors.first(),
                                                spotColor = auraColors.last(),
                                                clip = false
                                            )
                                            .background(
                                                brush = Brush.sweepGradient(colors = auraColors),
                                                shape = CircleShape
                                            )
                                    )
                                }

                                // Dark outer glass lens frame
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .background(Color(0xFF09080B), CircleShape)
                                        .border(
                                            width = if (isRecording) 3.5.dp else 1.5.dp,
                                            brush = if (isRecording) {
                                                Brush.sweepGradient(colors = auraColors)
                                            } else {
                                                androidx.compose.ui.graphics.SolidColor(Color(0xFF322E3A))
                                            },
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Dual Premium camera lenses
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(Color(0xFF16151A), CircleShape)
                                                .border(0.5.dp, Color(0xFF44404E), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(Color(0xFF0D0C0F), CircleShape)
                                                    .border(0.5.dp, if (isRecording) auraColors.first() else Color.DarkGray, CircleShape)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(Color(0xFF16151A), CircleShape)
                                                .border(0.5.dp, Color(0xFF44404E), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .background(Color(0xFF0D0C0F), CircleShape)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Glowing badge hardware telemetry
                            Text(
                                text = if (isRecording) "MONSTER HALO SYNC" else "STANDBY ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 7.5.sp,
                                    letterSpacing = 1.2.sp,
                                    color = if (isRecording) auraColors.first() else Color(0x66FFFFFF)
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = if (isRecording) "LEVEL: ${(amplitude * 100).toInt()}%" else "STANDBY",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isRecording) auraColors.last().copy(alpha = 0.85f) else Color(0xFF8B8599),
                                    fontFamily = FontFamily.Monospace
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Cyber pattern striping details
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(1.5.dp)
                                    .background(
                                        if (isRecording) {
                                            Brush.horizontalGradient(colors = auraColors)
                                        } else {
                                            androidx.compose.ui.graphics.SolidColor(Color(0xFF282531))
                                        }
                                    )
                            )
                        }
                    }
                }

                // Error Banner Display
                micError?.let { err ->
                    Text(
                        text = "Error: $err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                // Premium tactile Start and Pause option controls cockpit (Explicit buttons)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Start Sync Option Button
                    Button(
                        onClick = { isRecording = true },
                        enabled = hasMicPermission && !isRecording,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .shadow(2.dp, RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "START",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Pause Sync Option Button
                    Button(
                        onClick = { isRecording = false },
                        enabled = hasMicPermission && isRecording,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .shadow(2.dp, RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Custom painted pause icon (two lines)
                            androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                                val barWidth = 4.dp.toPx()
                                val barHeight = 12.dp.toPx()
                                val gap = 3.dp.toPx()
                                val brushCol = if (isRecording) Color.White else Color.Gray
                                drawRect(
                                    color = brushCol,
                                    topLeft = Offset(size.width / 2f - barWidth - gap / 2f, size.height / 2f - barHeight / 2f),
                                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                                )
                                drawRect(
                                    color = brushCol,
                                    topLeft = Offset(size.width / 2f + gap / 2f, size.height / 2f - barHeight / 2f),
                                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                                )
                            }
                            Text(
                                text = "PAUSE",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // Status descriptive Label
                Text(
                    text = if (isRecording) "🔴 MONSTER LIGHT INTEGRATION ACTIVE" else "⚪ LIGHT SYNC PAUSED / STANDBY",
                    color = if (isRecording) Color(0xFFBA1A1A) else Color(0xFF49454F),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Microphone realtime custom bar visualizer row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.height(48.dp)
                ) {
                    val barCount = 10
                    (0 until barCount).forEach { index ->
                        val factor = remember(index) { (0.4f + Math.random().toFloat() * 0.6f) }
                        val targetHeight = if (isRecording) (10.dp + 38.dp * (amplitude * factor)) else 6.dp
                        val animatedHeight by animateDpAsState(
                            targetValue = targetHeight,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                            label = "bar_height"
                        )
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(animatedHeight)
                                .background(
                                    Brush.verticalGradient(
                                        colors = if (isRecording) {
                                            auraColors
                                        } else {
                                            listOf(Color(0xFFCAC4D0), Color(0xFFEADDFF))
                                        }
                                    ),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }

        // Adjustable control sliders panel card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Light Parameters Control",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D1B20)
                    )
                )

                // Decibel sensitivity slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Signal Sensitivity", style = MaterialTheme.typography.bodySmall, color = Color(0xFF49454F))
                        Text("${"%.1f".format(sensitivity)}x", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 0.5f..4.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6750A4),
                            activeTrackColor = Color(0xFF6750A4)
                        )
                    )
                }

                // Bloom brightness slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Light Ring Glow", style = MaterialTheme.typography.bodySmall, color = Color(0xFF49454F))
                        Text("${(brightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    }
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = 0.1f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6750A4),
                            activeTrackColor = Color(0xFF6750A4)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.4f))

                // Microphone beat color theme switcher
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Glow Theme Palette",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20)
                    )
                    
                    val colorModes = listOf(
                        Pair("cyber_neon", "Cyber Neon"),
                        Pair("space_violet", "Cosmic Violet"),
                        Pair("magma_flame", "Magma Flame")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colorModes.forEach { (mode, label) ->
                            val isSelected = selectedColorMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF3EDF7),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedColorMode = mode },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF49454F)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun setServiceEnabledState(context: Context, enabled: Boolean) {
    MediaListenerService.toggleListenerState(context, enabled)
}
