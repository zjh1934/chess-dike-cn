package com.xqdk.chess.assist

/**
 * 语音提示的「是否朗读、朗读什么」决策——**确定性纯函数**，不依赖 Android，
 * 可直接在 JVM 单元测试里覆盖（见 VoicePromptTest）。
 *
 * 输入三要素：支招招数（MultiPV 1-3）、语音开关状态、界面「建议」文案；
 * 输出：[Decision.shouldSpeak] 是否朗读 + [Decision.text] 朗读文本（仅着法本身）。
 */
object VoicePrompt {

    data class Decision(val shouldSpeak: Boolean, val text: String)

    /**
     * @param moveCount      支招招数（1/2/3，对应悬浮窗「N变」/引擎 MultiPV），仅 1 招朗读
     * @param enabled        语音提示开关，关闭时完全不朗读
     * @param suggestionText 界面建议文案原文（如「建议 炮二平五 ✓」），以代码真实字符串为准
     */
    fun resolve(moveCount: Int, enabled: Boolean, suggestionText: String): Decision {
        if (!enabled || moveCount != 1) return Decision(false, "")
        val move = moveText(suggestionText)
        return if (move.isEmpty()) Decision(false, "") else Decision(true, move)
    }

    /**
     * 从界面建议文案中取出「建议」后面的着法文字本身，不含「建议」二字。
     *
     * - 只认我方着法建议文案（以「建议」或「开局库」开头）；「引擎思考中…」
     *   「轮次可能识别有误」「若对方走X」等一律返回空 → 不朗读；
     * - 剥离界面装饰后缀（成熟 ✓ / 暂定 / 预判β，可能叠加）：使「暂定 → 定着」
     *   过程中朗读文本恒为同一着法，配合上层去重做到「同一条着法不重复读」；
     * - 兼容真实代码里的「建议 炮二平五」（空格）与需求举例的「建议：炮二平五」（冒号），
     *   并兼容「开局库 炮二平五」「开局库备选2 炮二平五」等同源文案。
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

        if (!s.startsWith("建议") && !s.startsWith("开局库")) return ""

        return s
            .replaceFirst(Regex("^开局库"), "")
            .replaceFirst(Regex("^建议"), "")
            .replaceFirst(Regex("^备选\\d+"), "")
            .trim(' ', '\u3000', '：', ':')
    }
}
