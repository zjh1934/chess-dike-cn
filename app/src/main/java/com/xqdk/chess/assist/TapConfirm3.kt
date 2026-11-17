package com.xqdk.chess.assist

import kotlin.math.abs
import com.xqdk.chess.gamelogic.Piece

/**
 * 原作者「连续 3 帧逐格一致」确认口径（纯 JVM 留档）。
 * v1.22 起**不再用于点按档**：点一下棋盘不动，为凑 3 帧反复重提交画面会
 * 自己造出 A/B 两套合成画面（11:44 日志 9 次点按 65 帧全合法却全失败）。
 * 本对象保留给常开识别档与单测对照上游 `BoardTracker(confirmCount=3)`。
 *
 * 上游 `BoardTracker(confirmCount = 3)`：瞬时坏帧/幻觉帧很难连续多帧逐格一致，
 * 所以首次确认或局面变化都要**连续 3 帧完全相同**才确认；另带「子数跳变门限」
 * （真实走子只 ±1，检测噪声通常 ±2~3）挡走子动画过渡帧。
 *
 * 本对象只做纯 JVM 状态机。常开档用自然帧流累计；点按档不再调用它。
 */
object TapConfirm3 {

    /** 原作者 `AssistConfig.confirmCount` 默认值；用户要求「他识别几次咱就识别几次」 */
    const val REQUIRED = 3

    data class State(
        val canonical: Array<IntArray>? = null,
        val hits: Int = 0,
    )

    data class Verdict(val accept: Boolean, val hits: Int, val reason: String)

    /** 喂一帧合法局面：与候选逐格相同则累计，否则从 1 重新计。 */
    fun feed(state: State, cur: Array<IntArray>): Pair<State, Verdict> {
        val hits = if (state.canonical != null && AssistBoard.equal(state.canonical, cur))
            state.hits + 1 else 1
        val next = State(AssistBoard.clone(cur), hits)
        val verdict = if (hits >= REQUIRED)
            Verdict(true, hits, "连续 $hits 帧逐格一致 → 确认（原作者 confirmCount=3）")
        else
            Verdict(false, hits, "第 $hits/$REQUIRED 帧一致 → 继续抓下一帧")
        return next to verdict
    }

    /**
     * 原作者的子数跳变门限：`tol = max(2, 已确认子数 / 10)`。
     * 与上一确认局面相差过大的帧只可能是局部遮挡/动画/幻觉，不进确认累计。
     */
    fun pieceJumpPlausible(prev: Array<IntArray>?, cur: Array<IntArray>): Boolean {
        if (prev == null) return true
        val a = prev.sumOf { row -> row.count { it != Piece.EMPTY } }
        val b = cur.sumOf { row -> row.count { it != Piece.EMPTY } }
        return abs(a - b) <= maxOf(2, a / 10)
    }
}
