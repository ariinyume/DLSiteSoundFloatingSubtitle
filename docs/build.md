# 环境要求与构建

> 📦 本文档原为 README 的「环境要求」一节，为保持首页简洁而拆出。
> 🔄 **2026-10-07 重写**：旧版还停留在 `1.20.5` / `com.sena.*` / `xposed:api:82` 时代，与 2.x 完全不符。

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
- **Android SDK**：`platform-34` + `build-tools 34.0.0`（`compileSdk 34` / `minSdk 24` / `targetSdk 34`）
- **Gradle 8.4** —— 仓库已内置 Gradle Wrapper（`gradlew`），无需手动安装；首次执行会自动下载 8.4 发行包（本机已有 toolchain 时建议加 `--offline`）

### 依赖

| 坐标 | 方式 | 说明 |
| --- | --- | --- |
| `io.github.libxposed:api:102.0.0` | `compileOnly` | **libxposed 现代 API（API 102）**。2.0.0 起取代 `de.robv.android.xposed:api:82`，两者**不可混用**。只能 `compileOnly`（打进包会与框架的 `XposedInterface` 类冲突）。发布在 **Maven Central**，首次构建需联网拉取 |
| `com.google.android.material:material:1.9.0` | `implementation` | 设置页（`SettingsActivity`）用 MD3 组件。**必须打进包**；注入到宿主 / SystemUI 的视图一律用代码构建、不引用 Material |
| `androidx.appcompat:appcompat:1.6.1` | `implementation` | 同上 |
| `androidx.core:core:1.9.0` | `implementation` | 同上 |

> ⚠️ **`de.robv.android.xposed:api:82` 已完全移除**，连同四个 `xposed*` meta-data 一起
> （现代 API 读 `META-INF/xposed/` 下的 `java_init.list` / `scope.list` / `module.prop`，
> 模块名走 `android:label`、简介走 `android:description`）。迁移清单见 [api102-migration.md](api102-migration.md)。
>
> ⚠️ 包名自 **2.1.0** 起是 **`io.github.ariinyume.dlsitesoundfloat`**
> （2.0.x 及以前是 `com.sena.dlsitesoundfloat`）。改名的原因：`com.sena.*` 不是自有域名，
> 过不了 LSPosed 官方仓库审核。

## 构建命令

```bash
# 设置环境变量（指向 toolchain 里的 JDK / SDK）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 打 debug 包（2.2.8 / code 980）
./gradlew   assembleDebug --offline --no-daemon      # Linux / macOS
gradlew.bat assembleDebug --offline --no-daemon      # Windows
# 产物：app/build/outputs/apk/debug/DLsiteFloat-2.2.8-code980-debug.apk

# 只验语法（更快）
./gradlew   compileDebugJavaWithJavac --offline      # Linux / macOS
gradlew.bat compileDebugJavaWithJavac --offline      # Windows
```

> 仓库已内置 **Gradle Wrapper 8.4**（`gradlew` / `gradlew.bat` / `gradle/wrapper/`）。
> ⚠️ 本机 Gradle 真路径是 `toolchain/gradle-8.4/gradle-8.4/bin/gradle.bat`（**嵌套一层**）；
> `JAVA_HOME` / `ANDROID_HOME` 用**反斜杠**，另需 `ANDROID_SDK_ROOT`。

## 版本规则（2.0.0 起）

`app/build.gradle` 顶部有三个变量，**发版时三处必须同步**：

| 变量 | 例 | 说明 |
| --- | --- | --- |
| `appVersionTag` | `2.2.8` | 即 `versionName`，语义化版本 |
| `appVersionCode` | `980` | 即 `versionCode`，**严格单调递增**的整数（早期取 MMDD，现在只保证单调、不与任何已发/已占号相撞） |
| `appVersionCodeLabel` | `1007.8` | 可读标签 `MMDD.当日第几版`，只用于日志与人工辨识 |

**第四处**：`DlsiteSoundSubtitleModule` 里的
`==== BUILD <versionName> / code <versionCode> （<描述>）====` 横幅 ——
真机日志里就靠这一行确认装的是哪一版。

> ⚠️ **APK 文件名自动带 versionCode**
> （`outputFileName = "DLsiteFloat-${appVersionTag}-code${appVersionCode}-${buildType}.apk"`），
> `assembleDebug` 直接生效，**不要手工改名**。动因：同一 `versionName` 出现过多个 code，只看版本名分不清。
>
> ⚠️ **修复类迭代也要升版本号**（与 1.21.x 的旧规矩相反）：`versionCode` 必须严格高于机上已装的号，
> 否则 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒装。

## 构建纪律（血泪）

- 🔴 **出包前必须清理**：`rm -rf app/build`。**增量构建产物与 clean 构建不逐字节一致**（实测差 4 字节），
  会让「双闭环字节对照」假 FAIL。
- 🔴 **改完源码必须 `clean assembleDebug`**，不能只跑增量：曾出现增量构建**没重编译**目标文件
  （`BUILD SUCCESSFUL`、4 executed/28 up-to-date），dex 里还是旧逻辑，且包体虚胖 +55KB。
- 🔴 **每版校验必须做反向对照**：把修复撤掉、clean 重建，确认校验脚本**真的会 FAIL**。
  没有反向对照的绿 = 没有证据。
- ⚠️ `META-INF/xposed/` 下三个文件**不要写注释行**（`#` 开头）：`java_init.list` 是「逐行 = 一个类名」
  的裸解析器，混入注释会让入口解析失败 —— 表现为「装上了但完全没调起、日志里一个字都没有」。
- ⚠️ `.gitattributes` 已把 `META-INF/xposed/*` + `gradlew` + `LICENSE` 钉为 **LF**：
  CRLF 会让入口类名带 `\r` ⇒ 静默不加载。
- ⚠️ 矢量图 `pathData` **无编译期校验**：aapt2 不查语法，只有真机 `VectorDrawable` 解析才抛
  ⇒ 编译全绿、设置页一点就闪退（code 962 就栽在这）。改 `res/drawable/*.xml` 后必须跑 pathData 校验。

## 构建产物不入库

`.gitignore` 已忽略 `*.apk` / `*.aab` / `**/build/`，发布走 GitHub Releases：
<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases>
