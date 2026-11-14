package com.xqdk.chess.assist

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音功能诊断日志（**独立通道**，只记录 TTS/语音链路事件，绝不记录识别帧、FEN、
 * 引擎搜索、性能数据——与性能诊断 DiagLogWriter 完全分离、不复用它）。
 *
 * 记录项：开关与引擎配置快照、TTS 实例构造与引擎包名、getEngines() 枚举结果、
 * onInit status、setLanguage 结果与当前 locale、speak() 返回值、去重/排队/打断
 * 决策、UtteranceProgress 的 onStart/onDone/onError、试听事件、服务 onCreate/onDestroy、
 * 配置变更。
 *
 * 「一键导出」模型：日志先入内存会话缓冲（每条 [log] 以 synchronized 追加、立即可
 * 导出，写失败静默不拖垮主链路），点导出时由 [export] 一次性组装成带完整表头与设备/
 * 版本信息的 Markdown 文件并 flush 落盘。落盘口径与 DiagLogWriter 相同：API 29+ 用
 * MediaStore.Downloads（MIME text/markdown），API 26-28 回退应用专属外部目录；
 * [displayPath] 为最近一次导出的可展示路径。上限 [MAX_LINES] 行防无限增长。
 *
 * 开关 AssistConfig.voiceDiagEnabled（默认 true）：关闭时 [log] 不写任何行。
 */
object VoiceDiag {

    /** 会话缓冲行数上限（防无限增长；保留最近若干行） */
    const val MAX_LINES = 2000

    /** 最近一次导出文件路径（成功为可展示路径；失败/未导出为 null） */
    @Volatile
    var displayPath: String? = null
        private set

    private val lock = Any()
    private val lines = ArrayList<String>()

    /**
     * 追加一条语音链路事件。格式化为带 HH:mm:ss.SSS 时间戳与标签的 Markdown 表格行，
     * 追加后即成为可导出状态（写失败不会抛出、不影响朗读主链路）。
     */
    fun log(context: Context, tag: String, detail: String) {
        if (!AssistConfig(context).voiceDiagEnabled) return
        val time = synchronized(CLOCK) { CLOCK.format(Date()) }
        val row = formatRow(time, tag, detail)
        synchronized(lock) {
            lines.add(row)
            if (lines.size > MAX_LINES) {
                // 越界才裁剪：保留最近 MAX_LINES 行（与纯函数 trimToLimit 同语义）
                val kept = trimToLimit(lines, MAX_LINES)
                lines.clear()
                lines.addAll(kept)
            }
        }
    }

    /** 清空当前会话缓冲（不删除已导出的文件） */
    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    /**
     * 一键导出：把当前会话日志写成一个新的、带完整表头与设备/版本信息的 .md 文件
     * （文件名 XQDK_语音诊断_yyyyMMdd_HHmmss.md），触发媒体扫描，返回可展示路径。
     * 全流程 runCatching 包裹，失败返回 null。
     */
    fun export(context: Context): String? {
        val snapshot = synchronized(lock) { lines.toList() }
        var path: String? = null
        return try {
            val device = "${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）"
            val ver = try {
                com.xqdk.chess.BuildConfig.VERSION_NAME
            } catch (_: Throwable) {
                "?"
            }
            val exportTime = synchronized(STAMP) { STAMP.format(Date()) }
            val dateTag = synchronized(FILE_STAMP) { FILE_STAMP.format(Date()) }
            val doc = buildExportDocument(device, ver, Build.VERSION.SDK_INT, exportTime, snapshot)
            val name = exportFileName(dateTag)

            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                }
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                            w.write(doc)
                            w.flush()
                        }
                    }
                    // DATA 列在 API 29+ 仍可读：换回真实文件路径用于展示
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) path = c.getString(0)
                    }
                }
            } else {
                // API 26-28：无存储权限写不了公共 Download，回退应用专属目录
                val dir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
                val f = File(dir, name)
                BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8)).use { w ->
                    w.write(doc)
                    w.flush()
                }
                path = f.absolutePath
            }

            displayPath = path
            path?.let { p ->
                runCatching {
                    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(p), null, null)
                }
            }
            path
        } catch (t: Throwable) {
            Log.w(TAG, "voice diag export failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "VoiceDiag"

        /** 导出表格表头（三列：时间/标签/详情） */
        const val TABLE_HEADER = "| 时间 | 标签 | 详情 |\n|---|---|---|\n"

        private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /** 单行格式化：纯函数。转义 `|`、压平换行，保证不破坏 Markdown 表格结构。 */
        fun formatRow(time: String, tag: String, detail: String): String =
            "| ${esc(time)} | ${esc(tag)} | ${esc(detail)} |\n"

        private fun esc(s: String): String =
            s.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

        /** 上限裁剪：纯函数，保留最近 limit 行（limit<=0 返回空）。 */
        fun trimToLimit(rows: List<String>, limit: Int): List<String> {
            if (limit <= 0) return emptyList()
            return if (rows.size <= limit) rows
            else rows.subList(rows.size - limit, rows.size).toList()
        }

        /** 组装完整导出文档：标题 + 隐私提示 + 设备/版本信息 + 表头 + 全部行（纯函数）。 */
        fun buildExportDocument(
            device: String, version: String, sdk: Int, exportTime: String, rows: List<String>
        ): String {
            val sb = StringBuilder()
            sb.append("# XQDK 语音功能诊断\n\n")
            sb.append("> 隐私提示：本文件仅记录语音提示（TTS）功能链路事件，不含识别帧、FEN、" +
                    "对局记录或性能数据。\n\n")
            sb.append("- 设备：$device\n")
            sb.append("- 版本：$version\n")
            sb.append("- Android SDK：$sdk\n")
            sb.append("- 导出时间：$exportTime\n")
            sb.append("- 记录行数：${rows.size}\n\n")
            sb.append("## 事件\n\n")
            sb.append(TABLE_HEADER)
            rows.forEach { sb.append(it) }
            return sb.toString()
        }

        /** 导出文件名（纯函数）。 */
        fun exportFileName(dateTag: String): String = "XQDK_语音诊断_$dateTag.md"
    }
}
