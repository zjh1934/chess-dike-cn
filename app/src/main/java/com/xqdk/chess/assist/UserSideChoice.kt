package com.xqdk.chess.assist

/**
 * v1.13：悬浮面板上的「我方 红 / 黑」互斥锁定。纯 JVM，CI 可直接断言。
 *
 * 用户实测：第一盘执黑、第二盘执红，软件仍按黑方支招。根因是我方阵营
 * 「一局只认一次」（mySideLockedRed），换盘时只有识别到满 32 子的新开局才重定；
 * 厂商换边 / 识别没判成新局时，上一盘的锁定就带过去了。
 *
 * 用户拍板的方案：**不在设置页**，把悬浮面板上原来那个「哈希」按钮改成
 * 「我方 红 / 黑」切换。点一下切到另一色并锁定 —— 只有一个值，天然互斥，
 * 选了黑就不可能同时是红。锁定后**跨对局、跨服务重启都保持**，自动判定
 * （屏幕下方＝我方）和「新对局重定」都不能再把它翻回去。
 *
 * 哈希档位保持默认 256MB 不再可点，配置仍在引擎侧生效。
 */
object UserSideChoice {
    /** 持久化编码：-1 = 跟随识别（自动），0 = 黑，1 = 红 */
    const val AUTO = -1
    const val BLACK = 0
    const val RED = 1

    fun encode(red: Boolean?): Int = when (red) {
        null -> AUTO
        true -> RED
        false -> BLACK
    }

    fun decode(v: Int): Boolean? = when (v) {
        RED -> true
        BLACK -> false
        else -> null
    }

    /**
     * 点一下按钮：切到另一色并锁定。
     * 未锁定时以「当前自动判定的我方」为基准取反（第一盘自动判黑，点一下就锁红）。
     */
    fun toggle(currentOverride: Boolean?, autoRed: Boolean): Boolean =
        !(currentOverride ?: autoRed)

    /** 生效值：手动锁定**绝对优先**；没锁才走自动判定（含一局锁死、新对局重定）。 */
    fun effective(override: Boolean?, redIsBottom: Boolean, isNewGame: Boolean,
                  previousLocked: Boolean?): Boolean =
        override ?: TapTurnInference.resolveMySide(previousLocked, redIsBottom, isNewGame)

    fun label(red: Boolean, locked: Boolean): String =
        "${if (locked) "我方✓" else "我方"} ${if (red) "红" else "黑"}"
}
