package com.xqdk.chess.assist

/**
 * 语音提示的「是否朗读、朗读什么」决策——**确定性纯函数**，不依赖 Android，
 * 可直接在 JVM 单元测试里覆盖（见 VoicePromptTest）。
 *
 * 输入三要素：支招招数（MultiPV 1-3）、语音开关状态、界面「建议」文案；
 * 输出：[Decision.shouldSpeak] 是否朗读 + [Decision.text] 朗读文本（仅着法本身）。
 *
 * v1.2：
 * - [moveText] 追加剥离半角括号 UCCI 坐标（如 "(h2e2)"），只读纯中文着法；
 * - 新增「朗读内容多选」：[Category] 位掩码 readMask，按候选文案类别决定读哪些，
 *   默认只勾 SUGGESTION|BOOK（与 v1.1「只读建议/开局库着法」一致）；
 * - [resolve] 用 Kotlin 默认参数向后兼容，旧 3 参数调用语义完全等价于 v1.1。
 */
object VoicePrompt {

    /** 朗读内容类别，每个占一个 bit 构成 readMask；NONE 不占 bit。 */
    enum class Category(val bit: Int, val label: String) {
        SUGGESTION(1, "支招建议"),
        BOOK(2, "开局库着法"),
        OPPONENT_PLAN(4, "对方回合预案"),
        EVALUATION(8, "态势评估"),
        STATUS(16, "状态与异常提示"),
        NONE(0, "无"),
    }

    /** 默认勾选：仅支招建议 + 开局库着法（保持 v1.1 行为：支两招三招/预案/状态都不读）。 */
    val DEFAULT_MASK: Int = (Category.SUGGESTION.bit or Category.BOOK.bit)

    data class Decision(
        val shouldSpeak: Boolean,
        val text: String,
        /** 识别出的内容类别；默认 NONE，旧断言只取 shouldSpeak/text 不受影响。 */
        val category: Category = Category.NONE,
        /** 不朗读原因（成功时为 ""）；默认 ""，旧断言不受影响。 */
        val reason: String = "",
    )

    /** 半角括号 UCCI 坐标，如 (h2e2)/(i0h0)/(g3g4)/(b0c2)/(f0e1)。
     *  用半角空格而非 \s，避免吞掉 OPPONENT_PLAN 里作为行分隔符的换行。 */
    private val COORD_PAREN = Regex(""" *\([a-i][0-9][a-i][0-9]\) *""")

    /** 裸 UCCI 兜底：MoveChinese 解析失败时 describe 原样返回如 "h2e2"。 */
    private val COORD_BARE = Regex("""\b[a-i][0-9][a-i][0-9]\b""")

    /** 一组类别 → 位掩码（NONE 的 bit=0，天然被忽略）。 */
    fun maskOf(categories: Set<Category>): Int =
        categories.fold(0) { acc, c -> acc or c.bit }

    /** 位掩码 → 类别集合（忽略 NONE 与未定义 bit）。 */
    fun categoriesOf(mask: Int): Set<Category> =
        Category.values().filter { it != Category.NONE && (it.bit and mask) != 0 }.toSet()

    /**
     * 按候选文案前缀归类；空文本归 NONE。EVALUATION 由 advantageText 触发，
     * 属于附加朗读内容，不由 classify 产出。
     */
    fun classify(candidateText: String): Category {
        val s = candidateText.trim()
        if (s.isEmpty()) return Category.NONE
        return when {
            s.startsWith("建议") || s.startsWith("备选") -> Category.SUGGESTION
            s.startsWith("开局库") -> Category.BOOK
            s.startsWith("若对方走") -> Category.OPPONENT_PLAN
            else -> Category.STATUS
        }
    }

    /**
     * 决策主入口。旧 3 参数调用走默认 advantageText="" / readMask=DEFAULT_MASK，
     * 语义与 v1.1 完全一致（只读建议/开局库着法）。
     *
     * 决策链：门禁(!enabled / 非支一招 / readMask==0) → classify → 查 readMask 是否
     * 含该类别 bit → 生成朗读文本 → EVALUATION 勾选时拼接 advantageText。
     */
    fun resolve(
        moveCount: Int,
        enabled: Boolean,
        suggestionText: String,
        advantageText: String = "",
        readMask: Int = DEFAULT_MASK,
    ): Decision {
        if (!enabled) return Decision(false, "", Category.NONE, "总开关关闭")
        if (moveCount != 1) return Decision(false, "", Category.NONE, "非支一招(moveCount=$moveCount)")
        if (readMask == 0) return Decision(false, "", Category.NONE, "未勾选任何朗读内容")

        val category = classify(suggestionText)
        if (category == Category.NONE) return Decision(false, "", Category.NONE, "空文本")
        if ((category.bit and readMask) == 0) {
            return Decision(false, "", category, "类别未勾选：${category.label}")
        }

        val main = when (category) {
            Category.SUGGESTION, Category.BOOK -> moveText(suggestionText)
            Category.OPPONENT_PLAN -> planText(suggestionText)
            Category.STATUS -> statusText(suggestionText)
            else -> ""
        }
        if (main.isEmpty()) return Decision(false, "", category, "清洗后文本为空")

        var text = main
        if ((Category.EVALUATION.bit and readMask) != 0 && advantageText.isNotBlank()) {
            val ev = evaluationText(advantageText)
            if (ev.isNotEmpty()) text = "$main，$ev"
        }
        return Decision(true, text, category, "")
    }

    /** 去掉候选文案里的 UCCI 坐标（全部替换），合并多余空格后 trim。 */
    fun stripCoordinates(s: String): String {
        var t = COORD_PAREN.replace(s, " ")
        t = COORD_BARE.replace(t, " ")
        return t.replace(Regex(""" {2,}"""), " ").trim()
    }

    /** 对方回合预案：「若对方走X\n我方应Y」→ 去两个坐标、换行压成「，」。 */
    fun planText(suggestionText: String): String {
        var t = suggestionText.replace(Regex("""[\r\n]+"""), "，")
        t = stripCoordinates(t)
        t = t.replace(Regex("""\s*，\s*"""), "，")
        return t.trim(' ', '，', '\u3000')
    }

    /** 状态与异常提示：换行压成「，」、去掉省略号「…」。 */
    fun statusText(suggestionText: String): String {
        var t = suggestionText.replace(Regex("""[\r\n]+"""), "，")
        t = t.replace("…", "")
        t = t.replace(Regex("""\s*，\s*"""), "，")
        return t.trim(' ', '，', '\u3000', '…')
    }

    /** 态势评估：清洗多余空格；「红方优势 32 深 20」→「红方优势 32，深 20」。 */
    fun evaluationText(advantageText: String): String {
        val t = advantageText.trim().replace(Regex("""\s{2,}"""), " ")
        return t.replace(Regex("""\s+深\s+(\d+)""")) { m -> "，深 ${m.groupValues[1]}" }
    }

    /**
     * 从界面建议文案取出着法本身（不含「建议/开局库/备选N」前缀与 ✓/（暂定）/
     * （预判β）装饰后缀），v1.2 追加剥离半角括号 UCCI 坐标，只读纯中文着法。
     *
     * 只认我方着法建议文案；「引擎思考中…」「轮次可能识别有误」「若对方走X」等
     * 一律返回空（这些类别在 [resolve] 里由 [planText]/[statusText] 单独清洗）。
     */
    fun moveText(suggestionText: String): String {
        var s = suggestionText.trim()
        if (s.isEmpty()) return ""

        while (true) {
            val prev = s
            s = s.removeSuffix("✓").trim()
                .removeSuffix("（暂定）").trim()
                .removeSuffix("（预判β）").trim()
            if (s == prev) break
        }
        if (s.isEmpty()) return ""

        if (!s.startsWith("建议") && !s.startsWith("开局库") && !s.startsWith("备选")) return ""

        return stripCoordinates(
            s.replaceFirst(Regex("^开局库"), "")
                .replaceFirst(Regex("^建议"), "")
                .replaceFirst(Regex("^备选\\d+"), "")
                .trim(' ', '\u3000', '：', ':')
        )
    }
}
