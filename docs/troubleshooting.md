# 日志与排查

> 📦 本文档汇总完整的日志对照表（含首页精简版中省略的条目），以及排障顺序。

## 1. 日志过滤关键字

在 LSPosed 日志里过滤 `DLsiteSoundFloat`：

| Tag | 内容 |
| --- | --- |
| `[DLsiteSoundFloat:Network]` | 网络拦截 / 字幕 JSON 解析 |
| `[DLsiteSoundFloat:Source]` | 音轨切换（含序号确认与假换轨忽略） |
| `[DLsiteSoundFloat:View]` | 字幕视图识别与页面判定（含 `verdict=` 证据） |
| `[DLsiteSoundFloat:Button]` | 按钮生命周期与"是否播放页"判定 |
| `[DLsiteSoundFloat:Window]` | 悬浮窗显隐 / 拖拽 / 权限失败 |

## 2. 页面判定 `verdict=` 证据行

| 字段 | 含义 | 期望 |
| --- | --- | --- |
| `paired=true(N)` | 主滑条是否配上 N 个时间文本 | 播放页**恒为 true**；若为 false 会看到 `anchor adopted` 兜底 |
| `rejected=N` | 被"页面容器可见面积 <60%"否掉的滑条数 | 只在**切页瞬间** >0；常年 >0 说明门太严（调 `PAGE_CONTAINER_MIN_VISIBLE_RATIO`） |
| `anchor=<cls hNNNN visNN% kidsN>` | 锚点容器及其可见面积 | `vis` 常年接近 100% 说明锚点选得过高（提高 `ANCHOR_MIN_H_RATIO`） |
| `anchor=dead #N` | 锚点连续失效次数 | 播放页上出现 `#4 -> hide` 才算真正离开；`#1~#3` 是转场假死 |

> OTHER 必须**连出 2 次且持续 ≥250ms** 才跟 `button hidden` —— 这就是"闪一下"被吃掉的判据。

机制详见 [page-detection.md](page-detection.md)。

## 3. 换轨判定关键日志

| 日志 | 含义 | 说明 |
| --- | --- | --- |
| `ignored … (track index unchanged=N, no-op navigation)` | 方法被调、但序号没变 | **这是"假换轨"被挡住的标志**，不该出现 `SUSPEND` |
| `ignored N->M (playlist reset, not a track change)` | 序号从 N 变到 **0** 且 N>1 | **播放列表被重置回第 0 首**（非换轨），忽略、不动字幕 |
| `seeded track index=N from …` | 序号基线种入（首次观测 / 基线过期） | 种基线时**不会**发出换轨通知，避免后台回来误判 |
| `track changed via … \| pos=Nms` | 真的判定为换轨 | `pos` 是当时的播放位置，便于判断是否真回退 |
| `track decision: SPURIOUS track change (sawPositionReset=…, samples=…, startPos=…, curPos=…)` | 兜底确认是假换轨 | **保留字幕、不关窗**（第二层救回来了） |
| `track decision: NO subtitles for this track` | 真换轨 + 该音轨无字幕 | 清空 cues + 自动关窗 |
| `track decision: subtitle json arrived` | 真换轨 + 新字幕已到达 | 保留新字幕 |

> ⚠️ **切轨瞬间播放位置常被清零**（实测 54 次换轨中 52 次 `pos=0ms`），
> 因此"位置是否回退"**不能**当假换轨的主判据（v27 曾因此把所有真换轨误判成假换轨 → 字幕不切）。
> 现在的判据是：**相邻序号变化 = 真换轨**；`X->0`（X>1）= 列表重置忽略；位置大幅回退 = 假换轨兜底。

机制详见 [track-change.md](track-change.md)。

## 4. 网络诊断日志

`/sdcard/Download/dlsitefloat_net.log` —— 前 150 个响应的 URL / body 长度 / 是否含字幕 JSON，
用于定位真实字幕接口。

## 5. 排障顺序（血泪经验）

1. **先确认版本**：看日志里的 `==== BUILD … ====` 一行（1.20.5 起还有 `build applicationId=… versionName=…`）。
   绝大多数"功能没生效"其实只是**装了旧包**。
2. **再看功能链路日志**：按上面第 2、3 节的对照表逐条核对。
3. **最后才怀疑逻辑**：改判定代码前，务必先读 [page-detection.md](page-detection.md) 和
   [track-change.md](track-change.md)——这两块的坑都复发过 7 次以上。

## 6. 版本确认

| 版本 | BUILD 描述 |
| --- | --- |
| 1.20.4（v27） | `track-change index verification + spurious-change guard` |
| **1.20.5（v28）** | `index-stale-guard + playlist-reset-guard + spurious-criterion-fix` |

> ⚠️ 曾有**同日两个同名 1.20.4**（19:05 的 v26 与 22:11 的 v27），只能靠 BUILD 描述区分。
