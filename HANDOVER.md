# SignHelper 交接文档

> 本文件用于项目停止维护后的交接。项目后续不再计划更新，除非接手者继续维护。

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
├─ hook/MainHook.kt               libxposed 入口：挂 getPackageInfo，替换 signatures
├─ ui/
│  ├─ MainActivity.kt             NavDisplay 容器、路由、选择应用流程
│  ├─ component/                  PageScaffold、AppIcon、对话框、底栏等
│  ├─ navigation3/                Route / Navigator
│  ├─ screen/catalog/             应用目录（主页）
│  ├─ screen/installed/           选择已安装应用（缓存、下拉刷新）
│  ├─ screen/detail/              条目详情（原始/伪造签名）
│  ├─ screen/oobe/                初次引导
│  ├─ screen/settings/            设置
│  └─ screen/about/               关于
└─ util/
   ├─ SignatureUtil.kt            读取已安装应用 / APK / 图标 / 缓存
   └─ LogX.kt                     日志出口（logcat + LSPosed，支持中英双语）
```

## 4. 核心数据与 Hook 协议

配置组：`signhelper_config`（`Config.GROUP`）

- `entries_json`：全部条目的 JSON 数组，UI 读写
- `forged_packages`：启用伪造的包名，`\n` 分隔
- `sig:<包名>`：该包的伪造签名 hex（注入进程读取）
- 其他设置键：`oobe_done`、`pick_target`、`log_level`、`log_lang`、`app_lang`、`fab_hint_shown` 等

Hook 流程（`MainHook`）：

1. 模块加载后读取伪造表
2. 在宿主进程挂 `ApplicationPackageManager#getPackageInfo` 各重载
3. 命中目标包时，把返回的 `PackageInfo.signatures` 替换为配置的伪造签名
4. 需要用户在 LSPosed 中勾选被 hook 的应用；修改伪造签名后通常需强制停止宿主应用再打开

注意：`forged_packages` / `sig:` 是注入进程的协议键，改名会导致 hook 失效。

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
- OOBE：欢迎 / 基础操作 / 跳转行为 / 免责协议 / 完成页
- 设置：应用详细页跳转、日志输出与语言、界面语言、OOBE 重放
- 主页「选择 APK」改为右下角悬浮按钮，升级后提示一次
- 日志支持中英双语（默认 English）

已知问题（作为“特性”保留）：

- 页面切换逻辑仍有瑕疵：
  - 主页模式选择应用后用 `replace` 进详情，**进入详情没有转场动画**
  - 返回主页时不再闪列表/黑屏，但整体观感不完美
- 主页绿色提示文案已改为「页面切换逻辑保留」，点开显示「懒得修了，当作特性保留了」

后续若要彻底修复，建议把「主页 / 选择页 / 详情」三者收进同一个状态机，用一套 Miuix motion 驱动，而不是继续叠加 NavDisplay 路由。

## 8. 交接注意事项

- 不要提交 `keystore.properties`、`*.p12`、`*.jks`
- 密钥已移到工作区 `_keys/`，不在任何仓库内
- 修改 `entries_json` / hook 协议键时要同时检查 `SignStore` 与 `MainHook`
- 本机 Gradle/Kotlin daemon 偶发 `AccessDeniedException` 临时文件警告，一般可忽略；必要时 `--no-daemon`
- 设备无线 adb 可能掉线，重连用 `adb mdns services` 找地址
- 仓库已不再计划更新；接手者继续维护时请更新本文件
