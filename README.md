# 签名助手（SignHelper）

<p align="center">
  <a href="https://github.com/xiangfa-test/com.xiang.signhelper/releases"><img src="https://img.shields.io/github/v/release/xiangfa-test/com.xiang.signhelper?label=release&color=0aa1dd" alt="Release"></a>
  <a href="https://github.com/xiangfa-test/com.xiang.signhelper/releases"><img src="https://img.shields.io/github/downloads/xiangfa-test/com.xiang.signhelper/total?label=downloads&color=2ea44f" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/license-GPL--3.0--only-8957e5" alt="License">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-13%2B-3ddc84?logo=android&logoColor=white" alt="Android 13+">
  <img src="https://img.shields.io/badge/LSPosed-module-6f42c1" alt="LSPosed module">
  <a href="https://t.me/onelazyfish"><img src="https://img.shields.io/badge/Telegram-MIKU%E7%9A%84%E5%B0%8F%E7%AA%9D-26A5E4?logo=telegram&logoColor=white" alt="Telegram"></a>
  <a href="https://t.me/xiangfa_channel"><img src="https://img.shields.io/badge/Telegram-%E6%83%B3%E6%B3%95%E3%81%AE%E7%AA%9D-26A5E4?logo=telegram&logoColor=white" alt="Telegram"></a>
</p>

让被 hook 的应用通过 `PackageManager` 读到的签名，变成你为它设置的伪造值。
基于 libxposed API 102，界面用 Miuix；默认作用域微信 / QQ，可在 LSPosed 里加任意应用。

## 功能

- **应用目录**：选已安装应用或 APK 加入列表，条目显示原始 / 伪造签名与开关；点击编辑，长按删除
- **多签名应用**：自动识别多签名 APK 与签名轮换，进入专属详情页，逐张展示并按条数校验伪造值
- **增强模式**（设置 → 行为，默认开）：`signatures` 与 `signingInfo` 一起替换，并接管
  `hasSigningCertificate`、`asUser` / `VersionedPackage` 查询；关闭则回到只替换 `signatures` 的旧行为
- 备份 / 恢复、OOBE、日志中英双语

## 使用

1. LSPosed 启用本模块，并勾选需要被 hook 的宿主应用
2. 列表里加目标应用，填入伪造签名（十六进制，一行一条）
3. 打开条目开关 → 强制停止宿主 → 重新打开

> MIUI / HyperOS 等默认禁止读取应用列表时，模块会退回 intent 反查，并引导你授予「获取应用列表」。

## 构建

需要 JDK 21 与 Android SDK（compileSdk 37 / build-tools 37.0.0）。

```bash
./gradlew :app:assembleDebug      # ~12 MB
./gradlew :app:assembleRelease    # ~1.5 MB（R8 + 资源裁剪）
```

依赖自带 `maven-local/`，可离线构建；签名读工程根目录的 `keystore.properties`，debug 与 release 复用同一把。

## 配置键

`entries_json`（UI 条目）、`forged_packages`（生效包名，`\n` 分隔）、
`sig:<包名>`（伪造签名，多行 = 多签名）、`hook_mode`（`enhanced` / `legacy`）。

只读兼容上游 [SignHook](https://github.com/xihan123/SignHook) 的
`fakeSignature$$<包名>` / `fakeSignatureEnabled$$<包名>`，同包名时以本模块的键为准。

## 其它

- 发布频道：https://t.me/onelazyfish 、https://t.me/xiangfa_channel
- 致谢：应用内「关于」页
- License：GPL-3.0（见 [LICENSE](LICENSE)）
