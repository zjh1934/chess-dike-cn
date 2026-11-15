package com.xqdk.chess.assist

/**
 * 「识别方式」四选一（v1.5 统一命名）：点悬浮窗迷你棋盘这个手势到底做什么，
 * 以及不点棋盘时是否自动识别。纯函数、零 android.* 依赖，可直接跑 JVM 单测。
 *
 * 旧名对照（v1.5 起停用）：
 * - 点按识别 / 点按触发识别 / TapTrigger → **点按出招** [TAP_SUGGEST]
 * - 快捷刷新 → **点按刷新** [TAP_REFRESH]
 * - 切换候选着法 → **点按变招** [TAP_CANDIDATE]
 * - （v1.3 老行为，v1.4 曾移除）→ **自动识别** [AUTO]
 *
 * 四档互斥，同一时刻只有一档生效；[DEFAULT] = 点按出招，装上即「不点棋盘零识别、
 * 零推理、零支招、零朗读，点一次才跑完整一轮」。每个分支功能在「增强功能」页
 * 都有独立开关（互斥组呈现为四个开/关按钮，开一个自动关其余三个）。
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

    fun isAuto(raw: String?): Boolean = normalize(raw) == AUTO

    /** 是否「不点棋盘就不识别」（前三档都是，AUTO 不是） */
    fun isTapDriven(raw: String?): Boolean = !isAuto(raw)

    fun labelOf(raw: String?): String = when (normalize(raw)) {
        TAP_SUGGEST -> "点按出招"
        TAP_REFRESH -> "点按刷新"
        TAP_CANDIDATE -> "点按变招"
        else -> "自动识别"
    }

    fun hintOf(raw: String?): String = when (normalize(raw)) {
        TAP_SUGGEST -> "不点棋盘＝零识别、零推理、零支招、零朗读；点一次棋盘＝抓当前画面识别一轮 → 开局库/引擎支招 → 中文播报，确认后立刻收场。最省电，推荐。"
        TAP_REFRESH -> "不点棋盘零识别；点一次棋盘＝等同右列「更新」键，用当前局面翻转轮次重新分析（不重新识别画面）。"
        TAP_CANDIDATE -> "不点棋盘零识别；点一次棋盘＝切换候选着法/变招（上游原行为）。"
        else -> "恢复上游的自动连续识别：画面一变化就自动识别推理支招，长时间使用发热明显。非本分支默认。"
    }

    /** 点棋盘这次手势派发什么动作；[AUTO] 档沿用旧的快捷刷新开关决定。 */
    fun resolveAction(
        raw: String?,
        manualMode: Boolean,
        quickRefreshEnabled: Boolean,
    ): Action {
        if (manualMode) return Action.NONE
        return when (normalize(raw)) {
            TAP_SUGGEST -> Action.RECOGNIZE
            TAP_REFRESH -> Action.REFRESH
            TAP_CANDIDATE -> Action.CANDIDATE
            else -> if (quickRefreshEnabled) Action.REFRESH else Action.CANDIDATE
        }
    }

    enum class Action { RECOGNIZE, REFRESH, CANDIDATE, NONE }

    fun switchText(on: Boolean): String = if (on) "开" else "关"
}
