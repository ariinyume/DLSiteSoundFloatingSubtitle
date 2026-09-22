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
| **1.21.1（v42）** | `button initial layout: the subtitle-toggle button is now created at its final bottom-right place (BOTTOM\|END, right=14dp / bottom=96dp, screen-clamped) instead of a top-left placeholder (TOP\|START, left=16dp / top=200dp)——`showButton()` 只在判定为 PLAYER 后才把它挪过去，中间那段「已可见但还没判定」的窗口期按钮会停在 app 左上角，重开播放页才归位 |
| **1.21.2（v43）** | `resume phantom-button fix（切后台再回前台时按钮凭空出现在**非播放页**）`：(1) `pageVisualOffset()` 不再「每读一次就续期」`sHeldOffsetMs` —— 该函数被 55ms 心跳/跟帧循环/`isPageHeld()` 自己反复调用，于是 `HOLD_SNAPSHOT_MS` 这个有效期**永远从 0 起算**、抑制门永不失效（与 v38 注释里「不会赖着不走」的设计意图相反）；(2) `isPageHeld()` 加会话总闸 `sPlayerConfirmedInSession`，本次 onResume 后未确认播放页则一律不放行；(3) `ensureButton()` 不再沿用**过期的** `sLastDecision` 初始化可见性，仅当 onPause 前 ≤1.5s（`RESUME_TRUST_MS`）内确实见过播放页才直接 VISIBLE，否则先 GONE 交给检测。根因：播放页被滑走后容器**停靠在屏外** `translateY=2772px`（= 屏幕高，与真实拖到极限的 2710~2772px 完全同量级，位移无法区分）→ 回前台时按钮以 VISIBLE 重建、且 hide 被永久压制 → 在首页上一挂 20s+（四次复现：19:34 / 19:42 / 19:55 / 20:05） |
| **1.21.3（v44）** | `follow jank fix（「偶尔不跟手」）`：**先排除公式问题**——把跟帧写入的 `vis` 与检测扫描读到的**主滑条屏幕 y**（`getLocationOnScreen`，独立真值）在同一时间网格上比增长率，11:19:56 那一段是 `1.00 / 1.03 / 0.95 / 1.07 / 0.86`，一次 2700px→30px 的完整回弹也逐帧咬住 → **通道是准的**（用「日志间隔」推帧率会被 `FOLLOW_LOG_STEP_PX=64px` 的补打阈值骗到，必须先排除）。真因是**跟帧回调与自己的整树扫描抢同一个主线程**：拖动时宿主每帧 `setTranslationY` → 结构事件按 50ms 合并 → 每趟一次整树扫描（实测 6~8ms）+ 2 行长日志，实测 `11:18:30.398→30.508` 按钮**整整 110ms 没动**（页面同期走 ~85px），那一窗里刚跑完 2 次扫描、写了 6 行日志。修法三处：(1) `detectAndLayout()` 在「跟帧循环正在跟 + 页面 200ms 内动过」时**整趟跳过扫描**（拖动期间扫描无决策价值），心跳改 120ms，页面一停自动恢复，恢复时补打 `page follow quiet: skipped N scans while dragging`；(2) `FOLLOW_STILL_FRAMES` 12→90（停手后循环热待机 ~1s 再注销，消除连续上下揉时循环被反复拆装导致的起手断档；实测 68s 内被拆装 14 次）；(3) 连带修 `captureFollowBaseline()` 的门①：由「循环没在跑」改为「循环报告页面已静止」（新增 `followReportsStill()`），否则第 (2) 条会把基线 / transform 偏置的标定推迟 1 秒，**反倒让跟手更晚就绪** |
| **1.21.4（v45 起）** | `status-bar subtitle（状态栏常驻字幕）`首发：SystemUI 侧新增 `hook/StatusBarSubtitleHook.java` + `util/StatusBarSubtitleBridge.java`，钩 `PhoneStatusBarView#onAttachedToWindow`，在状态栏起始区注入 `FrameLayout`（通知数徽标 + 单行字幕）；App 侧经跨进程广播送行（`EXTRA_LINE/ENABLED/DURATION_MS/PLAYING`）；悬浮窗按钮长按 2s 开关，app 启动默认关；新增 `assets/xposed_scope` 自动勾作用域。详见 `docs/statusbar-subtitle.md` |
| **1.21.5（v46）** | `RECEIVER_EXPORTED`：Android 13+（API 33+）动态注册广播必须声明可见性标志，App 进程与 SystemUI 进程 UID 不同 → 必须 `RECEIVER_EXPORTED`，否则注册即抛 `SecurityException`、字幕一个字都收不到 |
| **1.21.6（v47）** | **同一修复真的生效版**：1.21.5 对 `StatusBarSubtitleHook.java` 连续三次 Edit，后两次被**静默覆盖丢失**（`BUILD SUCCESSFUL`、类名字符串指纹全 PASS，dex 里却还是 2 参 `registerReceiver`）→ 改「整文件 Write」重做，并把「dex 级硬验证」定为发版前置（解析 method_id 确认 3 参 `registerReceiver(...,int)` 真在包里，不再只信字符串指纹） |
| **1.21.7（v48）** | 视觉/行为收口：字体字号字距**对齐状态栏时钟**（`StatClock`）；滚动改 `ValueAnimator` 驱动的**单程左移**（系统跑马灯会到头弹回起点）；显示期间**遮挡时钟 + 左侧通知图标**并画「实心圆 + 数字」通知数徽标；检测到暂停即隐藏、恢复播放再显示 |
| **1.21.8（v49）** | 五项：①**切页时状态栏时钟弹出**——原实现一次性抓时钟引用，状态栏重建换实例后压不住，且低频复检只认旧引用 → 逐次重收集 + `hook View.setVisibility` 事件级压制 + `OnGlobalLayout` 复检；②**浅色底仍白字**——颜色原只在首次显示同步一次 → 每次显示/心跳/布局重读 `getCurrentTextColor()` 并 hook `TextView.setTextColor` 做事件镜像；③**字幕被流体云压住**——原宽 451px 时钻进胶囊下方约 119px，改为动态探测胶囊左界并卡住右界（当时靠猜名 + 几何兜底，**猜名全落空**，见 1.21.9）；④新字幕行先静置 100ms 再起滚；⑤按钮长按开关 2s → 1s。另：`attach` 时把状态栏视图树 dump 进日志 |
| **1.21.9（v50）** | 四项：①**暂停后时钟不恢复**——还原被遮挡视图的动作本身就是一次 `setVisibility(VISIBLE)`，被自己的压制 hook 当成「SystemUI 又露出来」立刻压回 GONE，且快照已 `clear()`，时钟再也回不来；改为「先落 `sSuppressed` 旗标 + 开 `sRestoring` 门再还原」并统计 `restored` 条数；②**浅色底字形糊边**——去掉硬写的 `setShadowLayer(1.5f,0,1,0xCC000000)`，阴影改为**跟随状态栏时钟**（时钟无阴影则不加）；③**字幕仍被流体云遮挡**——照搬 base.apk（`com.rikumi.colorosmod`）的 `updateLyricWidth`：实机 dump 实锤流体云容器真身是 **`seeding_card_container`**（类名 `CapsulePluginContainer`，之前猜的 15 个名字一个都不存在），取容器内**最左可见子视图**左界 −2dp（**递归** + 过滤 `alpha<=0.01` 的隐形子视图），右界再与 `status_bar_start_side_container` 右界、`cutout_space_view` 左界取最小，**宽度不变则不重排**；④新行起滚静置 100ms → **200ms**；另：颜色日志按 RGB 节流（ColorOS 时钟 alpha 渐变动画曾一轮刷 25 行）。宽度算法与关键视图表见 `docs/statusbar-subtitle.md` |
| **1.21.10（v51）** | 两项：①**流体云伸缩时字幕滚动突然加快**——`restartScrollForNewWidth` 用「上一轮剩余时间 ÷ 全程距离」反推速度，而流体云收缩是逐帧动画、宽度每帧变 1~2px（实机日志 640ms 内 333/329/331/332/333 五次），每 restart 一次 `sScrollDurMs`(剩余时间) 变小、`sScrollTarget`(全程距离)不变 → 速度被逐次放大；改为在 `startScroll` 时定死绝对速度 `sScrollSpeedPxPerMs`，续滚只做 `remain=(target-cur)/speed`，且目标变化 <8px 不重启动画；②**字幕前后抖一下**——同一根因的副产品（每帧 `setLayoutParams` + `cancel/start` 动画），加宽度迟滞 6px + 150ms 去抖合并，且变宽后 `target<=0`/`cur>=target` 时**停在当前位置**不再跳回行首或倒退；另修 `left` 基线：原用 `screenX(sLineView)` 带滚动偏移，同一右界算出 307px 与 463px 两个值（差 156px），改用 `screenX(sContainer)+getLeft()`。详见 `docs/statusbar-subtitle.md` §5.1 |
| **1.21.11（v52）** | 三项：①**流体云从无到出现时滚动速度变了**——1.21.10 虽然改对了速度公式，但宽度变化时仍然是「cancel + 重启动画」，`remain` 被 200ms 下限 / 420px/s 上限夹过之后已不等于原速度；改为**动态目标** `sLiveTargetPx`：宽度变化只更新目标与裁剪宽度，动画每帧比对（变小则到线即停、不倒退），变大则本段自然跑完后按**已定死的原速度**续滚（`距离÷速度`），全程不 cancel、不重算速度、不 `setScrollX` 瞬移；②**字幕整体右移了一个通知徽标的宽度**——实机截图量出参考插件「无通知」那行歌词起点屏 66px、我们 110px，差 44px 恰好是 `BADGE_SIZE(13dp)+BADGE_GAP(2dp)`；改为默认顶到最左、有通知时由 `applyBadgeInset()` 补 15dp；③**按钮显示「无字幕」时状态栏字幕未强制作废**——新增 `StatusBarSubtitleBridge.forceDisabledWhenNoSubtitles`（仓库观察者驱动）与 `canToggle()`，长按不翻转、不广播、不变色。另：`android.animation.Animator` 接口没有 `isCancelled()`，改用动画代次 `sScrollGen` 区分自然结束/主动取消。详见 `docs/statusbar-subtitle.md` §4.1 / §5.2 |
| **1.21.12（v53）** | 两项：①**状态栏字幕左边的圆形通知数徽标比字幕亮**——`applyBadgeStyle()` 原先写 `clockArgb \| 0xFF000000` 强制不透明，而字幕文字用的是**原样的**时钟色、ColorOS 深色栏给的是「纯白 **+ alpha**」（alpha 被日志里 `& 0x00FFFFFF` 抹掉，从日志看不出来）；Ari 截图（6096px 宽）取亮像素峰值：时钟色 `#cccccc` 不透明那一行徽标 203.7 / 字幕 201.5（**本来就一致**），`#ffffff` + alpha 那一行徽标 **248.5**(max 255) / 字幕 197.5 → 差的就是被补掉的 alpha；改为连 alpha 一并继承（`alpha==0` 才补 `0xFF`）。②**从「有字幕」切到「无字幕」按钮要等 3 秒**——按钮原先只看 `hasSubtitles()`，而它在 `NO_SUBTITLE_GRACE_MS`(3000ms) 换轨待确认窗里照样返回 true（cues 还没清），同一时刻悬浮窗面板与状态栏字幕早就按「无字幕」显示了（它们读 `getCues()/getCurrentSubtitles()`，pending 返回空）→ 三处 UI 不一致；改为 `!hasSubtitles() \|\| isSuspended()`，并在切进「无字幕」前压 `BUTTON_NO_SUB_DELAY_MS`(500ms)——实测新音轨的 JSON **115ms / 345ms** 就到，有字幕的音轨全程不闪、没字幕的 ~0.5s 表态；**数据侧 3000ms 裁决窗未动**。另：`verify_dex_12112.py` 新增**第 3 层 smali 字节码校验**（这两项是纯逻辑改动、没有新日志串，字符串指纹验不出来） |
| **1.21.13（v54）** | 两项：①**状态栏字幕左侧的圆形通知数徽标缩小 15%**——`BADGE_SIZE_DP` 13→**11.05dp**、`BADGE_TEXT_SP` 9→**7.65sp**（都 ×0.85；数字必须同比缩，否则顶出圆外）。`BADGE_GAP_DP`(2dp) 是「间隙」不属于图标尺寸故不动，`applyBadgeInset()` 的让位宽度由 `BADGE_SIZE_DP` 推导、自动从 16dp 收窄到 14.05dp。⚠️ `static final float` 被 javac 内联，dex 里查不到字段名，只能断言浮点常量（新增 `0x4130cccd`(11.05f) / `0x40f4cccd`(7.65f)，旧 `0x41100000`(9.0f) / `0x41800000`(16.0f) 必须消失）。②**从有字幕音轨切到无字幕音轨后按钮仍是绿色高亮**——绿色底色的真源 `StatusBarSubtitleBridge.sAppEnabled` 有两条改动路径（长按翻转 / 无字幕强制作废），后者从仓库观察者里调用、**调用方不知道按钮存在**；而 `applyButtonText()` 的「没变化就不折腾」只比 `text`+`alpha`、把底色变化漏掉了，且按钮观察者注册得比模块观察者早（先跑的那个必然早退）→ 16:09:27.667 已 `force-off (no subtitles)`，16:09:40 截图里按钮仍是绿色。**像素实锤**：按钮填充 `rgb(24,119,97)` = `0.6 × BUTTON_BG_STATUSBAR(30,185,128) + 0.4 × 背景(15,19,48)` 分毫不差（0.6 = 「无字幕」态 view alpha；对照 `BUTTON_BG_ACTIVE` 合成值是 (40.8,41.8,81.6)，偏蓝紫不符）。修法：底色三态收敛到唯一入口 `buttonBgMode()` + `createButtonDrawable(int)`（旧 `createButtonDrawable(boolean)` 删除），判等新增 `sLastBtnBgMode`，并给 Bridge 加 `setEnabledListener()` 让开关一变就重画。另：`verify_dex_12113.py` 三层校验，含两条**负向**断言（旧 `(Z)` 重载、旧浮点常量都必须不在包里） |
| **1.21.14（v55）** | 一项：**切到无字幕音轨时按钮「文字很及时、底色却慢 3 秒」**——1.21.13 只修好了「谁会重画」，没修「绿色什么时候才该消失」：文字走 `noSub`（含 `BUTTON_NO_SUB_DELAY_MS` 500ms 预压缩），底色却只读 `StatusBarSubtitleBridge.sAppEnabled`，而它要到 `NO_SUBTITLE_GRACE_MS`(3000ms) 换轨裁决完、`forceDisabledWhenNoSubtitles()` 运行时才翻成 false（日志实证 `16:09:24.662` 换轨 SUSPEND → `16:09:27.664` 裁决 = **3002ms**）→ 同一块按钮上两条口径差 2.5s。**修法**：`buttonBgMode()` 判绿改为 `!noSub && sAppEnabled`，显示口径与文字同出一源、同一时刻落笔（~0.5s）；`sAppEnabled` 的**持久状态照旧等 3000ms 数据裁决**（提前作废会把「其实有字幕」的新音轨的状态栏字幕误关，要再长按一次才恢复）；另加 `[Button] button bg: normal (noSub=true, statusBarOn=true)` 日志便于对时。详见 `docs/statusbar-subtitle.md` §6.2 |
| **1.21.15（v56）** | 一项：**有时切换新音轨时，状态栏字幕会自己关闭**——两个根因。**A) 拿数据推断去改写用户意图**：换轨裁决到点后 `forceDisabledWhenNoSubtitles()` 直接把用户长按打开的 `sAppEnabled` 翻成 false，而**没有任何自动恢复路径**（悬浮窗有 `autoClosedForNoSubtitle` 会在字幕晚到时自动重开，开关没有，只能用户再长按）；日志实证 `17:22:05` / `17:35:05` / `18:00:34` / `18:20:28` 四次 `force-off`，其中 `18:00:33.391` 用户刚长按打开、**961ms 后**就被自己关掉。而它想达到的效果早已有别的东西在管：「无字幕时状态栏不挂字幕」由 `sendCurrentFromRepo()` 算出 `line=""` → SystemUI `hideSubtitleNow()` 把整个 `sContainer`（含 `sBadge` 子视图）GONE + 还原时钟；「按钮底色回归普通色」由 `buttonBgMode()` 的 `!noSub`。**修法**：删除 `forceDisabledWhenNoSubtitles()` 及两处调用，`sAppEnabled` 回归「纯用户意图」（只由长按翻转），数据侧只决定「有没有内容可显示」。**B) 「3s 内没等到 JSON ⇒ 该音轨无字幕」是假阴性**：JSON 到达延迟实测 0.2s~15.6s（冷请求可拖到 11~15.6s），而窗口固定 3000ms —— `18:20:25.879` 判无字幕(cues were 55) → `18:20:31.355` Loaded **105** cues（同一音轨，5.5s 后到）、`17:35:02.521` 判无字幕(cues 81) → `17:35:07.812` Loaded **152**（5.3s）、`17:22:02.745` 判无字幕(cues 81) → `17:22:16.824` Loaded **81**（11.1s）。**修法**：裁决窗分级 —— 进待确认时已有缓存 cues 则等 `NO_SUBTITLE_GRACE_MS_CACHED`(10000ms)，cues 空则维持 3000ms（零代价，因为待确认期间 `getCues()` 已被 `pendingTrackDecision` 门控返回空，UI 早就显示「无字幕」，窗口拉长不改变用户所见）。另加假阴性取证：判无字幕记 `lastNoSubtitleVerdictMs`，60s 内 JSON 又到就打 `track decision was a FALSE NEGATIVE: subtitle json arrived Nms after the "no subtitles" verdict`。详见 `docs/statusbar-subtitle.md` §6.3 |
| **构建纪律（1.21.14 补）** | ⚠️ **改完源码必须 `clean assembleDebug`，不能只跑增量 `assembleDebug`**：本次对照实验里增量构建**没重编译** `ActivityButtonHook.java`（`BUILD SUCCESSFUL`、4 executed/28 up-to-date），dex 里还是旧逻辑，且包体从 112193B 虚胖到 **167841B**（+55KB，正是 1.21.x 早前记过的「增量 debug 包异常臃肿」）。`clean` 重建后回到 112193B 且与首个修复包**逐字节相同**（sha256 `872030e7…`）。另：**每版校验必须做反向对照**——把修复撤掉、clean 重建，确认校验脚本**真的会 FAIL**（1.21.14 首次对照因增量构建假 PASS，差点当成「断言写太宽」） |

> ⚠️ 曾有**同日两个同名 1.20.4**（19:05 的 v26 与 22:11 的 v27），只能靠 BUILD 描述区分。
