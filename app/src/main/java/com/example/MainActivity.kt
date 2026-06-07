package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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

class MainActivity : ComponentActivity() {

    private val isNotificationAccessGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                var selectedTab by remember { mutableStateOf("Home") }
                val isAccessGranted by isNotificationAccessGranted
                
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFEF7FF)),
                    containerColor = Color(0xFFFEF7FF),
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

    var isRhythmSyncEnabled by remember { mutableStateOf(prefs.isRhythmSyncEnabled) }
    var rhythmSyncStyle by remember { mutableStateOf(prefs.rhythmSyncStyle) }
    var rhythmColorMode by remember { mutableStateOf(prefs.rhythmColorMode) }
    var rhythmSensitivity by remember { mutableStateOf(prefs.rhythmSensitivity) }
    var rhythmBrightness by remember { mutableStateOf(prefs.rhythmBrightness) }

    LaunchedEffect(Unit) {
        if (prefs.isListenerEnabled) {
            setServiceEnabledState(context, true)
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "listener_enabled") {
                isListenerToggleEnabled = prefs.isListenerEnabled
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
        val activePlaying = isAccessGranted && isListenerToggleEnabled && mediaInfo.packageName.isNotEmpty()
        
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

        // Dedicated Sound Suite Sync (音律神光 / Music Rhythm) Panel
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
            containerColor = Color(0xFFFFFFFF)
        ),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header with custom light bulb / rhythm graphics
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
                                text = "Sound Suite Sync (音律神光)",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D1B20)
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFEADDFF), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF21005D),
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
                        color = Color(0xFF49454F)
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF6750A4),
                        uncheckedThumbColor = Color(0xFF49454F),
                        uncheckedTrackColor = Color(0xFFF3EDF7)
                    )
                )
            }

            if (isEnabled) {
                // Interactive Simulated physical device mockup displays Monster Halo/Dynamic Light
                Text(
                    text = "AURA PREVIEW (酷玩光效)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF49454F)
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

                // Phone Backplate Mockup Component
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF111012)),
                    contentAlignment = Alignment.Center
                ) {
                    // Mobile outline frame representation
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
                            // Circular Lens Matrix with Neon Halo!
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .background(Color(0xFF131214), CircleShape)
                                    .border(1.dp, Color(0xFF49454F), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPlaying) {
                                    // Simulated Ambient Ray Glow
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
                                    // Saturated low-key inactive outline representation
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .border(1.5.dp, Color(0xFF49454F), CircleShape)
                                    )
                                }

                                // Dark reflective camera glass
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

                HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.5f))

                // Rhythm Synchronization style triggers
                Text(
                    text = "SYNC STYLE (酷玩灯效模式)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF49454F)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "neon_glow" to "律动呼吸",
                        "aurora_wave" to "极光频谱",
                        "pulse" to "幽光瞬闪"
                    ).forEach { styleOpt ->
                        val isSelected = syncStyle == styleOpt.first
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(
                                    if (isSelected) Color(0xFFEADDFF) else Color(0xFFF3EDF7),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFF6750A4) else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onStyleChanged(styleOpt.first) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = styleOpt.second,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Custom aura visualizers color options
                Text(
                    text = "AURA COLORS (炫彩神光方案)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF49454F)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "album_art" to "流光自适应",
                        "cyber_neon" to "冰晶圣歌",
                        "space_violet" to "紫夜幽兰",
                        "magma_flame" to "熔岩魔火"
                    ).forEach { colorOpt ->
                        val isSelected = colorMode == colorOpt.first
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(
                                    if (isSelected) Color(0xFFEADDFF) else Color(0xFFF3EDF7),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFF6750A4) else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onColorModeChanged(colorOpt.first) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = colorOpt.second,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F)
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.5f))

                // Rhythm Reaction Sensitivity
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Frequency Reaction Sensitivity (响应灵敏度)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF49454F)
                            )
                        )
                        Text(
                            text = "${(sensitivity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        )
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = onSensitivityChanged,
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF6750A4),
                            inactiveTrackColor = Color(0xFFEADDFF),
                            thumbColor = Color(0xFF6750A4)
                        )
                    )
                }

                // Rhythm Brightness Intensity
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ambient Glow Brightness (神光亮度)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF49454F)
                            )
                        )
                        Text(
                            text = "${(brightness * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        )
                    }
                    Slider(
                        value = brightness,
                        onValueChange = onBrightnessChanged,
                        valueRange = 0.1f..1.0f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF6750A4),
                            inactiveTrackColor = Color(0xFFEADDFF),
                            thumbColor = Color(0xFF6750A4)
                        )
                    )
                }

            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF3EDF7), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sound Suite Sync is disabled. Enable to activate real-time LED and screen-edge halo visualizations under music beat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF49454F),
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
                            .border(
                                width = if (isRhythmEnabled && mediaInfo.isPlaying) 2.dp else 1.dp,
                                brush = if (isRhythmEnabled && mediaInfo.isPlaying) {
                                    Brush.sweepGradient(colors = auraColors)
                                } else {
                                    androidx.compose.ui.graphics.SolidColor(Color(0xFFCAC4D0))
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
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
        color = Color(0xFFF3EDF7),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFCAC4D0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            BottomTabButton(
                label = "Home",
                isSelected = selectedTab == "Home",
                iconContent = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(
                                if (selectedTab == "Home") Color(0xFFEADDFF) else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Home",
                            tint = if (selectedTab == "Home") Color(0xFF21005D) else Color(0xFF49454F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                onClick = { onTabSelected("Home") }
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
                                if (selectedTab == "Stats") Color(0xFFEADDFF) else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
                            drawLine(
                                color = if (selectedTab == "Stats") Color(0xFF21005D) else Color(0xFF49454F),
                                start = Offset(2.dp.toPx(), size.height - 2.dp.toPx()),
                                end = Offset(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = if (selectedTab == "Stats") Color(0xFF21005D) else Color(0xFF49454F),
                                start = Offset(4.dp.toPx(), size.height - 4.dp.toPx()),
                                end = Offset(4.dp.toPx(), 4.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = if (selectedTab == "Stats") Color(0xFF21005D) else Color(0xFF49454F),
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
                                if (selectedTab == "Logs") Color(0xFFEADDFF) else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
                            val spacing = 5.dp.toPx()
                            drawLine(
                                color = if (selectedTab == "Logs") Color(0xFF21005D) else Color(0xFF49454F),
                                start = Offset(0f, 2.dp.toPx()),
                                end = Offset(size.width, 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = if (selectedTab == "Logs") Color(0xFF21005D) else Color(0xFF49454F),
                                start = Offset(0f, 2.dp.toPx() + spacing),
                                end = Offset(size.width * 0.7f, 2.dp.toPx() + spacing),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = if (selectedTab == "Logs") Color(0xFF21005D) else Color(0xFF49454F),
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

fun setServiceEnabledState(context: Context, enabled: Boolean) {
    MediaListenerService.toggleListenerState(context, enabled)
}
