# DLsiteFloat —— DLsiteSound 悬浮字幕模块

> 一个 LSPosed / Xposed 模块，在 DLsiteSound（DLsite 音频 App）的**播放页**上挂一个与播放进度同步的**系统级悬浮字幕窗**；2.0.0 起还会把当前字幕行镜像到**系统状态栏**（在 SystemUI 进程内注入）。

- 当前版本：**2.0.1**（`DLsiteFloat-2.0.1-debug.apk`，包名 `com.sena.dlsitesoundfloat`）
- 📦 下载 APK：[Releases · v2.0.1](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v2.0.1)
- 📦 历史版本：[Releases · v1.21.0](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v1.21.0)
- 📦 开发进度：[DLsiteFloat插件开发进度管理](https://my.feishu.cn/wiki/CQoMwY4nFilzLkkrzG4cy44fnoh)


---


## 功能特点

- **按播放进度对齐字幕**：以播放器（`ExoPlayer` / expo 音频）真实播放进度为主轴对齐字幕行，画面稳定不抖；仅在拿不到进度时回退到"屏上字幕文本"匹配。
- **进程内悬浮窗**：窗口直接挂在 DLsiteSound 自己的进程里（`WindowManager` + `TYPE_APPLICATION_OVERLAY`），与各个 Hook 共享同一个 `SubtitleRepository` 实例，**无需任何跨进程 IPC、无需独立 Service**。App 退到后台、只要进程还活着，窗口仍停在最上层。
- **自动抓取字幕**：拦截 DLsiteSound 的网络响应（okhttp3），直接扫描响应体是否包含 `webvtt` / `subtitles` 字幕 JSON 并解析——**不依赖 URL 关键词**，对混淆/重打包的 okhttp3 也能兜底。字幕源为 DLsiteSound 官方 `play.dl.dlsitesound.com/.../optimized/xxx.json`，按音轨下发。
- **播放页内注入开关按钮**：仅在播放页出现一个原生按钮，文案随状态切换：
  - `悬浮关`：悬浮窗已关闭
  - `悬浮开`：悬浮窗已打开
  - `无字幕`：当前音轨没有可用字幕
- **离开播放页自动隐藏按钮**：用"三态判定 + 播放页锚点 + 三道证据门"识别页面，非播放页（首页 / 列表页 / mini-player 页）时自动隐藏（**仅按钮，悬浮窗不随页面隐藏**）。详见 [docs/page-detection.md](docs/page-detection.md)。
- **换轨智能处理**：切到无字幕音轨时先挂起并显示"无字幕"，3 秒内字幕 JSON 到达则自动恢复；否则清空并自动关窗。同时用**「曲目序号二次确认 + 列表重置识别 + 位置回退兜底」**识别**假换轨**（如第一轨按「上一首」时 App 是空操作、或播放列表被重置回第 0 首），此时**保留字幕、不关窗**。详见 [docs/track-change.md](docs/track-change.md)。手动关掉的窗口不会被字幕晚到重新打开。
- **可拖动 / 可缩放**：面板任意处按住拖动移动；右下角手柄拖动缩放（默认 85% 屏宽 × 200dp，最小 140×72dp，**纵向最长不超过半屏**）。
- **阅读体验优化**：窗口上下留白最多半屏；**首行 / 末行强制居中**（即使播到第一条或最后一条字幕，当前行也停在窗口正中）；文字四周最小留白 **15dp**；换行切换时做 **360ms 平滑上滚**动画。
- **玻璃磨砂质感**：自绘 `GlassPanelDrawable` 实现半透明磨砂底。当前行加粗高亮、字号更大，其余行为半透明上下文。
- **权限引导与降级**：未授予悬浮窗权限时，首次点击会跳到"在其他应用上层显示"设置页，且**只提示一次**；ONEPLUS/ColorOS 下绕过 `canDrawOverlays()` 误报，直接尝试挂载用真实结果判断。
- 🆕 **状态栏字幕（2.0.0 新增）**：把当前字幕行**镜像到系统状态栏** —— 在 SystemUI 进程里注入一个常驻 `FrameLayout`，由 App 进程**跨进程广播**推送字幕行（所以关掉悬浮窗，状态栏字幕照样在）。默认**关**，由播放页左侧的`状态栏 开 / 状态栏 关`胶囊按钮单击切换。详见 [docs/statusbar-subtitle.md](docs/statusbar-subtitle.md)。
  - **字体 / 颜色跟随时钟**：状态栏转浅色时自动切深色字，不会出现"浅底白字"隐身。
  - **单程左移滚动**（替代系统跑马灯的循环回弹）：按 cue 时长滚动，新行先静置再起滚，长字幕不再来回弹。
  - **遮挡时钟 / 通知图标区**：显示期间隐藏状态栏时钟与通知图标区，通知数换成一个「实心圆 + 数字」徽标；结束时原样还原。
  - **流体云避让**：字幕右界动态卡在流体云（`seeding_card_container`）左边；流体云不在时按固定左界（38dp）铺满。宽度带 24px 迟滞，避免流体云进出时宽度来回跳。
  - **暂停宽限 2s**：暂停后短暂保留再隐藏并还原时钟 / 通知图标，恢复播放自动回来。
- 🆕 **播放页双胶囊按钮**：播放控制条上方并排两个 MD3 胶囊按钮 —— 左`状态栏 开 / 状态栏 关`（**无字幕时整个消失**），右`悬浮窗 开 / 悬浮窗 关 / 无字幕`。按钮**恒定 75×32dp 不压缩**，底边强制落在**滑条顶边往上 25dp**，不会被可用空间挤到进度条上。
- 🆕 **转场抖动防护**：锁定视图密度（`stableDensity`），ColorOS 打开播放页那几秒 density 在 476 / 560 / 620dpi 间跳变时，按钮不再斜着抖（异常读数打 `density spike ignored`）。
- 🆕 **显隐改事件驱动**：新增 `StructureWatcher` 监听宿主视图树结构变化，按钮显隐从 600ms 轮询改为"宿主一动就扫"，离开播放页即时隐藏。


---


## 适配范围

| 项目 | 说明 |
| --- | --- |
| **目标 App** | DLsiteSound（`jp.co.eisys.dlsitesound`） |
| **框架** | LSPosed **2.2.0+**（2.0.0 起改用 libxposed 现代 API，legacy API 与 modern API 不可混用） |
| **作用域** | 需勾选**两个**：`jp.co.eisys.dlsitesound`（宿主 App：悬浮窗 + 播放页按钮）与 `com.android.systemui`（状态栏字幕） |
| **libxposed API** | `minApiVersion=101` / `targetApiVersion=102`，配置在 `META-INF/xposed/module.prop`。2.0.0 起**不再**使用 `de.robv.android.xposed:api:82` 与四个 `xposed*` meta-data |
| **⚠️ 装完必须重启 SystemUI** | 状态栏字幕跑在 SystemUI 进程内 —— 换新 APK 后**必须重启 SystemUI 或重启手机**，否则进程里仍是旧代码，表现是"装了没生效" |
| **Android 版本** | `minSdk 24`（Android 7.0）起；`targetSdk / compileSdk 34`。Android 8.0+ 用 `TYPE_APPLICATION_OVERLAY`，更低版本回退 `TYPE_PHONE` |
| **机型 / ROM** | 针对 **ONEPLUS / ColorOS** 做了适配（权限检测绕过、整屏模糊规避）；其它厂商 ROM 若悬浮窗/权限逻辑正常也应可用 |
| **字幕生效条件** | 仅当该音轨由官方服务端提供字幕（optimized 字幕 JSON）时生效；无字幕音轨显示"无字幕" |
| **不适用** | 非 DLsiteSound 的 App；未 root 或未安装 Xposed 框架的设备；DRM 受限内容本身无字幕的情况 |

> ⚠️ 适配依赖目标 App 的内部实现（视图结构、播放器类名、网络栈），**App 大版本更新可能导致模块失效**，需随版本重新适配。


---



## 测试环境

- 软件版本：DLsiteSound 2.18.1(570)

- 设备： 一加 15 ColorOS 16.0.10.500(CN01) 一加 12 ColorOS 16.0.10.501(CN01)



---



## 文档

详细的机制说明与排查手册已拆到 `docs/`，首页只保留概览：

| 文档 | 内容 |
| --- | --- |
| [docs/page-detection.md](docs/page-detection.md) | 页面判定：三态判定、播放页锚点、三道证据门、响应节奏 |
| [docs/track-change.md](docs/track-change.md) | 换轨判定：序号二次确认、基线种入、列表重置识别、v28 判据修正 |
| [docs/build.md](docs/build.md) | 环境要求与构建：运行环境、构建环境、构建命令、版本规则 |
| [docs/statusbar-subtitle.md](docs/statusbar-subtitle.md) | 状态栏字幕：跨进程广播、时钟/通知让位、宽度与流体云避让、滚动、踩坑 |
| [docs/api102-migration.md](docs/api102-migration.md) | libxposed API 102 迁移清单（2.0.0 已执行，留档备查） |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 日志与排查：完整日志对照表、排障顺序、版本确认 |


---


## 使用方式

1. **获取模块**：自行构建（见 [docs/build.md](docs/build.md)），或从发布页下载 `DLsiteFloat-<版本>-debug.apk`。
2. **安装并启用**：把 APK 装到已 root 设备 → 打开 **LSPosed Manager** 或同类 → 启用本模块 → 作用域**两个都勾**：**`jp.co.eisys.dlsitesound`** + **`com.android.systemui`** → **强制停止** DLsiteSound，并**重启 SystemUI 或重启手机**（状态栏字幕在 SystemUI 进程里，不重启就还是旧代码）。
3. **授予悬浮窗权限**：
   - 第一步：系统设置 → 应用 → DLsiteSound → 权限管理 → 特殊应用权限 → `悬浮窗`。
   - 第二步：设置 → 应用 → 应用管理 → DLsiteSound Floating Subtitle → 权限管理 → 特殊应用权限 → `悬浮窗`。
   - 如有需要，可以允许`后台弹出界面`
4. **打开有字幕的播放页**：播放控制条上方出现两个胶囊按钮。
   - 左`状态栏 开 / 状态栏 关`：单击开关**状态栏字幕**（默认关）；无字幕时该按钮整个消失。
   - 右`悬浮窗 开 / 悬浮窗 关`：单击开关**悬浮窗**；无字幕时显示`无字幕`且不可开。
   - 离开播放页（回到首页 / 列表页等）按钮自动隐藏；悬浮窗与状态栏字幕都不随页面隐藏。
5. **操作悬浮窗**：面板任意处按住拖动可移动；右下角手柄拖动可缩放。点面板（非手柄）可切换右上角关闭按钮（✕，30dp 显示 / 点击区）的显隐；点 ✕ 关闭窗口。


---


## 意见反馈

- **GitHub Issues**（首选）：请在项目仓库的 Issues 区提交，并尽量附上：
  1. 你的设备型号 / Android 版本 / ROM（尤其是否 ColorOS / 澎湃 / 原生 / 类原生 等）
  2. 使用的 DLsiteSound 的版本号
  3. 复现步骤 + LSPosed 日志（`DLsiteSoundFloat` 过滤）+ 必要时 `dlsitefloat_net.log`+ 录屏 / 截屏（请给敏感信息打码或截除）
- **提交前请先确认**：装的是不是最新 APK（看 LSPosed 日志里 `==== BUILD 2.0.1 / code ...` 那一行）
- 仓库地址：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle>
- 提交 Issue：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/issues>


---


## 免责声明

1. 本项目**仅用于个人技术研究 / 学习目的**，不得用于任何商业用途或盈利性分发。
2. 本项目只研究**客户端字幕显示行为**，不绕过任何 DRM，不修改、不提取受版权保护的音频/文本内容本体。字幕文件由 DLsiteSound 官方服务器下发，请尊重内容版权方权益，请通过正规渠道购买与支持作品。
3. 使用本模块需要 **Root 与 Xposed 框架**，可能违反部分设备的保修条款或某些 ROM 的安全策略，可能带来系统不稳定、失去保修、安全风险等后果，**由此带来的任何风险由使用者自行承担**。
4. 本模块与 DLsiteSound（eisys 株式会社）及各手机厂商**无任何隶属或合作关系**。因使用本模块导致的任何问题（包括但不限于账号异常、设备不稳定、数据丢失等），作者**不承担任何责任**。
5. 适配依赖目标 App 的内部实现，App 更新可能导致模块失效；作者**不保证**模块长期可用或对所有版本/机型都适配。
6. 下载、使用本模块即视为同意上述条款。


---



## 目录结构（简）

```
DLsiteSound_FloatSubtitle/
├── app/src/main/
│   ├── AndroidManifest.xml          # 模块元数据（模块名 / 简介走 android:label / android:description）
│   ├── resources/META-INF/xposed/   # 入口与作用域：java_init.list / scope.list / module.prop
│   └── java/com/sena/dlsitesoundfloat/
│       ├── DlsiteSoundSubtitleModule.java   # 入口（extends XposedModule），注册各 Hook
│       ├── data/
│       │   ├── SubtitleRepository.java      # 字幕数据中枢（播放进度 / 换轨判定 / 假换轨兜底）
│       │   └── SubtitleCue.java             # 单条字幕（起止时间 + 文本）
│       ├── hook/
│       │   ├── NetworkHook.java             # 拦截 okhttp3 响应，抓字幕 JSON
│       │   ├── PlayerSourceHook.java        # 音轨切换监听（序号二次确认 + 基线种入 + 列表重置识别）
│       │   ├── PlayerPositionHook.java      # 播放进度（getCurrentPosition）监听
│       │   ├── SubtitleViewHook.java        # 视图树扫描器 + 页面三态判定（三道门）
│       │   ├── StructureWatcher.java        # 宿主视图树结构监听（显隐改事件驱动）
│       │   ├── ActivityButtonHook.java      # 播放页双胶囊按钮（状态栏 / 悬浮窗）+ 定位
│       │   └── StatusBarSubtitleHook.java   # SystemUI 侧状态栏字幕（注入 / 避让 / 滚动）
│       ├── view/
│       │   ├── FloatingSubtitleView.java    # 悬浮窗视图（布局/居中/平滑上滚）
│       │   ├── GlassPanelDrawable.java      # 玻璃磨砂底自绘
│       │   ├── GripIndicatorView.java       # 右下角缩放手柄
│       │   └── CloseButtonView.java         # 右上角关闭按钮（✕）
│       ├── window/FloatingWindowManager.java # 进程内悬浮窗管理（拖拽/缩放）
│       └── util/
│           ├── Utils.java                   # dp/sp 换算等
│           ├── NetLogFile.java              # 网络诊断日志落盘
│           ├── StatusBarSubtitleBridge.java # 跨进程广播桥（App → SystemUI：字幕行 / 开关）
│           └── XposedCompat.java            # libxposed API 102 兼容门面
├── docs/                            # 详细机制文档（从 README 拆出）
│   ├── page-detection.md            # 页面判定与响应机制
│   ├── track-change.md              # 换轨判定与假换轨保护
│   ├── statusbar-subtitle.md        # 状态栏字幕实现与踩坑
│   ├── api102-migration.md          # libxposed API 102 迁移清单（已执行）
│   ├── build.md                     # 环境要求与构建
│   └── troubleshooting.md           # 日志与排查
├── tools/version_code.py            # 版本 code 生成器（MMDD 规则）
├── gradle/wrapper/                  # Gradle Wrapper 8.4
├── build.gradle / settings.gradle
└── README.md
```
