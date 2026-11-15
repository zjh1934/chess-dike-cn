**v{{VER}}** · 修好「每次都要卸载重装」：签名改成固定钥匙，并加出包前指纹硬门禁。

> ⚠️ **这次是最后一次卸载**：v1.14 及以前每版签名都不同，旧钥匙已随临时构建机销毁，无法做覆盖升级。装上 v1.15 之后，以后所有版本都能直接覆盖安装。

安装包就在下面 **Assets** 区，点开即可下载（不用往下翻）：

- `armv8-dotprod-release.apk` — **推荐**，新机器
- `armv8-release.apk` — 老机器兼容版

<details>
<summary>展开看这一版改了什么</summary>

- 实测确认根因：包名 `com.xqdk.chess` 和 versionCode 一直固定，**是签名证书每版都不同**（cn-1.12 / 1.13 / 1.14 三个包三把不同证书）
- CI 之前只「打印」指纹不比对，且 release 构建缺密钥时回退到全新 runner 自动生成的 debug.keystore
- 现在显式注入本分支固定 release 密钥，出包前用 `apksigner` 逐个比对证书指纹，不一致直接构建失败
- 点按即时抓帧、面板红黑锁定、单帧直出、Release 页极简正文等全部保留

</details>

固定证书 SHA-256：`3f612a1d496d898617c1eee9fed3642b1e9c5ed2d72cab1bfdc6b7f5beba1a57`

完整更新说明 / 历史版本 / 上游同步审计 → [docs/RELEASE.md](https://github.com/zjh1934/chess-dike-cn/blob/main/docs/RELEASE.md)
