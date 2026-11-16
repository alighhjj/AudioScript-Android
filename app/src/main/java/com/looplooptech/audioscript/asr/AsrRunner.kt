package com.looplooptech.audioscript.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.looplooptech.audioscript.audio.PcmReader
import com.looplooptech.audioscript.core.AsrConstants
import com.looplooptech.audioscript.core.Hub
import com.looplooptech.audioscript.core.ModelCatalog
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.RecognizerFactory
import com.looplooptech.audioscript.core.RunStatus
import kotlin.math.sqrt

/**
 * 识别线程：读音频 → 喂流式模型 → 有端点就定稿、没有就刷新 partial。
 *
 * 这段逻辑是桌面端 `asr/streaming.rs` 的直译：
 * 每次 decode 之后取一次结果，`isEndpoint` 为真就 reset 并进入下一句。
 */
class AsrRunner(
    private val context: Context,
    private val pcm: PcmReader,
    /** 引擎/采集中断时通知服务自己收摊，避免「通知栏还在、其实没在听」。 */
    private val onFatal: () -> Unit = {},
) : CaptureRunner {

    private var worker: Thread? = null

    @Volatile
    private var running = false

    private var recognizer: OnlineRecognizer? = null

    override fun start() {
        if (running) return
        running = true
        Hub.setStatus(RunStatus.PREPARING)
        worker = Thread({ loop() }, "asr-runner").also { it.start() }
    }

    override fun stop() {
        running = false
        pcm.stop()
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var stream: OnlineStream? = null
        try {
            val cfg = Hub.settings.current
            val spec = ModelCatalog.forLanguage(cfg.sourceLang)
            if (!ModelStore.isInstalled(context, spec)) {
                Hub.fail("${spec.label} 尚未下载，请到「设置 → 模型」下载后再开始")
                onFatal()
                return
            }

            // 模型解包 + 加载：首次启动约 1~3 秒，这段时间界面显示「加载模型」
            val files = ModelStore.ensure(context, spec)
            val recognizer = RecognizerFactory.create(context, files, cfg.endpointProfile)
            this.recognizer = recognizer
            stream = recognizer.createStream()

            // 日文中没有需要还原的英文大小写；中英模型才走 LatinCase。
            val restoreCase = cfg.sourceLang != ModelCatalog.LANG_JA
            fun clean(text: String): String =
                if (restoreCase) LatinCase.restoreSentenceCase(text) else text

            Hub.beginUtterance(0L)
            Hub.setStatus(RunStatus.RUNNING)

            var samplesFed = 0L
            var lastPartial = ""
            var emptyReads = 0

            while (running) {
                val chunk = pcm.read()
                if (chunk == null) {
                    // 采集被系统掐断（例如录屏授权被撤销）时不要空转烧 CPU
                    if (++emptyReads > 100) {
                        Hub.fail("音频采集中断，请重新开始")
                        onFatal()
                        break
                    }
                    Thread.sleep(20)
                    continue
                }
                emptyReads = 0
                stream.acceptWaveform(chunk, AsrConstants.SAMPLE_RATE)
                samplesFed += chunk.size
                Hub.onLevel(rms(chunk))

                var pending: String? = null
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text
                    if (text.isNotBlank()) pending = text
                }

                val nowMs = samplesFed * 1000 / AsrConstants.SAMPLE_RATE

                if (recognizer.isEndpoint(stream)) {
                    if (!pending.isNullOrBlank()) {
                        Hub.emitFinal(clean(pending.trim()), nowMs)
                    }
                    recognizer.reset(stream)
                    Hub.beginUtterance(nowMs)
                    lastPartial = ""
                } else if (pending != null && pending != lastPartial) {
                    lastPartial = pending
                    Hub.emitPartial(clean(pending.trim()), nowMs)
                }
            }

            Hub.onLevel(0f)
        } catch (t: Throwable) {
            if (running) {
                Hub.fail("识别引擎出错：${t.message ?: t.javaClass.simpleName}")
                onFatal()
            }
        } finally {
            runCatching { stream?.release() }
            runCatching { this.recognizer?.release() }
            this.recognizer = null
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return (sqrt(sum / samples.size) * 4.0).toFloat().coerceIn(0f, 1f)
    }

}
