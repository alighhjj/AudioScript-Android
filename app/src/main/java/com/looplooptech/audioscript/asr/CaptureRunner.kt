package com.looplooptech.audioscript.asr

/**
 * 识别线程的统一入口。
 *
 * 中英走 [AsrRunner]（流式解码）；日文走 [VadAsrRunner]（VAD 切句 + 离线整段解码）。
 * 采集服务只关心「开始 / 停止」，不关心底层是哪条管线。
 */
interface CaptureRunner {
    fun start()
    fun stop()
}
