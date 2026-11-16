package com.xqdk.chess.assist

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 支招结果的语音提示（Android 原生 TextToSpeech，不引入任何第三方 TTS SDK）。
 *
 * 把 [VoicePrompt] 的纯函数决策交给系统 TTS 朗读，并负责：
 * - 异步初始化：TTS 初始化完成前的新结果排队（只保留最新一条），init 成功后补读；
 * - 打断：新着法用 QUEUE_FLUSH 停掉上一段、改读新的一条；
 * - 去重：同一条着法不重复读（仅在 tts.speak 真正返回 SUCCESS 后才记去重）；
 * - 引擎选择：跟随系统（默认）或设备已安装的指定 TTS 引擎，包名变化时重建实例；
 * - 音量：走 AudioManager.STREAM_MUSIC 媒体流，跟随系统媒体音量，不设额外音量条。
 *
 * v1.1 修复（支招完全不朗读）：
 * - onInit 失败不再不可逆：置空 tts、标记失败态，下一条 onSuggestion 会重新 rebuild 重试；
 * - 初始化成功先 setLanguage(Locale.CHINESE) 并检查返回值，中文缺失再兜底，不放弃朗读；
 * - 构造/初始化/朗读/出声全过程写独立语音诊断日志（VoiceDiag），错误可见、可自愈。
 *
 * 新增 [speakTest]：供设置页用独立实例试听，绕过支招条件与去重，朗读结束自行释放。
 *
 * 生命周期：由 ScreenAssistService 持有，服务 onDestroy 时 [shutdown]；设置页引擎
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
    /** 上一次朗读的着法文本（去重，仅在 speak 返回 SUCCESS 后写入） */
    private var lastSpoken: String? = null
    /** 最近一次初始化失败：待下一条建议或 [resetDedup] 触发重新 rebuild 重试 */
    private var initFailed = false
    /** 试听实例标志：跳过去重、朗读结束（onDone/onError）后自行 shutdown 释放 TTS */
    private var testMode = false

    // ==================== v1.19：出声回执 + TTS 掉线自愈 ====================
    /**
     * 03:26 诊断暴露的真机问题：最后一次 `speak SUCCESS` 是 03:21:47「车六进二」，
     * 但**连 onStart 都没有**；此后 12 次「决策→打断」全打齐了，却再没有一条
     * speak SUCCESS / onStart / onDone，系统 TTS 那头已经哑了，程序还以为活着。
     * 切到黑之后更彻底（连「决策」都没有）—— 实例进了不可恢复的 shutdown 态。
     *
     * 这里不看"调用有没有返回 SUCCESS"，只看**系统有没有真的回 onStart**：
     * speak 后 1.5 秒没收到 onStart 就判定掉线 → 丢弃失效实例、重新绑定并补读一次。
     */
    private var dead = false
    private var awaitingStart = false
    private var lastSpeakConfirmed = false
    private var lastText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startWatchdog = Runnable { onStartWatchdog() }

    /**
     * 一条支招结果产生时调用（renderOverlay 内、主线程）。
     * 开关与引擎每次从持久化配置现读，设置页改动即时生效。
     *
     * v1.2：扩展 advantageText / readMask 两个默认参数（旧 4 参数调用仍可编译），
     * 交给 [VoicePrompt.resolve] 做「朗读内容多选」决策，并在决策处写诊断埋点
     * （类别 / 勾选态 / 跳过原因 / 文本清洗前后对比 / 最终朗读文本）。
     */
    fun onSuggestion(
        moveCount: Int,
        enabled: Boolean,
        suggestionText: String,
        engine: String?,
        advantageText: String = "",
        readMask: Int = VoicePrompt.DEFAULT_MASK,
    ) {
        synchronized(this) {
            // v1.19：以前 shuttingDown 一旦为 true 就永久静默（日志里连「决策」都没有）。
            // 朗读器只由本服务持有，服务还活着就该能自愈，不再不可逆。
            if (shuttingDown || dead || initFailed) repair()
            val decision = VoicePrompt.resolve(moveCount, enabled, suggestionText, advantageText, readMask)
            // 决策埋点：原文换行压平保证单行；跳过原因由 resolve.reason 枚举给出。
            // 变量先算好再插值，避免在字符串模板里嵌套双引号字面量。
            val reasonText = decision.reason.ifEmpty { "—" }
            val spokenText = decision.text.ifEmpty { "—" }
            val raw = suggestionText.replace("\n", "／")
            VoiceDiag.log(appContext, "决策",
                "类别=${decision.category.name} 朗读=${decision.shouldSpeak} " +
                    "reason=$reasonText ｜ 原文=$raw → 朗读文本=$spokenText")
            when {
                // 开关明确关闭：立即停读并清去重态（重开后当前建议可重新朗读）
                !enabled -> cancel()
                // 支两招/三招、类别未勾选、空文本等：不朗读、也不打断正在读的一段
                !decision.shouldSpeak -> Unit
                else -> {
                    rebuild(engine)
                    speak(decision.text)
                }
            }
        }
    }

    /**
     * v1.19 警示：**只给独立实例用**。本方法会置 testMode 并在朗读结束后
     * `finishTestIfNeeded → shutdown()`，把 `shuttingDown` 永久置 true。
     * v1.18 曾把它挂在共享的 voiceSpeaker 上做失败提示，等于念完一句就把
     * 整个支招朗读器封死。服务层失败提示已改用 [speakNotice]。
     *
     * 试听：用当前选中引擎朗读一句固定中文，绕过 VoicePrompt 支招条件与去重。
     * 调用方应使用**独立的** VoiceSpeaker 实例；本实例在朗读结束或初始化/speak
     * 失败后自行 [shutdown]，避免持有 TTS 服务连接泄漏。
     */
    fun speakTest(text: String, engine: String?) {
        synchronized(this) {
            if (shuttingDown) return
            testMode = true
            VoiceDiag.log(appContext, "试听开始",
                "engine=${engine?.takeIf { it.isNotEmpty() } ?: "跟随系统"} text=$text")
            rebuild(engine)               // 复用同一套初始化/构造/日志逻辑
            if (tts == null) {            // 构造失败：rebuild 已记日志，这里兜底释放
                shutdown()
                return
            }
            speak(text)                   // 复用 setLanguage(onInit)/speak/返回值检查
        }
    }

    /**
     * v1.19：点按朗读前调用。实例为 dead / 已 shutdown / 初始化失败时**自愈重建**，
     * 不再出现「点了棋盘，连决策行都没有」的永久静默。
     */
    fun ensureAlive() {
        synchronized(this) {
            if (shuttingDown || dead || initFailed || tts == null) repair()
        }
    }

    /** 最近一次朗读是否收到系统 onStart 出声回执（供链路日志如实写结果）。 */
    fun isConfirmed(): Boolean = synchronized(this) { lastSpeakConfirmed }

    /** v1.19：丢弃失效的 TTS 实例，下一条建议重新绑定；同时解除 shutdown 死锁。 */
    fun repair() {
        synchronized(this) {
            mainHandler.removeCallbacks(startWatchdog)
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
            initFailed = false
            shuttingDown = false
            dead = false
            testMode = false
            awaitingStart = false
            pendingText = null
            lastSpoken = null
            VoiceDiag.log(appContext, "TTS重建",
                "朗读器自愈：丢弃失效实例，解除 shutdown 死锁，下一条建议重新绑定系统 TTS")
        }
    }

    /**
     * v1.19：失败/兜底提示专用。**不设 testMode、不 finishTestIfNeeded**，
     * 因此不会把共享朗读器 shutdown 掉（speakTest 的坑）。
     */
    fun speakNotice(text: String, engine: String?) {
        synchronized(this) {
            if (shuttingDown || dead) repair()
            rebuild(engine)
            speak(text)
        }
    }

    /** 新局面到来：清去重态与排队，允许同一着法在新局面下再次朗读；重置失败态以便重试。 */
    fun resetDedup() {
        synchronized(this) {
            lastSpoken = null
            pendingText = null
            if (initFailed) {
                // 失败态：丢弃半成品实例，下一条建议触发全新 rebuild（onSuggestion→rebuild）
                runCatching { tts?.shutdown() }
                tts = null
                ready = false
                initFailed = false
                VoiceDiag.log(appContext, "失败态重置", "下一条建议将重新初始化 TTS")
            } else {
                VoiceDiag.log(appContext, "去重复位", "清 lastSpoken/pendingText")
            }
        }
    }

    /** 组件销毁：停读、释放 TTS 服务连接，之后不再接受朗读 */
    fun shutdown() {
        synchronized(this) {
            shuttingDown = true
            ready = false
            pendingText = null
            lastSpoken = null
            initFailed = false
            awaitingStart = false
            mainHandler.removeCallbacks(startWatchdog)
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
        initFailed = false
        gen++
        val myGen = gen
        val listener = TextToSpeech.OnInitListener { status -> onInit(myGen, status) }
        tts = try {
            if (normalized == null) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, normalized)
        } catch (t: Throwable) {
            // 构造异常：记诊断日志，绝不静默吞成 null；tts 保持 null，下一条建议可重建重试
            VoiceDiag.log(appContext, "TTS构造异常", t.toString())
            initFailed = true
            null
        }
        VoiceDiag.log(appContext, "TTS实例构造",
            "engine=${normalized ?: "跟随系统"} 成功=${tts != null}")
    }

    private fun onInit(g: Int, status: Int) {
        synchronized(this) {
            if (g != gen || shuttingDown) return   // 迟到/已销毁实例的回调作废
            if (status != TextToSpeech.SUCCESS) {
                // 根因①：初始化失败不可逆 → 永久闭环。这里置空 tts、标记失败态，
                // 使下一条 onSuggestion 的 rebuild 重新构造实例（绝不写 lastSpoken）。
                VoiceDiag.log(appContext, "onInit失败", "status=$status（下次建议重新初始化）")
                initFailed = true
                ready = false
                runCatching { tts?.shutdown() }
                tts = null
                if (testMode) finishTestIfNeeded(g)
                return
            }
            initFailed = false
            val t = tts
            if (t == null) {
                VoiceDiag.log(appContext, "onInit异常", "SUCCESS 但 tts=null")
                return
            }
            VoiceDiag.log(appContext, "onInit", "SUCCESS engine=${currentEngine ?: "跟随系统"}")
            VoiceDiag.log(appContext, "locale", "默认=${Locale.getDefault()}")
            // 根因②：朗读前先设中文，检查返回值并记日志；缺数据/不支持再兜底
            configureLanguage(t)
            // 根因③：把"到底有没有真的出声"落到硬证据（onStart/onDone/onError）
            runCatching {
                t.setOnUtteranceProgressListener(makeProgressListener(g))
            }.onFailure { VoiceDiag.log(appContext, "listener设置失败", it.toString()) }
            ready = true
            pendingText?.let { p ->
                pendingText = null
                speak(p)   // 补读：复用 speak（成功后才写 lastSpoken）
            }
        }
    }

    /**
     * setLanguage(Locale.CHINESE) 并检查返回值；中文不可用依次尝试
     * Locale.SIMPLIFIED_CHINESE / Locale.getDefault()，全程写日志，绝不因语言失败放弃朗读。
     * 调用方持有 synchronized(this)。
     */
    private fun configureLanguage(t: TextToSpeech) {
        var r = runCatching { t.setLanguage(Locale.CHINESE) }
            .getOrElse { TextToSpeech.LANG_NOT_SUPPORTED }
        VoiceDiag.log(appContext, "setLanguage", "Locale.CHINESE → ${describeLang(r)}")
        if (r < 0) {
            r = runCatching { t.setLanguage(Locale.SIMPLIFIED_CHINESE) }
                .getOrElse { TextToSpeech.LANG_NOT_SUPPORTED }
            VoiceDiag.log(appContext, "setLanguage", "Locale.SIMPLIFIED_CHINESE → ${describeLang(r)}")
        }
        if (r < 0) {
            r = runCatching { t.setLanguage(Locale.getDefault()) }
                .getOrElse { TextToSpeech.LANG_NOT_SUPPORTED }
            VoiceDiag.log(appContext, "setLanguage", "Locale.getDefault → ${describeLang(r)}")
        }
    }

    /** UtteranceProgressListener：API 15+ 可用，onError(String,Int) 自 API 21，minSdk 26 全覆盖。 */
    private fun makeProgressListener(g: Int) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            onUtterance(g, "onStart", utteranceId ?: "")
        }

        override fun onDone(utteranceId: String?) {
            onUtterance(g, "onDone", utteranceId ?: "")
            finishTestIfNeeded(g)
        }

        @Suppress("DEPRECATION")
        override fun onError(utteranceId: String?) {
            onUtterance(g, "onError(legacy)", utteranceId ?: "")
            finishTestIfNeeded(g)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onUtterance(g, "onError", "code=$errorCode id=${utteranceId ?: ""}")
            finishTestIfNeeded(g)
        }
    }

    /** 回调里不再持有已 shutdown 的实例：gen 变化或 shuttingDown 直接忽略，只写日志。 */
    private fun onUtterance(g: Int, tag: String, detail: String) {
        synchronized(this) {
            if (g != gen || shuttingDown) return
            VoiceDiag.log(appContext, tag, detail)
            when {
                tag == "onStart" -> {
                    awaitingStart = false
                    lastSpeakConfirmed = true
                    mainHandler.removeCallbacks(startWatchdog)
                    VoiceDiag.log(appContext, "朗读回执",
                        "onStart 已出声 ✓ 文本=${lastText ?: "—"}")
                }
                tag.startsWith("onError") -> {
                    awaitingStart = false
                    lastSpeakConfirmed = false
                    mainHandler.removeCallbacks(startWatchdog)
                    VoiceDiag.log(appContext, "朗读回执",
                        "$tag → 系统朗读失败，重建并补读一次")
                    repairAndRepeat()
                }
            }
        }
    }

    /** speak 成功但 1.5 秒内系统没回 onStart：判定 TTS 服务掉线，重建并补读一次。 */
    private fun onStartWatchdog() {
        synchronized(this) {
            if (!awaitingStart) return
            awaitingStart = false
            lastSpeakConfirmed = false
            VoiceDiag.log(appContext, "朗读无回执",
                "speak 返回 SUCCESS 但 1.5s 内没有 onStart → 系统 TTS 已掉线，重建并补读一次")
            repairAndRepeat()
        }
    }

    /** 调用方持有 synchronized(this)；用主线程异步重建，避免 Binder 回调里重入 speak。 */
    private fun repairAndRepeat() {
        val text = lastText
        repair()
        if (text != null) {
            mainHandler.post {
                synchronized(this) { rebuild(null); speak(text) }
            }
        }
    }

    /** 试听实例在朗读结束（或初始化/speak 失败）后释放 TTS，避免泄漏。 */
    private fun finishTestIfNeeded(g: Int) {
        synchronized(this) {
            if (g == gen && testMode && !shuttingDown) shutdown()
        }
    }

    /** 调用方持有 synchronized(this)。只有 tts.speak 返回 SUCCESS 才写去重。 */
    private fun speak(text: String) {
        if (!testMode && text == lastSpoken) {
            VoiceDiag.log(appContext, "去重", "跳过重复：$text")
            return
        }
        val t = tts
        if (!ready || t == null) {
            // 根因①：排队态只保留最新一条，绝不写 lastSpoken（旧版在此写去重→锁死去重）
            pendingText = text
            VoiceDiag.log(appContext, "排队", "未就绪，保留最新：$text")
            return
        }
        lastText = text
        lastSpeakConfirmed = false
        VoiceDiag.log(appContext, "打断", "QUEUE_FLUSH 覆盖上一段")
        val result = try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, streamParams(), UTTERANCE_ID)
        } catch (e: Throwable) {
            VoiceDiag.log(appContext, "speak异常", "$e → 判定 TTS 掉线，重建并补读一次")
            repairAndRepeat()
            return
        }
        if (result == TextToSpeech.SUCCESS) {
            lastSpoken = text
            // v1.19：SUCCESS 不等于出声，必须等系统 onStart 回执
            awaitingStart = true
            mainHandler.removeCallbacks(startWatchdog)
            mainHandler.postDelayed(startWatchdog, START_TIMEOUT_MS)
            VoiceDiag.log(appContext, "speak",
                "SUCCESS：$text（等待 onStart 出声回执，1.5s 无回执即判定掉线）")
        } else {
            // 根因③：非 SUCCESS 记日志、不写去重；v1.19 进一步重建补读，不再干等下一轮
            VoiceDiag.log(appContext, "speak失败",
                "result=$result：$text → 判定 TTS 掉线，重建并补读一次")
            if (testMode) finishTestIfNeeded(gen) else repairAndRepeat()
        }
    }

    /** 调用方持有 synchronized(this)。 */
    private fun cancel() {
        val hadPending = pendingText != null || lastSpoken != null
        pendingText = null
        lastSpoken = null
        if (hadPending) {
            runCatching { tts?.stop() }
            VoiceDiag.log(appContext, "开关关闭", "停读并清去重态")
        }
    }

    /** 媒体流：音量跟随系统媒体音量，不设额外音量条 */
    private fun streamParams(): Bundle = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    companion object {
        private const val UTTERANCE_ID = "xqdk_voice_prompt"
        /** v1.19：speak 返回 SUCCESS 后，等多久没收到 onStart 就判定 TTS 掉线 */
        private const val START_TIMEOUT_MS = 1500L

        private fun describeLang(r: Int): String = when {
            r == TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA"
            r == TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
            r >= 0 -> "OK($r)"
            else -> "UNKNOWN($r)"
        }
    }
}
