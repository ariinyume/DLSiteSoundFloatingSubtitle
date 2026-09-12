# 换轨判定与「假换轨」保护（核心逻辑）

> 📦 本文档原为 README 的第三节，为保持首页简洁而拆出。
> ⚠️ 与「页面判定」并列的"易改坏"区域。核心命题：**「调了方法」≠「真的换轨」**。

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

## 6. 相关参数

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `PRELOAD_TOLERANCE_MS` | 3500 | 距上次成功加载字幕 JSON 在此以内 → 视为新音轨预加载，直接保留 |
| `NO_SUBTITLE_GRACE_MS` | 3000 | 换轨后的「待确认」窗口长度 |
| `POSITION_RESET_TOLERANCE_MS` | 1000 | 位置回退多少毫秒以上算"重开一轨" |
| `PENDING_MIN_POS_SAMPLES` | 2 | 至少收到几次位置回调，才敢用"没回退"否定换轨 |
| `DEDUP_MS` | 500 | 同一次切换会命中多个 hook 点，去重窗口 |
| `INDEX_STALE_MS` | 30000 | **v28**：序号基线过期时间，过期后只种基线不发通知 |

---

## 7. 相关源码

| 文件 | 职责 |
| --- | --- |
| `hook/PlayerSourceHook.java` | 音轨切换监听（序号二次确认 + 基线种入 + 列表重置识别） |
| `hook/PlayerPositionHook.java` | 播放进度（`getCurrentPosition`）监听 |
| `data/SubtitleRepository.java` | 假换轨兜底判定 + 字幕行重算 |

日志对照表见 [troubleshooting.md](troubleshooting.md)。
