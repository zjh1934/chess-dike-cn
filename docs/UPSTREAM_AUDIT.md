# 上游同步审计（文件级 · 带日期）

> **审计日期：2026-11-15（Asia/Shanghai）** · 审计人：本仓库维护助手
> 这份是可公开核验的「同步进度板」：镜像 / 汉化 fork 最典型的死法是**悄悄漏掉上游新功能**，
> 所以不写「我们会持续跟进」这种空话，直接把三方文件树摆出来数。
> 身份关系与合规红线见 [`../UPSTREAM.md`](../UPSTREAM.md)，差异化清单见 [`DIFFERENTIATION.md`](DIFFERENTIATION.md)。

## 1. 方法（可复跑，不依赖本机源码）

用 GitHub REST API 取三份 `recursive=1` 的 Git tree，按 **路径 + blob SHA** 比对（只比 `type=blob`）：

| 代号 | 仓库 / ref | 文件数 | 说明 |
| --- | --- | --- | --- |
| `base` | `Silva6c/chess-dike` @ `a0b17f6451` | 169 | **近似派生基线**：v3.1 稳定版发布提交（本仓库 `docs/技术报告.md` 自述 `versionName 3.1`） |
| `up34` | `Silva6c/chess-dike` @ `v3.4` | 188 | 上游当前正式版，2026-11-14 发布，HEAD `8fd66c94b9` |
| `ours` | `zjh1934/chess-dike-cn` @ `main` | 190 | 本分支 v1.27 发布线（审计时 `main`，发布标签 `cn-1.27`） |

派生出四组：

| 组 | 定义 | 数量 | 含义 |
| --- | --- | --- | --- |
| 上游演进 | `up34[p] != base[p]` | **62** | v3.1 → v3.4 上游自己动过的文件 |
| 本分支相对基线 | `ours[p] != base[p]` | **56** | 本分支动过的文件 |
| **漏同步候选** | 上游改了，但 `ours[p] == base[p]`（或本分支没有） | **24** | 上游有新东西，本分支还停在 v3.1 的文件 |
| 本分支自研 | 本分支改了，而上游没改 | **18** | 与 [`DIFFERENTIATION.md`](DIFFERENTIATION.md) 一一对应 |
| 两边都改 | 上游与本分支都改了同一路径 | **38** | **SHA 不同 ≠ 没吸收**，必须逐文件读内容才能判断 |

## 2. 漏同步候选 24 个（分类 + 本分支决策）

### A. v3.4「学习模式」整块 —— 10 个，**明确不吸收**

```
app/src/main/java/com/xqdk/chess/learn/LearnActivity.kt
app/src/main/java/com/xqdk/chess/learn/LearnBoardView.kt
app/src/main/java/com/xqdk/chess/learn/LearnEngine.kt
app/src/main/java/com/xqdk/chess/learn/LearnGameController.kt
app/src/main/java/com/xqdk/chess/learn/TrendChartView.kt
app/src/main/res/layout/activity_learn.xml
app/src/test/java/com/xqdk/chess/learn/LearnBoardViewTapTest.kt
app/src/test/java/com/xqdk/chess/learn/LearnGameControllerTest.kt
app/src/test/java/com/xqdk/chess/learn/LearnLaunchRobolectricTest.kt
app/src/test/java/com/xqdk/chess/learn/TrendChartViewRenderTest.kt
```

**决策：放弃同步（v1.5 拍板）**。理由：这是上游一整条独立产品线，与「点按出招 / 中文播报 / 统一开关页」零耦合；整套搬入等于把未在本机回归的大功能塞进包，风险远大于收益；学习模式属上游产品方向，用户应到[上游](https://github.com/Silva6c/chess-dike)获取。这也修正了 `UPSTREAM.md` 早先「未见对应代码」的猜测——现在是**有文件级证据的确认未同步**。

### B. 落地页 / 主题 / 视觉 —— 7 个，**部分待复核**

```
app/src/main/java/com/xqdk/chess/LauncherActivity.kt
app/src/main/java/com/xqdk/chess/assist/ui/OverlayTheme.kt
app/src/main/res/layout/activity_launcher.xml
app/src/main/res/drawable/bento_card.xml
app/src/main/res/drawable/bento_card_zhu.xml
app/src/main/res/drawable/main_text_btn_bg.xml
app/src/main/res/values/styles.xml
```

**决策**：Launcher / 落地页属上游品牌入口，本分支不改（避免被误读为官方版）；`OverlayTheme.kt` 与 `styles.xml` 列**下次同步优先复核**——它们影响悬浮窗主题配色，本分支「增强功能」页沿用了上游样式体系，上游改了配色而本分支没跟可能造成观感不一致。

### C. 棋规 —— 3 个，**最高优先复核**

```
app/src/main/java/com/xqdk/chess/gamelogic/Board.java
app/src/main/java/com/xqdk/chess/gamelogic/Rule.java
app/src/test/java/com/xqdk/chess/gamelogic/RulePortTest.java
```

**决策**：棋规正确性直接影响局面合法性校验、FEN 生成与支招，上游若在 v3.2~v3.4 修过棋规 bug，本分支停在 v3.1 会带着旧 bug 跑。**这是 24 个里唯一「应该尽快吸收」的一组**，下次同步时逐行 review 后再决定 cherry-pick。

### D. 上游 assist 测试更新 —— 4 个，**跟随对应代码评估**

```
app/src/test/java/com/xqdk/chess/assist/AnalysisParseTest.kt
app/src/test/java/com/xqdk/chess/assist/AssistLaunchRobolectricTest.kt
app/src/test/java/com/xqdk/chess/assist/OverlayChessViewTapTest.kt
app/src/test/java/com/xqdk/chess/assist/OverlayPanelViewRenderTest.kt
```

**决策**：这些是上游对既有模块的测试补充/调整，只改测试不改实现；等 C 组与「两边都改」里的模块复核完，一并决定是否并入。

## 3. 本分支自研 18 个（与差异化清单对应）

```
.github/workflows/cn-build.yml
UPSTREAM.md
app/src/main/java/com/xqdk/chess/assist/QuickRefresh.kt
app/src/main/java/com/xqdk/chess/assist/TapMode.kt
app/src/main/java/com/xqdk/chess/assist/VoiceDiag.kt
app/src/main/java/com/xqdk/chess/assist/VoicePrompt.kt
app/src/main/java/com/xqdk/chess/assist/VoiceSpeaker.kt
app/src/main/java/com/xqdk/chess/assist/ui/FeatureSwitchesActivity.kt
app/src/main/res/layout/feature_switches.xml
app/src/test/java/com/xqdk/chess/assist/FeatureSwitchesLinkTest.kt
app/src/test/java/com/xqdk/chess/assist/QuickRefreshTest.kt
app/src/test/java/com/xqdk/chess/assist/TapModeTest.kt
app/src/test/java/com/xqdk/chess/assist/VoiceDiagTest.kt
app/src/test/java/com/xqdk/chess/assist/VoicePromptTest.kt
docs/COLD_START.md
docs/DIFFERENTIATION.md
docs/RELEASE.md
docs/UPSTREAM_CONTRIB.md
```

`QuickRefresh.kt` / `Voice*.kt` 是本分支 v1.0~v1.3 起新增、上游从未有过的模块；`TapMode.kt` / `FeatureSwitchesActivity.kt` 是 v1.5 新增。这 18 个文件是本分支「不是汉化」的文件级证据。

## 4. 两边都改 38 个（**待语义复核**，不是结论）

```
.github/dependabot.yml
.github/workflows/android.yml
.github/workflows/codeql.yml
.gitignore
DESIGN_BOUNDARIES.md
README.md
THIRD-PARTY-NOTICES.md
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/assets/help.html
app/src/main/java/com/xqdk/chess/assist/AnalysisEngine.kt
app/src/main/java/com/xqdk/chess/assist/AnalysisModels.kt
app/src/main/java/com/xqdk/chess/assist/AppUpdateChecker.kt
app/src/main/java/com/xqdk/chess/assist/AssistConfig.kt
app/src/main/java/com/xqdk/chess/assist/DiagLogWriter.kt
app/src/main/java/com/xqdk/chess/assist/PonderCache.kt
app/src/main/java/com/xqdk/chess/assist/RecognitionTypes.kt
app/src/main/java/com/xqdk/chess/assist/ScreenAssistService.kt
app/src/main/java/com/xqdk/chess/assist/ui/AssistActivity.kt
app/src/main/java/com/xqdk/chess/assist/ui/AssistDotGridView.kt
app/src/main/java/com/xqdk/chess/assist/ui/OverlayChessView.kt
app/src/main/java/com/xqdk/chess/assist/ui/OverlayPanelView.kt
app/src/main/java/com/xqdk/chess/assist/ui/RiverLineView.kt
app/src/main/java/com/xqdk/chess/views/WebviewActivity.kt
app/src/main/res/layout/assist_activity.xml
app/src/main/res/layout/overlay_panel.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/test/java/com/xqdk/chess/assist/AppUpdateCheckerTest.kt
app/src/test/java/com/xqdk/chess/assist/AssistServiceTeardownTest.kt
app/src/test/java/com/xqdk/chess/assist/PonderCacheTest.kt
build.gradle.kts
docs/README.md
docs/assist-helper.md
docs/技术报告.md
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
settings.gradle.kts
```

**必须说清**：SHA 不同只说明「两边都动过」，**不能推出「本分支漏了上游的修复」**——本分支在这些文件上本来就有自己的改动（播报链路、点按闸口、开关页、构建脚本改 `cn-*` 标签等）。下次同步时按下面的优先级逐文件读 diff：

1. `ScreenAssistService.kt` / `OverlayPanelView.kt` / `AssistConfig.kt`（主线逻辑，上游可能有识别或性能修复）
2. `RecognitionTypes.kt` / `AnalysisEngine.kt` / `AnalysisModels.kt`（识别确认与引擎解析）
3. `app/build.gradle.kts` / `gradle/libs.versions.toml` / `build.gradle.kts`（依赖与构建口径）
4. `AndroidManifest.xml` / `backup_rules.xml` / `data_extraction_rules.xml`（合规与权限）
5. 其余 UI / 文档 / 测试

## 5. 口径与局限（别把这份表当全知）

1. **基线是近似值**：`a0b17f6451`（v3.1 发布提交）来自本仓库自述版本号，本分支精确派生点**未确认**。若派生点在该提交之后，「自研 18 / 两边都改 38」会有噪声（上游 v3.1→v3.2 之间的小提交会被算进本分支改动）。
2. **只比路径 + blob SHA，未读文件内容**：能确定「哪些文件变了」，不能确定「改动语义是否被吸收」。第 2、3 节的决策是**基于文件路径与上游 release 主题的判断**，不是逐行 diff 的结论。
3. **版权资产不参与比对**：上游 ONNX 识别模型与桔库开局库两边都被 `.gitignore` 排除，不在 tree 里；本分支按设计自动降级，见 `THIRD-PARTY-NOTICES.md` §5。
4. **时间切片**：上游 v3.4 发布于 2026-11-14，本审计 2026-11-15 跑的；上游之后有新提交需重跑本表并更新日期。
5. **本表不替代回归测试**：任何吸收动作都要在真机上回归「点按出招 / 播报 / 覆盖安装」，CI 只保证编译与单测。

## 6. 复跑方法

```
GET /repos/Silva6c/chess-dike/commits/{基线ref}          → commit.tree.sha
GET /repos/Silva6c/chess-dike/commits/vX.Y               → commit.tree.sha
GET /repos/zjh1934/chess-dike-cn/commits/main            → commit.tree.sha
GET /repos/{repo}/git/trees/{tree_sha}?recursive=1       → 取所有 type=blob 的 path + sha
```

按第 1 节的四组定义重算，更新第 1 节数字、第 2 节清单与下面的状态记录，并在 `UPSTREAM.md` 的版本审计表里同步结论。

## 7. 状态记录

| 日期 | 基线 → 上游 | 上游演进 | 漏同步 | 决策 | 未核验项 |
| --- | --- | --- | --- | --- | --- |
| 2026-11-15 | `a0b17f6`(v3.1) → `v3.4` | 62 文件 | 24 文件 | 学习模式 10 个放弃；落地页 7 个部分待复核；棋规 3 个优先吸收；测试 4 个跟随 | 38 个「两边都改」的逐行语义 diff；本分支精确派生点 |
