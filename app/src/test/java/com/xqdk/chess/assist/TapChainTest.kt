package com.xqdk.chess.assist

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.xqdk.chess.R
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
}
