package com.xqdk.chess.assist

import kotlin.math.abs
import com.xqdk.chess.gamelogic.Piece

/**
 * v1.21：点按档的「连续 3 帧逐格一致」确认器 —— 对齐原作者口径。
 *
 * 上游 `BoardTracker(confirmCount = 3)`：瞬时坏帧/幻觉帧很难连续多帧逐格一致，
 * 所以首次确认或局面变化都要**连续 3 帧完全相同**才确认；另带「子数跳变门限」
 * （真实走子只 ±1，检测噪声通常 ±2~3）挡走子动画过渡帧。
 *
 * 本对象只做纯 JVM 状态机，抓帧节奏由服务层控制：点一下后不查节拍器、不等帧差，
 * 连续重提交帧，几百毫秒内拿齐 3 帧（不回到 v1.11 的 6~12 秒）。
 * 每帧仍先过 `TapFrameGate` 合法性与 `PieceLimitTrim` 数量规范化。
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
