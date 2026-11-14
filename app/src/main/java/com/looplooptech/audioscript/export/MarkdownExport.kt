package com.looplooptech.audioscript.export

import android.content.Context
import android.content.Intent
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.Segment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定稿段落导出为 Markdown，原文与译文成对保留 —— 与桌面端「导出」行为一致。
 */
object MarkdownExport {

    fun build(segments: List<Segment>, sourceLang: String, targetLang: String): String {
        val finals = segments.filter { it.isFinal }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val builder = StringBuilder()
        builder.appendLine("# 声稿转写")
        builder.appendLine()
        builder.appendLine("- 导出时间：$stamp")
        builder.appendLine("- 识别模型：${ModelStore.MODEL_LABEL}")
        builder.appendLine("- 语言：${languageLabel(sourceLang)} → ${languageLabel(targetLang)}")
        builder.appendLine("- 段落：${finals.size} 句")
        builder.appendLine()
        builder.appendLine("---")
        builder.appendLine()

        for (segment in finals) {
            builder.appendLine("[${timestamp(segment.endMs)}] ${segment.text}")
            val translation = segment.translation
            if (!translation.isNullOrBlank()) {
                builder.appendLine("> $translation")
            }
            builder.appendLine()
        }
        return builder.toString()
    }

    fun share(context: Context, markdown: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "声稿转写")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        context.startActivity(Intent.createChooser(intent, "导出转写").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun timestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun languageLabel(code: String): String = when (code) {
        "zh" -> "中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        "pt" -> "Português"
        "ru" -> "Русский"
        "it" -> "Italiano"
        "ar" -> "العربية"
        "id" -> "Bahasa Indonesia"
        "hi" -> "हिन्दी"
        "th" -> "ไทย"
        "vi" -> "Tiếng Việt"
        "tr" -> "Türkçe"
        else -> code
    }
}
