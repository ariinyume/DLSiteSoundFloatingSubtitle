# DLsiteFloat —— DLsiteSound 悬浮字幕模块

> 一个 LSPosed / Xposed 模块，在 DLsiteSound（DLsite 音频 App）的**播放页**上挂一个与播放进度同步的**系统级悬浮字幕窗**。

- 当前版本：**1.21.0**（`DLsiteFloat-1.21.0-debug.apk`，包名 `com.sena.dlsitesoundfloat`）
- 📦 下载 APK：[Releases · v1.21.0](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v1.21.0)
- 📦 历史版本：[Releases · v1.20.5](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v1.20.5)


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


---


## 适配范围

| 项目 | 说明 |
| --- | --- |
| **目标 App** | DLsiteSound（`jp.co.eisys.dlsitesound`） |
| **框架** | LSPosed / Xposed 兼容框架；模块作用域**仅** `jp.co.eisys.dlsitesound` |
| **Xposed 最低版本** | `xposedminversion` 要求 **93**（模块编译用 Xposed API 82） |
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
| [docs/troubleshooting.md](docs/troubleshooting.md) | 日志与排查：完整日志对照表、排障顺序、版本确认 |


---


## 使用方式

1. **获取模块**：自行构建（见 [docs/build.md](docs/build.md)），或从发布页下载 `DLsiteFloat-<版本>-debug.apk`。
2. **安装并启用**：把 APK 装到已 root 设备 → 打开 **LSPosed Manager** 或 同类→ 启用本模块 → 作用域勾选 **`jp.co.eisys.dlsitesound`** → **强制停止** DLsiteSound 后重新打开。
3. **授予悬浮窗权限**：
   - 第一步：系统设置 → 应用 → DLsiteSound → 权限管理 → 特殊应用权限 → `悬浮窗`。
   - 第二步：设置 → 应用 → 应用管理 → DLsiteSound Floating Subtitle → 权限管理 → 特殊应用权限 → `悬浮窗`。
   - 如有需要，可以允许`后台弹出界面`
4. **打开有字幕的播放页**：右下角（播放控制条下方）出现本模块按钮。
   - 点一下在 `悬浮关 / 悬浮开` 间切换；无字幕时按钮显示`无字幕`且不可开。
   - 离开播放页（回到首页 / 列表页等）按钮自动隐藏，悬浮窗不随页面隐藏。
5. **操作悬浮窗**：面板任意处按住拖动可移动；右下角手柄拖动可缩放。点面板（非手柄）可切换右上角关闭按钮（✕，30dp 显示 / 点击区）的显隐；点 ✕ 关闭窗口。


---


## 意见反馈

- **GitHub Issues**（首选）：请在项目仓库的 Issues 区提交，并尽量附上：
  1. 你的设备型号 / Android 版本 / ROM（尤其是否 ColorOS / MIUI 等）
  2. DLsiteSound 的版本号
  3. 复现步骤 + LSPosed 日志（`DLsiteSoundFloat` 过滤）+ 必要时 `dlsitefloat_net.log`
- **提交前请先确认**：装的是不是最新 APK——看 LSPosed 日志里的
  `==== BUILD 1.21.0 (launcher icon refined: r=300 rounded corners, smooth edges (no grey rim), outer shadow offset down+right so it sits clearly BELOW the icon [card-on-table drop shadow]; regenerated at mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi with proportional scaling; version stays 1.21.0 / code 12100) ====` 一行。
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
│   ├── AndroidManifest.xml          # Xposed 模块元数据 + 作用域
│   ├── assets/xposed_init           # 模块入口
│   └── java/com/sena/dlsitesoundfloat/
│       ├── DlsiteSoundSubtitleModule.java   # Xposed 入口，注册各 Hook
│       ├── data/
│       │   ├── SubtitleRepository.java      # 字幕数据中枢（播放进度 / 换轨判定 / 假换轨兜底）
│       │   └── SubtitleCue.java             # 单条字幕（起止时间 + 文本）
│       ├── hook/
│       │   ├── NetworkHook.java             # 拦截 okhttp3 响应，抓字幕 JSON
│       │   ├── PlayerSourceHook.java        # 音轨切换监听（序号二次确认 + 基线种入 + 列表重置识别）
│       │   ├── PlayerPositionHook.java      # 播放进度（getCurrentPosition）监听
│       │   ├── SubtitleViewHook.java        # 视图树扫描器 + 页面三态判定（三道门）
│       │   └── ActivityButtonHook.java      # 播放页按钮生命周期 + 自适应心跳
│       ├── view/
│       │   ├── FloatingSubtitleView.java    # 悬浮窗视图（布局/居中/平滑上滚）
│       │   ├── GlassPanelDrawable.java      # 玻璃磨砂底自绘
│       │   ├── GripIndicatorView.java       # 右下角缩放手柄
│       │   └── CloseButtonView.java         # 右上角关闭按钮（✕）
│       ├── window/FloatingWindowManager.java # 进程内悬浮窗管理（拖拽/缩放）
│       └── util/
│           ├── Utils.java                   # dp/sp 换算等
│           └── NetLogFile.java              # 网络诊断日志落盘
├── docs/                            # 详细机制文档（从 README 拆出）
│   ├── page-detection.md            # 页面判定与响应机制
│   ├── track-change.md              # 换轨判定与假换轨保护
│   ├── build.md                     # 环境要求与构建
│   └── troubleshooting.md           # 日志与排查
├── gradle/wrapper/                  # Gradle Wrapper 8.4
├── build.gradle / settings.gradle
└── README.md
```
