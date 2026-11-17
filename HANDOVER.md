# SignHelper 接续开发文档

> 本文件用于把项目交接给其他 AI / 新会话继续开发，减少重新摸清上下文的时间。

## 1. 项目概览

- 名称：签名助手（SignHelper）
- 作用：LSPosed 模块。被勾选的应用通过 `PackageManager` 读取其他应用签名时，把 `PackageInfo.signatures` 替换成用户配置的伪造签名。
- 包名 / applicationId：`io.github.xiangfa_test.signhelper`
- 当前版本：`0.2.2`（versionCode 由 git commit 数生成）
- 源码仓库：https://github.com/xiangfa-test/com.xiang.signhelper
- 模块仓库：https://github.com/Xposed-Modules-Repo/io.github.xiangfa_test.signhelper
- 最后发版：`0.2.2`（tag `0.2.2-<code>` / 模块仓库 ` <code>-0.2.2`）
- 交流频道：Telegram `t.me/onelazyfish`、`t.me/xiangfa_channel`

## 2. 技术栈

- Kotlin / Jetpack Compose（Compose Multiplatform runtime 1.12.x）
- UI：Miuix 0.9.4（`miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-nav` / `miuix-blur`）
- Xposed API：libxposed API 102（`compileOnly`）
- 构建：AGP 9.4.1、Gradle 9.8.x、JDK 21
- 最低 Android：API 33；targetSdk 34
- 单元测试：JUnit4 + `org.json`；CI：GitHub Actions

## 3. 目录结构

```
app/src/main/java/com/xiang/signhelper/
├─ App.kt                         进程入口（模块进程初始化）
├─ config/Config.kt               SharedPreferences 封装（本地 + 框架远程镜像）
├─ data/
│  ├─ SignEntry.kt                条目模型 + JSON 序列化
│  └─ SignStore.kt                条目仓库（Compose 状态 + 持久化 + hook 键同步）
├─ hook/
│  ├─ MainHook.kt                 libxposed 入口：生命周期 / 读配置 / 按模式装 hook
│  ├─ SignatureHookInstaller.kt   两套装法（原始 / 增强），含安装期分步日志
│  └─ SignatureForge.kt           签名合成：signatures + signingInfo + 证书比对
├─ ui/
│  ├─ MainActivity.kt             NavDisplay 容器、路由、选择应用流程
│  ├─ component/                  PageScaffold、AppIcon、对话框、底栏等
│  ├─ navigation3/                Route / Navigator
│  ├─ screen/catalog/             应用目录（主页）
│  ├─ screen/installed/           选择已安装应用（缓存、下拉刷新）
│  ├─ screen/detail/              条目详情（原始/伪造签名）
│  ├─ screen/oobe/                初次引导
│  ├─ screen/settings/            设置（含拦截模式开关）
│  └─ screen/about/               关于
└─ util/
   ├─ SignatureUtil.kt            读取已安装应用 / APK / 图标 / 缓存
   ├─ FakeSignatureCodec.kt       伪造签名文本编解码（单/多行，UI 与 hook 共用）
   └─ LogX.kt                     日志出口（logcat + LSPosed，支持中英双语）
```

## 4. 核心数据与 Hook 协议

配置组：`signhelper_config`（`Config.GROUP`）

- `entries_json`：全部条目的 JSON 数组，UI 读写
- `forged_packages`：启用伪造的包名，`\n` 分隔
- `sig:<包名>`：该包的伪造签名（hex，可多行 = 多签名，注入进程读取）
- `hook_mode`：拦截模式 `enhanced`（默认）/ `legacy`，注入进程读取
- 其他设置键：`oobe_done`、`pick_target`、`log_level`、`log_lang`、`app_lang`、`fab_hint_shown` 等

Hook 流程（`MainHook` → `SignatureHookInstaller` → `SignatureForge`）：

1. 模块加载后读取伪造表与 `hook_mode`（`refreshForgedSignatures`）
2. 按模式在宿主进程装 hook：

   | 模式 | 装什么 | 改什么 |
   | --- | --- | --- |
   | `legacy`（原始） | 写死的两个重载：`getPackageInfo(String,int)`、`getPackageInfo(String,PackageInfoFlags)` | 只改 `PackageInfo.signatures` |
   | `enhanced`（增强，默认） | 语义扫描：返回 `PackageInfo` 且方法名 ∈ {`getPackageInfo`,`getPackageInfoAsUser`}、首参 `String` 或 `VersionedPackage`；另加 `hasSigningCertificate(String,byte[],int)` | `signatures` **与** `signingInfo` 一起伪造；证书校验直接答 `true` |

3. 命中目标包时由 `SignatureForge.applyTo` 就地改写返回对象
4. 需要用户在 LSPosed 中勾选被 hook 的应用；**改配置或改模式后必须强制停止宿主应用再打开**
   （hook 只在进程启动时装一次）

注意：`forged_packages` / `sig:` / `hook_mode` 是注入进程的协议键，改名会导致 hook 失效。

上游 `xihan123/SignHook` 的键（`fakeSignature$$<包名>` / `fakeSignatureEnabled$$<包名>`）
**只做只读兼容**：`MainHook#mergeUpstreamEntries` 会把它们补进伪造表（同包名以本地键为准），
用于迁移或手工搬配置。写协议不跟着改，老配置零迁移。

### 日志（排障入口）

`adb logcat -s SignHelper:*`，或 LSPosed 日志。安装期与配置期**每步都有前缀**：

- `[启动] 1/3 → 2/3 → 3/3`：命中包、配置就绪（条数 + 模式）、hook 安装结果
- `[配置] 2/4 / 4/4`：主协议清单条数、有效条数、缺签名条数、模式原始值
- `[安装] 1/4 → 4/4`：定位实现类、选模式、扫描候选（含"疑似目标但未命中"清单）、逐个装、汇总
- `[签名合成]`：解析出几条签名、`SigningDetails` 用了哪种构造器形状、`signingInfo` 是否重建成功、
  `hasSigningCertificate` 的类型与比对结果

热路径（每次 `getPackageInfo`）的 INFO 有配额（解析 3 次 / 应用 3 次 / 证书 5 次），异常走
一次性 WARN/ERROR，不会刷屏。

## 5. 签名与构建

### 密钥

- 共享密钥：工作区 `_keys/release.p12`（alias `fuckhome`，PKCS#12）
- 各项目 `keystore.properties`（已 gitignore）：
  ```properties
  storeFile=../_keys/release.p12
  storePassword=***
  keyAlias=fuckhome
  keyPassword=***
  ```
- `app/build.gradle.kts` 中 **debug 也复用 release 签名**，所以 debug / release 可以直接 `adb install -r` 覆盖安装，不需要卸载
- 也支持环境变量覆盖：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`

### 构建命令

```powershell
$env:JAVA_HOME="<workspace>\_tools\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="<workspace>\_tools\gradle-home"
cd SignHelper
.\gradlew.bat --offline --no-watch-fs :app:assembleDebug :app:assembleRelease
```

产物：

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

### 单元测试

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest
```

CI：`.github/workflows/ci.yml`（`assembleDebug` + `testDebugUnitTest`，需 `gradlew` 可执行位）。

## 6. 发布流程

1. 修改 `version.properties` 的 `name`（`code` 由 git commit 数生成）
2. commit 后构建 release
3. 推 `main`，打 tag：
   - 源码仓库：`<versionName>-<versionCode>`，例如 `0.2.2-50`
   - 模块仓库：`<versionCode>-<versionName>`，例如 `50-0.2.2`
4. Release 资产名：`SignHelper-<versionName>.apk`
5. 更新日志保持一行一条，不写实现细节

## 7. 当前功能与已知问题

已实现：

- 应用目录：搜索、条目卡片、开关、长按删除、右上角备份/恢复
- 选择已安装应用：列表缓存（按包名逐项比较）、下拉刷新、按数量/字段变化更新
- 条目详情：原始签名 5 行预览（点击展开、长按复制）、伪造签名自动保存（防抖）
- **双拦截模式**（设置 → 行为 → 拦截模式）：`enhanced` 为超集，`legacy` 为 0.2.x 行为
- **增强层**：`signingInfo` 与 `signatures` 同步伪造、覆盖 `getPackageInfoAsUser` /
  `VersionedPackage` 重载、`hasSigningCertificate`、多行多签名
- **上游键只读兼容**：认 `fakeSignature$$<包名>`，方便从 SignHook 迁移
- OOBE：欢迎 / 基础操作 / 跳转行为 / 免责协议 / 完成页
- 设置：应用详细页跳转、日志输出与语言、界面语言、OOBE 重放
- 主页「选择 APK」改为右下角悬浮按钮，升级后提示一次
- 日志支持中英双语（默认 English）

已知问题（当前作为“特性”保留，后续仍可修）：

- 页面切换逻辑仍有瑕疵：
  - 主页模式选择应用后用 `replace` 进详情，**进入详情没有转场动画**
  - 返回主页时不再闪列表/黑屏，但整体观感不完美
- 主页绿色提示文案已改为「页面切换逻辑保留」，点开显示「懒得修了，当作特性保留了」

后续若要彻底修复，建议把「主页 / 选择页 / 详情」三者收进同一个状态机，用一套 Miuix motion 驱动，而不是继续叠加 NavDisplay 路由。

## 8. 交接注意事项

- 不要提交 `keystore.properties`、`*.p12`、`*.jks`
- 密钥已移到工作区 `_keys/`，不在任何仓库内
- 修改 `entries_json` / hook 协议键时要同时检查 `SignStore` 与 `MainHook`
- 改 `SignatureHookInstaller` 的扫描条件后，务必看 `[安装]` 日志里的"疑似目标但未命中"清单：
  那是 ROM 差异唯一能被看见的地方
- 增强层（`signingInfo` 合成）依赖 `SigningDetails` 反射，失败时**只降级不崩**：
  日志出现 `signingInfo 重建失败` 就说明这台机器上只剩 `signatures` 生效
- 两套模式共用同一份配置与同一套 `SignatureForge`，差别只在"装哪些 hook、改不改 `signingInfo`"
- 本机 Gradle/Kotlin daemon 偶发 `AccessDeniedException` 临时文件警告，一般可忽略；必要时 `--no-daemon`
- 设备无线 adb 可能掉线，重连用 `adb mdns services` 找地址
- 项目仍会继续维护；接手者完成结构性改动后请更新本文件

## 9. 新会话接手建议

1. 先读本文件，再看 `app/build.gradle.kts`、`MainActivity.kt`、`MainScreen.kt`、`SignStore.kt`、`MainHook.kt`
2. 用 `assembleDebug` 确认能构建，再跑 `testDebugUnitTest`
3. 装 release/debug 时一律 `adb install -r`，不要卸载（debug 与 release 使用同一签名）
4. 发版前先问用户是否要发、版本号用什么；发布流程见第 6 节
5. 不要读取或提交 `keystore.properties` / `_keys/` 里的密钥内容
6. 需要继续修页面切换时，优先考虑把主页/选择页/详情收进同一状态机，而不是继续叠 NavDisplay 路由