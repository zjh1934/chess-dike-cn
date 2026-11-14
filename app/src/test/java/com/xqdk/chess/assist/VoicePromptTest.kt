package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音提示决策（[VoicePrompt.resolve]）纯函数单测：
 * 支招招数 / 开关 / 建议文本 → 是否朗读 + 朗读文本，全部不依赖 Android。
 */
class VoicePromptTest {

    @Test
    fun `支一招且开关开则朗读`() {
        val d = VoicePrompt.resolve(moveCount = 1, enabled = true, suggestionText = "建议 炮二平五")
        assertTrue(d.shouldSpeak)
        assertEquals("炮二平五", d.text)
    }

    @Test
    fun `支两招不朗读`() {
        val d = VoicePrompt.resolve(moveCount = 2, enabled = true, suggestionText = "建议 炮二平五")
        assertFalse(d.shouldSpeak)
        assertEquals("", d.text)
    }

    @Test
    fun `支三招不朗读`() {
        val d = VoicePrompt.resolve(moveCount = 3, enabled = true, suggestionText = "建议 炮二平五")
        assertFalse(d.shouldSpeak)
        assertEquals("", d.text)
    }

    @Test
    fun `开关关闭不朗读`() {
        val d = VoicePrompt.resolve(moveCount = 1, enabled = false, suggestionText = "建议 炮二平五")
        assertFalse(d.shouldSpeak)
        assertEquals("", d.text)
    }

    @Test
    fun `朗读文本去掉建议前缀`() {
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议：炮二平五"))
        assertEquals("炮二平五", VoicePrompt.resolve(1, true, "建议 炮二平五").text)
    }

    @Test
    fun `空文本不朗读`() {
        assertFalse(VoicePrompt.resolve(1, true, "").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "   ").shouldSpeak)
        assertEquals("", VoicePrompt.moveText(""))
    }

    @Test
    fun `界面装饰后缀不改变朗读文本-保证同一着法不重复读`() {
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五（暂定）"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五 ✓"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五 ✓（预判β）"))
    }

    @Test
    fun `开局库建议同样去前缀朗读`() {
        assertEquals("炮二平五", VoicePrompt.moveText("开局库 炮二平五 ✓"))
        assertEquals("炮二平五", VoicePrompt.moveText("开局库备选2 炮二平五 ✓"))
        assertTrue(VoicePrompt.resolve(1, true, "开局库 炮二平五 ✓").shouldSpeak)
    }

    @Test
    fun `非建议文案不朗读`() {
        assertFalse(VoicePrompt.resolve(1, true, "引擎思考中…").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "轮次可能识别有误\n走一步后自动纠正").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "若对方走炮二平五\n我方应马八进七").shouldSpeak)
    }

    @Test
    fun `只有建议二字无着法不朗读`() {
        assertFalse(VoicePrompt.resolve(1, true, "建议").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "建议 ✓").shouldSpeak)
    }

    @Test
    fun `非法招数不朗读`() {
        assertFalse(VoicePrompt.resolve(0, true, "建议 炮二平五").shouldSpeak)
        assertFalse(VoicePrompt.resolve(-1, true, "建议 炮二平五").shouldSpeak)
    }

    // ===== v1.2 任务1：半角括号 UCCI 坐标剥离（真机日志 6 个样例）=====

    @Test
    fun `真机六个着法样例逐个剥离坐标`() {
        val samples = listOf(
            "炮二平五 (h2e2)" to "炮二平五",
            "炮八平五 (b2e2)" to "炮八平五",
            "兵三进一 (g3g4)" to "兵三进一",
            "车一平二 (i0h0)" to "车一平二",
            "马八进七 (b0c2)" to "马八进七",
            "仕四进五 (f0e1)" to "仕四进五",
        )
        samples.forEach { (raw, expected) ->
            assertEquals(expected, VoicePrompt.moveText("建议 $raw"))
            assertEquals(expected, VoicePrompt.stripCoordinates(raw))
        }
    }

    @Test
    fun `坐标与装饰后缀叠加仍只读纯中文着法`() {
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五 (h2e2) ✓（暂定）"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五 (h2e2)（暂定）（预判β）"))
        assertEquals("炮二平五", VoicePrompt.moveText("开局库 炮二平五 (h2e2) ✓"))
        assertEquals("炮八平五", VoicePrompt.moveText("备选2 炮八平五 (b2e2) ✓"))
    }

    @Test
    fun `坐标剥离不误伤纯中文着法与全角装饰括号`() {
        assertEquals("马三进二", VoicePrompt.moveText("建议 马三进二"))
        assertEquals("炮五平七", VoicePrompt.moveText("建议 炮五平七"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五（暂定）"))
        assertEquals("炮二平五", VoicePrompt.moveText("建议 炮二平五 ✓"))
        assertEquals("(none)", VoicePrompt.stripCoordinates("(none)"))
    }

    @Test
    fun `裸UCCI兜底也被剥离`() {
        assertEquals("", VoicePrompt.stripCoordinates("h2e2"))
        assertTrue(VoicePrompt.moveText("建议 h2e2").isEmpty())
    }

    @Test
    fun `对方回合预案去两个坐标并压平换行`() {
        val raw = "若对方走炮二平五 (h2e2)\n我方应马八进七 (b0c2)"
        assertEquals("若对方走炮二平五，我方应马八进七", VoicePrompt.planText(raw))
        val d = VoicePrompt.resolve(1, true, raw, readMask = VoicePrompt.Category.OPPONENT_PLAN.bit)
        assertTrue(d.shouldSpeak)
        assertEquals("若对方走炮二平五，我方应马八进七", d.text)
        assertEquals(VoicePrompt.Category.OPPONENT_PLAN, d.category)
    }

    // ===== v1.2 任务2：朗读内容多选决策 =====

    @Test
    fun `classify按前缀识别五类候选文案`() {
        assertEquals(VoicePrompt.Category.SUGGESTION, VoicePrompt.classify("建议 炮二平五 (h2e2) ✓"))
        assertEquals(VoicePrompt.Category.SUGGESTION, VoicePrompt.classify("备选2 炮八平五 (b2e2) ✓"))
        assertEquals(VoicePrompt.Category.BOOK, VoicePrompt.classify("开局库 炮二平五 (h2e2) ✓"))
        assertEquals(VoicePrompt.Category.BOOK, VoicePrompt.classify("开局库备选2 炮二平五 (h2e2) ✓"))
        assertEquals(VoicePrompt.Category.OPPONENT_PLAN,
            VoicePrompt.classify("若对方走炮二平五 (h2e2)\n我方应马八进七 (b0c2)"))
        assertEquals(VoicePrompt.Category.STATUS, VoicePrompt.classify("引擎思考中…"))
        assertEquals(VoicePrompt.Category.STATUS, VoicePrompt.classify("无可用着法\n（困毙或已无着可走）"))
        assertEquals(VoicePrompt.Category.NONE, VoicePrompt.classify(""))
        assertEquals(VoicePrompt.Category.NONE, VoicePrompt.classify("   "))
    }

    @Test
    fun `默认mask只读建议与开局库着法-与v1-1一致`() {
        assertEquals(VoicePrompt.Category.SUGGESTION.bit or VoicePrompt.Category.BOOK.bit,
            VoicePrompt.DEFAULT_MASK)
        assertTrue(VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)").shouldSpeak)
        assertTrue(VoicePrompt.resolve(1, true, "开局库 炮二平五 (h2e2) ✓").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true,
            "若对方走炮二平五 (h2e2)\n我方应马八进七 (b0c2)").shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "引擎思考中…").shouldSpeak)
        assertEquals("炮二平五", VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)").text)
    }

    @Test
    fun `readMask为零不朗读`() {
        val d = VoicePrompt.resolve(1, true, "建议 炮二平五", readMask = 0)
        assertFalse(d.shouldSpeak)
        assertEquals("", d.text)
        assertTrue(d.reason.contains("未勾选"))
    }

    @Test
    fun `只勾某类则该类读其它类不读`() {
        val planMask = VoicePrompt.Category.OPPONENT_PLAN.bit
        val plan = "若对方走炮二平五 (h2e2)\n我方应马八进七 (b0c2)"
        assertTrue(VoicePrompt.resolve(1, true, plan, readMask = planMask).shouldSpeak)
        assertFalse(VoicePrompt.resolve(1, true, "建议 炮二平五", readMask = planMask).shouldSpeak)

        val statusMask = VoicePrompt.Category.STATUS.bit
        val status = "轮次可能识别有误\n走一步后自动纠正"
        assertTrue(VoicePrompt.resolve(1, true, status, readMask = statusMask).shouldSpeak)
        assertEquals("轮次可能识别有误，走一步后自动纠正",
            VoicePrompt.resolve(1, true, status, readMask = statusMask).text)
        assertFalse(VoicePrompt.resolve(1, true, "建议 炮二平五", readMask = statusMask).shouldSpeak)
    }

    @Test
    fun `全开mask各主类别均可朗读`() {
        val all = VoicePrompt.maskOf(setOf(
            VoicePrompt.Category.SUGGESTION, VoicePrompt.Category.BOOK,
            VoicePrompt.Category.OPPONENT_PLAN, VoicePrompt.Category.EVALUATION,
            VoicePrompt.Category.STATUS))
        assertTrue(VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)", readMask = all).shouldSpeak)
        assertTrue(VoicePrompt.resolve(1, true, "开局库 炮二平五 (h2e2) ✓", readMask = all).shouldSpeak)
        assertTrue(VoicePrompt.resolve(1, true,
            "若对方走炮二平五 (h2e2)\n我方应马八进七 (b0c2)", readMask = all).shouldSpeak)
        assertTrue(VoicePrompt.resolve(1, true, "引擎思考中…", readMask = all).shouldSpeak)
    }

    @Test
    fun `勾选态势评估时把advantageText拼在主文本后`() {
        val mask = VoicePrompt.Category.SUGGESTION.bit or VoicePrompt.Category.EVALUATION.bit
        assertEquals("炮二平五，胜率约 55%",
            VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)", "胜率约 55%", mask).text)
        assertEquals("炮二平五，红方优势 32，深 20",
            VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)", "红方优势 32 深 20", mask).text)
        // 未勾 EVALUATION / advantageText 为空：都不拼接
        assertEquals("炮二平五", VoicePrompt.resolve(1, true, "建议 炮二平五 (h2e2)",
            "胜率约 55%", VoicePrompt.Category.SUGGESTION.bit).text)
        assertEquals("炮二平五", VoicePrompt.resolve(1, true, "建议 炮二平五", "", mask).text)
    }

    @Test
    fun `状态文案清洗去掉省略号并压平换行`() {
        assertEquals("引擎思考中", VoicePrompt.statusText("引擎思考中…"))
        assertEquals("轮次可能识别有误，走一步后自动纠正",
            VoicePrompt.statusText("轮次可能识别有误\n走一步后自动纠正"))
        val d = VoicePrompt.resolve(1, true, "引擎思考中…",
            readMask = VoicePrompt.Category.STATUS.bit)
        assertTrue(d.shouldSpeak)
        assertEquals("引擎思考中", d.text)
    }

    @Test
    fun `各类跳过原因正确`() {
        assertTrue(VoicePrompt.resolve(1, false, "建议 炮二平五").reason.contains("总开关"))
        assertTrue(VoicePrompt.resolve(2, true, "建议 炮二平五").reason.contains("非支一招"))
        assertTrue(VoicePrompt.resolve(1, true, "").reason.contains("空文本"))
        assertTrue(VoicePrompt.resolve(1, true, "引擎思考中…").reason.contains("未勾选"))
        assertEquals("", VoicePrompt.resolve(1, true, "建议 炮二平五").reason)
    }

    @Test
    fun `maskOf与categoriesOf互逆且忽略NONE`() {
        val set = setOf(VoicePrompt.Category.SUGGESTION, VoicePrompt.Category.BOOK,
            VoicePrompt.Category.EVALUATION)
        val mask = VoicePrompt.maskOf(set)
        assertEquals(set, VoicePrompt.categoriesOf(mask))
        assertEquals(emptySet<VoicePrompt.Category>(), VoicePrompt.categoriesOf(0))
        assertEquals(0, VoicePrompt.maskOf(setOf(VoicePrompt.Category.NONE)))
        assertTrue(VoicePrompt.categoriesOf(VoicePrompt.DEFAULT_MASK).containsAll(
            listOf(VoicePrompt.Category.SUGGESTION, VoicePrompt.Category.BOOK)))
    }

    @Test
    fun `Decision新增字段带默认值不影响旧断言`() {
        val d = VoicePrompt.resolve(1, true, "建议 炮二平五")
        assertEquals(VoicePrompt.Category.SUGGESTION, d.category)
        assertEquals("", d.reason)
        assertEquals(VoicePrompt.Category.NONE,
            VoicePrompt.resolve(0, true, "建议 炮二平五").category)
    }
}
