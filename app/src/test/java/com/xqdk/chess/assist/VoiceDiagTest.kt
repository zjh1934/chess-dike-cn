package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(doc.contains("# XQDK 增强功能诊断"))
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
            "XQDK_增强功能诊断_20261115_100000.md",
            VoiceDiag.exportFileName("20261115_100000")
        )
        assertTrue(VoiceDiag.exportFileName("x").endsWith(".md"))
    }

    @Test
    fun `表头为三列时间标签详情`() {
        assertTrue(VoiceDiag.TABLE_HEADER.startsWith("| 时间 | 标签 | 详情 |"))
        assertTrue(VoiceDiag.TABLE_HEADER.contains("|---|---|---|"))
    }

    // ===== v1.2 任务5：版本号展示修正 =====

    @Test
    fun `displayVersion补回被削掉的v首字母`() {
        assertEquals("voice-1.0-2-gb5f34d0", VoiceDiag.displayVersion("oice-1.0-2-gb5f34d0"))
        assertEquals("3.2", VoiceDiag.displayVersion("3.2"))
        assertEquals("", VoiceDiag.displayVersion(""))
        assertEquals("voice-1.2", VoiceDiag.displayVersion("voice-1.2"))
    }

    @Test
    fun `增强功能诊断模块版本常量稳定`() {
        assertEquals("1.14", VoiceDiag.VOICE_MODULE_VERSION)
    }

    @Test
    fun `v1_7高频标签跨交错聚合口径`() {
        assertEquals(5000L, VoiceDiag.COMPACT_WINDOW_MS)
        assertTrue(VoiceDiag.isCompactTag("决策"))
        assertTrue(VoiceDiag.isCompactTag("去重"))
        assertTrue(VoiceDiag.isCompactTag("状态文案"))
        // 关键事件必须逐条保留，不能被聚合抹掉排障时间线
        assertFalse(VoiceDiag.isCompactTag("点按小票"))
        assertFalse(VoiceDiag.isCompactTag("识别结果"))
        assertFalse(VoiceDiag.isCompactTag("点按出招"))
        assertFalse(VoiceDiag.isCompactTag("录屏停止"))
    }

    @Test
    fun `诊断覆盖范围写明点按出招与支招调度`() {
        val s = VoiceDiag.scopeLine()
        assertTrue(s.contains("点按出招会话"))
        assertTrue(s.contains("识别闸口"))
        assertTrue(s.contains("识别帧丢弃原因"))
        assertTrue(s.contains("支招调度"))
        assertTrue(s.contains("中文播报"))
    }

    @Test
    fun `文档头部补包名识别方式与覆盖范围`() {
        val block = VoiceDiag.metaBlock("1.14", "点按出招")
        assertTrue(block.contains("- 版本：1.14"))
        assertTrue(block.contains("- 包名：com.xqdk.chess"))
        assertTrue(block.contains("- 识别方式：点按出招"))
        assertTrue(block.contains("- 诊断覆盖："))
        assertTrue(block.contains("- 诊断模块版本：1.14"))
    }

    @Test
    fun `导出文档隐私提示写明覆盖链路且不记敏感内容`() {
        val doc = VoiceDiag.buildExportDocument("d", "1.14", 36, "ts", emptyList())
        assertTrue(doc.contains("# XQDK 增强功能诊断"))
        assertTrue(doc.contains("点按出招会话"))
        assertTrue(doc.contains("不含识别帧像素"))
        assertTrue(doc.contains("FEN 具体内容"))
    }

    // ===== v1.2 任务5：刷屏聚合纯函数 =====

    @Test
    fun `mergeConsecutive聚合连续相同条目并取最新时间`() {
        val rows = listOf(
            VoiceDiag.Entry("t1", "去重", "跳过重复：炮二平五", 1),
            VoiceDiag.Entry("t2", "去重", "跳过重复：炮二平五", 1),
            VoiceDiag.Entry("t3", "去重", "跳过重复：炮二平五", 1),
            VoiceDiag.Entry("t4", "打断", "QUEUE_FLUSH 覆盖上一段", 1),
        )
        val merged = VoiceDiag.mergeConsecutive(rows)
        assertEquals(2, merged.size)
        assertEquals(3, merged[0].count)
        assertEquals("t3", merged[0].time)
        assertEquals("打断", merged[1].tag)
        assertEquals(1, merged[1].count)
    }

    @Test
    fun `mergeConsecutive相邻不同不聚合且count=1原样`() {
        val rows = listOf(
            VoiceDiag.Entry("t1", "去重", "A", 1),
            VoiceDiag.Entry("t2", "打断", "A", 1),
            VoiceDiag.Entry("t3", "去重", "A", 1),
        )
        val merged = VoiceDiag.mergeConsecutive(rows)
        assertEquals(3, merged.size)
        merged.forEach { assertEquals(1, it.count) }
    }

    @Test
    fun `mergeConsecutive空列表与已有count累加`() {
        assertEquals(emptyList<VoiceDiag.Entry>(), VoiceDiag.mergeConsecutive(emptyList()))
        val merged = VoiceDiag.mergeConsecutive(listOf(
            VoiceDiag.Entry("t1", "x", "y", 5),
            VoiceDiag.Entry("t2", "x", "y", 2),
        ))
        assertEquals(1, merged.size)
        assertEquals(7, merged[0].count)
    }

    @Test
    fun `聚合后行数受上限约束`() {
        val rows = (1..10).map { VoiceDiag.Entry("t", "去重", "同一条", 1) }
        val merged = VoiceDiag.mergeConsecutive(rows)
        assertEquals(1, merged.size)
        assertEquals(10, merged[0].count)
        val rendered = merged.map {
            VoiceDiag.formatRowWithCount(it.time, it.tag, it.detail, it.count)
        }
        assertEquals(rendered, VoiceDiag.trimToLimit(rendered, VoiceDiag.MAX_LINES))
    }

    @Test
    fun `formatRowWithCount含计数行与count=1原样`() {
        val base = VoiceDiag.formatRow("t", "去重", "跳过重复：炮二平五")
        assertEquals(base, VoiceDiag.formatRowWithCount("t", "去重", "跳过重复：炮二平五", 1))
        assertEquals(base, VoiceDiag.formatRowWithCount("t", "去重", "跳过重复：炮二平五", 0))
        val withCount = VoiceDiag.formatRowWithCount("t", "去重", "跳过重复：炮二平五", 3)
        assertTrue(withCount.contains("（连续 3 次）"))
        assertTrue(withCount.startsWith("| "))
        assertTrue(withCount.endsWith(" |\n"))
    }

    @Test
    fun `含计数行仍转义竖线并保持三列`() {
        val row = VoiceDiag.formatRowWithCount("t", "speak", "a|b", 2)
        assertTrue(row.contains("a\\|b"))
        assertTrue(row.contains("（连续 2 次）"))
    }

    @Test
    fun `含计数行可进导出文档`() {
        val rows = listOf(VoiceDiag.formatRowWithCount("t", "去重", "跳过重复：炮二平五", 5))
        val doc = VoiceDiag.buildExportDocument("d", "v", 33, "ts", rows)
        assertTrue(doc.contains("（连续 5 次）"))
        assertTrue(doc.contains(VoiceDiag.TABLE_HEADER))
        assertTrue(doc.contains("- 记录行数：1"))
    }
}
