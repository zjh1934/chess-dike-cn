package com.xqdk.chess.assist

/**
 * 快捷刷新（v1.3）：悬浮窗迷你棋盘点按的行为决策，纯函数便于 JVM 单测。
 *
 * 规则（优先级从上到下）：
 * 1. manualMode（手动摆棋）优先：点格用于选子/走子/删子，快捷刷新与「切换候选着法」
 *    都不能抢走这次点按。
 * 2. 开关开启：点棋盘 = 点右列「更新」按钮，即 [TapAction.REFRESH]。
 *    运行时该分支会派发 OverlayAction.FLIP_TURN（悬浮窗右列「更新」按钮的同一个
 *    动作），经 ScreenAssistService 的 onAction 闭包 post 到 worker 后走
 *    onOverlayAction(FLIP_TURN)：翻转走子方、重建 FEN、重新分析，链路与按钮完全一致。
 * 3. 开关关闭（默认）：保持上游原有行为「切换候选着法」[TapAction.NEXT_CANDIDATE]。
 *
 * [TapAction.MANUAL_CELL] 分支在运行时由 OverlayChessView 内部截胡（manualMode 下
 * onTouchEvent 走 hitCell/onCellTap，不会回调 onNextCandidate，因此到不了这里）；
 * 此处保留是为了规则完整与防御（即便将来回调被误触发也不会误刷新棋局）。
 * 本文件零 android.* 依赖，可被 JVM 单测直接跑。
 */
object QuickRefresh {
    /** 默认关：关闭时点棋盘仍是上游原有的「切换候选着法」，不侵入原行为 */
    const val DEFAULT_ENABLED = false

    enum class TapAction { NEXT_CANDIDATE, REFRESH, MANUAL_CELL }

    /** manualMode 优先（手动摆棋不能被抢），其次看开关。 */
    fun resolve(enabled: Boolean, manualMode: Boolean = false): TapAction = when {
        manualMode -> TapAction.MANUAL_CELL
        enabled -> TapAction.REFRESH
        else -> TapAction.NEXT_CANDIDATE
    }

    fun switchText(enabled: Boolean): String = if (enabled) "开" else "关"

    fun labelOf(action: TapAction): String = when (action) {
        TapAction.NEXT_CANDIDATE -> "切换候选着法"
        TapAction.REFRESH -> "刷新棋局（等同点『更新』）"
        TapAction.MANUAL_CELL -> "手动摆棋点格"
    }
}
