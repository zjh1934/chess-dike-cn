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
}
