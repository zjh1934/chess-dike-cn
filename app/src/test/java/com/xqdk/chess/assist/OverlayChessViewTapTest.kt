package com.xqdk.chess.assist

import android.os.SystemClock
import android.view.MotionEvent
import com.xqdk.chess.assist.ui.OverlayChessView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 小棋盘点击手势契约（v1.27 更新）：窗口（3s）内两下 = 首下「点棋盘更新轮次」+
 * 第二下触发三变；触发后时间戳归零——三连点 = 更新 → 三变 → 更新，不连发三变；
 * 手动模式点格两条手势回调都不走。原「切换候选」已移到面板「变着」按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayChessViewTapTest {

    private fun tap(view: OverlayChessView) {
        val down = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f, 10f, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `双击窗口内=更新加三变 触发后时间戳归零`() {
        val chess = OverlayChessView(RuntimeEnvironment.getApplication())
        var boardTaps = 0
        var triples = 0
        chess.onBoardTap = { boardTaps++ }
        chess.onTripleAnalysis = { triples++ }

        tap(chess)
        assertEquals(1 to 0, boardTaps to triples) // 首下 = 点棋盘更新轮次
        tap(chess)
        assertEquals(1 to 1, boardTaps to triples) // 窗口内第二下 = 触发三变
        tap(chess)
        assertEquals(2 to 1, boardTaps to triples) // 触发后归零，第三下重起新对首下
    }

    @Test
    fun `手动模式两条手势回调都不走`() {
        val chess = OverlayChessView(RuntimeEnvironment.getApplication())
        chess.manualMode = true
        var fired = 0
        chess.onBoardTap = { fired++ }
        chess.onTripleAnalysis = { fired++ }
        tap(chess)
        tap(chess)
        assertEquals(0, fired)
    }
}
