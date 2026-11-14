package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音诊断日志纯函数单测：Markdown 行格式化、上限裁剪、导出文档组装、文件名。
 * 全部只依赖 kotlin/jvm 标准库，零 Android 框架类依赖，可在本地 JVM 单元测试直接跑。
 */
class VoiceDiagTest {

    @Test
    fun `日志行格式化为标准三列表格行`() {
        assertEquals(
            "| 12:00:00.123 | onInit | SUCCESS |\n",
            VoiceDiag.formatRow("12:00:00.123", "onInit", "SUCCESS")
        )
    }

    @Test
    fun `竖线被转义不破坏表格列`() {
        val row = VoiceDiag.formatRow("t", "speak", "a|b|c")
        assertTrue(row.contains("a\\|b\\|c"))
        assertTrue(row.startsWith("| "))
        assertTrue(row.endsWith(" |\n"))
    }

    @Test
    fun `换行被压平为空格`() {
        val row = VoiceDiag.formatRow("t", "x", "第一行\n第二行\r第三行")
        assertTrue(!row.contains("第一行\n第二行"))
        assertTrue(row.contains("第一行 第二行 第三行"))
    }

    @Test
    fun `未超限时裁剪原样返回`() {
        val rows = listOf("a", "b", "c")
        assertEquals(rows, VoiceDiag.trimToLimit(rows, VoiceDiag.MAX_LINES))
        assertEquals(rows, VoiceDiag.trimToLimit(rows, 3))
    }

    @Test
    fun `超限保留最近N行`() {
        val rows = (1..5).map { "row$it" }
        assertEquals(listOf("row4", "row5"), VoiceDiag.trimToLimit(rows, 2))
    }

    @Test
    fun `上限非正返回空列表`() {
        assertEquals(emptyList<String>(), VoiceDiag.trimToLimit(listOf("a", "b"), 0))
        assertEquals(emptyList<String>(), VoiceDiag.trimToLimit(listOf("a", "b"), -1))
    }

    @Test
    fun `导出文档含标题设备版本表头与全部行`() {
        val rows = listOf("| t | onInit | SUCCESS |\n", "| t2 | speak | SUCCESS |\n")
        val doc = VoiceDiag.buildExportDocument("Pixel 7", "1.1", 33, "2026-11-15 10:00:00", rows)
        assertTrue(doc.contains("# XQDK 语音功能诊断"))
        assertTrue(doc.contains("- 设备：Pixel 7"))
        assertTrue(doc.contains("- 版本：1.1"))
        assertTrue(doc.contains("- Android SDK：33"))
        assertTrue(doc.contains("- 导出时间：2026-11-15 10:00:00"))
        assertTrue(doc.contains("- 记录行数：2"))
        assertTrue(doc.contains(VoiceDiag.TABLE_HEADER))
        assertTrue(doc.contains("| t | onInit | SUCCESS |"))
        assertTrue(doc.contains("| t2 | speak | SUCCESS |"))
    }

    @Test
    fun `导出文档无行时仍含表头`() {
        val doc = VoiceDiag.buildExportDocument("d", "v", 26, "ts", emptyList())
        assertTrue(doc.contains(VoiceDiag.TABLE_HEADER))
        assertTrue(doc.contains("- 记录行数：0"))
    }

    @Test
    fun `导出文件名格式`() {
        assertEquals(
            "XQDK_语音诊断_20261115_100000.md",
            VoiceDiag.exportFileName("20261115_100000")
        )
        assertTrue(VoiceDiag.exportFileName("x").endsWith(".md"))
    }

    @Test
    fun `表头为三列时间标签详情`() {
        assertTrue(VoiceDiag.TABLE_HEADER.startsWith("| 时间 | 标签 | 详情 |"))
        assertTrue(VoiceDiag.TABLE_HEADER.contains("|---|---|---|"))
    }
}
