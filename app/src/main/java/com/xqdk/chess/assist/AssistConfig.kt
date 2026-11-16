package com.xqdk.chess.assist

import android.content.Context
import com.xqdk.chess.assist.ui.OverlayTheme

/**
 * 连线功能的持久化配置（SharedPreferences）。
 */
class AssistConfig(context: Context) {
    private val sp = context.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)

    /**
     * v1.17：识别引擎**固定 yolo**。ONNX 角点式模型受版权不随包分发，历史配置里的
     * "onnx" 会让每次启动白加载一次失败再回退，UI 还「选了又跳回」。这里读写双向
     * 幂等归位：旧值/脏值/外部写入一律按 yolo 处理，老用户覆盖安装后立刻好用。
     */
    var recognizerEngine: String
        get() = "yolo"
        set(value) { sp.edit().putString(KEY_RECOGNIZER_ENGINE, "yolo").apply() }

    /** 最近一次识别时的帧尺寸（悬浮窗遮挡判断用：把归一化棋盘框换算回像素） */
    var frameSize: Pair<Int, Int>
        get() {
            val w = sp.getInt(KEY_FRAME_W, 0)
            val h = sp.getInt(KEY_FRAME_H, 0)
            return w to h
        }
        set(value) {
            sp.edit().putInt(KEY_FRAME_W, value.first).putInt(KEY_FRAME_H, value.second).apply()
        }

    /** 稳定确认帧数（1..8） */
    var confirmCount: Int
        get() = sp.getInt(KEY_CONFIRM, 3).coerceIn(1, 8)
        set(value) { sp.edit().putInt(KEY_CONFIRM, value.coerceIn(1, 8)).apply() }

    /**
     * 悬浮窗上次位置（左上角，归一化 0..1 基准=内容区尺寸）。归一化值**允许为负
     * 或超 1**——贴边停靠钳制允许面板挂进状态栏/导航栏区域，因此"未保存过"的
     * 判定不能再用负值哨兵，改由 [hasOverlayPos] 布尔位承担。
     */
    var overlayPos: Pair<Float, Float>
        get() = sp.getFloat(KEY_OVERLAY_X, -1f) to sp.getFloat(KEY_OVERLAY_Y, -1f)
        set(value) {
            sp.edit().putFloat(KEY_OVERLAY_X, value.first).putFloat(KEY_OVERLAY_Y, value.second).apply()
        }

    /** 是否保存过悬浮窗位置（false = 从未拖动过，落位走默认底部中央） */
    var hasOverlayPos: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_POS_SET, false)
        set(value) { sp.edit().putBoolean(KEY_OVERLAY_POS_SET, value).apply() }

    /** 搜索深度（与对弈"固定深度"制式一致，默认 20） */
    var searchDepth: Int
        get() = sp.getInt(KEY_SEARCH_DEPTH, 20)
        set(value) { sp.edit().putInt(KEY_SEARCH_DEPTH, value.coerceIn(6, 40)).apply() }

    /** 引擎 Hash（MB，与搜索设置同口径；面板按钮循环 256→512→1024→2048） */
    var hashMb: Int
        get() = sp.getInt(KEY_HASH_MB, 256)
        set(value) { sp.edit().putInt(KEY_HASH_MB, value).apply() }

    /** v1.13 悬浮面板「我方 红/黑」手动锁定：UserSideChoice.AUTO/BLACK/RED。
     *  **不在设置页暴露**，只在悬浮面板上切换；跨对局、跨服务重启保持。 */
    var mySideOverride: Int
        get() = sp.getInt(KEY_MY_SIDE_OVERRIDE, UserSideChoice.AUTO)
        set(value) { sp.edit().putInt(KEY_MY_SIDE_OVERRIDE, value).apply() }

    /**
     * 引擎变招路数（MultiPV，1-3；悬浮窗左列"N变"按钮循环）。
     * 1 路 = 全部算力集中最佳着（最强）；2-3 路附带给备选候选。默认 1（2026-10-30
     * 用户拍板：算力全部给最佳着；要看备选再手动开 2-3 路，代价是同深度明显变慢）。
     */
    var multiPv: Int
        get() = sp.getInt(KEY_MULTI_PV, 1).coerceIn(1, 3)
        set(value) { sp.edit().putInt(KEY_MULTI_PV, value.coerceIn(1, 3)).apply() }

    /**
     * 思考制式（象棋鱼"按深度思考/按时间思考"的连线映射）："depth"（默认，建议更强）
     * 或 "time"（固定时长收束，节奏可预期，快棋友好）。
     */
    var searchMode: String
        get() = sp.getString(KEY_SEARCH_MODE, "depth") ?: "depth"
        set(value) { sp.edit().putString(KEY_SEARCH_MODE, value).apply() }

    /** 时间制搜索时长 ms（500-30000，默认 3000；仅 searchMode="time" 时生效） */
    var searchTimeMs: Int
        get() = sp.getInt(KEY_SEARCH_TIME_MS, 3000).coerceIn(500, 30000)
        set(value) { sp.edit().putInt(KEY_SEARCH_TIME_MS, value).apply() }

    /** 悬浮窗主题（OverlayTheme.key：深色玻璃/纯黑 OLED/浅色纸感，连线页切换） */
    var overlayTheme: String
        get() = sp.getString(KEY_OVERLAY_THEME, null) ?: OverlayTheme.DARK.key
        set(value) { sp.edit().putString(KEY_OVERLAY_THEME, value).apply() }

    /**
     * 识别引擎分段计时开关（默认关）。开启后 ONNX 引擎每次识别多打一条 timing 日志
     * （角点/透视/分类各段耗时），供性能对比实验与排查识别耗时用；平时关闭零开销。
     * 不放主页 UI：这是诊断开关，实验机用 adb 改 shared_prefs 即可。
     */
    var benchProfiling: Boolean
        get() = sp.getBoolean(KEY_BENCH_PROFILING, false)
        set(value) { sp.edit().putBoolean(KEY_BENCH_PROFILING, value).apply() }

    /**
     * 空闲预计算（β，默认开）：对方思考期提前以同参搜索当前局面，结果进预判缓存；
     * 对方落子后若命中预判线，我方建议零等待直接显示且不再发引擎请求。连线页
     * 设置行热切换（ScreenAssistService.setPrecomputeEnabled）。
     */
    var precomputeEnabled: Boolean
        get() = sp.getBoolean(KEY_PRECOMPUTE_ENABLED, true)
        set(value) { sp.edit().putBoolean(KEY_PRECOMPUTE_ENABLED, value).apply() }

    /**
     * 语音提示开关（默认开）：支一招结果用系统 TTS 朗读着法；关闭后完全不朗读，
     * 不影响支招计算、悬浮窗、连线等任何原有行为。
     */
    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) { sp.edit().putBoolean(KEY_VOICE_ENABLED, value).apply() }

    /**
     * 语音提示引擎：TTS 引擎包名，空串 =「跟随系统」（默认，直接用系统默认 TTS）。
     * 可选项由设置页 TextToSpeech.getEngines() 列出，选中项持久化。
     */
    var ttsEngine: String
        get() = sp.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) { sp.edit().putString(KEY_TTS_ENGINE, value).apply() }

    /**
     * 语音诊断日志开关（默认开）：只控制新建的语音诊断通道（VoiceDiag），与性能诊断
     * DiagLogWriter 完全独立。关掉后不写任何语音诊断行；开箱即默认开，保证一键导出可用。
     */
    var voiceDiagEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_DIAG_ENABLED, true)
        set(value) { sp.edit().putBoolean(KEY_VOICE_DIAG_ENABLED, value).apply() }

    /**
     * 语音「朗读内容多选」位掩码（v1.2）：见 VoicePrompt.Category / DEFAULT_MASK。
     * 默认 DEFAULT_MASK = SUGGESTION|BOOK，保持 v1.1「只读建议/开局库着法」。
     * FeatureSwitchesActivity 的 5 个勾选项读写此值，ScreenAssistService 每次建议现读。
     */
    var voiceReadMask: Int
        get() = sp.getInt(KEY_VOICE_READ_MASK, VoicePrompt.DEFAULT_MASK)
        set(value) { sp.edit().putInt(KEY_VOICE_READ_MASK, value).apply() }

    /**
     * v1.9「开箱即用」：**行为固定，不再有可选档位**。
     *
     * 不管旧版本存过什么（v1.5~v1.7 的 AUTO / 点按变招 / 点按刷新 / 脏值，
     * 或 v1.8 的 autoTapEnabled=true），读配置时一律幂等归位成
     * 「点按出招 + 不点不动」。这是用户实测「装上点一下没用」的根治：
     * 旧档位会把点棋盘劫持成「切候选」，把识别推理支招播报整条链断掉。
     */
    var tapMode: String
        get() {
            normalizeToFixedBehavior()
            return TapMode.TAP_SUGGEST
        }
        set(_) { sp.edit().putString(KEY_TAP_MODE, TapMode.TAP_SUGGEST).apply() }

    /** v1.9：不点棋盘时永远不自动识别（零识别 / 零推理 / 零支招 / 零朗读）。 */
    var autoTapEnabled: Boolean
        get() {
            normalizeToFixedBehavior()
            return false
        }
        set(_) { sp.edit().putBoolean(KEY_AUTO_TAP_ENABLED, false).apply() }

    /** 幂等迁移：任何非固定行为的旧配置都写回归位，老用户升级后立刻好用。 */
    private fun normalizeToFixedBehavior() {
        val stored = sp.getString(KEY_TAP_MODE, null)
        val auto = sp.getBoolean(KEY_AUTO_TAP_ENABLED, false)
        if (stored != TapMode.TAP_SUGGEST || auto) {
            sp.edit()
                .putString(KEY_TAP_MODE, TapMode.TAP_SUGGEST)
                .putBoolean(KEY_AUTO_TAP_ENABLED, false)
                .apply()
        }
    }

    /**
     * 「自动识别」档下点棋盘做什么（默认开 = 点按刷新，关 = 点按变招）。
     * v1.8 起仅作 v1.3「快捷刷新」旧配置兼容保留；实际点棋盘动作统一由 [tapMode] 决定，
     * 设置页不再展示这一项（避免和「点棋盘时做什么」重复、语义打架）。
     */
    var quickRefreshEnabled: Boolean
        get() = sp.getBoolean(KEY_QUICK_REFRESH_ENABLED, QuickRefresh.DEFAULT_ENABLED)
        set(value) { sp.edit().putBoolean(KEY_QUICK_REFRESH_ENABLED, value).apply() }

    companion object {
        private const val KEY_RECOGNIZER_ENGINE = "recognizer_engine"
        private const val KEY_FRAME_W = "frame_w"
        private const val KEY_FRAME_H = "frame_h"
        private const val KEY_CONFIRM = "confirm_count"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_POS_SET = "overlay_pos_set"
        private const val KEY_SEARCH_DEPTH = "search_depth"
        private const val KEY_HASH_MB = "hash_mb"
    private const val KEY_MY_SIDE_OVERRIDE = "my_side_override"
        private const val KEY_MULTI_PV = "multi_pv"
        private const val KEY_SEARCH_MODE = "search_mode"
        private const val KEY_SEARCH_TIME_MS = "search_time_ms"
        private const val KEY_OVERLAY_THEME = "overlay_theme"
        private const val KEY_BENCH_PROFILING = "bench_profiling"
        private const val KEY_PRECOMPUTE_ENABLED = "precompute_enabled"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_VOICE_DIAG_ENABLED = "voice_diag_enabled"
        private const val KEY_VOICE_READ_MASK = "voice_read_mask"
        private const val KEY_QUICK_REFRESH_ENABLED = "quick_refresh_enabled"
        private const val KEY_TAP_MODE = "tap_mode"
        /** v1.8：不点棋盘时是否自动识别（独立开关，与 tap_mode 不再互斥） */
        private const val KEY_AUTO_TAP_ENABLED = "auto_tap_enabled"
        /** v1.4 遗留键，仅用于首次读取时迁移，不再写入 */
        private const val LEGACY_KEY_TAP_TRIGGER = "tap_trigger_enabled"
    }
}
