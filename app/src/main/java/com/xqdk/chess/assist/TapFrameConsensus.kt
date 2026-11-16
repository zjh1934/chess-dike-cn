package com.xqdk.chess.assist

/**
 * v1.17：短窗口内「两帧一致」才采纳，等价于原作者的稳定性确认，但**不等待**：
 * 点击后自然到达的帧（约 130ms 一帧）逐帧比对，最多 3 帧 / 700ms。
 * 纯 JVM、零 Android 依赖，CI 直接对生产逻辑下断言。
 */
object TapFrameConsensus {

    data class Verdict(val accept: Boolean, val reason: String)

    /**
     * @param prevLegal 本轮上一帧的合法局面（null＝这是第一帧合法）
     * @param curLegal  当前帧的合法局面
     * @param hasMoreFrames 窗口内还能不能再抓一帧
     * @param prevBoardPlausible 当前帧与「上一次已确认局面」是否说得通（TapBoardStep）
     */
    fun decide(
        prevLegal: Array<IntArray>?,
        curLegal: Array<IntArray>,
        hasMoreFrames: Boolean,
        prevBoardPlausible: Boolean,
    ): Verdict {
        if (prevLegal != null && AssistBoard.equal(prevLegal, curLegal)) {
            return Verdict(true, "连续两帧一致 → 采纳（零等待）")
        }
        if (hasMoreFrames) {
            return Verdict(false, if (prevLegal == null)
                "第一帧合法，再抓一帧确认一致性" else "两帧不一致，再抓一帧")
        }
        // 窗口用尽仍凑不齐两帧一致：只在「与上一局面说得通」时放行，绝不支可疑招
        return if (prevBoardPlausible)
            Verdict(true, "窗口内未凑齐两帧一致，但与上一局面说得通 → 放行")
        else
            Verdict(false, "窗口内未凑齐两帧一致，且与上一局面不符 → 不支招")
    }
}
