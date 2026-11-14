package com.looplooptech.audioscript.ui

/**
 * 语言列表。
 *
 * 识别端只有中英混说模型（内置 Zipformer），所以「源语言」只在中英之间选；
 * 「目标语言」跟随 ML Kit 本地翻译支持的语言。
 */
object Languages {

    data class Lang(val code: String, val label: String)

    val source: List<Lang> = listOf(
        Lang("zh", "中文"),
        Lang("en", "English"),
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
