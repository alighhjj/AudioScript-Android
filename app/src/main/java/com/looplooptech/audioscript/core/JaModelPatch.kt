package com.looplooptech.audioscript.core

import java.io.File
import java.io.RandomAccessFile

/**
 * 多语种模型（PengChengStarling 系列）的「首帧语言标签」补丁。
 *
 * 这个模型训练时把 `<SOS>` 换成了语言标签（日文是 `<JA>`，token id = 6），
 * 但 sherpa-onnx 的解码器首帧固定喂 `[pad, <blk>]`，官方也明确说不支持指定语言标签，
 * 而这个上下文在训练里从没出现过，会明显拉低日文识别质量。
 *
 * 解码器结构很简单：`y -> Clip -> Gather(decoder.embedding.weight)`。
 * 首帧的 `y` 是 `[-1, 0]`：`-1` 是左侧 padding（随后被 mask 成零向量），`0` 是 `<blk>`。
 * `<blk>` 只会出现在首帧，后续解码出来的 token 不会再有它，
 * 所以把 embedding 第 0 行原地换成第 [langTagTokenId] 行，
 * 首个上下文就变成训练时的 `[pad, <JA>]`，与官方训练完全一致。
 *
 * 补丁就地改写已解压的 decoder onnx（文件长度不变），失败时返回 false 并保持原文件。
 */
object JaModelPatch {

    private const val MODEL_PROTO_GRAPH = 7
    private const val GRAPH_INITIALIZER = 5

    private const val TENSOR_DIMS = 1
    private const val TENSOR_DATA_TYPE = 2
    private const val TENSOR_NAME = 8
    private const val TENSOR_RAW_DATA = 9

    private const val WIRE_VARINT = 0
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED64 = 1
    private const val WIRE_FIXED32 = 5

    private const val DATA_TYPE_FLOAT = 1
    private const val EMBEDDING_TENSOR = "decoder.embedding.weight"

    /** 把 decoder 里 `<blk>`（第 0 行）的 embedding 换成 [langTagTokenId] 对应行。 */
    fun apply(decoder: File, langTagTokenId: Int): Boolean {
        if (!decoder.isFile) return false
        val length = decoder.length()
        if (length <= 0L) return false

        return try {
            RandomAccessFile(decoder, "rw").use { file ->
                val graph = findField(file, 0L, length, MODEL_PROTO_GRAPH) ?: return false

                file.seek(graph.start)
                while (file.filePointer < graph.end) {
                    val field = readField(file, graph.end) ?: return false
                    if (field.id != GRAPH_INITIALIZER || field.wire != WIRE_BYTES) continue

                    val tensor = readTensor(file, field) ?: continue
                    if (tensor.name == EMBEDDING_TENSOR) {
                        return patchEmbedding(file, tensor, langTagTokenId)
                    }
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun patchEmbedding(
        file: RandomAccessFile,
        tensor: Tensor,
        langTagTokenId: Int,
    ): Boolean {
        if (tensor.dataType != DATA_TYPE_FLOAT) return false
        if (tensor.dims.size != 2) return false

        val rows = tensor.dims[0]
        val cols = tensor.dims[1]
        if (rows <= 0L || cols <= 0L || cols > 1_000_000L) return false
        if (langTagTokenId < 0 || langTagTokenId >= rows) return false

        val rowBytes = cols * 4L
        if (tensor.rawEnd - tensor.rawStart != rows * rowBytes) return false

        val buffer = ByteArray(rowBytes.toInt())
        file.seek(tensor.rawStart + langTagTokenId * rowBytes)
        file.readFully(buffer)
        file.seek(tensor.rawStart)
        file.write(buffer)
        return true
    }

    private fun findField(file: RandomAccessFile, start: Long, end: Long, id: Int): Field? {
        file.seek(start)
        while (file.filePointer < end) {
            val field = readField(file, end) ?: return null
            if (field.id == id && field.wire == WIRE_BYTES) return field
        }
        return null
    }

    /** 只做「定位」，大块数据一律用 seek 跳过，不会把 33 MB 的 decoder 读进内存。 */
    private fun readField(file: RandomAccessFile, limit: Long): Field? {
        val key = readVarint(file) ?: return null
        val id = (key ushr 3).toInt()
        val wire = (key and 0x7L).toInt()

        return when (wire) {
            WIRE_VARINT -> Field(id, wire, readVarint(file) ?: return null, 0L, 0L)

            WIRE_BYTES -> {
                val size = readVarint(file) ?: return null
                val start = file.filePointer
                val end = start + size
                if (size < 0L || end > limit) return null
                file.seek(end)
                Field(id, wire, size, start, end)
            }

            WIRE_FIXED64 -> {
                val start = file.filePointer
                if (start + 8 > limit) return null
                file.seek(start + 8)
                Field(id, wire, 0L, start, start + 8)
            }

            WIRE_FIXED32 -> {
                val start = file.filePointer
                if (start + 4 > limit) return null
                file.seek(start + 4)
                Field(id, wire, 0L, start, start + 4)
            }

            else -> null
        }
    }

    private fun readVarint(file: RandomAccessFile): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = file.read()
            if (byte < 0) return null
            result = result or ((byte.toLong() and 0x7fL) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        return null
    }

    private fun readTensor(file: RandomAccessFile, field: Field): Tensor? {
        var name = ""
        val dims = ArrayList<Long>(4)
        var dataType = 0
        var rawStart = -1L
        var rawEnd = -1L

        file.seek(field.start)
        while (file.filePointer < field.end) {
            val item = readField(file, field.end) ?: return null
            when (item.id) {
                TENSOR_DIMS -> when (item.wire) {
                    WIRE_VARINT -> dims.add(item.varint)
                    WIRE_BYTES -> {
                        val back = file.filePointer
                        file.seek(item.start)
                        while (file.filePointer < item.end) {
                            dims.add(readVarint(file) ?: return null)
                        }
                        file.seek(back)
                    }

                    else -> Unit
                }

                TENSOR_DATA_TYPE -> if (item.wire == WIRE_VARINT) dataType = item.varint.toInt()

                TENSOR_NAME -> if (item.wire == WIRE_BYTES) name = readString(file, item)

                TENSOR_RAW_DATA -> if (item.wire == WIRE_BYTES) {
                    rawStart = item.start
                    rawEnd = item.end
                }

                else -> Unit
            }
        }
        return Tensor(name, dims, dataType, rawStart, rawEnd)
    }

    private fun readString(file: RandomAccessFile, field: Field): String {
        val size = (field.end - field.start).toInt()
        if (size <= 0 || size > 4096) return ""
        val bytes = ByteArray(size)
        file.seek(field.start)
        file.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private class Field(
        val id: Int,
        val wire: Int,
        val varint: Long,
        val start: Long,
        val end: Long,
    )

    private class Tensor(
        val name: String,
        val dims: List<Long>,
        val dataType: Int,
        val rawStart: Long,
        val rawEnd: Long,
    )
}
