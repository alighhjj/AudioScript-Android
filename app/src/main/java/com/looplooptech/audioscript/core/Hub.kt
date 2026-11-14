package com.looplooptech.audioscript.core

import android.content.Context
import com.looplooptech.audioscript.overlay.SubtitleOverlay
import com.looplooptech.audioscript.translate.MlKitTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 应用内的单一事实来源：转写段落、运行状态、翻译队列、悬浮窗都由它持有。
 * 采集服务（前台 Service）只负责喂音频，界面只负责观察，两者解耦。
 */
object Hub {

    lateinit var app: Context
        private set
    lateinit var settings: SettingsStore
        private set

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()

    private val _status = MutableStateFlow(RunStatus.IDLE)
    val status: StateFlow<RunStatus> = _status.asStateFlow()

    private val _activeSource = MutableStateFlow<SourceKind?>(null)
    val activeSource: StateFlow<SourceKind?> = _activeSource.asStateFlow()

    private val _translationStatus = MutableStateFlow(TranslationStatus.OFF)
    val translationStatus: StateFlow<TranslationStatus> = _translationStatus.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val translationQueue = Channel<Segment>(Channel.UNLIMITED)
    private val lock = Any()

    private var currentId: String? = null
    private var revision = 0
    private var utteranceStartMs = 0L

    private var translator: MlKitTranslator? = null
    private var overlay: SubtitleOverlay? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        settings = SettingsStore(app)

        scope.launch { translationLoop() }
        scope.launch { overlayLoop() }
        scope.launch { syncTranslationStatus() }
    }

    fun setNotice(text: String?) {
        _notice.value = text
    }

    fun consumeNotice() {
        _notice.value = null
    }

    // ---- 段落写入 ----------------------------------------------------------

    /** 一句话开始（上一句刚断点，或刚开始跑）。 */
    fun beginUtterance(startMs: Long) {
        synchronized(lock) {
            currentId = null
            revision = 0
            utteranceStartMs = startMs
        }
    }

    fun emitPartial(text: String, endMs: Long) {
        if (text.isBlank()) return
        synchronized(lock) {
            val id = currentId ?: UUID.randomUUID().toString().also { currentId = it }
            revision += 1
            val seg = Segment(
                id = id,
                startMs = utteranceStartMs,
                endMs = endMs,
                text = text,
                state = SegmentState.PARTIAL,
                revision = revision,
            )
            val list = _segments.value
            _segments.value = if (list.isNotEmpty() && list.last().id == id) {
                list.dropLast(1) + seg
            } else {
                list + seg
            }
        }
    }

    fun emitFinal(text: String, endMs: Long) {
        if (text.isBlank()) return
        val finalized: Segment
        synchronized(lock) {
            val id = currentId ?: UUID.randomUUID().toString()
            revision += 1
            finalized = Segment(
                id = id,
                startMs = utteranceStartMs,
                endMs = endMs,
                text = text,
                state = SegmentState.FINAL,
                revision = revision,
            )
            val list = _segments.value
            _segments.value = if (list.isNotEmpty() && list.last().id == id) {
                list.dropLast(1) + finalized
            } else {
                list + finalized
            }
            currentId = null
            revision = 0
        }
        translationQueue.trySend(finalized)
    }

    fun clear() {
        synchronized(lock) {
            _segments.value = emptyList()
            currentId = null
            revision = 0
            utteranceStartMs = 0L
        }
    }

    private fun updateSegment(id: String, block: (Segment) -> Segment) {
        synchronized(lock) {
            val list = _segments.value
            val index = list.indexOfLast { it.id == id }
            if (index < 0) return
            val copy = list.toMutableList()
            copy[index] = block(copy[index])
            _segments.value = copy
        }
    }

    // ---- 运行状态 ----------------------------------------------------------

    fun onRunning(kind: SourceKind) {
        _activeSource.value = kind
    }

    fun setStatus(status: RunStatus) {
        _status.value = status
    }

    fun onStopped() {
        _activeSource.value = null
        _status.value = RunStatus.IDLE
        _level.value = 0f
    }

    fun fail(message: String) {
        _notice.value = message
        _status.value = RunStatus.IDLE
    }

    fun onLevel(value: Float) {
        _level.value = value
    }

    // ---- 翻译 --------------------------------------------------------------

    private suspend fun translationLoop() {
        for (segment in translationQueue) {
            val cfg = settings.current
            if (!cfg.translateEnabled || cfg.sourceLang == cfg.targetLang) continue

            updateSegment(segment.id) { it.copy(translationState = TranslationState.PENDING) }
            try {
                val client = translatorFor(cfg.sourceLang, cfg.targetLang)
                val text = client.translate(segment.text)
                updateSegment(segment.id) {
                    it.copy(translation = text, translationState = TranslationState.DONE)
                }
                _translationStatus.value = TranslationStatus.READY
            } catch (t: Throwable) {
                updateSegment(segment.id) { it.copy(translationState = TranslationState.FAILED) }
                _translationStatus.value = TranslationStatus.UNAVAILABLE
                _notice.value = "本地翻译模型不可用，先只显示原文。原因：${t.message ?: t.javaClass.simpleName}"
                // 失败后丢弃旧客户端，下次重试重新建连
                translator?.close()
                translator = null
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun translatorFor(source: String, target: String): MlKitTranslator {
        val existing = translator
        if (existing != null && existing.matches(source, target)) {
            existing.awaitReady()
            return existing
        }
        existing?.close()
        _translationStatus.value = TranslationStatus.PREPARING
        val created = MlKitTranslator(app, source, target)
        translator = created
        created.awaitReady()
        return created
    }

    private suspend fun syncTranslationStatus() {
        combine(settings.data, _translationStatus) { cfg, status -> cfg to status }
            .collect { (cfg, status) ->
                if (!cfg.translateEnabled || cfg.sourceLang == cfg.targetLang) {
                    if (_translationStatus.value != TranslationStatus.OFF) {
                        _translationStatus.value = TranslationStatus.OFF
                    }
                } else if (status == TranslationStatus.OFF) {
                    _translationStatus.value = TranslationStatus.PREPARING
                }
            }
    }

    // ---- 悬浮字幕 ----------------------------------------------------------

    private suspend fun overlayLoop() {
        // 悬浮窗是 View，只能在主线程动
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            combine(_segments, settings.data, _activeSource) { segments, cfg, source ->
                Triple(segments, cfg, source)
            }.collect { (segments, cfg, source) ->
                val visible = cfg.overlayVisible && source != null
                val view = overlay ?: SubtitleOverlay(app).also { overlay = it }
                if (visible) view.render(segments, cfg) else view.hide()
            }
        }
    }

    /** 拖动后回写位置，下次进前台还在原处。 */
    fun updateOverlayPosition(y: Int) {
        settings.update { it.copy(overlayY = y) }
    }
}
