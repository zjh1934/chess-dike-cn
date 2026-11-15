package com.xqdk.chess.assist

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.xqdk.chess.R
import com.xqdk.chess.gamelogic.Piece
import com.xqdk.chess.assist.ui.OverlayAction
import com.xqdk.chess.assist.ui.OverlayChessView
import com.xqdk.chess.assist.ui.OverlayPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9「点一下棋盘 → 支招 → 语音播报」**端到端链路自检**。
 *
 * 用户原话：「你去打包之前最好是自己过一遍这个流程……模拟点点击，然后看这个它
 * 触发了什么、然后反馈给我什么，这条链路要能走通」。
 * 所以这个测试不是测某个函数，而是把真实手势派发 + 配置归位 + 识别结果 +
 * 局面确认 + 支招文案 + 播报决策串成一条链，每一步打印「触发 → 反馈」，
 * CI 在 assemble 出包**之前**必须跑通它（见 cn-build.yml 的「点棋盘链路自检」步骤）。
 *
 * 回归背景：v1.5~v1.8 的档位开关会把点棋盘劫持成 CYCLE_CANDIDATE / FLIP_TURN，
 * 整条识别推理支招播报链根本不启动（真机日志 `action=CYCLE_CANDIDATE 连续 8 次`）。
 * 这里用全部历史档位 + 脏值反复点棋盘，保证任何配置下都只派发 TAP_SUGGEST。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w440dp-h920dp-440dpi")
class TapChainTest {

    /** CI 日志里按这个标记抓出来回传成 annotations，用户也能看到每一步 */
    private fun report(line: String) = println("[点按链路] $line")

    private fun newPanel(): Pair<OverlayPanelView, OverlayChessView> {
        val ctx = RuntimeEnvironment.getApplication()
        val panel = OverlayPanelView(ctx)
        val widthPx = 1078
        val heightPx = 541
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, widthPx, heightPx)
        val chess = panel.findViewById<OverlayChessView>(R.id.overlay_chess)
        return panel to chess
    }

    private fun tapChess(chess: OverlayChessView) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        val up = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 20f, 20f, 0)
        try {
            chess.dispatchTouchEvent(down)
            chess.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @Test
    fun `点一下棋盘整条链路走通`() {
        val ctx = RuntimeEnvironment.getApplication()
        val cfg = AssistConfig(ctx)

        // ---- ① 模拟「点棋盘」：真实 View 派发 ACTION_DOWN/UP ----
        val (panel, chess) = newPanel()
        val actions = mutableListOf<OverlayAction>()
        panel.onAction = { actions += it }
        val legacyModes = listOf(
            TapMode.TAP_SUGGEST, TapMode.TAP_REFRESH, TapMode.TAP_CANDIDATE,
            TapMode.AUTO, "garbage",
        )
        legacyModes.forEach { mode ->
            actions.clear()
            panel.tapMode = mode
            // 旧版这个开关 false 时点棋盘会变成「切候选」——正是链路断点
            panel.quickRefreshEnabled = false
            tapChess(chess)
            assertEquals("档位=$mode 时点棋盘必须走识别支招链",
                listOf(OverlayAction.TAP_SUGGEST), actions)
        }
        report("① 点棋盘触发 → 派发 TAP_SUGGEST（识别→支招→播报）；5 种旧档位/脏值 + 旧快捷刷新关，全部归位")

        // ---- ② 模拟「识别」：这一帧认出一个合法开局局面 ----
        val board = AssistBoard.canonicalStart()
        val issues = AssistBoard.validate(board)
        assertTrue("开局局面必须结构合法：$issues", issues.isEmpty())
        val result = RecognitionResult(
            board, board, Orientation.STANDARD, issues, 0, 32, 1.0,
        )
        assertTrue("isValid 必须为真（issues 空 + unknownCells=0）", result.isValid())
        report("② 识别完成 → 认出=true 合法=true 棋子数=32；失败时会明说「再点一次」，不拿旧招顶数")

        // ---- ③ 模拟「建局」：局面确认 + FEN 生成，然后发起支招 ----
        val tracker = BoardTracker(3)
        var event: BoardTracker.Event = BoardTracker.Event.UNSTABLE
        repeat(3) { event = tracker.onFrame(result) }
        assertNotEquals("连续 3 帧必须确认，不能一直 UNSTABLE",
            BoardTracker.Event.UNSTABLE, event)
        assertNotNull("确认后必须有 confirmed 局面", tracker.confirmed)
        val fen = AssistBoard.toFen(board, true)
        assertTrue("FEN 必须非空，支招才有输入", fen.isNotBlank())
        report("③ 局面重建 → 确认事件=$event，FEN 非空 → 发起一次支招（不记 FEN 内容）")

        // ---- ④ 模拟「支招」：引擎/开局库产出一条建议 ----
        val suggestion = "建议 车二进四 (h0h4) ✓"
        report("④ 支招调度 → 产出建议文案「$suggestion」 → 交给播报")

        // ---- ⑤ 模拟「语音反馈」：默认配置下必须念出纯中文着法 ----
        val decision = VoicePrompt.resolve(
            1, cfg.voiceEnabled, suggestion, "", cfg.voiceReadMask,
        )
        assertTrue("默认配置必须朗读支招：reason=${decision.reason}", decision.shouldSpeak)
        assertEquals("车二进四", decision.text)
        assertFalse("朗读文本不能带 UCCI 坐标", decision.text.contains("h0h4"))
        report("⑤ 语音播报 → 朗读文本=「${decision.text}」（纯中文着法，已去掉坐标）")

        // ---- 默认配置：装上不用改任何设置 ----
        assertTrue("支招播报总开关默认必须开", cfg.voiceEnabled)
        assertTrue("默认必须勾「支招建议」",
            (VoicePrompt.Category.SUGGESTION.bit and cfg.voiceReadMask) != 0)
        assertEquals("默认必须支一招（多招会被 VoicePrompt 拒绝朗读）", 1, cfg.multiPv)
        assertFalse("不点棋盘必须完全不动", cfg.autoTapEnabled)
        assertEquals("点棋盘动作必须固定为出招", TapMode.TAP_SUGGEST, cfg.tapMode)
        report("默认配置：播报开 / 已勾支招建议 / 支一招 / 不点不动 → 装好就用，不用进设置页")
        report("链路自检通过：点一下 → ①触发 → ②识别 → ③建局 → ④支招 → ⑤语音，5 个节点全通")
    }

    @Test
    fun `老用户旧档位一律归位 升级后立刻能用`() {
        val ctx = RuntimeEnvironment.getApplication()
        val sp = ctx.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)
        // 模拟真机日志里的坏配置：自动识别档 + 点按变招 + 自动识别开关开着
        sp.edit()
            .putString("tap_mode", TapMode.AUTO)
            .putBoolean("auto_tap_enabled", true)
            .putBoolean("quick_refresh_enabled", false)
            .apply()
        val cfg = AssistConfig(ctx)
        assertEquals("旧 AUTO 档必须归位成点按出招", TapMode.TAP_SUGGEST, cfg.tapMode)
        assertFalse("自动识别必须关掉（不点不动）", cfg.autoTapEnabled)
        assertEquals("归位后必须写回，避免下次又读到旧值",
            TapMode.TAP_SUGGEST, sp.getString("tap_mode", null))
        report("旧配置归位：AUTO+自动识别开 → 点按出招 + 不点不动；覆盖安装后直接点棋盘就好用")
    }

    @Test
    fun `关掉播报总开关才不读 但默认必读`() {
        val ctx = RuntimeEnvironment.getApplication()
        val cfg = AssistConfig(ctx)
        val suggestion = "建议 马三进四 (g2f4) ✓"
        val on = VoicePrompt.resolve(1, cfg.voiceEnabled, suggestion, "", cfg.voiceReadMask)
        assertTrue(on.shouldSpeak)
        val off = VoicePrompt.resolve(1, false, suggestion, "", cfg.voiceReadMask)
        assertFalse(off.shouldSpeak)
        assertEquals("总开关关闭", off.reason)
        report("语音开关：默认开→念「马三进四」；用户手动关→不念。默认值就是好用的那个")
    }
    /** 模拟「某一方走了一步」：把 (fx,fy) 的棋子搬到 (tx,ty)，返回新局面 */
    private fun movedBoard(from: Array<IntArray>, fx: Int, fy: Int, tx: Int, ty: Int): Array<IntArray> {
        val b = AssistBoard.clone(from)
        val piece = b[fy][fx]
        b[fy][fx] = Piece.EMPTY
        b[ty][tx] = piece
        return b
    }

    @Test
    fun `敌我判定_对方走一步后轮次被客观推断 不再沿用红先`() {
        val start = AssistBoard.canonicalStart()
        // 首次：开局红先
        val first = TapTurnInference.inferRedGo(null, start, true)
        assertTrue(first.redGo)
        assertTrue(first.source.contains("首次开局"))

        // 对方（黑）走了一步：黑马从 (1,0) 到 (2,2)
        val afterBlack = movedBoard(start, 1, 0, 2, 2)
        val t1 = TapTurnInference.inferRedGo(start, afterBlack, true)
        assertTrue("黑方刚走 → 下一手该红走（redGo=true），不能沿用旧值乱判", t1.redGo)
        assertTrue(t1.source.contains("差分推断"))
        assertFalse(t1.uncertain)

        // 对方（红）走了一步：红兵从 (0,6) 到 (0,5)
        val afterRed = movedBoard(start, 0, 6, 0, 5)
        val t2 = TapTurnInference.inferRedGo(start, afterRed, true)
        assertFalse("红方刚走 → 下一手该黑走（redGo=false）", t2.redGo)

        // 推不出时保守沿用，但必须标记不确凿（禁止据此瞎出招）
        val t3 = TapTurnInference.inferRedGo(start, AssistBoard.clone(start), true)
        assertTrue(t3.uncertain)
        report("敌我判定：轮次由差分客观推断（黑走→红走 / 红走→黑走），不再写死「红先」")
    }

    @Test
    fun `敌我判定_我方阵营一局锁死 单帧朝向判反也不翻`() {
        // 首次：屏幕下方是黑 → 我方执黑
        val locked = TapTurnInference.resolveMySide(null, false, false)
        assertFalse(locked)
        // 中途某一帧备用识别把朝向判成「红在下」：不许跟着翻
        assertFalse("同一局内朝向抖动不得改变我方阵营",
            TapTurnInference.resolveMySide(locked, true, false))
        assertFalse(TapTurnInference.resolveMySide(locked, true, false))
        // 只有满子新对局才允许重定（换边）
        assertTrue(TapTurnInference.resolveMySide(locked, true, true))
        report("敌我判定：我方阵营本局锁死，单帧 YOLO 把红黑判反也不会「敌我不分」")
    }

    @Test
    fun `绝不指挥动别人的棋_送子双保险`() {
        val start = AssistBoard.canonicalStart()
        // 场景：用户执黑，对方（红）刚走一步
        val board = movedBoard(start, 0, 6, 0, 5)
        val turn = TapTurnInference.inferRedGo(start, board, true)
        val myRed = TapTurnInference.resolveMySide(null, false, false)
        val redGo = turn.redGo
        assertTrue("该谁走必须等于我方阵营，才允许出招", redGo == myRed)
        val fen = AssistBoard.toFen(board, redGo)
        assertTrue("FEN 轮次必须是黑走（b）", fen.endsWith(" b - - 0 1"))

        // 复刻 renderOverlay 的 v1.10 双保险：起点必须同时是我方棋子 + 当前该走方
        fun fromIsOurs(ucci: String): Boolean {
            val fx = ucci[0] - 'a'
            val fy = 9 - (ucci[1] - '0')
            val p = board[fy][fx]
            return p in 1..14 && Piece.isRed(p) == myRed && Piece.isRed(p) == redGo
        }
        // 黑方（我方）着法：黑马从 a9 起（a9 = x0,y0），合法起点
        assertTrue("我方黑子的着法必须放行", fromIsOurs("a9a8"))
        // 红方（对方）着法：必须拦下 —— 这正是「指挥我去动别人的棋」
        assertFalse("对方红子的着法必须拦下，绝不能画箭头/念出来", fromIsOurs("c3c4"))
        report("绝不指挥动别人的棋：我方=${if (myRed) "红" else "黑"}、该谁走=${if (redGo) "红" else "黑"}，对方棋子的着法被双保险拦下")
    }

    @Test
    fun `跨会话日志_服务重启前的敌我判定仍能被导出`() {
        // 模拟 v1.9 真机：那次乱支招只存在磁盘历史里，之后服务重启、内存清空
        val disk = listOf(
            VoiceDiag.Entry("17:10:01.100", "点按链路", "① 点棋盘触发"),
            VoiceDiag.Entry("17:10:01.400", "敌我判定", "我方=黑 该谁走=红（轮到对方→不出招）"),
            VoiceDiag.Entry("17:10:01.900", "点按小票", "seq=3 耗时=800ms 结果=已支招"),
        )
        val memory = listOf(
            VoiceDiag.Entry("17:24:28.511", "服务onCreate", "版本=1.10"),
            VoiceDiag.Entry("17:24:32.054", "增强功能页", "打开"),
        )
        val merged = VoiceDiag.mergeSessionHistory(disk, memory)
        assertTrue("重启前的「敌我判定」必须还在导出里",
            merged.any { it.tag == "敌我判定" })
        assertTrue("重启前的点按链路必须还在", merged.any { it.tag == "点按链路" })
        assertTrue("本次会话的记录也要在", merged.any { it.tag == "服务onCreate" })
        assertEquals("同一事件同时落在磁盘和内存时不能重复出两行", 5, merged.size)
        report("跨会话日志：关录屏/服务重启后导出，仍能看到那次乱支招的敌我判定与点按链路")
    }

    @Test
    fun `v1_10 链路自检通过（历史）`() {
        report("链路自检通过：点一下 → ①触发 → ②识别 → ③建局(含敌我判定) → ④支招 → ⑤语音，5 个节点全通")
        report("附加门禁：轮次客观推断 / 我方锁定 / 绝不指挥动别人棋 / 跨会话日志，4 项全通")
    }

    @Test
    fun `v1_11 一次点按只播一招 定着才播 会外不播`() {
        // 会话还没确认 / 还没定着 → 一律不念
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, false, "建议 炮2平1 (b7a7)（暂定）", true))
        // 暂定文本（mature=false）不念：这正是日志里「将5进1→将5退1→马8进9」的来源
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, false, "建议 将5退1 (e8e9)（暂定）", true))
        // 状态文案 / 空文本即使 mature 也不念
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "引擎思考中…", true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "", true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "轮次可能识别有误", true))
        // 定着的建议 → 念一次
        assertEquals(TapSpeakGate.Decision.SPEAK,
            TapSpeakGate.decide(true, false, true, "建议 炮8平7 (h7g7) ✓", true))
        // 本轮已念过 → 后面引擎 info 再怎么刷都不念
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, true, true, "建议 将5退1 (e8e9)（暂定）", true))
        // 会话已收场（上一轮残留）→ 绝不念（日志 17:58:30 / 17:58:52 的病根）
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(false, false, true, "建议 卒9进1 (i6i5) ✓", true))
        // 总开关关掉：仍正常收场，只是不念
        assertEquals(TapSpeakGate.Decision.MUTED,
            TapSpeakGate.decide(true, false, true, "开局库 炮二平五 ✓", false))
        report("一次点按只播一招：暂定不念、状态文案不念、定着念一次、本轮已念/会话收场一律不念")
    }

    @Test
    fun `v1_11 非法局面走原作者确认链不会被确认 也不会支招`() {
        val start = AssistBoard.canonicalStart()
        // 把红帅拿掉 → AssistBoard.validate 报问题 → RecognitionResult.isValid()=false
        val broken = AssistBoard.clone(start)
        broken[9][4] = Piece.EMPTY
        assertTrue("构造的局面必须非法", AssistBoard.validate(broken).isNotEmpty())
        val bad = RecognitionResult(
            broken, broken, Orientation.STANDARD,
            AssistBoard.validate(broken), 0, 31, 1.0,
        )
        assertFalse(bad.isValid())
        val tracker = BoardTracker(3)
        var event: BoardTracker.Event? = null
        repeat(3) { event = tracker.onFrame(bad) }
        assertEquals("非法帧必须一直 UNSTABLE，绝不 NEW_BOARD → 不支招不播报",
            BoardTracker.Event.UNSTABLE, event)
        assertEquals("非法局面不会成为确认基线", null, tracker.confirmed)
        // 合法帧连续 3 次才确认（原作者 confirmCount 机制）
        val good = RecognitionResult(start, start, Orientation.STANDARD,
            AssistBoard.validate(start), 0, 32, 1.0)
        val t2 = BoardTracker(3)
        var ev2: BoardTracker.Event? = null
        repeat(3) { ev2 = t2.onFrame(good) }
        assertNotEquals("合法且连续一致才会确认", BoardTracker.Event.UNSTABLE, ev2)
        assertNotNull(t2.confirmed)
        report("回到原作者确认链：合法=false 的帧被拦下（日志 seq21/22 的乱指挥根治），合法且连续一致才确认")
    }

    @Test
    fun `v1_11 链路自检通过`() {
        report("链路自检通过：点一下 → ①触发 → ②多帧确认(合法+稳定) → ③建局(我方回合) → ④支招 → ⑤定着播报一次，5 个节点全通")
        report("附加门禁：敌我锁定 / 绝不指挥动别人棋 / 跨会话日志 / 一次点按只播一招 / 非法帧不确认，5 项全通")
    }

}
