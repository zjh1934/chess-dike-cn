package com.xqdk.chess.assist

import com.xqdk.chess.assist.TapMode.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9「开箱即用」行为决策的纯 JVM 单测。
 * 核心不变量：**点棋盘永远派发 RECOGNIZE**（不管旧档位/脏值/快捷刷新开关），
 * 手动摆棋模式仍优先；旧常量与旧配置值只为迁移兼容保留，不再影响手势。
 */
class TapModeTest {

    @Test
    fun `默认档是点按出招-装上即不点不动`() {
        assertEquals(TapMode.TAP_SUGGEST, TapMode.DEFAULT)
        assertTrue(TapMode.isTapDriven(null))
        assertFalse(TapMode.isAuto(null))
    }

    @Test
    fun `四档全集与互斥语义`() {
        assertEquals(4, TapMode.ALL.size)
        assertEquals(
            setOf(TapMode.TAP_SUGGEST, TapMode.TAP_REFRESH, TapMode.TAP_CANDIDATE, TapMode.AUTO),
            TapMode.ALL.toSet(),
        )
        assertTrue(TapMode.ALL.all { TapMode.normalize(it) == it })
        assertTrue(TapMode.isAuto(TapMode.AUTO))
        assertFalse(TapMode.isAuto(TapMode.TAP_REFRESH))
    }

    @Test
    fun `脏值与空值归位默认档`() {
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalize(null))
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalize(""))
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalize("tap_trigger_enabled"))
        assertEquals(TapMode.AUTO, TapMode.normalize("auto"))
        assertFalse(TapMode.isValid("voice-1.4"))
        assertTrue(TapMode.isValid(TapMode.TAP_CANDIDATE))
    }

    @Test
    fun `v1_9 任何旧档位点棋盘都派发 RECOGNIZE-链路不再被劫持`() {
        val modes: List<String?> = TapMode.ALL + listOf<String?>(null, "garbage")
        for (m in modes) {
            for (quick in listOf(true, false)) {
                assertEquals("mode=$m quickRefresh=$quick 必须走识别支招链",
                    Action.RECOGNIZE, TapMode.resolveAction(m, false, quick))
            }
        }
    }

    @Test
    fun `手动摆棋优先级最高-任何档位都不抢点格`() {
        val cases: List<String?> = TapMode.ALL + listOf<String?>(null, "garbage")
        for (m in cases) {
            assertEquals("mode=$m", Action.NONE, TapMode.resolveAction(m, true, true))
        }
    }

    @Test
    fun `labelOf 与 hintOf 覆盖四档且非空`() {
        assertEquals("点按出招", TapMode.labelOf(TapMode.TAP_SUGGEST))
        assertEquals("点按刷新", TapMode.labelOf(TapMode.TAP_REFRESH))
        assertEquals("点按变招", TapMode.labelOf(TapMode.TAP_CANDIDATE))
        assertEquals("自动识别", TapMode.labelOf(TapMode.AUTO))
        for (m in TapMode.ALL) assertTrue(TapMode.hintOf(m).isNotBlank())
        assertEquals("点按出招", TapMode.labelOf("garbage"))
    }

    @Test
    fun `switchText 开与关`() {
        assertEquals("开", TapMode.switchText(true))
        assertEquals("关", TapMode.switchText(false))
    }

    @Test
    fun `v1_8 点棋盘动作只允许前三档 AUTO 与脏值归位`() {
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalizeAction(TapMode.TAP_SUGGEST))
        assertEquals(TapMode.TAP_REFRESH, TapMode.normalizeAction(TapMode.TAP_REFRESH))
        assertEquals(TapMode.TAP_CANDIDATE, TapMode.normalizeAction(TapMode.TAP_CANDIDATE))
        // 历史「自动识别」是第四档，v1.8 拆成独立开关后不再作为点棋盘动作
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalizeAction(TapMode.AUTO))
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalizeAction(null))
        assertEquals(TapMode.TAP_SUGGEST, TapMode.normalizeAction("garbage"))
    }

    @Test
    fun `v1_9 摘要固定一句话-任何旧配置都不改变行为`() {
        val expected = "不点棋盘完全不动 ＋ 点一下棋盘＝这一帧直接算 → 支招 → 语音播报"
        assertEquals(expected, TapMode.summary())
        assertEquals(expected, TapMode.summary(TapMode.AUTO, true))
        assertEquals(expected, TapMode.summary(TapMode.TAP_CANDIDATE, false))
        assertTrue(TapMode.hintOf(TapMode.AUTO).contains("不用设置"))
    }
}
