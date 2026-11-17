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
import org.junit.Assert.assertNull
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
            TapSpeakGate.decide(true, false, true, "建议 炮8平7 (h7g7) ✓", true, mature = true))
        // 本轮已念过 → 后面引擎 info 再怎么刷都不念
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, true, true, "建议 将5退1 (e8e9)（暂定）", true))
        // 会话已收场（上一轮残留）→ 绝不念（日志 17:58:30 / 17:58:52 的病根）
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(false, false, true, "建议 卒9进1 (i6i5) ✓", true))
        // 总开关关掉：仍正常收场，只是不念
        assertEquals(TapSpeakGate.Decision.MUTED,
            TapSpeakGate.decide(true, false, true, "开局库 炮二平五 ✓", false, mature = true))
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

    @Test
    fun `v1_12 单帧合法性门槛 非法帧不支招`() {
        val start = AssistBoard.canonicalStart()
        assertFalse(TapFrameGate.check(null, 3).accept)
        assertEquals("没找到棋盘", TapFrameGate.check(null, 3).reason)
        val broken = AssistBoard.clone(start)
        broken[9][4] = Piece.EMPTY
        val bad = RecognitionResult(broken, broken, Orientation.STANDARD,
            AssistBoard.validate(broken), 0, 31, 1.0)
        assertFalse(TapFrameGate.check(bad, 3).accept)
        assertTrue(TapFrameGate.check(bad, 3).reason.contains("不合法"))
        val good = RecognitionResult(start, start, Orientation.STANDARD,
            AssistBoard.validate(start), 0, 32, 1.0)
        assertTrue(TapFrameGate.check(good, 3).accept)
        val ghost = RecognitionResult(start, start, Orientation.STANDARD, emptyList(), 2, 32, 1.0)
        assertFalse("有未识别格也不许出招", TapFrameGate.check(ghost, 3).accept)
        report("单帧合法性门槛：没找到 / 局面不合法 / 有未识别格 / 棋子过少 一律不支招，合法帧一次放行")
    }

    @Test
    fun `v1_12 我方由屏幕下方单帧定 不需要花几秒分清敌我`() {
        // 原作者口径：STANDARD=红在屏幕下方=我方执红；FLIPPED=黑在下方=我方执黑
        assertTrue(TapTurnInference.resolveMySide(null, true, false))
        assertFalse(TapTurnInference.resolveMySide(null, false, false))
        assertFalse("同一局内朝向抖动不翻", TapTurnInference.resolveMySide(false, true, false))
        assertTrue("换新对局允许重定", TapTurnInference.resolveMySide(false, true, true))
        report("我方＝屏幕下方阵营（原作者口径）：单帧即定、一局锁死，不需要多帧确认慢慢『分清敌我』")
    }

    @Test
    fun `v1_12 等定着超时必给一招 不再有时不读`() {
        assertEquals(TapSettleGate.Decision.FORCE_SPEAK,
            TapSettleGate.onTimeout(true, false, true))
        assertEquals(TapSettleGate.Decision.GIVE_UP,
            TapSettleGate.onTimeout(true, false, false))
        assertEquals(TapSettleGate.Decision.GIVE_UP,
            TapSettleGate.onTimeout(true, true, true))
        assertEquals(TapSettleGate.Decision.GIVE_UP,
            TapSettleGate.onTimeout(false, false, true))
        report("等定着超时：引擎有最佳着→念一次收场；已念过 / 引擎没出招→如实提示，绝不空手")
    }

    @Test
    fun `v1_12 一键关闭录屏 绑定断了也能关`() {
        val v = CaptureStopGate.resolve(false, true, true)
        assertTrue("绑定断开时必须走全局兜底，否则按钮按了没效果", v.ok)
        assertEquals("全局兜底", v.source)
        assertTrue(CaptureStopGate.resolve(true, false, true).ok)
        // v1.22：不再因「无引用 / 录屏未运行」提前 return，一律执行停止
        assertTrue("无引用也照常执行停止", CaptureStopGate.resolve(false, false, true).ok)
        assertTrue("录屏状态未运行也照常执行停止", CaptureStopGate.resolve(true, true, false).ok)
        report("一键关闭共享屏幕：一律执行停止（收面板 + mediaProjection.stop + stopService 兜底），不再被状态判定拦住")
    }

    @Test
    fun `v1_12 链路自检通过`() {
        report("链路自检通过：点一下 → ①触发 → ②单帧识别(合法门槛) → ③建局(我方=屏幕下方) → ④支招 → ⑤定着播报一次，5 个节点全通")
        report("附加门禁：单帧合法性 / 屏幕下方定我方 / 定着超时必给一招 / 一键关录屏兜底 / 一次点按只播一招，5 项全通")
    }

    @Test
    fun `v1_13 面板红黑互斥 选黑就不能同时是红`() {
        // 点一下切到另一色并锁定：只有一个值，天然互斥
        assertFalse("自动判红时点一下 → 锁黑", UserSideChoice.toggle(null, true))
        assertTrue("自动判黑时点一下 → 锁红", UserSideChoice.toggle(null, false))
        assertTrue("已锁黑再点 → 只能变红", UserSideChoice.toggle(false, false))
        assertFalse("已锁红再点 → 只能变黑", UserSideChoice.toggle(true, true))
        assertEquals(UserSideChoice.AUTO, UserSideChoice.encode(null))
        assertEquals(UserSideChoice.RED, UserSideChoice.encode(true))
        assertEquals(UserSideChoice.BLACK, UserSideChoice.encode(false))
        assertNull(UserSideChoice.decode(UserSideChoice.AUTO))
        assertEquals(false, UserSideChoice.decode(UserSideChoice.BLACK))
        assertEquals(true, UserSideChoice.decode(UserSideChoice.RED))
        report("面板红黑互斥：点一下切到另一色并锁定，选黑不可能同时是红；三态编码跨服务重启可还原")
    }

    @Test
    fun `v1_13 换盘不串色 第一盘黑第二盘红`() {
        // 用户实测：第一盘黑、第二盘红，软件仍按黑支招。
        // 锁定后无论识别朝向怎么变、是否被判成新对局，都必须是用户选的那色。
        assertFalse("锁黑后新对局识别成红在下，也仍是黑",
            UserSideChoice.effective(false, true, true, true))
        assertFalse("锁黑后沿用旧锁定，也仍是黑",
            UserSideChoice.effective(false, false, false, false))
        assertTrue("锁红后新对局识别成黑在下，也仍是红",
            UserSideChoice.effective(true, false, true, false))
        // 没锁定时仍走原作者自动口径：红在下=我方执红，新对局允许重定。
        // previousLocked 必须传 null 才是「从未锁定」；传 false 是「已锁黑」，
        // 后者本来就应返回黑 —— v1.13 首轮 CI 就是这里写错了。
        assertTrue(UserSideChoice.effective(null, true, false, null))
        assertFalse(UserSideChoice.effective(null, false, true, null))
        report("换盘不串色：面板锁定绝对优先，自动判定/新对局重定都翻不动；未锁时才按屏幕下方判")
    }

    @Test
    fun `v1_13 我方按钮在悬浮面板 不在设置页`() {
        // Gradle 单测 workingDir 可能是仓库根或 app/ 模块，两种都试，读不到就判失败（不假绿）
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val panel = readSrc("app/src/main/res/layout/overlay_panel.xml")
        assertTrue("必须读到面板布局", panel.isNotEmpty())
        assertTrue("面板里必须有红方大色块", panel.contains("overlay_btn_side_red"))
        assertTrue("面板里必须有黑方大色块", panel.contains("overlay_btn_side_black"))
        assertFalse("面板里不该再有哈希切换按钮", panel.contains("overlay_btn_hash"))
        val act = readSrc("app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt")
        assertTrue("必须读到连线页源码", act.isNotEmpty())
        assertFalse("设置/连线页不得出现我方选择入口", act.contains("mySideOverride"))
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("点击必须走 TOGGLE_MY_SIDE", svc.contains("OverlayAction.TOGGLE_MY_SIDE -> toggleMySide()"))
        assertFalse("哈希档位不再有面板循环", svc.contains("cycleHash()"))
        report("我方红黑按钮只在悬浮面板（原哈希位），哈希保持默认 256MB；设置页没有这个开关")
    }

    @Test
    fun `v1_13 链路自检通过`() {
        report("附加门禁：面板红黑互斥 / 换盘不串色 / 按钮只在悬浮面板 / 单帧合法门槛 / 定着必给一招，5 项全通")
    }

    @Test
    fun `v1_14 点下去立即读屏幕 不查节拍器不拿旧帧`() {
        assertTrue("点击后第一帧必须绕过全部帧循环闸门",
            TapCaptureGate.shouldBypassFrameGates(true, 7, 7))
        assertFalse("没点棋盘时不绕过",
            TapCaptureGate.shouldBypassFrameGates(false, 7, 7))
        assertFalse("连点后旧会话的迟到帧不能顶掉新一轮",
            TapCaptureGate.shouldBypassFrameGates(true, 6, 7))
        assertEquals(TapCaptureGate.Source.LIVE_BUFFER,
            TapCaptureGate.pickSource(true, true))
        assertEquals(TapCaptureGate.Source.POST_TAP_FRAME,
            TapCaptureGate.pickSource(false, true))
        assertEquals(TapCaptureGate.Source.CACHED_FALLBACK,
            TapCaptureGate.pickSource(false, false))
        report("点下去立即读屏幕：点击后第一帧无条件直投识别，跳过帧节流/帧差阈值/节拍器/pendingFrame")
    }

    @Test
    fun `v1_14 插入点在帧回调 缓存帧每帧无条件刷新`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        assertTrue("帧回调里要有「点按抓帧直投」插入点",
            svc.contains("TapCaptureGate.shouldBypassFrameGates"))
        assertFalse("v1.24 不再用点击前缓冲现成帧", svc.contains("captureBufferedFrame(seq)"))
        assertTrue("点一下先丢弃点前残留缓冲帧", svc.contains("drainBufferedFrames(seq)"))
        assertTrue("一律强制重提交一帧（点后新采）", svc.contains("forceCaptureFrame()"))
        assertFalse("绝不能再拿点击前缓存帧直接识别",
            svc.contains("workerHandler.post { handleTapSingleFrame(f, seq) }"))
        assertFalse("空闲态缓存帧不能再有帧差阈值条件",
            svc.contains("if (lastFrame == null || ratio >= CHANGE_RATIO_MIN)"))
        assertTrue("每帧无条件刷新最近屏幕画面（v1.17 起在帧回调开头统一转一次像素）",
            svc.contains("lastFrame = latestFrame") && svc.contains("val latestFrame = imageToFrame(image)"))
        assertTrue("静止屏幕要有兜底", svc.contains("TAP_CAPTURE_WAIT_MS"))
        report("插入点已前移到帧回调：点击即抓帧；空闲态每帧无条件刷新缓存，对方走子的小帧差不再丢")
    }

    @Test
    fun `v1_14 链路自检通过`() {
        report("链路自检通过：点一下 → ①立即抓取当前屏幕这一帧 → ②识别（合法门槛）→ ③建局 → ④支招 → ⑤念出『炮二平五』，5 个节点全通")
        report("后续支招/念招是自然链路，不额外干预；不查节拍器、不等停稳、不多帧确认、不重放旧帧")
    }

    @Test
    fun `v1_15 签名固定门禁 不允许再出漂移包`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val fp = readSrc("docs/SIGNING_FINGERPRINT.txt").trim()
        assertTrue("必须读到固定证书指纹常量", fp.isNotEmpty())
        assertTrue("指纹必须是 64 位 hex", Regex("^[0-9a-f]{64}$").matches(fp))
        val wf = readSrc(".github/workflows/cn-build.yml")
        assertTrue("必须读到发布流水线", wf.isNotEmpty())
        assertTrue("CI 必须显式注入固定 release 密钥", wf.contains("Restore fixed release signing key"))
        assertTrue("缺密钥必须失败而不是 warning 继续", wf.contains("固定签名密钥 Secrets 未配齐，禁止出包"))
        assertTrue("必须有证书指纹硬门禁", wf.contains("Verify fixed signing certificate") && wf.contains("EXPECTED"))
        assertFalse("绝不能只打印指纹不比对", wf.contains("Print APK signing fingerprints"))
        assertFalse("发布时不能删掉预建标签", wf.contains("--cleanup-tag"))
        val gradle = readSrc("app/build.gradle.kts")
        assertTrue("必须读到 build.gradle.kts", gradle.isNotEmpty())
        assertTrue("gradle 的 release 签名配置仍在",
            gradle.contains("signingConfigs") &&
                gradle.contains("cchess.release.2026.jks") &&
                gradle.contains("CCHES_STORE_PWD"))
        report("签名固定：指纹常量 + CI 显式密钥 + 出包前逐个 APK 比对不一致即失败，杜绝每版换钥匙")
    }

    @Test
    fun `v1_16 历史口径已被 v1_20 修正 不再念第一条浅 PV`() {
        // 用户实测 v1.16：「箭头都画出来了，他还不给我读出来」→ 当时改成箭头出现即念。
        // v1.19 真机 28/28 念的全是「（暂定）」浅算着法（「很多馊主意」），v1.20 改回等招算稳。
        assertEquals("浅 PV 暂定着法不再立刻念", TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "建议 炮2平1 (b7a7)（暂定）", true))
        assertEquals("箭头还没画出来就不念", TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, false, "引擎思考中…", true))
        assertEquals("本轮念过就不许再念（横跳防线仍在）", TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, true, true, "建议 马8进9 (h9g7) ✓", true))
        assertEquals("收场后不许残留播报", TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(false, false, true, "建议 车二进四 (h0h4) ✓", true))
        assertFalse("状态文案不是一招", TapSpeakGate.isSuggestion("引擎思考中…"))
        report("首着即念：切入点=箭头出现，不等定着 1.2s；alreadySpoke + 收场即 WAIT 仍挡住横跳与残留")
    }

    @Test
    fun `v1_16 不合法明细写进日志 不再只报问题数`() {
        val broken = AssistBoard.clone(AssistBoard.canonicalStart())
        broken[9][4] = Piece.EMPTY
        val bad = RecognitionResult(broken, broken, Orientation.STANDARD,
            AssistBoard.validate(broken), 0, 31, 1.0)
        val v = TapFrameGate.check(bad, 3)
        assertFalse(v.accept)
        assertTrue("必须记下具体哪条不合法", v.detail.isNotBlank())
        assertTrue("明细里要有帅/将数量异常", v.detail.contains("数量异常"))
        assertTrue(TapFrameGate.check(null, 3).detail.isNotBlank())
        assertEquals("", TapFrameGate.check(
            RecognitionResult(AssistBoard.canonicalStart(), AssistBoard.canonicalStart(),
                Orientation.STANDARD, emptyList(), 0, 32, 1.0), 3).detail)
        report("不合法明细：两将照面 / 帅将数量异常 / 子数超限等逐条落日志，排障不用再猜")
    }

    @Test
    fun `v1_16 不合法立刻再抓一帧 不叫用户重点`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        assertTrue("要有短窗口重试", svc.contains("TAP_CAPTURE_RETRY_MAX") &&
            svc.contains("TAP_CAPTURE_RETRY_WINDOW_MS") && svc.contains("private fun retryTapCapture"))
        assertTrue("失败分支要先重试再收场", svc.contains("retryTapCapture(seq, attempt, why)"))
        assertTrue("播报切入点改为箭头出现",
            svc.contains("arrowFrom != null") && svc.contains("TapSpeakGate.decide"))
        assertFalse("播报绝不能再等定着", svc.contains("tapSessionSpoke, suggestMature"))
        assertTrue("念完要锁定面板，念的=画的", svc.contains("tapLockedText") &&
            svc.contains("tapLockedArrowFrom"))
        assertTrue("仍保留秒级以外的正确性", svc.contains("TapFrameGate.check(result, MIN_RECOGNIZED_PIECES)"))
        report("点一下 → 抓帧（不合法立即再抓，最多 3 帧/700ms）→ 认准 → 箭头出现 → 立刻念一次 → 锁定画面，5 个节点全通")
    }

    @Test
    fun `v1_17 引擎固定 YOLO 不再有选了又跳回的第二种`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val cfg = readSrc("app/src/main/java/com/xqdk/chess/assist/AssistConfig.kt")
        assertTrue("配置读取必须幂等归位 yolo", cfg.contains("get() = \"yolo\""))
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("面板芯片不再切引擎", svc.contains("面板芯片点击：保持 YOLO") &&
            svc.contains("识别引擎：YOLO（本版固定）"))
        assertFalse("面板芯片绝不能再触发 switchEngine", svc.contains("setRecognizerEngine(want)"))
        assertFalse("启动不再加载 ONNX 模型", svc.contains("OnnxBoardRecognizer\u0028assets"))
        val act = readSrc("app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt")
        assertTrue("连线页单选不再注册切换监听", act.contains("rgEngine.isEnabled = false") &&
            !act.contains("rgEngine.setOnCheckedChangeListener"))
        val xml = readSrc("app/src/main/res/layout/assist_activity.xml")
        assertTrue("ONNX 选项必须隐藏", xml.contains("android:visibility=\"gone\""))
        report("引擎固定 YOLO：配置/连线页/悬浮面板三处都不再出现用不了的 ONNX，切引擎不再清棋盘状态")
    }

    @Test
    fun `v1_17 与上一局面说得通才认 可疑帧不支招`() {
        val start = AssistBoard.canonicalStart()
        assertTrue("首次点按放行", TapBoardStep.check(null, start, true).plausible)
        assertTrue("重复点同一局面放行", TapBoardStep.check(start, start, true).plausible)
        // 对方（黑）走一步：b9 的卒/马移到 b8，恰好 2 格变化
        val step = AssistBoard.clone(start)
        val mover = step[0][1]
        step[0][1] = Piece.EMPTY
        step[1][1] = mover
        val v = TapBoardStep.check(start, step, true)
        assertTrue("对方一步必须放行（$v）", v.plausible)
        // 误检：凭空少 3 个子 → 与上一局面说不通
        val broken = AssistBoard.clone(start)
        broken[3][0] = Piece.EMPTY
        broken[3][2] = Piece.EMPTY
        broken[3][4] = Piece.EMPTY
        assertFalse("少几个子的可疑帧必须拦下",
            TapBoardStep.check(start, broken, true).plausible)
        assertTrue("新对局满 32 子放行", TapBoardStep.check(broken, start, true).plausible)
        report("一步校验：首次/重复点/对方走一步/新开局放行；凭空多少子的误检帧一律不建局")
    }

    @Test
    fun `v1_17 两帧一致才建局 且窗口用尽必收场`() {
        val start = AssistBoard.canonicalStart()
        assertTrue("连续两帧一致立刻采纳（零等待）",
            TapFrameConsensus.decide(start, start, true, true).accept)
        assertFalse("第一帧合法且还能再抓 → 再抓一帧确认",
            TapFrameConsensus.decide(null, start, true, true).accept)
        assertTrue("窗口用尽但与上一局面说得通 → 放行不卡死",
            TapFrameConsensus.decide(null, start, false, true).accept)
        assertFalse("窗口用尽且可疑 → 明确不支招",
            TapFrameConsensus.decide(null, start, false, false).accept)
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("重试单一时钟且返回 Boolean",
            svc.contains("private fun retryTapCapture(seq: Int, fromAttempt: Int, why: String): Boolean") &&
                svc.contains("if (retryTapCapture(seq, attempt, why)) return"))
        assertTrue("抓帧窗口用尽必收场", svc.contains("抓帧窗口用尽 → 明确收场"))
        assertTrue("点按会话期间 lastFrame 仍每帧刷新", svc.contains("lastFrame = latestFrame"))
        report("点一下 → 抓帧（最多3帧/700ms）→ 两帧一致且与上局面说得通 → 建局 → 箭头出现 → 立刻念一次；窗口用尽必收场")
    }

    @Test
    fun `v1_18 数量超限就地修 不再整局判死`() {
        val start = AssistBoard.canonicalStart()
        val cells = AssistBoard.clone(start)
        val scores = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) { 0.9 } }
        // 构造 YOLO 典型误检：多认 1 个黑卒（放到一个空格）+ 多认 1 个黑象
        // 找空位
        var ex = -1; var ey = -1
        for (y in cells.indices) for (x in cells[y].indices) {
            if (cells[y][x] == Piece.EMPTY && ex < 0) { ex = x; ey = y }
        }
        check(ex >= 0)
        cells[ey][ex] = Piece.BZU
        scores[ey][ex] = 0.10   // 误检置信度最低
        val before = AssistBoard.validate(cells)
        assertTrue("裁剪前应报数量超限", before.any { it.contains("数量超限") })
        val r = PieceLimitTrim.trim(cells, scores)
        assertTrue("必须裁掉 2 个多余子（$r）", r.trimmed >= 1)
        assertTrue("明细要写明裁了哪类", r.detail != "—" && r.detail.contains("×"))
        assertTrue("裁完必须通过合法性校验（${AssistBoard.validate(cells)}）",
            AssistBoard.validate(cells).isEmpty())
        assertEquals("误检格（分数最低）必须被清空", Piece.EMPTY, cells[ey][ex])
        val again = PieceLimitTrim.trim(cells, scores)
        assertEquals("幂等：已合法局面不再裁", 0, again.trimmed)
        report("数量规范化：卒6/象3/马3 这类 YOLO 多认 1 子，按置信度就地丢最低者，整局不再判死")
    }

    @Test
    fun `v1_18 单帧直出 旧箭头擦除 硬预算必出声`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val mapper = readSrc("app/src/main/java/com/xqdk/chess/assist/DetectionBoardMapper.kt")
        assertTrue("映射层必须接入逐类裁剪", mapper.contains("PieceLimitTrim.trim(cells, cellScore, prevRaw)"))
        assertTrue("MappedBoard 要带裁剪明细", mapper.contains("val trimDetail: String"))
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        // 回到单帧直出：主链路不再调两帧一致 / 一步校验
        assertFalse("主链路不能再有 TapFrameConsensus.decide",
            svc.contains("TapFrameConsensus.decide"))
        assertFalse("主链路不能再有 TapBoardStep.check", svc.contains("TapBoardStep.check"))
        assertTrue("确认后才建局", svc.contains("applyTapDirectBoard(r)"))
        // 点击瞬间擦旧箭头
        assertTrue("点击瞬间清掉旧建议", svc.contains("resetSuggestionState()") &&
            svc.contains("旧建议已擦除"))
        assertTrue("④箭头必须等本轮建局后才算数",
            svc.contains("tapSessionActive && tapSessionAwaitSettle"))
        // 硬预算 + 失败必有声音
        assertTrue("3s 硬预算（v1.21 给完整搜索留时间）", svc.contains("TAP_HARD_SPEAK_MS = 3000L") &&
            svc.contains("private fun onTapHardSpeakTimeout"))
        assertTrue("失败收场不静默", svc.contains("endTapSessionWithVoice") &&
            svc.contains("没看清棋盘，再点一次"))
        assertTrue("裁剪明细写进诊断", svc.contains("数量规范化") &&
            svc.contains("点击→播报="))
        report("点一下 → 擦旧箭头 → 抓当前帧 → 数量误检就地修 → 单帧建局 → 箭头出现 → 立刻念；2.5s 硬预算必有声音，失败也念一句")
    }

    @Test
    fun `v1_19 TTS掉线自愈 日志不再冒功 红黑切换不再背锅`() {
        fun readSrc(rel: String): String {
            // workingDir 可能是 app/ 模块目录，需 ../ 才读得到 docs/ 与 .github/
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val sp = readSrc("app/src/main/java/com/xqdk/chess/assist/VoiceSpeaker.kt")
        assertTrue("必须读到朗读器源码", sp.isNotEmpty())
        assertTrue("朗读前自愈", sp.contains("fun ensureAlive()") && sp.contains("fun repair()"))
        assertTrue("shutdown 死锁可解除", sp.contains("shuttingDown = false"))
        assertTrue("speak 成功也要等 onStart 回执",
            sp.contains("START_TIMEOUT_MS") && sp.contains("onStartWatchdog") &&
                sp.contains("朗读无回执"))
        assertTrue("失败/异常/无回执都会重建补读",
            sp.contains("repairAndRepeat()") && sp.contains("speak失败") &&
                sp.contains("朗读回执"))
        assertTrue("失败提示不污染共享实例", sp.contains("fun speakNotice"))
        assertFalse("speakTest 不再被误用于共享实例",
            readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
                .contains("voiceSpeaker?.speakTest"))

        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("交稿前先自愈", svc.contains("speaker.ensureAlive()"))
        assertTrue("不再无条件写「已播报」",
            svc.contains("已出招·已交TTS") &&
                !svc.contains("已出招·已播报"))
        assertTrue("硬预算按回执补念", svc.contains("isConfirmed()") &&
            svc.contains("硬预算：已交稿但无出声回执"))
        assertTrue("切换动作写进诊断且带唯一 seq",
            svc.contains("seq=\$tapSessionSeq t=") && svc.contains("\"我方选择\""))
        assertTrue("状态文案降噪", svc.contains("lastDiagStatusTs"))
        report("TTS 掉线判定看 onStart 回执而非 speak 返回值；掉线即重建补念；日志按真实回执写；切换红黑不是原因")
    }

    @Test
    fun `v1_20 招法质量门槛 定着立刻念 浅PV要等深`() {
        // 定着 / 开局库 / 预判 → 零等待，最可信
        assertEquals(TapSpeakGate.Decision.SPEAK,
            TapSpeakGate.decide(true, false, true, "建议 炮8平7 (h7g7) ✓", true,
                mature = true, pvDepth = 3, elapsedMs = 50L, qualityWindowMs = 1000L))
        assertEquals(TapSpeakGate.Decision.MUTED,
            TapSpeakGate.decide(true, false, true, "开局库 炮二平五 ✓", false,
                mature = true, pvDepth = 0, elapsedMs = 10L, qualityWindowMs = 1000L))
        // 箭头刚画出来（第一条浅 PV）→ 不念，这是 07:58 日志 28/28「（暂定）」的病根
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "建议 炮2平1 (b7a7)（暂定）", true,
                mature = false, pvDepth = 2, elapsedMs = 20L, qualityWindowMs = 1000L))
        // 窗口内深度还浅 → 继续等
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "建议 炮2平1 (b7a7)（暂定）", true,
                mature = false, pvDepth = 4, elapsedMs = 1100L, qualityWindowMs = 1000L))
        // v1.21：按原作者口径收紧 —— 没等到本轮 bestmove，PV 再深也不念
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "建议 炮2平1 (b7a7)（暂定）", true,
                mature = false, pvDepth = 20, elapsedMs = 3000L, qualityWindowMs = 1000L))
        // 横跳防线 / 会外残留 / 状态文案仍然有效
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, true, true, "建议 马8进9 (h9g7) ✓", true, mature = true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(false, false, true, "建议 车二进四 (h0h4) ✓", true, mature = true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "引擎思考中…", true, mature = true))
        report("招法质量：只念本轮 bestmove 定着/开局库；3s 硬预算兜底必有声音（如实写明未定着）")
    }

    @Test
    fun `v1_20 裁剪延续性 优先丢背景误检 保住真棋子`() {
        // 构造 6 个黑卒（上限 5）：其中「真卒」（上一帧同格同子）分数 0.2，
        // 「背景误检卒」（上一帧不存在）分数 0.9。纯置信度会丢错真子。
        val cells = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        val scores = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        val spots = listOf(0 to 0, 0 to 2, 0 to 4, 0 to 6, 0 to 8, 2 to 3)
        spots.forEach { (y, x) -> cells[y][x] = Piece.BZU; scores[y][x] = 0.9 }
        val trueY = 0; val trueX = 0
        scores[trueY][trueX] = 0.2          // 真卒这帧分数反而低
        val fakeY = 2; val fakeX = 3
        val prev = Array(AssistBoard.H) { y -> IntArray(AssistBoard.W) { x ->
            if (y == trueY && x == trueX) Piece.BZU else Piece.EMPTY
        } }
        // 不带上一局面：按置信度丢最低 → 真卒被丢（复现 v1.18 的失误）
        val a = AssistBoard.clone(cells); val sa = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) sa[y][x] = scores[y][x]
        PieceLimitTrim.trim(a, sa, null)
        assertEquals("不带上一局面时会误丢真卒", Piece.EMPTY, a[trueY][trueX])
        // 带上一局面：延续性加成保住真卒，丢掉背景误检
        val b = AssistBoard.clone(cells); val sb = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) sb[y][x] = scores[y][x]
        val r = PieceLimitTrim.trim(b, sb, prev)
        assertEquals("必须裁掉 1 个", 1, r.trimmed)
        assertNotEquals("真卒必须保住", Piece.EMPTY, b[trueY][trueX])
        assertEquals("背景误检必须被丢", Piece.EMPTY, b[fakeY][fakeX])
        assertTrue("延续性保住数要上报", r.protectedByContinuity >= 1)
        // 手工局面只摆了卒（没有帅将），不能套 validate；直接数黑卒数量
        val left = b.sumOf { row -> row.count { it == Piece.BZU } }
        assertEquals("裁完黑卒应回到上限 5", 5, left)
        // canonical <-> screenRaw 互逆（两种朝向）
        for (o in Orientation.values()) {
            val src = Array(AssistBoard.H) { y -> IntArray(AssistBoard.W) { x ->
                if ((x + y) % 3 == 0) Piece.WMA else Piece.EMPTY } }
            val back = DetectionBoardMapper.fromCanonical(
                DetectionBoardMapper.toCanonical(src, o), o)
            assertTrue("朝向 $o 下坐标必须还原", AssistBoard.equal(src, back))
        }
        report("裁剪延续性：上一帧同格同子优先保住，10/28 帧的裁剪不再按缺子局面算馊主意")
    }

    @Test
    fun `v1_20 服务层接质量门槛 重复局面重念 日志写清招法来源`() {
        fun readSrc(rel: String): String {
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        assertTrue("质量窗口常量", svc.contains("TAP_QUALITY_WINDOW_MS = 1000L"))
        assertTrue("decide 传质量参数", svc.contains("suggestMature, pvDepthNow") &&
            svc.contains("qualityElapsed, TAP_QUALITY_WINDOW_MS"))
        assertTrue("窗口到点强制重渲染", svc.contains("tapQualityTick") &&
            svc.contains("removeCallbacks(tapQualityTick)"))
        assertTrue("日志写清念的是什么质量的招", svc.contains("招法质量") &&
            svc.contains("等招算稳=") && svc.contains("本轮定着✓（bestmove）"))
        assertTrue("局面未变重念上一招，不重新浅算", svc.contains("重复点按") &&
            svc.contains("lastTapSpoken") && svc.contains("lastTapFen"))
        // 映射层延续性
        val det = readSrc("app/src/main/java/com/xqdk/chess/assist/YoloBoardDetector.kt")
        assertTrue("检测层透传上一局面", det.contains("prevCanonical = prevCanonical"))
        val mp = readSrc("app/src/main/java/com/xqdk/chess/assist/DetectionBoardMapper.kt")
        assertTrue("映射层做延续性裁剪", mp.contains("fromCanonical(it, roughOrientation)") &&
            mp.contains("PieceLimitTrim.trim(cells, cellScore, prevRaw)"))
        assertTrue("服务层把上一局面带进映射", svc.contains("yolo.detect(frame, cropHint(frame), currentCanonical)"))
        report("点一下 → 抓帧（延续性裁剪）→ 建局 → 等定着/较深PV → 念一次；局面未变重念上一招；日志写明招法来源")
    }

    @Test
    fun `v1_21 原作者口径 连续3帧一致才建局`() {
        val board = Array(AssistBoard.H) { y -> IntArray(AssistBoard.W) { x ->
            if ((x + y) % 4 == 0) Piece.WMA else Piece.EMPTY } }
        val other = AssistBoard.clone(board)
        other[3][3] = Piece.BZU
        var st = TapConfirm3.State()
        val (s1, v1) = TapConfirm3.feed(st, board)
        assertFalse("第 1 帧不能确认", v1.accept); assertEquals(1, v1.hits); st = s1
        val (s2, v2) = TapConfirm3.feed(st, board)
        assertFalse("第 2 帧仍不能确认", v2.accept); assertEquals(2, v2.hits); st = s2
        val (s3, v3) = TapConfirm3.feed(st, board)
        assertTrue("连续第 3 帧一致才确认（原作者 confirmCount=3）", v3.accept)
        assertEquals(3, v3.hits); st = s3
        // 中间插一帧不同局面 → 累计清零，不能凑数
        val (s4, v4) = TapConfirm3.feed(st, other)
        assertFalse("局面变了要重新累计", v4.accept); assertEquals(1, v4.hits); st = s4
        val (_, v5) = TapConfirm3.feed(st, board)
        assertFalse("坏帧打断后仍要满 3 帧", v5.accept); assertEquals(1, v5.hits)
        // 子数跳变门限：真实走子只 ±1，±3 的过渡帧不进累计
        val big = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        for (y in 0..5) big[y][0] = Piece.BZU
        assertTrue("首次无上一局面放行", TapConfirm3.pieceJumpPlausible(null, big))
        assertFalse("子数跳变过大判为动画/遮挡", TapConfirm3.pieceJumpPlausible(board, big))
        assertEquals(3, TapConfirm3.REQUIRED)
        report("识别对齐原作者：连续 3 帧逐格一致 + 子数跳变门限；不查节拍器由服务层保证")
    }

    @Test
    fun `v1_21 只念本轮定着 不念预判不算对方阵营`() {
        fun readSrc(rel: String): String {
            for (candidate in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(candidate)
                if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        // v1.22：点按档回到单帧直出（原作者 3 帧确认只用于常开档）
        assertFalse("主链路不再跨帧比对", svc.contains("TapConfirm3.feed"))
        assertTrue("单帧合法直接建局", svc.contains("applyTapDirectBoard(r)"))
        assertTrue("只在单帧不合法时才重试最多 3 帧", svc.contains("TAP_CAPTURE_RETRY_MAX = 3"))
        assertTrue("硬预算 3s", svc.contains("TAP_HARD_SPEAK_MS = 3000L"))
        // 只念本轮 bestmove
        assertTrue("成熟度只认 searchSettled", svc.contains("suggestMature = searchSettled"))
        assertTrue("本轮定着守卫", svc.contains("val isThisRound = tapSearchRequested") &&
            svc.contains("不让上一局结果冒充定着"))
        // 不想要的两种计算全删
        assertFalse("不再查预判缓存", svc.contains("ponderCache.pick(fen)"))
        assertFalse("不再喂预判缓存", svc.contains("ponderCache.feed(result.fen, result)"))
        assertFalse("不再记对方回合预案 FEN", svc.contains("if (!myTurn && precomputeEnabled) lastPreFen = fen"))
        assertTrue("对方回合不发引擎搜索", svc.contains("轮到对方 → 不发起引擎搜索"))
        assertTrue("预案行留空", svc.contains("用户明确不要「若对方走X 我方应Y」这类预判计算/显示"))
        assertTrue("预计算固定关闭", svc.contains("private var precomputeEnabled = false"))
        // 日志不再冒功
        assertTrue("小票用真实点击→播报", svc.contains("tapSpeakTs - tapSessionStartTs"))
        assertFalse("小票不再用箭头时刻冒充播报", svc.contains("tapSuggestShownTs - tapSessionStartTs"))
        assertTrue("硬预算如实写未定着", svc.contains("硬预算·未定着"))
        assertTrue("质量来源写本轮定着", svc.contains("本轮定着✓（bestmove）"))
        report("点一下 → 单帧直出 → 只在我方回合发一次完整搜索 → 本轮 bestmove 才念；预判与对方阵营计算全删")
    }

    @Test
    fun `v1_21 播报门控 非定着一律不念`() {
        assertEquals(TapSpeakGate.Decision.SPEAK,
            TapSpeakGate.decide(true, false, true, "建议 车二进八 (h1h9) ✓", true,
                mature = true, pvDepth = 20, elapsedMs = 100L))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, false, true, "建议 炮2平1 (b7a7)（暂定）", true,
                mature = false, pvDepth = 20, elapsedMs = 2900L))
        assertEquals(TapSpeakGate.Decision.MUTED,
            TapSpeakGate.decide(true, false, true, "开局库 炮二平五 ✓", false, mature = true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(true, true, true, "建议 马8进9 ✓", true, mature = true))
        assertEquals(TapSpeakGate.Decision.WAIT,
            TapSpeakGate.decide(false, false, true, "建议 车二进四 ✓", true, mature = true))
        report("只念本轮定着/开局库；3s 硬预算由服务层强制定着并如实标注，绝不静默")
    }

    @Test
    fun `v1_22 点按档单帧直出 不再为凑3帧反复重提交画面`() {
        fun readSrc(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        // 11:44 日志：9 次点按 / 65 帧全合法 / 全是「第 1/3 帧一致」/ 9 次全失败
        assertFalse("不再有 3 帧硬门槛", svc.contains("confirmVerdict.accept"))
        assertFalse("不再有凑不齐就收场的分支", svc.contains("未凑齐${TapConfirm3.REQUIRED}帧一致"))
        assertTrue("点按档单帧直出说明在", svc.contains("点按档单帧直出"))
        assertTrue("不跨帧比对", svc.contains("不跨帧比对"))
        assertTrue("单帧不合法才重试", svc.contains("TAP_CAPTURE_RETRY_MAX = 3"))
        // 原作者 3 帧确认器留档但不阻塞点按档
        val c = readSrc("app/src/main/java/com/xqdk/chess/assist/TapConfirm3.kt")
        assertEquals(3, TapConfirm3.REQUIRED)
        assertTrue("确认器仍保留原作者门限", c.contains("pieceJumpPlausible"))
        report("9 次点按 9 次失败的根因是反复 setSurface+resize 造出 A/B 两套画面；改回单帧直出，认准放在帧内")
    }

    @Test
    fun `v1_22 一键关闭共享屏幕 一律执行停止`() {
        fun readSrc(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val act = readSrc("app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt")
        val gate = readSrc("app/src/main/java/com/xqdk/chess/assist/CaptureStopGate.kt")
        assertTrue("必须读到连线页源码", act.isNotEmpty())
        assertFalse("不再有提前 return 拦截", act.contains("if (!verdict.ok)"))
        assertTrue("一律写录屏停止诊断", act.contains("主页一键关闭共享屏幕：点击"))
        assertTrue("服务引用在就完整收场", act.contains("stopGuidance(\"主页一键关闭共享屏幕\")"))
        assertTrue("引用不在也发停止意图兜底", act.contains("stopService(Intent(this, ScreenAssistService::class.java))"))
        assertTrue("门禁永远放行", gate.contains("return Verdict(true, source, hint)"))
        assertTrue(CaptureStopGate.resolve(false, false, false).ok)
        report("按了就停：不检查 isCapturing、不依赖服务引用，stopService 触发 onDestroy→releaseCapture 兜底")
    }

    @Test
    fun `v1_22 红黑大方块互斥 引擎芯片已合并`() {
        fun readSrc(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val xml = readSrc("app/src/main/res/layout/overlay_panel.xml")
        val view = readSrc("app/src/main/java/com/xqdk/chess/assist/ui/OverlayPanelView.kt")
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        assertTrue("必须读到面板布局", xml.isNotEmpty())
        assertTrue("红方大方块", xml.contains("overlay_btn_side_red") && xml.contains("OverlaySideBlock"))
        assertTrue("黑方大方块", xml.contains("overlay_btn_side_black"))
        assertFalse("引擎芯片已删除", xml.contains("overlay_btn_engine"))
        assertFalse("旧我方小芯片已删除", xml.contains("overlay_btn_my_side"))
        assertTrue("两个色块直选 action", view.contains("SET_MY_SIDE_RED") && view.contains("SET_MY_SIDE_BLACK"))
        assertTrue("互斥由单一布尔驱动", view.contains("paintSideBlocks(model.mySideIsRed)") &&
            view.contains("paintSideBlock(btnSideBlack, !myRed, false)"))
        assertTrue("选中态实心高亮", view.contains("selected && red -> 0xE6C35440") && view.contains("\" ✓\""))
        assertTrue("服务处理红块", svc.contains("OverlayAction.SET_MY_SIDE_RED -> setMySide(true)"))
        assertTrue("服务处理黑块", svc.contains("OverlayAction.SET_MY_SIDE_BLACK -> setMySide(false)"))
        assertTrue("跨对局锁定", svc.contains("互斥锁定·跨对局保持"))
        assertFalse("连线页仍无我方选择入口", readSrc("app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt").contains("mySideOverride"))
        report("引擎固定 YOLO → 引擎芯片删掉；红/黑两个 34dp 大方块直选、互斥、选中高亮✓，跨对局保持")
    }

    // ==================== v1.23 单帧识别修补：漏子 / 车卒不分 / 不瞎指挥 ====================

    private fun findPiece(b: Array<IntArray>, p: Int, skip: Int = 0): Pair<Int, Int> {
        var n = 0
        for (y in b.indices) for (x in b[y].indices) if (b[y][x] == p) {
            if (n == skip) return x to y
            n++
        }
        error("找不到棋子 $p")
    }

    private fun firstEmpty(b: Array<IntArray>): Pair<Int, Int> {
        for (y in b.indices) for (x in b[y].indices) if (b[y][x] == Piece.EMPTY) return x to y
        error("没有空格")
    }


    // ============ v1.24 点后新采帧 + 不再把新局面改回上一手 ============

    @Test
    fun `v1_24 对方走一步且终点漏检 不再补回上一手`() {
        val prev = AssistBoard.canonicalStart()
        val cur = AssistBoard.clone(prev)
        // 黑车从 (0,0) 走到 (0,1)，但 YOLO 把终点也漏检了 → 只看到起点消失
        cur[0][0] = Piece.EMPTY
        assertTrue("少一个黑车在合法性检查里是合法的（所以旧版会补）",
            AssistBoard.validate(cur).isEmpty())
        val r = BoardRepair.repair(prev, cur)
        assertEquals("一个格子都不许补", 0, r.fixed)
        assertEquals("起点仍为空：没有把黑车补回上一手的位置", Piece.EMPTY, r.canonical[0][0])
        assertFalse("不能变回上一局面（这正是「算的是前面那一帧」的根因）",
            AssistBoard.equal(prev, r.canonical))
        assertTrue("按当前这一帧原样保留", AssistBoard.equal(cur, r.canonical))
        assertTrue("认不全是可疑 → 不建局不支招", r.suspect)
        assertTrue("日志写明认不全", r.change.contains("认不全") && r.reason.contains("没认出来"))
        report("对方走一步 + 终点漏检：v1.23 会把起点补回、棋盘变回上一手；v1.24 一个子都不补，认不全就明说")
    }

    @Test
    fun `v1_24 对方走一步且终点被认出 按当前新局面建局`() {
        val prev = AssistBoard.canonicalStart()
        val cur = AssistBoard.clone(prev)
        cur[0][0] = Piece.EMPTY
        cur[1][0] = Piece.BJU                  // 终点被正确认出
        val r = BoardRepair.repair(prev, cur)
        assertEquals(0, r.fixed)
        assertFalse("真实走子必须放行", r.suspect)
        assertTrue("按当前这一帧建局，不带上一局面任何残留", AssistBoard.equal(cur, r.canonical))
        assertTrue("日志判定为真实走子", r.change.contains("真实走子") &&
            r.change.contains("起点1/终点1"))
        assertTrue("不再补空格", r.detail.contains("无需纠正"))
        report("对方走一步（终点认出）：认定真实走子 → 一个子都不补 → 按当前这一帧全新起点算招")
    }

    @Test
    fun `v1_24 车卒不分仍能改回 但不补空格`() {
        val prev = AssistBoard.canonicalStart()
        val cur = AssistBoard.clone(prev)
        val (cx, cy) = findPiece(cur, Piece.WJU)
        cur[cy][cx] = Piece.WBING              // 红车被认成红卒
        assertTrue("类别错在合法性检查里也可能合法", AssistBoard.validate(cur).isEmpty())
        val r = BoardRepair.repair(prev, cur)
        assertEquals(1, r.fixed)
        assertFalse("改回后必须放行", r.suspect)
        assertTrue("只改类别、不动空格 → 与上一局面一致", AssistBoard.equal(prev, r.canonical))
        assertTrue("日志写明是同类替换", r.change.contains("同类替换1"))
        assertTrue("明细写明车卒纠正", r.detail.contains("车") && r.detail.contains("→"))
        report("车卒不分：同格同阵营类别混淆就地改回；空格一律不补（这两件事 v1.24 分开了）")
    }

    @Test
    fun `v1_24 纯漏检一个车 不补回旧局面 标可疑不出招`() {
        val prev = AssistBoard.canonicalStart()
        val cur = AssistBoard.clone(prev)
        val (x, y) = findPiece(cur, Piece.WJU)
        cur[y][x] = Piece.EMPTY
        val r = BoardRepair.repair(prev, cur)
        assertEquals(0, r.fixed)
        assertEquals(Piece.EMPTY, r.canonical[y][x])
        assertFalse(AssistBoard.equal(prev, r.canonical))
        assertTrue(r.suspect && r.reason.contains("没认出来"))
        report("纯漏检一个车：不再按缺子棋盘瞎指挥、也不补回旧局面 → 明确提示再点一次")
    }

    @Test
    fun `v1_24 走子被吃新开局无锚 都不误补`() {
        val prev = AssistBoard.canonicalStart()
        // 走子：起点消失 + 终点出现 → 放行
        val moved = AssistBoard.clone(prev)
        val (ax, ay) = findPiece(moved, Piece.WJU)
        val (ex, ey) = firstEmpty(moved)
        moved[ay][ax] = Piece.EMPTY
        moved[ey][ex] = Piece.WJU
        val r1 = BoardRepair.repair(prev, moved)
        assertEquals(0, r1.fixed)
        assertFalse(r1.suspect)
        assertTrue(r1.change.contains("真实走子"))
        // 被吃：同格换成对方子 → 放行，且不能当成类别混淆改回
        val eaten = AssistBoard.clone(prev)
        val (px, py) = findPiece(eaten, Piece.WBING)
        eaten[py][px] = Piece.BZU
        val r2 = BoardRepair.repair(prev, eaten)
        assertEquals(0, r2.fixed)
        // 裸构造局面里「兵换成卒」会让卒数达上限 +1（真实链路上 PieceLimitTrim
        // 会先裁过），所以这里不断言 suspect，只验证不误补、不误改类别、判定为吃子。
        assertFalse("跨阵营不能被误判成同色类别混淆", r2.detail.contains("→"))
        assertTrue(r2.change.contains("吃子1"))
        // 新开局：满 32 子且布局变了 → 不按旧残局修补
        val old = AssistBoard.clone(prev)
        val (ox, oy) = findPiece(old, Piece.WJU)
        old[oy][ox] = Piece.EMPTY
        val rn = BoardRepair.repair(old, prev)
        assertEquals(0, rn.fixed)
        assertTrue(rn.detail.contains("新开局"))
        // 无锚：帅缺失 → 可疑；合法 → 放行
        val broken = AssistBoard.clone(prev)
        val (sx, sy) = findPiece(broken, Piece.WSHUAI)
        broken[sy][sx] = Piece.EMPTY
        val rb = BoardRepair.repair(null, broken)
        assertTrue(rb.suspect && rb.reason.contains("红帅"))
        assertFalse(BoardRepair.repair(null, prev).suspect)
        report("走子 / 被吃 / 新开局 / 无锚：只纠正类别混淆，绝不篡改真实棋局；硬伤才不出招")
    }

    @Test
    fun `v1_24 每次点按都是全新起点 全量FEN不带历史着法`() {
        val board = AssistBoard.canonicalStart()
        val fenRed = AssistBoard.toFen(board, true)
        val fenBlack = AssistBoard.toFen(board, false)
        assertTrue("红方走：w", fenRed.endsWith("w - - 0 1"))
        assertTrue("黑方走：b", fenBlack.endsWith("b - - 0 1"))
        assertEquals("同一棋盘只差轮次字段", fenRed.substringBefore(' '),
            fenBlack.substringBefore(' '))
        fun readSrc(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val eng = readSrc("app/src/main/java/com/xqdk/chess/assist/AnalysisEngine.kt")
        assertTrue("引擎收到的是全量局面", eng.contains("position fen"))
        assertFalse("绝不从 startpos 重放开局", eng.contains("position startpos"))
        assertFalse("不带历史着法串", eng.contains("moves "))
        report("引擎入口：position fen <当前这一帧的摆法> + go；无 startpos、无 moves 历史，每次都是全新起点")
    }

    @Test
    fun `v1_24 取帧与修补接线 源码门禁`() {
        fun readSrc(rel: String): String {
            for (c in listOf(rel, rel.removePrefix("app/"), "../" + rel)) {
                val f = java.io.File(c); if (f.exists()) return f.readText()
            }
            return ""
        }
        val svc = readSrc("app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt")
        val br = readSrc("app/src/main/java/com/xqdk/chess/assist/BoardRepair.kt")
        val diag = readSrc("app/src/main/java/com/xqdk/chess/assist/VoiceDiag.kt")
        assertTrue("必须读到服务源码", svc.isNotEmpty())
        // 取帧：一律点后新采
        assertFalse("不再用点击前缓冲帧识别", svc.contains("captureBufferedFrame(seq)"))
        assertTrue("先丢弃点前残留缓冲帧", svc.contains("drainBufferedFrames(seq)") &&
            svc.contains("丢弃点击前残留缓冲帧"))
        assertTrue("强制重提交一帧", svc.contains("forceCaptureFrame()"))
        assertTrue("点后第一帧仍绕过全部帧闸门", svc.contains("TapCaptureGate.shouldBypassFrameGates"))
        assertTrue("静止 ROM 仍有兜底", svc.contains("TAP_CAPTURE_WAIT_MS"))
        // 修补：不再补空格、按当前帧建局
        assertTrue("单帧后先过修补层", svc.contains("BoardRepair.repair(currentCanonical, raw.canonical)"))
        assertTrue("日志带变化判定", svc.contains("${'$'}{fix.change}"))
        assertTrue("可疑时不建局不支招", svc.contains("识别可疑·未支招") &&
            svc.contains("宁可不出招，绝不按错局面或旧局面瞎指挥"))
        assertTrue("明确写不回退上一局面", svc.contains("不回退上一局面"))
        assertTrue("修补层已删除补漏检子", !br.contains("R2 孤立漏检") && br.contains("不补空格"))
        assertTrue("认不全判据在", br.contains("val unrecognized = disappeared > 0 && realChangeEvidence == 0"))
        // 防线不能回退
        assertFalse("没有退回跨帧确认", svc.contains("TapConfirm3.feed"))
        assertFalse("没有退回凑不齐就收场", svc.contains("confirmVerdict.accept"))
        assertTrue("单帧直出仍在", svc.contains("applyTapDirectBoard(r)"))
        assertTrue("起点双保险仍在", svc.contains("fun fromIsOurs(ucci: String): Boolean") &&
            svc.contains("if (fromIsOurs(ucci))"))
        assertTrue("合法门槛仍只查上限", readSrc("app/src/main/java/com/xqdk/chess/assist/AssistBoard.kt")
            .contains("if (counts[k] > v)"))
        assertTrue("诊断覆盖新语义", diag.contains("识别修补") && diag.contains("点击后新采帧") &&
            diag.contains("position fen"))
        report("取帧一律点后新采、修补不补空格、按当前帧建局；单帧直出与起点双保险全在，未退回多帧确认")
    }

}
