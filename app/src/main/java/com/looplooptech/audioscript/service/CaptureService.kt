package com.looplooptech.audioscript.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.looplooptech.audioscript.MainActivity
import com.looplooptech.audioscript.R
import com.looplooptech.audioscript.asr.AsrRunner
import com.looplooptech.audioscript.audio.AudioCaptureFactory
import com.looplooptech.audioscript.audio.PcmReader
import com.looplooptech.audioscript.core.Hub
import com.looplooptech.audioscript.core.SourceKind

/**
 * 前台服务：把音频采集活到「关掉界面也继续听」。
 *
 * 两种音源都支持：
 *  - 系统声音 = AudioPlaybackCapture（Android 10+，需要一次录屏授权）
 *  - 麦克风   = AudioRecord
 */
class CaptureService : Service() {

    private var runner: AsrRunner? = null
    private var reader: PcmReader? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYSTEM -> startSystemCapture(intent)
            ACTION_START_MIC -> startMicrophoneCapture()
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startSystemCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode == 0 || data == null) {
            Hub.fail("系统声音授权已失效，请重新点「开始」")
            stopSelf()
            return
        }

        // Android 10+ 要求先起前台服务、再取 MediaProjection
        startForegroundSafely(
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            text = "正在采集系统声音",
        )

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (mediaProjection == null) {
            Hub.fail("无法建立系统声音采集通道，请重新授权")
            stopSelf()
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统弹窗里点了「停止共享」
                Hub.setNotice("系统声音共享已停止")
                shutdown()
            }
        }
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projection = mediaProjection
        projectionCallback = callback

        val record = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            AudioCaptureFactory.playback(mediaProjection)
        } else {
            null
        }
        if (record == null) {
            Hub.fail("这台设备不支持系统声音采集（需要 Android 10 及以上，且目标应用允许被采集）")
            stopSelf()
            return
        }
        startRunner(record, SourceKind.SYSTEM)
    }

    private fun startMicrophoneCapture() {
        startForegroundSafely(
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            text = "正在采集麦克风",
        )
        val record = AudioCaptureFactory.microphone()
        if (record == null) {
            Hub.fail("麦克风打开失败，请检查录音权限")
            stopSelf()
            return
        }
        startRunner(record, SourceKind.MIC)
    }

    private fun startRunner(record: android.media.AudioRecord, kind: SourceKind) {
        val pcmReader = PcmReader(record)
        reader = pcmReader
        runCatching { record.startRecording() }

        Hub.clear()
        Hub.onRunning(kind)
        Hub.setStatus(com.looplooptech.audioscript.core.RunStatus.PREPARING)

        val asrRunner = AsrRunner(applicationContext, pcmReader) { stopOnMainThread() }
        runner = asrRunner
        asrRunner.start()
    }

    private fun startForegroundSafely(type: Int, text: String) {
        createChannel()
        val notification = buildNotification(text)
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            Hub.fail("前台服务启动失败：${it.message}")
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("声稿 · 正在聆听")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "转写服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "转写进行中的常驻通知"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopOnMainThread() {
        Handler(Looper.getMainLooper()).post { shutdown() }
    }

    private fun shutdown() {
        runner?.stop()
        runner = null
        reader?.stop()
        reader = null
        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null

        Hub.onStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runner?.stop()
        reader?.stop()
        runCatching { projection?.stop() }
        Hub.onStopped()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START_SYSTEM = "com.looplooptech.audioscript.START_SYSTEM"
        private const val ACTION_START_MIC = "com.looplooptech.audioscript.START_MIC"
        private const val ACTION_STOP = "com.looplooptech.audioscript.STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun startSystem(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START_SYSTEM
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun startMic(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START_MIC)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
