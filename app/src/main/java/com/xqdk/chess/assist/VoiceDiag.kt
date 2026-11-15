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
 * 配置变更、v1.2 朗读内容多选决策（类别/勾选态/清洗前后文本）、SAF 导出事件。
 *
 * 「一键导出」模型：日志先入内存会话缓冲（每条 [log] 以 synchronized 追加、立即可
 * 导出，写失败静默不拖垮主链路），点导出时由 [buildDocument] 一次性组装成带完整表头
 * 与设备/版本信息的 Markdown。v1.2 起主路径走 SAF 自选路径（[buildDocument] 供
 * Activity 复用），[export] 保留为写下载目录的兜底。上限 [MAX_LINES] 行防无限增长。
 *
 * 刷屏聚合（v1.2）：连续相同的 tag+detail 不新增行、只累加 [Entry.count]，导出时
 * count>1 渲染成「xxx（连续 N 次）」，避免「去重/打断/排队/onStart/onDone」高频刷屏。
 *
 * 开关 AssistConfig.voiceDiagEnabled（默认 true）：关闭时 [log] 不写任何行。
 */
object VoiceDiag {

    /** 会话缓冲行数上限（防无限增长；保留最近若干行） */
    const val MAX_LINES = 2000

    /** 语音模块稳定版本标识（我们自己的版本，不受 git describe 派生干扰） */
    const val VOICE_MODULE_VERSION = "1.2"

    /** 最近一次 [export] 导出文件路径（成功为可展示路径；失败/未导出为 null） */
    @Volatile
    var displayPath: String? = null
        private set

    private val lock = Any()

    /** 会话日志条目：count 用于把连续相同的 tag+detail 聚合成一行。 */
    data class Entry(val time: String, val tag: String, val detail: String, val count: Int = 1)

    private val entries = ArrayList<Entry>()

    /**
     * 追加一条语音链路事件。连续相同的 tag+detail 只累加计数不新增行（刷屏聚合）。
     * 追加后即成为可导出状态（写失败不会抛出、不影响朗读主链路）。
     */
    fun log(context: Context, tag: String, detail: String) {
        if (!AssistConfig(context).voiceDiagEnabled) return
        val time = synchronized(CLOCK) { CLOCK.format(Date()) }
        synchronized(lock) {
            val last = entries.lastOrNull()
            if (last != null && last.tag == tag && last.detail == detail) {
                // 连续重复：更新时间为最近一次、计数 +1，不新增行
                entries[entries.size - 1] = last.copy(count = last.count + 1, time = time)
            } else {
                entries.add(Entry(time, tag, detail, 1))
            }
            if (entries.size > MAX_LINES) {
                // 越界才裁剪：保留最近 MAX_LINES 行（与纯函数 trimToLimit 同语义）
                val kept = entries.subList(entries.size - MAX_LINES, entries.size).toList()
                entries.clear()
                entries.addAll(kept)
            }
        }
    }

    /** 清空当前会话缓冲（不删除已导出的文件） */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * 组装完整导出文档（v1.2）：取当前会话快照、刷屏聚合后逐行渲染，补齐设备/
     * 版本（经 [displayVersion] 修正）/SDK/导出时间，调纯函数 [buildExportDocument]。
     * 供 SAF 自选路径写入，也被兜底 [export] 复用。
     */
    fun buildDocument(context: Context): String {
        val snapshot = synchronized(lock) { entries.toList() }
        val rows = mergeConsecutive(snapshot).map { e ->
            formatRowWithCount(e.time, e.tag, e.detail, e.count)
        }
        val device = "${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）"
        val rawVer = try {
            com.xqdk.chess.BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            "?"
        }
        val ver = displayVersion(rawVer)
        val exportTime = synchronized(STAMP) { STAMP.format(Date()) }
        val doc = buildExportDocument(device, ver, Build.VERSION.SDK_INT, exportTime, rows)
        // 稳定语音模块版本行：不改纯函数 buildExportDocument，在文档头部信息区补一行
        return doc.replace(
            "- 版本：$ver\n",
            "- 语音模块版本：$VOICE_MODULE_VERSION\n- 版本：$ver\n"
        )
    }

    /**
     * 兜底导出：写公共 Download（API 29+ 走 MediaStore.Downloads；API 26-28 回退
     * 应用专属外部目录），无需存储权限。SAF 自选路径取消/写失败时由设置页回落调用。
     * 返回可展示路径；全流程 runCatching 包裹，失败返回 null。
     */
    fun export(context: Context): String? {
        var path: String? = null
        return try {
            val doc = buildDocument(context)
            val name = exportFileName(synchronized(FILE_STAMP) { FILE_STAMP.format(Date()) })

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

    /** 含计数行的格式化（纯函数）：count>1 在详情后追加「（连续 N 次）」，count<=1 原样。 */
    fun formatRowWithCount(time: String, tag: String, detail: String, count: Int): String =
        if (count > 1) formatRow(time, tag, "$detail（连续 $count 次）")
        else formatRow(time, tag, detail)

    /**
     * 刷屏聚合纯函数：连续相同的 tag+detail 合并为一条（count 累加、time 取最新），
     * 相邻不同则不聚合。count=1 的条目原样保留。供 JVM 单测覆盖。
     */
    fun mergeConsecutive(rows: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        for (e in rows) {
            val last = out.lastOrNull()
            if (last != null && last.tag == e.tag && last.detail == e.detail) {
                out[out.size - 1] = last.copy(count = last.count + e.count, time = e.time)
            } else {
                out.add(e.copy())
            }
        }
        return out
    }

    /**
     * 版本号展示修正（纯函数，历史兼容）：v1.4 及更早的标签是 voice-1.x，而旧版
     * build.gradle.kts 用 git describe --match "v*" 后 removePrefix("v")，把
     * voice-... 的首字母 v 一并削掉变成 oice-...。这里在日志侧补回 v，让旧设备导出的
     * 诊断日志仍显示正确版本。v1.5 起标签改用 cn-* 且构建脚本已改为 --match "cn-*"，
     * 不再产生 oice- 字符串；本函数保留仅为兼容历史日志与既有单测，其余原样返回。
     */
    fun displayVersion(raw: String): String =
        if (raw.startsWith("oice-")) "v$raw" else raw

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
        sb.append("# XQDK 增强功能诊断\n\n")
        sb.append("> 隐私提示：本文件仅记录播报（TTS）功能链路事件，不含识别帧、FEN、" +
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
    fun exportFileName(dateTag: String): String = "XQDK_播报诊断_$dateTag.md"
}
