package com.xqdk.chess.assist

/**
 * v1.12：点按单帧的合法性门槛。纯 JVM，CI 可直接对生产逻辑下断言。
 *
 * v1.7 的单帧旁路之所以翻车，是把 BoardTracker 的 `isValid()` 校验一起跳过了 ——
 * 真机日志里「认出=true 合法=false 问题数=1」照样支招。v1.11 用多帧确认找回了
 * 校验，但代价是 3~11 秒延迟。v1.12 把校验**留在单帧路径上**，又快又不认错局面。
 */
object TapFrameGate {
    /** v1.16：detail 记下**具体**哪条不合法（两将照面 / 帅将数量异常 / 子数超限…）。
     *  之前日志只有「问题数=1」，用户反复点却不知道软件在拦什么，排障全靠猜。 */
    data class Verdict(val accept: Boolean, val reason: String, val detail: String = "")

    fun check(result: RecognitionResult?, minPieces: Int): Verdict {
        if (result == null) return Verdict(false, "没找到棋盘", "识别器没返回棋盘")
        if (result.unknownCells > 0) return Verdict(false, "有未识别格", "未识别格=${result.unknownCells}")
        if (result.issues.isNotEmpty()) return Verdict(
            false, "局面不合法×${result.issues.size}", result.issues.joinToString("；"))
        if (result.recognizedPieces < minPieces) return Verdict(
            false, "棋子数过少", "认出=${result.recognizedPieces} 门槛=$minPieces")
        return Verdict(true, "单帧合法")
    }
}
