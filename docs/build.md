# 环境要求与构建

> 📦 本文档原为 README 的「环境要求」一节，为保持首页简洁而拆出。

## 运行环境（设备端）

- 已 **root** 的 Android 设备，并安装 **LSPosed（或兼容 Xposed 框架）**。
- Android **7.0（API 24）及以上**。
- 必须给 **DLsiteSound 本身**授予「显示在其他应用上层 / 悬浮窗」权限 —— 窗口挂在它的进程里，这是**必需项**。
- 同时建议给 **模块 App（DLsiteSound Floating Subtitle）**也授予同一权限 —— 部分 ROM（如 ColorOS）
  会在模块侧再做一道拦截，**两者都开最稳妥**（实际设备上两步都要做）。
- 必要时允许「后台弹出界面」权限。

> 以 系统设置 → 应用 → 权限管理 → 特殊应用权限 → `悬浮窗` 为路径，DLsiteSound 与模块 App **各授一次**。

## 构建环境（开发端）

- **JDK 17**
- **Android SDK**：`platform-34` + `build-tools 34.0.0`
- **Gradle 8.4** —— 仓库已内置 Gradle Wrapper（`gradlew`），无需手动安装；首次执行会自动下载 8.4 发行包（本机已有 toolchain 时建议加 `--offline`）
- 依赖 `de.robv.android.xposed:api:82`（`compileOnly`，由 `https://api.xposed.info/` 自动拉取）

## 构建命令

```bash
# 设置环境变量（指向 toolchain 里的 JDK / SDK）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 打 debug 包（com.sena.dlsitesoundfloat，版本 1.20.5）
./gradlew   assembleDebug --offline --no-daemon      # Linux / macOS
gradlew.bat assembleDebug --offline --no-daemon      # Windows
# 产物：app/build/outputs/apk/debug/DLsiteFloat-1.20.5-debug.apk

# 只验语法（更快）
./gradlew   compileDebugJavaWithJavac --offline      # Linux / macOS
gradlew.bat compileDebugJavaWithJavac --offline      # Windows
```

> 仓库已内置 **Gradle Wrapper 8.4**（`gradlew` / `gradlew.bat` / `gradle/wrapper/`），
> 首次执行会自动下载 Gradle 8.4 发行包；本机若已装 Gradle 8.4 也可直接用 `gradle` 命令。

## 版本规则

- 版本号：`1.20.x`，第三位递增；`versionCode = 10200 + x`（如 `1.20.5` → `10205`）。
- 三处需同步修改：`app/build.gradle` 的 `appVersionTag` / `appVersionCode` / `appVersionName`，
  以及 `DlsiteSoundSubtitleModule` 里的 `==== BUILD <版本> (<描述>) ====`。
- 包名固定 `com.sena.dlsitesoundfloat`；应用名带 `_debug` 后缀，
  LSPosed 列表中显示为 **DLsiteSound Floating Subtitle_debug**。

> ⚠️ **同版本号可能对应多个包**（保留版本号只改 BUILD 描述时）。
> 装机前务必用日志里的 `==== BUILD … ====` 一行确认拿到的是哪一版。
> 1.20.5 起还会紧接一行 `build applicationId=… versionName=…`。

## 构建产物不入库

`.gitignore` 已忽略 `*.apk` / `*.aab` / `**/build/`，发布走 GitHub Releases：
<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases>
