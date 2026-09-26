# DLsiteFloat —— DLsiteSound 悬浮窗/状态栏字幕模块

> 一个 LSPosed / Xposed 模块，在 DLsiteSound（DLsite 音频 App）的**播放页**上挂一个与播放进度同步的**悬浮字幕窗**；同时支持将字幕镜像到**系统状态栏**（在 SystemUI 进程内注入）。

- 当前版本：**2.1.2**（`DLsiteFloat-2.1.2-debug.apk`，包名 `io.github.ariinyume.dlsitesoundfloat`）
- 📦 下载 APK：[Releases · v2.1.2](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v2.1.2)
- 🕘 历史版本：[Releases · v2.1.0](https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/releases/tag/v2.1.0)
- 📝 开发进度：[DLsiteFloat插件开发进度管理](https://my.feishu.cn/wiki/CQoMwY4nFilzLkkrzG4cy44fnoh)


---


## 功能特点

- **播放页双胶囊按钮（2.0.1 起）**：播放控制条上方并排两个 MD3 胶囊按钮 —— 左`状态栏 开 / 状态栏 关`（**无字幕时整个消失**），右`悬浮窗 开 / 悬浮窗 关 / 无字幕`。
  - `状态栏 关`：状态栏字幕已关闭
  - `状态栏 开`：状态栏字幕已打开
  - `悬浮窗 关`：悬浮窗已关闭
  - `悬浮窗 开`：悬浮窗已打开
  - `无字幕`：当前音轨没有可用字幕
  - **按钮尺寸固定（2.1.2 起）**：宽高、圆角、文字、距滑条偏移只按**首次锁定的密度**计算；文字用**固定 px** 并按胶囊可用宽度自动适配，保证**完全显示**，也不受后续系统「字体大小 / 显示大小」变化影响。
  - **多语言支持（2.1.2 起）**：按**系统语言**取插件显示语言（读系统配置，不受宿主 App 的「应用语言」影响），支持**简体中文 / 繁體中文 / English**。
    | 系统语言 | 左 / 右按钮 | 按钮`无字幕` | 悬浮窗占位 |
    | --- | --- | --- | --- |
    | 简体中文 | `状态栏 开 / 关`、`悬浮窗 开 / 关` | `无字幕` | `无字幕` |
    | 繁體中文 | `狀態欄 開 / 關`、`懸浮窗 開 / 關` | `無字幕` | `無字幕` |
    | 其它语言 | `Status ON / OFF`、`Popup ON / OFF` | `No Sub` | `No Subtitles` |
- **状态栏字幕（2.0.1 起）**：把当前字幕行**镜像到系统状态栏** —— 在 SystemUI 进程里注入一个常驻 `FrameLayout`，由 App 进程**跨进程广播**推送字幕行（所以关掉悬浮窗，状态栏字幕照样在）。默认**关**，由播放页左侧的`状态栏 开 / 状态栏 关`胶囊按钮单击切换。详见 [docs/statusbar-subtitle.md](docs/statusbar-subtitle.md)。
  - **单程左移滚动**（替代系统跑马灯的循环回弹）：按 cue 时长滚动，新行先静置再起滚，长字幕不再来回弹。
  - **遮挡时钟 / 通知图标区**：显示期间隐藏状态栏时钟与通知图标区，通知换成数字徽标；结束时原样还原。
  - **流体云避让**：字幕右界动态卡在流体云（`seeding_card_container`）左边；流体云不在时按固定左界（38dp）铺满。宽度带 24px 迟滞，避免流体云进出时宽度来回跳。

- **进程内悬浮窗**：窗口直接挂在 DLsiteSound 进程内（`WindowManager` + `TYPE_APPLICATION_OVERLAY`），与各个 Hook 共享同一个 `SubtitleRepository` 实例，**无需任何跨进程 IPC、无需独立 Service**。
  - **悬浮窗可拖动 / 可缩放**：面板任意处按住拖动移动；右下角手柄拖动缩放（默认 85% 屏宽 × 200dp，最小 140×72dp，**纵向最长不超过半屏**）。
  - **悬浮窗内关闭按钮两种收法**：点一次面板出现 ✕，**5s 内没点自动隐藏**；期间**再点面板空白处立即收回**。
  - **可单独使用悬浮窗字幕**：未授权 `com.android.systemui` 作用域时左侧`状态栏 开 / 状态栏 关`按钮不显示，用跨进程握手确认 SystemUI 是否已被注入（App 每 3s 发 PING、SystemUI 回 PONG，8s 内无回应即视为未授权）。
- **按播放进度对齐字幕**：以播放器（`ExoPlayer` / expo 音频）真实播放进度为主轴对齐字幕行。
- **自动抓取字幕**：拦截 DLsiteSound 的网络响应（okhttp3），直接扫描响应体是否包含 `webvtt` / `subtitles` 字幕 JSON 并解析——**不依赖 URL 关键词**，对混淆/重打包的 okhttp3 也能兜底。字幕源为 DLsiteSound 官方 `play.dl.dlsitesound.com/.../optimized/xxx.json`，按音轨下发。
- **换轨智能处理**：切到无字幕音轨时先挂起、悬浮窗显示`无字幕`占位，等新音轨的字幕 JSON —— 无缓存 cues 时等 **5s**，已有缓存 cues 时放宽到 **10s**；**5s**内还没等到 JSON 就**提前收窗**，JSON 到了会**自动把窗口开回**；直到裁决窗到点仍无 JSON，才清空 cues 并判「无字幕」。详见 [docs/track-change.md](docs/track-change.md)。
- **权限引导与降级**：未授予悬浮窗权限时，首次点击会跳到"在其他应用上层显示"设置页，且**只提示一次**。


---


## 适配范围

| 项目 | 说明 |
| --- | --- |
| **目标 App** | DLsiteSound（`jp.co.eisys.dlsitesound`） |
| **框架** | LSPosed **2.2.0+**（2.0.0 起改用 libxposed 现代 API，legacy API 与 modern API 不可混用） |
| **作用域** | 需勾选**两个**：`jp.co.eisys.dlsitesound`（宿主 App：悬浮窗 + 播放页按钮）与 `com.android.systemui`（状态栏字幕） |
| **libxposed API** | `minApiVersion=101` / `targetApiVersion=102`，配置在 `META-INF/xposed/module.prop`。2.0.0 起**不再**使用 `de.robv.android.xposed:api:82` 与四个 `xposed*` meta-data |
| **Android 版本** | `minSdk 24`（Android 7.0）起；`targetSdk / compileSdk 34`。Android 8.0+ 用 `TYPE_APPLICATION_OVERLAY`，更低版本回退 `TYPE_PHONE` |
| **机型 / ROM** | 针对 **ONEPLUS / ColorOS 16** 做了测试与适配，理论上支持 OxygenOS/RealmeUI；其它厂商 ROM 若悬浮窗/权限逻辑正常也应可用 |
| **生效条件** | 仅当该音轨由官方服务端提供字幕（optimized 字幕 JSON）时生效 |
| **不适用** | 非 DLsiteSound 的 App；未 root 或未安装 Xposed 框架的设备；DRM 受限内容本身无字幕的情况 |

> ⚠️ 适配依赖目标 App 的内部实现（视图结构、播放器类名、网络栈），**App 大版本更新可能导致模块失效**，需随版本重新适配。


---



## 测试环境

- 软件版本：DLsiteSound 2.19.0 (573)

- 设备 1 ： 一加 15 ColorOS 16.0.9.400 (CN01) KSU 3.3.0 (32601-2) · LSPosed 2.2.0 (7854)
- 设备 2 ： 一加 12 ColorOS 16.0.10.501 (CN01) APatch 0.13.3 (11224) · LSPosed 2.2.0 (7854)

---


## 使用方式

1. **获取模块**：自行构建（见 [docs/build.md](docs/build.md)），或从发布页下载 `DLsiteFloat-<版本>-debug.apk`。
2. **安装并启用**：把 APK 装到已 root 设备 → 打开 **LSPosed Manager** 或同类插件管理器 → 启用本模块 → 作用域**两个都勾**：**`jp.co.eisys.dlsitesound`** + **`com.android.systemui`** → **强制停止** DLsiteSound，并**重启 SystemUI 或重启手机**。
3. **授予悬浮窗权限**：
   - 设置 → 应用 → DLsiteSound / DLsiteFloat → 权限管理 → 特殊应用权限 → `悬浮窗`。
   - 如有需要，可允许`后台弹出界面`
4. **打开有字幕的播放页**：播放控制条上方出现两个胶囊按钮。
   - 左`状态栏 开 / 状态栏 关`：单击开关**状态栏字幕**（默认关）；无字幕或未授权 `com.android.systemui` 作用域时该按钮不出现。
   - 右`悬浮窗 开 / 悬浮窗 关`：单击开关**悬浮窗**。
   - 无字幕时显示`无字幕`且悬浮窗不可开。
   - 离开播放页（回到首页 / 列表页等）按钮自动隐藏；悬浮窗与状态栏字幕都不随页面隐藏。
5. **操作悬浮窗**：面板任意处按住拖动可移动；右下角手柄拖动可缩放。点面板（非手柄）可切换右上角关闭按钮（✕，30dp 显示 / 点击区）的显隐；点 ✕ 关闭窗口。
6. 悬浮窗字幕和状态栏字幕可同时使用，二者相互不干扰。


---


## 意见反馈

- **GitHub Issues**（首选）：请在项目仓库的 Issues 区提交，并尽量附上：
  1. 你的设备型号 / Android 版本 / ROM（尤其是否 ColorOS / 澎湃 / 原生 / 类原生 等）
  2. 使用的 DLsiteSound 的版本号
  3. 复现步骤 + LSPosed 日志（`DLsiteSoundFloat` 过滤）+ 必要时 `dlsitefloat_net.log`+ 录屏 / 截屏（请给敏感信息打码或进行截除）
- **提交前请先确认**：装的是不是最新 APK（看 LSPosed 日志里 `==== BUILD 2.1.2 / code 954` 那一行）
- 仓库地址：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle>
- 提交 Issue：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/issues>


---


## 免责声明

1. 本项目主要用于个人技术研究 / 学习目的；作者**不鼓励不建议**将其用于商业用途。
2. 本模块由 AI 辅助开发，仅能保证在开发过程中绝不包含人工主观恶意输入 & 恶意行为。 AI 产生的代码可能无法完全符合开发预期，请在使用前自行确认。
3. 本项目只研究**客户端字幕显示行为**，不绕过任何 DRM，不修改、不提取受版权保护的音频/文本内容本体。字幕文件由 DLsiteSound 官方服务器下发，请尊重内容版权方权益，请通过正规渠道购买与支持作品。
4. 本模块与 DLsiteSound（eisys 株式会社）及各手机厂商**无任何隶属或合作关系**。
5. 使用本模块需要 **Root 与 Xposed 框架**，可能违反部分设备的保修条款或某些 ROM 的安全策略，可能带来系统不稳定、失去保修、安全风险等后果，**由此带来的任何风险由使用者自行承担**；因使用本模块导致的任何问题（包括但不限于账号异常、设备不稳定、数据丢失等），作者**不承担任何责任**，建议及时并周期性备份常用设备数据。
8. 适配依赖目标 App 的内部实现，App 更新可能导致模块失效；作者**不保证**模块长期可用或对所有版本 / 机型都适配。
9. 下载、使用本模块即视为同意上述条款。


---


## 特别感谢

- 状态栏字幕效果呈现参考：[ColorOS Mod](https://github.com/rikumi/coloros-mod)


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


## 目录结构（简）

```
DLsiteSound_FloatSubtitle/
├── app/src/main/
│   ├── AndroidManifest.xml          # 模块元数据（模块名 / 简介走 android:label / android:description）
│   ├── resources/META-INF/xposed/   # 入口与作用域：java_init.list / scope.list / module.prop
│   └── java/io/github/ariinyume/dlsitesoundfloat/
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
├── LICENSE                          # GPL-3.0 许可证全文
└── README.md
```
