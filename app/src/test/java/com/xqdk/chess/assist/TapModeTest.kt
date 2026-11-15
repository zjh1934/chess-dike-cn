package com.xqdk.chess.assist

import com.xqdk.chess.assist.TapMode.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别方式」四选一（v1.5）行为决策的纯 JVM 单测。
 * 核心不变量：默认点按出招（不点不动）、四档互斥且脏值归位、手动摆棋优先。
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
    fun `点按出招派发 RECOGNIZE-其余三档各自派发`() {
        assertEquals(Action.RECOGNIZE, TapMode.resolveAction(TapMode.TAP_SUGGEST, false, true))
        assertEquals(Action.REFRESH, TapMode.resolveAction(TapMode.TAP_REFRESH, false, false))
        assertEquals(Action.CANDIDATE, TapMode.resolveAction(TapMode.TAP_CANDIDATE, false, true))
        assertEquals(Action.REFRESH, TapMode.resolveAction(TapMode.AUTO, false, true))
        assertEquals(Action.CANDIDATE, TapMode.resolveAction(TapMode.AUTO, false, false))
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
}
