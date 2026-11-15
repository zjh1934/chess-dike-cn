package com.xqdk.chess.assist.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.xqdk.chess.R
import com.xqdk.chess.assist.AssistConfig
import com.xqdk.chess.assist.TapMode
import com.xqdk.chess.assist.VoiceDiag
import com.xqdk.chess.assist.VoicePrompt
import com.xqdk.chess.assist.VoiceSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 增强功能（v1.9 开箱即用）：**识别行为永久固定，没有任何档位开关**。
 *
 * 用户原话：「那么多复杂的设置我也看不懂啊，有必要吗？……我要的就是能用」。
 * v1.5~v1.8 的四选一互斥 / 两维度开关会把点棋盘劫持成「切候选」，把
 * 「识别 → 推理 → 支招 → 语音播报」整条链断掉，实测就是「点一下没用」。
 * v1.9 起页面第一屏只写清「怎么用」，一个识别选项都不放：
 *
 * - **点一下棋盘 = 这一帧直接算 → 支招 → 语音播报；不点棋盘完全不动。**
 * - 中文播报：总开关（默认开）/ 引擎 / 内容多选 / 试听 —— 默认已配好，不用改。
 * - 诊断工具：增强功能诊断开关 / 自选路径导出 MD / 清空（排障时用）。
 * - 构建期固定项（包名锁定、固定签名）只读展示，明确不是运行时开关。
 *
 * 旧配置由 AssistConfig 读取时幂等归位，老用户覆盖安装后立刻好用。
 */
class FeatureSwitchesActivity : AppCompatActivity() {

    private var engineProbe: TextToSpeech? = null
    private var voiceTester: VoiceSpeaker? = null
    private var voiceEngineChoices: List<Pair<String, String>> = listOf("跟随系统" to "")

    private lateinit var cfg: AssistConfig
    private lateinit var readCheckBoxes: List<Pair<CheckBox, VoicePrompt.Category>>

    // v1.9：识别行为永久固定，页面不再有任何档位开关、也不再需要服务引用。
    // 旧配置由 AssistConfig 在读取时幂等归位，老用户升级后直接点棋盘就好用。

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onExportResult(result.resultCode, result.data?.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.feature_switches)
        cfg = AssistConfig(applicationContext)
        VoiceDiag.log(applicationContext, "增强功能页",
            "打开 固定行为=${TapMode.summary()} 支招播报=${cfg.voiceEnabled} " +
                "播报内容mask=${cfg.voiceReadMask} 诊断=${cfg.voiceDiagEnabled}")

        findViewById<Button>(R.id.btn_fs_back).setOnClickListener { finish() }

        // ---- 1. 怎么用（v1.9 开箱即用：行为固定，无可选项） ----
        // 点棋盘 = 这一帧直接算 → 支招 → 语音播报，永久固定；不点棋盘完全不动。
        // 旧档位配置（四选一互斥 / 自动识别）由 AssistConfig 读取时幂等归位，
        // 这里连一个开关都不放，用户不用看懂任何设置，装上点一下就能用。
        findViewById<TextView>(R.id.tv_usage_summary).text =
            "点一下棋盘 → 立刻支招 ＋ 语音播报\n" +
            "不点棋盘 → 完全不动\n" +
            "不用设置，装好就能用"

        // ---- 3. 中文播报：总开关 ----
        val btnEnabled = findViewById<Button>(R.id.btn_voice_enabled)
        btnEnabled.text = TapMode.switchText(cfg.voiceEnabled)
        btnEnabled.setOnClickListener {
            val on = !cfg.voiceEnabled
            cfg.voiceEnabled = on
            val state = TapMode.switchText(on)
            btnEnabled.text = state
            VoiceDiag.log(applicationContext, "配置变更", "支招播报=$state")
        }

        // ---- 4. 播报引擎 ----
        val btnEngine = findViewById<Button>(R.id.btn_voice_engine)
        updateVoiceEngineButton(btnEngine)
        btnEngine.setOnClickListener {
            val choices = voiceEngineChoices
            val labels = choices.map { it.first as CharSequence }.toTypedArray()
            val checked = choices.indexOfFirst { it.second == cfg.ttsEngine }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("播报引擎")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    if (which in choices.indices) {
                        cfg.ttsEngine = choices[which].second
                        VoiceDiag.log(applicationContext, "配置变更", "播报引擎=${choices[which].first}")
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
                    "onInit=$status 已安装=${installed.size}")
                runCatching { probe?.shutdown() }
                engineProbe = null
                if (!isFinishing && !isDestroyed) updateVoiceEngineButton(btnEngine)
            })
        } catch (t: Throwable) {
            null
        }

        // ---- 5. 播报内容多选（位掩码） ----
        readCheckBoxes = listOf(
            findViewById<CheckBox>(R.id.cb_read_suggestion) to VoicePrompt.Category.SUGGESTION,
            findViewById<CheckBox>(R.id.cb_read_book) to VoicePrompt.Category.BOOK,
            findViewById<CheckBox>(R.id.cb_read_opponent_plan) to VoicePrompt.Category.OPPONENT_PLAN,
            findViewById<CheckBox>(R.id.cb_read_evaluation) to VoicePrompt.Category.EVALUATION,
            findViewById<CheckBox>(R.id.cb_read_status) to VoicePrompt.Category.STATUS,
        )
        val mask = cfg.voiceReadMask
        readCheckBoxes.forEach { (cb, cat) ->
            cb.isChecked = ((cat.bit and mask) != 0)
            cb.setOnCheckedChangeListener { _, _ -> onReadMaskChanged() }
        }

        // ---- 6. 播报试听 ----
        findViewById<Button>(R.id.btn_voice_test).setOnClickListener {
            VoiceDiag.log(applicationContext, "试听", "点击试听（引擎=${cfg.ttsEngine.ifEmpty { "跟随系统" }}）")
            voiceTester?.shutdown()
            voiceTester = VoiceSpeaker(applicationContext).also {
                it.speakTest("语音提示正常，炮二平五", cfg.ttsEngine)
            }
        }

        // ---- 7. 增强功能诊断开关 ----
        val btnDiag = findViewById<Button>(R.id.btn_voice_diag)
        btnDiag.text = TapMode.switchText(cfg.voiceDiagEnabled)
        btnDiag.setOnClickListener {
            val on = !cfg.voiceDiagEnabled
            VoiceDiag.log(applicationContext, "配置变更", "增强功能诊断→${TapMode.switchText(on)}")
            cfg.voiceDiagEnabled = on
            btnDiag.text = TapMode.switchText(on)
        }

        // ---- 8. 导出增强功能诊断（SAF 自选路径，无需存储权限） ----
        findViewById<Button>(R.id.btn_voice_export).setOnClickListener {
            VoiceDiag.log(applicationContext, "导出", "点击导出增强功能诊断（自选路径）")
            val dateTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/markdown"
                putExtra(Intent.EXTRA_TITLE, VoiceDiag.exportFileName(dateTag))
            }
            try {
                exportLauncher.launch(intent)
            } catch (t: Throwable) {
                VoiceDiag.log(applicationContext, "导出", "选择器启动失败(${t.javaClass.simpleName}) 回落兜底")
                fallbackExport()
            }
        }

        // ---- 9. 清空增强功能诊断 ----
        findViewById<Button>(R.id.btn_voice_clear).setOnClickListener {
            VoiceDiag.log(applicationContext, "配置变更", "清空增强功能诊断日志")
            VoiceDiag.clear()
            Toast.makeText(applicationContext, "增强功能诊断日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    // v1.9：档位交互函数整体删除 —— 行为固定后没有任何需要同步或回填的状态。

    private fun onReadMaskChanged() {
        val selected = readCheckBoxes.filter { it.first.isChecked }.map { it.second }.toSet()
        val newMask = VoicePrompt.maskOf(selected)
        cfg.voiceReadMask = newMask
        val names = VoicePrompt.categoriesOf(newMask).joinToString("、") { it.label }.ifEmpty { "无" }
        VoiceDiag.log(applicationContext, "配置变更", "播报内容多选=$names mask=$newMask")
    }

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
            Toast.makeText(applicationContext, "增强功能诊断已导出：$target", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            VoiceDiag.log(applicationContext, "导出", "自选路径：失败(${t.javaClass.simpleName}) 回落兜底")
            fallbackExport()
        }
    }

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
        runCatching { engineProbe?.shutdown() }
        engineProbe = null
        runCatching { voiceTester?.shutdown() }
        voiceTester = null
        super.onDestroy()
    }

    private fun updateVoiceEngineButton(btn: Button) {
        val engine = cfg.ttsEngine
        btn.text = voiceEngineChoices.firstOrNull { it.second == engine }?.first ?: "跟随系统"
    }
}
