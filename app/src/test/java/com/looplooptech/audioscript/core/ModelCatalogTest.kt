package com.looplooptech.audioscript.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型目录的静态检查：语言 → 模型的选择、日文模型的下载/镜像元数据。 */
class ModelCatalogTest {

    @Test
    fun `源语言决定识别模型`() {
        assertEquals(ModelCatalog.japanese, ModelCatalog.forLanguage("ja"))
        assertEquals(ModelCatalog.builtinZhEn, ModelCatalog.forLanguage("zh"))
        assertEquals(ModelCatalog.builtinZhEn, ModelCatalog.forLanguage("en"))
    }

    @Test
    fun `内置中英模型必须随包存在`() {
        val builtin = ModelCatalog.builtinZhEn
        assertTrue(builtin.requiredInApk)
        assertNotNull(builtin.assetDir)
        assertEquals(AsrPipeline.STREAMING, builtin.pipeline)
        assertFalse(builtin.downloadable)
    }

    @Test
    fun `日文模型走 VAD 离线管线并带镜像清单`() {
        val spec = ModelCatalog.japanese

        assertEquals("ja-reazon", spec.id)
        assertEquals("models/asr-ja-reazon", spec.assetDir)
        assertEquals(AsrPipeline.VAD_OFFLINE, spec.pipeline)
        assertNull(spec.archiveUrl)
        assertTrue(spec.downloadable)

        assertNotNull(spec.mirrorBase)
        assertTrue(spec.mirrorBase!!.endsWith("/"))
        assertEquals(4, spec.mirrorFiles.size)

        val names = spec.mirrorFiles.map { it.name }.toSet()
        assertEquals(
            setOf(
                "tokens.txt",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.onnx",
            ),
            names,
        )

        spec.mirrorFiles.forEach { file ->
            assertEquals("sha256 长度", 64, file.sha256.length)
            assertTrue("${file.name} 的大小", file.bytes > 0L)
        }
        assertEquals(spec.mirrorFiles.sumOf { it.bytes }, spec.downloadBytes)

        // 镜像文件名必须能被 ModelStore 识别出来（解压/下载后要能定位到模型）。
        spec.mirrorFiles
            .filter { it.name.endsWith(".onnx") }
            .forEach { assertTrue(it.name, ModelStore.isModelFileName(it.name)) }
        assertTrue(ModelStore.isModelFileName("tokens.txt"))
    }
}
