package com.looplooptech.audioscript.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsData(
    val sourceKind: SourceKind = SourceKind.SYSTEM,
    /** 识别语言：同时决定使用哪个识别模型（中文 / 英文用内置模型，日文用可下载模型）。 */
    val sourceLang: String = "zh",
    val targetLang: String = "en",
    val translateEnabled: Boolean = true,
    val endpointProfile: EndpointProfile = EndpointProfile.INTERPRETATION,
    // 悬浮字幕外观（与桌面端悬浮窗参数一致）
    val fontSize: Int = 22,
    val opacity: Int = 60,
    val maxLines: Int = 3,
    val glow: Boolean = true,
    val overlayVisible: Boolean = false,
    val overlayLocked: Boolean = false,
    val overlayX: Int = -1,
    val overlayY: Int = -1,
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("audioscript", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<SettingsData> = _data.asStateFlow()

    val current: SettingsData get() = _data.value

    fun update(block: (SettingsData) -> SettingsData) {
        val next = block(_data.value)
        if (next == _data.value) return
        _data.value = next
        save(next)
    }

    private fun load(): SettingsData = SettingsData(
        sourceKind = runCatching {
            SourceKind.valueOf(prefs.getString(KEY_SOURCE, SourceKind.SYSTEM.name)!!)
        }.getOrDefault(SourceKind.SYSTEM),
        sourceLang = prefs.getString(KEY_SRC_LANG, "zh") ?: "zh",
        targetLang = prefs.getString(KEY_TGT_LANG, "en") ?: "en",
        translateEnabled = prefs.getBoolean(KEY_TRANSLATE, true),
        endpointProfile = runCatching {
            EndpointProfile.valueOf(
                prefs.getString(KEY_PROFILE, EndpointProfile.INTERPRETATION.name)!!
            )
        }.getOrDefault(EndpointProfile.INTERPRETATION),
        fontSize = prefs.getInt(KEY_FONT_SIZE, 22),
        opacity = prefs.getInt(KEY_OPACITY, 60),
        maxLines = prefs.getInt(KEY_LINES, 3),
        glow = prefs.getBoolean(KEY_GLOW, true),
        overlayVisible = prefs.getBoolean(KEY_OVERLAY_VISIBLE, false),
        overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
        overlayX = prefs.getInt(KEY_OVERLAY_X, -1),
        overlayY = prefs.getInt(KEY_OVERLAY_Y, -1),
    )

    private fun save(d: SettingsData) {
        prefs.edit()
            .putString(KEY_SOURCE, d.sourceKind.name)
            .putString(KEY_SRC_LANG, d.sourceLang)
            .putString(KEY_TGT_LANG, d.targetLang)
            .putBoolean(KEY_TRANSLATE, d.translateEnabled)
            .putString(KEY_PROFILE, d.endpointProfile.name)
            .putInt(KEY_FONT_SIZE, d.fontSize)
            .putInt(KEY_OPACITY, d.opacity)
            .putInt(KEY_LINES, d.maxLines)
            .putBoolean(KEY_GLOW, d.glow)
            .putBoolean(KEY_OVERLAY_VISIBLE, d.overlayVisible)
            .putBoolean(KEY_OVERLAY_LOCKED, d.overlayLocked)
            .putInt(KEY_OVERLAY_X, d.overlayX)
            .putInt(KEY_OVERLAY_Y, d.overlayY)
            .apply()
    }

    private companion object {
        const val KEY_SOURCE = "source_kind"
        const val KEY_SRC_LANG = "source_lang"
        const val KEY_TGT_LANG = "target_lang"
        const val KEY_TRANSLATE = "translate_enabled"
        const val KEY_PROFILE = "endpoint_profile"
        const val KEY_FONT_SIZE = "overlay_font_size"
        const val KEY_OPACITY = "overlay_opacity"
        const val KEY_LINES = "overlay_lines"
        const val KEY_GLOW = "overlay_glow"
        const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        const val KEY_OVERLAY_LOCKED = "overlay_locked"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
    }
}
