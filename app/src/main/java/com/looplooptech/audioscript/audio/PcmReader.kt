package com.looplooptech.audioscript.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.looplooptech.audioscript.core.AsrConstants

/**
 * 把任意采样率的 AudioRecord 统一成 16 kHz 单声道 float —— 识别器只认这个格式。
 *
 * 每 100 ms 取一块：和桌面端「partial 实时刷新」的节奏一致，一块一次刷新，
 * 既不会太碎也不会显得卡。
 */
class PcmReader(private val record: AudioRecord, private val targetRate: Int = AsrConstants.SAMPLE_RATE) {

    private val srcRate: Int = record.sampleRate
    private val ratio: Double = srcRate.toDouble() / targetRate
    private val inFrames: Int = (targetRate * 0.1 * ratio).toInt().coerceAtLeast(targetRate / 10)
    private val shortBuf = ShortArray(inFrames)

    val isPassthrough: Boolean get() = srcRate == targetRate

    /** 读一块音频；返回 null 表示这一轮没有数据（已停止或被打断）。 */
    fun read(): FloatArray? {
        val n = try {
            record.read(shortBuf, 0, inFrames)
        } catch (t: Throwable) {
            return null
        }
        if (n <= 0) return null

        if (isPassthrough) {
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = shortBuf[i] / 32768f
            return out
        }

        // 线性插值重采样。每块单独映射到固定时长，不做跨块相位累积，
        // 省掉状态机也不会产生可听见的漂移（每块误差 < 1 个采样点）。
        val outCount = (n / ratio).toInt()
        if (outCount <= 0) return null
        val out = FloatArray(outCount)
        for (i in 0 until outCount) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val s0 = shortBuf[idx.coerceIn(0, n - 1)] / 32768f
            val s1 = shortBuf[(idx + 1).coerceIn(0, n - 1)] / 32768f
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }

    fun stop() {
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        runCatching { record.release() }
    }
}

object AudioCaptureFactory {

    /** 16 kHz 最省算力；部分机型对系统声音采集只开放 48 k，故逐个降级尝试。 */
    private val CANDIDATE_RATES = intArrayOf(16_000, 48_000, 44_100)

    @SuppressLint("MissingPermission")
    fun microphone(): AudioRecord? {
        for (rate in CANDIDATE_RATES) {
            val record = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes(rate))
                    .build()
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record?.release() }
        }
        return null
    }

    /**
     * 系统声音采集 = Android 的 AudioPlaybackCapture，对应桌面端的
     * Core Audio Process Tap / WASAPI loopback：不需要装虚拟声卡，也不需要改音频路由。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun playback(projection: MediaProjection): AudioRecord? {
        for (rate in CANDIDATE_RATES) {
            val record = runCatching {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes(rate))
                    .build()
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record?.release() }
        }
        return null
    }

    private fun bufferBytes(sampleRate: Int): Int {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)
        // 至少能装下 200 ms，避免读块时 underrun
        return maxOf(min * 4, sampleRate * 2 / 5)
    }
}
