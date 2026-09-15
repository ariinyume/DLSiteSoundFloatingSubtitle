# 日志与排查

> 📦 本文档汇总完整的日志对照表（含首页精简版中省略的条目），以及排障顺序。

## 1. 日志过滤关键字

在 LSPosed 日志里过滤 `DLsiteSoundFloat`：

| Tag | 内容 |
| --- | --- |
| `[DLsiteSoundFloat:Network]` | 网络拦截 / 字幕 JSON 解析 |
| `[DLsiteSoundFloat:Source]` | 音轨切换（含序号确认与假换轨忽略） |
| `[DLsiteSoundFloat:View]` | 字幕视图识别与页面判定（含 `verdict=` 证据） |
| `[DLsiteSoundFloat:Button]` | 按钮生命周期与"是否播放页"判定 / 结构事件加急 / 跟手位移（v37：`page follow on: … (ref=… vis=… base=… ch=transform|baseline)` 每 64px 补一行；另有 `page follow channel: transform-sum active`、`page follow bias calibrated: …`、`page follow rebase: …`、`hide suppressed: page held (vis=…px ref=…)`、`page follow skipped: no baseline & no transform channel`） |
| `[DLsiteSoundFloat:Window]` | 悬浮窗显隐 / 拖拽 / 缩放 / 几何恢复 / 权限失败 |
| `[DLsiteSoundFloat:Pos]` | 播放进度采集 + ExoPlayer 播放状态（v29「播放结束」判定的数据源） |
| `[DLsiteSoundFloat:Watcher]` | 宿主视图树结构事件钩子的装载结果（v34；每条事件本身记在 `Button` 下） |

## 2. 页面判定 `verdict=` 证据行

| 字段 | 含义 | 期望 |
| --- | --- | --- |
| `paired=true(N)` | 主滑条是否配上 N 个时间文本 | 播放页**恒为 true**；若为 false 会看到 `anchor adopted` 兜底 |
| `rejected=N` | 被"页面容器可见面积 <60%"否掉的滑条数 | 只在**切页瞬间** >0；常年 >0 说明门太严（调 `PAGE_CONTAINER_MIN_VISIBLE_RATIO`） |
| `anchor=<cls hNNNN visNN% kidsN>` | 锚点容器及其可见面积 | `vis` 常年接近 100% 说明锚点选得过高（提高 `ANCHOR_MIN_H_RATIO`） |
| `anchor dead for Nms (area=… evidence=… need=… samples=… still=NNms)` | 锚点已连续失效多久 + 判据原始量 | v30 起是**墙钟口径**；`need` 通常是 600ms（有正面证据时 200ms）。v38 起 `still=` = 页面已静止多久，**确认窗实际从 `max(锚点判死, 最后运动)` 起算** |
| `page follow resume: catch-up Npx after Nms` | **跟手断层**（v39）：按钮冻了多久、一帧补跳多少 px | 修 v39 前实测 86/86 次重启有 ≥100ms 停顿（p50 587ms）、补跳 p50 122px。这行应基本不出现 |
| `subtitle scroll: cue A->B travel=Npx scrollDelta=Mpx` | **字幕上滚**（v40 新增，每次换句一行）：`travel` = 上一句块中心 → 这一句块中心的几何距离（**动效真正走的距离**），`scrollDelta` = 新旧滚动量之差 | v40 前 `travel` 根本没算，动效靠 `scrollDelta` 驱动；`scrollDelta=0` 就是"下一句直接出现"。修好后应看到 `travel≈一行高`（几十 px）而 `scrollDelta` 常为 0 |

> OTHER 必须**连出 2 次且持续 ≥250ms** 才跟 `button hidden` —— 这就是"闪一下"被吃掉的判据。

### 2.1 v34：按钮显隐延迟的两条新日志

| 日志 | 含义 |
| --- | --- |
| `structure event -> instant scan [addView] #N` | 宿主视图树动了 → 立刻触发一次扫描。方括号是事件类型（`addView` / `removeView` / `removeViewAt` / `removeAllViews` / `setVisibility` / `setTranslationX` / `setTranslationY` / `setAlpha` / `anchor attached` / `anchor detached`） |
| `button shown (player page) after structure event +XXms` | **真实端到端延迟**：从本轮结构事件爆发的第一个事件，到按钮真的出现 |
| `button hidden (…) after structure event +XXms` | 同上，隐藏方向 |
| `anchor detached from window -> hide (hard evidence)` | 锚点被从窗口摘除 → 硬证据，**跳过 600ms 确认窗**立即隐藏 |
| `anchor attach-state watched` | 已给锚点挂上挂载/摘除监听（每次换锚点打一次） |

> 期望：进 / 出播放页的 `+XXms` 落在 **30 ~ 150ms**（v33 之前最坏 600ms+）。
> 若 `after structure event` 缺失（显示 `no structure event nearby`），说明那次页面切换
> 没触发任何结构事件 —— 需要把日志反馈回来重新选钩子点。

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

## 4. 悬浮窗自动关闭 / 自动恢复（v29）

窗口打开后**不会自己消失**，只有下面这几种情况会关闭：

| 触发 | 日志 | 说明 |
| --- | --- | --- |
| 用户点 ✕ / 点按钮切换 | `close button CLICKED -> setFloatingWindowOpen(false)` | 用户决定，之后**不会**被自动重开 |
| 切到**无字幕**音轨 | `track decision: NO subtitles for this track … -> auto-closed floating window` | 等 3 秒仍没等到字幕 JSON |
| **播放结束**（v29 新增） | `playback ended (state 3->4) -> auto-closed floating window` | ExoPlayer 进入 `STATE_ENDED`；重新开播会自动恢复 |
| 悬浮窗权限被收回 | `[DLsiteSoundFloat:Window] addView FAILED: …` | 同时把开关回退成「悬浮关」 |

被**自动**关掉的窗口会在下列情况自动恢复（用户手动关掉的不在此列）：

| 恢复日志 | 场景 |
| --- | --- |
| `subtitles arrived -> reopen floating window (auto-closed earlier)` | 新音轨的字幕 JSON 到达 |
| `playback resumed (state 4->3) -> reopen floating window (auto-closed on playback end)` | 播放结束后重新开播 |
| `new track after playback end -> reopen floating window` | 播放结束后切到别的音轨 |

> ⚠️ 「播放结束」带 **400ms 延迟确认**：自动连播时播放器可能短暂进入 `ENDED` 后立刻开始下一首，
> 此刻马上关窗会让窗口"闪一下"。确认期内状态若已变回，会打
> `playback end not confirmed (state came back to N) -> keep floating window` 并保留窗口。
> 状态取值来自 Media3 `Player`：1=IDLE / 2=BUFFERING / 3=READY / 4=ENDED。
> **IDLE 不关窗**（加载新内容时也会短暂 IDLE，据此关窗会误伤）。

窗口几何（v29）：尺寸 / 位置会在关窗前被记住，重开时恢复并按当前屏幕夹取 ——
日志 `floating window shown (…, 1081x595 at 60,953 [geometry restored])`；
`[default geometry]` 表示本次用默认值（从未调整过）。**仅同一进程内有效**（目标 App 进程被杀后回默认）。

## 5. 网络诊断日志

`/sdcard/Download/dlsitefloat_net.log` —— 响应的 URL / body 长度 / 是否含字幕 JSON，
用于定位真实字幕接口。**v33 起只记录「字幕候选」响应**（外加前 30 条被略过的），
不再对每个响应都写盘。

### 5.1 v33：网络钩子只碰字幕，修「音频缓存少了开头 44 秒」

**症状**：模块开启时做「音频缓存」，缓存下来的音轨**开头少一段**（实测 44 秒）；
在 LSPosed 里关掉模块再缓存就正常 → 责任在本模块的网络钩子。

**根因**：v32 及以前，`okhttp3.Response$Builder.build()` 的 after 钩子对**每一个**响应无条件做两件重活：

1. `peekBody(PEEK_LIMIT)` —— okhttp 的 `peekBody` 会把 body **读进内存**再返回，
   也就是在 App 数据源的 `open()` 路径上硬加一次「必须先同步读完 1MB」的阻塞读；
2. 同步写一行 `dlsitefloat_net.log`（open → write → flush → close）。

缓存期间响应又多又大，两者叠加足以让**开头几个分片超时/写失败** → 缓存里留下空洞 →
播放时表现就是「前面的音频丢了」。

**修法**：给 peek 加白名单 `isSubtitleCandidate()`，四条判据（先便宜后昂贵）：

| # | 判据 | 结论 |
| --- | --- | --- |
| ① | URL 含 `/optimized/` 或结尾 `.json` | **peek**（字幕固定放这儿，最可信） |
| ② | 状态码 ≠ 200（典型 206 分片） | 略过 |
| ③ | `content-length` > 256KB | 略过 |
| ④ | `content-type` 命中 audio/video/image/octet-stream/mpeg/mp4/zip/font/pdf | 略过 |

其余（小体积文本类响应）仍按老办法 peek，兼容「URL 取不到」的兜底路径。
同时 `PEEK_LIMIT` 由 **1MB → 256KB**（字幕 JSON 实测只有几 KB）。

### 5.2 v34：v33 的白名单**没拦住音频**（44s → 10s 那一版）

**症状**：v33 装机后缓存音频**仍然缺开头**，只是从 44 秒缩到约 **10 秒**。

**根因**：**音频文件本身就放在 `/optimized/` 目录下** —— 目录名区分不了音频与字幕：

```
…/RJ01695219/optimized/bf225a84341ad6c28a693d10842a91d2.mp3     ← 音频
…/RJ01695219/optimized/79d82fcb92f012c58a24dbe653c8e1dd.json    ← 字幕
```

而 v33 的白名单第 ① 条（`URL 含 /optimized/` → 判为字幕候选）**排在所有拒绝条件之前**，
于是每个 mp3 及其 206 分片照样走 `peekBody`。唯一变化只是 `PEEK_LIMIT` 1MB → 256KB ——
**缺口长度正好随 peek 大小线性变化：44s × (256KB/1MB) ≈ 11s ≈ 实测的 10s**。
这个比例关系就是「peek 是元凶」最硬的证据。

**修法（v34）**：

1. **判据顺序反过来：先拒绝、后放行。**
2. 只认 **`.json`**：去掉 `/optimized/` 这条（错误的强判据），改成
   「URL 含 `.json`」或「content-type 含 json」才 peek。
3. 新增**媒体扩展名**黑名单（`.mp3/.m4a/.aac/.wav/.ogg/.opus/.flac/.mp4/.webm/.m3u8/.jpg/.png/…`）
   —— 这是唯一能把音频挡住的判据。
4. 删掉 `java.net.URL.toString()` 那条兜底日志钩子（早期用来找字幕接口的；
   现在字幕已稳定从 `Response$Builder.build()` 拿到，它只剩开销 ——
   日志里成串的 `URL captured: …mp3` 就是它打的）。
5. `PEEK_LIMIT` 256KB → **128KB**；被略过的响应**连 NetLogFile 都不写**。

**验证方法**：缓存期间看 logcat 有没有这行（**出现即说明确实碰到过媒体响应**）：

```
[DLsiteSoundFloat:Network] skip non-subtitle response (no peek): ctype=… code=… url=…
```

正常情况下它**不该出现**；若出现，看 `ctype` / `code` 就知道我们的白名单漏了哪种响应。
**另**：缓存完成后搜 `URL captured: ` —— v34 起**应该一条都没有**（那条钩子已删）。

## 6. 排障顺序（血泪经验）

1. **先确认悬浮窗权限**：**DLsiteSound 与模块 App 各授一次**「悬浮窗」权限（路径见 [build.md](build.md)）。
   窗口 / 按钮完全出不来时优先查这里——权限缺失会在 `[DLsiteSoundFloat:Window]` 下记权限失败。
2. **再确认版本**：看日志里的 `==== BUILD … ====` 一行（1.20.5 起还有 `build applicationId=… versionName=…`）。
   绝大多数"功能没生效"其实只是**装了旧包**。
3. **然后看功能链路日志**：按上面第 2、3 节的对照表逐条核对。
4. **最后才怀疑逻辑**：改判定代码前，务必先读 [page-detection.md](page-detection.md) 和
   [track-change.md](track-change.md)——这两块的坑都复发过 7 次以上。

## 7. 版本确认

| 版本 | BUILD 描述 |
| --- | --- |
| 1.20.4（v27） | `track-change index verification + spurious-change guard` |
| 1.20.5（v28） | `index-stale-guard + playlist-reset-guard + spurious-criterion-fix` |
| 1.20.6（v29+v30） | `window-geometry-restore + playback-ended-autoclose + button-latency: anchor-probe/wall-clock/positive-evidence + module-meta: name/desc/icon` |
| 1.20.7（v31） | `button-anchored-to-transport-row`（**已废弃**：按钮跟着主滑条动 → 摆幅 248px + 布局回环） |
| 1.20.8（v32） | `revert-button-anchoring: static-bottom-96dp/width-76dp` |
| 1.20.9（v33） | `fix-audio-cache: network peek is subtitle-only now, media/206 responses never touched`（**不彻底**：见 §5.2） |
| **1.20.10（v34）** | `fix-audio-cache-v2: peek 只认 .json，音频在 /optimized/ 也被挡住；+ host-event driven button: ViewGroup/translate/alpha hooks -> instant show; + anchor-detach hard evidence` |
| **1.20.11（v35）** | `page-follow: play-button/anchor tracked at frame rate via Choreographer + setTranslationY; baseline = settled PLAYER scan; suppress OTHER-hide while page held`（**缺陷版**：往下钳 277px 撞线、基线被反复覆盖、循环被杀 → 见 1.20.12） |
| **1.20.12（v36）** | `page-follow-fix: baseline no longer overwritten mid-drag[gate3 read wrong field]; clamp by screen position not screen-ratio[down was 277px vs 985px travel]; frame loop survives transient ref miss; sticky follow-ref; play-button scan depth8/budget2000/reverse`（**仍不达标**：位置钳制往下只有 262px、参考点换人导致 -2015px 飞到上方、hide 顺手扔基线 → 见 1.20.13） |
| **1.20.13（v37）** | `page-follow-v2: baseline-free transform-sum offset[Σ translationY − Σ scrollY over ancestor chain, bias calibrated at settled PLAYER]; clamp removed -> 1:1 follow; ref-switch rebases baseline[fixes -2015px fly-to-top]; baseline survives hide; hold-gate now baseline-free and also guards the no-evidence hide`（跟手已达标；**但上下滑动时按钮会闪消失 331ms 再闪回来** → 见 1.20.14） |
| **1.20.14（v38）** | `anti-flicker: (1) leave-page confirmation window now starts at the page's LAST MOVEMENT, not at first anchor-dead; (2) hold-gate gains a displacement-snapshot criterion that survives the ref/area going invalid at max drag [23/23 hides happened while page was dragged 2710-2772px of a 2772px screen]; motion timestamp refreshed only on real displacement + also from the 55ms scan heartbeat; anchor-dead + hide diags now print still=/snap=/attached=`（**闪消失已治好**：修复后 92s 内 28 次抑制 / **0 次隐藏**；**但位移仍会闪跳** → 见 1.20.15） |
| **1.20.15（v39）** | `follow no longer stalls mid-drag: frame loop used to self-deregister after 12 still frames, so a page held at the drag limit froze the button 300-1700ms then caught up 122-2769px in one frame [86/86 restarts had >=100ms stalls, p50 587ms]; it now keeps running while the page is displaced from rest (FOLLOW_MAX_HELD_MS=2min), re-arms from the 55ms scan heartbeat, snaps the sub-deadzone residual to 0 [was 20-107px], and syncs the offset BEFORE the button becomes visible [was 345-448px pop-in jump]; new diag: page follow resume: catch-up Npx after Nms` |
| **1.20.16（v40）** | `subtitle scroll-up animation: was driven by the OLD->NEW scroll offset delta, but content is rebuilt centred on the new cue so that delta is 0 whenever two consecutive cues have the same line count -> the line just swapped in place (root cause of "sometimes no animation"); now the scroll offset is applied instantly and the travel is animated explicitly as "previous cue block centre -> current cue block centre" via container translationY, staged in a pre-draw pass so the first frame already starts from the old position; renderCues records per-cue child ranges (cueBlockStart/End), cancelScrollAnim() resets translationY; new diag: subtitle scroll: cue A->B travel=Npx scrollDelta=Mpx`（改动全在 `view/FloatingSubtitleView.java`，与按钮跟手无关） |
| **1.21.0（v41）** | `new launcher icon: r=300 rounded corners, smooth edges (no grey rim), outer lifted shadow only [soft ambient halo removed]; regenerated at mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi with proportional scaling so the shadow stays crisp; version bumped to 1.21.0 / code 12100`（纯资源 + 版本号变更，`ic_launcher.png` 五套密度重写；代码逻辑未动） |

> ⚠️ 曾有**同日两个同名 1.20.4**（19:05 的 v26 与 22:11 的 v27），只能靠 BUILD 描述区分。
