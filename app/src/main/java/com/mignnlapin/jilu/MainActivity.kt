package com.mignnlapin.jilu

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mignnlapin.jilu.ui.AmapRouteMap
import com.mignnlapin.jilu.ui.theme.JiLuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JiLuTheme {
                JiLuApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JiLuApp() {
    val context = LocalContext.current
    val state by TrackingRepository.state.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme

    var permissionRefresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permissionRefresh++
        onPauseOrDispose { }
    }
    val hasLocationPermission = remember(permissionRefresh) {
        context.hasLocationPermission()
    }

    var startAfterGrant by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionRefresh++
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && startAfterGrant) {
            TrackingService.start(context)
        }
        startAfterGrant = false
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryOptimized by remember {
        mutableStateOf(!powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    LifecycleResumeEffect(Unit) {
        batteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    var settingsVersion by remember { mutableIntStateOf(0) }
    val amapApiKey = remember(settingsVersion) { AppSettings.getAmapApiKey(context) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var mapTouching by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateManager.Info?>(null) }
    val scope = rememberCoroutineScope()
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.2"
    }

    LaunchedEffect(Unit) {
        if (!TrackingRepository.state.value.isTracking &&
            TrackingRepository.state.value.route.isEmpty()
        ) {
            val path = AppSettings.getLastTrackPath(context)
            if (path != null) {
                val restored = withContext(Dispatchers.IO) {
                    TrackCsvReader.read(File(path))
                }
                if (restored != null) {
                    TrackingRepository.restore(path, restored)
                }
            }
        }
    }

    var nowRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isTracking) {
        while (state.isTracking) {
            nowRealtime = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }
    val elapsedSeconds = if (state.isTracking) {
        ((nowRealtime - state.startElapsedRealtime) / 1000L).coerceAtLeast(0L)
    } else {
        state.elapsedSeconds.toLong()
    }

    fun startRecording() {
        if (hasLocationPermission) {
            TrackingService.start(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startAfterGrant = true
            permissionLauncher.launch(requiredPermissions())
        }
    }

    val subtitle = when {
        state.isTracking && state.hasFix ->
            String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude)

        state.isTracking -> "正在获取卫星定位…"
        state.lastFilePath != null -> "上次记录已保存"
        else -> "点击下方按钮开始记录"
    }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "JiLu · 轨迹记录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    StatusPill(active = state.isTracking)
                    IconButton(onClick = { showKeyDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "地图 API 设置"
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState(), enabled = !mapTouching)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SpeedCard(
                speedText = formatKmh(state.speedMps),
                subtitle = subtitle,
                active = state.isTracking
            )

            Button(
                onClick = {
                    if (state.isTracking) TrackingService.stop(context) else startRecording()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
                colors = if (state.isTracking) {
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = if (state.isTracking) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isTracking) "结束记录" else "开始记录",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            RouteMapCard(
                apiKey = amapApiKey,
                route = state.route,
                tracking = state.isTracking,
                onConfigureKey = { showKeyDialog = true },
                onMapTouchChanged = { mapTouching = it }
            )

            if (!hasLocationPermission) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colorScheme.secondaryContainer
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "需要定位权限",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "应用需要访问位置信息，才能在后台记录轨迹与速度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { permissionLauncher.launch(requiredPermissions()) }
                        ) {
                            Text("立即授权")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "距离",
                    value = formatDistance(state.distanceMeters),
                    unit = "km",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "时长",
                    value = formatDuration(elapsedSeconds),
                    unit = "",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "平均速度",
                    value = formatKmh(state.averageSpeedMps),
                    unit = "km/h",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "最高速度",
                    value = formatKmh(state.maxSpeedMps),
                    unit = "km/h",
                    modifier = Modifier.weight(1f)
                )
            }

            state.error?.let { message ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colorScheme.errorContainer
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onErrorContainer
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "本次记录",
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface
                    )
                    DetailRow(
                        label = "定位精度",
                        value = state.accuracyMeters?.let {
                            String.format(Locale.US, "± %.1f m", it)
                        } ?: "—"
                    )
                    DetailRow(
                        label = "海拔",
                        value = state.altitudeMeters?.let {
                            String.format(Locale.US, "%.1f m", it)
                        } ?: "—"
                    )
                    DetailRow(label = "数据点", value = state.points.toString())
                    DetailRow(label = "步数", value = state.steps.toString())
                    DetailRow(
                        label = "定位来源",
                        value = state.provider?.uppercase(Locale.US) ?: "—"
                    )
                }
            }

            val savedPath = state.lastFilePath
            if (!state.isTracking && savedPath != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colorScheme.tertiaryContainer
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "记录已保存",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = savedPath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onTertiaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { shareTrack(context, savedPath) }) {
                            Text("分享 CSV")
                        }
                    }
                }
            }

            if (batteryOptimized) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "后台持续记录",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "建议关闭电池优化，避免系统在息屏后中断定位记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { openBatterySettings(context) }) {
                            Text("电池优化设置")
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "版本 $appVersion",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSurface
                        )
                        Text(
                            "从 GitHub 发布页检查更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        enabled = !checkingUpdate,
                        onClick = {
                            checkingUpdate = true
                            scope.launch {
                                val info = UpdateManager.checkForUpdate(appVersion)
                                checkingUpdate = false
                                if (info != null) {
                                    updateInfo = info
                                } else {
                                    val error = UpdateManager.lastError
                                    Toast.makeText(
                                        context,
                                        if (error != null) "检查更新失败：$error" else "已是最新版本",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Text(if (checkingUpdate) "检查中…" else "检查更新")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showKeyDialog) {
        AmapKeyDialog(
            initialKey = amapApiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                AppSettings.setAmapApiKey(context, key)
                settingsVersion++
                showKeyDialog = false
            }
        )
    }

    val pendingUpdate = updateInfo
    if (pendingUpdate != null) {
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本 ${pendingUpdate.version}") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = pendingUpdate.notes.ifBlank { "有新的版本可用，建议下载更新。" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateInfo = null
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(pendingUpdate.url))
                            )
                        }
                    }
                ) { Text("前往下载") }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun StatusPill(active: Boolean) {
    val container = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(shape = CircleShape, color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(content)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (active) "记录中" else "待机",
                style = MaterialTheme.typography.labelMedium,
                color = content
            )
        }
    }
}

@Composable
private fun SpeedCard(speedText: String, subtitle: String, active: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (active) "实时速度" else "速度",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = speedText,
                    fontSize = 68.sp,
                    lineHeight = 68.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onPrimaryContainer
                )
                Text(
                    text = "km/h",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun RouteMapCard(
    apiKey: String,
    route: List<TrackPoint>,
    tracking: Boolean,
    onConfigureKey: () -> Unit,
    onMapTouchChanged: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colorScheme.surfaceContainerLow
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "路线地图",
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            apiKey.isBlank() -> "填写高德 Key 后显示路线"
                            route.isEmpty() -> "开始记录后实时绘制轨迹"
                            tracking -> "正在记录 · ${route.size} 个地图点"
                            else -> "已载入 · ${route.size} 个地图点"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onConfigureKey) {
                    Text(if (apiKey.isBlank()) "填写 Key" else "更换 Key")
                }
            }

            if (apiKey.isBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .height(160.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "尚未配置高德地图 API Key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "在 open.amap.com 创建 Android 应用，绑定当前包名与签名 SHA1。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onConfigureKey) { Text("填写 API Key") }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .height(260.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    AmapRouteMap(
                        apiKey = apiKey,
                        route = route,
                        tracking = tracking,
                        lineColor = colorScheme.primary.toArgb(),
                        modifier = Modifier.fillMaxSize(),
                        onTouchActive = onMapTouchChanged
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.88f)
                    ) {
                        Text(
                            text = when {
                                tracking -> "实时路线"
                                route.isEmpty() -> "暂无轨迹"
                                else -> "历史路线"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, monospace: Boolean = false) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "$label（点击复制）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
private fun AmapKeyDialog(
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(initialKey) }
    val sha1 = remember { signingSha1(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高德地图 API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Android Key") },
                    placeholder = { Text("在高德开放平台获取") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                CopyRow(label = "包名", value = context.packageName)
                CopyRow(label = "签名 SHA1", value = sha1, monospace = true)
                Text(
                    text = "请在 open.amap.com 创建 Android 平台应用，绑定以上包名与 SHA1 后填入 Key。地图服务由高德提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun signingSha1(context: Context): String {
    return runCatching {
        val packageManager = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            info.signatures?.firstOrNull()
        }
        val bytes = signature?.toByteArray() ?: return@runCatching "未知"
        MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString(":") { String.format(Locale.US, "%02X", it) }
    }.getOrDefault("未知")
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
}.toTypedArray()

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun openBatterySettings(context: Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(request) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private fun shareTrack(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享记录文件"))
    }
}

private fun formatKmh(metersPerSecond: Double): String =
    String.format(Locale.US, "%.1f", (metersPerSecond * 3.6).coerceAtLeast(0.0))

private fun formatDistance(meters: Double): String =
    String.format(Locale.US, "%.2f", (meters / 1000.0).coerceAtLeast(0.0))

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
