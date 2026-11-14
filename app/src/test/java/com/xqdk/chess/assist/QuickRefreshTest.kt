package com.xqdk.chess.assist

import com.xqdk.chess.assist.QuickRefresh.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 快捷刷新（v1.3）行为决策的纯 JVM 单测：零 android.* 依赖，直接跑 QuickRefresh 纯函数。
 * 中文反引号测试名便于失败时一眼定位规则。
 */
class QuickRefreshTest {

    @Test
    fun `默认开关为关`() {
        assertFalse("快捷刷新必须默认关，不侵入上游点棋盘=切换候选的行为", QuickRefresh.DEFAULT_ENABLED)
    }

    @Test
    fun `关闭且非手动时点棋盘仍是切换候选着法`() {
        assertEquals(TapAction.NEXT_CANDIDATE, QuickRefresh.resolve(false, false))
    }

    @Test
    fun `开启且非手动时点棋盘等于刷新棋局`() {
        assertEquals(TapAction.REFRESH, QuickRefresh.resolve(true, false))
    }

    @Test
    fun `关闭但手动模式时点棋盘是手动摆棋点格`() {
        assertEquals(TapAction.MANUAL_CELL, QuickRefresh.resolve(false, true))
    }

    @Test
    fun `开启但手动模式时点棋盘仍是手动摆棋点格-手动优先`() {
        // 手动摆棋优先级最高：即便开关已开，也不能抢走点格
        assertEquals(TapAction.MANUAL_CELL, QuickRefresh.resolve(true, true))
    }

    @Test
    fun `resolve 的 manualMode 默认参数是 false`() {
        assertEquals(TapAction.NEXT_CANDIDATE, QuickRefresh.resolve(false))
        assertEquals(TapAction.REFRESH, QuickRefresh.resolve(true))
    }

    @Test
    fun `switchText 开与关`() {
        assertEquals("开", QuickRefresh.switchText(true))
        assertEquals("关", QuickRefresh.switchText(false))
    }

    @Test
    fun `labelOf 覆盖三个枚举值`() {
        assertEquals("切换候选着法", QuickRefresh.labelOf(TapAction.NEXT_CANDIDATE))
        assertEquals("刷新棋局（等同点『更新』）", QuickRefresh.labelOf(TapAction.REFRESH))
        assertEquals("手动摆棋点格", QuickRefresh.labelOf(TapAction.MANUAL_CELL))
    }

    @Test
    fun `开关往返 false-true-false 结果一致`() {
        var enabled = QuickRefresh.DEFAULT_ENABLED
        assertEquals(TapAction.NEXT_CANDIDATE, QuickRefresh.resolve(enabled))
        enabled = !enabled // 开
        assertEquals(TapAction.REFRESH, QuickRefresh.resolve(enabled))
        enabled = !enabled // 关
        assertEquals(TapAction.NEXT_CANDIDATE, QuickRefresh.resolve(enabled))
    }

    @Test
    fun `TapAction 枚举全集数量为 3`() {
        assertEquals(3, TapAction.values().size)
        assertEquals(
            setOf(TapAction.NEXT_CANDIDATE, TapAction.REFRESH, TapAction.MANUAL_CELL),
            TapAction.values().toSet(),
        )
    }
}
