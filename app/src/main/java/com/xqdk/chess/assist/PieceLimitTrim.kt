package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece

/**
 * v1.18：YOLO 单帧「逐类棋子数量上限」裁剪。纯 JVM，CI 可直接断言。
 *
 * ## 真机教训（03:00 诊断，19 次点按 / 600 行）
 *
 * 19 次点按只有 8 次出声（42%），11 次静默收场；失败明细几乎全是
 * `卒数量超限:6`（11 次）/ `象数量超限:3`（7 次）/ `马数量超限:3`。
 * 根因不是「没读到屏幕」，是 YOLO 偶尔**多认 1 个子**（把背景或相邻棋子
 * 又检成一个卒/象），而 `AssistBoard.validate` 一见数量超限就把整局判死，
 * `TapFrameGate` 再一票否决 → 3 帧全废 → 收场。用户只能连点 2~6 次碰运气。
 *
 * v1.16/v1.17 试图靠「再抓几帧 / 两帧一致」绕过，但 `forceCaptureFrame()`
 * 重提交的是**同一静止画面**，YOLO 每帧抖动认出不同的错，凑齐一致的概率
 * 很低（日志里 18 次「第一帧合法再抓一帧」、5 次「两帧不一致」）。
 *
 * ## 正确解法：在映射层就地修
 *
 * 象棋每类棋子数量有硬上限（车马炮仕相各 2、兵卒各 5、帅将各 1），
 * 多出来的那个几乎必然是置信度最低的误检。`DetectionBoardMapper` 已经
 * 在 `cellScore[row][col]` 存了每格置信度，这里按分数升序丢掉超出的，
 * 产出合法局面再交给 validate —— 不再靠「等下一帧碰运气」。
 *
 * 只裁「超出上限」的多余者；帅/将若一个都没有不补（补不出来，交给
 * validate 如实报异常），若多了保留最高分那 1 个。
 *
 * ## v1.20：延续性加成（用户「支的招很多馊主意」的第二个来源）
 *
 * 07:58 诊断 28 次点按里 **10 次（36%）被裁了子**，其中「帅×1」3 次、
 * 「象×1」5 次、一次裁 2 子 3 次。v1.18 只按置信度丢最低者：万一真棋子
 * 这帧分数偏低、背景误检分数偏高，就**丢错了真子**，引擎按缺子/错子的局面
 * 算招 —— 搜索再深也是馊主意。
 *
 * 现在传入「上一帧的屏幕布局」：某个候选格若上一帧同位置就是同样的棋子，
 * 给一个大加成（[CONTINUITY_BONUS]）优先保留；背景误检上一帧通常不存在，
 * 于是优先被丢。不增加任何等待、不叫用户重点。
 */
object PieceLimitTrim {

    data class Result(
        /** 被裁掉的棋子数 */
        val trimmed: Int,
        /** 给诊断日志看的中文明细，如「卒×1、象×1」；无裁剪为「—」 */
        val detail: String,
        /** v1.20：因「上一帧同格同子」被优先保住的真棋子数 */
        val protectedByContinuity: Int = 0,
    )

    /** 每类棋子的上限；帅/将按 1（与 AssistBoard.validate 口径一致） */
    private fun capOf(p: Int): Int = when (p) {
        Piece.WSHUAI, Piece.BJIANG -> 1
        else -> AssistBoard.PIECE_LIMITS[p] ?: Int.MAX_VALUE
    }

    /**
     * 就地裁剪 `cells`（同步把 `cellScore` 对应格清零，供调用方重算平均分）。
     * 幂等：已合法的局面返回 trimmed=0，可安全重复调用。
     */
    /** 延续性加成：置信度域是 0..1，2.0 足以压过分数差异，但不改变「没上一帧时」的行为 */
    private const val CONTINUITY_BONUS = 2.0

    fun trim(
        cells: Array<IntArray>,
        cellScore: Array<DoubleArray>,
        /** 上一帧的**屏幕布局**（与 cells 同为 screenRaw 坐标系）；null = 退化为纯置信度 */
        prevRaw: Array<IntArray>? = null,
    ): Result {
        val groups = mutableMapOf<Int, MutableList<Triple<Int, Int, Double>>>()
        for (y in cells.indices) for (x in cells[y].indices) {
            val p = cells[y][x]
            if (p == Piece.EMPTY) continue
            groups.getOrPut(p) { mutableListOf() }
                .add(Triple(y, x, if (y < cellScore.size && x < cellScore[y].size)
                    cellScore[y][x] else 0.0))
        }
        var trimmed = 0
        var protected = 0
        val parts = mutableListOf<String>()
        for ((p, list) in groups) {
            val cap = capOf(p)
            if (list.size <= cap) continue
            // 分数 = 置信度 + 延续性加成；分高的保留，分低的丢
            fun scoreOf(c: Triple<Int, Int, Double>): Double {
                val (y, x, sc) = c
                val keptLastFrame = prevRaw != null && y < prevRaw.size &&
                    x < prevRaw[y].size && prevRaw[y][x] == p
                return sc + if (keptLastFrame) CONTINUITY_BONUS else 0.0
            }
            val sorted = list.sortedByDescending { scoreOf(it) }
            repeat(cap) { i -> if (scoreOf(sorted[i]) >= CONTINUITY_BONUS) protected++ }
            repeat(list.size - cap) { i ->
                val (y, x, _) = sorted[cap + i]
                cells[y][x] = Piece.EMPTY
                if (y < cellScore.size && x < cellScore[y].size) cellScore[y][x] = 0.0
                trimmed++
            }
            parts.add("${Piece.getNameByValue(p)}×${list.size - cap}")
        }
        return Result(trimmed, if (parts.isEmpty()) "—" else parts.joinToString("、"), protected)
    }
}
