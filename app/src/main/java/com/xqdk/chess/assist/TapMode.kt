package com.xqdk.chess.assist

/**
 * v1.9「开箱即用」：**点棋盘 = 这一帧直接算 → 支招 → 语音播报**，永久固定，无档位可选。
 *
 * 历史（v1.5~v1.8）曾把点棋盘做成四选一互斥档位（点按出招 / 刷新 / 变招 / 自动识别），
 * 后来又拆成两个独立维度。用户实测反馈：设置看不懂、且档位会把点棋盘劫持成
 * 「切候选」，整条识别推理支招播报链断掉，表现就是「点一下没用」。
 * 故 v1.9 起**取消全部识别档位开关**：不点棋盘零动作，点一下就出招＋语音，
 * 装上即用，不需要进任何设置页。纯函数、零 android.* 依赖，可直接跑 JVM 单测。
 *
 * 旧常量 / 旧配置值仅为迁移与诊断兼容保留，不再影响手势决策。
 */
object TapMode {

    /** 点按出招（默认）：不点不动；点一次 = 识别 → 推理 → 支招 → 播报，完成即收场 */
    const val TAP_SUGGEST = "tap_suggest"
    /** 点按刷新：不点不动；点一次 = 等同右列「更新」键，翻转轮次重算当前局面 */
    const val TAP_REFRESH = "tap_refresh"
    /** 点按变招：不点不动；点一次 = 切换候选着法（上游原行为） */
    const val TAP_CANDIDATE = "tap_candidate"
    /** 自动识别：恢复上游连续识别（画面变化即识别推理，非本分支默认） */
    const val AUTO = "auto"

    const val DEFAULT = TAP_SUGGEST

    val ALL: List<String> = listOf(TAP_SUGGEST, TAP_REFRESH, TAP_CANDIDATE, AUTO)

    fun isValid(raw: String?): Boolean = raw != null && raw in ALL

    /** 脏值/旧版本遗留值一律归位到默认档，避免 UI 显示与运行时行为脱钩 */
    fun normalize(raw: String?): String = if (isValid(raw)) raw!! else DEFAULT

    /**
     * v1.8：「点棋盘时做什么」只允许前三档（AUTO 已拆成独立开关 autoTapEnabled）。
     * 历史遗留的 AUTO 值、脏值一律归位到 TAP_SUGGEST，保证面板手势永远有明确动作。
     */
    fun normalizeAction(raw: String?): String = when (raw) {
        TAP_REFRESH -> TAP_REFRESH
        TAP_CANDIDATE -> TAP_CANDIDATE
        else -> TAP_SUGGEST
    }

    /** v1.9 诊断用的一句话摘要：行为固定，任何旧配置都归位成这一句 */
    fun summary(action: String? = null, autoEnabled: Boolean = false): String =
        "不点棋盘完全不动 ＋ 点一下棋盘＝这一帧直接算 → 支招 → 语音播报"

    fun isAuto(raw: String?): Boolean = normalize(raw) == AUTO

    /** 是否「不点棋盘就不识别」（前三档都是，AUTO 不是） */
    fun isTapDriven(raw: String?): Boolean = !isAuto(raw)

    fun labelOf(raw: String?): String = when (normalize(raw)) {
        TAP_SUGGEST -> "点按出招"
        TAP_REFRESH -> "点按刷新"
        TAP_CANDIDATE -> "点按变招"
        else -> "自动识别"
    }

    /** v1.9：无论旧配置存了什么档位，说明都只有这一句，避免页面显示与真实行为脱钩 */
    fun hintOf(raw: String?): String =
        "点一下棋盘＝就用这一帧直接算 → 立刻支招 → 中文语音播报；不点棋盘完全不动。认不出来会提示「再点一次」。不用设置，装好就能用。"

    /**
     * v1.9「开箱即用」：**点棋盘这个手势永久固定为「识别 → 支招 → 语音播报」**。
     *
     * 之前四档互斥（点按出招 / 刷新 / 变招 / 自动识别）会把点棋盘劫持成
     * 「切候选」「翻转轮次」，整条识别推理支招播报链根本不启动 —— 用户实测
     * 「点一下没用」，日志里 `action=CYCLE_CANDIDATE ...（连续 8 次）` 即铁证。
     * 用户明确要求：不要看不懂的复杂设置，装上点一下就要出招＋语音。
     *
     * 因此这里不再读档位：无论配置里存的是什么（含历史 AUTO / 点按变招 / 脏值），
     * 点棋盘一律派发 [Action.RECOGNIZE]。`raw` / `quickRefreshEnabled` 仅为兼容
     * 既有调用点与回归单测保留，不参与决策。手动摆棋模式仍优先（点格是选子走子）。
     */
    fun resolveAction(
        raw: String?,
        manualMode: Boolean,
        quickRefreshEnabled: Boolean,
    ): Action {
        if (manualMode) return Action.NONE
        return Action.RECOGNIZE
    }

    enum class Action { RECOGNIZE, REFRESH, CANDIDATE, NONE }

    fun switchText(on: Boolean): String = if (on) "开" else "关"
}
