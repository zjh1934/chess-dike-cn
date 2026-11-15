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
 * 增强功能诊断日志（**独立通道**）：v1.5 起覆盖本分支相对上游的全部改动链路，
 * 不再只记 TTS 事件——用户实测「点棋盘用不了」时旧日志只有 3 行语音事件，
 * 完全看不出点按、闸口、识别、确认、支招哪一步断了，没有参考价值。
 *
 * 记录项：
 * - 配置快照（版本 / 包名 / 识别方式档位 / 各开关 / 播报内容掩码）
 * - 面板动作（点棋盘是否派发到服务、当时档位与闸口状态）
 * - 点按出招会话（启动 / 拒绝原因 / 首帧可用 / 完成 / 超时兜底支招）
 * - 识别闸口（录屏启动 / 释放 / 自动档常开 / 点按档静止态）
 * - 识别帧（丢弃原因去重：闸口关 / 停稳等待 / 节拍器退避 / 引擎未就绪 → 恢复放行）
 * - 识别引擎（ONNX 加载成功 / 失败回退 YOLO）
 * - 棋盘确认（SAME_BOARD / NEW_BOARD，不记 FEN 具体内容）
 * - 支招调度（开局库 / 预判缓存 / 引擎搜索 / 未就绪）
 * - 中文播报（TTS 构造、引擎枚举、onInit、setLanguage、speak、UtteranceProgress、
 *   去重/排队/打断、试听、播报内容多选决策）
 * - 状态文案（所有 setStatus 变化，去重）、配置变更、服务 onCreate/onDestroy、SAF 导出
 *
 * 隐私边界：只记**功能链路事件与配置状态**，绝不记录识别帧像素、FEN 具体内容、
 * 对局记录、引擎搜索 PV 明细或性能数据——与性能诊断 DiagLogWriter 完全分离、不复用。
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

    /** 增强功能诊断模块稳定版本标识（我们自己的版本，不受 git describe 派生干扰）。
     *  常量名沿用 v1.2 的 VOICE_MODULE_VERSION 以兼容既有单测与导出文档字段。 */
    const val VOICE_MODULE_VERSION = "1.5"

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
        val tapModeLabel = try {
            TapMode.labelOf(AssistConfig(context).tapMode)
        } catch (_: Throwable) { "?" }
        val doc = buildExportDocument(device, ver, Build.VERSION.SDK_INT, exportTime, rows)
        // 头部信息区补全：不改纯函数 buildExportDocument（单测锁定其字段），
        // 在此把诊断模块版本 / 包名 / 识别方式 / 覆盖范围一起补进 - 版本 行
        return doc.replace("- 版本：$ver\n", metaBlock(ver, tapModeLabel))
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

    /** 诊断覆盖说明（纯函数）：写进文档头部与 onCreate 快照，一眼看清这份日志能查什么 */
    fun scopeLine(): String =
        "点按出招会话 / 识别闸口 / 识别帧丢弃原因 / 识别引擎 / 棋盘确认 / " +
            "支招调度 / 中文播报 / 状态文案 / 面板动作 / 配置变更 / 导出"

    /** 文档头部信息区（纯函数）：版本、包名、诊断模块版本、识别方式、覆盖范围 */
    fun metaBlock(ver: String, tapModeLabel: String): String =
        "- 诊断模块版本：$VOICE_MODULE_VERSION\n" +
            "- 版本：$ver\n" +
            "- 包名：com.xqdk.chess\n" +
            "- 识别方式：$tapModeLabel\n" +
            "- 诊断覆盖：${scopeLine()}\n"

    /** 组装完整导出文档：标题 + 隐私提示 + 设备/版本信息 + 表头 + 全部行（纯函数）。 */
    fun buildExportDocument(
        device: String, version: String, sdk: Int, exportTime: String, rows: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("# XQDK 增强功能诊断\n\n")
        sb.append("> 隐私提示：记录增强功能的功能链路事件与配置状态（${scopeLine()}），" +
                "不含识别帧像素、FEN 具体内容、对局记录、引擎搜索 PV 明细或性能数据。\n\n")
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
    fun exportFileName(dateTag: String): String = "XQDK_增强功能诊断_$dateTag.md"
}
