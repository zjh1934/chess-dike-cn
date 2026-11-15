package com.xqdk.chess.assist

/**
 * v1.14：点棋盘「立即读取当前屏幕」的插入点门禁。纯 JVM，CI 可直接断言。
 *
 * 之前错在哪：点棋盘功能挂在**帧循环 / 缓存帧**上 —— startTapRecognition 直接拿
 * `lastFrame`（点击前缓存的画面）去识别。而空闲态帧回调只在帧差 >= 0.2% 时才更新
 * 这份缓存；象棋只动一个小棋子，帧差常常低于阈值，加上帧节流（220/1400ms）与
 * 节拍器退避，对方走完后点击，程序认的还是几十秒前的旧局面。用户原话：
 *「它不会去识别我们的屏幕，它还在按它的节奏去识别，还不到那个时间它不去识别屏幕」。
 *
 * 正确插入点：**点下去那一刻主动抓一帧**。点击后到达的第一帧无条件直投识别，
 * 跳过帧节流 / 帧差阈值 / 帧差节流 / 节拍器 / pendingFrame；识别完支招、念招
 * 是后续自然链路，不额外干预。
 */
object TapCaptureGate {

    /** 帧来源：缓冲现取 > 点击后新帧 > 缓存兜底（某些 ROM 静止屏幕不产帧） */
    enum class Source { LIVE_BUFFER, POST_TAP_FRAME, CACHED_FALLBACK }

    fun pickSource(bufferHit: Boolean, postTapFrame: Boolean): Source = when {
        bufferHit -> Source.LIVE_BUFFER
        postTapFrame -> Source.POST_TAP_FRAME
        else -> Source.CACHED_FALLBACK
    }

    fun label(s: Source): String = when (s) {
        Source.LIVE_BUFFER -> "点击瞬间缓冲现取"
        Source.POST_TAP_FRAME -> "点击后新采帧"
        Source.CACHED_FALLBACK -> "最近屏幕帧兜底"
    }

    /**
     * 点按抓帧待办中的帧是否需要**绕过全部帧循环闸门**直接送识别。
     * pendingSeq 与 sessionSeq 必须一致：连点时旧会话的迟到帧不能顶掉新一轮。
     */
    fun shouldBypassFrameGates(pending: Boolean, pendingSeq: Int, sessionSeq: Int): Boolean =
        pending && pendingSeq == sessionSeq && sessionSeq > 0
}
