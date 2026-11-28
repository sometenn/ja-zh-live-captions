package com.sometenn.callcaptions.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sometenn.callcaptions.core.AudioSource
import com.sometenn.callcaptions.core.CaptionFace
import com.sometenn.callcaptions.core.CaptionPresentation
import com.sometenn.callcaptions.core.CaptionDisplayMode
import com.sometenn.callcaptions.core.CaptionTextLayout
import com.sometenn.callcaptions.core.OverlayAppearance
import com.sometenn.callcaptions.core.OverlayBanner
import com.sometenn.callcaptions.core.OverlayFrame
import com.sometenn.callcaptions.core.Provider
import com.sometenn.callcaptions.core.Session
import com.sometenn.callcaptions.core.SessionState
import com.sometenn.callcaptions.overlay.OverlayUiState

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    tileRequests: Int = 0,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle()
    val mediaProjectionRequestId by viewModel.mediaProjectionRequestId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showKeySheet by rememberSaveable { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var tileOverlay by rememberSaveable { mutableStateOf(false) }
    var tileNeedsKey by rememberSaveable { mutableStateOf(false) }
    var pendingCaptureMode by rememberSaveable { mutableStateOf<Session.Mode?>(null) }

    val appContext = context.applicationContext
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.finishExport(uri, appContext.contentResolver) { message ->
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val mode = pendingCaptureMode
        pendingCaptureMode = null
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) tileOverlay = false
        if (micGranted && mode != null) {
            when (mode) {
                Session.Mode.TRANSLATE -> viewModel.beginTranslate()
                Session.Mode.PROBE -> viewModel.beginProbe()
            }
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    LaunchedEffect(mediaProjectionRequestId) {
        if (mediaProjectionRequestId != null) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            if (manager != null) {
                mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                viewModel.onMediaProjectionResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.clearOverlayNotice()
            viewModel.startOverlay()
        }
    }

    fun startWithPermissions(mode: Session.Mode) {
        // Both microphone and Android 10+ AudioPlaybackCapture require RECORD_AUDIO,
        // and Android 13+ foreground services benefit from POST_NOTIFICATIONS.
        val required = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (required.isNotEmpty()) {
            pendingCaptureMode = mode
            audioPermissionLauncher.launch(required.toTypedArray())
            return
        }
        when (mode) {
            Session.Mode.TRANSLATE -> viewModel.beginTranslate()
            Session.Mode.PROBE -> viewModel.beginProbe()
        }
    }

    fun requestOrStartOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        viewModel.startOverlay()
    }

    LaunchedEffect(tileRequests) {
        if (tileRequests == 0) return@LaunchedEffect
        when (sessionState.stage) {
            Session.Stage.IDLE -> if (sessionState.hasKey) {
                tileOverlay = true
                startWithPermissions(Session.Mode.TRANSLATE)
            } else {
                tileNeedsKey = true
                showKeySheet = true
            }
            Session.Stage.LISTENING -> if (sessionState.mode == Session.Mode.TRANSLATE && !overlayState.active) requestOrStartOverlay()
            else -> Unit
        }
    }

    LaunchedEffect(sessionState.stage) {
        if (tileOverlay && sessionState.stage == Session.Stage.LISTENING) {
            tileOverlay = false
            if (!overlayState.active && sessionState.mode == Session.Mode.TRANSLATE) requestOrStartOverlay()
        }
        if (sessionState.stage == Session.Stage.STOPPING) tileOverlay = false
        if (sessionState.stage == Session.Stage.IDLE) {
            viewModel.refreshPreviewIfIdle()
        }
    }

    HomeScreen(
        sessionState = sessionState,
        overlayState = overlayState,
        onOpenKeys = { showKeySheet = true },
        onOpenAppearance = { showAppearanceSheet = true },
        onSelectProvider = viewModel::selectProvider,
        onSelectSource = viewModel::selectSource,
        onSelectDisplayMode = viewModel::selectDisplayMode,
        onStartTranslate = {
            if (sessionState.hasKey) {
                startWithPermissions(Session.Mode.TRANSLATE)
            } else {
                showKeySheet = true
            }
        },
        onStartProbe = { startWithPermissions(Session.Mode.PROBE) },
        onFinish = viewModel::finish,
        onSaveTranscript = {
            viewModel.prepareExport()
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            exportLauncher.launch("字幕记录-$stamp.txt")
        },
        onToggleOverlay = {
            if (overlayState.active) {
                viewModel.stopOverlay()
            } else {
                requestOrStartOverlay()
            }
        },
    )

    if (showKeySheet) {
        KeySettingsSheet(
            provider = sessionState.provider,
            hasKey = sessionState.hasKey,
            onSaveKey = { key ->
                viewModel.saveKey(key).onSuccess {
                    if (tileNeedsKey && key.isNotBlank()) {
                        tileNeedsKey = false
                        tileOverlay = true
                        startWithPermissions(Session.Mode.TRANSLATE)
                    }
                }
            },
            onDismiss = {
                showKeySheet = false
                tileNeedsKey = false
            },
        )
    }

    if (showAppearanceSheet) {
        AppearanceSheet(
            appearance = overlayState.appearance,
            previewFrame = sessionState.frame,
            displayMode = sessionState.displayMode,
            overlayActive = overlayState.active,
            onUpdate = viewModel::updateAppearance,
            onReset = viewModel::resetAppearance,
            onDismiss = { showAppearanceSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sessionState: SessionState,
    overlayState: OverlayUiState,
    onOpenKeys: () -> Unit,
    onOpenAppearance: () -> Unit,
    onSelectProvider: (Provider) -> Unit,
    onSelectSource: (AudioSource) -> Unit,
    onSelectDisplayMode: (CaptionDisplayMode) -> Unit,
    onStartTranslate: () -> Unit,
    onStartProbe: () -> Unit,
    onFinish: () -> Unit,
    onSaveTranscript: () -> Unit,
    onToggleOverlay: () -> Unit,
) {
    val isReconnecting = sessionState.connectionState is Session.ConnectionState.Reconnecting
    val isListening = sessionState.stage == Session.Stage.LISTENING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日语实时字幕") },
                actions = {
                    TextButton(onClick = onOpenAppearance) {
                        Text("字幕外观")
                    }
                    TextButton(
                        onClick = onOpenKeys,
                        enabled = sessionState.stage == Session.Stage.IDLE,
                    ) {
                        Text("密钥")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "日语",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "简体中文",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(if (isListening) "实时字幕" else "字幕预览", style = MaterialTheme.typography.titleSmall)
            OverlayPreviewCard(
                frame = sessionState.frame,
                displayMode = sessionState.displayMode,
                appearance = overlayState.appearance,
            )

            OutlinedButton(onClick = onSaveTranscript, enabled = sessionState.hasTranscript) { Text("保存记录") }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("会话设置", style = MaterialTheme.typography.titleSmall)
                    SessionChoice("翻译服务", sessionState.provider, Provider.entries, { it.rawValue },
                        sessionState.stage != Session.Stage.PICKING && sessionState.stage != Session.Stage.STOPPING, onSelectProvider)
                    SessionChoice("收音来源", sessionState.source, AudioSource.entries, { it.title },
                        sessionState.stage == Session.Stage.IDLE, onSelectSource)
                    SessionChoice("字幕显示", sessionState.displayMode, CaptionDisplayMode.entries, { it.title },
                        sessionState.stage != Session.Stage.STOPPING, onSelectDisplayMode)
                }
            }
            if (sessionState.switchNotice.isNotEmpty()) Text(sessionState.switchNotice)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ap = overlayState.appearance
                Text(
                    text = "缩放 ${ap.fontScalePercent}% · 宽度 ${ap.widthPercent}% · ${ap.maxLines} 行 · 不透明度 ${ap.backgroundAlphaPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAppearance) {
                    Text("调整外观")
                }
            }

            // Floating overlay controls when listening
            if (isListening) {
                Button(
                    onClick = onToggleOverlay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (overlayState.active) "收起悬浮字幕" else "开启悬浮字幕")
                }
                if (overlayState.active) {
                    Text(
                        text = "悬浮字幕已开启，现在可以切回通话或视频，并按住字幕窗拖动位置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (overlayState.notice.isNotEmpty()) {
                    Text(
                        text = overlayState.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Status row
            Text(
                text = (if (isReconnecting) "↻ " else if (isListening) "● " else "○ ") + sessionState.status,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isReconnecting) Color(0xFFEA580C) else MaterialTheme.colorScheme.onSurface,
            )

            // Action buttons
            if (sessionState.stage == Session.Stage.IDLE) {
                Button(
                    onClick = onStartTranslate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (sessionState.hasKey) "开始翻译" else "设置 ${sessionState.provider.rawValue} 密钥",
                    )
                }

                OutlinedButton(
                    onClick = onStartProbe,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (sessionState.source == AudioSource.SYSTEM) "测试通话声音" else "测试麦克风",
                    )
                }

                Text(
                    text = if (sessionState.source == AudioSource.MICROPHONE) {
                        "对着麦克风说话，观察音量条是否跳动。测试仅在本机运行。"
                    } else {
                        "测试时戴上耳机，在系统窗口中允许共享整个屏幕，开启悬浮字幕后再切回通话。请对方说话，观察音量条是否跳动。声音测试仅在本机运行；若目标应用禁止被捕获播放音频，音量条可能保持静止。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = onFinish,
                    enabled = sessionState.stage != Session.Stage.STOPPING,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (sessionState.source == AudioSource.SYSTEM) "结束共享" else "结束收音")
                }
            }

            Text(
                text = if (sessionState.source == AudioSource.MICROPHONE) {
                    "翻译时，麦克风收到的声音会直接发给 ${sessionState.provider.rawValue}。"
                } else {
                    "翻译时，系统共享的声音会直接发给 ${sessionState.provider.rawValue}。其他正在播放的应用也可能被听到。字幕仅在本次共享期间显示。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> SessionChoice(label: String, selected: T, options: List<T>, title: (T) -> String,
                              enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text(title(selected) + "　▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(title(option)) }, onClick = {
                        expanded = false
                        onSelect(option)
                    })
                }
            }
        }
    }
}

@Composable
fun OverlayPreviewCard(
    frame: OverlayFrame,
    displayMode: CaptionDisplayMode,
    appearance: OverlayAppearance = OverlayAppearance.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val captionFont = when (appearance.face) {
        CaptionFace.SYSTEM -> FontFamily.SansSerif
        CaptionFace.SERIF -> FontFamily.Serif
        CaptionFace.MONO -> FontFamily.Monospace
    }
    val bgColor = Color(0xFF0B2124).copy(alpha = appearance.backgroundAlphaFloat)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = displayMode.accessibilityLabel },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            frame.banner?.let { banner ->
                Text(
                    text = banner.text,
                    color = when (banner.tone) {
                        OverlayBanner.Tone.WARNING -> Color(0xFFFB923C)
                        OverlayBanner.Tone.INFO -> Color(0xFF93C5FD)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            when (val style = if (frame == CaptionPresentation.idleFrame(displayMode)) {
                if (displayMode == CaptionDisplayMode.BILINGUAL) OverlayFrame.Style.Bilingual("今日はどんな一日でしたか？", "你今天过得怎么样？")
                else OverlayFrame.Style.ChineseOnly("你今天过得怎么样？", false)
            } else frame.style) {
                is OverlayFrame.Style.Bilingual -> {
                    val topBudget = appearance.charBudget(isChineseOnly = false, isTopJapanese = true)
                    val bottomBudget = appearance.charBudget(isChineseOnly = false, isTopJapanese = false)
                    if (style.top.isNotEmpty()) {
                        Text(
                            text = CaptionTextLayout.fitRecentPhrase(style.top) { it.length <= topBudget },
                            color = Color(0xFFD2E0F0),
                            fontFamily = captionFont,
                            fontSize = appearance.topTextSizeSp.sp,
                            lineHeight = (appearance.topTextSizeSp * 1.3f).sp,
                            maxLines = appearance.maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = CaptionTextLayout.fitRecentPhrase(style.bottom) { it.length <= bottomBudget },
                        color = Color(appearance.ink.argb),
                        fontFamily = captionFont,
                        fontSize = appearance.bottomTextSizeSp.sp,
                        lineHeight = (appearance.bottomTextSizeSp * 1.3f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = appearance.maxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                is OverlayFrame.Style.ChineseOnly -> {
                    val budget = appearance.charBudget(isChineseOnly = true)
                    Text(
                        text = CaptionTextLayout.fitRecentPhrase(style.text) { it.length <= budget },
                        color = if (style.isPlaceholder) Color(0xAAD2E0F0) else Color(appearance.ink.argb),
                        fontFamily = captionFont,
                        fontSize = appearance.chineseOnlyTextSizeSp.sp,
                        lineHeight = (appearance.chineseOnlyTextSizeSp * 1.3f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = appearance.chineseOnlyMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is OverlayFrame.Style.Probe -> {
                    Text(
                        text = style.status,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = style.meter,
                        color = Color(0xFF4ADE80),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
