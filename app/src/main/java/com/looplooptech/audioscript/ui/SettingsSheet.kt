package com.looplooptech.audioscript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looplooptech.audioscript.core.EndpointProfile
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.SettingsData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: SettingsData,
    overlayAllowed: Boolean,
    onDismiss: () -> Unit,
    onUpdate: ((SettingsData) -> SettingsData) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                "识别：${ModelStore.MODEL_LABEL}（随安装包内置，离线）\n翻译：ML Kit 本地翻译模型（首次使用需下载，之后离线）",
                color = Palette.Ink1,
                fontSize = 12.sp,
                lineHeight = 18.sp,
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
