package com.sena.dlsitesoundfloat.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在 DLsiteSound 播放页注入一个悬浮按钮，用于开关字幕悬浮窗。
 *
 * 性能注意（v4 关键修复 —— 解决「装插件后 App 极其卡顿」）：
 * 旧版有两个致命性能问题：
 * 1) 每次 onResume 都往 decor 上再挂一个 OnGlobalLayoutListener，且从不移除；
 * 2) detectAndLayout 里【无条件】调用 setLayoutParams，必然触发 requestLayout →
 *    再触发 onGlobalLayout → 再 setLayoutParams …… 形成永不收敛的布局回环。
 * 现在：监听器全局只挂一个、onPause 摘除；检测节流；布局参数只在真的变化时才 set。
 *
 * ── v23(1.20.2)：用「播放页锚点」修「非播放页仍显示按钮」 ──
 * 判定到播放页时记住主滑条所属的**页面级容器**（弱引用）。滑条扫不到但容器还在 →
 * 还在播放页，保持显示；容器消失/不可见 → 已离开，立即隐藏。
 *
 * ── v24(1.20.3)：**响应提速** ──
 * 把「检测节奏」从「固定 400ms 节流 + 固定 600ms 心跳」改成自适应：
 * 节流 150ms、跟踪态 200ms 心跳、宽限 300ms，心跳独立成自带重排的链（永不断链）。
 *
 * ── v25(1.20.3 再打包)：**过渡态不再误判**（本轮）──
 * 功能已正常，但仍"慢半拍"。录屏逐帧 × 日志时间戳对齐（每 0.5s 一帧）显示：按钮状态
 * 与页面**整整错开一拍** ——
 *   · f_005(18:04:03) 列表页 → 按钮正确隐藏；
 *   · f_007(18:04:04) 播放页 → 按钮**仍隐藏**（要等 05.243 才出现）；
 *   · f_009(18:04:05) 列表页 → 按钮**仍可见**（要等 06.252 才消失）。
 * 两条根因，都在「过渡态处理」上：
 *   1) **锚点会假死**：RN 切页动画期间页面容器会瞬时不可见，实测播放页上
 *      `06.051 anchor alive=false → 06.858 alive=true`（假死 **807ms**）。
 *      旧逻辑只要锚点死一次、300ms 宽限到点就隐藏 → 播放页上被误隐藏。
 *   2) **底部滑条这条正面证据被"连败 2 次"拖了一轮**：`03.592 已识别 OTHER`，
 *      却拖到 `03.835` 才隐藏。
 * 修法：
 *   · 锚点**活着 → 立即恢复显示**（不再傻等下一次扫到主滑条，那要 ~0.6s）；
 *   · 锚点**失效须连续 4 次采样**才隐藏（覆盖 0.8s 的假死窗口）——v30 已改成墙钟口径，
 *     见 {@link #ANCHOR_DEAD_MIN_MS}；
 *   · 「底部 mini-player 滑条」是**正面证据** → {@link #HIDE_ON_OTHER_STREAK}=1 立即隐藏；
 *   · 扫描端把 alpha 过滤阈值 0.05 → 0.02，让"正在淡入的活动页面"早点被认出来。
 *
 * ── v26(1.20.4)：**治「有时快有时慢」**（本轮）──
 * 第二轮延迟反馈（VID_20260911_183258_1.mp4 + LSPosed log，逐帧 × 时间戳对齐）定位到：
 * 「快慢不一」不是节奏参数问题，而是**判据在页面过渡期必然失准** ——
 *   · 播放页**入场**时主滑条会从屏幕底部一路滑到 64%，途中必然穿过 82% 那条绝对位置判据线，
 *     于是被误判成「底部 mini-player 滑条」→ 判 OTHER → 按钮被误隐藏（误隐藏时长 0.16s~1.6s 不等）；
 *   · 播放页**退场**时更极端：`bottom=y=2759(99%)`，滑条**还完整在屏内**，纯纵坐标根本拦不住。
 * 修法（三管齐下，扫描端见 {@link SubtitleViewHook}）：
 *   1) **OTHER 去抖**：{@link #HIDE_ON_OTHER_STREAK} 1 → 2，且证据需持续
 *      {@link #HIDE_ON_OTHER_PROOF_MS} —— 单帧假阳性不再能把按钮打掉；
 *   2) **页面容器可见面积**门（在扫描端）：页面正在滑入/滑出时容器占比骤降 → 该滑条不作数；
 *   3) **主滑条必须与时间文本配对**（在扫描端）：配不上就返回 UNKNOWN（保持原状 / 走锚点），
 *      绝不因此判 OTHER。
 * 另：alpha 门限 0.02 → 0.05 调回；锚点存活判定追加「可见面积 ≥ 50%」。
 *
 * ── v30(1.20.6)：**砍掉「发现得晚」与「采样抖动」**（本轮）──
 *
 * 逐条量化 v28 日志（work_diag_15，10 次隐藏）后把总延迟拆成两段，发现瓶颈根本不在确认窗本身：
 *
 *   | 段 | 实测 | 根因 |
 *   |---|---|---|
 *   | ①「页面已经走了」→「我们发现」 | **190 ~ 802ms** | 稳定态心跳 600ms，而 RN 转场是
 *   |   |   |  translate/opacity 驱动、**不触发布局回调** → 那段只能等心跳 |
 *   | ②「发现死亡」→「真的隐藏」 | **605 ~ 896ms** | 确认窗写成「4 次采样」：采样点落在
 *   |   |   |  节流(150ms)上就快、落在心跳(200ms)上就慢 → 同一事件差 300ms |
 *
 * 修法：
 *   1) **廉价锚点探针**（{@link #ANCHOR_WATCH_MS}=120ms）：只盯锚点一个 View 的存活，
 *      翻转或持续失效时立刻让完整扫描跑一次 → ① 从 190~802ms 压到 ≤130ms。
 *      探针**不做任何判定**，决策仍然只在 {@link #detectAndLayout} 一处，避免两套状态机各说各话。
 *   2) **确认窗改墙钟**（{@link #ANCHOR_DEAD_MIN_MS}=600ms + 最少 2 次采样）→ ② 收敛到 600~660ms。
 *   3) **正面证据快通道**：近期见过 mini-player 滑条时确认窗只要
 *      {@link #ANCHOR_DEAD_WITH_EVIDENCE_MS}=200ms（依据见该常量注释里的交叉表）。
 *   4) 修 {@link #ANCHOR_HARD_TIMEOUT_MS} 的计时起点（原用 `sUnknownSinceMs`，把长时间 UNKNOWN
 *      的账算在锚点头上，导致锚点复活时反而错过「立即恢复显示」—— 实测白等 606ms，
 *      详见 `sAnchorAliveSinceMs`）。
 *
 * ⚠️ **没有**下调安全余量：600ms 这条下限已经贴在安全边界上（见 {@link #ANCHOR_DEAD_MIN_MS}）。
 *
 * ── v32(1.20.8)：**撤销 v31 的动态锚定，按钮位置回到常量**（本轮）──
 *
 * v31 把按钮位置改成「按主滑条几何实时推导」。实测**不成立**，两条独立证据：
 *
 *   ① **日志**（work_diag_19，模块日志 19:41 那份）：20:19:05 ~ 20:20:35 共 13 条
 *      `button anchored`，`sliderCy` 在 **1799 ~ 2243** 之间摆
 *      （＝按钮底边距 245 ~ 493px，**摆幅 248px**）。
 *   ② **录屏**（VID_20260914_202036）：逐帧看，按钮在同一裁剪窗内纵向跨越 860px，
 *      而且是「每次扫描跳一格」地前进 —— 看着既不像跟手、又像卡顿。
 *
 * 根因三层，缺一不可：
 *   · 播放页**本身可滚动**（上滑露出滚动歌词），主滑条位置随之变化 →
 *     即使在播放页静止时，`scan.mainSliderY` 也**不是常量**；
 *   · 位置只在**扫描时**（节流 150ms / 心跳 200~600ms）才重算 → 页面连续滚动，
 *     按钮只能一格格跳过去，帧与帧之间不同步 ＝「不跟手」；
 *   · 每次变化的 bottom 都触发 `setLayoutParams` → `requestLayout` → 整棵 decor
 *     重新 measure/layout → `onGlobalLayout` 再次触发扫描 → **布局回环** ＝「卡顿」。
 *
 * 结论：**按钮位置必须是常量，与页面几何解耦。** 还原 v31 之前的口径 ——
 * 右下角、距屏底 {@link #BUTTON_BOTTOM_DP}（=96）dp、宽 {@link #BUTTON_W_DP}（=76）dp。
 * 位置恒定后 {@link #showButton} 里那句「只在真的变化时才 setLayoutParams」的守卫自然生效，
 * 只在首次摆位时触发布局一次，回环不可能发生。
 *
 * 该位置正是用户圈出的目标位：截图上手绘红圈中心 y=2451px（88.4% 屏高），
 * 而 96dp 口径算出的按钮中心 ≈2433px（87.8%），差 18px —— 同一处。
 *
 * ── v34(1.20.10)：**把「什么时候显示」交给宿主事件**（本轮）──
 *
 * 需求原话（Ari 2026-09-14）：「识别播放器按钮的位置，把按钮跟它绑定，做到**无延迟**」。
 *
 * 先否掉「绑位置」与「注入控件行」两条路，再给出正解：
 *
 *   ⛔ **绑位置**：v31 已证伪（见上），位置不是延迟来源。
 *   ⛔ **注入宿主控件行**：1.20.9 打了一次真实视图树 dump（`SubtitleViewHook#dumpViewTree`），
 *      结论是**技术上不可行** —— RN 的 `ReactViewGroup` 在 `onInterceptTouchEvent()` 里
 *      **无条件拦截**触摸（它用 JSTouchDispatcher 自己重做命中测试，只认带 React tag 的视图），
 *      塞进去的原生 View **收不到点击**，按钮会变成纯装饰。
 *   ✅ **正解：按钮继续留在 DecorView（可点击；拖动仍归悬浮窗自己），只把「何时显示」改由宿主事件驱动。**
 *
 * 实现见 {@code hook/StructureWatcher.java}：钩住 `ViewGroup.addView/removeView*`、
 * `View.setVisibility`、`View.setTranslationX/Y`、`View.setAlpha` —— 也就是
 * **RN 挂载页面**和**RN 转场（translate/opacity 驱动）**这两类必然发生的动作，
 * 事件一到就立刻跑一次扫描（{@link #POKE_MIN_INTERVAL_MS}=50ms 合并、绕过节流）。
 * 于是「页面已经上来了」不再依赖 600ms 心跳去「问」，而是宿主自己「说」。
 *
 * 另外新增一条**硬证据**：给锚点挂 attach 监听（{@link #watchAnchorAttachment}），
 * 锚点被从窗口摘除 = 视图都没了 = 一定不在播放页 → 跳过 {@link #ANCHOR_DEAD_MIN_MS}
 * 确认窗直接隐藏（确认窗本来只为防「假死又滑回来」，而视图摘除不会滑回来）。
 *
 * 日志里新增两类行，用来验证并继续校准：
 *   · `structure event -> instant scan [addView] #N` —— 哪类事件触发的；
 *   · `button shown (player page) after structure event +XXms` —— 真实端到端延迟。
 *
 * ── v35(1.20.11)：**按钮跟随页面位移**（本轮）──
 *
 * 需求原话（Ari 2026-09-14 21:56，附 5s 录屏 + 日志）：
 * 「我想要悬浮开关按钮跟随页面上下滑动而变动位置，是否可以通过识别中间的播放键的位置实现位移跟随」
 *
 * 先看录屏与日志（work_diag_20）确认了现象：播放页被**按住上滑**时整层沿 y 平移，
 * 逐帧测出 `sPlayerAnchor` 主滑条 y 在 **1799 ↔ 2224** 之间摆动（摆幅 ≈425px，
 * 峰值位移 ≈560px），而按钮**纹丝不动** —— 视觉上就像「按钮没长在播放器上」。
 *
 * ⚠️ 这与 v31 那次失败**不是同一回事**，区别必须说清楚（否则很容易被当成回滚）：
 *   | | v31（已撤销） | v35（本轮） |
 *   |---|---|---|
 *   | 位置来源 | 主滑条几何**实时推导**，是位置的**唯一**来源 | 静止位置仍是常量，只叠加**相对位移** |
 *   | 重算时机 | 只在扫描时（节流 150ms / 心跳 200~600ms）→ 一格格跳 | **每帧**（Choreographer）→ 跟手 |
 *   | 写入方式 | `setLayoutParams` → `requestLayout` → **布局回环** | `setTranslationY` → **不触发布局** |
 *   | 静止态 | 位置随页面滚动而变（被污染） | 位移恒为 0，与 v32 口径**完全等价** |
 *
 * 参考点＝用户点名的「中间的播放键」：真实结构里是
 * `ReactViewGroup @0,141（页面容器，19 个区块）→ …（锚点下 2~3 层）→ LottieAnimationView 148x149`
 * （见 {@link #ensureFollowRef()}），它位置唯一、稳定，且与页面锚点同属一个位移层。
 * 取不到时退回用**页面锚点**，位移量完全等价。
 *
 * 三条防坑措施（每条都对应一个会让效果崩掉的坑，写在对应常量的注释里）：
 *   1. {@link #maybeStartPageFollow()}：结构事件到达时只读**一次**坐标判断「页面动没动」，
 *      动了才启动跟帧循环 → 常驻动画（缓冲转圈等）不会把循环反复重启；
 *   2. {@link #captureFollowBaseline()}：基线只在「跟帧循环停 + 位移为 0 + 连续两次读数一致」
 *      时采集 → 入场动画期与拖动初期都不会把基线采歪；
 *   3. {@link #PAGE_HOLD_PX}：**页面容器还活着、只是被拖开了 → 不采信 OTHER 证据**。
 *      实测一次 2.4s 的上下拖动里，下层列表页从缝里露出的 mini-player 滑条
 *      让扫描器连出 **5 次 OTHER（219ms）**，离 {@link #HIDE_ON_OTHER_PROOF_MS} 的
 *      250ms 判隐藏只差一步 —— 也就是说「手还在拖、按钮已经被判走了」随时会真实发生。
 *
 * 诊断行（v35 起每轮一行，v36 起位移每变化 64px 补一行，v37 起带位移通道与原始累加值）：
 * `page follow on: button translationY=…px (ref=play-button|anchor vis=… base=… ch=transform|baseline)`。
 *
 * ── v36(1.20.12)：跟手三处致命缺陷修复（本轮）──
 *
 * 反馈原话（Ari 2026-09-14 22:25，附 10.7s 录屏 + 日志）：
 * 「悬浮窗会卡在屏幕边缘不跟随页面继续向下滑动，甚至有时还卡在原位置不动」
 *
 * 先把 v35 的**实际运行数据**摆出来（work_diag_21）：
 *   · `page follow` 共 34 行，**全部** `ref=anchor` —— 播放键一次都没认出来；
 *   · 记录到的位移是 7~264px，而同一时段主滑条 y 从 1799 一路走到 2784（位移 ≈985px）；
 *   · 拖动期间每 55ms 一次 PLAYER 扫描，`button hidden (bottom mini-player #6)` 真的发生了。
 *
 * 三个**互相独立**的缺陷叠在一起，正好逐条对上她的两种描述：
 *
 * ① 「卡在屏幕边缘」= 位移钳制太死（{@link #clampFollowOffset}）
 *    v35 按屏高比例钳制**位移量**：往下只给 0.10 × 2772 ≈ **277px**，而页面能被拖走
 *    985px → 按钮跟到 277px 就撞线不动。日志里那批 261/264px 正是撞线记录。
 *    v36 改成钳制**屏幕位置**：只要按钮本体还在窗口内（躲开状态栏 / 导航栏）就放行，
 *    「跟到头」由屏幕物理边界决定，而不是一个拍脑袋的比例常数。
 *
 * ② 「卡在原位置不动」= 基线被反复覆盖（{@link #captureFollowBaseline()} 的门③）
 *    门③ 本意是「页面没落回原位就不许改基线」，但它读的是 `sPlayBtnBaseY` ——
 *    而播放键从没被认出来，那个字段恒为哨兵值 → **门③ 永远不生效**。
 *    于是拖动中每 55ms 的一次 PLAYER 扫描都把「拖到一半的位置」写成新基线
 *    → delta 立即归零 → 按钮弹回原位，此后永远认为「这就是静止位」。
 *    v36 把基线收敛成唯一一份 {@link #sFollowBaseY}（与当前参考点一一对应），门才拦得住。
 *
 * ③ 「偶尔彻底不动」= 帧循环被**瞬时**读失败杀死（{@link #followFrameCallback}）
 *    `doFrame` 里读到 {@link #FOLLOW_NO_BASELINE} 就直接 return，`again` 保持 false
 *    → 循环**一次性结束**。而拖动时参考点会被短暂判成不可用（锚点 area 掉到 45%、
 *    verdict 在 PLAYER / OTHER 之间跳），且 RN 拖动是 translate 驱动、不触发布局回调，
 *    结构事件不一定再来 → 循环再也起不来。v36 改为只跳过该帧，连续
 *    {@link #FOLLOW_MISS_MAX_FRAMES} 帧读不到才认输。
 *
 * 三处连带修正：
 *   · **参考点粘性**（{@link #ensureFollowRef()}）：v35 每次调用都现场重挑，
 *     verdict 一跳就换来源、基线跟着漂。现在认下一个实例就一直用它。
 *   · **播放键扫描够不着**（{@link #scanPlayButton}）：v35 深度限 3、节点预算 80，
 *     而锚点有 19 个直接子节点（页面区块）、播放键在**最后一个**区块里 ——
 *     正序 DFS 还没走到就耗尽预算了。v36：深度 8 + 预算 2000 + **从后往前**遍历。
 *   · **OTHER 抑制门**（{@link #PAGE_HOLD_PX}）不再依赖 {@link #isPlayerAnchorAlive()}：
 *     拖动期间那条判据频繁为 false（`player anchor alive=false (area=45%)`），
 *     等于整道门没生效。现在只看「粘住的参考点被拖离基线多远」。
 *
 * 日志同时升级：`page follow on:` 从「每轮只打一行」改成**位移每变化 64px 补一行**
 * （v35 那 34 行看不出「跟到哪儿断了」，复盘只能靠猜），并带上 base / btnBase。
 *
 * ⚠️ 教训（写给下一轮）：v35 那三个坑有**共同点** —— 都是「诊断字段读的不是当前
 * 生效的那份状态」。`sPlayBtnBaseY` 在退化路径下永远不写、`sFollowRef` 每帧重挑、
 * 钳制用屏高比例而不是真实按钮位置。**加新判据前，先问一句「这个量在退化路径上
 * 有没有被更新」**，否则判据在正常路径上看起来完全正确，一退化就静默失效。
 *
 * ── v37(1.20.13)：跟手换成「无基线」位移通道 + 放行 1:1 跟随（本轮）──
 *
 * 反馈原话（Ari 2026-09-14 22:44，附 13.4s 录屏 + 日志）：
 * 「还是之前的问题没解决：悬浮窗会卡在屏幕边缘不跟随页面继续向下滑动，
 *   甚至有时还卡在原位置不动或者飞到屏幕上方去了」
 *
 * v36 的数据（work_diag_22，逐帧互相关 + 模板匹配量的）：
 *   · 每次拖动按钮都**刚好下移 121 video px = 262 屏幕 px** 后停住 —— 数字严丝合缝地
 *     等于 v36 那道「屏幕位置钳制」（2772 − 103 − 22 − 2385 = 262），
 *     即**钳制本身就是「卡在屏幕边缘」的成因**，不是钳得不够松。
 *   · 日志里出现 `page follow on: button translationY=-2015px (ref=anchor base=2156)`：
 *     播放键基线 2156、页面锚点基线 141，两者差 2015 —— 参考点**换人**了而基线没跟着换
 *     → 按钮瞬间飞到屏幕上方（**她说的「飞到屏幕上方」就是这一行**）。
 *   · `page follow skipped: no settled baseline yet` 出现 **15 次** —— 那段时间按钮
 *     一步都不跟（**「卡在原位置不动」**）；且 hide 会顺手扔掉基线，
 *     拖动中每 hide 一次就多一段「再也不跟」。
 *
 * 三条对应的修法：
 *
 * ① **新增无基线位移通道**（{@link #pageVisualOffset()}）—— 这是本轮的核心。
 *    位移 = 参考点祖先链上 Σ translationY − Σ scrollY；页面在布局静止位时恒为 0。
 *    · **不需要基线** → 「基线被覆盖 / 过期 / 与参考点不匹配」三类坑一次性消失；
 *    · **不受参考点换实例影响** → 播放键与页面锚点算出来完全相等，换人不跳变；
 *    · 手指按住页面停在半路时它**仍是位移量**，不会像基线方案那样自愈成 0。
 *    偏置（祖先链上常驻的非零 translationY）在「确认页面静止」那一刻标定掉
 *    （{@link #sVisualBias}）。整条通道没被证实有效时自动退回老通道
 *    （{@link #followOffset()}），所以不存在「新通道失效 = 功能全废」。
 *
 * ② **放行 1:1 跟随**（{@link #clampFollowOffset}）：删掉「必须留在屏幕内」这个前提 ——
 *    它本身就是错的（按钮静止位在屏幕下方，往下只剩 262px）。页面移出屏幕按钮就跟着
 *    移出屏幕，页面回来按钮自然回来。只留 ±1.6 屏高挡异常值。
 *
 * ③ **不再靠基线判「页面被拖住」**（{@link #isPageHeld()}）：v36 那道门要用基线算位移，
 *    而「没有基线」时它直接放行 —— 恰好在最需要它的场景完全失效。现在用无基线通道
 *    + 「刚刚还在动」时间窗，并且把这道门**同时**接到「无播放页证据 → hide」那条路上
 *    （v36 只接在 OTHER 分支，而实测 hide 是从锚点失效那条路来的）。
 *
 * 连带修正：
 *   · {@link #stopPageFollow(boolean)} 不再作废基线（只清零位移）—— v36 每次 hide
 *     都扔基线，而拖动中 hide 频繁发生，这正是「卡在原位置」的一半原因；
 *     真正的作废收敛到 {@link #invalidateFollow()}，只在参考点没了 / 换页 / 重建按钮时调。
 *   · 参考点换实例时{@link #adoptFollowRef(View, boolean)} **平移基线**保持位移不变。
 *   · 播放键 DFS 加节流（{@link #PLAY_BTN_RESCAN_MS}），不再可能被每帧调用。
 *
 * ⚠️ 教训（写给下一轮）：「必须留在屏幕内」这种**未经确认的产品前提**，比任何算法细节
 * 都更容易让功能看起来完全没用。**先问「用户到底要什么」，再问「怎么算得准」。**
 *
 * ── v38(1.20.14)：治「上下滑动时按钮闪现 / 闪消失」（本轮）──
 *
 * 反馈原话（Ari 2026-09-14 23:18）：
 * 「播放页面上下滑动偶尔还是会悬浮窗按钮闪现和闪消失」
 *
 * 数据（work_diag_23/hide_ctx.py，把每次 hide 与它之前的页面位移对上）：
 *   · 1.20.13 时段共 **23 次 hide**，**23/23** 都发生在页面被拖走 **2710~2772px**
 *     的时候 —— 而屏幕高度就是 2772px，即「页面被拖到极限、播放控件彻底出屏」。
 *   · hide → shown 的间隔：**168 ~ 2095ms，中位 369ms**；23 次散布在 115 秒里
 *     ≈ 每 5 秒闪一次。
 *   · 每次 hide 之前都先出现过 `hide suppressed: page held` —— 抑制门**挡了一下就失守**。
 *
 * 根因：**「离开播放页」的确认窗从错误的时刻起算。**
 *   {@code sAnchorDeadSinceMs} 记的是「锚点第一次判死」的时刻，而那正好是**拖动刚开始**；
 *   从那一刻起 {@link #ANCHOR_DEAD_MIN_MS}(600ms) 一路走完，而手指还在拖、页面还在飞 ——
 *   于是按钮在半空中被判「离开播放页」而 hide，页面回弹后 331ms 又 shown。
 *   完整现场（23:16:26~27）：
 *     26.905 锚点判死(area=42%) → 26.905 `hide suppressed`(vis=1459px) → 27.014 页面到位
 *     (vis=2768px) → 27.561 deadFor=656ms ≥ 600 → **hide** → 27.891 锚点回来 → **shown**。
 *
 * 修法一：**确认窗改成从「页面最后一次运动」起算** ——
 *   {@code since = max(sAnchorDeadSinceMs, sLastPageMotionMs, …)}。
 *   拖动 / fling 期间「扫不到播放页证据」是这一刻的**正常现象**（页面被拖到看不见的地方），
 *   不该被当成「离开播放页」的证据。
 *
 * ⚠️ 但**光改起算点不够** —— 页面到位后还会**停**在那里几百毫秒才回弹，那段时间
 * 「无证据」是真的，确认窗照样会走完。真正让 23 次 hide 全部发生的是第二条：
 *
 * 修法二（主因）：**抑制门在「页面被拖到极限」时会失守**，补判据③「位移快照」。
 *   页面被拖走 2710~2772px 时，参考点失去「可用」资格（滚出屏幕）、锚点可见面积也
 *   趋近 0 → {@link #isPageHeld()} 的判据②**恰好在最需要它的时刻返回 false**。
 *   日志里每次 hide 之前都先出现过一次 {@code hide suppressed} —— 正是「挡住了，
 *   然后在页面到位的那一刻失守」。
 *   判据③：{@link #sHeldOffset} 记住「最后读到的位移 + 时刻 + 当时的参考点」，
 *   只要「不久前（{@link #HOLD_SNAPSHOT_MS}）被拖开过」**且参考点仍 attached**
 *   → 维持原状。**attached 是「被拖开」与「真离开」的分水岭**：拖到极限时参考点只是
 *   滚出屏幕、依然 attached；页面真被卸载时 RN 会把它 detach → 立即放行，不会赖着不走。
 *
 * 配套两处（让 {@code sLastPageMotionMs} 精确可信）：
 *   · 帧循环里改成**只在位移真的变了**才刷新（老代码每帧无条件刷新 → 比页面真正停下
 *     的时刻晚 ≈200ms，而它现在是确认窗的起算点，晚 200ms = 白送 200ms 宽限）；
 *   · {@link #notePageMotion()} 也挂到 {@link #detectAndLayout} 的扫描心跳 —— 结构事件
 *     在 RN 里**并不保证触发**（translate 驱动的拖动不触发布局回调），55ms 的心跳才可靠。
 *
 * 诊断：{@code anchor dead for …ms (… still=NNms)} 新增「页面已静止多久」；
 * {@code -> hide} 那行补上判据③的原始量（{@code snap=…px/…ms attached=… vis=…px}），
 * 门失守时**一眼能看出是判据②失效、还是③超期 / detach**。
 */
public class ActivityButtonHook {
    private static final String TAG = "[DLsiteSoundFloat:Button]";
    private static final String ACTIVITY_CLASS = "jp.co.eisys.dlsitesound.MainActivity";
    private static final int BUTTON_ID = 0x7F999001;
    private static final int BUTTON_BG_NORMAL = 0x99000000;
    private static final int BUTTON_BG_ACTIVE = 0x993A3968;

    /** 按钮尺寸（dp）。宽度 v31 一度收到 60dp（为塞进传输控件行右端），v32 撤销锚定后还原 76dp。 */
    private static final int BUTTON_W_DP = 76;
    private static final int BUTTON_H_DP = 34;

    /** 按钮距屏幕右缘的边距（dp）。 */
    private static final int BUTTON_RIGHT_DP = 16;

    /**
     * 按钮距屏幕底缘的边距（dp）—— v32 起**重新是唯一口径**（v31 曾把它降级成「兜底值」），
     * v35 的跟手位移是**叠加**在它上面的相对量，不动这个常量。
     *
     * 为什么必须写死、不能按页面几何推导：见类头 v32 段。一句话 —— 播放页可滚动，
     * 主滑条位置本身就是变量，跟着它走等于把一个静态控件变成随页面乱跳的东西。
     *
     * 为什么是 96：实测它正好落在「传输控件行（shuffle/prev/play/next/repeat）」与
     * 「底部图标行（1x / 月亮 / 剪刀 / 书签 / 汉堡）」正中的空档里，两侧都不遮挡 ——
     * 这是这块屏上唯一既显眼、又不挡事的独立条带。
     */
    private static final int BUTTON_BOTTOM_DP = 96;

    /**
     * 两次扫描之间的最小间隔。
     *
     * onGlobalLayout 驱动的检测是**事件驱动**的 —— 页面切换 / 视图铺开必然触发布局，
     * 所以这个值直接决定「切页后多久能反应过来」。v23 是 400ms，实测切页延迟 0.6~0.9s。
     * 降到 150ms 后，切页通常在一次布局回调内就被捕捉到；页面静止时没有布局回调，
     * 不会产生额外开销（只有心跳在跑）。
     */
    private static final long DETECT_MIN_INTERVAL_MS = 150L;

    /** 稳定态心跳：结论明确且与按钮现状一致时用，省电。 */
    private static final long HEARTBEAT_MS = 600L;

    /**
     * 跟踪态心跳：结论不确定（UNKNOWN）或结论与按钮现状不一致时用 ——
     * 这正是「需要尽快反应」的时刻，例如离开播放页后主滑条被回收、
     * 或刚切页、刚重建按钮、视图树还在铺。
     */
    private static final long HEARTBEAT_FAST_MS = 200L;

    /** 连续多少次同结论算「已稳定」→ 退回常规心跳，避免长期高频扫描。 */
    private static final int SETTLE_STREAK = 4;

    /**
     * 明确判为「非播放页」（屏上只出现底部 mini-player 滑条、且**没有**主滑条）须连续几次才隐藏。
     *
     * v25 曾设为 1（"底部滑条是正面证据，再要求连败纯属浪费"）—— **实测这是个错误判断**：
     * 播放页做**入场动画**时，页面从屏幕底部往上滑，它自己的主滑条会一路扫过下半屏，
     * 途中被 82% 那条位置判据误判成「mini-player 滑条」（录屏铁证 18:32:47.5：
     * 画面明确是播放页滑入，日志却是 `OTHER | bottom=y=2324(83%)`）。
     * 单帧假阳性 + 单次即隐藏 = 按钮被误打掉，这正是"忽快忽慢"的来源。
     * v26 调回 **2**，并叠加 {@link #HIDE_ON_OTHER_PROOF_MS} 的时间确认窗。
     */
    private static final int HIDE_ON_OTHER_STREAK = 2;

    /**
     * 判 OTHER 后还需**持续**这么久才真的隐藏（v26 新增，与 {@link #HIDE_ON_OTHER_STREAK} 同时满足）。
     *
     * 目的：页面过渡动画通常 <400ms，给它一段确认窗，短促的单次误判就被吃掉。
     * 取 250ms：既能吃掉过渡噪声，又不会让"真的离开播放页"显得迟钝。
     */
    private static final long HIDE_ON_OTHER_PROOF_MS = 250L;

    /**
     * 「锚点已失效」须**持续**这么久（墙钟毫秒）才真的隐藏。
     *
     * ── v30：口径由「次数」改成「时间」──
     * 旧版是 {@code ANCHOR_DEAD_STREAK = 4} 次采样。实测（work_diag_15，v28 日志 10 次隐藏）
     * 「锚点死亡 → 隐藏」耗时在 **605ms ~ 896ms** 之间摆：采样点落在节流(150ms)上就快、
     * 落在心跳(200ms)上就慢。**同一物理事件延迟差 300ms**，这正是"有时快有时慢"的来源。
     * 改成墙钟后所有隐藏都落在同一档，不再受采样相位影响。
     *
     * ── 为什么是 600ms，而不是更短 ──
     * 交叉表（work_diag_16/anchor_xtab.py，43 个死亡段）证明锚点"假死"确实存在：
     * 2026-09-11T18:28:25.9 播放页整页滑出屏幕（area=0%）后 **1.326s 又滑回同一首歌同一条字幕**
     * （`沒有拒絕我呢` → `你果然也是喜歡我的吧？`，主滑条 y 回到 1799/64%），当时按钮白闪了 720ms。
     * 也就是说 600ms **已经贴在安全边界上**，再短就会把这个瞬态误判成"已离开"。
     * 本轮优化的是"发现得晚（①）"和"采样抖动"，**不是**砍这条安全余量。
     */
    private static final long ANCHOR_DEAD_MIN_MS = 600L;

    /**
     * 判定锚点死亡所需的**最少采样次数**（与 {@link #ANCHOR_DEAD_MIN_MS} 同时满足）。
     * 防「只凭一次采样就下结论」—— 哪怕那一次采样距死亡起点已超过 600ms（例如刚重建完按钮）。
     */
    private static final int ANCHOR_DEAD_MIN_SAMPLES = 2;

    /**
     * 廉价锚点探针的周期（v30 新增）。
     *
     * ── 为什么需要它 ──
     * 日志显示「锚点死亡」**被发现的时刻**比真实死亡晚 **190ms ~ 802ms**：稳定态心跳是 600ms，
     * 而 RN 的页面转场由 translate/opacity 驱动，**根本不触发布局回调** ——
     * 那段时间唯一的采样来源就是 600ms 心跳，最坏要等 600ms 才知道页面已经走了。
     * 探针只查**锚点这一个 View**（不遍历视图树，成本≈0），120ms 一轮，
     * 一旦发现活/死翻转、或锚点持续失效（正在等确认），就立刻（绕过节流）触发一次完整扫描。
     * 效果：① 段从 190~802ms 压到 ≤130ms。
     *
     * 只在「按钮可见（= 认定在播放页）」时才有意义，其余情况直接空转返回，不增加常态开销。
     */
    private static final long ANCHOR_WATCH_MS = 120L;

    /**
     * 「底部 mini-player 滑条」这条**正面证据**的有效期（v30 新增）。
     *
     * ── 依据（work_diag_16/other_vs_button.py 交叉表）──
     * v28 日志里 {@code verdict=OTHER} 共 8 次：
     *   · **7 次发生在按钮可见时**，且 7/7 都在 200~420ms 后被锚点死亡证实 = 真的离开了播放页；
     *   · 唯一 1 次假阳性（10:02:26.810，播放页入场动画）发生在**按钮已隐藏**时 → 隐藏本就是 no-op。
     * 所以「按钮可见 + 屏上出现 mini-player 滑条」在这批数据里是 7/7 干净的正面证据。
     *
     * 为什么用「有效期」而不是「连续两次」：真实转场里滑条会 **OTHER → UNKNOWN 交替**
     * （下一页铺开时滑条被容器可见面积门否掉），连续判定永远凑不齐两次，这条正面证据就白丢了。
     */
    private static final long OTHER_EVIDENCE_TTL_MS = 400L;

    /**
     * 拿到正面证据（{@link #OTHER_EVIDENCE_TTL_MS} 内见过 mini-player 滑条）时，
     * 锚点死亡只需持续这么久即可隐藏（v30 新增）。
     *
     * 实测（work_diag_15）：OTHER 出现后 200~420ms 锚点才死亡，所以实际隐藏时刻仍略晚于
     * 「锚点死亡」，但整体比走 600ms 的负面推断通道快 **~450ms**。
     * 按钮**已隐藏**时这条通道不生效（两次已知的 OTHER 假阳性都发生在按钮隐藏时）。
     */
    private static final long ANCHOR_DEAD_WITH_EVIDENCE_MS = 200L;

    /**
     * 兜底宽限：**从未取到过锚点**时用（{@code findPlayerAnchor} 返回 null 的机型/页面结构）。
     * 锚点缺失时无法区分「滑条被回收但仍在本页」与「已离开本页」，只能靠时间放宽一点。
     */
    private static final long NO_ANCHOR_GRACE_MS = 900L;

    /**
     * 锚点**仍存活**但一直处于 UNKNOWN 的兜底时限。
     * 用于兜住「锚点恰好选得过高层（一直可见）」的异常情况 ——
     * 宁可多显示一会儿，也不能让按钮在非播放页永远藏不掉。
     */
    private static final long ANCHOR_HARD_TIMEOUT_MS = 3000L;

    /** 切页动画期间快速复检的间隔与次数上限（过渡态要绕过节流）。 */
    private static final long AMBIGUOUS_RECHECK_MS = 150L;
    private static final int MAX_AMBIGUOUS_RECHECKS = 12;

    /**
     * 宿主结构事件（addView / removeView / setVisibility / 转场位移·透明度）的**合并窗口**（v34 新增）。
     *
     * ── 为什么是这个值 ──
     * 一次页面转场会连续触发成千上万次视图树变更（RN 挂载整棵子树），
     * 全部映射成扫描会直接把主线程拖死。50ms ≈ 3 帧，既能把同一批事件合并成一次扫描，
     * 又远小于人眼能察觉的延迟（100ms 以内都算「立刻」）。
     *
     * ── 效果预期 ──
     * 进播放页：RN 挂载首帧 → 触发扫描 → 按钮出现，**≤ 1 帧 + 一次扫描耗时（约 30~80ms）**，
     * 而 v33 之前最坏要等 600ms 心跳。
     */
    private static final long POKE_MIN_INTERVAL_MS = 50L;

    /**
     * 一次「结构事件爆发」的归属窗口（v34，仅用于量显隐延迟）。
     * 超过这个时长没有新事件，就认为上一轮爆发已经结束、重新起算。
     */
    private static final long POKE_BURST_GAP_MS = 1500L;

    /** 结构事件触发日志的打印上限（诊断用，避免刷屏）。 */
    private static final int MAX_POKE_LOGS = 40;

    /**
     * 结构事件加急扫描的**每秒上限**（v34 令牌桶）。
     *
     * 为什么必须有：钩子挂在 {@code setAlpha / setTranslationX / setVisibility} 这类
     * **动画驱动**的方法上。如果 App 里出现任何持续动画（缓冲转圈、轮播图、循环闪烁），
     * 事件会**每帧**来一次 —— 只看 50ms 合并窗的话就会变成永久 20 次/秒的整树扫描。
     * 这里硬性封顶，保证最坏情况下 CPU 也有界。
     *
     * ⚠️ 桶**不能**拦住用户触发的页面切换：所以配了 {@link #POKE_QUIET_BYPASS_MS} ——
     * 事件爆发前若有一段安静期（用户点击 → RN 才开始动），第一个事件**直接放行**。
     * 桶只对「紧挨着的连续事件」（= 持续动画）生效。
     */
    private static final int POKE_MAX_PER_SEC = 12;

    /**
     * 「安静期」门限（v34）：距上一次结构事件超过这么久，认为新一轮**用户触发**的切换开始了，
     * 首个事件**绕过令牌桶**直接触发扫描。见 {@link #POKE_MAX_PER_SEC} 的说明。
     */
    private static final long POKE_QUIET_BYPASS_MS = 400L;

    // ── v35：按钮「跟手」——播放页上下拖动时按钮跟着走 ─────────────────────
    //
    // 与 v31 那次失败的动态锚定**不是**同一回事，务必分清：
    //   · v31：按钮位置 = 主滑条几何**实时推导**的唯一来源，且只在「扫描时」重算
    //     （扫描被节流到 150ms / 心跳 200~600ms）→ 一格格跳 + 每次 setLayoutParams
    //     触发 requestLayout → 布局回环。**位置还被页面滚动本身污染**。
    //   · v35：按钮的**静止位置仍然是常量**（右下角 96dp），只是叠加一个
    //     **相对位移**（{@code setTranslationY}，不触发布局），
    //     且位移是相对「页面静止时的基线」算出来的差值 —— 页面静止时差值恒为 0，
    //     与 v32 的口径完全等价。
    //
    // 参考点选「播放键」（中间的播放/暂停键，`LottieAnimationView`）：它在播放页
    // 传输控件行里、**位置唯一且稳定**，且与页面容器同属一个位移层 —— 页面怎么动它怎么动。
    // 找不到时退回用**页面锚点**（同一个位移层，位移量完全等价）。

    /** 页面位移小于这个值就当没动（px），避免亚像素抖动把跟帧循环永远拖着跑。 */
    private static final int FOLLOW_DEADZONE_PX = 6;

    /**
     * 跟帧循环的**静止帧数**上限（≈ 200ms @60fps）：连续这么多帧参考点没动，
     * 就认为页面停了、注销帧回调。空闲时**零成本**（没有注册任何帧回调）。
     */
    private static final int FOLLOW_STILL_FRAMES = 12;

    /** 单次跟帧循环的硬时长上限（安全网：万一参考点一直在抖，也不会无限跟）。 */
    private static final long FOLLOW_MAX_MS = 20000L;

    /**
     * 页面**仍被拖开**（位移 &gt; {@link #FOLLOW_DEADZONE_PX}）时的时长上限（v39）。
     *
     * 比 {@link #FOLLOW_MAX_MS} 宽得多：手指按住页面停在半路几分钟是正常操作，
     * 那时候循环必须**继续跑** —— 一停就会出现 v39 修的那个「冻住 → 补跳」。
     * 这条只是防止「参考点一直在、位移一直非 0」的极端情况下永久跑下去。
     */
    private static final long FOLLOW_MAX_HELD_MS = 120000L;

    /**
     * 参考点连续读不到时的容忍帧数（≈1.5s @60fps）。
     *
     * v35 的坑：{@code doFrame} 里读到 {@link #FOLLOW_NO_BASELINE} 就直接 {@code return}，
     * 而 {@code again} 保持 false → **帧循环被一次性杀死**。RN 拖动是 translate 驱动、
     * 不触发布局回调，结构事件不一定再来 → 循环再也起不来 = 按钮**永久卡死**。
     * v36 改为：读不到只跳过这一帧；连续超过这个帧数才真正结束。
     */
    private static final int FOLLOW_MISS_MAX_FRAMES = 90;

    /**
     * 位移**硬上限**（屏高倍数）—— 只用来挡「算出来的垃圾值」，
     * **不再限制正常跟随范围**。
     *
     * ⚠️ v36 的坑（本版修的就是它）：把「位移量」钳到屏幕边界以内。按钮静止位在屏幕
     * 下方，往下只剩 262px 空间，而播放页能被拖走 2000+px → 按钮**跟到 262px 就撞死**。
     * 录屏逐帧实测：按钮下移 121 video px（= 262 屏幕 px）后停住，此后每次拖动都停在
     * 同一个位置 —— 用户看到的就是「卡在屏幕边缘不跟随页面继续向下滑动」。
     *
     * v37：**放行 1:1 跟随** —— 页面去哪儿按钮去哪儿，页面移出屏幕按钮也移出屏幕
     * （页面回来按钮就回来）。只留 ±1.6 屏高这个兜底，防的是读错坐标 / 除零这类异常值。
     */
    private static final float FOLLOW_SANITY_RATIO = 1.6f;

    /**
     * 「页面最近还在动」的保持窗（ms）。
     *
     * 拖动过程中扫描器会在 PLAYER / OTHER / UNKNOWN 之间反复跳，锚点可见面积掉到
     * 30~45% → 判据链会一路走到「无播放页证据 → hide」。实测一次 2.4s 的拖动里按钮
     * **消失约 700ms 再回来**，而 hide 会把跟随位移清零 → 用户看到的就是
     * 「卡在原位置不动」。这个窗口让「刚刚还在动」直接否决隐藏。
     */
    private static final long PAGE_MOTION_HOLD_MS = 200L;

    /**
     * 「页面被拖开」时锚点**至少要留这么多可见面积**（0~1）。
     *
     * 光看「位移非 0」不能判「被拖开」：播放页被划走（真的离开）时位移也非 0。
     * 补上面积下限 —— 拖动时实测面积 29~57%，而页面真的走掉时趋近 0。
     */
    private static final float PAGE_HOLD_MIN_AREA = 0.12f;

    /**
     * 播放键扫描的最小重试间隔（ms）。
     *
     * {@code pickPlayButton} 是一次最多 2000 节点的 DFS，绝不能被每帧 / 每次扫描调用。
     * 粘住的参考点失效时，距上次扫描不足这个间隔就**直接用锚点**顶着。
     */
    private static final long PLAY_BTN_RESCAN_MS = 3000L;

    /**
     * 跟手日志的补打步长（px）。
     *
     * v35 每轮只打第一行，于是「这一轮跟到哪儿、在哪个位移上卡住」在日志里
     * **完全看不出来** —— 复盘只能靠猜。v36 改成位移每变化这么多就补一行，
     * 一行一次 log 的开销可以忽略，但排查价值很高。
     */
    private static final int FOLLOW_LOG_STEP_PX = 64;

    /**
     * 「页面还在手上」的位移门限（v35 起，px）。
     *
     * 拖动播放页时，**下层的列表页会从缝里露出来**，它那条底部 mini-player 滑条会被
     * 扫描器当成「已离开播放页」的正面证据 → 实测一次 2.4s 的上下拖动里出现了
     * **连续 5~6 次 `verdict=OTHER`（219~275ms）**，直接越过
     * {@link #HIDE_ON_OTHER_PROOF_MS} 的 250ms 判隐藏 —— 手指还在拖、按钮已经被判走了。
     *
     * 判据：**粘住的参考点已被拖离基线超过这个距离** → 这是「页面被拖动」而不是「换页」。
     *
     * v36 修正：v35 还额外要求 {@link #isPlayerAnchorAlive()} —— 而日志显示拖动期间
     * 这条判据频繁为 false（`player anchor alive=false (area=45%)`），于是整道门形同虚设。
     * 现在**只看参考点位移**，不再问锚点死活。
     */
    private static final int PAGE_HOLD_PX = 24;

    /**
     * 「最后已知位移」快照的有效期（v38，ms）。
     *
     * 页面被拖到**极限**时（实测拖走 2710~2772px，而屏幕高就是 2772），参考点会失去
     * 「可用」资格、锚点可见面积也掉到 0 —— 于是 {@link #isPageHeld()} 的判据②直接
     * 返回 false，抑制门在最需要它的时候失守（日志里每次 hide 之前都先打出过一次
     * {@code hide suppressed}，就是「挡了一下然后失守」）。
     *
     * 快照把「页面刚才被拖开过」这件事记住一段时间，把判据撑过这段空窗。
     * 真正的放行由「参考点已 detach」和这个时间上限**双重**保证，所以不会赖着不走。
     */
    private static final long HOLD_SNAPSHOT_MS = 1500L;

    /** 「尚无基线」哨兵值（用 Integer.MIN_VALUE 而不是 0，避免把屏幕顶边当成合法基线）。 */
    private static final int FOLLOW_NO_BASELINE = Integer.MIN_VALUE;

    /** 复用的取坐标数组 —— 跟帧循环每帧都要读，避免每帧 new int[2]。 */
    private static final int[] sTmpLoc = new int[2];

    /**
     * 跟随参考点的弱引用（v36 起**粘住**）。
     *
     * 为什么必须粘住：v35 每轮都现场重挑参考点，而拖动过程中 {@code verdict} 会在
     * PLAYER / OTHER / UNKNOWN 之间反复跳，扫描结果随之变化 → 基线跟着漂、位移算出
     * 来时大时小时而恒为 0。现在一旦认下来，只要它还在窗口里、还可见、还有尺寸，
     * 就**一直用它**，无论判定结果怎么变。
     */
    private static WeakReference<View> sFollowRef;
    /** 当前参考点是不是播放键（true）/ 退化用的页面容器（false）—— 只用于日志。 */
    private static boolean sFollowRefIsPlayBtn = false;
    /** 当前已施加在按钮上的位移（px），用于去重与日志。 */
    private static int sFollowAppliedY = 0;
    /** 跟帧循环是否在跑。 */
    private static boolean sFollowing = false;
    /** 连续静止帧计数。 */
    private static int sFollowStill = 0;
    /** 连续读不到参考点的帧数（见 {@link #FOLLOW_MISS_MAX_FRAMES}）。 */
    private static int sFollowMiss = 0;
    /** 本轮跟帧循环的起点（uptimeMillis），用于 {@link #FOLLOW_MAX_MS}。 */
    private static long sFollowStartMs = 0L;
    /**
     * 上一次算出来的位移量（v37 从「上一次参考点 y」改过来）。
     *
     * 上一版比的是「参考点原始屏幕 y」，那只在基线通道下成立；v37 主通道读的是
     * transform 累加值，不会去写屏幕 y —— 直接比位移量才是两条通道都成立的口径。
     */
    private static int sLastFollowY = FOLLOW_NO_BASELINE;
    /** 本轮是否已经打过「跟随」日志。 */
    private static boolean sFollowLogged = false;
    /** 上一次写进日志的位移值（见 {@link #FOLLOW_LOG_STEP_PX}）。 */
    private static int sFollowLoggedY = FOLLOW_NO_BASELINE;
    /**
     * 上一次写进日志的时刻（v39）。
     *
     * 用来量「跟手断层」：新一轮循环的第一行日志与上一行之间的**时间差 + 位移差**
     * 就是用户看到的「按钮冻住 N 毫秒、然后一次性补跳 N 像素」。修 v39 之前实测
     * **86 次重启全部**有 ≥100ms 停顿（p50 587ms），补跳 p50 122px / 最大 2769px。
     */
    private static long sFollowLoggedMs = 0L;
    /** 上一次打「跟手因缺基线而跳过」诊断行的时间（1s 节流）。 */
    private static long sFollowRejectLogMs = 0L;
    /** 本轮「无播放页证据」是否已经因为「页面被拖住」而被拦下（每轮只打一行日志）。 */
    private static boolean sHoldSuppressLogged = false;

    /**
     * 自身 {@code setTranslationY} 的重入闸。
     *
     * 我们靠 {@code View.setTranslationY} 这个宿主钩子（见 {@code StructureWatcher}）
     * 得知「页面在动」，而按钮自己也是 {@code View} —— 不给闸就会自己触发自己。
     */
    private static boolean sSelfTranslate = false;

    // ── 参考点识别参数 ──
    /** 播放键的类名标识（com.airbnb.lottie.LottieAnimationView）。用类名匹配，不受混淆影响。 */
    private static final String LOTTIE_CLASS_MARK = "LottieAnimationView";
    /**
     * 在锚点下这么多层内找播放键。
     *
     * v35 按记忆里的「第 2~3 层」设成 3 —— 实测**够不着**：60+ 次跟随全部是
     * {@code ref=anchor}，播放键一次都没认出来。放宽到 8 层。
     */
    private static final int PLAY_BTN_MAX_DEPTH = 8;
    /**
     * 遍历节点的硬预算。
     *
     * v35 设 80 —— 这是 {@code ref=anchor} 的**直接原因**：锚点有 19 个直接子节点
     * （页面区块），播放键在**最后一个**区块里，而 DFS 从第 0 个孩子起步，
     * 80 个节点根本走不到那里就耗尽了。
     * v36：提高预算 + **从后往前**遍历（播放键在页面下部 = 靠后的区块）。
     */
    private static final int PLAY_BTN_MAX_NODES = 2000;
    /** 播放键最小边长（px）。 */
    private static final int PLAY_BTN_MIN_PX = 48;

    /**
     * 参考点在**页面静止**时的屏幕绝对 y（基线）。
     *
     * v36 把 v35 的 {@code sPlayBtnBaseY} / {@code sAnchorBaseY} 合并成一个。
     * 两者本就同层等值，留双份只会让「门③」拿错那一份 —— 那正是 v35 基线被
     * 反复覆盖、按钮卡死的根因。
     */
    private static int sFollowBaseY = FOLLOW_NO_BASELINE;
    /**
     * 「无基线位移」通道的输出：{@link #pageVisualOffset()} 累加出来的原始值。
     *
     * 这套通道是 v37 的关键新增，专门解决「基线」这一整类问题：
     *   · 位移 = 参考点祖先链上所有 {@code translationY} 之和 − 所有 {@code scrollY} 之和；
     *   · 页面处在**布局静止位**时它恒为 0 —— **根本不需要基线**，
     *     因此也就不存在「基线被拖动中的位置覆盖」「换页后基线过期」「参考点换实例
     *     导致基线与参考点不匹配」这些坑（v35/v36 全部栽在这里）；
     *   · 拖动播页时手指按住不放、页面停在半路，它仍然是「被拖开的量」而不是 0
     *     —— 基线方案在那种情况下会自愈成 0（按钮弹回原位）。
     *
     * 它是**首选**通道；只有它在整个进程里一次都没动过（说明这个 App 的位移不是
     * transform 驱动）才退回老的「基线 + 屏幕 y」通道。
     */
    private static int sVisualOffset = FOLLOW_NO_BASELINE;
    /**
     * 位移通道偏置：**首次确认播放页静止时**读到的 {@link #pageVisualOffset()}。
     *
     * 为什么要减掉它：祖先链里若有一个**常驻**的非零 translationY（系统 insets、
     * 宿主布局补偿等），累加值会带一个常数偏置 → 按钮会永久偏移那么多。
     * 在「确认静止」的那一刻取一次差，就把这个常数抹平了，而且**不需要反复重采**。
     */
    private static int sVisualBias = FOLLOW_NO_BASELINE;
    /** transform 位移通道是否被证实**有效**（见过 ≥8px 的变化）。有效则不再用基线通道。 */
    private static boolean sVisualSeen = false;
    /** 最近一次「页面在动」的时刻（uptimeMillis），见 {@link #PAGE_MOTION_HOLD_MS}。 */
    private static long sLastPageMotionMs = 0L;
    /** 上一次播放键 DFS 扫描的时刻，见 {@link #PLAY_BTN_RESCAN_MS}。 */
    private static long sLastPlayScanMs = 0L;
    /** 「页面在动」采样的上一帧值（与 {@link #sSampleY} 分开：两者读的是不同量）。 */
    private static int sMotionSampleY = FOLLOW_NO_BASELINE;

    /**
     * 「页面刚才被拖开过」的快照（v38）：最后读到的位移量 + 时刻 + 当时的参考点。
     *
     * 为什么需要它：页面被拖到极限时，参考点会失去「可用」资格（滚出屏幕）、
     * 锚点可见面积也趋近 0 —— 于是 {@link #isPageHeld()} 的判据②**在最需要它的时刻
     * 恰好失效**。实测 23 次 hide，**每一次**之前都先打出过一次 {@code hide suppressed}
     * （门挡住了），然后在页面到位的那一刻失守。
     *
     * 这份快照只由 {@link #pageVisualOffset()} 在成功读到位移时更新，**从不清空**
     * （清空只发生在 {@link #invalidateFollow()}）。配合
     * {@link #holdProbeStillAttached()}：页面真被卸载时参考点会 detach → 立即放行。
     */
    private static int sHeldOffset = FOLLOW_NO_BASELINE;
    private static long sHeldOffsetMs = 0L;
    private static WeakReference<View> sHoldProbeRef;
    /** 最近一次扫描用的屏幕尺寸（{@link #isPageHeld} 要用它算锚点可见面积）。 */
    private static int sLastScreenW = 0;
    private static int sLastScreenH = 0;
    /** 上一次「便宜采样」读到的参考点 y（见 {@link #maybeStartPageFollow()}）。 */
    private static int sSampleY = FOLLOW_NO_BASELINE;
    /** 基线候选：连续两次读到同一个位置才落定基线（见 {@link #captureFollowBaseline()}）。 */
    private static int sBaseCandY = FOLLOW_NO_BASELINE;

    /** {@link #scanPlayButton} 的临时结果（只用于单次挑选，主线程独占）。 */
    private static int sPickCenterX = 0;
    private static View sPickBest = null;
    private static int sPickScore = Integer.MAX_VALUE;
    private static View sPickFallback = null;
    private static int sPickBudget = 0;

    private static TextView sButton;
    private static Activity sActivity;
    private static ViewTreeObserver.OnGlobalLayoutListener sLayoutListener;
    private static long sLastDetectMs = 0L;
    private static String sLastBtnText;
    /** 连续判「非播放页」的采样次数。一旦判为播放页立即清零。 */
    private static int sNotPlayerStreak = 0;
    /** 本轮「连续判非播放页」的起点时刻（uptimeMillis）；0 = 当前不在连败中。 */
    private static long sNotPlayerSinceMs = 0L;
    /**
     * 最近一次**确定**下来的结论（跨 onPause/onResume 保留）。
     * 按钮重建时用它初始化可见性，避免「从 GONE 开始干等扫描」造成的空窗。
     */
    private static Boolean sLastDecision = null;
    /** 上一次的证据指纹。只在证据本身变化时才打日志（见 evidenceSignature）。 */
    private static String sLastEvidenceSig = null;
    /** 模糊证据期间的快速复检次数（上限见 {@link #MAX_AMBIGUOUS_RECHECKS}）。 */
    private static int sAmbiguousRechecks = 0;
    /** 该次检测是否允许绕过节流（只用于切页动画期间的快速复检）。 */
    private static boolean sForceNextDetect = false;

    /** 下一次心跳的间隔（由最近一次扫描结论决定，见 {@link #decideHeartbeat}）。 */
    private static long sNextHeartbeatMs = HEARTBEAT_MS;
    /** 上一次的判定结论（-1 = 尚未判定），用于「结论有没有变」与稳定计数。 */
    private static int sLastVerdict = -1;
    /** 连续多少次扫描结论未变。 */
    private static int sStableStreak = 0;

    /**
     * 播放页锚点：判定到播放页时记下的**页面级容器**（弱引用，避免泄漏 Activity）。
     * 它是「还在不在播放页」的判据：容器还在 → 在播放页；容器没了 → 已离开。
     */
    private static WeakReference<View> sPlayerAnchor;
    /** 最近一次拿到「播放页证据」的时刻（uptimeMillis）；0 表示还没拿到过。 */
    private static long sLastPlayerSeenMs = 0L;
    /** 锚点存活状态的翻转记录（仅用于打日志，避免重复刷屏）。 */
    private static Boolean sLastAnchorAlive = null;
    /** 锚点描述（仅用于打日志）。 */
    private static String sLastAnchorDesc = null;
    /** 锚点**连续**失效的起始时刻（uptimeMillis）；0 = 锚点当前视为存活。 */
    private static long sAnchorDeadSinceMs = 0L;
    /** 本轮「锚点连续失效」已经采到几次（见 {@link #ANCHOR_DEAD_MIN_SAMPLES}）。 */
    private static int sAnchorDeadSamples = 0;
    /** 锚点失效期间是否已打过一行诊断日志（避免每个采样点都刷屏）。 */
    private static boolean sAnchorDeadLogged = false;

    /**
     * 锚点**连续存活**的起始时刻（uptimeMillis）；0 = 锚点当前视为失效。
     *
     * v30 修正：原来 {@link #ANCHOR_HARD_TIMEOUT_MS} 用 `sUnknownSinceMs`（= 进入 UNKNOWN 状态的
     * 时刻）当计时起点，它比「锚点恢复存活」早得多。后果：长时间 UNKNOWN 之后，锚点刚复活就被
     * 这个 3s 超时判掉，**反而错过了「立即恢复显示」**。实测日志铁证：
     * `10:01:12.886 anchor alive=true (area=58%)` 当时没有恢复显示，一直拖到
     * `10:01:13.492` 才由一次 PLAYER 判定把按钮亮出来 —— **白等 606ms**。
     * 现在改成"锚点连续存活了多久"，与它本来的意图（锚点选得过高 → 一直可见却一直 UNKNOWN）一致。
     */
    private static long sAnchorAliveSinceMs = 0L;

    /** 最近一次「看到底部 mini-player 滑条」（正面证据）的时刻；0 = 本次会话尚未见过。 */
    private static long sLastOtherSeenMs = 0L;

    /** 上一次探针读到的锚点存活状态；null = 尚未采样（用于识别活/死翻转）。 */
    private static Boolean sLastProbeAlive = null;

    /**
     * v33 诊断：本次进程是否已经打过一次视图树（见 {@link SubtitleViewHook#dumpViewTree}）。
     * 用途是定位「播放器传输控件行」，供下一版把开关按钮**注入**进去。
     * 只打一次 —— 整棵树有几百个 View，每次进播放页都打会淹掉日志。
     */
    private static boolean sTreeDumped = false;

    // ── v34：宿主结构事件驱动的即时扫描 ────────────────────────────────────
    /** 上一次因结构事件触发扫描的时刻（uptimeMillis）；用于 {@link #POKE_MIN_INTERVAL_MS} 合并。 */
    private static long sLastPokeMs = 0L;
    /** 本轮「结构事件爆发」的起点（uptimeMillis）；仅用于在日志里量「事件→按钮出现」的延迟。 */
    private static long sPokeBurstStartMs = 0L;
    /** 最近一次结构事件的时刻（uptimeMillis），用于判断爆发是否还在继续。 */
    private static long sLastPokeEventMs = 0L;
    /** 累计触发次数 / 已打印日志次数（诊断）。 */
    private static int sPokeCount = 0;
    private static int sPokeLogged = 0;
    /** 令牌桶：本秒内已加急的次数与窗口起点（见 {@link #POKE_MAX_PER_SEC}）。 */
    private static long sPokeWindowStartMs = 0L;
    private static int sPokeWindowCount = 0;

    /**
     * 锚点是否已从窗口摘除（v34，attach 监听的回调）。
     *
     * 这是一条**硬证据**：视图都摘下来了，就不可能还在当前页 → 不必再等
     * {@link #ANCHOR_DEAD_MIN_MS} 的确认窗，直接隐藏。播放页真正被销毁时走得最干净。
     */
    private static boolean sAnchorDetached = false;
    /** 已经挂过 attach 监听的锚点实例（避免重复挂）。 */
    private static View sAnchorWatched = null;

    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** onGlobalLayout / 重建按钮后的补检（受节流约束，不负责续心跳）。 */
    private static final Runnable detectRunnable = new Runnable() {
        @Override
        public void run() {
            if (sActivity != null) {
                detectAndLayout(sActivity, SubtitleRepository.getInstance());
            }
        }
    };

    /**
     * 心跳：**自带重排**的独立检测链，保证检测永不因「页面静止、无布局回调」而停摆。
     *
     * 与 {@link #detectRunnable} 分开是 v24 的关键：旧版两者共用一个 Runnable，
     * 而被节流时是 `return` 提前返回（不重排）—— 一旦心跳那次刚好被节流，
     * 整条链就断了，按钮能失踪好几秒。现在心跳无论是否被节流都会重排。
     */
    private static final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (sActivity != null) {
                    detectAndLayout(sActivity, SubtitleRepository.getInstance());
                }
            } finally {
                armHeartbeat();
            }
        }
    };

    /**
     * 廉价锚点探针（v30，见 {@link #ANCHOR_WATCH_MS}）。
     *
     * 只做一件事：盯着**锚点这一个 View** 的存活状态，状态一变、或锚点持续失效（正在等确认），
     * 就让完整扫描立刻跑一次（绕过节流）。它**不做任何判定** —— 所有决策仍然只发生在
     * {@link #detectAndLayout} 里，避免出现两套各说各话的状态机。
     *
     * 与心跳一样是自带重排的独立 Runnable（`finally` 里重新 arm），不会因中途 return 而断链。
     */
    private static final Runnable anchorWatchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (sActivity == null || sButton == null || !Boolean.TRUE.equals(sLastDecision)) {
                    return; // 只在「按钮可见 = 认定在播放页」时才有意义
                }
                View anchor = sPlayerAnchor == null ? null : sPlayerAnchor.get();
                if (anchor == null) {
                    return;
                }
                boolean alive = SubtitleViewHook.isEffectivelyVisible(anchor);
                boolean flipped = sLastProbeAlive == null || sLastProbeAlive != alive;
                sLastProbeAlive = alive;
                if (!flipped && alive) {
                    return; // 状态没变且还活着 —— 交给常规心跳，不必加急
                }
                // 两种情形都要加急：
                //   ① 刚翻转（活↔死）→ 立刻看清到底发生了什么；
                //   ② 持续失效 → 推进「锚点已经死了多久」这个墙钟（隐藏决策在 detectAndLayout 里做）。
                sForceNextDetect = true;
                uiHandler.removeCallbacks(detectRunnable);
                uiHandler.post(detectRunnable);
            } catch (Throwable e) {
                XposedBridge.log(TAG + " anchor watch error: " + e.getMessage());
            } finally {
                uiHandler.removeCallbacks(anchorWatchRunnable);
                uiHandler.postDelayed(anchorWatchRunnable, ANCHOR_WATCH_MS);
            }
        }
    };

    /** 启动 / 重启廉价锚点探针。 */
    private static void armAnchorWatch() {
        uiHandler.removeCallbacks(anchorWatchRunnable);
        uiHandler.postDelayed(anchorWatchRunnable, ANCHOR_WATCH_MS);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  v35：按钮「跟手」——页面被上下拖动时，按钮跟着同一个位移走
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 在播放页锚点子树里挑出「播放键」。
     *
     * 真实结构（1.20.9 打的那份视图树 dump，见 work_diag_20）：
     * <pre>
     *   ReactViewGroup @0,141 1272x2583 kids=19      ← 页面锚点 sPlayerAnchor
     *     ReactViewGroup @529,2121 214x214 kids=0    ← 播放键外圈光晕
     *     ReactViewGroup @562,2154 148x149 kids=1    ← 播放键容器
     *       LottieAnimationView @562,2154 148x149    ← ← 就是它（播放/暂停图标）
     * </pre>
     *
     * 判据刻意做得「宽进严出」——
     *   · **只在锚点下 3 层内**找（结构已知，再深就不是控件行了）；
     *   · 类名含 {@code LottieAnimationView}（不依赖混淆）；
     *   · 尺寸 ≥48px 且**基本方正**（播放键是圆/正方形；进度条、装饰线会被这条筛掉）；
     *   · 多个候选时取**水平最靠屏幕中线**的那个（播放键在控件行正中）。
     * 全都不满足时返回 null，调用方自动退回用**页面锚点**当参考点（同一个位移层）。
     */
    private static View ensureFollowRef() {
        View cached = sFollowRef == null ? null : sFollowRef.get();
        if (isUsableReference(cached)) {
            return cached; // 粘住：拖动过程中判定结果怎么变都不换参考点
        }
        sFollowRef = null;
        sFollowRefIsPlayBtn = false;
        View anchor = sPlayerAnchor == null ? null : sPlayerAnchor.get();
        if (anchor == null) {
            return null;
        }
        // 播放键扫描是 DFS，绝不能被每帧调用 —— 节流；扫不到就直接用锚点顶着。
        long now = SystemClock.uptimeMillis();
        View found = null;
        if (now - sLastPlayScanMs >= PLAY_BTN_RESCAN_MS) {
            sLastPlayScanMs = now;
            found = pickPlayButton(anchor);
        }
        if (found != null) {
            adoptFollowRef(found, true);
            return found;
        }
        // 找不到播放键 → 退回**页面容器**（与播放键同一位移层，位移量等价）。
        // v36 起这里也写进 sFollowRef，让「粘住」同样适用于退化情形。
        if (isUsableReference(anchor)) {
            adoptFollowRef(anchor, false);
            return anchor;
        }
        return null;
    }

    /**
     * 认下新的参考点实例，并把**基线平移到新实例上**（v37 新增，修「飞到屏幕上方」）。
     *
     * ⚠️⚠️ v35/v36 的致命坑：参考点会在「播放键」与「页面锚点」之间**悄悄换人**——
     * 播放键的 View 实例被 RN 重渲染换掉 / 短暂判为不可用时，{@code ensureFollowRef}
     * 直接改认锚点，可**基线还是播放键那一刻的**。两者屏幕 y 差 2000+ px
     * （播放键 @2156、页面锚点 @141），于是位移量瞬间变成 **−2015px**，
     * 按钮当场飞到屏幕上方 —— 日志里的
     * {@code page follow on: button translationY=-2015px (ref=anchor base=2156)}
     * 就是这个。而且日志上的 {@code ref=} 读的是「刚刚被重置的」标志位，
     * 所以看起来永远是 {@code ref=anchor}，把「播放键其实被认出来过」这件事完全盖住了。
     *
     * v37 的修法：**换参考点必须同时平移基线**，保持当前位移量不变。
     * 两个实例同属一个位移层（相对偏移是常数），所以 {@code 新基线 = 新参考点当前 y − 当前位移}
     * 在物理上是精确的，视觉上**零跳变**。
     */
    private static void adoptFollowRef(View v, boolean isPlayBtn) {
        sFollowRef = new WeakReference<>(v);
        sFollowRefIsPlayBtn = isPlayBtn;
        if (sFollowBaseY == FOLLOW_NO_BASELINE) {
            return; // 还没有基线，无从平移
        }
        try {
            v.getLocationOnScreen(sTmpLoc);
        } catch (Throwable e) {
            sFollowBaseY = FOLLOW_NO_BASELINE;
            return;
        }
        int oldBase = sFollowBaseY;
        sFollowBaseY = sTmpLoc[1] - sFollowAppliedY;
        sBaseCandY = FOLLOW_NO_BASELINE;
        sSampleY = FOLLOW_NO_BASELINE;
        XposedBridge.log(TAG + " page follow rebase: ref -> "
                + (isPlayBtn ? "play-button" : "anchor")
                + " | base " + oldBase + " -> " + sFollowBaseY
                + " (keep offset " + sFollowAppliedY + "px)");
    }

    /** 参考点是否还能用（已挂到窗口 + 可见 + 有尺寸）。RN 重渲染会换实例，所以每次取用都要校验。 */
    private static boolean isUsableReference(View v) {
        return v != null
                && v.isAttachedToWindow()
                && v.getVisibility() == View.VISIBLE
                && v.getWidth() > 0
                && v.getHeight() > 0;
    }

    private static View pickPlayButton(View anchor) {
        int centerX;
        try {
            centerX = sButton == null ? 0
                    : sButton.getResources().getDisplayMetrics().widthPixels / 2;
        } catch (Throwable e) {
            centerX = 0;
        }
        sPickCenterX = centerX;
        sPickBest = null;
        sPickScore = Integer.MAX_VALUE;
        sPickFallback = null;
        sPickBudget = PLAY_BTN_MAX_NODES;
        scanPlayButton(anchor, PLAY_BTN_MAX_DEPTH);
        return sPickBest != null ? sPickBest : sPickFallback;
    }

    /** DFS 收集候选；用一个共享预算封顶，避免遇到变态深的树。 */
    private static void scanPlayButton(View v, int depth) {
        if (v == null || depth < 0 || sPickBudget <= 0) {
            return;
        }
        sPickBudget--;
        if (v.getClass().getName().contains(LOTTIE_CLASS_MARK)) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (sPickFallback == null) {
                sPickFallback = v; // 兜底：只要是个 Lottie 就留着，万一方正判据挑不出来
            }
            if (w >= PLAY_BTN_MIN_PX && h >= PLAY_BTN_MIN_PX
                    && Math.abs(w - h) <= Math.max(w, h) * 0.4f) {
                v.getLocationOnScreen(sTmpLoc);
                int score = Math.abs(sTmpLoc[0] + w / 2 - sPickCenterX);
                if (score < sPickScore) {
                    sPickScore = score;
                    sPickBest = v;
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            // v36：**从后往前**遍历。
            // 播放键在播放页的传输控件行里（页面靠下），在锚点的直接子节点列表（19 个页面区块）
            // 中排在靠后的位置。v35 用正序 + 80 个节点预算，一路走前面的区块就把预算耗光了，
            // 永远到不了播放键 —— 这就是那 60+ 次跟随**全部** `ref=anchor` 的原因。
            for (int i = n - 1; i >= 0; i--) {
                scanPlayButton(g.getChildAt(i), depth - 1);
            }
        }
    }

    /**
     * 读「页面参考点」相对静止基线的位移（px）。负数 = 页面上移。
     *
     * 参考点优先用**播放键**（用户指出要绑的就是它），退化用**页面锚点** ——
     * 两者同属一个位移层，算出来的位移量完全一致。基线**不在这里**惰性补，
     * 必须在页面静止时由 {@link #captureFollowBaseline()} 采集，否则第一次拖动
     * 会把「拖动中的位置」当基线，位移恒为 0（= 完全不动）。
     *
     * v36：参考点与基线都收敛成**唯一一份**（{@link #sFollowRef} / {@link #sFollowBaseY}）。
     * v35 在**每次调用**里现场重挑参考点，拖动中 verdict 一跳就换基线来源，
     * 位移随之跳变；现在粘住一个实例读到底。
     *
     * v37：这条**退化成兜底通道** —— 主通道换成 {@link #pageVisualOffset()}（无基线），
     * 只有主通道被证实不适用时才会走到这里。保留它的价值是「新通道失效 ≠ 功能全废」。
     */
    private static int referenceDelta() {
        View ref = ensureFollowRef();
        if (!isUsableReference(ref)) {
            return FOLLOW_NO_BASELINE;
        }
        ref.getLocationOnScreen(sTmpLoc);
        if (sFollowBaseY == FOLLOW_NO_BASELINE) {
            return FOLLOW_NO_BASELINE;
        }
        return sTmpLoc[1] - sFollowBaseY;
    }

    /**
     * **无基线**位移通道：参考点（含全部祖先）相对「布局静止位」的视觉位移。
     *
     * 位移 = Σ 祖先链 {@code translationY} − Σ 祖先链 {@code scrollY}
     * （参考点自身的 {@code scrollY} 不算 —— 它只挪自己的子节点，不挪自己）。
     * 页面处在**布局静止位**时恒为 0。
     *
     * 为什么要这条路（v37 的核心结论）：
     *   · **不需要基线** → 「基线被拖动中的位置覆盖 / 换页后过期 / 与参考点不匹配」
     *     这三类 bug 一次性消失（v35、v36 全部栽在这上面）；
     *   · **不受参考点换实例影响** —— 播放键与页面锚点算出来**完全相等**
     *     （同一祖先链，只多几个零位移的节点），换参考点不产生跳变；
     *   · 手指按住页面停在半路时它**仍是「被拖开的量」**，不会像基线方案那样
     *     自愈成 0（自愈 = 按钮弹回原位 = 用户说的「卡在原位置不动」）。
     *
     * 减掉 {@link #sVisualBias}：祖先链里若有常驻的非零 translationY（系统 insets、
     * 宿主布局补偿等），累加值会带一个常数偏置 → 按钮永久偏移那么多。在「确认页面
     * 静止」的那一刻取一次差就把它抹平了，且**不需要反复重采**。
     */
    private static int pageVisualOffset() {
        View v = ensureFollowRef();
        if (v == null) {
            return FOLLOW_NO_BASELINE;
        }
        int sum = 0;
        View cur = v;
        boolean self = true;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            try {
                sum += (int) cur.getTranslationY();
                if (!self) {
                    sum -= cur.getScrollY();
                }
            } catch (Throwable ignored) {
                // 被回收 / 非主线程 —— 当作 0，继续往上走
            }
            self = false;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        sVisualOffset = sum;
        int vis = (sVisualBias == FOLLOW_NO_BASELINE) ? sum : (sum - sVisualBias);
        // v38：**成功读到位移时就留一份快照**，供 isPageHeld 的判据③用。
        // 页面被拖到极限后参考点会失去「可用」资格、面积也趋近 0，判据②会失效；
        // 这份快照把「页面刚才确实被拖开过」记住 HOLD_SNAPSHOT_MS 毫秒，
        // 撑过「页面已到位、但还没回弹」的那段空窗（实测最长 2.1s 的 hide→show 间隔）。
        sHeldOffset = vis;
        sHeldOffsetMs = SystemClock.uptimeMillis();
        sHoldProbeRef = new WeakReference<>(v);
        if (sVisualBias == FOLLOW_NO_BASELINE) {
            return sum; // 还没确认过静止 → 先原样返回（调用方的死区判断照旧成立）
        }
        return vis;
    }

    /**
     * 快照里的参考点**还在窗口里吗**？
     *
     * 这是「拖开」与「真的离开」的分水岭：
     *   · 被拖到极限 —— 参考点只是滚出屏幕，{@code isAttachedToWindow()} 仍为 true；
     *   · 页面被卸载（真的离开）—— RN 会把容器 detach，这里立刻变 false → 抑制门放行。
     * 所以判据③ 不需要靠时间上限去猜「是不是该放行了」。
     */
    private static boolean holdProbeStillAttached() {
        View v = sHoldProbeRef == null ? null : sHoldProbeRef.get();
        if (v == null) {
            return false;
        }
        try {
            return v.isAttachedToWindow();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 跟手用哪个位移量：**首选无基线通道**，它无效才退回「基线 + 屏幕 y」。
     *
     * 判定「有效」的条件：transform 通道出现过 ≥{@link #FOLLOW_DEADZONE_PX}px 的变化。
     * 一旦出现就认为这个 App 的位移确实是 transform 驱动，此后一直用它；
     * 整个进程内一次都没动过，才说明该通道不适用（例如位移是布局驱动的），
     * 用老通道兜着 —— 两条路都留着，不会出现「新通道失效 = 功能全废」。
     */
    private static int followOffset() {
        int vis = pageVisualOffset();
        // 偏置还没标定过就不敢信这条通道（标定需要一次「确认静止」，见 captureFollowBaseline()）
        if (vis != FOLLOW_NO_BASELINE && sVisualBias != FOLLOW_NO_BASELINE) {
            if (!sVisualSeen && Math.abs(vis) >= FOLLOW_DEADZONE_PX) {
                sVisualSeen = true;
                XposedBridge.log(TAG + " page follow channel: transform-sum active (first motion "
                        + vis + "px, bias=" + sVisualBias + ")");
            }
            if (sVisualSeen) {
                return vis;
            }
        }
        return referenceDelta();
    }

    /**
     * 结构事件到达时的**便宜采样**：页面动没动？动的话记下时刻。
     *
     * 这里同时承担「transform 通道是否有效」的发现（{@link #sVisualSeen}）——
     * 必须在这里发现，否则会死锁：跟帧循环要靠 sVisualSeen 才敢不用基线，
     * 而它自己又是被 startPageFollow 拉起来的。
     *
     * 便宜：只在值真的有变化时才动字段，页面静止时几乎零成本。
     */
    private static void notePageMotion() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return;
        }
        int vis = pageVisualOffset();
        if (vis == FOLLOW_NO_BASELINE) {
            return;
        }
        if (sVisualBias != FOLLOW_NO_BASELINE && !sVisualSeen
                && Math.abs(vis) >= FOLLOW_DEADZONE_PX) {
            sVisualSeen = true;
            XposedBridge.log(TAG + " page follow channel: transform-sum active (first motion "
                    + vis + "px, bias=" + sVisualBias + ")");
        }
        if (sMotionSampleY == FOLLOW_NO_BASELINE
                || Math.abs(vis - sMotionSampleY) >= FOLLOW_DEADZONE_PX) {
            sLastPageMotionMs = SystemClock.uptimeMillis();
        }
        sMotionSampleY = vis;
    }

    /**
     * 页面是不是**被手指拖住**（v37）—— 两条判据**都不需要基线**。
     *
     * ⚠️ v36 的这道门用基线算位移，而「没有基线」时它直接放行 —— 恰好在最需要它的
     * 场景（拖动中基线还没采到 / 已被 hide 清掉）完全失效，日志里那 15 处
     * {@code page follow skipped: no settled baseline yet} 就是它。
     *
     * 判据：
     *   ① 刚刚还在动（{@link #PAGE_MOTION_HOLD_MS} 窗口内）；
     *   ② 页面被拖开 ≥{@link #PAGE_HOLD_PX}px **且**锚点还留着
     *      ≥{@link #PAGE_HOLD_MIN_AREA} 的可见面积 —— 页面真的被划走时面积趋近 0，
     *      这条会放行，所以正常的「离开播放页 → 隐藏」一点不受影响。
     *   ③ **（v38 新增）最后已知的位移快照**：页面被拖到**极限**时参考点会失去
     *      「可用」资格、锚点可见面积也趋近 0 → 判据②恰好在最需要它的时候失守。
     *      实测 23 次 hide —— **每一次**之前都先打出过一次 {@code hide suppressed}，
     *      然后在页面到位的那一刻失守。③ 用「刚才被拖开过 + 参考点还在窗口里」
     *      把门撑过那段空窗；页面真被卸载时参考点会 detach → 立即放行。
     */
    private static boolean isPageHeld(long now) {
        if (sLastPageMotionMs != 0L && now - sLastPageMotionMs < PAGE_MOTION_HOLD_MS) {
            return true;
        }
        // 判据③：先于②判断 —— ②在「拖到极限」时必然失效，③正是为那一刻准备的。
        if (sHeldOffsetMs != 0L && now - sHeldOffsetMs < HOLD_SNAPSHOT_MS
                && Math.abs(sHeldOffset) >= PAGE_HOLD_PX
                && holdProbeStillAttached()) {
            return true;
        }
        int held = pageVisualOffset();
        if (held == FOLLOW_NO_BASELINE) {
            return false;
        }
        if (Math.abs(held) < PAGE_HOLD_PX) {
            return false;
        }
        if (sLastScreenW <= 0 || sLastScreenH <= 0) {
            return true; // 拿不到屏幕尺寸 → 宁可维持现状
        }
        return anchorAreaRatio(sLastScreenW, sLastScreenH) >= PAGE_HOLD_MIN_AREA;
    }

    /**
     * 在**页面静止**时采集基线（只由 PLAYER 分支调用）。
     *
     * 三道门，缺一不可（每一道都对应一种会把按钮永久带偏的坑）：
     *   ① 跟帧循环没在跑、且当前位移为 0 —— 跟帧循环只在连续 {@link #FOLLOW_STILL_FRAMES}
     *      帧没动之后才停，所以这两条同时成立时，页面确实已经落回原位；
     *   ② **连续两次读到同一个位置**才算静止 —— 播放页**入场动画**期间参考点也在动，
     *      那一刻采到的基线会让按钮此后一直偏几十 px；
     *   ③ 已有基线且当前位置离它很远时**不覆盖** —— 否则拖动刚开始那 50ms（跟帧循环
     *      还没起来）的一次 PLAYER 扫描会把「拖到一半的位置」当成新基线，位移随即归零，
     *      表现为「拖不动 / 拽到一半按钮又弹回去」。
     *
     * ⚠️ v36 复盘：**三道门里最关键的③在 v35 里是坏的**（读错字段，永远不生效），
     * 而①②又刚好都能通过 —— 于是「基线被反复覆盖」这个 ③ 专门要防的场景原样发生了。
     * 教训：**诊断字段写进日志之前，先确认它读的是「当前生效的那一份状态」**。
     */
    private static void captureFollowBaseline() {
        if (sFollowing || sFollowAppliedY != 0
                || Looper.myLooper() != Looper.getMainLooper()) {
            return;
        }
        int vis = pageVisualOffset();
        int y = readReferenceY();
        if (y == FOLLOW_NO_BASELINE) {
            sBaseCandY = FOLLOW_NO_BASELINE;
            return;
        }
        // 门③（v37 重写）：已有基线、当前位置离它很远时 —— 页面到底「真的回到布局静止位」了吗？
        // 用**无基线通道**回答（它不受换参考点影响）：transform 累加值 ≈ 0 才是真的在静止位。
        //   · 是 → 允许落定新基线（自愈：换页 / 换曲之后基线能跟上）
        //   · 否 → 页面只是被拖开、停在半路，**绝不覆盖** —— 覆盖 = 位移归零 = 按钮弹回原位，
        //          那正是用户说的「卡在原位置不动」。
        // ⚠️ v35 这里读错了字段（sPlayBtnBaseY 恒为哨兵）→ 这道门从来没生效过；
        //    v36 换了字段但仍然只能靠「页面确实不再动」间接推断，无法区分
        //    「回到原位」和「被拖住不动」。v37 用无基线通道把这件事直接问清楚。
        if (sFollowBaseY != FOLLOW_NO_BASELINE && Math.abs(y - sFollowBaseY) > FOLLOW_DEADZONE_PX) {
            boolean atLayoutRest = sVisualSeen && vis != FOLLOW_NO_BASELINE
                    && Math.abs(vis) < FOLLOW_DEADZONE_PX;
            if (!atLayoutRest) {
                sBaseCandY = y;
                return;
            }
        }
        // 门②：位置还在变 → 先记候选，等下一次扫描确认。
        if (sBaseCandY == FOLLOW_NO_BASELINE || Math.abs(y - sBaseCandY) > FOLLOW_DEADZONE_PX) {
            sBaseCandY = y;
            return;
        }
        sBaseCandY = y;
        View ref = ensureFollowRef();
        if (isUsableReference(ref)) {
            ref.getLocationOnScreen(sTmpLoc);
            sFollowBaseY = sTmpLoc[1];
        } else {
            sFollowBaseY = FOLLOW_NO_BASELINE;
        }
        // v37：**同一时刻**标定 transform 通道的常驻偏置。
        // 走到这里 = 跟帧循环停着 + 位移为 0 + 连续两次读数一致 → 页面确实在静止位，
        // 此时累加出来的 sum 就是那条常驻偏置（通常为 0，但不假设它一定为 0）。
        if (sVisualBias == FOLLOW_NO_BASELINE && vis != FOLLOW_NO_BASELINE) {
            sVisualBias = sVisualOffset;
            XposedBridge.log(TAG + " page follow bias calibrated: " + sVisualBias
                    + "px (ref=" + (sFollowRefIsPlayBtn ? "play-button" : "anchor") + ")");
        }
        sSampleY = FOLLOW_NO_BASELINE;      // 基线更新 → 便宜采样的历史值作废
        sMotionSampleY = FOLLOW_NO_BASELINE;
    }

    /**
     * 结构事件到达时的**便宜前置判断**：页面到底动没动？
     *
     * 为什么需要它：跟帧循环每帧都要读一次参考点。若不加这道门，App 里任何一个
     * **常驻动画**（缓冲转圈、循环闪烁）都会让结构事件持续不断 → 跟帧循环被反复重启
     * → 白白烧 CPU（虽然每帧很便宜，但没人愿意让它 24 小时跑）。
     *
     * v37：判据改成**无基线**的 transform 累加值（见 {@link #notePageMotion()}）——
     * 屏幕 y 那条路要先有基线才判得动，而没有基线时这里会一直沉默，
     * 于是「页面明明在动、循环就是起不来」= 用户说的「卡在原位置不动」。
     *
     * v39 补第二条启动判据：**页面现在就被拖开**（位移 &gt; 死区）也直接拉起。
     *
     * 为什么需要它：起始判据只有「最近 {@link #PAGE_MOTION_HOLD_MS} 内动过」，
     * 而循环一旦因为任何原因停了（hide→show、读不到参考点超上限、超时），
     * 就只能等下一次结构事件；页面停在被拖开的位置不动时**恰好没有结构事件**
     * → 按钮冻在半路。心跳（55ms 一趟，见 {@link #detectAndLayout}）现在也会
     * 调这里，于是最坏 55ms 就能接上。
     *
     * 事件被 {@link #POKE_MIN_INTERVAL_MS} 合并过（≥50ms 一次），所以这道门的
     * 采样频率本身就是有界的；连续拖动时每次采样都不同 → 循环该起就起。
     */
    private static void maybeStartPageFollow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return; // 帧回调和共享临时数组都只允许主线程用；非主线程的钩子直接跳过
        }
        notePageMotion(); // 无论如何先采样一次「页面在不在动」（只读字段，极为便宜）
        if (sFollowing || sActivity == null || sButton == null
                || sButton.getVisibility() != View.VISIBLE) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (sLastPageMotionMs != 0L && now - sLastPageMotionMs < PAGE_MOTION_HOLD_MS) {
            startPageFollow();
            return;
        }
        // v39：页面**此刻就是被拖开的** —— 循环若在半路掉了，这里立刻接上。
        // 已经跑起来的循环会在上面 `sFollowing` 处返回，不会重复启动。
        if (sVisualSeen && sVisualBias != FOLLOW_NO_BASELINE) {
            int vis = pageVisualOffset();
            if (vis != FOLLOW_NO_BASELINE && Math.abs(vis) > FOLLOW_DEADZONE_PX) {
                startPageFollow();
            }
        }
    }

    /** 读参考点屏幕 y（与 {@link #referenceDelta} 同源，但不做基线判空）。 */
    private static int readReferenceY() {
        View ref = ensureFollowRef();
        if (isUsableReference(ref)) {
            ref.getLocationOnScreen(sTmpLoc);
            return sTmpLoc[1];
        }
        return FOLLOW_NO_BASELINE;
    }

    /**
     * 启动跟帧循环（每帧同步一次位移）。已在跑 / 非主线程 / 两条位移通道都不可用时返回。
     *
     * v37 放宽启动条件：不再强求「已有基线」。transform 通道（无基线）一旦被证实可用
     * 就够开工了 —— 老条件是「没有基线就不启动」，而基线恰好是拖动中最容易缺的东西，
     * 于是出现大量 {@code page follow skipped: no settled baseline yet}（实测 15 次），
     * 表现就是「页面在动、按钮一动不动」。现在**只要有一条第就能启动**。
     */
    private static void startPageFollow() {
        if (sFollowing) {
            return;
        }
        boolean transformReady = sVisualSeen && sVisualBias != FOLLOW_NO_BASELINE;
        if (sFollowBaseY == FOLLOW_NO_BASELINE && !transformReady) {
            // 诊断（v36 新增）：v35 这里**静默失败** —— 页面明明在动、循环就是起不来，
            // 日志里一个字都没有，只能靠猜。这条行就是给下一轮排查用的。
            long now = SystemClock.uptimeMillis();
            if (now - sFollowRejectLogMs >= 1000L) {
                sFollowRejectLogMs = now;
                XposedBridge.log(TAG + " page follow skipped: no baseline & no transform channel");
            }
            return; // 两条通道都没有 —— 等一次「页面静止的 PLAYER 扫描」把基线/偏置采出来
        }
        sFollowing = true;
        sFollowStartMs = SystemClock.uptimeMillis();
        sFollowStill = 0;
        sFollowMiss = 0;
        sLastFollowY = FOLLOW_NO_BASELINE;
        sFollowLogged = false;
        sFollowLoggedY = FOLLOW_NO_BASELINE;
        try {
            Choreographer.getInstance().postFrameCallback(followFrameCallback);
        } catch (Throwable e) {
            sFollowing = false;
        }
    }

    /**
     * 停掉跟帧循环。
     *
     * v37：{@code resetOffset=true} 只**清零位移（视觉归位）**，不再作废基线 / 偏置。
     * 原因：v36 每次 hide 都顺手把基线扔掉，而 hide 在拖动中会频繁发生
     * （锚点 area 掉到 30~45% → 判「无播放页证据」）→ 重新显示后基线没了、
     * 循环起不来 → 按钮**卡在原位**。基线的失效只该由「参考点真的没了 / 换了页面」
     * 触发，那两条路径各自显式处理。
     */
    private static void stopPageFollow(boolean resetOffset) {
        sFollowing = false;
        sFollowStill = 0;
        try {
            Choreographer.getInstance().removeFrameCallback(followFrameCallback);
        } catch (Throwable ignored) {
            // 非主线程 / 无 Looper —— 此处无事可做
        }
        if (resetOffset) {
            applyFollowOffset(0);
            sSampleY = FOLLOW_NO_BASELINE;
            sMotionSampleY = FOLLOW_NO_BASELINE;
            sFollowMiss = 0;
            sFollowLogged = false;
        }
    }

    /**
     * **彻底作废**整个跟手机制（位移 / 基线 / 偏置 / 参考点）。
     *
     * 什么时候用：参考点真的没了、换页面了、按钮被重建了 —— 也就是「旧的位移参考系
     * 整体失效」的时刻。**不要**在普通的 hide 里用它（那正是 v36 的病根：
     * 拖动中 hide 顺手扔掉基线 → 重新显示后按钮卡在原位）。
     */
    private static void invalidateFollow() {
        stopPageFollow(true);
        sFollowAppliedY = 0;
        sFollowBaseY = FOLLOW_NO_BASELINE;
        sBaseCandY = FOLLOW_NO_BASELINE;
        sVisualOffset = FOLLOW_NO_BASELINE;
        sVisualBias = FOLLOW_NO_BASELINE;
        sVisualSeen = false;
        sFollowRef = null;
        sFollowRefIsPlayBtn = false;
        sLastPlayScanMs = 0L;
        sLastPageMotionMs = 0L;
        sFollowLoggedY = FOLLOW_NO_BASELINE;
        // v38：抑制门的「拖开快照」也必须作废 —— 它记录的是**旧参考系**下的位移，
        // 留着会让新页面一上来就被误判成「刚被拖开过」。
        sHeldOffset = FOLLOW_NO_BASELINE;
        sHeldOffsetMs = 0L;
        sHoldProbeRef = null;
    }

    /**
     * 跟帧循环本体（v35）。
     *
     * 为什么用 {@link Choreographer} 而不是复用扫描：v31 的「一格格跳」就是因为位置只在
     * 扫描时（被节流 150ms / 心跳 200~600ms）才重算。这里每帧读一次参考点、把差值直接
     * 写进 {@code translationY}，与宿主动画同帧输出，才是真正的「跟手」。
     *
     * 成本控制全部内建：
     *   · 只在「页面在动 / 页面正被拖开」时启动（见 {@link #maybeStartPageFollow()}）；
     *   · v39：**只有页面回到静止位附近**才用「连续 {@link #FOLLOW_STILL_FRAMES} 帧没动」注销
     *     （页面停在被拖开的位置时**必须继续跑**，否则就是「冻住 → 补跳」，见下面注释）；
     *   · {@link #FOLLOW_MAX_MS} / {@link #FOLLOW_MAX_HELD_MS} 是安全网，避免无限跟；
     *   · 写位移用 {@code setTranslationY}，**不触发布局**（这也是与 v31 布局回环的分水岭）。
     */
    private static final Choreographer.FrameCallback followFrameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    sFollowing = false; // 先置 false：下面任何一条 return 都意味着循环结束
                    boolean again = false;
                    try {
                        if (sActivity == null || sButton == null
                                || sButton.getVisibility() != View.VISIBLE) {
                            return;
                        }
                        int delta = followOffset();
                        boolean held = delta != FOLLOW_NO_BASELINE
                                && Math.abs(delta) > FOLLOW_DEADZONE_PX;
                        long nowMs = SystemClock.uptimeMillis();
                        // v39：时长上限**只在页面回到静止位附近时才收口**。
                        // 页面被拖开（held）时必须继续跑 —— 见 FOLLOW_MAX_HELD_MS。
                        long cap = held ? FOLLOW_MAX_HELD_MS : FOLLOW_MAX_MS;
                        if (nowMs - sFollowStartMs > cap) {
                            return;
                        }
                        if (delta == FOLLOW_NO_BASELINE) {
                            // ⚠️ v35 在这里直接 return → `again` 保持 false → 循环被**一次性杀死**。
                            // 而拖动期间参考点会被短暂判成「不可用」（锚点 area 掉到 45%、
                            // verdict 在 PLAYER/OTHER 之间来回跳），这种**瞬时**读不到
                            // 绝不该结束跟手 —— 何况 RN 拖动不触发布局回调，
                            // 结构事件不一定再来，循环再也起不来 = 按钮永久卡死。
                            // v36：跳过这一帧、清掉静止计数（免得误判「页面停了」），
                            // 连续 FOLLOW_MISS_MAX_FRAMES 帧都读不到才认输。
                            if (++sFollowMiss >= FOLLOW_MISS_MAX_FRAMES) {
                                return;
                            }
                            sFollowStill = 0;
                            again = true;
                            return;
                        }
                        sFollowMiss = 0;
                        if (delta == sLastFollowY) {
                            sFollowStill++;
                        } else {
                            sFollowStill = 0;
                            sLastFollowY = delta;
                            // v38：**只在位移真的变了**才刷新「页面最后运动的时刻」。
                            // 老代码无条件刷新 —— 于是帧循环那 12 帧「静止确认」期间时间戳
                            // 还在往前走，sLastPageMotionMs 会比「页面真正停下的时刻」晚 ≈200ms。
                            // 而它现在是「离开播放页」确认窗的起算点（见 deadFor），
                            // 晚 200ms = 白白多给 200ms 宽限。改成条件刷新后它精确等于最后一次移动。
                            sLastPageMotionMs = SystemClock.uptimeMillis();
                        }
                        applyFollowOffset(clampFollowOffset(delta));
                        // v39：**只有页面回到静止位附近，才允许用「静止帧数」注销循环**。
                        //
                        // 老代码无条件 `if (sFollowStill >= FOLLOW_STILL_FRAMES) return;` ——
                        // 于是「页面被拖到极限、位移不再变化」的那 ~200ms 就会把循环注销掉，
                        // 而重启**只能靠宿主结构事件**（maybeStartPageFollow 唯一的调用点），
                        // RN 的回弹却不保证产生结构事件 → 按钮在原地冻住，等下一次事件
                        // 唤醒时**一帧补齐几百 px**。
                        //
                        // 实测（2026-09-14 23:55:10~23:56:33，一次连续拖动）：
                        //   86 次「跟手重启」**全部**先经历 ≥100ms 停顿（p50 587ms、
                        //   p90 1.7s），补跳 p50 122px、最大 2769px。典型形态是
                        //   「按钮停在 2765px 不动 554ms → 一帧跳到 2428px」。
                        //   循环停了、页面还在动 —— 这一段就是用户说的「位移闪跳」。
                        //
                        // 页面被拖开时循环继续跑的成本可忽略（每帧 ~15 个节点的
                        // getTranslationY/getScrollY，没有布局、没有日志），
                        // 换掉的是几百毫秒的冻结 + 几百像素的补跳。
                        if (!held) {
                            // 回到静止位：把最后那点亚死区残留抹平（v38 实测残留 20~107px），
                            // 否则 `captureFollowBaseline()` 的「位移为 0」那道门永远进不去。
                            if (delta != 0) {
                                applyFollowOffset(0);
                            }
                            if (sFollowStill >= FOLLOW_STILL_FRAMES) {
                                return;
                            }
                        }
                        again = true;
                    } catch (Throwable ignored) {
                        // 跟帧循环绝不能把异常抛回宿主
                    } finally {
                        if (again) {
                            sFollowing = true;
                            try {
                                Choreographer.getInstance().postFrameCallback(this);
                            } catch (Throwable e) {
                                sFollowing = false;
                            }
                        }
                    }
                }
            };

    /**
     * 位移兜底（v37：**只挡垃圾值，不再限制跟随范围**）。
     *
     * 这条路走了三个版本，教训很清楚：
     *   · v35「按屏高比例钳位移量」（上 35% / 下 10%）→ 往下只有 277px 空间，
     *     而页面能被拖走 2000+px → **按钮跟到 277px 就撞死**；
     *   · v36「按屏幕位置钳」→ 换成屏幕物理边界，但按钮静止位本来就在屏幕下方，
     *     往下仍然只剩 262px 可用 —— 录屏逐帧实测：按钮下移 121 video px
     *     （= 262 屏幕 px）后**每次都停在同一个位置**，用户看到的还是
     *     「卡在屏幕边缘不跟随页面继续向下滑动」。
     *
     * 根因不是「钳得不够松」，而是**「不能让按钮离开屏幕」这个前提本身就是错的**：
     * 用户要的是「按钮跟着页面走」，所以页面移出屏幕时按钮就该跟着移出屏幕
     * （页面回来按钮自然回来）。v37 直接放行 1:1 跟随，只留 ±1.6 屏高兜底，
     * 防的是坐标读错 / 通道打架这类**异常值**——正常拖动永远碰不到它。
     */
    private static int clampFollowOffset(int delta) {
        int h;
        try {
            h = sButton.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable e) {
            return delta;
        }
        if (h <= 0) {
            return delta;
        }
        int lim = (int) (h * FOLLOW_SANITY_RATIO);
        if (delta > lim) {
            return lim;
        }
        if (delta < -lim) {
            return -lim;
        }
        return delta;
    }

    /** 把位移写到按钮上。用 {@code setTranslationY} —— **不 requestLayout**，因此不可能形成 v31 那种布局回环。 */
    private static void applyFollowOffset(int dy) {
        if (sButton == null || dy == sFollowAppliedY) {
            return;
        }
        sFollowAppliedY = dy;
        sSelfTranslate = true; // 挡住我们自己的 setTranslationY 触发的结构事件
        try {
            sButton.setTranslationY(dy);
        } catch (Throwable ignored) {
        } finally {
            sSelfTranslate = false;
        }
        boolean first = !sFollowLogged;
        if (dy != 0 && (first || sFollowLoggedY == FOLLOW_NO_BASELINE
                || Math.abs(dy - sFollowLoggedY) >= FOLLOW_LOG_STEP_PX)) {
            // v39：新一轮循环的第一行日志带上「断层量」—— 距上一行多久、补跳多少 px。
            // 这两个数就是「位移闪跳」的直接度量：修好后应该恒为 ≈0ms / ≈0px。
            long nowMs = SystemClock.uptimeMillis();
            String gap = "";
            if (first && sFollowLoggedMs != 0L && sFollowLoggedY != FOLLOW_NO_BASELINE) {
                long dtMs = nowMs - sFollowLoggedMs;
                if (dtMs >= 100L) {
                    gap = " catch-up " + (dy - sFollowLoggedY) + "px after " + dtMs + "ms";
                }
            }
            sFollowLogged = true;
            sFollowLoggedY = dy;
            sFollowLoggedMs = nowMs;
            XposedBridge.log(TAG + " page follow" + (first ? " on" : "") + ": button translationY="
                    + dy + "px (ref=" + (sFollowRefIsPlayBtn ? "play-button" : "anchor")
                    + " vis=" + sVisualOffset + " base=" + sFollowBaseY
                    + " ch=" + (sVisualSeen ? "transform" : "baseline") + ")");
            if (gap.length() > 0) {
                XposedBridge.log(TAG + " page follow resume:" + gap);
            }
        }
    }

    /**
     * 宿主视图树发生**结构变更**时调用的即时触发口（v34，调用方见
     * {@code hook/StructureWatcher.java}）。
     *
     * 语义：**「页面结构变了」≠「结论要变」** —— 这里一个判定都不做，
     * 只是立刻再跑一次 {@link #detectAndLayout}，让判定逻辑自己去核实。
     * 这样决策仍然只有一处（避免两套状态机各说各话），但采样节奏从
     * 「等 600ms 心跳」变成「宿主一动就采样」。
     *
     * 三道闸保证成本可控，同时**不牺牲用户真正关心的那次切换**：
     *   ① 没有 Activity（模块未激活 / 已切后台）→ 直接返回，连时间都不取；
     *   ② 距上次触发不足 {@link #POKE_MIN_INTERVAL_MS} → 合并掉
     *      （转场时事件成千上万，必须合并）；
     *   ③ 只有在「上一事件距今 < {@link #POKE_QUIET_BYPASS_MS}」时（即确属持续动画）
     *      才走 {@link #POKE_MAX_PER_SEC} 令牌桶；安静期后的第一个事件**永远放行**。
     */
    public static void pokeStructureChanged(String reason) {
        if (sActivity == null) {
            return;
        }
        if (sSelfTranslate) {
            // v35：这次事件是**我们自己**挪按钮（setTranslationY）产生的，不是宿主在动页面。
            // 不拦住就会「自己触发自己」，跟帧循环空转。
            return;
        }
        long now = SystemClock.uptimeMillis();
        long gap = sLastPokeEventMs == 0L ? Long.MAX_VALUE : (now - sLastPokeEventMs);
        // 「爆发」= 相邻两次结构事件间隔都在 POKE_BURST_GAP_MS 内的一串事件。
        // 用**事件时间**而非爆发起点来判断，保证一次长转场只有一个起点
        // （这样 latencySuffix() 报出来的才是真实端到端延迟）。
        if (sPokeBurstStartMs == 0L || gap > POKE_BURST_GAP_MS) {
            sPokeBurstStartMs = now;
        }
        boolean quietBypass = gap > POKE_QUIET_BYPASS_MS; // 用户点击引发的新一轮切换 → 放行
        sLastPokeEventMs = now;
        if (now - sLastPokeMs < POKE_MIN_INTERVAL_MS) {
            return; // 与 50ms 内的那次合并
        }
        // v35：页面是不是在动？「跟手」的启动信号。
        // 放在合并闸**之后**：一次持续动画每秒能产生上百个事件，逐事件读坐标没必要 ——
        // 50ms 归一后上限 20 次/秒，而每次只是一次 getLocationOnScreen，成本可忽略。
        maybeStartPageFollow();
        // 令牌桶：只约束「紧挨着的连续事件」（= 持续动画），硬性封顶 CPU。
        // 见 POKE_MAX_PER_SEC / POKE_QUIET_BYPASS_MS 的说明。
        if (!quietBypass) {
            if (now - sPokeWindowStartMs >= 1000L) {
                sPokeWindowStartMs = now;
                sPokeWindowCount = 0;
            }
            if (sPokeWindowCount >= POKE_MAX_PER_SEC) {
                return;
            }
            sPokeWindowCount++;
        }
        sLastPokeMs = now;
        sPokeCount++;
        sForceNextDetect = true;             // 绕过节流：结构事件就是我们最想立刻看的时刻
        uiHandler.removeCallbacks(detectRunnable);
        uiHandler.post(detectRunnable);
        if (sPokeLogged < MAX_POKE_LOGS) {
            sPokeLogged++;
            XposedBridge.log(TAG + " structure event -> instant scan [" + reason
                    + "] #" + sPokeCount);
        }
    }

    /**
     * 给播放页锚点挂「挂载/摘除」监听（v34）。
     *
     * 锚点被**从窗口摘除**是「已离开播放页」的硬证据（视图都没了），
     * 可以在 {@link #detectAndLayout} 的 UNKNOWN 分支里跳过 600ms 确认窗直接隐藏。
     */
    private static void watchAnchorAttachment(View anchor) {
        if (anchor == null || anchor == sAnchorWatched) {
            return;
        }
        sAnchorWatched = anchor;
        sAnchorDetached = false;
        try {
            anchor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    sAnchorDetached = false;
                    pokeStructureChanged("anchor attached");
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    sAnchorDetached = true;
                    pokeStructureChanged("anchor detached");
                }
            });
            XposedBridge.log(TAG + " anchor attach-state watched");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " watchAnchorAttachment failed: " + e.getMessage());
        }
    }

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedBridge.log(TAG + " activity onResume -> ensureButton");
                    ensureButton(activity, repo);
                }
            });

            XposedHelpers.findAndHookMethod(activityClass, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedBridge.log(TAG + " activity onPause -> removeButton");
                    removeButton(activity);
                }
            });

            repo.addObserver(() -> updateButtonText(repo));
            XposedBridge.log(TAG + " hooked Activity lifecycle");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook failed: " + e.getMessage());
        }
    }

    private static void ensureButton(Activity activity, SubtitleRepository repo) {
        uiHandler.post(() -> {
            try {
                sActivity = activity;
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                sButton = decor.findViewById(BUTTON_ID);
                boolean freshButton = false;
                if (sButton == null) {
                    sButton = createButton(activity, repo);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            dip2px(activity, BUTTON_W_DP),
                            dip2px(activity, BUTTON_H_DP));
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.leftMargin = dip2px(activity, 16);
                    lp.topMargin = dip2px(activity, 200);
                    sButton.setLayoutParams(lp);
                    // 用上次结论初始化，而不是一律 GONE。
                    // 否则每次 onResume 重建按钮都要从"隐形"开始，干等下一次扫描才出现。
                    sButton.setVisibility(Boolean.TRUE.equals(sLastDecision) ? View.VISIBLE : View.GONE);
                    decor.addView(sButton);
                    freshButton = true;
                    sLastBtnText = null; // 新按钮创建后，强制 updateButtonText 重新 setText
                    sNotPlayerStreak = 0;
                    sNotPlayerSinceMs = 0L;
                    sLastEvidenceSig = null;  // 允许下一次扫描重新打一行证据日志
                    sLastAnchorAlive = null;  // 锚点状态未知，允许重新打一行
                    resetAnchorTracking();
                    sLastProbeAlive = null;
                    // v35：新按钮一律从「零位移 + 无基线」开始（旧按钮的位移不该继承）。
                    // v37：基线/偏置/参考点整体作废 —— 新按钮是个全新的参考系。
                    invalidateFollow();
                }

                // 刚从后台/别的页面回来 —— 视图树还在铺，先快速跟踪一阵再退回常规心跳。
                sLastVerdict = -1;
                sStableStreak = 0;
                sNextHeartbeatMs = HEARTBEAT_FAST_MS;

                // 全局只挂一个 OnGlobalLayoutListener，避免切页导致监听器堆积。
                if (sLayoutListener == null) {
                    sLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (sActivity != null) {
                                detectAndLayout(sActivity, repo);
                            }
                        }
                    };
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(sLayoutListener);
                }

                updateButtonText(repo);
                scheduleDetect();
                armHeartbeat();
                armAnchorWatch();
                if (freshButton) {
                    XposedBridge.log(TAG + " button created, init visibility="
                            + sButton.getVisibility());
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " ensureButton error: " + e.getMessage());
            }
        });
    }

    private static void removeButton(Activity activity) {
        uiHandler.post(() -> {
            try {
                sActivity = null;
                uiHandler.removeCallbacks(detectRunnable);
                uiHandler.removeCallbacks(heartbeatRunnable);
                uiHandler.removeCallbacks(anchorWatchRunnable);
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                if (sLayoutListener != null) {
                    decor.getViewTreeObserver().removeOnGlobalLayoutListener(sLayoutListener);
                    sLayoutListener = null;
                }
                View btn = decor.findViewById(BUTTON_ID);
                if (btn != null) {
                    decor.removeView(btn);
                }
                sButton = null;
                // v35：跟帧循环必须停 —— 按钮都没了，再跟就是纯浪费（而且 sButton==null
                // 时帧回调里的守卫虽会拦住，但循环会一直挂着不退）。
                // v37：按钮重建 = 旧的位移参考系整体失效 → 用 invalidateFollow() 彻底作废。
                invalidateFollow();
                // 注意：sLastDecision **刻意不重置** —— 回到前台时用它初始化按钮可见性，
                // 避免「切后台回前台按钮文字/可见性丢失」。已确认页面类型后由检测覆盖。
                sNotPlayerStreak = 0;
                sNotPlayerSinceMs = 0L;
                sLastVerdict = -1;
                sStableStreak = 0;
                resetAnchorTracking();
                sLastProbeAlive = null;
                // v34：切后台后锚点未必还在，attach 监听与结构事件状态一并清掉，
                // 否则回前台时会拿「上一次会话的已摘除」当硬证据误隐藏。
                sAnchorDetached = false;
                sAnchorWatched = null;
                sPokeBurstStartMs = 0L;
                sLastPokeEventMs = 0L;
                sLastPokeMs = 0L;
                sPokeWindowStartMs = 0L;
                sPokeWindowCount = 0;
                // 切后台后按钮被销毁，但 sLastBtnText 是静态变量，必须重置。
                // 否则 onResume 重建按钮时 updateButtonText 会认为文字没变而跳过 setText，
                // 导致回到前台按钮只剩背景、没有文字。
                sLastBtnText = null;
            } catch (Throwable e) {
                XposedBridge.log(TAG + " removeButton error: " + e.getMessage());
            }
        });
    }

    /** 重建按钮后打一组补检，尽快把新页面定型（受 {@link #DETECT_MIN_INTERVAL_MS} 约束）。 */
    private static void scheduleDetect() {
        uiHandler.removeCallbacks(detectRunnable);
        uiHandler.postDelayed(detectRunnable, 80);
        uiHandler.postDelayed(detectRunnable, 240);
        uiHandler.postDelayed(detectRunnable, 520);
    }

    /** 按当前节奏重排下一次心跳（心跳链的唯一入口，任何路径都不会漏排）。 */
    private static void armHeartbeat() {
        uiHandler.removeCallbacks(heartbeatRunnable);
        uiHandler.postDelayed(heartbeatRunnable, Math.max(80L, sNextHeartbeatMs));
    }

    private static void detectAndLayout(Activity activity, SubtitleRepository repo) {
        if (sButton == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        // 节流：全局布局回调非常频繁，限制检测频率，避免主线程被拖死。
        // 这里刻意**不重排心跳**：心跳由 heartbeatRunnable 独立续期，
        // 被节流提前 return 也不会让检测链断掉。
        // 例外：切页动画期间的快速复检需要绕过节流（否则会被丢掉）。
        long now = SystemClock.uptimeMillis();
        if (!sForceNextDetect && now - sLastDetectMs < DETECT_MIN_INTERVAL_MS) {
            return;
        }
        sForceNextDetect = false;
        sLastDetectMs = now;

        int verdict = SubtitleViewHook.PAGE_UNKNOWN;
        boolean ambiguous = false;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }

            // 一次性新鲜扫描：收集「屏上命中字幕库的文本」+ 播放页/列表页的结构证据 + 播放页锚点。
            // 不读取任何历史标记，不可见子树直接跳过。
            SubtitleViewHook.ScanResult scan = SubtitleViewHook.scan(decor, repo);
            repo.setCurrentSubtitles(scan.liveLines);

            int screenW = decor.getWidth() > 0
                    ? decor.getWidth()
                    : decor.getResources().getDisplayMetrics().widthPixels;
            int screenH = decor.getHeight() > 0
                    ? decor.getHeight()
                    : decor.getResources().getDisplayMetrics().heightPixels;
            sLastScreenW = screenW;
            sLastScreenH = screenH;

            // v38：扫描心跳也要刷新「页面最后运动的时刻」。
            // 它是「离开播放页」确认窗的起算点（见下面 deadFor），刷新源必须足够可靠；
            // 而结构事件在 RN 里**并不保证触发**（translate 驱动的拖动不触发布局回调），
            // 只有这条 55ms 一次的心跳一定在跑。
            notePageMotion();
            // v39：跟帧循环的**另一个**启动口。
            // 以前只有宿主结构事件能拉起循环（见 pokeStructureChanged），而 RN 的
            // 回弹 / 慢速拖动不保证产生结构事件 → 循环在半路掉了就冻在那儿，
            // 直到几百毫秒后被某个偶然事件唤醒，一帧补齐几百 px（=「位移闪跳」）。
            // 这条 55ms 一趟的心跳一定在跑，是最可靠的兜底。
            maybeStartPageFollow();

            verdict = scan.pageVerdict();
            // 两条宽滑条同时在 → RN 正在做页面切换（旧页还没卸载）。这不是"未知页面"，
            // 而是"过渡态"，应当尽快复检定型，别让按钮在过渡期做错决定。
            ambiguous = scan.hasMainSlider && scan.hasBottomSlider;

            switch (verdict) {
                case SubtitleViewHook.PAGE_PLAYER: {
                    sNotPlayerStreak = 0;
                    sNotPlayerSinceMs = 0L;
                    resetAnchorTracking();
                    sAnchorAliveSinceMs = now;
                    sLastProbeAlive = Boolean.TRUE;
                    sLastPlayerSeenMs = now;
                    sLastAnchorAlive = Boolean.TRUE;
                    if (!sTreeDumped) {
                        sTreeDumped = true;
                        try {
                            // v33 诊断：定位「播放器传输控件行」用的真实视图树，只打一次。
                            SubtitleViewHook.dumpViewTree(decor);
                        } catch (Throwable ignored) {
                        }
                    }
                    // 更新播放页锚点（页面级容器），供主滑条被回收时继续判定「还在播放页」。
                    if (scan.anchorRef != null) {
                        sPlayerAnchor = scan.anchorRef;
                        sAnchorDetached = false; // 锚点在播放页里被重新确认 → 清掉「已摘除」的硬证据
                        watchAnchorAttachment(scan.anchorRef.get());
                        if (!scan.anchorDesc.equals(sLastAnchorDesc)) {
                            sLastAnchorDesc = scan.anchorDesc;
                            XposedBridge.log(TAG + " player anchor -> " + scan.anchorDesc);
                        }
                    }
                    // v35：确认在播放页 → 顺手把「播放键」认下来，并在页面静止时采集跟随基线。
                    // 只在页面静止（跟帧循环没跑 + 位移为 0）时才采 —— 见 captureFollowBaseline()。
                    captureFollowBaseline();
                    sLastDecision = Boolean.TRUE;
                    showButton(activity, repo, screenW, screenH);
                    break;
                }

                case SubtitleViewHook.PAGE_OTHER: {
                    // v35 先问一句：**页面是不是只是被拖开了？**
                    // 拖动播放页时下层列表页会从缝里露出来，它那条底部 mini-player 滑条会被当成
                    // 「已离开播放页」的正面证据 —— 实测 2.4s 的上下拖动里出现过**连续 6 次 OTHER**
                    // （275ms），直接越过 HIDE_ON_OTHER_PROOF_MS=250ms 的判隐藏线，按钮当场消失
                    // （日志 22:22:45.766~46.039 → `button hidden (bottom mini-player #6)`）。
                    // v36 去掉对 isPlayerAnchorAlive() 的依赖；v37 换成完全**不需要基线**的
                    // isPageHeld()（v36 那道门要用基线算位移，没基线时直接放行 = 等于没拦，
                    // 日志里 15 处 "no settled baseline yet" 期间它全程失效）。
                    if (isPageHeld(now)) {
                        sNotPlayerStreak = 0;
                        sNotPlayerSinceMs = 0L;
                        sLastPlayerSeenMs = now;
                        break; // 维持原状：这是「拖着页面」而不是「换页」
                    }
                    resetAnchorTracking();
                    sAnchorAliveSinceMs = 0L;
                    // 记下这条**正面证据**的时刻（见 OTHER_EVIDENCE_TTL_MS）：
                    // 转场里滑条会 OTHER → UNKNOWN 交替，所以不能要求「连续两次 OTHER」。
                    sLastOtherSeenMs = now;
                    sNotPlayerStreak++;
                    if (sNotPlayerSinceMs == 0L) {
                        sNotPlayerSinceMs = now;
                    }
                    if (sNotPlayerStreak < HIDE_ON_OTHER_STREAK
                            || now - sNotPlayerSinceMs < HIDE_ON_OTHER_PROOF_MS) {
                        // v26 去抖：必须「连续 2 次采样」且「证据持续 ≥250ms」才隐藏。
                        // 页面过渡动画期间播放页自己的主滑条会被误判成 mini-player 滑条，
                        // 这一层确认窗就是专门吃这种短促假阳性的。
                        break;
                    }
                    sLastDecision = Boolean.FALSE;
                    sPlayerAnchor = null;
                    sLastPlayerSeenMs = 0L;
                    sLastAnchorAlive = Boolean.FALSE;
                    hideButton("bottom mini-player #" + sNotPlayerStreak);
                    break;
                }

                default: { // PAGE_UNKNOWN：没有任何**能确认**的滑条证据
                    sNotPlayerSinceMs = 0L;
                    // v26 兜底：上半屏主滑条在、页面容器几乎占满屏幕，只是**时间文本没配上**
                    // （换机型 / 换布局 / 文本被虚拟化回收）。此时仍把这个容器收作锚点，
                    // 让下面的锚点通道把按钮撑住 —— 配对判据万一失效，也不至于让功能整个失灵。
                    if (scan.hasMainSlider && !scan.hasBottomSlider && scan.anchorRef != null
                            && sPlayerAnchor == null) {
                        sPlayerAnchor = scan.anchorRef;
                        sAnchorDetached = false;
                        watchAnchorAttachment(scan.anchorRef.get());
                        XposedBridge.log(TAG + " player anchor adopted (unpaired main slider) -> "
                                + scan.anchorDesc);
                    }
                    if (sAnchorDetached) {
                        // v34 硬证据：锚点已**从窗口摘除**（视图都没了）→ 一定不在播放页。
                        // 这是「离开播放页」最干净的一条信号，不必再等 ANCHOR_DEAD_MIN_MS 的确认窗
                        // —— 确认窗本来就是为了防「页面假死又滑回来」，而视图被摘掉不会滑回来。
                        if (Boolean.TRUE.equals(sLastDecision)) {
                            XposedBridge.log(TAG + " anchor detached from window -> hide (hard evidence)");
                        }
                        sLastDecision = Boolean.FALSE;
                        hideButton("anchor detached");
                        break;
                    }
                    boolean anchorAlive = isPlayerAnchorAlive();
                    if (sLastAnchorAlive == null || sLastAnchorAlive != anchorAlive) {
                        sLastAnchorAlive = anchorAlive;
                        XposedBridge.log(TAG + " player anchor alive=" + anchorAlive
                                + " (area=" + Math.round(anchorAreaRatio(screenW, screenH) * 100) + "%)");
                    }
                    if (anchorAlive) {
                        // 页面级容器还在屏幕上 → 仍在播放页（主滑条只是被 RN 回收 / 控制条隐藏）。
                        // v25：**立即恢复显示**，不再傻等下一次扫到主滑条（那要 ~0.6s）。
                        sLastPlayerSeenMs = now;
                        resetAnchorTracking();
                        // v30：ANCHOR_HARD_TIMEOUT 的计时起点必须是「锚点**连续**存活的时刻」。
                        // 旧版用 sUnknownSinceMs（= 进入 UNKNOWN 的时刻，早得多）：长时间 UNKNOWN 之后
                        // 锚点刚复活就被这个超时判掉，**反而错过了「立即恢复显示」** —— 实测白等 606ms
                        // （10:01:12.886 锚点已 alive=true(area=58%)，按钮却等到 13.492 才亮）。
                        if (sAnchorAliveSinceMs == 0L) {
                            sAnchorAliveSinceMs = now;
                        }
                        if (now - sAnchorAliveSinceMs >= ANCHOR_HARD_TIMEOUT_MS) {
                            // 兜底：锚点**连续**存活却一直 UNKNOWN（锚点可能选得过高 / 是常驻容器）。保守隐藏。
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " UNKNOWN + anchor alive for "
                                        + (now - sAnchorAliveSinceMs) + "ms -> hide (fallback)");
                            }
                            sLastDecision = Boolean.FALSE;
                            hideButton("unknown + anchor alive timeout");
                        } else if (!Boolean.TRUE.equals(sLastDecision)) {
                            sLastDecision = Boolean.TRUE;
                            showButton(activity, repo, screenW, screenH);
                        }
                    } else if (sPlayerAnchor == null) {
                        sAnchorAliveSinceMs = 0L;
                        // v37：同上 —— 手指还在拖页面就不隐藏（否则位移被清零 = 按钮卡住）。
                        if (Boolean.TRUE.equals(sLastDecision) && isPageHeld(now)) {
                            break;
                        }
                        // 从未取到过锚点（findPlayerAnchor 返回 null 的页面结构）：只能靠时间宽限兜底。
                        if (sLastPlayerSeenMs == 0L || now - sLastPlayerSeenMs >= NO_ANCHOR_GRACE_MS) {
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " no player evidence for "
                                        + (sLastPlayerSeenMs == 0L ? -1 : (now - sLastPlayerSeenMs))
                                        + "ms (anchor=never) -> hide");
                            }
                            sLastDecision = Boolean.FALSE;
                            hideButton("player page left (no anchor)");
                        }
                    } else {
                        sAnchorAliveSinceMs = 0L;
                        // 锚点失效：可能"真的离开了"，也可能"切页过渡期假死"
                        // （交叉表实测播放页上假死可达 1.326s，见 ANCHOR_DEAD_MIN_MS 的注释）。
                        //
                        // v30：确认窗由「4 次采样」改成「墙钟 {@link #ANCHOR_DEAD_MIN_MS}」+
                        // 最少 {@link #ANCHOR_DEAD_MIN_SAMPLES} 次采样 —— 采样相位不再影响结论。
                        // 若近期见过 mini-player 滑条这条**正面证据**，确认窗缩到
                        // {@link #ANCHOR_DEAD_WITH_EVIDENCE_MS}，且从**最早的离开信号**起算
                        // （正面证据优先于负面推断）。
                        if (sAnchorDeadSinceMs == 0L) {
                            sAnchorDeadSinceMs = now;
                            sAnchorDeadSamples = 0;
                            sAnchorDeadLogged = false;
                            sHoldSuppressLogged = false;
                        }
                        sAnchorDeadSamples++;
                        boolean evidence = isFreshOtherEvidence(now);
                        long need = evidence ? ANCHOR_DEAD_WITH_EVIDENCE_MS : ANCHOR_DEAD_MIN_MS;
                        long since = sAnchorDeadSinceMs;
                        if (evidence && sLastOtherSeenMs != 0L && sLastOtherSeenMs < since) {
                            since = sLastOtherSeenMs;
                        }
                        // v38：确认窗从「页面**最后一次运动**之后」才开始走。
                        //
                        // 为什么：拖动 / fling 会把页面拖到**看不见播放控件**的地方，此时
                        // 「扫不到播放页证据」是这一刻的**正常现象**，根本不是「离开播放页」。
                        // 而 sAnchorDeadSinceMs 从「锚点第一次判死」起算 —— 那正好是拖动**刚开始**，
                        // 于是确认窗趁手指还在拖就一路走完 → 按钮 hide，页面一回弹又 shown。
                        // 实测（2026-09-14 23:16:26~27）一次 fling：26.905 锚点判死 → 27.014 页面到位
                        // → 27.561 deadFor 已达 656ms，越过 ANCHOR_DEAD_MIN_MS=600ms → hide
                        // → 27.892 页面回弹、锚点回来 → shown。用户看到的就是「闪消失 + 闪现」。
                        //
                        // 改成从 sLastPageMotionMs 起算后，同一个场景 deadFor 只有 547ms < 600ms，
                        // 不隐藏；页面回弹后 27.891 重新扫到锚点 → 全程按钮没动过。
                        // 真·离开播放页时页面也会停（离场动画 ≤300ms），确认窗随即正常推进，
                        // 最坏情况只比原来晚一个离场动画的时长，不会「赖着不走」。
                        if (sLastPageMotionMs != 0L && sLastPageMotionMs > since) {
                            since = sLastPageMotionMs;
                        }
                        long deadFor = now - since;
                        if (!sAnchorDeadLogged) {
                            sAnchorDeadLogged = true;
                            // 诊断：把**判据原始量**打出来（面积 / 有无正面证据 / 需要的确认窗 /
                            // 页面已静止多久），下一轮才能直接用真实数据校准 ANCHOR_DEAD_MIN_MS。
                            XposedBridge.log(TAG + " anchor dead for " + deadFor + "ms"
                                    + " (area=" + Math.round(anchorAreaRatio(screenW, screenH) * 100) + "%"
                                    + " evidence=" + evidence + " need=" + need + "ms"
                                    + " samples=" + sAnchorDeadSamples
                                    + " still=" + (sLastPageMotionMs == 0L ? -1
                                            : (now - sLastPageMotionMs)) + "ms)");
                        }
                        // v37：**手指还在拖页面 → 绝不隐藏**。
                        // 拖动期间锚点面积掉到 30~45%、verdict 在 PLAYER/OTHER 之间跳，
                        // 「无播放页证据」会一路累计到判隐藏 —— 实测一次拖动里按钮消失约 700ms
                        // 再回来，而 hide 会把跟随位移清零 → 用户看到的是「卡在原位置不动」。
                        // 页面真被划走时锚点面积趋近 0，这条门自动放行。
                        if (Boolean.TRUE.equals(sLastDecision) && isPageHeld(now)) {
                            if (!sHoldSuppressLogged) {
                                sHoldSuppressLogged = true;
                                XposedBridge.log(TAG + " hide suppressed: page held (vis="
                                        + sVisualOffset + "px ref="
                                        + (sFollowRefIsPlayBtn ? "play-button" : "anchor")
                                        + " snap=" + sHeldOffset + "px/"
                                        + (sHeldOffsetMs == 0L ? -1 : (now - sHeldOffsetMs))
                                        + "ms attached=" + holdProbeStillAttached() + ")");
                            }
                            break;
                        }
                        if (deadFor >= need && sAnchorDeadSamples >= ANCHOR_DEAD_MIN_SAMPLES) {
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " no player evidence for "
                                        + (sLastPlayerSeenMs == 0L ? -1 : (now - sLastPlayerSeenMs))
                                        + "ms (anchor dead " + deadFor + "ms evidence=" + evidence
                                        // v38：门失守时**快照的值**必须看得见，否则下一轮
                                        // 只能靠猜「到底是判据②失效还是③超期 / detach」。
                                        + " | snap=" + sHeldOffset + "px/"
                                        + (sHeldOffsetMs == 0L ? -1 : (now - sHeldOffsetMs)) + "ms"
                                        + " attached=" + holdProbeStillAttached()
                                        + " vis=" + sVisualOffset + "px) -> hide");
                            }
                            sLastDecision = Boolean.FALSE;
                            hideButton("player page left");
                        }
                    }
                    break;
                }
            }

            // 只在**证据指纹**变化时打日志（此前只在"结论变化"时打，
            // 导致结论被误判撑住时全程静默，排查时看不到任何线索）。
            String sig = scan.evidenceSignature();
            if (!sig.equals(sLastEvidenceSig)) {
                sLastEvidenceSig = sig;
                XposedBridge.log(TAG + " verdict=" + SubtitleViewHook.verdictName(verdict)
                        + " | " + scan.describe(screenW, screenH));
            }

            repo.setPlayerPageVisible(Boolean.TRUE.equals(sLastDecision));
        } catch (Throwable e) {
            XposedBridge.log(TAG + " detectAndLayout error: " + e.getMessage());
        } finally {
            // 只更新下一次心跳的节奏；真正重排在 heartbeatRunnable 的 finally 里，
            // 保证「无论本次是否被节流、是否抛异常」心跳都不会断。
            sNextHeartbeatMs = decideHeartbeat(verdict, ambiguous);
        }
    }

    /**
     * 决定下一次心跳的间隔（v24 响应提速的核心）。
     *
     * 原则：**只在「需要尽快收敛」的时候快，其余时间省电。**
     *   1) 过渡态（主滑条 + mini-player 滑条同时在）→ 快速复检；
     *   2) 结论与按钮**现状不一致**（例：还显示着按钮但判定已不确定）→ 快速跟踪；
     *   3) 结论刚刚变化 → 快速跟踪一段（{@link #SETTLE_STREAK} 次）后转常规。
     */
    private static long decideHeartbeat(int verdict, boolean ambiguous) {
        if (ambiguous && sAmbiguousRechecks < MAX_AMBIGUOUS_RECHECKS) {
            sAmbiguousRechecks++;
            sForceNextDetect = true; // 过渡态复检要绕过节流，否则会被丢掉
            return AMBIGUOUS_RECHECK_MS;
        }

        boolean buttonVisible = sButton != null && sButton.getVisibility() == View.VISIBLE;
        if ((verdict == SubtitleViewHook.PAGE_PLAYER) != buttonVisible) {
            return HEARTBEAT_FAST_MS; // 结论与现状不符 → 尽快收敛（离开播放页后的隐藏就走这条）
        }

        if (verdict != sLastVerdict) {
            sLastVerdict = verdict;
            sStableStreak = 0;
        } else {
            sStableStreak++;
        }
        return sStableStreak < SETTLE_STREAK ? HEARTBEAT_FAST_MS : HEARTBEAT_MS;
    }

    /**
     * 播放页锚点是否仍然「真实可见」——即页面容器还在屏幕上。
     *
     * 这是判断「有没有离开播放页」的关键：RN 切页时旧页会被隐藏 / 卸载，
     * 锚点随即失效；而停留在播放页时，即使主滑条被回收，页面容器也依然可见。
     */
    private static boolean isPlayerAnchorAlive() {
        WeakReference<View> ref = sPlayerAnchor;
        if (ref == null) {
            return false;
        }
        return SubtitleViewHook.isEffectivelyVisible(ref.get());
    }

    /**
     * 清掉「锚点失效」的计时状态（锚点被确认存活时调用）。
     * 只在真的有待清的状态时才动手，避免每帧写静态字段。
     */
    private static void resetAnchorTracking() {
        if (sAnchorDeadSinceMs != 0L || sAnchorDeadSamples != 0 || sAnchorDeadLogged) {
            sAnchorDeadSinceMs = 0L;
            sAnchorDeadSamples = 0;
            sAnchorDeadLogged = false;
        }
    }

    /**
     * 近期是否见过「底部 mini-player 滑条」这条**正面证据**（见 {@link #OTHER_EVIDENCE_TTL_MS}）。
     *
     * 只在按钮**当前可见**时才算数 —— 交叉表（work_diag_16/other_vs_button.py）显示两次已知的
     * OTHER 假阳性（播放页入场动画把它自己的主滑条误判成 mini-player 滑条）都发生在按钮已隐藏时，
     * 而 7 次「按钮可见 + OTHER」全部是**真的离开播放页**。
     */
    private static boolean isFreshOtherEvidence(long now) {
        return Boolean.TRUE.equals(sLastDecision)
                && sLastOtherSeenMs != 0L
                && now - sLastOtherSeenMs <= OTHER_EVIDENCE_TTL_MS;
    }

    /** 锚点在屏上的可见面积占比（仅用于日志诊断，判断锚点是否选得过高）。 */
    private static float anchorAreaRatio(int screenW, int screenH) {
        WeakReference<View> ref = sPlayerAnchor;
        View v = ref == null ? null : ref.get();
        return SubtitleViewHook.visibleAreaRatio(v, screenW, screenH);
    }

    private static void showButton(Activity activity, SubtitleRepository repo,
                                   int screenW, int screenH) {
        int btnW = sButton.getWidth() > 0 ? sButton.getWidth() : dip2px(activity, BUTTON_W_DP);
        int btnH = sButton.getHeight() > 0 ? sButton.getHeight() : dip2px(activity, BUTTON_H_DP);

        // ── 位置：常量，右下角（v32 起恢复为唯一口径）──
        // ⚠️ 这里曾在 v31 改成「按主滑条几何实时推导」。已撤销，原因见类头 v32 段：
        //    播放页可滚动 → 主滑条位置本身是变量 → 按钮被拖着满屏跳 + 触发布局回环。
        //    位置一旦是常量，下面那句守卫就只在首次摆位时成立，之后零 setLayoutParams。
        int wantRight = dip2px(activity, BUTTON_RIGHT_DP);
        // 安全钳制：极窄 / 极矮屏（分屏、平板、异常 density）下别把按钮顶出可视区。
        // 正常机型上这两个分支都不会命中，不改变既定位置。
        if (wantRight + btnW > screenW) {
            wantRight = Math.max(0, screenW - btnW - dip2px(activity, 8));
        }
        int wantBottom = dip2px(activity, BUTTON_BOTTOM_DP);
        if (wantBottom + btnH > screenH) {
            wantBottom = Math.max(0, screenH - btnH - dip2px(activity, 8));
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sButton.getLayoutParams();
        int wantGravity = Gravity.BOTTOM | Gravity.END;
        // 只在真的变化时才 setLayoutParams —— setLayoutParams 必然 requestLayout，
        // 在 onGlobalLayout 里无条件调用会形成永不停止的布局回环（卡顿主因）。
        if (lp.gravity != wantGravity
                || lp.leftMargin != 0
                || lp.topMargin != 0
                || lp.rightMargin != wantRight
                || lp.bottomMargin != wantBottom) {
            lp.gravity = wantGravity;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = wantRight;
            lp.bottomMargin = wantBottom;
            sButton.setLayoutParams(lp);
        }

        if (sButton.getVisibility() != View.VISIBLE) {
            // v39：**先把位移同步到「页面当前被拖开的量」，再显示**。
            // hideButton() 会 stopPageFollow(true) → 位移清零。若不同步就显示，
            // 按钮会先在静止位露脸、几十毫秒后再跳到几百 px 外 ——
            // 实测 23:34:07 显示后 62ms 才补上 448px、23:35:33 显示后 62ms 补 345px，
            // 那一瞬间就是用户看到的「位移闪跳」。
            syncFollowOffsetOnShow();
            sButton.setVisibility(View.VISIBLE);
            updateButtonText(repo);
            XposedBridge.log(TAG + " button shown (player page)" + latencySuffix());
            // 循环在 setVisibility 之前会被 maybeStartPageFollow 的可见性检查挡掉，
            // 所以放在后面：按钮一露脸就跟帧，不留空窗。
            maybeStartPageFollow();
        }
    }

    /**
     * 显示按钮前把位移一次性对齐到当前值（v39）。
     *
     * 只写 {@code translationY}（不触发布局），且此时按钮还是 GONE —— 用户看不到
     * 这次写入，只会看到「显示出来的那一刻位置就已经是对的」。
     */
    private static void syncFollowOffsetOnShow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return;
        }
        if (!sVisualSeen || sVisualBias == FOLLOW_NO_BASELINE) {
            return; // 通道还没被证实可用 —— 保持 0，别拿没校准过的读数去挪
        }
        int d = followOffset();
        if (d == FOLLOW_NO_BASELINE) {
            return;
        }
        applyFollowOffset(clampFollowOffset(d));
    }

    /**
     * 「宿主结构事件 → 按钮显隐」的延迟后缀（v34 诊断）。
     *
     * 起点取本轮**结构事件爆发**的第一次触发时刻 —— 那正是「RN 开始动页面」的时刻，
     * 所以这个数字就是用户真正感受到的延迟。配合日志里的
     * {@code structure event -> instant scan [reason]} 可以看出是哪类事件起了作用。
     */
    private static String latencySuffix() {
        if (sPokeBurstStartMs == 0L) {
            return " (no structure event nearby)";
        }
        return " after structure event +" + (SystemClock.uptimeMillis() - sPokeBurstStartMs) + "ms";
    }

    private static void hideButton(String reason) {
        // v35：离开播放页 → 跟随状态必须整体复位（位移清零 + 基线作废），
        // 否则下次回到播放页时会带着上一页的位移量出现。
        stopPageFollow(true);
        if (sButton.getVisibility() != View.GONE) {
            sButton.setVisibility(View.GONE);
            XposedBridge.log(TAG + " button hidden (" + reason + ")" + latencySuffix());
        }
    }

    private static TextView createButton(Context ctx, SubtitleRepository repo) {
        TextView tv = new TextView(ctx);
        tv.setId(BUTTON_ID);
        tv.setTextSize(12);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dip2px(ctx, 8), 0, dip2px(ctx, 8), 0);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setBackground(createButtonDrawable(false));

        tv.setOnClickListener(v -> {
            if (!repo.hasSubtitles()) {
                XposedBridge.log(TAG + " no subtitles available");
                return;
            }
            // 注意：这里不再用 Settings.canDrawOverlays() 拦截。
            // OPPO/ColorOS 上该 API 即使用户已授予悬浮窗权限也返回 false，会导致点击后
            // 每次都跳权限页且无法开启。改为直接 toggle，由 FloatingWindowManager 真实
            // addView；只有真正抛异常（确实没权限）时才提示一次去授权。
            repo.toggleFloatingWindow();
            updateButtonDrawable(repo);
        });
        return tv;
    }

    private static GradientDrawable createButtonDrawable(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(active ? BUTTON_BG_ACTIVE : BUTTON_BG_NORMAL);
        drawable.setCornerRadius(8);
        drawable.setStroke(1, 0x80FFFFFF);
        return drawable;
    }

    private static void updateButtonText(SubtitleRepository repo) {
        if (sButton == null) {
            return;
        }
        final String text;
        final float alpha;
        if (!repo.hasSubtitles()) {
            text = "无字幕";
            alpha = 0.6f;
        } else if (repo.isFloatingWindowOpen()) {
            text = "悬浮开";
            alpha = 1.0f;
        } else {
            text = "悬浮关";
            alpha = 1.0f;
        }
        if (text.equals(sLastBtnText) && sButton.getAlpha() == alpha) {
            return; // 没变化就不折腾（避免每次通知都重建 drawable / 触发重绘）
        }
        sLastBtnText = text;
        uiHandler.post(() -> {
            if (sButton == null) {
                return;
            }
            sButton.setText(text);
            sButton.setAlpha(alpha);
            updateButtonDrawable(repo);
        });
    }

    private static void updateButtonDrawable(SubtitleRepository repo) {
        if (sButton == null) {
            return;
        }
        boolean active = repo.hasSubtitles() && repo.isFloatingWindowOpen();
        sButton.setBackground(createButtonDrawable(active));
    }

    private static int dip2px(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
