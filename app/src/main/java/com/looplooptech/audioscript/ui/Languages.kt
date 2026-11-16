package com.looplooptech.audioscript.ui

/**
 * 语言列表。
 *
 * 「源语言」决定识别模型：中文 / English 用内置中英混说模型，日本語 用可下载的
 * ReazonSpeech 单语离线模型（见 [com.looplooptech.audioscript.core.ModelCatalog]）。
 * 「目标语言」跟随 ML Kit 本地翻译支持的语言。
 */
object Languages {

    data class Lang(val code: String, val label: String)

    val source: List<Lang> = listOf(
        Lang("zh", "中文"),
        Lang("en", "English"),
        Lang("ja", "日本語"),
    )

    val target: List<Lang> = listOf(
        Lang("en", "English"),
        Lang("zh", "中文"),
        Lang("ja", "日本語"),
        Lang("ko", "한국어"),
        Lang("fr", "Français"),
        Lang("de", "Deutsch"),
        Lang("es", "Español"),
        Lang("pt", "Português"),
        Lang("ru", "Русский"),
        Lang("it", "Italiano"),
        Lang("ar", "العربية"),
        Lang("id", "Indonesia"),
        Lang("hi", "हिन्दी"),
        Lang("th", "ไทย"),
        Lang("vi", "Tiếng Việt"),
        Lang("tr", "Türkçe"),
    )

    fun label(code: String): String =
        (source + target).firstOrNull { it.code == code }?.label ?: code
}
