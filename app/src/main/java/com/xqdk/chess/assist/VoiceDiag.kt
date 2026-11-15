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
 * - 配置快照（版本 / 包名 / 不点棋盘是否自动识别 + 点棋盘动作 / 各开关 / 播报内容掩码）
 * - 面板动作（点棋盘是否派发到服务、当时档位与闸口状态）
 * - 点按出招会话（单帧直出启动 / 拒绝原因 / 首帧可用 / 连点取代 / 异常卡住收场）
 * - **点按小票**：每次点按一行总账（耗时 / 结果 / 识别次数 / 拦下次数 / 引擎 / 档位）
 * - **识别结果**：每轮一行（引擎 / 耗时 / 认出没认出 / 合法否 / 失败原因），
 *   v1.6 只记「这帧为啥被拦」，认出来没有全靠反推
 * - 识别闸口（录屏启动 / 释放 / 自动档常开 / 点按档静止态）、**录屏停止**（来源区分：
 *   主页一键关闭 / 悬浮窗关闭 / MediaProjection onStop 手动或系统结束）
 * - 识别帧（丢弃原因去重：闸口关 / 停稳等待 / 节拍器退避 / 引擎未就绪 → 恢复放行；
 *   单帧会话内在途帧忽略）
 * - 识别引擎（ONNX 加载成功 / 失败回退 YOLO）
 * - 棋盘确认（单帧直出 / SAME_BOARD / NEW_BOARD，不记 FEN 具体内容）
 * - 支招调度（开局库 / 预判缓存 / 引擎搜索 / 未就绪）
 * - 中文播报（TTS 构造、引擎枚举、onInit、setLanguage、speak、UtteranceProgress、
 *   去重/排队/打断、试听、播报内容多选决策）
 * - 状态文案（所有 setStatus 变化，去重且带会话/闸口标志）、配置变更、
 *   服务 onCreate/onDestroy、SAF 导出
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
    const val VOICE_MODULE_VERSION = "1.13"

    /** 最近一次 [export] 导出文件路径（成功为可展示路径；失败/未导出为 null） */
    @Volatile
    var displayPath: String? = null
        private set

    private val lock = Any()

    /** 会话日志条目：count 用于把连续相同的 tag+detail 聚合成一行。 */
    data class Entry(val time: String, val tag: String, val detail: String, val count: Int = 1)

    private val entries = ArrayList<Entry>()

    /**
     * v1.7 高频标签跨交错聚合：v1.6 真机日志 614 行里「决策 + 去重」占 61%，
     * 因为两者交替出现，旧的「连续相同才合并」完全失效。窗口内同 tag+detail
     * 合并到**已有那一行**并累加 count，导出仍渲染「（连续 N 次）」。
     * 只合并纯状态类高频事件；点按会话、闸口、识别结果、点按小票、录屏停止等
     * 关键事件一律逐条保留，排障时间线不受影响。
     */
    const val COMPACT_WINDOW_MS = 5000L
    private val COMPACT_TAGS = setOf("决策", "去重", "状态文案", "识别帧", "排队", "打断")

    /**
     * v1.10：诊断**跨会话落盘**。之前 entries 只存进程内存，用户手动关录屏/划掉任务
     * 后服务重建，那次「乱支招」的点按链路全丢 —— 真机导出的日志里只剩 onCreate、
     * 关闭、再 onCreate、打开设置、导出，一条点棋盘都没有，排障价值为零。
     *
     * 现在把关键事件同步 append 到应用私有文件（每行 time\ttag\tdetail），
     * 导出时把磁盘历史与本次内存快照合并。高频状态类（COMPACT_TAGS）不入盘，
     * 一次点按约 15 行，单行 append 不拖慢识别/支招延迟。
     */
    private const val PERSIST_FILE = "voice_diag_persist.tsv"
    private const val PERSIST_MAX_LINES = 600

    /**
     * 纯函数供单测：合并「磁盘历史 + 本次内存」。
     * 按 (time,tag,detail) 去重即可 —— 磁盘在前、内存在后本来就是时间顺序，
     * 不按 HH:mm:ss 字符串排序（跨天会乱）。**不按 (tag,detail) 折叠**，否则
     * 多次点按的同内容「敌我判定」会被压成一条，看不出哪次判反了。
     */
    internal fun mergeSessionHistory(disk: List<Entry>, memory: List<Entry>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        (mergeConsecutive(disk) + mergeConsecutive(memory)).forEach { e ->
            val k = e.time + "\u0000" + e.tag + "\u0000" + e.detail
            if (seen.add(k)) out.add(e)
        }
        return out.takeLast(PERSIST_MAX_LINES)
    }

    private fun persistLine(context: Context, time: String, tag: String, detail: String) {
        if (tag in COMPACT_TAGS) return
        try {
            val f = java.io.File(context.filesDir, PERSIST_FILE)
            val line = listOf(time, tag, detail).joinToString("\t") {
                it.replace("\t", " ").replace("\r", " ").replace("\n", " ")
            } + "\n"
            java.io.FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {
            // 写失败不影响主链路（诊断是旁路）
        }
    }

    /** 读回落盘历史；超过上限就重写裁剪，避免文件无限增长 */
    private fun readPersisted(context: Context): List<Entry> {
        return try {
            val f = java.io.File(context.filesDir, PERSIST_FILE)
            if (!f.exists()) return emptyList()
            val lines = f.readLines(Charsets.UTF_8)
            val kept = if (lines.size > PERSIST_MAX_LINES) {
                val tail = lines.takeLast(PERSIST_MAX_LINES)
                try { f.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8) } catch (_: Throwable) {}
                tail
            } else lines
            kept.mapNotNull { raw ->
                val c = raw.split("\t", limit = 3)
                if (c.size < 3) null else Entry(c[0], c[1], c[2], 1)
            }
        } catch (_: Throwable) { emptyList() }
    }
    private val lastByTag = HashMap<String, Entry>()
    private val lastTsByTag = HashMap<String, Long>()

    /** 纯函数供单测：该标签是否走跨交错聚合 */
    internal fun isCompactTag(tag: String): Boolean = tag in COMPACT_TAGS

    /**
     * 追加一条功能链路事件。两级刷屏聚合：
     * 1. 连续相同的 tag+detail 直接累加最后一行（v1.2 原有）；
     * 2. v1.7 新增：高频标签在 [COMPACT_WINDOW_MS] 窗口内，即使中间夹了别的事件，
     *    同 tag+detail 也合并回**已有那一行**并累加 count（决策/去重交替刷屏的关键）。
     * 追加后即成为可导出状态（写失败不会抛出、不影响主链路）。
     */
    fun log(context: Context, tag: String, detail: String) {
        if (!AssistConfig(context).voiceDiagEnabled) return
        val time = synchronized(CLOCK) { CLOCK.format(Date()) }
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val last = entries.lastOrNull()
            if (last != null && last.tag == tag && last.detail == detail) {
                // 连续重复：更新时间为最近一次、计数 +1，不新增行
                val merged = last.copy(count = last.count + 1, time = time)
                entries[entries.size - 1] = merged
                if (isCompactTag(tag)) {
                    lastByTag[tag] = merged
                    lastTsByTag[tag] = nowMs
                }
            } else if (isCompactTag(tag)) {
                val prev = lastByTag[tag]
                val idx = if (prev != null) entries.indexOf(prev) else -1
                if (prev != null && idx >= 0 && prev.tag == tag && prev.detail == detail &&
                    nowMs - (lastTsByTag[tag] ?: 0L) < COMPACT_WINDOW_MS) {
                    // 交错重复：合并回原行（该行仍位于首次发生的位置），计数 +1
                    val merged = prev.copy(count = prev.count + 1, time = time)
                    entries[idx] = merged
                    lastByTag[tag] = merged
                    lastTsByTag[tag] = nowMs
                } else {
                    val e = Entry(time, tag, detail, 1)
                    entries.add(e)
                    lastByTag[tag] = e
                    lastTsByTag[tag] = nowMs
                }
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
        // v1.10：写盘放在锁外，单行 append，不阻塞点按链路
        persistLine(context, time, tag, detail)
    }

    /** 清空当前会话缓冲（不删除已导出的文件）；v1.10 同时清跨会话历史 */
    fun clear(context: Context) {
        synchronized(lock) {
            entries.clear()
            lastByTag.clear()
            lastTsByTag.clear()
        }
        // v1.10：用户点「清空」应该是真清空，连跨会话历史一起删
        try { java.io.File(context.filesDir, PERSIST_FILE).delete() } catch (_: Throwable) {}
    }

    /**
     * 组装完整导出文档（v1.2）：取当前会话快照、刷屏聚合后逐行渲染，补齐设备/
     * 版本（经 [displayVersion] 修正）/SDK/导出时间，调纯函数 [buildExportDocument]。
     * 供 SAF 自选路径写入，也被兜底 [export] 复用。
     */
    fun buildDocument(context: Context): String {
        val snapshot = synchronized(lock) { entries.toList() }
        // v1.10：把服务重启前那次会话的点按链路一起带出来
        val rows = mergeSessionHistory(readPersisted(context), snapshot).map { e ->
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
        // v1.8：头部写「导出时」的两个独立维度组合，不再只写一个档位名；
        // 与「服务 onCreate」快照不同属正常（运行中改过配置），见下方说明行
        val tapModeLabel = try {
            val c = AssistConfig(context)
            TapMode.summary(c.tapMode, c.autoTapEnabled)
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
        "点按链路（①触发→②单帧识别(合法门槛)→③建局(我方=屏幕下方)→④支招→⑤定着播报一次）/ 单帧合法性门槛 / 定着兜底必给一招 / 敌我判定（我方/该谁走/轮次来源/朝向锁定）/ 我方选择（面板红黑互斥锁定·跨对局保持）/ " +
            "点按出招会话 / 点按小票 / 识别结果 / 识别闸口 / 识别帧丢弃原因 / 识别引擎 / 棋盘确认 / " +
            "支招调度 / 中文播报 / 状态文案 / 面板动作 / 录屏停止 / 配置变更 / 导出 / 跨会话历史"

    /** 文档头部信息区（纯函数）：版本、包名、诊断模块版本、识别设置、覆盖范围 */
    fun metaBlock(ver: String, tapModeLabel: String): String =
        "- 诊断模块版本：$VOICE_MODULE_VERSION\n" +
            "- 版本：$ver\n" +
            "- 包名：com.xqdk.chess\n" +
            "- 识别方式：$tapModeLabel\n" +
            "- 识别设置说明：导出时为当前配置；服务启动态见「服务onCreate」行\n" +
            "- 日志范围：含**跨会话历史**（服务重启/关录屏前的点按链路也会带出，v1.10 起落盘）\n" +
            "- 敌我排障：看「敌我判定」行（我方=红/黑、该谁走、轮次来源、来源是否=悬浮面板手动锁定）\n" +
            "- 换盘串色排障：看「我方选择」行（面板点选后跨对局保持；不清掉手动锁定的话自动判定翻不动它）\n" +
            "- 乱指挥排障：看「识别结果」的判定列与「⑤ 语音播报」——单帧合法才支招，一次点按只念一招\n" +
            "- 延迟排障：看「点按小票」耗时（单帧识别 ms 级 + 引擎定着约 1.2s，超过 3s 即异常）\n" +
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
