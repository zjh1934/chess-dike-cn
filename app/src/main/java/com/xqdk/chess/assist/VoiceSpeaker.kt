package com.xqdk.chess.assist

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 支招结果的语音提示（Android 原生 TextToSpeech，不引入任何第三方 TTS SDK）。
 *
 * 把 [VoicePrompt] 的纯函数决策交给系统 TTS 朗读，并负责：
 * - 异步初始化：TTS 初始化完成前的新结果排队（只保留最新一条），init 成功后补读；
 * - 打断：新着法用 QUEUE_FLUSH 停掉上一段、改读新的一条；
 * - 去重：同一条着法不重复读（[VoicePrompt.moveText] 已剥离成熟度/预判装饰，
 *   「暂定 → 定着」不会让同一着法被读两遍）；
 * - 引擎选择：跟随系统（默认）或设备已安装的指定 TTS 引擎，包名变化时重建实例；
 * - 音量：走 AudioManager.STREAM_MUSIC 媒体流，跟随系统媒体音量，不设额外音量条。
 *
 * 生命周期：由 ScreenAssistService 持有，服务 onDestroy 时 [shutdown]；设置页的引擎
 * 枚举另用一次性 TTS 探测实例、用完即 shutdown。内部状态全部在 synchronized(this)
 * 内访问（renderOverlay 主线程 / 状态复位 worker 线程 / TTS onInit 回调可能并发）。
 */
class VoiceSpeaker(context: Context) {

    // applicationContext：不持有 Activity/Service 引用，避免 TTS 服务连接泄漏组件
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    private var shuttingDown = false
    /** 当前 TTS 引擎包名；null = 跟随系统默认 */
    private var currentEngine: String? = null
    /** 实例代际：引擎切换重建后，旧实例的迟到 onInit 回调按 gen 作废 */
    private var gen = 0
    /** 初始化完成前排队的最新一条朗读文本 */
    private var pendingText: String? = null
    /** 上一次朗读的着法文本（去重） */
    private var lastSpoken: String? = null

    /**
     * 一条支招结果产生时调用（renderOverlay 内、主线程）。
     * 开关与引擎每次从持久化配置现读，设置页改动即时生效。
     */
    fun onSuggestion(moveCount: Int, enabled: Boolean, suggestionText: String, engine: String?) {
        synchronized(this) {
            if (shuttingDown) return
            val decision = VoicePrompt.resolve(moveCount, enabled, suggestionText)
            when {
                // 开关明确关闭：立即停读并清去重态（重开后当前建议可重新朗读）
                !enabled -> cancel()
                // 支两招/三招、或非建议类文案：不朗读、也不打断正在读的一段
                !decision.shouldSpeak -> Unit
                else -> {
                    rebuild(engine)
                    speak(decision.text)
                }
            }
        }
    }

    /** 新局面到来：清去重态，允许同一着法在新局面下再次朗读 */
    fun resetDedup() {
        synchronized(this) { lastSpoken = null }
    }

    /** 组件销毁：停读、释放 TTS 服务连接，之后不再接受朗读 */
    fun shutdown() {
        synchronized(this) {
            shuttingDown = true
            ready = false
            pendingText = null
            lastSpoken = null
            runCatching { tts?.shutdown() }
            tts = null
        }
    }

    /** 调用方持有 synchronized(this)。引擎包名变化时才重建（首次为懒初始化）。 */
    private fun rebuild(engine: String?) {
        val normalized = engine?.takeIf { it.isNotEmpty() }
        if (tts != null && normalized == currentEngine) return
        runCatching { tts?.shutdown() }
        currentEngine = normalized
        ready = false
        gen++
        val myGen = gen
        val listener = TextToSpeech.OnInitListener { status -> onInit(myGen, status) }
        tts = try {
            if (normalized == null) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, normalized)
        } catch (t: Throwable) {
            null
        }
    }

    private fun onInit(g: Int, status: Int) {
        synchronized(this) {
            if (g != gen || shuttingDown || status != TextToSpeech.SUCCESS) return
            ready = true
            pendingText?.let { p ->
                pendingText = null
                runCatching {
                    tts?.speak(p, TextToSpeech.QUEUE_FLUSH, streamParams(), UTTERANCE_ID)
                }
            }
        }
    }

    /** 调用方持有 synchronized(this)。 */
    private fun speak(text: String) {
        if (text == lastSpoken) return
        lastSpoken = text
        val t = tts
        if (!ready || t == null) {
            pendingText = text   // 未初始化完成：只保留最新一条
            return
        }
        runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, streamParams(), UTTERANCE_ID) }
    }

    /** 调用方持有 synchronized(this)。 */
    private fun cancel() {
        val hadPending = pendingText != null || lastSpoken != null
        pendingText = null
        lastSpoken = null
        if (hadPending) runCatching { tts?.stop() }
    }

    /** 媒体流：音量跟随系统媒体音量，不设额外音量条 */
    private fun streamParams(): Bundle = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    companion object {
        private const val UTTERANCE_ID = "xqdk_voice_prompt"
    }
}
