package com.xqdk.chess.assist

/**
 * 点按触发识别（v1.4）：悬浮窗迷你棋盘点按行为的顶层决策，纯函数便于 JVM 单测。
 *
 * 本版把「自动连续识别」整体关掉：不点棋盘 = 零识别、零推理、零支招、零朗读；
 * 点一次 = 抓当前画面识别一轮 → 确认局面 → 引擎支招 → 语音播报。
 *
 * - [Action.RECOGNIZE]：点棋盘派发 TAP_RECOGNIZE，服务进入一次识别会话。
 * - [Action.LEGACY]：开关关闭时退回 v1.3 的快捷刷新/切换候选（**仍然不会自动识别**）。
 * - [Action.NONE]：手动摆棋优先，点格用于选子/走子，不抢这次点按。
 *
 * 零 android.* 依赖，可被 JVM 单测直接跑。
 */
object TapTrigger {
    /** 默认开：装上即「点棋盘才识别」，不需要任何设置。 */
    const val DEFAULT_ENABLED = true

    enum class Action { RECOGNIZE, LEGACY, NONE }

    fun resolve(enabled: Boolean, manualMode: Boolean = false): Action = when {
        manualMode -> Action.NONE
        enabled -> Action.RECOGNIZE
        else -> Action.LEGACY
    }

    fun switchText(enabled: Boolean): String = if (enabled) "开" else "关"
}
