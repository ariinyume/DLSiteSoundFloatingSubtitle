# 换轨判定与「假换轨」保护（核心逻辑）

> 📦 本文档原为 README 的第三节，为保持首页简洁而拆出。
> ⚠️ 与「页面判定」并列的"易改坏"区域。核心命题：**「调了方法」≠「真的换轨」**。

> 🔴🔴 **2.2.x 已重写判据，读旧章节前先看 [§5.5](#55-226--code-978判据换根从曲目序号到播放列表身份)**。
>
> 下面 **§2 / §3 / §5.1–§5.2 讲的是「曲目序号」判据，已在 2.2.6（code 978）被废弃**
> —— 真机实测证明宿主切作品 / 切音轨时 `currentIndex` **恒为 0**，那套判据一次都没成立过。
> 现在的主判据是 **「播放列表身份」（音轨数 + 总时长 + 序号）**，并配一道**权威门**过滤多实例污染。
> 旧章节保留是为了留档「为什么那条路走不通」，**不要照它改代码**。
>
> 另外 **§5.4 的软裁决、§5.3 的按钮口径仍然有效**，2.2.x 只在它上面叠加，没有推翻。

---

## 1. 要解决的问题

App 的音频是**播放列表**形态（expo `AudioPlaylist`）。在**第一轨**按「上一首」时，App 本身是 no-op（音频继续播、不跳转），但 `AudioPlaylist.previous()` **依然会被调用**。

旧逻辑把这类调用**无条件当成换轨** → 挂起字幕、等 3 秒新字幕 JSON → 期间当然等不到（压根没换轨）→ 判定「本音轨无字幕」→ **清空 cues + 自动关掉悬浮窗**。日志铁证：

```
15:40:36.072 | playerPage=true | seekBar=1153x54 y=1799 seekWidthRatio=90%   ← 播放页正常
15:40:40.092 | track changed via AudioPlaylist.previous | lastJson=15375ms ago | cues=88 -> SUSPEND
15:40:43.092 | track decision: NO subtitles for this track (cues were 88) -> auto-closed floating window
```

---

## 2. 第一层：曲目序号二次确认（`PlayerSourceHook`）

把 `AudioPlaylist` 上的方法分成两类：

| 类别 | 方法 | 处理 |
| --- | --- | --- |
| **需序号确认** | `emitTrackChanged` / `next` / `previous` / `skipTo` / `onManualNavigation` | 调用后**再比一次** `getCurrentTrackIndex()`：序号确实变了才当换轨；没变 → 打 `ignored ... (track index unchanged=N, no-op navigation)` 直接忽略 |
| **无条件** | `AudioPlayer.setMediaSource`；`ExoPlayer` 的 `setMediaItems` / `setMediaItem` / `setMediaSource` / `setMediaSources` | 真的换掉了媒体源 → **无条件**当换轨（并顺便种序号基线） |

序号读不到（方法缺失 / 抛异常）时退化为旧行为（**宁可误报也不漏报**），交给第二层兜底。

---

## 3. 序号基线为什么要单独「种」

实测日志显示 **JS 从不调用 `getCurrentTrackIndex()`**（全量日志里一次序号变化都没触发过）。所以只靠"变化才通知"的那个 hook，基线会永远停在「未观测到」→ 上面那道序号门**退化成旧行为、等于没加**。

因此额外在 `AudioPlayer.setMediaSource` 时**只读取、不通知**地把基线种进去（日志 `seeded track index=N`）。基线永远滞后一个信号，正好就是我们要的"变更前状态"。

> ⚠️ **踩过的坑**：基线必须种在 **`AudioPlayer.setMediaSource`**，不是 `AudioPlaylist` —— 后者根本没有这个方法，
> 挂在它上面会**静默 0 命中**（日志 `[unconditional + seed] (0 methods)`、`seeded` 一次都不出现）。
> 另：取基线要**先存 `last`、再调 `readTrackIndex`** —— `readTrackIndex` 内部会触发 `getCurrentTrackIndex`
> 的 after-hook 顺带刷新基线，顺序颠倒会把真实变更误判成"没变"。

---

## 4. 第二层：播放位置回退兜底（`SubtitleRepository`）

与 App 内部结构**无关**的一道兜底：真换轨时新音轨总是从 0 附近开始播，**播放位置必然大幅回退**。

待确认的 3 秒窗口内：

| 观察 | 结论 | 动作 |
| --- | --- | --- |
| 位置**回退** ≥ `POSITION_RESET_TOLERANCE_MS`(1000ms) | 确实重开了一轨 | 照旧走无字幕判定 |
| 收到 ≥ `PENDING_MIN_POS_SAMPLES`(2) 次位置回调、且**从未回退** | **假换轨**（`SPURIOUS track change`） | **保留 cues、按当前进度重算当前行、不关窗** |
| 位置不可知（暂停 / 没回调） | 不下结论 | 保持旧行为，避免误留上一轨字幕 |

> 拖动进度条走的是 `seekTo`，**不会**触发以上任何换轨方法，因此不会误清字幕。

---

## 5. v28（1.20.5）判据修正 —— 重要

> ⚠️ 这一节是 **1.20.5 才改的**，推翻了 v27 的部分判据，改代码前务必看完。

### 5.1 为什么改

实测 **54 次换轨中有 52 次在切轨瞬间播放位置被清零**（`pos=0ms`）。
于是 v27 那套「位置从未回退 ⇒ 假换轨」的兜底，把**所有真换轨都误判成假换轨** → **字幕不切换**。

同时另一支：从后台切回 / 进程重建后重新读到序号，被当成「首次观测」发出伪换轨通知 →
`SPURIOUS` 分支用被清零的 `playbackPositionMs`(0) 重算 `currentCueIndex` →
**字幕跳回该音轨早已播过的位置**。

### 5.2 现在的判据层级

1. **相邻序号变化 = 真换轨**（主判据）
2. `X->0`（X > 1）= **播放列表被重置**，忽略、不动字幕
   （日志 `ignored N->0 (playlist reset, not a track change)`）
3. 位置大幅回退 = 假换轨**兜底**（不再是主判据）

配套改动：

- **序号基线过期保护** `INDEX_STALE_MS = 30000`：基线超过 30 秒未刷新即视为过期，
  首次观测与过期后观测**只记录 `seeded track index=N`，不再发出换轨通知**。
- `SPURIOUS` 分支**不再用 `playbackPositionMs` 重算 `currentCueIndex`**，
  `onTrackChanged` 也不再无条件 `currentCueIndex = -1`。

---

### 5.3 按钮文案：待确认窗口内就按「无字幕」显示（1.21.12 修复）

「待确认」窗口（`NO_SUBTITLE_GRACE_MS` = 3000ms）里，仓库对外是**三态不一致**的：

| 读取方 | 方法 | pending 时 | 结果 |
| --- | --- | --- | --- |
| 悬浮窗面板 / 状态栏字幕 | `getCues()` / `getCurrentSubtitles()` | 返回**空** | 立刻按「无字幕」显示 ✅ |
| 播放页按钮 | `hasSubtitles()` | 返回 **true**（cues 还没清） | 死等 3 秒才变 ❌ |

`hasSubtitles()` 的注释写着「不把待确认当作无字幕，避免按钮文案闪烁」—— 这是**刻意取舍**，
但 Ari 实测：「从有字幕切到无字幕，按钮用了三秒才切」→ 三处 UI 不一致，改掉。

改法（`ActivityButtonHook.applyButtonText`）：`noSub = !hasSubtitles() || isSuspended()`，
待确认也算「无字幕」，但**切进「无字幕」前压 `BUTTON_NO_SUB_DELAY_MS` = 500ms**：

- 500ms 的依据：全量日志里「换轨 → 字幕 JSON 到达」实测 **115ms**（15:01:03.295 → .410）
  与 **345ms**（15:25:18.527 → .872）→ 500ms 足以让「其实有字幕」的音轨**全程不闪**；
- 真没字幕的音轨 **~0.5s** 表态（原先 3s），快 6 倍；
- 队列只有一份（`sBtnNoSubPending`）；任何其它状态落笔 / 状态一致都 `sBtnNoSubGen++`
  作废排队中的切换 → JSON 一到立刻撤销，不会出现「无字幕 → 悬浮开」的回头闪；
- 延时到点后的重入**必须**传 `allowDelay=false`，否则会自己给自己再排一次、永远落不了笔。

> ⚠️ **只提前表态 UI，不动数据**：3000ms 裁决窗一个字没改，提前显示「无字幕」≠ 清 cues；
> JSON 真迟到时窗口照旧自动恢复（`autoClosedForNoSubtitle`）。

---

### 5.4 软裁决：有 cues 在手时**绝不销毁数据**（1.21.16 修复）

#### 症状

「音频有字幕但插件判无字幕」。实测（2026-09-18 日志，RJ01489000）：

```
11:32:34.285  Loaded 49 cues from JSON      ← 同一作品，有字幕
11:32:37.793  track changed via setMediaSources | cues=49 -> SUSPEND, wait 10000ms
11:32:38.296  button bg: normal (noSub=true)         ← 仅 503ms 后底色就变暗
11:32:47.794  track decision: NO subtitles (cues were 49)   ← 10s 到点，清 cues
11:32:58.772  no subtitles available                  ← 用户点击被静默吞掉
```

画面里字幕一直在（用户截图 11:33:13 可见），但按钮挂「无字幕」且点不动。

#### 三个根因

**A) 把「10 秒没等到 JSON」当成「不存在」的证据 —— 并销毁数据。**

1.21.15 的裁决在「等待期内没有新 JSON」时无条件 `cues = new ArrayList<>()`。
但实测 JSON 到达延迟跨度 **711ms ~ 11973ms**：

| 轮次 | 换轨 | 裁决窗 | JSON 到达 | 延迟 |
| --- | --- | --- | --- | --- |
| R1 | 09:05:41.131 | 10000 | 09:05:41.842 | 711ms |
| R3 | 10:28:56.006 | 10000 | 10:29:01.899 | 5893ms |
| R2 | 09:33:33.649 | 10000 | 09:33:45.622 | **11973ms（已超窗）** |
| R4 | 11:17:04.732 | 10000 | 从未 | — |
| R6 | 11:32:37.793 | 10000 | 从未 | — |

R2 差 1973ms 就踩线。**「N 秒没到」不是「不存在」的证据。**

修法 —— 软裁决 `softNoSubtitles`：

- 裁决时若 `cueCount > 0`：**保留** `cues` / `subtitleLineSet`，只置 `softNoSubtitles = true`，
  显示层据此降级；`playbackPositionMs` / `lastFedMs` **不清**（位置与"有没有字幕"无关）。
- 裁决时若 `cueCount == 0`（本来就没加载过任何字幕）：走**硬裁决**，行为与旧版一致。
- 任何一次新的 `loadFromJson` 成功都会清掉软裁决并 `lastScannedSecond = MIN_VALUE`
  强制重扫 → UI 自动恢复，无需用户重进页面。日志：`soft "no subtitles" verdict revoked`。

> 这是本仓库铁律那条：**「N 秒超时」不是「不存在」的证据 —— 量不到就只降级显示、不销毁数据。**

**B) 底色比文字跑得快 9.5 秒（同粒度被破坏）。**

文字侧受 `BUTTON_NO_SUB_DELAY_MS`（500ms）约束要压队列，而 `updateButtonDrawable`
**内部自己重算**了一次 `noSub` → 底色不受延时保护、立刻落笔。
修法：`updateButtonDrawable(repo, noSub)` 改为接收调用方**同一次**判定的 `noSub`，
文字与底色强制同粒度。

**C) 三处 UI 各拼各的口径。**

按钮文字+底色、长按开关（`StatusBarSubtitleBridge.canToggle`）、状态栏各自拼
`!hasSubtitles() || isSuspended()`。新增**唯一口径**：

```java
public boolean shouldShowNoSubtitles()   // cues.isEmpty() || pendingTrackDecision || softNoSubtitles
```

全部显示层改走它。

#### 附：按钮点击为什么会被静默吞掉

`createButton()` 的 `setOnClickListener` 里 `if (!repo.hasSubtitles()) return;`
—— 硬裁决清空 cues 后，用户点击**没有任何反馈**。1.21.16 改为走
`shouldShowNoSubtitles()` 并打出 `softNoSub=` / `suspended=` / `hasCues=` 三个值，
下次复现能直接看出是哪种「无字幕」。

#### 仍未定论

R4 / R6 期间 **JSON 一次都没到**（全量日志确认零命中）。两种可能：
(a) App 侧压根没为那一轨请求字幕 —— 那裁决在**数据上是正确的**，问题只在 UI 提前表态；
(b) 模块白名单把响应拒了 —— 但 `skip non-subtitle response` 是**一次性日志**，after-the-fact 无法枚举。
1.21.16 的软裁决对 (a)(b) 都成立（都不销毁数据），但要彻底定位需把 skip 日志改成计数。

---

## 5.5 【2.2.6 / code 978】判据换根：从「曲目序号」到「播放列表身份」

> ⚠️ 本节**取代** §2 / §3 / §5.1–§5.2 的主判据。改动集中在 `hook/PlayerSourceHook.java`，
> 去抖闸门的两道阈值与数据层语义均未改动。

### 5.5.1 为什么要换：序号判据从未成立过

2.2.1~2.2.5（code 973~977）连修五轮「切到无字幕轨仍残留上一轨字幕」全部无效。
2026-10-07 的真机录屏 + 全量日志把根因钉死：

- 宿主切「作品 / 音轨」时，播放列表回报的 **`currentIndex` 恒为 0** ——
  六轨列表是 0，切到单轨列表**还是** 0；
- 于是 973~977 里所有以「序号变化」为核心的判据，**一次都没成立过**；
- 真正随切换变化的是**播放列表身份**：`(音轨数, 总时长)`，
  实测从 `(6, 731.832s)` 变成 `(1, 2431.085s)`。

> ★ 这条已上升为仓库铁律：**调判据的「时序」之前，先证明「判据读的字段真的会随事件变化」。**
> 五轮工时都花在了调一个恒定的字段上。

### 5.5.2 现在的判据：三元组身份

换轨判定入口改为 **`onPlaylistStateFromStatusMap(idx, trackCount, durationSec, where)`**
（旧入口 `settleIndexChange` → 更名 `settleChange`）：

```
身份 identity = trackCount * 1e12 + durationMs * 1e3 + idx
```

- **跨作品换列表**：靠 `trackCount` + `durationMs` 两项触发；
- **同一列表内换轨**：靠 `idx` 触发；
- **三者全同** ⇒ 不是换轨。

去抖与长静默两道闸门（`INDEX_DEBOUNCE_MS` / `INDEX_SUPPRESS_MS`）**一字未动**，
继续挡住宿主在多个列表之间规律交替的横跳。

### 5.5.3 第二道根因：多实例读数互相污染

同一份日志还暴露出状态污染：宿主同时持有**多个**播放列表实例
（当前活跃的 / 已停止的「幽灵」列表 `currentTime=0.0` / 闲置的单曲 `AudioPlayer`），
读数交替流进同一个 `consume()`。后果：

| 现象 | 实测 |
|---|---|
| 进度被幽灵实例的 0 值反复拉回起点 | 字幕按旧列表匹配出**上一轨的句子** |
| `playing` 每秒横跳 | **29 次/秒** |
| 播放状态被覆盖 | `playback end not confirmed` **47 次** ⇒ 悬浮窗不关 |

**修法 —— 权威门**：只有 `trackCount > 0 && duration > 0 && !halted` 的那份列表有权更新仓库状态，
已停止实例与闲置播放器只贡献诊断。宿主从未暴露列表时自动退回旧行为，老环境不受影响。

> ★ 顺带澄清一条一直被误读的现象：日志里的 `muted=true / volume=0.0` **不是故障**，
> 是宿主的**扬声器静音保护**（真机提示原文「因将透过设备扬声器输出，正在静音播放」），与字幕无关。

---

## 5.6 【2.2.8 / code 980】切回「有字幕的那条轨」直接恢复

### 5.6.1 症状

- **正向**（有字幕 → 无字幕）：✅ 2.2.1 / 2.2.6 已修好；
- **反向**（无字幕 → 切回原先那条有字幕的轨）：❌ 界面**永久停在「无字幕」**。

### 5.6.2 根因：恢复路径只有一条，而宿主不会再发第二次

字幕数据的**唯一**恢复入口是「一次成功的字幕 JSON 加载」（`loadFromJsonArrayInternal`）。
可是**宿主对同一条音轨的字幕响应有缓存** —— 切走再切回来**不会重新发请求**，
于是网络钩子不会再被触发 ⇒ 模块永远等不到新 JSON。

而 §5.4 的软挂起会在换轨瞬间就置 `softNoSubtitles = true` + `previousTrackCuesHidden = true`
（三处 UI 立刻按「无字幕」呈现、旧 cue 一律不渲染），15s 后软裁决定案 —— **仍然保留这两个标志**。
两者叠加：**数据一条没丢，界面却永久显示「无字幕」。**

真机时间线（`LSPosed_20261007_222354`）：

| 时刻 | 事件 |
|---|---|
| 22:23:18.215 | `Loaded 39 cues`（身份 `tc=7 dur=278640ms idx=0`） |
| 22:23:38.481 | 切走 → `SUSPEND`，`cues=39` 保留（**正确**） |
| 22:23:53.483 | 软裁决定案（**正确**） |
| **22:23:58.746** | 切**回**同一身份 → **又是软挂起** ❌ |
| 此后 | **再无任何 `Loaded`**（该实例 `ihc=7b11b5` 全程在播，位置从未归零） |

> ★ 与仓库铁律同源，但这次的「量不到」不是超时，而是**宿主根本不打算再发一次**。

### 5.6.3 修法（纯增量，阈值一字未改）

1. **归属印章** `cuesOwnerIdentity`：每次成功加载字幕 JSON 时，记下那一刻的活跃播放列表身份；
   硬裁决时清章，普通换轨不动。活跃身份由 `PlayerSourceHook` 推给数据层
   `noteObservedPlaylistIdentity(long)`（**只写 volatile，不触发状态变更**）。
2. **快路径** `tryResumeFromCache(where, newIdentity)`：放在 `onTrackChanged` 最前面 ——
   身份一致 + cues 非空 ⇒ 撤销挂起 / 软裁决 / 隔离，`pendingToken++` 作废排队任务，
   按当前进度重新定位（走 `computeIndexAtSecondLocked`），必要时把因无字幕而自动关掉的窗口开回来。
   **安全性**：印章只在一次成功的字幕加载时盖上，所以「身份一致」等价于「这份 cues 就是这条轨的」，
   渲染它不可能画错内容。
3. **闸门例外**：`settleChange` 的 20s 静默窗原本连「反向同对」也一起挡（那是为防宿主横跳设计的）。
   现在静默前先问 `ownsCuesFor(to)`，命中则**立即放行**并让静默窗失效（`sNotifiedMs = 0`）——
   否则紧接着的真换轨会被「反向同对」吞掉、渲染错内容。
   真机里用户 22:23:45 就切回去了，硬生生被压到 22:23:58 才放行，白挂 13.7s。
4. 出口签名改为 `onTrackChanged(String, boolean, long)`（多带一个身份参数）；
   老信号路径传 0，**行为与之前完全一致**。

---

## 6. 相关参数

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `PRELOAD_TOLERANCE_MS` | 3500 | 距上次成功加载字幕 JSON 在此以内 → 视为新音轨预加载，直接保留 |
| `NO_SUBTITLE_GRACE_MS` | 3000 | 换轨后的「待确认」窗口长度，**手上没有 cues 时**用（**数据裁决**，勿动） |
| `NO_SUBTITLE_GRACE_MS_CACHED` | 15000 | **2.1.x**：手上已有 cues 时放宽到 15s（JSON 到达延迟实测最长 ~15.6s） |
| `NO_SUBTITLE_EARLY_CLOSE_MS` | 5000 | **2.2.1**：5s 内没等到 JSON 就**提前收窗**（JSON 到了会自动开回） |
| `FALSE_NEGATIVE_REPORT_MS` | 60000 | 判「无字幕」后 60s 内 JSON 又到 → 打 `FALSE NEGATIVE` 取证行 |
| `BUTTON_NO_SUB_DELAY_MS` | 500 | **1.21.12**：按钮切进「无字幕」前的短延时（纯 UI 侧，不动上面那个 3000ms） |
| `POSITION_RESET_TOLERANCE_MS` | 1000 | 位置回退多少毫秒以上算"重开一轨" |
| `PENDING_MIN_POS_SAMPLES` | 2 | 至少收到几次位置回调，才敢用"没回退"否定换轨 |
| `DEDUP_MS` | 500 | 同一次切换会命中多个 hook 点，去重窗口 |
| `INDEX_STALE_MS` | 30000 | ~~**v28**：序号基线过期时间~~ **2.2.6 起已随序号判据一并废弃**（仅留档） |
| `INDEX_DEBOUNCE_MS` | 600 | **2.2.4**：同一候选身份再次出现即认定换轨的去抖窗（中途回到原值**不清零**） |
| `INDEX_SUPPRESS_MS` | 20000 | **2.2.4**：上报后 20s 内同一对身份（含反向）不再重复上报；**2.2.8** 对「切回自己有字幕的那条轨」开例外 |

---

## 7. 相关源码

| 文件 | 职责 |
| --- | --- |
| `hook/PlayerSourceHook.java` | 音轨切换监听。**2.2.6 起**：播放列表身份判据（`onPlaylistStateFromStatusMap`）+ 去抖 `settleChange` + 权威门 + 身份回推 `noteObservedPlaylistIdentity` |
| `hook/PlayerPositionHook.java` | 播放进度（`getCurrentPosition`）监听 + 兜底轮询自证 |
| `data/SubtitleRepository.java` | 假换轨兜底判定 + 字幕行重算 + 软挂起 / 软裁决 + **2.2.8**：`cuesOwnerIdentity` 印章与 `tryResumeFromCache` 快路径 |

日志对照表见 [troubleshooting.md](troubleshooting.md)。
