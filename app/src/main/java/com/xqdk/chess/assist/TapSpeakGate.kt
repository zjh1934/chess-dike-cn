package com.xqdk.chess.assist

/**
 * v1.11：一次点按，只在**定着**时播报一招。纯 JVM、零 Android 依赖，CI 直接对生产逻辑下断言。
 *
 * ## 真机日志里的「多指挥 / 乱指挥」
 *
 * `XQDK_增强功能诊断_20261115_180534.md` 里 seq=19 一次点按的实际播报序列：
 *
 * ```
 * 17:58:06.427 ⑤ 炮2平1（暂定） → speak SUCCESS
 * 17:58:06.539 ⑤ 将5退1（暂定） → speak SUCCESS   ← 同一轮，第二招
 * 17:58:06.661 ⑤ 马8进9（暂定） → speak SUCCESS   ← 第三招
 * 17:58:07.118 ⑤ 马8进9（暂定） → QUEUE_FLUSH 打断
 * 17:58:08.471 ⑤ 马8进9 ✓       → 又念一次
 * ```
 *
 * 根因：v1.0 加中文播报时把 `voiceSpeaker.onSuggestion(...)` 放在 `renderOverlay` 末尾，
 * 而上游原作者的 renderOverlay 本来是给**界面**用的 —— 引擎每条 info 行都会刷新 UI
 * 显示「（暂定）」着法，这是合理的；但一接上 TTS，每条暂定 PV 都变成了念出来的一招，
 * 还互相 QUEUE_FLUSH 打断。更糟的是会话 200ms 就收场了，引擎还在搜几秒，
 * 于是下一轮点棋盘之后仍在念上一轮的残留着法（日志 17:58:30 / 17:58:52 即是）。
 *
 * ## 门控口径
 *
 * - 只在本轮点按会话**等待定着**期间生效；会话收场后一律 WAIT（杜绝残留播报）。
 * - 必须 `mature`（上游 `searchSettled` 或最佳着稳定 ≥1200ms / 开局库命中）才念。
 * - 文案必须是建议/开局库/备选，状态文案（「引擎思考中…」）、空文本、警告文本一律不念。
 * - 本轮已播过 → WAIT，保证一次点按最多一招。
 * - 播报总开关关掉 → MUTED（仍然正常收场，只显示不念）。
 */
object TapSpeakGate {

    enum class Decision {
        /** 念这一招（本轮唯一一次） */
        SPEAK,
        /** 定着了但总开关关着：不念，正常收场 */
        MUTED,
        /** 还没定着 / 不是建议文案 / 本轮已念过 / 会话已收场 */
        WAIT,
    }

    fun decide(
        tapAwaitingSettle: Boolean,
        alreadySpoke: Boolean,
        mature: Boolean,
        text: String?,
        voiceEnabled: Boolean,
    ): Decision {
        if (!tapAwaitingSettle || alreadySpoke) return Decision.WAIT
        if (!mature) return Decision.WAIT
        val t = text ?: return Decision.WAIT
        if (!isSuggestion(t)) return Decision.WAIT
        return if (voiceEnabled) Decision.SPEAK else Decision.MUTED
    }

    /** 只认真正的着法文案；状态/警告/空文本一律不算「一招」 */
    fun isSuggestion(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("建议") || t.startsWith("开局库") || t.startsWith("备选")
    }
}
