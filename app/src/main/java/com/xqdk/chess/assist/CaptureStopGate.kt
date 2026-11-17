package com.xqdk.chess.assist

/**
 * v1.12：主页「一键关闭录屏」的可用性判定。纯 JVM，CI 可直接下断言。
 *
 * v1.7~v1.11 把按钮 isEnabled 与「Activity 绑定到的 service」绑死：服务重启 / 页面
 * 重建导致绑定断开时，running=false → 按钮变灰，或点击时 svc==null 只弹个 Toast
 * 提前 return —— 用户实测「按了没有什么效果」，日志里连一条「来源=主页一键关闭录屏」
 * 都没有。现在服务自身的 activeService 全局引用作兜底，按钮始终可点。
 */
object CaptureStopGate {
    data class Verdict(val ok: Boolean, val source: String, val hint: String)

    /**
     * v1.22：**不再拦截**。用户按「一键关闭共享屏幕」就是要立刻停。
     * v1.7~v1.21 在 `capturing=false` 或服务引用断掉时提前 return，只弹 Toast，
     * 用户实测「按了没有什么效果」——11:44 日志里连一条「录屏停止」都没有。
     * 现在无论状态如何都返回 ok，调用方一律执行停止；hint 只写进日志，不作门槛。
     */
    fun resolve(boundAvailable: Boolean, globalAvailable: Boolean, capturing: Boolean): Verdict {
        val source = when {
            boundAvailable -> "绑定"
            globalAvailable -> "全局兜底"
            else -> "无引用(仍发停止意图)"
        }
        val hint = if (capturing) "" else "录屏状态=未运行，仍照常执行停止"
        return Verdict(true, source, hint)
    }
}
