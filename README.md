# DLsiteFloat —— DLsiteSound 悬浮字幕模块

DLSiteSound 悬浮字幕窗（纯 AI 项目，无人工代码）

> 一个 LSPosed / Xposed 模块，在 DLsiteSound（DLsite 音频 App）的**播放页**上挂一个与播放进度同步的**系统级悬浮字幕窗**。
> 当前版本：**1.20.4**（`DLsiteFloat-1.20.4-debug.apk`）

---

## 一、功能特点

- **按播放进度对齐字幕**：以播放器（`ExoPlayer` / expo 音频）真实播放进度为主轴对齐字幕行，画面稳定不抖；仅在拿不到进度时回退到"屏上字幕文本"匹配。
- **进程内悬浮窗**：窗口直接挂在 DLsiteSound 自己的进程里（`WindowManager` + `TYPE_APPLICATION_OVERLAY`），与各个 Hook 共享同一个 `SubtitleRepository` 实例，**无需任何跨进程 IPC、无需独立 Service**。App 退到后台、只要进程还活着，窗口仍停在最上层。
- **自动抓取字幕**：拦截 DLsiteSound 的网络响应（okhttp3），直接扫描响应体是否包含 `webvtt` / `subtitles` 字幕 JSON 并解析——**不依赖 URL 关键词**，对混淆/重打包的 okhttp3 也能兜底。字幕源为 DLsiteSound 官方 `play.dl.dlsitesound.com/.../optimized/xxx.json`，按音轨下发。
- **播放页内注入开关按钮**：仅在播放页出现一个原生按钮，文案随状态切换：
  - `悬浮关`：悬浮窗已关闭
  - `悬浮开`：悬浮窗已打开
  - `无字幕`：当前音轨没有可用字幕
- **离开播放页自动隐藏**：用"三态判定 + 播放页锚点 + 三道证据门"识别页面，非播放页（首页 / 列表页 / mini-player 页）自动隐藏，播放页上不再闪断。详见 [第二节](#二页面判定与响应机制核心逻辑)。
- **换轨智能处理**：切到无字幕音轨时先挂起并显示"无字幕"，3 秒内若字幕 JSON 到达则自动恢复；否则判定无字幕、清空并自动关窗。同时用**「曲目序号二次确认 + 播放位置回退兜底」**识别**假换轨**（如第一轨按「上一首」时 App 是空操作），此时**保留字幕、不关窗**。详见 [第三节](#三换轨判定与假换轨保护核心逻辑)。手动关掉的窗口不会被字幕晚到重新打开。
- **可拖动 / 可缩放**：面板任意处按住拖动移动；右下角手柄拖动缩放（默认 85% 屏宽 × 200dp，最小 140×72dp，**纵向最长不超过半屏**）。
- **阅读体验优化**：窗口上下留白最多半屏；**首行 / 末行强制居中**（即使播到第一条或最后一条字幕，当前行也停在窗口正中）；文字四周最小留白 **15dp**；换行切换时做 **360ms 平滑上滚**动画。
- **玻璃磨砂质感**：自绘 `GlassPanelDrawable` 实现半透明磨砂底，**不启用系统级 `FLAG_BLUR_BEHIND`**（ColorOS 上那玩意会糊掉整个屏幕）。当前行加粗高亮、字号更大，其余行为半透明上下文，无滚动条。
- **权限引导与降级**：未授予悬浮窗权限时，首次点击会跳到"在其他应用上层显示"设置页，且**只提示一次**；OPPO/ColorOS 下绕过 `canDrawOverlays()` 误报，直接尝试挂载用真实结果判断。

---

## 二、页面判定与响应机制（核心逻辑）

> ⚠️ 这是本项目**最容易改坏**的部分（同一个 bug 复发过多次），改动前请先读完本节与 `SubtitleViewHook` 的类注释。

### 2.1 三态判定：**"扫不到滑条" ≠ "不是播放页"**

扫描器每轮在视图树里找"宽滑条"（宽度 ≥ 45% 屏宽的原生 `SeekBar` 家族），据此给出三种结论：

| 结论 | 触发条件 | 动作 |
| --- | --- | --- |
| `PAGE_PLAYER` | 找到**主滑条**（纵向 ≤ 82% 屏高，**且**与时间文本配对成功） | 立即显示按钮 |
| `PAGE_OTHER` | 只找到**底部 mini-player 滑条**（纵向 > 82%） | 隐藏按钮 |
| `PAGE_UNKNOWN` | 一条宽滑条都没扫到（首页、转场、被回收） | **不改变现状**，交给「锚点」判断 |

关键点：
- **首页等页面根本没有 SeekBar** → `UNKNOWN`。若把"没证据"直接当"非播放页"，播放页上滑条被回收的瞬间就会把按钮闪掉。
- **列表页 / 首页底部的 mini-player 里也有同宽滑条**，与播放页主滑条**只靠纵向位置区分**。

### 2.2 播放页锚点（`anchorRef`）

判定到 `PAGE_PLAYER` 时，顺手记住主滑条所属的**页面级祖先容器**（弱引用）：

- 滑条扫不到、但**锚点容器仍真实可见** → 还在播放页（只是控制条被回收）→ **保持显示**；
- 锚点**消失 / 不可见 / 被卸载** → 确实离开了 → 隐藏。

锚点存活要求"仍挂在窗口 + VISIBLE + 有尺寸 + 祖先链累计 alpha ≥ 0.05 + 可见面积 ≥ 50%"。

> RN 切页动画期间页面容器会**瞬时不可见**（实测播放页锚点假死 **807ms**），所以锚点失效要**连续 4 次**（≈800ms）才隐藏，避免误闪。

### 2.3 三道证据门（v26 新增，专治过渡期误判）

**问题**：播放页**入场**时页面从屏幕底部滑入，它的主滑条会一路扫过整个下半屏，途中必然穿过 82% 那条绝对位置判据线，被误判成"mini-player 滑条"→ 按钮被误隐藏；**退场**时更极端，`bottom=y=2759(99%)` 的滑条**依然完整在屏内**，纯纵坐标根本拦不住。

**修法**（判据不再依赖"绝对纵坐标"）：

1. **页面容器可见面积门** `PAGE_CONTAINER_MIN_VISIBLE_RATIO = 0.6`：滑条所属页面容器必须在屏上占比 ≥60%（静止态实测 ≈92%~100%，过渡态掉到 ~20%~35%）。**与滑条位置无关**，故比 82% 稳健得多。
2. **主滑条必须与时间文本配对** `MAIN_SLIDER_TIME_LABEL_DY_DP = 80`：播放页主滑条**下沿同排**恒有 `04:48` / `-13:34` 两个时间文本，mini-player 进度条旁一个都没有。配不上就不敢判 `PLAYER`（返回 `UNKNOWN` 交给锚点），**绝不因为"配不上"就判成 OTHER**。
3. **OTHER 去抖** `HIDE_ON_OTHER_STREAK = 2` + `HIDE_ON_OTHER_PROOF_MS = 250`：单帧假阳性不再能把按钮打掉。

### 2.4 响应节奏（自适应）

| 参数 | 值 | 说明 |
| --- | --- | --- |
| 检测节流 | 150ms | `DETECT_MIN_INTERVAL_MS` |
| 跟踪态心跳 | 200ms | 过渡态 / 结论与按钮现状不一致 / 结论刚变（< 4 次） |
| 稳定态心跳 | 600ms | 连续 4 次同结论后省电 |
| 无证据宽限 | 900ms | `NO_ANCHOR_GRACE_MS`（无锚点可参考时的兜底） |
| 锚点硬超时 | 3000ms | `ANCHOR_HARD_TIMEOUT_MS` |

> 心跳是**自带重排的独立 Runnable**（`finally` 里重新 arm），不会因节流 `return` 而断链。

---

## 三、换轨判定与「假换轨」保护（核心逻辑）

> ⚠️ 与第二节并列的"易改坏"区域。核心命题：**「调了方法」≠「真的换轨」**。

### 3.1 要解决的问题

App 的音频是**播放列表**形态（expo `AudioPlaylist`）。在**第一轨**按「上一首」时，App 本身是 no-op（音频继续播、不跳转），但 `AudioPlaylist.previous()` **依然会被调用**。

旧逻辑把这类调用**无条件当成换轨** → 挂起字幕、等 3 秒新字幕 JSON → 期间当然等不到（压根没换轨）→ 判定「本音轨无字幕」→ **清空 cues + 自动关掉悬浮窗**。日志铁证：

```
15:40:36.072 | playerPage=true | seekBar=1153x54 y=1799 seekWidthRatio=90%   ← 播放页正常
15:40:40.092 | track changed via AudioPlaylist.previous | lastJson=15375ms ago | cues=88 -> SUSPEND
15:40:43.092 | track decision: NO subtitles for this track (cues were 88) -> auto-closed floating window
```

### 3.2 第一层：曲目序号二次确认（`PlayerSourceHook`）

把 `AudioPlaylist` 上的方法分成两类：

| 类别 | 方法 | 处理 |
| --- | --- | --- |
| **需序号确认** | `emitTrackChanged` / `next` / `previous` / `skipTo` / `onManualNavigation` | 调用后**再比一次** `getCurrentTrackIndex()`：序号确实变了才当换轨；没变 → 打 `ignored ... (track index unchanged=N, no-op navigation)` 直接忽略 |
| **无条件** | `AudioPlaylist.setMediaSource`；`ExoPlayer` 的 `setMediaItems` / `setMediaItem` / `setMediaSource` / `setMediaSources` | 真的换掉了媒体源 → **无条件**当换轨（并顺便种序号基线） |

序号读不到（方法缺失 / 抛异常）时退化为旧行为（**宁可误报也不漏报**），交给第二层兜底。

### 3.3 序号基线为什么要单独「种」

实测日志显示 **JS 从不调用 `getCurrentTrackIndex()`**（全量日志里一次序号变化都没触发过）。所以只靠"变化才通知"的那个 hook，基线会永远停在「未观测到」→ 上面那道序号门**退化成旧行为、等于没加**。

因此额外在 `AudioPlaylist.setMediaSource` 时**只读取、不通知**地把基线种进去（日志 `seeded track index=N`）。基线永远滞后一个信号，正好就是我们要的"变更前状态"。

> ⚠️ 实现细节：取基线必须**先存 `last`、再调 `readTrackIndex`**。因为 `readTrackIndex` 内部会触发 `getCurrentTrackIndex` 的 after-hook 顺带刷新基线，顺序颠倒会把真实变更误判成"没变"。

### 3.4 第二层：播放位置回退兜底（`SubtitleRepository`）

与 App 内部结构**无关**的一道兜底：真换轨时新音轨总是从 0 附近开始播，**播放位置必然大幅回退**。

待确认的 3 秒窗口内：

| 观察 | 结论 | 动作 |
| --- | --- | --- |
| 位置**回退** ≥ `POSITION_RESET_TOLERANCE_MS`(1000ms) | 确实重开了一轨 | 照旧走无字幕判定 |
| 收到 ≥ `PENDING_MIN_POS_SAMPLES`(2) 次位置回调、且**从未回退** | **假换轨**（`SPURIOUS track change`） | **保留 cues、按当前进度重算当前行、不关窗** |
| 位置不可知（暂停 / 没回调） | 不下结论 | 保持旧行为，避免误留上一轨字幕 |

> 拖动进度条走的是 `seekTo`，**不会**触发以上任何换轨方法，因此不会误清字幕。

### 3.5 相关参数

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `PRELOAD_TOLERANCE_MS` | 3500 | 距上次成功加载字幕 JSON 在此以内 → 视为新音轨预加载，直接保留 |
| `NO_SUBTITLE_GRACE_MS` | 3000 | 换轨后的「待确认」窗口长度 |
| `POSITION_RESET_TOLERANCE_MS` | 1000 | 位置回退多少毫秒以上算"重开一轨" |
| `PENDING_MIN_POS_SAMPLES` | 2 | 至少收到几次位置回调，才敢用"没回退"否定换轨 |
| `DEDUP_MS` | 500 | 同一次切换会命中多个 hook 点，去重窗口 |

---

## 四、适配范围

| 项目 | 说明 |
| --- | --- |
| **目标 App** | DLsiteSound（`jp.co.eisys.dlsitesound`，React Native + expo-audio + ExoPlayer 套壳） |
| **框架** | LSPosed / Xposed 兼容框架；模块作用域**仅** `jp.co.eisys.dlsitesound` |
| **Xposed 最低版本** | `xposedminversion` 要求 **93**（模块编译用 Xposed API 82） |
| **Android 版本** | `minSdk 24`（Android 7.0）起；`targetSdk / compileSdk 34`。Android 8.0+ 用 `TYPE_APPLICATION_OVERLAY`，更低版本回退 `TYPE_PHONE` |
| **机型 / ROM** | 针对 **OPPO / ColorOS** 做了适配（权限检测绕过、整屏模糊规避）；其它厂商 ROM 若悬浮窗/权限逻辑正常也应可用 |
| **字幕生效条件** | 仅当该音轨由官方服务端提供字幕（optimized 字幕 JSON）时生效；无字幕音轨显示"无字幕"，不创建窗口 |
| **不适用** | 非 DLsiteSound 的 App；未 root 或未安装 Xposed 框架的设备；DRM 受限内容本身无字幕的情况 |

> ⚠️ 适配依赖目标 App 的内部实现（视图结构、播放器类名、网络栈），**App 大版本更新可能导致模块失效**，需随版本重新适配。

---

## 五、环境要求

### 运行环境（设备端）
- 已 **root** 的 Android 设备，并安装 **LSPosed（或兼容 Xposed 框架）**。
- Android **7.0（API 24）及以上**。
- 必须给 **DLsiteSound 本身**授予「显示在其他应用上层 / 悬浮窗」权限（窗口挂在它的进程里，不是模块 App 的权限）。

### 构建环境（开发端）
- **JDK 17**
- **Android SDK**：`platform-34` + `build-tools 34.0.0`
- **Gradle 8.4** —— 仓库已内置 Gradle Wrapper（`gradlew`），无需手动安装；首次执行会自动下载 8.4 发行包（本机已有 toolchain 时建议加 `--offline`）
- 依赖 `de.robv.android.xposed:api:82`（`compileOnly`，由 `https://api.xposed.info/` 自动拉取）

```bash
# 设置环境变量（指向 toolchain 里的 JDK / SDK）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 用内置的 Gradle Wrapper 构建（无需预先安装 Gradle）
./gradlew   assembleDebug --offline --no-daemon      # Linux / macOS
gradlew.bat assembleDebug --offline --no-daemon      # Windows
# 产物：app/build/outputs/apk/debug/DLsiteFloat-1.20.4-debug.apk

# 只验语法（更快）
./gradlew   compileDebugJavaWithJavac --offline      # Linux / macOS
gradlew.bat compileDebugJavaWithJavac --offline      # Windows
```

> 仓库已内置 **Gradle Wrapper 8.4**（`gradlew` / `gradlew.bat` / `gradle/wrapper/`），
> 首次执行会自动从官方下载 Gradle 8.4 发行包；本机若已装 Gradle 8.4 也可直接用 `gradle` 命令。

> 版本号规则：`1.20.x` 第三位递增；`versionCode = 1*10000 + 20*100 + x`（如 `1.20.4` → `10204`）。
> ⚠️ **同版本号可能对应多个包**（保留版本号只改 BUILD 描述时）。装机前务必用日志里的 `==== BUILD … ====` 一行确认拿到的是哪一版。

---

## 六、使用方式

1. **获取模块**：自行构建上面的 APK，或从发布页下载 `DLsiteFloat-<版本>-debug.apk`。
2. **安装并启用**：把 APK 装到已 root 设备 → 打开 **LSPosed Manager** → 启用本模块 → 作用域勾选 **`jp.co.eisys.dlsitesound`** → **强制停止** DLsiteSound 后重新打开。
3. **授予悬浮窗权限**：
   - 通用：系统设置 → 应用 → DLsiteSound → 权限 → 显示在其他应用上层。
   - OPPO/ColorOS：设置 → 应用 → 应用管理 → DLsiteSound → 权限 → 悬浮窗（**给 DLsiteSound 授权，给模块授权无效**）。
4. **打开有字幕的播放页**：右下角（播放控制条上方）出现本模块按钮。
   - 点一下在 `悬浮关 / 悬浮开` 间切换；无字幕时按钮显示`无字幕`且不可开。
   - 离开播放页（回到首页 / 列表页等）按钮与悬浮窗自动隐藏。
5. **操作悬浮窗**：面板任意处按住拖动可移动；右下角手柄拖动可缩放。点面板（非手柄）可切换右上角关闭按钮（✕，30dp 显示 / 点击区）的显隐；点 ✕ 关闭窗口。

### 日志与排查
- **LSPosed 日志**过滤关键字 `DLsiteSoundFloat`：
  - `[DLsiteSoundFloat:Network]` 网络拦截 / 字幕 JSON 解析
  - `[DLsiteSoundFloat:Source]` 音轨切换（含序号确认与假换轨忽略）
  - `[DLsiteSoundFloat:View]` 字幕视图识别与页面判定（含 `verdict=` 证据）
  - `[DLsiteSoundFloat:Button]` 按钮生命周期与"是否播放页"判定
  - `[DLsiteSoundFloat:Window]` 悬浮窗显隐 / 拖拽 / 权限失败

- **页面判定 `verdict=` 证据行的关键字段**：

  | 字段 | 含义 | 期望 |
  | --- | --- | --- |
  | `paired=true(N)` | 主滑条是否配上 N 个时间文本 | 播放页**恒为 true**；若为 false 会看到 `anchor adopted` 兜底 |
  | `rejected=N` | 被"页面容器可见面积 <60%"否掉的滑条数 | 只在**切页瞬间** >0；常年 >0 说明门太严（调 `PAGE_CONTAINER_MIN_VISIBLE_RATIO`） |
  | `anchor=<cls hNNNN visNN% kidsN>` | 锚点容器及其可见面积 | `vis` 常年接近 100% 说明锚点选得过高（提高 `ANCHOR_MIN_H_RATIO`） |
  | `anchor=dead #N` | 锚点连续失效次数 | 播放页上出现 `#4 -> hide` 才算真正离开；`#1~#3` 是转场假死 |

  > OTHER 必须**连出 2 次且持续 ≥250ms** 才跟 `button hidden` —— 这就是"闪一下"被吃掉的判据。

- **换轨判定关键日志**：

  | 日志 | 含义 | 说明 |
  | --- | --- | --- |
  | `ignored … (track index unchanged=N, no-op navigation)` | 方法被调、但序号没变 | **这是"假换轨"被挡住的标志**，不该出现 `SUSPEND` |
  | `track changed via … \| pos=Nms` | 真的判定为换轨 | `pos` 是当时的播放位置，便于判断是否真回退 |
  | `seeded track index=N` | 序号基线种入 | 出现在 `AudioPlaylist.setMediaSource` 时 |
  | `track decision: SPURIOUS track change` | 兜底确认是假换轨 | **保留字幕、不关窗**（第二层救回来了） |
  | `track decision: NO subtitles for this track` | 真换轨 + 该音轨无字幕 | 清空 cues + 自动关窗 |
  | `track decision: subtitle json arrived` | 真换轨 + 新字幕已到达 | 保留新字幕 |

- **网络诊断日志**：`/sdcard/Download/dlsitefloat_net.log`（前 150 个响应的 URL / body 长度 / 是否含字幕 JSON），用于定位真实字幕接口。

---

## 七、意见反馈方式

- **GitHub Issues**（首选）：请在项目仓库的 Issues 区提交，并尽量附上：
  1. 你的设备型号 / Android 版本 / ROM（尤其是否 ColorOS / MIUI 等）
  2. DLsiteSound 的版本号
  3. 复现步骤 + LSPosed 日志（`DLsiteSoundFloat` 过滤）+ 必要时 `dlsitefloat_net.log`
- **提交前请先确认**：装的是不是最新 APK——看 LSPosed 日志里的
  `==== BUILD 1.20.4 (track-change index verification + spurious-change guard) ====` 一行，版本不对的话功能不生效多半只是装了旧包。
- 仓库地址：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle>
- 提交 Issue：<https://github.com/ariinyume/DLSiteSoundFloatingSubtitle/issues>

---

## 八、免责声明

1. 本项目**仅用于个人技术研究 / 学习目的**，不得用于任何商业用途或盈利性分发。
2. 本项目只研究**客户端字幕显示行为**，不绕过任何 DRM，不修改、不提取受版权保护的音频/文本内容本体。字幕文件由 DLsiteSound 官方服务器下发，请尊重内容版权方权益。
3. 使用本模块需要 **root 与 Xposed 框架**，可能违反部分设备的保修条款或某些 ROM 的安全策略，**由此带来的任何风险由使用者自行承担**。
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
│       │   ├── PlayerSourceHook.java        # 音轨切换监听（序号二次确认 + 基线种入）
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
├── build.gradle / settings.gradle
└── README.md
```
