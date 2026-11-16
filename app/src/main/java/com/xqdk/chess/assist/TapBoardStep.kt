package com.xqdk.chess.assist

/**
 * v1.17：点按单帧「与上一局面是否说得通」。纯 JVM，CI 可直接断言。
 *
 * 用户实测 v1.16「有时瞎指挥」：YOLO 单帧偶尔漏检/把过渡帧认成局面，
 * 而 `AssistBoard.validate` 只查 帅=1 / 将=1 / 子上限 / 总数≤32 / 两将照面，
 * **不查总数下限、也不查与上一步的关系** —— 少两三个子照样「合法」。
 * v1.16 又是「第一帧合法就建局」，于是按错局面支招。原作者的连续多帧确认
 * 本来就是防这个的，但那种做法要等好几秒；这里用「与上局面的关系」零延迟判断。
 */
object TapBoardStep {

    data class Verdict(val plausible: Boolean, val reason: String)

    /**
     * @param prev 上一次已确认的局面（null＝本次会话第一次点，放行）
     * @param cur  这一帧识别出的合法局面
     * @param myRed 我方是否执红；用于判断「变化的是不是对方棋子」
     */
    fun check(prev: Array<IntArray>?, cur: Array<IntArray>, myRed: Boolean): Verdict {
        if (prev == null) return Verdict(true, "首次点按·无上一局面")
        if (AssistBoard.equal(prev, cur)) return Verdict(true, "与上一局面相同（重复点）")
        if (AssistBoard.matchStartCount(cur) >= 32 &&
            AssistBoard.matchStartCount(prev) < 32) {
            return Verdict(true, "新对局开局（满 32 子）")
        }
        var changed = 0
        var emptiedPieces = 0
        var filledPieces = 0
        var movedIsOpponent = false
        for (y in cur.indices) for (x in cur[y].indices) {
            val a = prev[y][x]
            val b = cur[y][x]
            if (a == b) continue
            changed++
            if (b == Piece.EMPTY) {
                emptiedPieces++
                // 起点必须曾是对方棋子（我方不可能在对方回合自己动）
                if (a != Piece.EMPTY && Piece.isRed(a) != myRed) movedIsOpponent = true
            } else {
                filledPieces++
            }
        }
        // 对方走一步：恰好 2 格变化（起点空 + 终点落子），且动的是对方棋子
        if (changed == 2 && emptiedPieces == 1 && filledPieces == 1 && movedIsOpponent) {
            return Verdict(true, "对方走一步（2 格变化·对方棋子）")
        }
        return Verdict(false, "与上一局面差 $changed 格（空$emptiedPieces/落$filledPieces），疑似误检或过渡帧")
    }
}
