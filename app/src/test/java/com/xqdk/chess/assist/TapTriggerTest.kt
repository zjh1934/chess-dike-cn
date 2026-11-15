package com.xqdk.chess.assist

import com.xqdk.chess.assist.TapTrigger.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点按触发识别（v1.4）行为决策的纯 JVM 单测：零 android.* 依赖，直接跑纯函数。
 * 核心不变量：默认开 = 不点棋盘零识别；手动摆棋永远优先，点格不被抢走。
 */
class TapTriggerTest {

    @Test
    fun `默认开-装上即点棋盘才识别`() {
        assertTrue("v1.4 必须默认开：不点棋盘一律零识别/零推理/零支招", TapTrigger.DEFAULT_ENABLED)
    }

    @Test
    fun `开启且非手动时点棋盘启动一轮识别`() {
        assertEquals(Action.RECOGNIZE, TapTrigger.resolve(true, false))
    }

    @Test
    fun `关闭时退回旧行为但依然不自动识别`() {
        assertEquals(Action.LEGACY, TapTrigger.resolve(false, false))
    }

    @Test
    fun `手动摆棋优先级最高-开关开与关都不抢点格`() {
        assertEquals(Action.NONE, TapTrigger.resolve(true, true))
        assertEquals(Action.NONE, TapTrigger.resolve(false, true))
    }

    @Test
    fun `resolve 的 manualMode 默认参数是 false`() {
        assertEquals(Action.LEGACY, TapTrigger.resolve(false))
        assertEquals(Action.RECOGNIZE, TapTrigger.resolve(true))
    }

    @Test
    fun `switchText 开与关`() {
        assertEquals("开", TapTrigger.switchText(true))
        assertEquals("关", TapTrigger.switchText(false))
    }

    @Test
    fun `开关往返 true-false-true 结果一致`() {
        var enabled = TapTrigger.DEFAULT_ENABLED
        assertEquals(Action.RECOGNIZE, TapTrigger.resolve(enabled))
        enabled = !enabled
        assertEquals(Action.LEGACY, TapTrigger.resolve(enabled))
        enabled = !enabled
        assertEquals(Action.RECOGNIZE, TapTrigger.resolve(enabled))
    }

    @Test
    fun `Action 枚举全集数量为 3`() {
        assertEquals(3, Action.values().size)
        assertEquals(setOf(Action.RECOGNIZE, Action.LEGACY, Action.NONE), Action.values().toSet())
    }
}
