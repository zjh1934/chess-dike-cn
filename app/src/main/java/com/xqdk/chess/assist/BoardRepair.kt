package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece

/**
 * v1.24：单帧识别「车卒不分」就地纠正 + 「认不全不出招」。纯 JVM，CI 可直接断言。
 *
 * ## 为什么 v1.23 的补漏检子必须删掉（用户原话）
 * 「我感觉他完全就没在算我点了之后的画面」「他算的是前面的那一帧」。
 *
 * v1.23 拿上一次确认的局面当锚，只要这一帧某类棋子少 1 个就按旧局面**补回那个
 * 空格**。但它分不清「模型漏检」和「对方真的走了一步」：
 *  - 对方把黑车从 A 挪到 B，YOLO 没认出 B 的新车 → A 变空、该类少 1
 *    → v1.23 把 A 的车补回来 → **棋盘一模一样变回上一手** → 引擎算的是旧局面
 *  - 用户自己走一步、新位置漏检，同样被改回去
 * 这两者在本设计里完全无法从单帧区分：**补回去的结果恰好等于 prev**，而用户
 * 点棋盘就是因为局面变了。所以「补漏检子」在本软件里几乎没有正确发挥作用的
 * 场景，却正是「算上一手」的元凶 —— v1.24 整块删除。
 *
 * ## 现在怎么做
 * 1. **只在同格同阵营类别混淆时改**（红车↔红卒）：那个格子确实检到了一个子，
 *    只是类别读错；己方棋子不会在原地变成己方另一类棋子，所以改回是安全的。
 *    跨阵营变化（吃子 / 对方落子）一律不动。
 * 2. **先看这一帧相对上一局面是不是「真实棋局变化」**：统计起点消失 / 终点出现 /
 *    吃子替换。只要有「终点出现」或「跨阵营替换」，就认定是真实走子，
 *    一个子都不补，按当前这一帧建局 —— 这正是用户要的「每一次都是新的起点」。
 * 3. **认不全就明说**：如果只看到子凭空消失、既没有新落的子也没有被吃的痕迹
 *    （纯漏检，或走子且终点也漏检），标记 suspect → 不建局、不支招，
 *    语音明确提示再点一次。瞎指挥 / 算上一手，都比「不出招」更不能忍。
 * 4. 不再重抓帧、不跨帧比对（v1.21 反复重提交画面自造 A/B 抖动的教训）。
 */
object BoardRepair {

    /** 单帧最多纠正几格类别混淆：超过就是整帧不可信，别硬改 */
    const val MAX_FIXES = 3

    data class Result(
        /** 修补后的 canonical 局面（红恒在 y=9） */
        val canonical: Array<IntArray>,
        /** 实际纠正格数（只含类别混淆，不再含补空格） */
        val fixed: Int,
        /** 给诊断日志看的中文明细 */
        val detail: String,
        /** 可疑：服务层不建局、不支招，明确语音说明 */
        val suspect: Boolean,
        /** 可疑原因 */
        val reason: String,
        /** 变化统计（起点/终点/吃子/判定），供诊断日志核验「算的是不是新局面」 */
        val change: String,
    )

    fun repair(prev: Array<IntArray>?, cur: Array<IntArray>): Result {
        val out = AssistBoard.clone(cur)
        if (prev == null) {
            val issues = AssistBoard.validate(out)
            return Result(out, 0, "无上一局面·仅过合法性校验", issues.isNotEmpty(),
                issues.joinToString("、").ifEmpty { "" }, "变化=无锚(首次点按)")
        }
        // 识别到新开局（满 32 子且布局与上一残局不同）：不能拿旧残局往回补
        if (AssistBoard.matchStartCount(out) >= 32 && !AssistBoard.equal(prev, out)) {
            val issues = AssistBoard.validate(out)
            return Result(out, 0, "识别到新开局(满32子)·不按旧局修补", issues.isNotEmpty(),
                issues.joinToString("、").ifEmpty { "" }, "变化=新开局(满32子)")
        }

        // ---- 先在原始 cur 上统计变化（类别纠正之前） ----
        var disappeared = 0       // prev 有子 → cur 空格（走子起点 / 漏检）
        var appeared = 0          // prev 空格 → cur 有子（走子终点）
        var sameSideReplaced = 0  // 两边都有子、同阵营、类别不同（模型读错类别）
        var crossSideReplaced = 0 // 两边都有子、跨阵营（被吃 / 对方落子）
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val p = prev[y][x]
            val q = cur[y][x]
            when {
                p == Piece.EMPTY && q == Piece.EMPTY -> Unit
                p == Piece.EMPTY && q != Piece.EMPTY -> appeared++
                p != Piece.EMPTY && q == Piece.EMPTY -> disappeared++
                p == q -> Unit
                Piece.isRed(p) == Piece.isRed(q) -> sameSideReplaced++
                else -> crossSideReplaced++
            }
        }
        /** 真实落子 / 被吃的证据：终点出现，或某格换成对方子 */
        val realChangeEvidence = appeared + crossSideReplaced
        /** 只看到子凭空消失、没有任何新落子或被吃痕迹 → 这一帧没认全 */
        val unrecognized = disappeared > 0 && realChangeEvidence == 0
        val verdict = when {
            disappeared == 0 && appeared == 0 && crossSideReplaced == 0 && sameSideReplaced == 0 ->
                "与上一局面相同(重复点按)"
            unrecognized -> "认不全(少${disappeared}子·无新落子/被吃痕迹)"
            else -> "真实走子(起点${disappeared}·终点${appeared}·吃子${crossSideReplaced})"
        }
        val change = "变化=起点${disappeared}/终点${appeared}/同类替换${sameSideReplaced}/吃子${crossSideReplaced} 判定=$verdict"

        // ---- 只纠正「同格、同阵营、类别混淆」，绝不把空格填回 ----
        var fixed = 0
        val parts = mutableListOf<String>()
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            if (fixed >= MAX_FIXES) break
            val p = prev[y][x]
            val q = out[y][x]
            if (p == Piece.EMPTY || q == Piece.EMPTY || p == q) continue
            if (Piece.isRed(p) != Piece.isRed(q)) continue   // 吃子 / 对方落子，不动
            if (countOf(out, p) >= countOf(prev, p)) continue // 原类别没变少，不是混淆
            out[y][x] = p
            fixed++
            parts.add("${pos(y, x)}${Piece.getNameByValue(q)}→${Piece.getNameByValue(p)}")
        }

        val issues = AssistBoard.validate(out)
        val reasons = mutableListOf<String>()
        if (unrecognized) reasons.add("这一帧有${disappeared}个子没认出来，也没看到新落的子或被吃")
        reasons.addAll(issues)
        val suspect = reasons.isNotEmpty()
        return Result(out, fixed,
            if (parts.isEmpty()) "无需纠正(不补空格)" else parts.joinToString("、"),
            suspect, reasons.joinToString("、"), change)
    }

    private fun countOf(b: Array<IntArray>, p: Int): Int {
        var n = 0
        for (row in b) for (v in row) if (v == p) n++
        return n
    }

    /** 诊断用格位（列 + 行），不泄露 FEN / 棋局内容 */
    private fun pos(y: Int, x: Int): String = "${('a'.code + x).toChar()}$y "
}
