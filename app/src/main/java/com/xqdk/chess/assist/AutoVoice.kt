package com.xqdk.chess.assist

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 「回到原作者，只加语音」——本分支唯一的增量。
 *
 * 上游已经是**常开录屏、自动识别、自动支招**：连续 3 帧一致才确认，确认后
 * 自动发一次完整搜索，定着后在悬浮面板显示中文着法。这里不碰任何识别 / 轮次 /
 * 引擎 / 渲染逻辑，只在「定着」那一刻把面板上那句中文着法**自动读出来**。
 *
 * 设计约束（用户原话：「自动读，读支招的内容，放弃原来的瞎改」）：
 * - 不加开关、不加档位、不改设置页；语音恒开，内容就是着法本身（如「炮二平五」）
 * - 不念暂定 PV、不念对方回合的「若对方走X我方应Y」预案、不念分数与「建议/✓」
 * - 同一局面只念一次（成熟即念，bestmove 不补念）；新局面由 [onNewPosition] 重置
 * - 只念我方起点是我方棋子的着法（与上游 renderOverlay 的 fromIsOurs 同口径）
 * - 朗读走媒体流、跟随系统音量；打断用 QUEUE_FLUSH，不排队越积越多
 * - TTS 掉线自愈：speak 后 1.5s 没收到 onStart 回执就重建实例并补念一次
 *
 * 纯 JVM 决策抽到 [AutoVoiceDecision]，CI 可直接下断言；本类只负责系统 TTS。
 */
class AutoVoice(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    /** 初始化完成前排队的最新一条（只保留最新，不堆积） */
    private var pending: String? = null
    /** 待确认出声回执的文本与时间戳 */
    private var awaiting: String? = null
    private var awaitingTs = 0L
    private var rebuiltOnce = false

    private val watchdog = Runnable {
        val text = awaiting
        if (!closed && text != null && System.currentTimeMillis() - awaitingTs >= 1500L) {
            // 系统 TTS 静默掉线：speak 返回 SUCCESS 却既无 onStart 也无 onError。
            // 丢掉失效实例重建，补念一次；只补一次，避免无限循环扰民。
            awaiting = null
            tts?.runCatching { stop() }
            tts?.runCatching { shutdown() }
            tts = null
            ready = false
            if (!rebuiltOnce) {
                rebuiltOnce = true
                pending = text
                initTts()
            }
        }
    }

    init {
        main.post { initTts() }
    }

    private fun initTts() {
        if (closed) return
        // onInit 回调可能早于构造返回，用 holder 持有实例，避免自引用未初始化变量
        val holder = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
                val inst = holder[0]
                if (closed || inst == null) return@OnInitListener
                if (status == TextToSpeech.SUCCESS) {
                    try { inst.setLanguage(Locale.CHINESE) } catch (_: Exception) {}
                    try {
                        inst.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                if (utteranceId == UTT_ID) {
                                    awaiting = null
                                    rebuiltOnce = false
                                }
                            }
                            override fun onDone(utteranceId: String?) = Unit
                            @Suppress("DEPRECATION")
                            override fun onError(utteranceId: String?) {
                                if (utteranceId == UTT_ID) retryOnce()
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                if (utteranceId == UTT_ID) retryOnce()
                            }
                        })
                    } catch (_: Exception) {}
                    ready = true
                    pending?.let { text -> pending = null; doSpeak(text) }
                } else {
                    // 初始化失败不永久静默：下一条建议进来时再试一次
                    ready = false
                    tts = null
                }
        }
        holder[0] = try {
            TextToSpeech(app, listener)
        } catch (_: Throwable) { null }
        tts = holder[0]
        // 构造刚完成就已回调 SUCCESS 的极少数实现：这里再补一次 pending，幂等
        if (ready) pending?.let { text -> pending = null; doSpeak(text) }
    }

    private fun retryOnce() {
        if (closed || rebuiltOnce) return
        val text = awaiting ?: return
        rebuiltOnce = true
        awaiting = null
        tts?.runCatching { stop() }
        tts?.runCatching { shutdown() }
        tts = null
        ready = false
        pending = text
        initTts()
    }

    /** 新局面确认：由服务层调用，重置去重键（去重在 AutoVoiceDecision 里做） */
    fun onNewPosition() {
        // 去重键由服务层持有，这里只打断上一段没念完的话，避免新局面还念旧招
        main.post { tts?.runCatching { stop() }; awaiting = null }
    }

    /**
     * 念一句中文着法。可从引擎线程调用，内部切主线程。
     * 调用方已用 [AutoVoiceDecision] 判定过「该念」。
     */
    fun speak(text: String) {
        if (closed || text.isBlank()) return
        main.post {
            if (closed) return@post
            if (ready) doSpeak(text) else {
                pending = text
                if (tts == null) initTts()
            }
        }
    }

    private fun doSpeak(text: String) {
        val t = tts ?: return
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        val code = try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTT_ID)
        } catch (_: Throwable) { TextToSpeech.ERROR }
        if (code == TextToSpeech.SUCCESS) {
            awaiting = text
            awaitingTs = System.currentTimeMillis()
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 1600L)
        } else {
            awaiting = text
            retryOnce()
        }
    }

    fun shutdown() {
        closed = true
        main.removeCallbacks(watchdog)
        main.post {
            tts?.runCatching { stop() }
            tts?.runCatching { shutdown() }
            tts = null
            ready = false
            pending = null
            awaiting = null
        }
    }

    private companion object {
        const val UTT_ID = "xqdk_auto_voice"
    }
}

/**
 * 纯 JVM 决策：这一招要不要念。不依赖 Android，CI 可逐条下断言。
 *
 * UCCI 坐标与 FEN 盘面口径与上游 `AssistBoard.toFen` 一致：FEN 第一行的 y=0
 * 是黑方底线；大写字母=红方棋子，小写=黑方棋子。
 */
object AutoVoiceDecision {

    data class Verdict(val speak: Boolean, val key: String, val reason: String)

    fun decide(
        fen: String,
        ucci: String?,
        myTurn: Boolean,
        fromIsOurs: Boolean,
        lastKey: String?,
        mature: Boolean,
    ): Verdict {
        if (fen.isBlank()) return Verdict(false, "", "无局面")
        if (ucci.isNullOrBlank() || ucci == "(none)" || ucci.length < 4)
            return Verdict(false, "", "无着法或困毙")
        if (!mature) return Verdict(false, "", "非定着(暂定PV不念)")
        if (!myTurn) return Verdict(false, "", "对方回合预案不念")
        if (!fromIsOurs) return Verdict(false, "", "起点不是我方棋子")
        // v1.27：按**局面**去重，不是按「局面|着法」。成熟时念过一次后，
        // bestmove 再来（哪怕引擎改了主意）也不补念第二招 —— 一个局面只给一招。
        val key = fen
        if (key == lastKey) return Verdict(false, key, "同一局面已念过")
        return Verdict(true, key, "定着·我方·首次 → 念中文着法")
    }

    /** 着法起点是否是我方棋子：解析 FEN 盘面第 (fx,fy) 格的字母大小写。 */
    fun fromIsOurs(fen: String, ucci: String?, myRed: Boolean): Boolean {
        if (ucci == null || ucci.length < 4) return false
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        if (fx !in 0..8 || fy !in 0..9) return false
        val board = fen.trim().split(' ').getOrNull(0) ?: return false
        val rows = board.split('/')
        if (rows.size < 10) return false
        var x = 0
        for (c in rows[fy]) {
            if (c.isDigit()) x += c - '0'
            else {
                if (x == fx) {
                    val red = c.isUpperCase()
                    return red == myRed
                }
                x++
            }
            if (x > fx) break
        }
        return false
    }
}
