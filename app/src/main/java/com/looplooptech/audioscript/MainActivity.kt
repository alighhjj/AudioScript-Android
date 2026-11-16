package com.looplooptech.audioscript

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looplooptech.audioscript.core.Hub
import com.looplooptech.audioscript.core.ModelCatalog
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.RunStatus
import com.looplooptech.audioscript.core.SourceKind
import com.looplooptech.audioscript.export.MarkdownExport
import com.looplooptech.audioscript.service.CaptureService
import com.looplooptech.audioscript.ui.AudioScriptTheme
import com.looplooptech.audioscript.ui.HomeScreen
import com.looplooptech.audioscript.ui.SettingsSheet

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private var pendingKind: SourceKind? = null
    private var pendingOverlayEnable = false

    /** 悬浮窗权限是系统设置项，只能靠 onResume 回来重新读，所以做成 Compose 状态。 */
    private var overlayAllowed by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val kind = pendingKind
        pendingKind = null
        if (kind == null) return@registerForActivityResult

        val micGranted = result[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
        if (!micGranted) {
            Hub.setNotice("没有录音权限，采集无法开始")
            return@registerForActivityResult
        }
        proceed(kind)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.startSystem(this, result.resultCode, data)
        } else {
            Hub.setNotice("已取消系统声音授权")
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 结果以 onResume 里的 canDrawOverlays 为准
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(applicationContext)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        setContent {
            AudioScriptTheme {
                val segments by Hub.segments.collectAsStateWithLifecycle()
                val status by Hub.status.collectAsStateWithLifecycle()
                val source by Hub.activeSource.collectAsStateWithLifecycle()
                val translationStatus by Hub.translationStatus.collectAsStateWithLifecycle()
                val level by Hub.level.collectAsStateWithLifecycle()
                val settings by Hub.settings.data.collectAsStateWithLifecycle()
                val modelStates by Hub.models.states.collectAsStateWithLifecycle()
                val notice by Hub.notice.collectAsStateWithLifecycle()

                var showSettings by remember { mutableStateOf(false) }

                LaunchedEffect(notice) {
                    val text = notice ?: return@LaunchedEffect
                    Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                    Hub.consumeNotice()
                }

                HomeScreen(
                    segments = segments,
                    status = status,
                    source = source,
                    translationStatus = translationStatus,
                    level = level,
                    settings = settings,
                    onStart = { requestStart(settings.sourceKind) },
                    onStop = { CaptureService.stop(this@MainActivity) },
                    onOpenSettings = {
                        Hub.models.refresh()
                        showSettings = true
                    },
                    onSourceChange = { kind -> Hub.settings.update { it.copy(sourceKind = kind) } },
                    onTranslateToggle = { enabled ->
                        Hub.settings.update { it.copy(translateEnabled = enabled) }
                    },
                    onLanguagesChange = { src, tgt ->
                        Hub.settings.update { it.copy(sourceLang = src, targetLang = tgt) }
                        if (src == ModelCatalog.LANG_JA &&
                            !ModelStore.isInstalled(this@MainActivity, ModelCatalog.japanese)
                        ) {
                            Hub.setNotice("日文识别需要先下载日文模型，约 169 MB")
                            Hub.models.refresh()
                            showSettings = true
                        }
                    },
                    onOverlayToggle = { toggleOverlay() },
                    onExport = { exportMarkdown() },
                )

                if (showSettings) {
                    SettingsSheet(
                        settings = settings,
                        overlayAllowed = overlayAllowed,
                        modelStates = modelStates,
                        canManageModels = status == RunStatus.IDLE,
                        onDismiss = { showSettings = false },
                        onUpdate = { block -> Hub.settings.update(block) },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onInstallModel = { Hub.models.install(it) },
                        onCancelModel = { Hub.models.cancel(it) },
                        onDeleteModel = { Hub.models.uninstall(it) },
                        onExport = {
                            showSettings = false
                            exportMarkdown()
                        },
                        onClear = {
                            Hub.clear()
                            showSettings = false
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (overlayAllowed) {
                Hub.settings.update { it.copy(overlayVisible = true) }
            } else {
                Hub.setNotice("未授予「显示在其他应用上层」权限，悬浮字幕无法显示")
            }
        }
    }

    private fun requestStart(kind: SourceKind) {
        val spec = ModelCatalog.forLanguage(Hub.settings.current.sourceLang)
        if (!ModelStore.isInstalled(this, spec)) {
            Hub.setNotice("${spec.label} 尚未下载：请到「设置 → 模型」下载后再开始")
            return
        }

        if (kind == SourceKind.SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Hub.setNotice("系统声音采集需要 Android 10 及以上，这台设备请改用「麦克风」")
            return
        }

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            proceed(kind)
        } else {
            pendingKind = kind
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun proceed(kind: SourceKind) {
        when (kind) {
            SourceKind.SYSTEM -> {
                if (Hub.status.value == RunStatus.RUNNING) {
                    Hub.setNotice("已经在聆听了")
                    return
                }
                val intent = projectionManager.createScreenCaptureIntent()
                projectionLauncher.launch(intent)
            }

            SourceKind.MIC -> CaptureService.startMic(this)
        }
    }

    private fun toggleOverlay() {
        val current = Hub.settings.current
        if (!current.overlayVisible && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        Hub.settings.update { it.copy(overlayVisible = !it.overlayVisible) }
        if (!current.overlayVisible && Hub.status.value != RunStatus.RUNNING) {
            Hub.setNotice("悬浮字幕已开启，开始聆听后就会出现在屏幕上")
        }
    }

    private fun requestOverlayPermission() {
        pendingOverlayEnable = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { overlaySettingsLauncher.launch(intent) }
            .onFailure {
                pendingOverlayEnable = false
                Hub.setNotice("这台设备没有开放悬浮窗权限设置")
            }
    }

    private fun exportMarkdown() {
        val cfg = Hub.settings.current
        val segments = Hub.segments.value
        if (segments.none { it.isFinal }) {
            Hub.setNotice("还没有定稿的句子可以导出")
            return
        }
        val markdown = MarkdownExport.build(segments, cfg.sourceLang, cfg.targetLang)
        MarkdownExport.share(this, markdown)
    }
}
