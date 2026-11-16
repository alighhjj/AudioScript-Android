package com.looplooptech.audioscript.core

/**
 * 可用的识别模型。
 *
 * 内置模型继续跟随桌面端（中英混说 Zipformer）；日文没有官方流式单语模型，
 * 这里选用 sherpa-onnx 官方发布的 8 语种流式 Zipformer（含日语），
 * 以「按需下载」的方式提供，避免 APK 体积直接翻倍。
 *
 * 选择哪个模型由「源语言」决定：ja → [ModelCatalog.japanese]，其余 → 内置中英模型。
 */
enum class ModelSource {
    /** 随 APK 放在 assets 里。 */
    ASSET,

    /** 下载后解压到 filesDir。 */
    FILE,
}

data class AsrModelSpec(
    val id: String,
    val label: String,
    val languages: List<String>,
    /** 模型在 assets 里的目录；为 null 表示不能内置。 */
    val assetDir: String? = null,
    /** 是否必须随安装包存在（内置中英模型）。 */
    val requiredInApk: Boolean = false,
    val archiveUrl: String? = null,
    val archiveSha256: String? = null,
    val downloadBytes: Long = 0L,
    /**
     * 多语种模型的首帧语言标签 token id；null 表示不需要。
     *
     * 这类模型训练时把 `<SOS>` 换成了 `<JA>` 之类的标签，而 sherpa-onnx 只会用 `<blk>` 起头；
     * 安装时由 [JaModelPatch] 把 `<blk>` 的 embedding 换成该 token。
     */
    val langTagTokenId: Int? = null,
    /** 镜像目录；archiveUrl 下载失败时按 [mirrorFiles] 逐个下载。 */
    val mirrorBase: String? = null,
    val mirrorFiles: List<ModelFileSpec> = emptyList(),
    val note: String = "",
) {
    val downloadable: Boolean get() = archiveUrl != null
}

/** 镜像里的单个模型文件；[sha256] 非空时逐个校验。 */
data class ModelFileSpec(
    val name: String,
    val sha256: String,
    val bytes: Long,
)

object ModelCatalog {

    const val LANG_ZH = "zh"
    const val LANG_EN = "en"
    const val LANG_JA = "ja"

    val builtinZhEn = AsrModelSpec(
        id = "zh-en-small",
        label = "中英混说 · Zipformer 轻量版",
        languages = listOf(LANG_ZH, LANG_EN),
        assetDir = "models/asr",
        requiredInApk = true,
        note = "随安装包内置，离线可用",
    )

    /**
     * sherpa-onnx 官方 8 语种流式模型：ar / en / id / ja / ru / th / vi / zh。
     * 下载地址与 sha256 来自 release `asr-models`，文件名见 [archiveUrl]。
     *
     * 该模型来自 PengChengStarling，训练时用 `<JA>` 替换 `<SOS>` 来指定语种；
     * sherpa-onnx 不支持指定语言标签，所以安装后由 [JaModelPatch] 补齐首帧上下文。
     */
    val japanese = AsrModelSpec(
        id = "ja-multi",
        label = "日本語 · 8 语种流式 Zipformer",
        languages = listOf(LANG_JA, LANG_ZH, LANG_EN, "ru", "ar", "id", "th", "vi"),
        assetDir = "models/asr-ja",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
        archiveSha256 = "28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4",
        downloadBytes = 247_000_000L,
        // <JA> 在 tokens.txt 里的 id；见 core/JaModelPatch.kt
        langTagTokenId = 6,
        // GitHub 大文件在国内经常下不动，退回镜像按文件下载（sha256 来自 HF 的 lfs.oid）。
        mirrorBase = "https://hf-mirror.com/csukuangfj/" +
            "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/",
        mirrorFiles = listOf(
            ModelFileSpec(
                name = "tokens.txt",
                sha256 = "784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab",
                bytes = 195_244L,
            ),
            ModelFileSpec(
                name = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                sha256 = "f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e",
                bytes = 296_583_597L,
            ),
            ModelFileSpec(
                name = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                sha256 = "7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48",
                bytes = 33_837_085L,
            ),
            ModelFileSpec(
                name = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                sha256 = "db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917",
                bytes = 8_257_421L,
            ),
        ),
        note = "日文流式识别，同时覆盖中英等 8 种语言；下载后离线使用",
    )

    val downloadable: List<AsrModelSpec> = listOf(japanese)

    fun forLanguage(code: String): AsrModelSpec =
        if (code == LANG_JA) japanese else builtinZhEn

    fun byId(id: String): AsrModelSpec? =
        (listOf(builtinZhEn) + downloadable).firstOrNull { it.id == id }
}
