package com.looplooptech.audioscript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looplooptech.audioscript.core.AsrModelSpec
import com.looplooptech.audioscript.core.EndpointProfile
import com.looplooptech.audioscript.core.ModelCatalog
import com.looplooptech.audioscript.core.ModelInstallPhase
import com.looplooptech.audioscript.core.ModelInstallState
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.SettingsData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: SettingsData,
    overlayAllowed: Boolean,
    modelStates: Map<String, ModelInstallState>,
    canManageModels: Boolean,
    onDismiss: () -> Unit,
    onUpdate: ((SettingsData) -> SettingsData) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onInstallModel: (AsrModelSpec) -> Unit,
    onCancelModel: (AsrModelSpec) -> Unit,
    onDeleteModel: (AsrModelSpec) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.Surface1,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(Copy.SettingsTitle, color = Palette.Ink0, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            SectionTitle(Copy.EndpointTitle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileChip(
                    label = Copy.EndpointInterp,
                    selected = settings.endpointProfile == EndpointProfile.INTERPRETATION,
                ) { onUpdate { it.copy(endpointProfile = EndpointProfile.INTERPRETATION) } }
                ProfileChip(
                    label = Copy.EndpointDict,
                    selected = settings.endpointProfile == EndpointProfile.DICTATION,
                ) { onUpdate { it.copy(endpointProfile = EndpointProfile.DICTATION) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(Copy.EndpointHint, color = Palette.Ink2, fontSize = 11.sp, lineHeight = 16.sp)

            Spacer(Modifier.height(20.dp))
            SectionTitle(Copy.OverlayTitle)

            if (!overlayAllowed) {
                Card(
                    title = Copy.OverlayPermission,
                    action = Copy.Grant,
                    onClick = onRequestOverlayPermission,
                )
                Spacer(Modifier.height(10.dp))
            }

            SliderRow(
                label = Copy.FontSize,
                value = settings.fontSize.toFloat(),
                valueText = "${settings.fontSize} px",
                range = 12f..34f,
                steps = 21,
            ) { value -> onUpdate { it.copy(fontSize = value.toInt()) } }

            SliderRow(
                label = Copy.Opacity,
                value = settings.opacity.toFloat(),
                valueText = "${settings.opacity}%",
                range = 0f..100f,
                steps = 19,
            ) { value -> onUpdate { it.copy(opacity = value.toInt()) } }

            SliderRow(
                label = Copy.Lines,
                value = settings.maxLines.toFloat(),
                valueText = "${settings.maxLines} 行",
                range = 1f..6f,
                steps = 4,
            ) { value -> onUpdate { it.copy(maxLines = value.toInt()) } }

            SwitchRow(
                label = Copy.Glow,
                checked = settings.glow,
            ) { onUpdate { cfg -> cfg.copy(glow = !cfg.glow) } }

            SwitchRow(
                label = Copy.LockOverlay,
                checked = settings.overlayLocked,
            ) { onUpdate { cfg -> cfg.copy(overlayLocked = !cfg.overlayLocked) } }

            Spacer(Modifier.height(20.dp))
            SectionTitle(Copy.ModelTitle)
            Text(
                "识别模型由「源语言」自动选择：中文 / English 用内置模型；日本語 用可下载模型。",
                color = Palette.Ink1,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))

            BuiltinModelRow()

            ModelCatalog.downloadable.forEach { spec ->
                val installedOnDisk = remember(spec.id) {
                    ModelStore.isInstalled(context, spec)
                }
                val state = modelStates[spec.id] ?: if (installedOnDisk) {
                    ModelInstallState.installed()
                } else {
                    ModelInstallState.notInstalled()
                }
                Spacer(Modifier.height(8.dp))
                DownloadableModelRow(
                    spec = spec,
                    bundled = remember(spec.id) { ModelStore.isBundled(context, spec) },
                    state = state,
                    actionsEnabled = canManageModels,
                    onInstall = { onInstallModel(spec) },
                    onCancel = { onCancelModel(spec) },
                    onDelete = { onDeleteModel(spec) },
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "翻译：ML Kit 本地翻译模型（首次使用需下载，之后离线）",
                color = Palette.Ink2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle(Copy.AboutTitle)
            Text(Copy.AboutBody, color = Palette.Ink1, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(Copy.Repo, color = Palette.Accent, fontSize = 12.sp)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Copy.Export, primary = true, onClick = onExport)
                ActionButton(Copy.Clear, primary = false, onClick = onClear)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Palette.Ink2,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ProfileChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.SurfaceSelected else Palette.Surface2)
            .border(
                1.dp,
                if (selected) Palette.Accent else Palette.Border,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected) Palette.Ink0 else Palette.Ink1, fontSize = 13.sp)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Ink1, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = Palette.Ink2, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Palette.Accent,
                activeTrackColor = Palette.Accent,
                inactiveTrackColor = Palette.Surface2,
            ),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Ink1, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Palette.Accent,
                uncheckedTrackColor = Palette.Surface2,
            ),
        )
    }
}

@Composable
private fun BuiltinModelRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ModelCatalog.builtinZhEn.label,
                color = Palette.Ink0,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "中文 / English · 随安装包内置",
                color = Palette.Ink2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Text("已内置", color = Palette.Success, fontSize = 12.sp)
    }
}

@Composable
private fun DownloadableModelRow(
    spec: AsrModelSpec,
    bundled: Boolean,
    state: ModelInstallState,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface2)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    spec.label,
                    color = Palette.Ink0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    spec.note,
                    color = Palette.Ink2,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            when {
                bundled -> Text("已内置", color = Palette.Success, fontSize = 12.sp)
                state.phase == ModelInstallPhase.INSTALLED ->
                    Text("已安装", color = Palette.Success, fontSize = 12.sp)
            }
        }

        when (state.phase) {
            ModelInstallPhase.DOWNLOADING, ModelInstallPhase.EXTRACTING -> {
                Spacer(Modifier.height(10.dp))
                ModelProgressBar(state.progress)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        installStatusText(state),
                        color = Palette.Ink1,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction("取消", enabled = true, onClick = onCancel)
                }
            }

            ModelInstallPhase.FAILED -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.message ?: "下载失败，请检查网络后重试",
                    color = Palette.Warn,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    ModelAction("重试", enabled = actionsEnabled, accent = true, onClick = onInstall)
                }
            }

            ModelInstallPhase.INSTALLED -> {
                if (!bundled) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已下载到本机，选择「日本語」后自动使用",
                            color = Palette.Ink2,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        ModelAction("删除", enabled = actionsEnabled, onClick = onDelete)
                    }
                }
            }

            ModelInstallPhase.NOT_INSTALLED -> {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "下载约 ${formatBytes(spec.downloadBytes)}",
                        color = Palette.Ink2,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction("下载", enabled = actionsEnabled, accent = true, onClick = onInstall)
                }
            }
        }

        if (!actionsEnabled &&
            (state.phase == ModelInstallPhase.NOT_INSTALLED ||
                state.phase == ModelInstallPhase.FAILED)
        ) {
            Spacer(Modifier.height(6.dp))
            Text("停止识别后可以下载 / 删除模型", color = Palette.Ink2, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ModelProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.Surface1),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(Palette.Accent),
        )
    }
}

@Composable
private fun ModelAction(
    label: String,
    enabled: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = when {
            !enabled -> Palette.Ink2
            accent -> Palette.Accent
            else -> Palette.Ink1
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

private fun installStatusText(state: ModelInstallState): String = when (state.phase) {
    ModelInstallPhase.DOWNLOADING -> buildString {
        append("下载中 ${(state.progress * 100).toInt()}%")
        if (state.total > 0) {
            append(" · ${formatBytes(state.received)} / ${formatBytes(state.total)}")
        }
    }

    ModelInstallPhase.EXTRACTING -> "正在解压 ${(state.progress * 100).toInt()}%"
    else -> ""
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "${bytes / 1_000_000_000L} GB"
    bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
    else -> "${bytes / 1_000L} KB"
}

@Composable
private fun Card(title: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceSelected)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.Ink0, fontSize = 12.sp, modifier = Modifier.weight(1f), lineHeight = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(action, color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Palette.Accent else Palette.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
