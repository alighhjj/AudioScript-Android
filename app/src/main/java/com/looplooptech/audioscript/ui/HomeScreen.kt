package com.looplooptech.audioscript.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looplooptech.audioscript.core.RunStatus
import com.looplooptech.audioscript.core.Segment
import com.looplooptech.audioscript.core.SettingsData
import com.looplooptech.audioscript.core.SourceKind
import com.looplooptech.audioscript.core.TranslationState
import com.looplooptech.audioscript.core.TranslationStatus
import java.util.Locale

@Composable
fun HomeScreen(
    segments: List<Segment>,
    status: RunStatus,
    source: SourceKind?,
    translationStatus: TranslationStatus,
    level: Float,
    settings: SettingsData,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onSourceChange: (SourceKind) -> Unit,
    onTranslateToggle: (Boolean) -> Unit,
    onLanguagesChange: (String, String) -> Unit,
    onOverlayToggle: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Surface0)
            .statusBarsPadding(),
    ) {
        Header(
            status = status,
            translationStatus = translationStatus,
            level = level,
            onOpenSettings = onOpenSettings,
        )

        Box(modifier = Modifier.weight(1f)) {
            if (segments.isEmpty()) {
                EmptyState(status = status)
            } else {
                TranscriptList(segments = segments)
            }
        }

        ControlPanel(
            status = status,
            source = source ?: settings.sourceKind,
            level = level,
            settings = settings,
            onStart = onStart,
            onStop = onStop,
            onSourceChange = onSourceChange,
            onTranslateToggle = onTranslateToggle,
            onLanguagesChange = onLanguagesChange,
            onOverlayToggle = onOverlayToggle,
            onExport = onExport,
        )
    }
}

@Composable
private fun Header(
    status: RunStatus,
    translationStatus: TranslationStatus,
    level: Float,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(Copy.AppName, color = Palette.Ink0, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(Copy.AppSubtitle, color = Palette.Ink2, fontSize = 11.sp)
            }
            StatusPill(status)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = Copy.Settings, tint = Palette.Ink1)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 电平条：安静时几乎看不见，有人在说话时跟着动 —— 用来确认「真的在听」
        LevelBar(level = level, active = status == RunStatus.RUNNING)

        if (status == RunStatus.RUNNING) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (translationStatus) {
                    TranslationStatus.OFF -> "识别中 · 翻译已关闭"
                    TranslationStatus.PREPARING -> "识别中 · 正在准备本地翻译模型"
                    TranslationStatus.READY -> "识别中 · 本机翻译就绪"
                    TranslationStatus.UNAVAILABLE -> Copy.TranslateUnavailable
                },
                color = if (translationStatus == TranslationStatus.UNAVAILABLE) Palette.Warn else Palette.Ink2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun LevelBar(level: Float, active: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.Surface2),
    ) {
        val fraction = if (active) level.coerceIn(0f, 1f) else 0f
        if (fraction > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        Brush.horizontalGradient(listOf(Palette.Accent, Color(0xFF5AC8FA)))
                    ),
            )
        }
    }
}

@Composable
private fun StatusPill(status: RunStatus) {
    val (label, color) = when (status) {
        RunStatus.IDLE -> Copy.Idle to Palette.Ink2
        RunStatus.PREPARING -> Copy.Preparing to Palette.Warn
        RunStatus.RUNNING -> Copy.Running to Palette.Success
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface1)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BrandMark(size: Dp = 34.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val heights = listOf(0.35f, 0.65f, 1f, 0.72f, 0.42f)
        val barWidth = this.size.width / (heights.size * 2.2f)
        val gap = barWidth * 0.55f
        val totalWidth = heights.size * barWidth + (heights.size - 1) * gap
        var x = (this.size.width - totalWidth) / 2f
        heights.forEach { factor ->
            val h = this.size.height * factor
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF5AC8FA), Palette.Accent)),
                topLeft = Offset(x, (this.size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            x += barWidth + gap
        }
    }
}

@Composable
private fun EmptyState(status: RunStatus) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            Copy.EmptyTitle,
            color = Palette.Ink0,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            Copy.EmptyBody,
            color = Palette.Ink1,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Surface1)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Bullet(Copy.EmptyBullet1)
            Bullet(Copy.EmptyBullet2)
            Bullet(Copy.EmptyBullet3)
        }
        if (status == RunStatus.PREPARING) {
            Spacer(Modifier.height(16.dp))
            Text(Copy.Preparing, color = Palette.Warn, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.Accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.Ink1, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun TranscriptList(segments: List<Segment>) {
    val listState = rememberLazyListState()
    val tail = segments.lastOrNull()

    LaunchedEffect(tail?.id, tail?.revision, segments.size) {
        if (segments.isNotEmpty()) {
            listState.scrollToItem(segments.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = 12.dp,
        ),
    ) {
        items(items = segments, key = { it.id }) { segment ->
            SegmentRow(segment)
        }
    }
}

@Composable
private fun SegmentRow(segment: Segment) {
    Row(modifier = Modifier.padding(vertical = 7.dp).fillMaxWidth()) {
        Text(
            text = timeLabel(segment.endMs),
            color = Palette.Ink2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(46.dp).padding(top = 3.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = segment.text,
                color = if (segment.isFinal) Palette.Ink0 else Palette.SegmentPartial,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            )
            when {
                segment.translation != null -> Text(
                    text = segment.translation,
                    color = Palette.Translation,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )

                segment.translationState == TranslationState.PENDING -> Text(
                    text = Copy.Translating,
                    color = Palette.Ink2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )

                segment.translationState == TranslationState.FAILED -> Text(
                    text = Copy.TranslateFailed,
                    color = Palette.Warn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun timeLabel(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

@Composable
private fun ControlPanel(
    status: RunStatus,
    source: SourceKind,
    level: Float,
    settings: SettingsData,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSourceChange: (SourceKind) -> Unit,
    onTranslateToggle: (Boolean) -> Unit,
    onLanguagesChange: (String, String) -> Unit,
    onOverlayToggle: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Palette.Surface1)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleChip(Copy.SourceSystem, source == SourceKind.SYSTEM) {
                if (status == RunStatus.IDLE) onSourceChange(SourceKind.SYSTEM)
            }
            Spacer(Modifier.width(8.dp))
            ToggleChip(Copy.SourceMic, source == SourceKind.MIC) {
                if (status == RunStatus.IDLE) onSourceChange(SourceKind.MIC)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (status == RunStatus.IDLE) "准备就绪" else "电平 ${(level * 100).toInt()}%",
                color = Palette.Ink2,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguagePicker(
                current = settings.sourceLang,
                options = Languages.source,
                enabled = status == RunStatus.IDLE,
                onSelect = { onLanguagesChange(it, settings.targetLang) },
            )
            Text("  →  ", color = Palette.Ink2, fontSize = 13.sp)
            LanguagePicker(
                current = settings.targetLang,
                options = Languages.target,
                enabled = status == RunStatus.IDLE,
                onSelect = { onLanguagesChange(settings.sourceLang, it) },
            )
            Spacer(Modifier.weight(1f))
            Text("翻译", color = Palette.Ink1, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = settings.translateEnabled,
                onCheckedChange = onTranslateToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Palette.Accent,
                    uncheckedTrackColor = Palette.Surface2,
                ),
                modifier = Modifier.height(28.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedChip(
                label = Copy.Overlay,
                active = settings.overlayVisible,
                onClick = onOverlayToggle,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedChip(
                label = Copy.Export,
                active = false,
                onClick = onExport,
                icon = true,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (status == RunStatus.IDLE) onStart() else onStop() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (status == RunStatus.IDLE) Palette.Accent else Palette.Surface2,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(46.dp).width(122.dp),
            ) {
                Text(
                    text = if (status == RunStatus.IDLE) Copy.Start else Copy.Stop,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (status != RunStatus.IDLE) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "关掉界面也会继续听；通知栏里可以随时停止。",
                color = Palette.Ink2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.SurfaceSelected else Palette.Surface2)
            .border(
                width = 1.dp,
                color = if (selected) Palette.Accent else Palette.Border,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) Palette.Ink0 else Palette.Ink1,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun OutlinedChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Palette.SurfaceSelected else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) Palette.Accent else Palette.Border,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                Icons.Filled.Share,
                contentDescription = null,
                tint = Palette.Ink1,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (active) Palette.Ink0 else Palette.Ink1, fontSize = 13.sp)
    }
}

@Composable
private fun LanguagePicker(
    current: String,
    options: List<Languages.Lang>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Surface2)
                .alpha(if (enabled) 1f else 0.55f)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Languages.label(current), color = Palette.Ink0, fontSize = 12.sp)
            Text(" ▾", color = Palette.Ink2, fontSize = 10.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            lang.label,
                            color = if (lang.code == current) Palette.Accent else Palette.Ink0,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(lang.code)
                    },
                )
            }
        }
    }
}
