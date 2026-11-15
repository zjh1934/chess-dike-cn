**v{{VER}}** · 悬浮面板「我方 红/黑」互斥锁定，换盘不串色；包名与签名未变，可直接覆盖安装。

安装包就在下面 **Assets** 区，点开即可下载（不用往下翻）：

- `armv8-dotprod-release.apk` — **推荐**，新机器
- `armv8-release.apk` — 老机器兼容版

<details>
<summary>展开看这一版改了什么</summary>

- 悬浮面板原「哈希」按钮改为「我方 红 / 黑」，点一下切到另一色并锁定；选黑不能同时选红，跨对局、重启后都保持
- 解决「第一盘黑、第二盘红仍按黑方支招」
- 哈希固定默认 256 MB，不再可点；开关只在悬浮面板，不在设置页
- 单帧直出、合法性门槛、起点双保险、一次点按只念一招、延迟 1.5～2.5 秒，全部保留

</details>

完整更新说明 / 历史版本 / 上游同步审计 → [docs/RELEASE.md](https://github.com/zjh1934/chess-dike-cn/blob/main/docs/RELEASE.md)
