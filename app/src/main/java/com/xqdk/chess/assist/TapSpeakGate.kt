package com.xqdk.chess.assist

/**
 * v1.16：一次点按，**第一条算出来的着法（箭头画出来）就立刻念**，本轮只念一次。
 * 纯 JVM、零 Android 依赖，CI 直接对生产逻辑下断言。
 *
 * v1.11~v1.15 要求 `mature`（定着 ✓ / 最佳着稳定 1200ms / searchSettled）才念。
 * 真机用户实测：「箭头都画出来了，他还不给我读出来……算出来就要给我读啊」。
 * 命中：renderOverlay 在 `line != null && fromIsOurs` 时就画箭头（文案「（暂定）」），
 * 但播报要等 SUGGEST_STABLE_MS=1200ms，甚至 8 秒兜底 —— 日志里成功会话耗时
 * 1.4~8.3 秒。这 1.2 秒原本是防「暂定着法横跳乱念」的，可 `alreadySpoke` +
 * 「会话收场即 WAIT」两条已经能挡住横跳，根本不需要用户干等。
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
 * ## v1.20：招法质量门槛（用户「支的招很多馊主意」）
 *
 * 07:58 诊断（v1.19，28 次点按）把 v1.16 的取舍证伪了：**28/28 念的全是
 * 「（暂定）」，0 次「定着✓」**。v1.16 为「算出来就念」把播报挂在箭头画出的
 * 那一刻，而箭头用的是引擎**第一条 info 行**（最浅 PV，通常 depth 1~4），
 * 搜索还没收敛就念了 —— 快是快了（平均 0.32s），但支的是浅算的馊主意。
 *
 * 现在回到「等招算稳」，但不退回秒级干等：
 * - **定着 / 开局库 / 预判命中**（`mature=true`）→ 立刻念，零等待、最可信
 * - v1.21 改回原作者口径：**只念本轮完整搜索结束（bestmove）后的定着✓**，
 *   中间 info 行的暂定 PV 一律不念；开局库命中也念（库着即定着）
 * - 3s 硬预算由服务层兜底，绝不静默（日志会写明「硬预算·未定着」）
 *
 * 用户要的「最多两三秒出声」仍成立：完整搜索通常 1~2 秒；最差 3 秒硬预算。
 *
 * ## 门控口径
 *
 * - 只在本轮点按会话**等待定着**期间生效；会话收场后一律 WAIT（杜绝残留播报）。
 * - 文案必须是建议/开局库/备选，状态文案、空文本、警告文本一律不念。
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

    /** v1.20 曾用：短质量窗口内 PV 深度门槛。v1.21 起不再截念暂定 PV（用户要求
     *  「按原作者的来，他计算几次咱就计算几次」），只认本轮完整搜索的 bestmove。 */
    const val MIN_PV_DEPTH = 8
    const val DEFAULT_QUALITY_WINDOW_MS = 1000L

    fun decide(
        tapAwaitingSettle: Boolean,
        alreadySpoke: Boolean,
        suggestionReady: Boolean,
        text: String?,
        voiceEnabled: Boolean,
        /** 定着✓ / 开局库 / 预判β / 稳定 1200ms —— 最可信，立刻念 */
        mature: Boolean = false,
        /** 当前建议的引擎搜索深度 */
        pvDepth: Int = 0,
        /** 从本轮建局到现在过了多久 */
        elapsedMs: Long = 0L,
        /** 短质量窗口；到点且深度达标才念暂定 PV */
        qualityWindowMs: Long = DEFAULT_QUALITY_WINDOW_MS,
    ): Decision {
        if (!tapAwaitingSettle || alreadySpoke) return Decision.WAIT
        if (!suggestionReady) return Decision.WAIT
        val t = text ?: return Decision.WAIT
        if (!isSuggestion(t)) return Decision.WAIT
        // v1.21：只念「本轮这一局面完整跑完一次搜索」的 bestmove 定着✓ / 开局库。
        // 不再用 1 秒窗口 + 深≥8 截住念暂定 PV —— 那仍是没算完的着法。
        // 2.5s（本版 3s）硬预算由服务层兜底，绝不静默。
        if (!mature) return Decision.WAIT
        return if (voiceEnabled) Decision.SPEAK else Decision.MUTED
    }

    /** 只认真正的着法文案；状态/警告/空文本一律不算「一招」 */
    fun isSuggestion(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("建议") || t.startsWith("开局库") || t.startsWith("备选")
    }
}
