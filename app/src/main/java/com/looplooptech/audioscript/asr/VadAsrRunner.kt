package com.looplooptech.audioscript.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.looplooptech.audioscript.audio.PcmReader
import com.looplooptech.audioscript.core.AsrConstants
import com.looplooptech.audioscript.core.Hub
import com.looplooptech.audioscript.core.ModelCatalog
import com.looplooptech.audioscript.core.ModelStore
import com.looplooptech.audioscript.core.RecognizerFactory
import com.looplooptech.audioscript.core.RunStatus
import kotlin.math.sqrt
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * 日文识别线程：Silero VAD 切句 → 离线 Zipformer 整段解码。
 *
 * 这就是 sherpa-onnx 官方的 simulate-streaming 方案。和 [AsrRunner] 的流式解码不同，
 * 这里每读完一块音频只做很便宜的 VAD 推理，真正耗 CPU 的解码放在独立线程里，
 * 所以解码再慢也不会堵住 `AudioRecord.read()`，从根上避免录音缓冲溢出。
 *
 * 代价是没有逐字 partial：一句话说完（静音 0.4 s）或连续说满 10 s 才出字幕。
 */
class VadAsrRunner(
    private val context: Context,
    private val pcm: PcmReader,
    /** 引擎/采集中断时通知服务自己收摊，避免「通知栏还在、其实没在听」。 */
    private val onFatal: () -> Unit = {},
) : CaptureRunner {

    /** 待解码的整句；解码跟不上时丢最旧的一句，优先保证字幕贴近实时。 */
    private val pending = LinkedBlockingDeque<SpeechSegment>(MAX_PENDING_SEGMENTS)

    @Volatile
    private var running = false

    @Volatile
    private var droppedOnce = false

    private var vadWorker: Thread? = null
    private var decodeWorker: Thread? = null

    override fun start() {
        if (running) return
        running = true
        droppedOnce = false
        pending.clear()
        Hub.setStatus(RunStatus.PREPARING)
        vadWorker = Thread({ vadLoop() }, "asr-vad").also { it.start() }
        decodeWorker = Thread({ decodeLoop() }, "asr-decode").also { it.start() }
    }

    override fun stop() {
        running = false
        // 关掉 AudioRecord 会让阻塞中的 read() 立刻返回，两个线程随后自行退出。
        pcm.stop()
        vadWorker = null
        decodeWorker = null
    }

    // ---- VAD 线程 ----------------------------------------------------------

    private fun vadLoop() {
        var vad: Vad? = null
        try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET,
                    threshold = 0.5f,
                    // 静音多久算一句说完；比流式端点的 0.8 s 还短一点。
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.3f,
                    windowSize = 512,
                    // 连续说话时的上限：超过后 VAD 会用更严格的阈值强制断句。
                    maxSpeechDuration = 10f,
                ),
                sampleRate = AsrConstants.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            val v = Vad(assetManager = context.assets, config = config)
            vad = v
            Log.i(TAG, "VAD 已加载：$VAD_ASSET")

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

                v.acceptWaveform(chunk)
                Hub.onLevel(rms(chunk))
                drain(v)
            }

            // 停止时把最后一句也交出去，别把已经听到的内容丢掉。
            v.flush()
            drain(v)
        } catch (t: Throwable) {
            if (running) {
                Hub.fail("日文识别引擎出错：${t.message ?: t.javaClass.simpleName}")
                onFatal()
            }
        } finally {
            Hub.onLevel(0f)
            runCatching { vad?.release() }
        }
    }

    private fun drain(vad: Vad) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (segment.samples.size < MIN_SEGMENT_SAMPLES) continue

            if (!pending.offerLast(segment)) {
                // 队列满：丢掉最旧的一句，保住延迟。
                pending.pollFirst()
                pending.offerLast(segment)
                noteDrop()
            }
        }
    }

    private fun noteDrop() {
        if (droppedOnce) return
        droppedOnce = true
        Log.w(TAG, "解码队列已满，丢弃最旧的一段")
        Hub.setNotice("识别速度跟不上，已跳过一段音频")
    }

    // ---- 解码线程 ----------------------------------------------------------

    private fun decodeLoop() {
        var recognizer: OfflineRecognizer? = null
        try {
            val spec = ModelCatalog.forLanguage(Hub.settings.current.sourceLang)
            if (!ModelStore.isInstalled(context, spec)) {
                Hub.fail("${spec.label} 尚未下载，请到「设置 → 模型」下载后再开始")
                onFatal()
                return
            }

            // 154 MB int8 encoder 的加载约 1~2 秒，这段时间界面显示「加载模型」。
            val files = ModelStore.ensure(context, spec)
            val rec = RecognizerFactory.createOffline(context, files)
            recognizer = rec
            Hub.setStatus(RunStatus.RUNNING)
            Log.i(TAG, "识别器已加载：${files.source} / ${Runtime.getRuntime().availableProcessors()} 核")

            while (running || pending.isNotEmpty()) {
                val segment = try {
                    pending.pollFirst(100, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue

                val text = transcribe(rec, segment)
                if (text.isNotBlank()) {
                    val startMs = segment.start * 1000L / AsrConstants.SAMPLE_RATE
                    val endMs = (segment.start + segment.samples.size) * 1000L /
                        AsrConstants.SAMPLE_RATE
                    Hub.beginUtterance(startMs)
                    Hub.emitFinal(text, endMs)
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Hub.fail("日文识别引擎出错：${t.message ?: t.javaClass.simpleName}")
                onFatal()
            }
        } finally {
            runCatching { recognizer?.release() }
        }
    }

    private fun transcribe(recognizer: OfflineRecognizer, segment: SpeechSegment): String {
        val started = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(segment.samples, AsrConstants.SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()

            val audioMs = segment.samples.size * 1000L / AsrConstants.SAMPLE_RATE
            val costMs = SystemClock.elapsedRealtime() - started
            val rtf = if (audioMs > 0) costMs.toFloat() / audioMs else 0f
            Log.i(TAG, "解码 ${audioMs}ms 音频用 ${costMs}ms (RTF=%.2f)，${text.length} 字".format(rtf))
            text
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return (sqrt(sum / samples.size) * 4.0).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "VadAsr"
        const val VAD_ASSET = "models/vad/silero_vad.onnx"

        /** 太短的片段多半是咳嗽、碰麦，直接忽略（0.3 秒）。 */
        const val MIN_SEGMENT_SAMPLES = AsrConstants.SAMPLE_RATE * 3 / 10

        const val MAX_PENDING_SEGMENTS = 4
    }
}
