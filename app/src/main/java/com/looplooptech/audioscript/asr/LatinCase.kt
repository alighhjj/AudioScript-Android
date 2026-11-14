package com.looplooptech.audioscript.asr

/**
 * 内置 Zipformer 输出的英文是整句大写（`SO THE KEY INSIGHT IS ...`）。
 * 这里按桌面端 `asr/latin_case.rs` 的规则还原成正常句子大小写：
 * 只有当「拉丁字母占比够高且几乎全大写」时才动手，中英混排、缩写、短片段都不碰。
 */
object LatinCase {

    private const val LATIN_RATIO = 0.4f
    private const val UPPER_RATIO = 0.9f
    private const val MIN_LATIN = 4

    fun restoreSentenceCase(text: String): String {
        if (!needsRestore(text)) return text

        val out = StringBuilder(text.length)
        var atSentenceStart = true
        var pendingStart = false
        var asciiQuoteOpen = false

        for (ch in text) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                out.append(if (atSentenceStart) ch.uppercaseChar() else ch.lowercaseChar())
                atSentenceStart = false
                pendingStart = false
                continue
            }

            out.append(ch)

            when {
                ch == '.' || ch == '!' || ch == '?' -> pendingStart = true
                ch == '"' -> {
                    asciiQuoteOpen = !asciiQuoteOpen
                    if (asciiQuoteOpen) {
                        atSentenceStart = true
                        pendingStart = false
                    }
                }

                ch == '\u201C' -> {
                    atSentenceStart = true
                    pendingStart = false
                }

                ch == '\u201D' -> pendingStart = false
                pendingStart && !ch.isWhitespace() && ch != ')' -> pendingStart = false
            }

            if (pendingStart && ch.isWhitespace()) atSentenceStart = true
        }
        return out.toString()
    }

    private fun needsRestore(text: String): Boolean {
        var latin = 0
        var upper = 0
        var visible = 0
        for (ch in text) {
            if (!ch.isWhitespace()) visible++
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                latin++
                if (ch in 'A'..'Z') upper++
            }
        }

        if (latin < MIN_LATIN) return false
        if (latin.toFloat() / maxOf(visible, 1) < LATIN_RATIO) return false
        return upper.toFloat() / latin >= UPPER_RATIO
    }
}
