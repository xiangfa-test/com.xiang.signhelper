# 签名助手（SignHelper）

<p align="center">
  <a href="https://github.com/xiangfa-test/com.xiang.signhelper/releases"><img src="https://img.shields.io/github/v/release/xiangfa-test/com.xiang.signhelper?label=release&color=0aa1dd" alt="Release"></a>
  <a href="https://github.com/xiangfa-test/com.xiang.signhelper/releases"><img src="https://img.shields.io/github/downloads/xiangfa-test/com.xiang.signhelper/total?label=downloads&color=2ea44f" alt="Downloads"></a>
  <a href="https://github.com/xiangfa-test/com.xiang.signhelper/stargazers"><img src="https://img.shields.io/github/stars/xiangfa-test/com.xiang.signhelper?label=stars&color=e3b341" alt="Stars"></a>
  <img src="https://img.shields.io/badge/license-GPL--3.0--only-8957e5" alt="License">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-13%2B-3ddc84?logo=android&logoColor=white" alt="Android 13+">
  <img src="https://img.shields.io/badge/LSPosed-module-6f42c1" alt="LSPosed module">
  <a href="https://t.me/onelazyfish"><img src="https://img.shields.io/badge/Telegram-%E4%BA%A4%E6%B5%81%E9%A2%91%E9%81%93-26A5E4?logo=telegram&logoColor=white" alt="Telegram 交流频道"></a>
  <a href="https://t.me/xiangfa_channel"><img src="https://img.shields.io/badge/Telegram-%E5%8F%91%E5%B8%83%E9%A2%91%E9%81%93-26A5E4?logo=telegram&logoColor=white" alt="Telegram 发布频道"></a>
</p>

对**简单签名校验**通杀的 LSPosed 模块：被 hook 的应用通过 `PackageManager`
读取列表里某个应用的签名时，返回模块里为该应用设置的伪造签名。

- 基于 **libxposed API 102**（`minApiVersion=102`）
- UI 使用 **Miuix**（Compose，澎湃 OS / MIUI 风格），只有「应用目录」「关于」两页
- 默认作用域：微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`，可在 LSPosed 里加任意应用
- 作者：xiangfa；原版 `cn.xihan.sign` 作者：xihan

## 功能

- **应用目录**：选择已安装应用 / 选择 APK 加入列表；条目展示原始签名、伪造签名与启用开关；
  点击改签名，长按删除
- **选择已安装应用**：独立二级页，带搜索、排序（名称 / 最后更新 / 反向）与范围（用户 / 系统 / 全部）
- **Hook**：后置拦截 `ApplicationPackageManager#getPackageInfo(String, int)` 与
  `getPackageInfo(String, PackageInfoFlags)`；命中伪造表时替换 `PackageInfo.signatures`，
  异常全部 PROTECTIVE，不影响宿主
- **关于页**：构建时间、作者、致谢（xihan / Miuix / libxposed / LSPosed）

## 构建

需要 JDK 21 与 Android SDK（compileSdk 37 / build-tools 37.0.0）。

```bash
./gradlew :app:assembleDebug      # ~12 MB
./gradlew :app:assembleRelease    # ~1.5 MB（R8 + 资源裁剪）
```

- 依赖已包含 `maven-local/`（libxposed 102 的 api/interface/service），离线也能构建
- 签名：在工程根目录放 `keystore.properties`（storeFile / storePassword / keyAlias / keyPassword），
  debug 与 release 复用同一把；不配置则使用本地默认调试签名
- 产物：`app/build/outputs/apk/{debug,release}/`

## 使用

1. LSPosed 里启用本模块，并勾选需要被 hook 的应用（默认微信 / QQ）
2. 应用目录里加目标应用（已安装应用或 APK），点条目填入伪造签名（十六进制）
3. 打开条目开关 → 强制停止被 hook 的应用 → 重新打开
4. 该应用再读取列表内应用的签名，拿到的就是伪造值

> 部分系统（MIUI / HyperOS 等）默认禁止应用读取已安装应用列表。未开启时本模块会退回到 intent 反查（列表可能不完整），并弹窗引导你打开系统「应用信息 → 权限」自行允许「获取应用列表 / 已安装应用」，允许后返回即可自动刷新。

## 配置与数据

模块界面写本地 `SharedPreferences`，同时通过 `XposedService.getRemotePreferences("signhelper_config")`
同步给框架；注入进程只读该 remote prefs。键：

- `entries_json`：完整条目（UI 用）
- `forged_packages`：生效中的包名（`\n` 分隔）
- `sig:<包名>`：该包的伪造签名（hex）

## 交流

- Telegram 交流频道：https://t.me/onelazyfish
- Telegram 发布频道：https://t.me/xiangfa_channel

## License

GPL-3.0（见 [LICENSE](LICENSE)）。



