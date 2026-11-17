package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece

/**
 * v1.23：单帧识别「漏一个子 / 车卒不分」就地修补。纯 JVM，CI 可直接下断言。
 *
 * ## 为什么必须有这一层（用户原话）
 * 「不是经常看不清棋盘，就是棋盘识别不完整，漏掉一个车，车和卒不分，还有瞎指挥。」
 *
 * `AssistBoard.validate` 只查**数量上限**（车 2 / 卒 5 / 帅将各 1），不查下限：
 *  - 漏认 1 个车 → 车数 2→1，**完全合法**，单帧直出直接建局 → 引擎按缺子棋盘算 → 瞎指挥
 *  - 红车认成红卒 → 车 1、卒 6；`PieceLimitTrim` 按置信度丢一个「多余卒」，
 *    可能丢错真卒、留下假卒，局面错得更离谱，照样合法
 *
 * `PieceLimitTrim` 只会「裁多」，从不「补少」，也不会纠正类别。v1.22 单帧直出
 * 去掉跨帧确认后，这类错误没有第二帧能纠，必须在这一帧内部修好。
 *
 * ## 修法：拿上一次确认的局面当锚（不重抓帧、不跨帧比对）
 * 点按档的局面是连续的，相邻两次确认之间只可能有 1~2 步棋：
 *  - **走子**：某类棋子从 A 格移到 B 格 → 该类**总数不变** → 不算漏检，不补
 *  - **吃子**：A 格的我方子变成敌方子 → 该类少 1，但有「被吃证据」 → 不补
 *  - **漏检**：A 格凭空变空，该类少 1，且没有任何格子多出同类、也没有被吃证据 → 补回
 *  - **类别混淆**：A 格我方车变成我方卒（同阵营），且车总数少 1 → 改回车
 *
 * 跨阵营的类别变化一律不修（那是吃子/对方落子，不是模型混淆）。
 * 不改数据结构、不重提交 VirtualDisplay（v1.21 教训：重提交会自己造抖动），
 * 零额外延迟；修完仍不合法才标记 suspect，由服务层决定不支招。
 */
object BoardRepair {

    /** 单帧最多修几格：超过这个数就不是「偶发误检」，别硬补（硬补=瞎指挥） */
    const val MAX_FIXES = 3

    data class Result(
        /** 修补后的 canonical 局面（红恒在 y=9） */
        val canonical: Array<IntArray>,
        /** 实际修补格数 */
        val fixed: Int,
        /** 给诊断日志看的中文明细 */
        val detail: String,
        /** 修完仍可疑：服务层不建局、不支招，明确语音说明 */
        val suspect: Boolean,
        /** 可疑原因（validate 问题列表 / 改动过大） */
        val reason: String,
    )

    fun repair(prev: Array<IntArray>?, cur: Array<IntArray>): Result {
        val out = AssistBoard.clone(cur)
        if (prev == null) {
            val issues = AssistBoard.validate(out)
            return Result(out, 0, "无上一局面·仅过合法性校验", issues.isNotEmpty(),
                issues.joinToString("、").ifEmpty { "" })
        }
        // 识别到新开局（满 32 子且布局与上一残局不同）：不能拿旧残局往回补
        if (AssistBoard.matchStartCount(out) >= 32 && !AssistBoard.equal(prev, out)) {
            val issues = AssistBoard.validate(out)
            return Result(out, 0, "识别到新开局(满32子)·不按旧局修补", issues.isNotEmpty(),
                issues.joinToString("、").ifEmpty { "" })
        }

        var fixed = 0
        val parts = mutableListOf<String>()

        // ---- R1 同阵营类别混淆：红车→红卒 / 黑车→黑卒 ----
        // 判据：同色、两值不同、且「原类别」在当前帧确实变少了。
        // 跨阵营变化（我方子变成敌方子）= 被吃，绝不改回去。
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            if (fixed >= MAX_FIXES) break
            val p = prev[y][x]
            val q = out[y][x]
            if (p == Piece.EMPTY || q == Piece.EMPTY || p == q) continue
            if (Piece.isRed(p) != Piece.isRed(q)) continue
            if (countOf(out, p) >= countOf(prev, p)) continue
            out[y][x] = p
            fixed++
            parts.add("${pos(y, x)}${Piece.getNameByValue(q)}→${Piece.getNameByValue(p)}")
        }

        // ---- R2 孤立漏检：凭空少 1 个车/马/炮 ----
        // 被吃证据：prev 某格是 P，当前同格变成**敌方**棋子 → P 少 1 可解释
        val eaten = mutableMapOf<Int, Int>()
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val p = prev[y][x]
            val q = out[y][x]
            if (p == Piece.EMPTY || q == Piece.EMPTY || p == q) continue
            if (Piece.isRed(p) == Piece.isRed(q)) continue
            eaten[p] = (eaten[p] ?: 0) + 1
        }
        // 只需检查上一局面里出现过的类别（不硬编码棋子表，残局/自定义局都成立）
        val kinds = mutableSetOf<Int>()
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val p = prev[y][x]
            if (p != Piece.EMPTY) kinds.add(p)
        }
        for (p in kinds) {
            var gap = countOf(prev, p) - countOf(out, p) - (eaten[p] ?: 0)
            if (gap <= 0) continue
            for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
                if (gap <= 0 || fixed >= MAX_FIXES) break
                if (prev[y][x] == p && out[y][x] == Piece.EMPTY) {
                    out[y][x] = p
                    gap--
                    fixed++
                    parts.add("${pos(y, x)}补${Piece.getNameByValue(p)}")
                }
            }
        }

        val issues = AssistBoard.validate(out)
        val suspect = issues.isNotEmpty()
        return Result(out, fixed,
            if (parts.isEmpty()) "无需修补" else parts.joinToString("、"),
            suspect, issues.joinToString("、").ifEmpty { "" })
    }

    private fun countOf(b: Array<IntArray>, p: Int): Int {
        var n = 0
        for (row in b) for (v in row) if (v == p) n++
        return n
    }

    /** 诊断用格位（列 + 行），不泄露 FEN / 棋局内容 */
    private fun pos(y: Int, x: Int): String = "${('a'.code + x).toChar()}$y "
}
