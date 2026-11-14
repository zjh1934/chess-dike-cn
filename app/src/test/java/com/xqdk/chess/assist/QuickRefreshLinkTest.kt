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
 * 快捷刷新（v1.3）真实点击链路的运行时证据：直接把 ACTION_DOWN/ACTION_UP 派发给
 * OverlayPanelView 里的迷你棋盘，验证 chess.onNextCandidate → panel.onAction 的分发结果。
 * 关闭→CYCLE_CANDIDATE、开启→FLIP_TURN（与右列「更新」按钮完全相同）、
 * 手动摆棋→不抢点格。这是「链路接通」而非「只放开关」的回归保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w440dp-h920dp-440dpi")
class QuickRefreshLinkTest {

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
    fun `关闭时点迷你棋盘只派发 CYCLE_CANDIDATE`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.quickRefreshEnabled = false
        tapChess(chess)
        assertEquals(listOf(OverlayAction.CYCLE_CANDIDATE), actions)
    }

    @Test
    fun `开启时点迷你棋盘派发 FLIP_TURN-链路接通的运行时证据`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        panel.quickRefreshEnabled = true
        tapChess(chess)
        assertEquals(listOf(OverlayAction.FLIP_TURN), actions)
    }

    @Test
    fun `手动摆棋模式下点棋盘不被快捷刷新抢走`() {
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        // 服务端以 manualMode=true 渲染后，chess.onTouchEvent 走 hitCell/onCellTap，
        // 根本不会回调 onNextCandidate，因此不产生任何 onAction 动作
        panel.render(OverlayModel(manualMode = true))
        panel.quickRefreshEnabled = true
        tapChess(chess)
        assertTrue("手动摆棋点格不应触发任何 onAction 动作", actions.isEmpty())
    }
}
