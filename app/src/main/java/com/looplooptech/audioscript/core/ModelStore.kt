package com.looplooptech.audioscript.core

import android.content.Context

/**
 * 内置模型的三个 ONNX 与词表，全部以 **assets 相对路径** 表示。
 *
 * 这一点必须跟官方 Android 示例一致：`OnlineRecognizer(assetManager, config)` 走的是
 * JNI 的 `newFromAsset`，native 层用 `AAssetManager_open` 解析路径 —— 传绝对路径
 * （即 filesDir 下的真实文件）在部分版本上会直接打不开模型，进程退出。
 *
 * 模型随 APK 一起装入 assets，首次启动不需要联网，也不需要先解包到磁盘。
 * 精度选择与桌面端一致：encoder / joiner 用 int8，decoder 保持 fp32。
 */
object ModelStore {

    private const val ASSET_DIR = "models/asr"
    const val MODEL_LABEL = "Zipformer 轻量版 · 中英混说"

    @Volatile
    private var cached: ModelFiles? = null

    fun ensure(context: Context): ModelFiles {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            val names = context.assets.list(ASSET_DIR)?.toList().orEmpty()
            require(names.isNotEmpty()) { "安装包内缺少内置模型（assets/$ASSET_DIR 为空）" }

            val files = ModelFiles(
                encoder = "$ASSET_DIR/${pick(names, "encoder", preferInt8 = true)}",
                decoder = "$ASSET_DIR/${pick(names, "decoder", preferInt8 = false)}",
                joiner = "$ASSET_DIR/${pick(names, "joiner", preferInt8 = true)}",
                tokens = "$ASSET_DIR/tokens.txt",
            )
            require(names.contains("tokens.txt")) { "内置模型缺少 tokens.txt" }
            cached = files
            return files
        }
    }

    private fun pick(names: List<String>, prefix: String, preferInt8: Boolean): String {
        val candidates = names.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
        require(candidates.isNotEmpty()) { "内置模型里找不到 $prefix*.onnx" }
        return candidates.firstOrNull { it.contains(".int8.") == preferInt8 } ?: candidates.first()
    }
}

/** assets 内的相对路径。 */
data class ModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)
