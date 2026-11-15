package com.xqdk.chess.assist

import android.view.MotionEvent
import android.view.View
import com.xqdk.chess.R
import com.xqdk.chess.assist.ui.OverlayAction
import com.xqdk.chess.assist.ui.OverlayChessView
import com.xqdk.chess.assist.ui.OverlayModel
import com.xqdk.chess.assist.ui.OverlayPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9「点一下就出招」的运行时证据：把 ACTION_DOWN/ACTION_UP 派发给
 * OverlayPanelView 里的迷你棋盘，验证**任何历史档位 / 快捷刷新开关下**都只派发
 * TAP_SUGGEST（识别→支招→播报），不再被劫持成 FLIP_TURN / CYCLE_CANDIDATE。
 * 手动摆棋模式仍优先。端到端链路自检见 TapChainTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w440dp-h920dp-440dpi")
class FeatureSwitchesLinkTest {

    /** 与 OverlayPanelViewRenderTest 同口径：392×197dp @ 2.75 = 1078×541px */
    private fun newPanel(): Pair<OverlayPanelView, OverlayChessView> {
        val ctx = RuntimeEnvironment.getApplication()
        val panel = OverlayPanelView(ctx)
        val widthPx = 1078
        val heightPx = 541
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, widthPx, heightPx)
        val chess = panel.findViewById<OverlayChessView>(R.id.overlay_chess)
        return panel to chess
    }

    private fun tapChess(chess: OverlayChessView) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        val up = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 20f, 20f, 0)
        try {
            chess.dispatchTouchEvent(down)
            chess.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @Test
    fun `默认点按出招档点棋盘派发 TAP_SUGGEST`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.TAP_SUGGEST
        tapChess(chess)
        assertEquals(listOf(OverlayAction.TAP_SUGGEST), actions)
    }

    @Test
    fun `v1_9 旧点按刷新档也派发出招`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.TAP_REFRESH
        tapChess(chess)
        assertEquals(listOf(OverlayAction.TAP_SUGGEST), actions)
    }

    @Test
    fun `v1_9 旧点按变招档也派发出招-不再劫持成切候选`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.TAP_CANDIDATE
        tapChess(chess)
        assertEquals(listOf(OverlayAction.TAP_SUGGEST), actions)
    }

    @Test
    fun `v1_9 旧自动识别档两种快捷刷新开关都派发出招`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.AUTO
        panel.quickRefreshEnabled = true
        tapChess(chess)
        assertEquals(listOf(OverlayAction.TAP_SUGGEST), actions)
        actions.clear()
        panel.quickRefreshEnabled = false
        tapChess(chess)
        assertEquals(listOf(OverlayAction.TAP_SUGGEST), actions)
    }

    @Test
    fun `手动摆棋模式下点棋盘不触发任何动作`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.render(OverlayModel(manualMode = true))
        panel.tapMode = TapMode.TAP_SUGGEST
        tapChess(chess)
        assertTrue("手动摆棋点格不应触发任何 onAction 动作", actions.isEmpty())
    }
}
