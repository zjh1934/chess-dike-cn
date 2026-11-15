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

    fun resolve(boundAvailable: Boolean, globalAvailable: Boolean, capturing: Boolean): Verdict {
        if (!boundAvailable && !globalAvailable)
            return Verdict(false, "无可用引用", "服务未连接，请回主页重新授权启动")
        if (!capturing)
            return Verdict(false, "录屏未运行", "录屏未在运行，无需关闭")
        return Verdict(true, if (boundAvailable) "绑定" else "全局兜底", "")
    }
}
