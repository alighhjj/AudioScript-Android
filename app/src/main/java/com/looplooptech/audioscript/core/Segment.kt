package com.looplooptech.audioscript.core

/** 一句话的两种状态：还在刷新（partial）与句末定稿（final）。 */
enum class SegmentState { PARTIAL, FINAL }

enum class TranslationState { IDLE, PENDING, DONE, FAILED }

/**
 * 与桌面端 `TranscriptSegment` 对齐的数据模型：
 * 同一句话 partial → final 保持同一个 [id]，只有 [revision] 递增，
 * 这样界面可以原地刷新而不会整段闪烁。
 */
data class Segment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val translationState: TranslationState = TranslationState.IDLE,
    val state: SegmentState = SegmentState.PARTIAL,
    val revision: Int = 1,
) {
    val isFinal: Boolean get() = state == SegmentState.FINAL
}

enum class SourceKind { SYSTEM, MIC }

enum class RunStatus { IDLE, PREPARING, RUNNING }

enum class TranslationStatus { OFF, PREPARING, READY, UNAVAILABLE }

enum class EndpointProfile(val trailingSilenceMs: Int, val maxUtteranceMs: Int) {
    /** 口译：断句快，适合看视频 / 听播客。 */
    INTERPRETATION(800, 8_000),

    /** 听写：等得更久，长句更完整。 */
    DICTATION(1_200, 20_000),
}

object AsrConstants {
    const val SAMPLE_RATE = 16_000

    /** 与桌面端一致：单句最长等待的尾部静音（sherpa rule1）。 */
    const val RULE1_TRAILING_SILENCE = 2.4f
}
