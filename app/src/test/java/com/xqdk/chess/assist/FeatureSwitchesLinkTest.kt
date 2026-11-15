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
 * v1.5「增强功能」统一开关页的运行时证据：把 ACTION_DOWN/ACTION_UP 派发给
 * OverlayPanelView 里的迷你棋盘，验证 tapMode 四档 → panel.onAction 的分发结果。
 * 四档互斥、手动摆棋优先，这是「开关真的接通」而非「只放了个按钮」的回归保证。
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
    fun `点按刷新档派发 FLIP_TURN`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.TAP_REFRESH
        tapChess(chess)
        assertEquals(listOf(OverlayAction.FLIP_TURN), actions)
    }

    @Test
    fun `点按变招档派发 CYCLE_CANDIDATE`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.TAP_CANDIDATE
        tapChess(chess)
        assertEquals(listOf(OverlayAction.CYCLE_CANDIDATE), actions)
    }

    @Test
    fun `自动识别档按点按刷新开关决定手势`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.tapMode = TapMode.AUTO
        panel.quickRefreshEnabled = true
        tapChess(chess)
        assertEquals(listOf(OverlayAction.FLIP_TURN), actions)
        actions.clear()
        panel.quickRefreshEnabled = false
        tapChess(chess)
        assertEquals(listOf(OverlayAction.CYCLE_CANDIDATE), actions)
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
