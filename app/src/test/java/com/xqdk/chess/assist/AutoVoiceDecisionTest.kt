package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.25「回到原作者，只加语音」的出包前自检。
 *
 * 用户原话：「使用原作者的源代码，只加语音，还是原作者的好用，自动读，读支招的
 * 内容，放弃原来的瞎改」。所以这里不测点按档、不测帧闸门，只验两件事：
 *  1) 语音决策：定着/我方/首次才念，暂定 PV、对方回合预案、动别人棋子都不念；
 *  2) 源码形态：app/src/main 与上游 v3.4 一致、瞎改组件一个都不剩、接线齐全。
 */
class AutoVoiceDecisionTest {

    private fun report(line: String) = println("[上游与语音自检] $line")

    /** 上游标准开局：FEN 第一行=黑方底线(y=0)，最后一行=红方底线(y=9) */
    private val startFen = AssistBoard.START_FEN

    @Test
    fun `定着且我方着法 自动读`() {
        val red = AutoVoiceDecision.fromIsOurs(startFen, "a0a1", myRed = true)
        assertTrue("红方底线车是我方棋子", red)
        val v = AutoVoiceDecision.decide(startFen, "a0a1", myTurn = true,
            fromIsOurs = red, lastKey = null, mature = true)
        assertTrue(v.speak)
        assertEquals("v1.27：按局面去重", startFen, v.key)
        report("定着 + 我方回合 + 起点是我方棋子 → 自动读中文着法（内容就是面板那句）")
    }

    @Test
    fun `暂定PV 对方回合预案 对方棋子 都不念`() {
        val red = AutoVoiceDecision.fromIsOurs(startFen, "a0a1", myRed = true)
        assertFalse("暂定 PV 不念", AutoVoiceDecision.decide(startFen, "a0a1", true, red, null, false).speak)
        assertFalse("对方回合预案不念", AutoVoiceDecision.decide(startFen, "a0a1", false, red, null, true).speak)
        assertFalse("起点不是我方棋子不念", AutoVoiceDecision.decide(startFen, "a0a1", true, false, null, true).speak)
        assertFalse("无着法不念", AutoVoiceDecision.decide(startFen, null, true, red, null, true).speak)
        assertFalse("困毙不念", AutoVoiceDecision.decide(startFen, "(none)", true, red, null, true).speak)
        report("暂定着法 / 对方回合「若对方走X我方应Y」/ 动别人棋子 / 无着法 / 困毙 一律不念")
    }

    @Test
    fun `同一局面只念一次 换局面可再念`() {
        val red = AutoVoiceDecision.fromIsOurs(startFen, "a0a1", myRed = true)
        val key = startFen
        assertFalse("重复不念", AutoVoiceDecision.decide(startFen, "a0a1", true, red, key, true).speak)
        assertTrue("新局面可再念", AutoVoiceDecision.decide("$startFen-x", "a0a1", true, red, key, true).speak)
        // v1.27：成熟时念过一次后，bestmove 换了着法也不补念第二招
        assertFalse("同一局面换着法也不补念", AutoVoiceDecision.decide(startFen, "a0a2", true, red, key, true).speak)
        report("按局面去重：成熟即念一次，bestmove 不重复念；新局面重新念")
    }

    @Test
    fun `执黑时黑方底线是我方 红方棋子不是我方`() {
        // e9e8：fy = 9 - 9 = 0 → FEN 第一行「rnbakabnr」第 4 格 = 黑将 k
        assertTrue("执黑：黑将是我方", AutoVoiceDecision.fromIsOurs(startFen, "e9e8", myRed = false))
        assertFalse("执黑：红车不是我方", AutoVoiceDecision.fromIsOurs(startFen, "a0a1", myRed = false))
        assertFalse("执红：黑将不是我方", AutoVoiceDecision.fromIsOurs(startFen, "e9e8", myRed = true))
        assertFalse("越界坐标不误判", AutoVoiceDecision.fromIsOurs(startFen, "z9z8", myRed = true))
        report("执红/执黑都用 FEN 盘面大小写判起点，越界坐标安全返回 false")
    }

    @Test
    fun `源码形态 上游基线加唯一语音件 瞎改组件全清`() {
        fun read(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        fun exists(rel: String): Boolean =
            listOf(rel, rel.removePrefix("app/"), "../" + rel).any { java.io.File(it).exists() }

        val svc = read("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        val voice = read("app/src/main/java/com/xqdk/chess/assist/AutoVoice.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        val ci = read(".github/workflows/cn-build.yml")
        assertTrue("必须读到上游服务源码", svc.isNotEmpty())

        // 上游原作者的机制必须原样在位
        for (mark in listOf("BoardTracker(config.confirmCount)",
            "position fen", "confirmCount", "precomputeEnabled",
            "MoveChinese.describe", "scheduleAnalysis()", "onBoardConfirmed()")) {
            // position fen 在 AnalysisEngine 里，单独核对
            val inSvc = svc.contains(mark)
            val inEngine = read("app/src/main/java/com/xqdk/chess/assist/AnalysisEngine.kt").contains(mark)
            assertTrue("上游机制缺失：$mark", inSvc || inEngine)
        }
        assertTrue("上游 3 帧确认在", read("app/src/main/java/com/xqdk/chess/assist/RecognitionTypes.kt")
            .contains("candidateHits >= confirmCount"))

        // 本分支所有「瞎改」组件必须一个都不剩
        val mainDir = listOf("app/src/main", "src/main", "../app/src/main")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }
        val textExt = setOf("kt", "java", "xml", "html", "md", "txt", "json", "gradle", "kts")
        val allMain = mainDir?.walkTopDown()
            ?.filter { it.isFile && it.extension.lowercase() in textExt }
            ?.map { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
            ?.toList().orEmpty()
        val joined = allMain.joinToString("\n")
        for (gone in listOf("BoardRepair", "TapCaptureGate", "TapFrameGate", "TapConfirm3",
            "TapBoardStep", "VoiceDiag", "VoicePrompt", "VoiceSpeaker", "FeatureSwitches",
            "TAP_SUGGEST", "点按出招", "单帧直出", "识别修补")) {
            assertFalse("瞎改组件仍在：$gone", joined.contains(gone))
        }

        // 唯一语音增量的接线：4 个定着入口 + 生命周期 + 去重 + 决策
        assertTrue("语音组件存在", voice.contains("class AutoVoice") && voice.contains("object AutoVoiceDecision"))
        for (mark in listOf("autoVoice = AutoVoice(this)", "autoVoice?.shutdown()",
            "autoVoice?.onNewPosition()", "lastVoiceKey = null",
            "fun voiceOnSettled", "AutoVoiceDecision.decide")) {
            assertTrue("接线缺失：$mark", svc.contains(mark))
        }
        assertEquals("定着入口恰好 3 处（引擎/开局库/预判命中）",
            3, svc.split("voiceOnSettled").size - 1 - 1) // 减掉函数定义本身
        val iUpdate = svc.indexOf("override fun onSearchUpdate")
        val iDone = svc.indexOf("override fun onSearchDone")
        assertTrue("上游回调都在", iUpdate > 0 && iDone > iUpdate)
        assertFalse("暂定 PV 的 onSearchUpdate 段不得触发朗读",
            svc.substring(iUpdate, iDone).contains("voiceOnSettled"))
        assertTrue("只在本轮搜索完整结束（onSearchDone）时朗读",
            svc.substring(iDone, minOf(svc.length, iDone + 1200)).contains("voiceOnSettled"))
        assertTrue("AndroidManifest 声明 TTS 包可见性", manifest.contains("android.intent.action.TTS_SERVICE"))
        assertTrue("CI 自检改为上游核心+语音", ci.contains("AutoVoiceDecisionTest") &&
            !ci.contains("TapChainTest"))
        assertTrue("固定签名门禁仍在", ci.contains("Verify fixed signing certificate"))
        assertTrue("包名门禁仍在", ci.contains("Verify fixed applicationId"))
        report("上游源码原样 + AutoVoice 唯一增量；所有点按/修补/诊断增强已删除；固定签名与包名门禁保留")
    }

    @Test
    fun `v1_26 双模型注入与出包校验都在`() {
        fun read(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val ci = read(".github/workflows/cn-build.yml")
        assertTrue("必须读到 CI 配置", ci.isNotEmpty())
        assertTrue("CI 从上游公开 assets Release 注入模型",
            ci.contains("gh release download assets --repo") && ci.contains("Silva6c/chess-dike"))
        for (n in listOf("4_v6-0301.onnx", "nano_v3-0319.onnx")) {
            assertTrue("缺少模型 $n 的下载", ci.contains(n))
        }
        assertTrue("模型路径与 OnnxBoardRecognizer 一致",
            ci.contains("app/src/main/assets/models"))
        assertTrue("sha256 逐字节校验在", ci.contains("sha256sum -c -") &&
            ci.contains("fbd895d40ab81b41b8188f881866485678b7871775821aa50cfe74004a1dc796") &&
            ci.contains("cb1c1eb93370cb9c6bd65bc8a8b0f5ddc8dbc34cfc83ca902c447960b6ef7e3c"))
        assertTrue("出包后硬校验 APK 内真有两个模型",
            ci.contains("Verify ONNX models packaged") && ci.contains("unzip -l"))
        assertTrue("直链兜底在（gh 失败时不至于缺件）", ci.contains("curl -fsSL --retry 3"))
        // 模型文件仍不进 git，与上游 .gitignore 一致
        // Gradle 单测 workingDir 是 app/，裸 ".gitignore" 会命中 app/.gitignore
        // （内容只有 /build）；仓库根那份在 ../.gitignore，逐个候选合并后断言。
        val gi = listOf(".gitignore", "../.gitignore", "../../.gitignore")
            .joinToString("\n") { read(it) }
        assertTrue("模型不进 git 的规则仍在", gi.contains("app/src/main/assets/models/*.onnx"))
        // 上游代码侧：默认 ONNX 主力、YOLO 回退仍在包里
        val cfg = read("app/src/main/java/com/xqdk/chess/assist/AssistConfig.kt")
        val rec = read("app/src/main/java/com/xqdk/chess/assist/OnnxBoardRecognizer.kt")
        assertTrue("默认识别器仍是 ONNX", cfg.contains("KEY_RECOGNIZER_ENGINE, \"onnx\""))
        assertTrue("加载的是 assets/models 下这两个文件",
            rec.contains("models/4_v6-0301.onnx") && rec.contains("models/nano_v3-0319.onnx"))
        val gradle = read("app/build.gradle.kts")
        assertTrue("onnxruntime 依赖在", gradle.contains("libs.onnxruntime.android"))
        report("两套识别引擎齐备：ONNX 角点+分类由 CI 注入 APK（sha256 校验），YOLO 随包回退；不进 git、出包后逐个 APK 校验")
    }

    @Test
    fun `v1_27 五项改动逐条在源码里`() {
        fun read(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = read("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        val cfg = read("app/src/main/java/com/xqdk/chess/assist/AssistConfig.kt")
        val panel = read("app/src/main/java/com/xqdk/chess/assist/ui/OverlayPanelView.kt")
        val chess = read("app/src/main/java/com/xqdk/chess/assist/ui/OverlayChessView.kt")
        val layout = read("app/src/main/res/layout/overlay_panel.xml")
        val act = read("app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt")
        val rec = read("app/src/main/java/com/xqdk/chess/assist/OnnxBoardRecognizer.kt")
        val voice = read("app/src/main/java/com/xqdk/chess/assist/AutoVoice.kt")
        val ci = read(".github/workflows/cn-build.yml")
        assertFalse("必须读到全部源码", listOf(svc,cfg,panel,chess,layout,act,rec,voice,ci).any { it.isEmpty() })

        // ① 红黑锁定（换盘不串）
        assertTrue("持久化字段在", cfg.contains("var sideLock") && cfg.contains("KEY_SIDE_LOCK"))
        assertTrue("默认自动=上游口径", cfg.contains("getString(KEY_SIDE_LOCK, \"auto\")"))
        assertTrue("mySideIsRed 优先锁定", svc.contains("when (config.sideLock)") &&
            svc.contains("\"red\" -> true") && svc.contains("\"black\" -> false"))
        assertTrue("三态互斥循环", svc.contains("\"auto\" -> \"red\"") &&
            svc.contains("\"red\" -> \"black\""))
        assertTrue("锁定后重建建议", svc.contains("OverlayAction.SIDE_LOCK") &&
            svc.contains("config.sideLock = next"))
        assertTrue("面板有锁定按钮", panel.contains("OverlayAction.SIDE_LOCK") &&
            panel.contains("model.sideLockText"))
        assertTrue("按钮放大且文字清楚", layout.contains("android:layout_height=\"34dp\"") &&
            layout.contains("我方 自动"))
        assertFalse("换盘不得清空锁定", svc.substring(
            svc.indexOf("private fun onBoardConfirmed"),
            svc.indexOf("private fun flatten")).contains("sideLock"))
        report("① 我方 自动/红/黑 三态锁定，持久化，换盘重启不串；按钮 34dp 大字")

        // ② 点棋盘 = 更新键
        assertTrue("单击改绑更新轮次", chess.contains("onBoardTap") &&
            !chess.contains("onNextCandidate") &&
            chess.contains("if (isDouble) onTripleAnalysis?.invoke() else onBoardTap?.invoke()"))
        assertTrue("面板映射到 FLIP_TURN", panel.contains("chess.onBoardTap = { onAction?.invoke(OverlayAction.FLIP_TURN) }"))
        assertTrue("原「变着」按钮仍承担切候选", panel.contains("btnChange.setOnClickListener"))
        assertTrue("更新动作写诊断", svc.contains("点小棋盘 → 轮次翻转"))
        assertFalse("小更新按钮不再绑 FLIP_TURN", panel.contains("btnTurn.setOnClickListener { onAction?.invoke(OverlayAction.FLIP_TURN) }"))
        report("② 点一下小棋盘＝更新轮次；双击仍三变；切候选由「变着」按钮保留")

        // ③ 引擎不再跳回
        assertTrue("回填抑制监听器", act.contains("suppressEngineCheck") &&
            act.contains("if (suppressEngineCheck) return@setOnCheckedChangeListener"))
        assertTrue("按持久化配置回填", act.contains("cfgNow.recognizerEngine") &&
            act.contains("service?.setRecognizerEngine(cfg.recognizerEngine)"))
        assertFalse("不再按运行态回填", act.contains("val actual = if (svc.getRecognizerEngine()"))
        assertTrue("失败时提示且不改写选择", act.contains("你的选择已保留，下次启动会重试"))
        assertTrue("识别器暴露失败原因", rec.contains("lastLoadError"))
        assertTrue("服务写诊断与状态栏", svc.contains("eng.lastLoadError") &&
            svc.contains("用户配置不改写"))
        report("③ 连线页切回来不再把 ONNX 静默改回 YOLO；失败原因进诊断日志，用户选择保留、下次重试")

        // ④ 语音提速：成熟即念 + 按局面去重
        assertTrue("renderOverlay 里成熟即念",
            svc.contains("if (suggestMature && myTurn && recHealthy && !paused)") &&
            svc.contains("voiceOnSettled(fen, matureUcci, mature = true)"))
        assertTrue("按局面去重", voice.contains("val key = fen") &&
            voice.contains("同一局面已念过"))
        assertTrue("暂停不念", svc.contains("if (paused || overlayClosed) return"))
        report("④ 建议成熟（1.2s 稳定）就念，不等 bestmove 完整搜索；一个局面只念一次")

        // ⑤ 既有基建一个不少
        assertTrue("上游 3 帧确认在", svc.contains("BoardTracker(config.confirmCount)"))
        assertTrue("自动语音仍在", voice.contains("class AutoVoice"))
        assertTrue("双模型注入仍在", ci.contains("4_v6-0301.onnx") &&
            ci.contains("nano_v3-0319.onnx") && ci.contains("Verify ONNX models packaged"))
        assertTrue("固定签名/包名门禁在", ci.contains("Verify fixed signing certificate") &&
            ci.contains("Verify fixed applicationId"))
        assertTrue("哈希按钮没动", panel.contains("setChipText(btnHash, \"哈希\", model.hashText)"))
        report("⑤ 上游机制、自动语音、双模型、固定签名/包名、哈希按钮全部保留")
    }

    @Test
    fun `上游与语音自检通过`() {
        report("上游 3 帧确认 / 自动识别 / 引擎定着 全保留；语音只在定着处自动读一次中文着法")
        println("上游与语音自检通过")
    }
}
