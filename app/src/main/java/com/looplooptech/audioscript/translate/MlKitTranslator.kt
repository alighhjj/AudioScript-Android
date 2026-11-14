package com.looplooptech.audioscript.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * 本机翻译。
 *
 * 桌面端用 CTranslate2 跑 Opus-MT；CTranslate2 没有 Android 版本，
 * 这里换成 ML Kit 的本地翻译模型（同样在本机推理，模型下载一次后可离线）。
 * 对外只暴露「离线、逐句、指定语言对」这三件事，内部实现可替换。
 */
class MlKitTranslator(
    context: Context,
    private val source: String,
    private val target: String,
) {

    private val client: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(mlKitCode(source))
            .setTargetLanguage(mlKitCode(target))
            .build()
    )

    private val mutex = Mutex()
    private var ready = false

    fun matches(source: String, target: String): Boolean =
        this.source == source && this.target == target

    suspend fun awaitReady() {
        if (ready) return
        mutex.withLock {
            if (ready) return
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            ready = true
        }
    }

    suspend fun translate(text: String): String {
        awaitReady()
        return client.translate(text).await()
    }

    fun close() {
        runCatching { client.close() }
    }

    private companion object {
        /**
         * 内部语言码 → ML Kit 语言码。内置识别模型只出中英，因此源语言只有这两个；
         * 目标语言跟随 ML Kit 支持列表（与桌面端 29+ 语言的定位一致）。
         */
        fun mlKitCode(code: String): String = when (code) {
            "zh" -> TranslateLanguage.CHINESE
            "zh-TW" -> TranslateLanguage.CHINESE
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "it" -> TranslateLanguage.ITALIAN
            "ar" -> TranslateLanguage.ARABIC
            "id" -> TranslateLanguage.INDONESIAN
            "hi" -> TranslateLanguage.HINDI
            "th" -> TranslateLanguage.THAI
            "vi" -> TranslateLanguage.VIETNAMESE
            "tr" -> TranslateLanguage.TURKISH
            "nl" -> TranslateLanguage.DUTCH
            "pl" -> TranslateLanguage.POLISH
            "uk" -> TranslateLanguage.UKRAINIAN
            "cs" -> TranslateLanguage.CZECH
            "sv" -> TranslateLanguage.SWEDISH
            "da" -> TranslateLanguage.DANISH
            "fi" -> TranslateLanguage.FINNISH
            "no" -> TranslateLanguage.NORWEGIAN
            "el" -> TranslateLanguage.GREEK
            "he" -> TranslateLanguage.HEBREW
            "ro" -> TranslateLanguage.ROMANIAN
            "hu" -> TranslateLanguage.HUNGARIAN
            "sk" -> TranslateLanguage.SLOVAK
            "bg" -> TranslateLanguage.BULGARIAN
            "ms" -> TranslateLanguage.MALAY
            else -> TranslateLanguage.ENGLISH
        }
    }
}
