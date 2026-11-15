package com.xqdk.chess.assist

/**
 * v1.12：点按会话「等定着」超时怎么办。纯 JVM，CI 可直接测。
 *
 * v1.11 真机日志 20 次点按有 5 次（seq 2/3/4/16/19）干等 12 秒后空手收场，
 * 用户说的「有时读、有时不读」就是它：多帧确认吃光了会话预算，后面没时间等定着。
 *
 * 现在预算从「建局完成」起算（8 秒）；到点若引擎已有最佳着，就把它当定着念一次，
 * **绝不空手**。TapSpeakGate 保证一次点按只念一招，所以不会变回「多指挥」。
 */
object TapSettleGate {
    enum class Decision {
        /** 用当前最佳着念一次并收场 */
        FORCE_SPEAK,
        /** 引擎连一招都没出：如实提示再点一次 */
        GIVE_UP,
    }

    fun onTimeout(awaitingSettle: Boolean, alreadySpoke: Boolean, hasAnalysis: Boolean): Decision =
        if (awaitingSettle && !alreadySpoke && hasAnalysis) Decision.FORCE_SPEAK
        else Decision.GIVE_UP
}
