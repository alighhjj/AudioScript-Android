package com.looplooptech.audioscript.core

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 识别器的创建参数。
 *
 * 流式模型逐项对齐桌面端 `asr/streaming.rs`：greedy_search、开启端点检测、
 * rule1 固定 2.4 s，rule2 / rule3 跟随「口译 / 听写」档位。
 * 日文离线模型走 VAD 切句 + greedy_search 整段解码。
 *
 * 模型路径见 [ModelStore]：内置模型是 assets 相对路径，下载模型是 filesDir 绝对路径。
 */
object RecognizerFactory {

    fun create(context: Context, files: ModelFiles, profile: EndpointProfile): OnlineRecognizer {
        val transducer = OnlineTransducerModelConfig(
            encoder = files.encoder,
            decoder = files.decoder,
            joiner = files.joiner,
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = files.tokens,
            numThreads = threadsForDevice(),
            provider = "cpu",
            debug = false,
        )

        val endpoint = EndpointConfig(
            rule1 = EndpointRule(false, AsrConstants.RULE1_TRAILING_SILENCE, 0f),
            rule2 = EndpointRule(true, profile.trailingSilenceMs / 1000f, 0f),
            rule3 = EndpointRule(false, 0f, profile.maxUtteranceMs / 1000f),
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AsrConstants.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = modelConfig,
            endpointConfig = endpoint,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        return if (files.source == ModelSource.ASSET) {
            OnlineRecognizer(assetManager = context.assets, config = config)
        } else {
            // assetManager 传 null（用默认值）时，AAR 内部走 newFromFile，读 filesDir 下的绝对路径。
            OnlineRecognizer(config = config)
        }
    }

    /**
     * 离线识别器：一次喂一整句，解完整段再取结果。
     *
     * `modelType` 留空，sherpa-onnx 会从 encoder 的 ONNX metadata 自动识别
     * `zipformer2`（ReazonSpeech 模型就是这种）。
     */
    fun createOffline(context: Context, files: ModelFiles): OfflineRecognizer {
        val transducer = OfflineTransducerModelConfig(
            encoder = files.encoder,
            decoder = files.decoder,
            joiner = files.joiner,
        )

        val modelConfig = OfflineModelConfig(
            transducer = transducer,
            tokens = files.tokens,
            numThreads = threadsForDevice(),
            provider = "cpu",
            debug = false,
        )

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AsrConstants.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )

        return if (files.source == ModelSource.ASSET) {
            OfflineRecognizer(assetManager = context.assets, config = config)
        } else {
            OfflineRecognizer(config = config)
        }
    }

    /**
     * 手机上一个 int8 流式 Zipformer 用 2 线程已经很够；
     * 开满 8 核反而会因为大小核调度拖慢首包。小核设备退回 1 线程。
     */
    private fun threadsForDevice(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 4 -> 1
            else -> 2
        }
    }
}
