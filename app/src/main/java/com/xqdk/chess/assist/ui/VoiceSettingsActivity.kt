package com.xqdk.chess.assist.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.xqdk.chess.R
import com.xqdk.chess.assist.AssistConfig
import com.xqdk.chess.assist.QuickRefresh
import com.xqdk.chess.assist.VoiceDiag
import com.xqdk.chess.assist.VoicePrompt
import com.xqdk.chess.assist.VoiceSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音设置页（v1.2）：把 v1.1 主页的 5 个语音入口全部收进独立页面，主页只留一个
 * 「语音设置」入口（少破坏原生布局）。承载：
 * 1 总开关 / 2 引擎单选 / 3 五个「朗读内容」勾选（voiceReadMask） / 4 试听 /
 * 5 诊断记录开关 / 6 导出（SAF 自选路径，失败回落 VoiceDiag.export）/ 7 清空日志。
 *
 * 全部配置写 AssistConfig（同一 assist_settings），服务每次建议现读，热改即时生效。
 * 导出走 ActivityResultContracts.StartActivityForResult + ACTION_CREATE_DOCUMENT，
 * 无需任何存储权限（SAF Uri 自带授权，targetSdk33 也不需要）。
 */
class VoiceSettingsActivity : AppCompatActivity() {

    /** TTS 引擎探测实例（仅用于 getEngines() 枚举，回调内即 shutdown 避免泄漏） */
    private var engineProbe: TextToSpeech? = null

    /** 试听用的一次性 VoiceSpeaker（独立于服务实例，朗读结束自释放；onDestroy 兜底） */
    private var voiceTester: VoiceSpeaker? = null

    /** 语音引擎候选：(显示名, 包名)，首项恒为「跟随系统」(包名="") */
    private var voiceEngineChoices: List<Pair<String, String>> = listOf("跟随系统" to "")

    private lateinit var cfg: AssistConfig

    /** 5 个朗读内容勾选框 → 对应 Category */
    private lateinit var readCheckBoxes: List<Pair<CheckBox, VoicePrompt.Category>>

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onExportResult(result.resultCode, result.data?.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.voice_settings)
        cfg = AssistConfig(applicationContext)

        findViewById<Button>(R.id.btn_voice_back).setOnClickListener { finish() }

        // 1. 语音提示总开关（沿用 v1.1 写法）
        val btnEnabled = findViewById<Button>(R.id.btn_voice_enabled)
        btnEnabled.text = if (cfg.voiceEnabled) "开" else "关"
        btnEnabled.setOnClickListener {
            val on = !cfg.voiceEnabled
            cfg.voiceEnabled = on
            val state = if (on) "开" else "关"
            btnEnabled.text = state
            VoiceDiag.log(applicationContext, "配置变更", "语音提示=$state")
        }

        // 2. 语音提示引擎（沿用 v1.1：getEngines 枚举 + setSingleChoiceItems + 探测实例即 shutdown）
        val btnEngine = findViewById<Button>(R.id.btn_voice_engine)
        updateVoiceEngineButton(btnEngine)
        btnEngine.setOnClickListener {
            // 取快照：引擎列表由异步 getEngines() 回调填充，对话框存活期间列表可能被替换，
            // 若点击时再按新列表索引 which 会错位/越界。
            val choices = voiceEngineChoices
            val labels = choices.map { it.first as CharSequence }.toTypedArray()
            val checked = choices.indexOfFirst { it.second == cfg.ttsEngine }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("语音提示引擎")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    if (which in choices.indices) {
                        cfg.ttsEngine = choices[which].second
                        VoiceDiag.log(applicationContext, "配置变更", "TTS引擎=${choices[which].first}")
                    }
                    updateVoiceEngineButton(btnEngine)
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        engineProbe = try {
            TextToSpeech(applicationContext, TextToSpeech.OnInitListener { status ->
                val probe = engineProbe
                val installed = ArrayList<TextToSpeech.EngineInfo>()
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { probe?.engines?.let { installed.addAll(it) } }
                }
                voiceEngineChoices = listOf("跟随系统" to "") +
                    installed.map { it.label to it.name }
                VoiceDiag.log(applicationContext, "引擎枚举",
                    "onInit=$status 已安装=${installed.size} 列表=" +
                        installed.joinToString("、") { "${it.label}(${it.name})" }.ifEmpty { "无" })
                runCatching { probe?.shutdown() }
                engineProbe = null
                if (!isFinishing && !isDestroyed) updateVoiceEngineButton(btnEngine)
            })
        } catch (t: Throwable) {
            null
        }

        // 3. 五个「朗读内容」勾选项：读写 voiceReadMask 位掩码
        readCheckBoxes = listOf(
            findViewById<CheckBox>(R.id.cb_read_suggestion) to VoicePrompt.Category.SUGGESTION,
            findViewById<CheckBox>(R.id.cb_read_book) to VoicePrompt.Category.BOOK,
            findViewById<CheckBox>(R.id.cb_read_opponent_plan) to VoicePrompt.Category.OPPONENT_PLAN,
            findViewById<CheckBox>(R.id.cb_read_evaluation) to VoicePrompt.Category.EVALUATION,
            findViewById<CheckBox>(R.id.cb_read_status) to VoicePrompt.Category.STATUS,
        )
        val mask = cfg.voiceReadMask
        readCheckBoxes.forEach { (cb, cat) ->
            // 先回填勾选态、再挂监听：避免回填时误触发一次配置写入
            cb.isChecked = ((cat.bit and mask) != 0)
            cb.setOnCheckedChangeListener { _, _ -> onReadMaskChanged() }
        }

        // 4. 试听（沿用 v1.1：独立 VoiceSpeaker.speakTest + onDestroy 释放）
        findViewById<Button>(R.id.btn_voice_test).setOnClickListener {
            val engineLabel = cfg.ttsEngine.ifEmpty { "跟随系统" }
            VoiceDiag.log(applicationContext, "试听", "点击试听（引擎=$engineLabel）")
            voiceTester?.shutdown()
            voiceTester = VoiceSpeaker(applicationContext).also {
                it.speakTest("语音提示正常，炮二平五", cfg.ttsEngine)
            }
        }

        // 5. 诊断记录开关（沿用 v1.1：先写日志再改配置，切到"关"这条也能落盘）
        val btnDiag = findViewById<Button>(R.id.btn_voice_diag)
        btnDiag.text = if (cfg.voiceDiagEnabled) "开" else "关"
        btnDiag.setOnClickListener {
            val on = !cfg.voiceDiagEnabled
            val state = if (on) "开" else "关"
            VoiceDiag.log(applicationContext, "配置变更", "诊断记录→$state")
            cfg.voiceDiagEnabled = on
            btnDiag.text = state
        }

        // 6. 导出诊断日志：SAF 自选路径（ACTION_CREATE_DOCUMENT），无需存储权限
        findViewById<Button>(R.id.btn_voice_export).setOnClickListener {
            VoiceDiag.log(applicationContext, "导出", "点击导出诊断日志（自选路径）")
            val dateTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/markdown"
                putExtra(Intent.EXTRA_TITLE, VoiceDiag.exportFileName(dateTag))
            }
            try {
                exportLauncher.launch(intent)
            } catch (t: Throwable) {
                // 设备无可用文件选择器：直接回落兜底导出
                VoiceDiag.log(applicationContext, "导出", "选择器启动失败(${t.javaClass.simpleName}) 回落兜底")
                fallbackExport()
            }
        }

        // 7. 清空日志（可选）
        findViewById<Button>(R.id.btn_voice_clear).setOnClickListener {
            VoiceDiag.log(applicationContext, "配置变更", "清空语音诊断日志")
            VoiceDiag.clear()
            Toast.makeText(applicationContext, "诊断日志已清空", Toast.LENGTH_SHORT).show()
        }

        // 8. 快捷刷新（v1.3）：与语音无关，但按用户要求放在本设置页；写同一份
        //    assist_settings，服务每次 render 现读，热改即时生效。
        val btnQuickRefresh = findViewById<Button>(R.id.btn_quick_refresh)
        btnQuickRefresh.text = QuickRefresh.switchText(cfg.quickRefreshEnabled)
        btnQuickRefresh.setOnClickListener {
            val on = !cfg.quickRefreshEnabled
            cfg.quickRefreshEnabled = on
            btnQuickRefresh.text = QuickRefresh.switchText(on)
            Toast.makeText(
                applicationContext,
                if (on) "快捷刷新已开启：点悬浮窗棋盘即可刷新棋局"
                else "快捷刷新已关闭：点棋盘仍切换候选着法",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 勾选变化：重算位掩码、持久化、写「多选配置变更」诊断日志。 */
    private fun onReadMaskChanged() {
        val selected = readCheckBoxes.filter { it.first.isChecked }.map { it.second }.toSet()
        val newMask = VoicePrompt.maskOf(selected)
        cfg.voiceReadMask = newMask
        val names = VoicePrompt.categoriesOf(newMask).joinToString("、") { it.label }.ifEmpty { "无" }
        VoiceDiag.log(applicationContext, "配置变更", "朗读内容多选=$names mask=$newMask")
    }

    /** SAF 回调：取消 Toast、写入成功 Toast 目标、异常回落兜底。 */
    private fun onExportResult(resultCode: Int, uri: Uri?) {
        if (isFinishing || isDestroyed) return
        if (resultCode != Activity.RESULT_OK || uri == null) {
            VoiceDiag.log(applicationContext, "导出", "自选路径：已取消")
            Toast.makeText(applicationContext, "已取消导出", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val doc = VoiceDiag.buildDocument(applicationContext)
            val os = applicationContext.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("openOutputStream 返回 null")
            os.use { it.write(doc.toByteArray(Charsets.UTF_8)) }
            val target = uri.path ?: uri.lastPathSegment ?: "所选位置"
            VoiceDiag.log(applicationContext, "导出", "自选路径：成功 目标=$target")
            Toast.makeText(applicationContext, "诊断日志已导出：$target", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            VoiceDiag.log(applicationContext, "导出", "自选路径：失败(${t.javaClass.simpleName}) 回落兜底")
            fallbackExport()
        }
    }

    /** 兜底：写下载目录并 Toast 说明。 */
    private fun fallbackExport() {
        if (isFinishing || isDestroyed) return
        val path = VoiceDiag.export(applicationContext)
        VoiceDiag.log(applicationContext, "导出", "兜底：下载目录 path=$path")
        val message = if (path != null)
            "自选路径写入失败，已存到下载目录：$path"
        else
            "导出失败：请检查存储空间后重试"
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        // 引擎枚举探测实例用完释放（回调未到时由这里兜底 shutdown）
        runCatching { engineProbe?.shutdown() }
        engineProbe = null
        // 试听一次性实例兜底释放（正常由朗读结束 onDone/onError 自释放）
        runCatching { voiceTester?.shutdown() }
        voiceTester = null
        super.onDestroy()
    }

    /** 语音引擎按钮回填：按持久化的引擎包名找显示名；跟随系统/尚未探测到时显示默认项。 */
    private fun updateVoiceEngineButton(btn: Button) {
        val engine = cfg.ttsEngine
        btn.text = voiceEngineChoices.firstOrNull { it.second == engine }?.first ?: "跟随系统"
    }
}
