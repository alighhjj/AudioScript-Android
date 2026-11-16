package com.looplooptech.audioscript.core

/**
 * 可用的识别模型。
 *
 * 中英继续用内置的流式 Zipformer；日文改用 ReazonSpeech 单语离线模型，
 * 由 VAD 切句后整段解码（sherpa-onnx 官方的 simulate-streaming 方案）。
 *
 * 选择哪个模型由「源语言」决定：ja → [ModelCatalog.japanese]，其余 → 内置中英模型。
 */
enum class ModelSource {
    /** 随 APK 放在 assets 里。 */
    ASSET,

    /** 下载后解压到 filesDir。 */
    FILE,
}

/** 识别管线：流式解码，还是 VAD 切句 + 离线整段解码。 */
enum class AsrPipeline {
    STREAMING,
    VAD_OFFLINE,
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
    /** 用哪条识别管线；日文离线模型需要 VAD 切句。 */
    val pipeline: AsrPipeline = AsrPipeline.STREAMING,
    /** 镜像目录；[archiveUrl] 下载失败时按 [mirrorFiles] 逐个下载。 */
    val mirrorBase: String? = null,
    val mirrorFiles: List<ModelFileSpec> = emptyList(),
    val note: String = "",
) {
    val downloadable: Boolean get() = archiveUrl != null || mirrorFiles.isNotEmpty()
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
     * ReazonSpeech 日本語 Zipformer（35k 小时日语数据），sherpa-onnx 官方发布的离线模型。
     *
     * 这台机器跑不动 296 MB 的 8 语种流式模型：解码速度低于实时速度，录音缓冲持续溢出，
     * 表现就是字幕过一会儿就不动了。单语离线模型 encoder 只有 154 MB，配合 Silero VAD
     * 按句切分后整段解码，CPU 和内存都低一个量级。
     *
     * 官方 .tar.bz2 放在 GitHub release 上，国内基本下不动，所以只走 hf-mirror 逐文件下载，
     * 四个文件各自校验 sha256。
     */
    val japanese = AsrModelSpec(
        id = "ja-reazon",
        label = "日本語 · ReazonSpeech Zipformer",
        languages = listOf(LANG_JA),
        assetDir = "models/asr-ja-reazon",
        downloadBytes = 177_203_844L,
        pipeline = AsrPipeline.VAD_OFFLINE,
        mirrorBase = "https://hf-mirror.com/DeL-TaiseiOzaki/" +
            "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        mirrorFiles = listOf(
            ModelFileSpec(
                name = "tokens.txt",
                sha256 = "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def",
                bytes = 45_754L,
            ),
            ModelFileSpec(
                name = "encoder-epoch-99-avg-1.int8.onnx",
                sha256 = "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d",
                bytes = 154_670_139L,
            ),
            ModelFileSpec(
                name = "decoder-epoch-99-avg-1.onnx",
                sha256 = "58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124",
                bytes = 11_767_836L,
            ),
            ModelFileSpec(
                name = "joiner-epoch-99-avg-1.onnx",
                sha256 = "d38a81d1191c9ed6de6a1719503692e07e3e973e2364adde0abae5eaaded1174",
                bytes = 10_720_115L,
            ),
        ),
        note = "日文专用离线模型：VAD 切句后整段解码，下载后完全离线",
    )

    val downloadable: List<AsrModelSpec> = listOf(japanese)

    fun forLanguage(code: String): AsrModelSpec =
        if (code == LANG_JA) japanese else builtinZhEn

    fun byId(id: String): AsrModelSpec? =
        (listOf(builtinZhEn) + downloadable).firstOrNull { it.id == id }
}
