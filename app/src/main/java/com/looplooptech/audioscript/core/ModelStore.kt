package com.looplooptech.audioscript.core

import android.content.Context
import java.io.File

/**
 * 模型文件定位。
 *
 * 两种来源：
 *  - assets 内置：`OnlineRecognizer(assetManager, config)`，路径必须是 assets 相对路径；
 *  - 下载安装：`OnlineRecognizer(config = config)`，路径是 filesDir 下的绝对路径。
 *
 * 下载模型统一放在 `filesDir/asr/<model-id>/`，全部文件就位后才写入 `.complete`，
 * 避免识别线程加载到解压到一半的模型。
 */
object ModelStore {

    /** filesDir 下存放下载模型的目录。 */
    const val MODEL_ROOT = "asr"

    /** 全部文件就位后才写入的标记文件；识别线程靠它判断模型是否可用。 */
    const val MARKER = ".complete"

    @Volatile
    private var builtinCache: ModelFiles? = null

    private val assetCaches = HashMap<String, ModelFiles>()

    fun ensure(context: Context, spec: AsrModelSpec = ModelCatalog.builtinZhEn): ModelFiles {
        bundled(context, spec)?.let { return it }
        installed(context, spec)?.let { return it }
        throw IllegalStateException(
            "${spec.label} 尚未下载，请在「设置 → 模型」中下载后再开始识别"
        )
    }

    /** 模型是否可用（内置或已下载）。 */
    fun isInstalled(context: Context, spec: AsrModelSpec): Boolean =
        bundled(context, spec) != null || installed(context, spec) != null

    /** 是否随安装包放在 assets 里。 */
    fun isBundled(context: Context, spec: AsrModelSpec): Boolean =
        bundled(context, spec) != null

    /** assets 里有对应模型就返回，否则 null。 */
    fun bundled(context: Context, spec: AsrModelSpec): ModelFiles? {
        val dir = spec.assetDir ?: return null
        if (spec.requiredInApk) return runCatching { builtin(context) }.getOrNull()

        synchronized(assetCaches) {
            assetCaches[dir]?.let { return it }
        }

        val names = runCatching { context.assets.list(dir)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) return null

        val files = filesFrom(names, dir, ModelSource.ASSET) ?: return null
        synchronized(assetCaches) {
            assetCaches[dir] = files
        }
        return files
    }

    /** 已下载并完整解压的模型；不存在或缺文件时返回 null。 */
    fun installed(context: Context, spec: AsrModelSpec): ModelFiles? {
        val dir = modelDir(context, spec)
        if (!File(dir, MARKER).isFile) return null
        return discoverFiles(dir)
    }

    /** 下载目录。 */
    fun modelDir(context: Context, spec: AsrModelSpec): File =
        File(File(context.filesDir, MODEL_ROOT), spec.id)

    /** 从任意目录里挑出 encoder / decoder / joiner / tokens。 */
    fun discoverFiles(dir: File): ModelFiles? {
        if (!dir.isDirectory) return null
        val names = dir.list()?.toList().orEmpty()
        return filesFrom(names, dir.absolutePath, ModelSource.FILE)
    }

    /** 解压时只需要保留这些文件。 */
    fun isModelFileName(name: String): Boolean {
        if (name == "tokens.txt") return true
        if (!name.endsWith(".onnx")) return false
        return name.startsWith("encoder") ||
            name.startsWith("decoder") ||
            name.startsWith("joiner")
    }

    private fun builtin(context: Context): ModelFiles {
        builtinCache?.let { return it }

        synchronized(this) {
            builtinCache?.let { return it }

            val dir = ModelCatalog.builtinZhEn.assetDir ?: error("内置模型目录未配置")
            val names = context.assets.list(dir)?.toList().orEmpty()
            require(names.isNotEmpty()) { "安装包内缺少内置模型（assets/$dir 为空）" }

            val files = filesFrom(names, dir, ModelSource.ASSET)
                ?: error("内置模型文件不完整（assets/$dir）")
            builtinCache = files
            return files
        }
    }

    private fun filesFrom(names: List<String>, base: String, source: ModelSource): ModelFiles? {
        if (!names.contains("tokens.txt")) return null
        val encoder = pick(names, "encoder", preferInt8 = true) ?: return null
        val decoder = pick(names, "decoder", preferInt8 = false) ?: return null
        val joiner = pick(names, "joiner", preferInt8 = true) ?: return null

        val prefix = "$base/"
        return ModelFiles(
            encoder = "$prefix$encoder",
            decoder = "$prefix$decoder",
            joiner = "$prefix$joiner",
            tokens = prefix + "tokens.txt",
            source = source,
        )
    }

    private fun pick(names: List<String>, prefix: String, preferInt8: Boolean): String? {
        val candidates = names.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.contains(".int8.") == preferInt8 } ?: candidates.first()
    }
}

/** 模型文件路径；[source] 决定用 AssetManager 还是文件系统加载。 */
data class ModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val source: ModelSource,
)
