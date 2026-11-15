package com.xqdk.chess.assist

/**
 * v1.10：点一下棋盘时，从「上一局面 → 这一帧」客观推断 **该谁走**，并**锁定我方阵营**。
 *
 * 纯 JVM、零 Android 依赖，CI 链路自检可直接对生产逻辑下断言。
 *
 * ## 病根（真机反馈「敌我不分 / 指挥我去动别人的棋」）
 *
 * v1.7 起做单帧直出时，为了「快」把 BoardTracker 的差分推断整个砍掉，
 * `applyTapDirectBoard` 写死 `val redGo = tracker.redGo` —— 轮次永远沿用服务启动的
 * 默认值 **true（红先）**。走了几步之后轮次早该交替了，它还按「红先」写进 FEN。
 *
 * 同时 `mySideIsRed()` 每帧直接采信单帧识别的 orientation（日志里 `识别引擎=yolo`，
 * 备用识别对红黑上下容易判反），于是「轮到我方走」由一个会翻的朝向决定。
 * 渲染里本来有「送子防线」`fromIsOurs()`（起点必须是我方棋子），但它校验用的
 * 正是同一个判反的 myRed —— 防线跟着一起反，红方（对方）的着法就这么被当成建议
 * 画了箭头、念了出来。
 *
 * ## 修法
 *
 * 1. `inferRedGo`：照抄 BoardTracker 第 194 行口径，用 `movedSide` 客观推断刚走的是
 *    哪一方 → 下一手是谁。**不含「我方是谁」的任何假设**，用户自己走完再点也不会反。
 * 2. `resolveMySide`：我方阵营**一局只认一次**（首次建局按屏幕朝向定），之后不随
 *    每帧识别结果翻；只有满 32 子的新对局才允许重定（换边）。
 * 3. 两件事分开后，`myTurn = redGo == mySideRed()` 才有意义；再配一条硬防线：
 *    建议着法的起点必须同时是「我方棋子」且「当前该走方」，否则一律不显示不朗读。
 */
object TapTurnInference {

    data class TurnOutcome(
        /** 当前该谁走（true=红方走），决定写进 FEN 的 w/b */
        val redGo: Boolean,
        /** 人话来源，写进诊断 */
        val source: String,
        /** 差分没推出来：判定不确凿，禁止据此瞎出招 */
        val uncertain: Boolean,
    )

    /**
     * 客观推断该谁走。
     *
     * @param prev 上一次点棋盘确认的局面（canonical，红恒在 y=9）；首次为 null
     * @param current 这一帧认出的局面
     * @param fallbackRedGo 推不出时沿用的轮次（tracker.redGo）
     */
    fun inferRedGo(
        prev: Array<IntArray>?,
        current: Array<IntArray>,
        fallbackRedGo: Boolean,
    ): TurnOutcome {
        // 首次：开局红先
        if (prev == null) {
            return TurnOutcome(true, "首次开局·红先", false)
        }
        // 满 32 子且与上一局面不同 = 换了新对局：红先
        if (AssistBoard.matchStartCount(current) >= 32 &&
            !AssistBoard.equal(prev, current)
        ) {
            return TurnOutcome(true, "满子新对局·红先", false)
        }
        val moved = AssistBoard.movedSide(prev, current)
        if (moved != null) {
            // 与 BoardTracker.onFrameInner 完全一致：
            // moved=1（刚走的是红）→ 下一手黑走；moved=0（刚走的是黑）→ 下一手红走
            return TurnOutcome(
                redGo = moved == 0,
                source = "差分推断·刚走的是${if (moved == 1) "红" else "黑"}方",
                uncertain = false,
            )
        }
        // 识别抖动 / 两帧差异不像一次合法走子：保守沿用，但标记不确凿
        return TurnOutcome(fallbackRedGo, "差分推不出·沿用旧值（不确凿）", true)
    }

    /**
     * 我方阵营：一局只认一次，不被单帧朝向带翻。
     *
     * @param locked 本局已锁定的我方（null=还没定过）
     * @param redIsBottom 这一帧识别出的朝向（STANDARD=红在屏幕下方=我方执红）
     * @param isNewGame 是否满 32 子新对局（换局允许重定，可能换边）
     */
    fun resolveMySide(
        locked: Boolean?,
        redIsBottom: Boolean,
        isNewGame: Boolean,
    ): Boolean = when {
        locked == null -> redIsBottom          // 首次建局：按屏幕朝向定
        isNewGame -> redIsBottom               // 新对局：允许重定
        else -> locked                         // 同一局：锁死，不随单帧识别翻
    }

    /** 人话一行，给「点按链路」和「敌我判定」诊断直接用 */
    fun describe(redGo: Boolean, mySideRed: Boolean, turn: TurnOutcome): String =
        "我方=${if (mySideRed) "红" else "黑"} 该谁走=${if (redGo) "红" else "黑"}" +
            "（${if (redGo == mySideRed) "轮到我方→可出招" else "轮到对方→不出招"}）" +
            " 轮次来源=${turn.source}" +
            if (turn.uncertain) " ⚠不确凿" else ""
}
