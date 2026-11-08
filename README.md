# 签名助手（SignHelper）

对**简单签名校验**通杀的 LSPosed 模块：被 hook 的应用通过 `PackageManager`
读取列表里某个应用的签名时，返回模块里为该应用设置的伪造签名。

- 基于 **libxposed API 102**（`minApiVersion=102`）
- UI 使用 **Miuix**（Compose，澎湃 OS / MIUI 风格），只有「应用目录」「关于」两页
- 默认作用域：微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`，可在 LSPosed 里加任意应用
- 作者：xiangfa

## 功能

- **应用目录**：选择已安装应用 / 选择 APK 加入列表；条目展示原始签名、伪造签名与启用开关；
  点击改签名，长按删除
- **选择已安装应用**：独立二级页，带搜索、排序（名称 / 最后更新 / 反向）与范围（用户 / 系统 / 全部）
- **Hook**：后置拦截 `ApplicationPackageManager#getPackageInfo(String, int)` 与
  `getPackageInfo(String, PackageInfoFlags)`；命中伪造表时替换 `PackageInfo.signatures`，
  异常全部 PROTECTIVE，不影响宿主
- **关于页**：构建时间、作者、致谢（Miuix / libxposed / LSPosed）

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

## 配置与数据

模块界面写本地 `SharedPreferences`，同时通过 `XposedService.getRemotePreferences("signhelper_config")`
同步给框架；注入进程只读该 remote prefs。键：

- `entries_json`：完整条目（UI 用）
- `forged_packages`：生效中的包名（`\n` 分隔）
- `sig:<包名>`：该包的伪造签名（hex）

## 版本

- `versionName`：`version.properties` 的 `name`，发版时手改（当前 `0.1.0`）
- `versionCode`：构建时取 `git rev-list --count HEAD`

## 已知限制

- 只替换 `PackageInfo.signatures`，不处理 `signingInfo` / `checkSignatures`（与原版行为一致）
- 改配置后需强制停止被 hook 的应用才会重新读取签名
- 作用域在 LSPosed 里配置，代码不写死应用名

## License

GPL-3.0（见 [LICENSE](LICENSE)）。

