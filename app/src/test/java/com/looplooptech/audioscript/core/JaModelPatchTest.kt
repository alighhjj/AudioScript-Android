package com.looplooptech.audioscript.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * `JaModelPatch` 的纯 JVM 单测：不依赖 Android，CI 里直接跑。
 *
 * 用最小可用的 ONNX protobuf 构造一个带 `decoder.embedding.weight` 的 decoder，
 * 校验补丁「把第 0 行换成第 6 行、其余字节不动、文件长度不变」。
 *
 * 设置了 `-PjaDecoder=<path>` 时，额外拿真实的日文 decoder 跑一遍并核对 sha256，
 * 见 .github/workflows/android-build.yml 的「校验日文语言标签补丁」选项。
 */
class JaModelPatchTest {

    private val rows = 8
    private val cols = 4
    private val rowBytes = cols * 4

    private fun row(seed: Int): ByteArray =
        ByteArray(rowBytes) { index -> (seed * 17 + index).toByte() }

    @Test
    fun `把 blk 那一行换成语言标签行`() {
        val original = model(
            graph(
                listOf(
                    tensor("decoder.conv.weight", 3, 4) { row(it + 20) },
                    tensor(EMBEDDING, rows, cols) { row(it) },
                )
            )
        )
        // 期望结果：只把第 0 行换成第 6 行，别的字节一个不动。
        val expected = model(
            graph(
                listOf(
                    tensor("decoder.conv.weight", 3, 4) { row(it + 20) },
                    tensor(EMBEDDING, rows, cols) { if (it == 0) row(6) else row(it) },
                )
            )
        )

        val file = tempFile(original)
        assertTrue(JaModelPatch.apply(file, 6))
        assertArrayEquals(expected, file.readBytes())
        assertEquals(original.size.toLong(), file.length())
    }

    @Test
    fun `没有 embedding 张量时不动文件`() {
        val original = model(graph(listOf(tensor("decoder.conv.weight", 3, 4) { row(it) })))
        val file = tempFile(original)

        assertFalse(JaModelPatch.apply(file, 6))
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `token id 越界时不动文件`() {
        val original = model(graph(listOf(tensor(EMBEDDING, rows, cols) { row(it) })))
        val file = tempFile(original)

        assertFalse(JaModelPatch.apply(file, rows))
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `文件不存在或不是 onnx 时返回 false`() {
        assertFalse(JaModelPatch.apply(File("/definitely/not/here.onnx"), 6))

        val junk = tempFile(ByteArray(64) { 0xFF.toByte() })
        assertFalse(JaModelPatch.apply(junk, 6))
        assertEquals(64L, junk.length())
    }

    @Test
    fun `真实日文 decoder 打补丁后 sha256 稳定`() {
        val path = System.getProperty("ja.decoder.path").orEmpty()
        assumeTrue("未传入 -PjaDecoder，跳过真实模型校验", path.isNotBlank())

        val source = File(path)
        // 参数已经传入却找不到文件，说明 CI 下载/路径出了问题，必须失败而不是跳过。
        assertTrue("找不到 $path", source.isFile)

        val copy = File.createTempFile("ja-decoder", ".onnx").apply { deleteOnExit() }
        source.copyTo(copy, overwrite = true)
        val sizeBefore = copy.length()

        assertTrue(JaModelPatch.apply(copy, 6))
        assertEquals(sizeBefore, copy.length())
        // 33,837,085 字节的 int8 encoder + fp32 decoder 模型，补丁后逐字节固定。
        assertEquals(PATCHED_SHA256, sha256(copy))
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("decoder", ".onnx").apply {
            deleteOnExit()
            writeBytes(bytes)
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
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    // ---- 最小 ONNX protobuf 构造 -------------------------------------------------

    private fun tensor(name: String, dims0: Int, dims1: Int, fill: (Int) -> ByteArray): ByteArray {
        val dims = ByteArrayOutputStream().apply {
            writeVarint(dims0.toLong())
            writeVarint(dims1.toLong())
        }
        val raw = ByteArrayOutputStream().apply {
            for (index in 0 until dims0) write(fill(index))
        }
        val tensor = ByteArrayOutputStream()
        tensor.write(bytesField(1, dims.toByteArray()))
        tensor.write(varintField(2, 1L)) // TensorProto.DataType.FLOAT
        tensor.write(bytesField(8, name.toByteArray()))
        tensor.write(bytesField(9, raw.toByteArray()))
        return tensor.toByteArray()
    }

    private fun graph(tensors: List<ByteArray>): ByteArray {
        val graph = ByteArrayOutputStream()
        // 混一个 node，确保解析器会跳过非 initializer 字段。
        graph.write(bytesField(1, bytesField(3, "node_name".toByteArray())))
        tensors.forEach { graph.write(bytesField(5, it)) }
        return graph.toByteArray()
    }

    private fun model(graph: ByteArray): ByteArray {
        val model = ByteArrayOutputStream()
        model.write(varintField(1, 7L)) // ir_version
        model.write(bytesField(3, "test".toByteArray()))
        model.write(bytesField(7, graph)) // graph
        model.write(bytesField(14, bytesField(1, "produced_by".toByteArray())))
        return model.toByteArray()
    }

    private fun varintField(field: Int, value: Long): ByteArray =
        ByteArrayOutputStream().apply {
            writeTag(field, 0)
            writeVarint(value)
        }.toByteArray()

    private fun bytesField(field: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeTag(field, 2)
            writeVarint(payload.size.toLong())
            write(payload)
        }.toByteArray()

    private fun ByteArrayOutputStream.writeTag(field: Int, wire: Int) =
        writeVarint(((field shl 3) or wire).toLong())

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var rest = value
        while (true) {
            val byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                write(byte)
                return
            }
            write(byte or 0x80)
        }
    }

    private companion object {
        const val EMBEDDING = "decoder.embedding.weight"
        const val PATCHED_SHA256 =
            "1d822929242004647cd9c41881599c52918254a8dbfd502933f9b20b61c962a2"
    }
}
