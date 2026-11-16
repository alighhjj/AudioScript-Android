package com.looplooptech.audioscript.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class ModelInstallPhase {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
    FAILED,
}

data class ModelInstallState(
    val phase: ModelInstallPhase,
    val received: Long = 0L,
    val total: Long = 0L,
    /** 0..1，下载或解压进度。 */
    val progress: Float = 0f,
    val message: String? = null,
) {
    companion object {
        fun notInstalled() = ModelInstallState(ModelInstallPhase.NOT_INSTALLED)
        fun installed() = ModelInstallState(ModelInstallPhase.INSTALLED)
        fun failed(message: String) = ModelInstallState(ModelInstallPhase.FAILED, message = message)
    }
}

/**
 * 可下载识别模型的管理器。
 *
 * 模型有两种下载方式：
 *  - 官方 tar.bz2：下载 → sha256 校验 → 解压（只保留四个模型文件）→ 原子替换目录；
 *  - 镜像按文件下载：逐个下载并校验 sha256，适合 GitHub 下不动的网络。
 *
 * 日文 ReazonSpeech 模型只配置了 hf-mirror 逐文件下载。状态放在 StateFlow 里，
 * 设置界面和启动前的检查都读这一份。
 */
class ModelManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<String, ModelInstallState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    fun stateOf(spec: AsrModelSpec): ModelInstallState = _states.value[spec.id]
        ?: if (ModelStore.isInstalled(context, spec)) {
            ModelInstallState.installed()
        } else {
            ModelInstallState.notInstalled()
        }

    /** 重新读一次磁盘，处理「从系统设置里清过数据」或首次启动。 */
    fun refresh() {
        val next = _states.value.toMutableMap()
        for (spec in ModelCatalog.downloadable) {
            if (jobs[spec.id]?.isActive == true) continue
            next[spec.id] = if (ModelStore.isInstalled(context, spec)) {
                ModelInstallState.installed()
            } else {
                ModelInstallState.notInstalled()
            }
        }
        _states.value = next
    }

    fun install(spec: AsrModelSpec) {
        if (!spec.downloadable) return
        if (jobs[spec.id]?.isActive == true) return
        if (ModelStore.isBundled(context, spec)) {
            set(spec.id, ModelInstallState.installed())
            return
        }
        if (Hub.status.value != RunStatus.IDLE) {
            Hub.setNotice("请先停止识别，再下载识别模型")
            return
        }

        jobs[spec.id] = scope.launch {
            val tempDir = File(File(context.filesDir, ModelStore.MODEL_ROOT), ".tmp-${spec.id}")
            try {
                tempDir.deleteRecursively()
                tempDir.mkdirs()
                set(
                    spec.id,
                    ModelInstallState(
                        phase = ModelInstallPhase.DOWNLOADING,
                        total = spec.downloadBytes,
                    ),
                )

                var archiveError: Throwable? = null
                val archiveOk = try {
                    fetchArchive(spec, tempDir)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    archiveError = t
                    false
                }

                if (!archiveOk) {
                    tempDir.deleteRecursively()
                    tempDir.mkdirs()
                    try {
                        if (!fetchMirror(spec, tempDir)) {
                            throw IOException(archiveError?.message ?: "下载失败")
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        val reason = t.message ?: archiveError?.message ?: "网络异常"
                        throw IOException("模型下载失败：$reason", t)
                    }
                }

                ModelStore.discoverFiles(tempDir)
                    ?: throw IOException("模型文件不完整，请重试")

                File(tempDir, ModelStore.MARKER).writeText(spec.id)
                replaceDirectory(tempDir, ModelStore.modelDir(context, spec))
                set(spec.id, ModelInstallState.installed())
            } catch (ce: CancellationException) {
                tempDir.deleteRecursively()
                set(spec.id, ModelInstallState.notInstalled())
                throw ce
            } catch (t: Throwable) {
                tempDir.deleteRecursively()
                if (!currentCoroutineContext().isActive) {
                    set(spec.id, ModelInstallState.notInstalled())
                } else {
                    set(spec.id, ModelInstallState.failed(t.message ?: "下载失败"))
                }
            } finally {
                connections.remove(spec.id)
                jobs.remove(spec.id)
            }
        }
    }

    fun cancel(spec: AsrModelSpec) {
        connections.remove(spec.id)?.disconnect()
        jobs[spec.id]?.cancel()
    }

    fun uninstall(spec: AsrModelSpec) {
        if (jobs[spec.id]?.isActive == true) {
            cancel(spec)
            return
        }
        if (ModelStore.isBundled(context, spec)) {
            Hub.setNotice("这个模型已随安装包内置，不需要下载，也不能删除")
            set(spec.id, ModelInstallState.installed())
            return
        }
        if (Hub.status.value != RunStatus.IDLE) {
            Hub.setNotice("请先停止识别，再删除识别模型")
            return
        }
        ModelStore.modelDir(context, spec).deleteRecursively()
        set(spec.id, ModelInstallState.notInstalled())
    }

    private fun set(id: String, state: ModelInstallState) {
        _states.update { it + (id to state) }
    }

    /** 首选：官方 tar.bz2（一次下载、一次校验）。失败由调用方决定是否退回镜像。 */
    private suspend fun fetchArchive(spec: AsrModelSpec, dest: File): Boolean {
        val url = spec.archiveUrl ?: return false
        val archive = File(context.cacheDir, "${spec.id}.tar.bz2")
        try {
            archive.delete()
            downloadTo(spec, url, archive, spec.downloadBytes) { received, total ->
                set(
                    spec.id,
                    ModelInstallState(
                        phase = ModelInstallPhase.DOWNLOADING,
                        received = received,
                        total = total,
                        progress = ratio(received, total),
                    ),
                )
            }

            val expected = spec.archiveSha256
            if (expected != null && !sha256(archive).equals(expected, ignoreCase = true)) {
                throw IOException("模型校验失败（sha256 不匹配）")
            }

            set(spec.id, ModelInstallState(ModelInstallPhase.EXTRACTING))
            extract(archive, dest) { progress ->
                set(
                    spec.id,
                    ModelInstallState(ModelInstallPhase.EXTRACTING, progress = progress),
                )
            }
            return ModelStore.discoverFiles(dest) != null
        } finally {
            archive.delete()
        }
    }

    /** 兜底：从镜像按文件下载，逐个校验 sha256（镜像的 release 包常常下不动）。 */
    private suspend fun fetchMirror(spec: AsrModelSpec, dest: File): Boolean {
        val base = spec.mirrorBase ?: return false
        if (spec.mirrorFiles.isEmpty()) return false

        dest.mkdirs()
        val total = spec.mirrorFiles.sumOf { it.bytes }
        var done = 0L

        for (file in spec.mirrorFiles) {
            val target = File(dest, file.name)
            downloadTo(spec, base + file.name, target, file.bytes) { received, _ ->
                set(
                    spec.id,
                    ModelInstallState(
                        phase = ModelInstallPhase.DOWNLOADING,
                        received = done + received,
                        total = total,
                        progress = ratio(done + received, total),
                    ),
                )
            }
            if (file.sha256.isNotEmpty() &&
                !sha256(target).equals(file.sha256, ignoreCase = true)
            ) {
                throw IOException("${file.name} 校验失败")
            }
            done += file.bytes
        }

        return ModelStore.discoverFiles(dest) != null
    }

    private fun ratio(received: Long, total: Long): Float =
        if (total > 0L) (received.toFloat() / total).coerceIn(0f, 1f) else 0f

    private suspend fun downloadTo(
        spec: AsrModelSpec,
        url: String,
        dest: File,
        fallbackTotal: Long,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AudioScript-Android")
        }
        connections[spec.id] = connection

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")

            val total = connection.contentLengthLong.takeIf { it > 0L } ?: fallbackTotal
            var received = 0L
            var lastEmit = 0L

            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 150L) {
                            lastEmit = now
                            onProgress(received, total)
                        }
                    }
                }
            }
            onProgress(received, total)
        } finally {
            connections.remove(spec.id)
            connection.disconnect()
        }
    }

    private suspend fun extract(
        archive: File,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val total = archive.length().coerceAtLeast(1L)
        var lastEmit = 0L

        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()

                    val name = File(entry.name).name
                    if (entry.isFile && ModelStore.isModelFileName(name)) {
                        val output = File(dest, name)
                        require(
                            output.canonicalPath.startsWith(dest.canonicalPath + File.separator)
                        ) { "压缩包路径非法" }
                        FileOutputStream(output).use { tar.copyTo(it) }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 150L) {
                        lastEmit = now
                        onProgress((tar.bytesRead.toFloat() / total).coerceIn(0f, 1f))
                    }
                    entry = tar.nextEntry
                }
            }
        }
        onProgress(1f)
    }

    private fun replaceDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()

        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
