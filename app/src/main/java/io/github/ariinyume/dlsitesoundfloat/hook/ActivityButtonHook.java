/*
 * DLsiteSound Floating Subtitle - Xposed module for DLsite Sound
 * Copyright (C) 2026 ariinyume
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ariinyume.dlsitesoundfloat.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.ariinyume.dlsitesoundfloat.BuildConfig;
import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;
import io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

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
 * 屏幕上「主滑条上方靠右」（v57 起，需求指定），尺寸是两个 {@link #CAPSULE_W_DP} dp 的胶囊。
 *
 * ⚠️ 类头这一段描述的是 v32 时期的「常量右下角」，v57 已改。之所以保留历史段落：
 *    里面记录的**反面教训**（位置跟着几何走 → 满屏跳 + 布局回环）依然成立，
 *    v57 只是用「迟滞 + 只写 LayoutParams 不写 translationY 基准」把它化解掉。
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
    /** 状态栏字幕胶囊的 id。 */
    private static final int CAPSULE_ID_STATUSBAR = 0x7F999002;
    /** 悬浮窗字幕胶囊的 id。 */
    private static final int CAPSULE_ID_FLOATING = 0x7F999003;

    /**
     * ── v57（2026-09-20）：字幕开关从「单按钮 + 长按/短按」改成**双胶囊按钮** ──
     *
     * 需求原话（Ari 2026-09-20）：
     *   「以 Material Design 3 作为设计基础，参考图片中的按键设计和按键所在界面中的位置。
     *     将悬浮窗字幕和状态栏字幕拆分成两个按键，均通过点击触发；如无字幕，状态栏字幕按键消失，
     *     悬浮窗字幕按键显示无字幕。按键位置是在音轨大标题和播放进度条中间靠右侧。」
     *
     * 设计稿量出来的规格（参考图是设计稿，实际渲染见 control-*.png）：
     *   · 胶囊 86x38px（设计稿）→ 按需求「再宽些」取 96x36dp；
     *   · 间距 12px（设计稿，≈ 高 x0.32）→ 取 8dp；
     *   · **全圆角**（设计稿圆角 = 高/2 = 19px）→ 这里是胶囊的关键特征，
     *     旧版 setCornerRadius(8) 是圆角矩形，必须改成 h/2 才是 MD3 胶囊。
     *
     * 配色（设计稿给的 HEX，勿改）：
     *   · #212042 —— 关态（深紫黑）
     *   · #584179 —— 开态（紫）
     * ⚠️ 旧版的绿色 #1EB980（状态栏开）已废弃 —— 新设计里两个按钮各自用
     *    #584179 表示「自己开着」，不再用绿色区分，也不再需要「长按照亮」的中间态。
     */
    private static final int CAPSULE_BG_OFF = 0xFF212042;
    private static final int CAPSULE_BG_ON = 0xFF584179;

    /**
     * 胶囊尺寸（dp）。
     *
     * 【code 922 问题 3】96x36 -> **90x35**，两钮间距 8 -> **10**。
     * 尺寸收紧后单钮更贴近 MD3 filled button 的紧凑比例（90/35 = 2.571），
     * 而间距加宽到 10dp 让两个胶囊的「独立感」更明确 —— 96x36 + 8dp 时
     * 两钮几乎连成一条长胶囊，用户反馈分辨不出是两个独立按钮。
     */
    private static final int CAPSULE_W_DP = 75;
    private static final int CAPSULE_H_DP = 32;
    /** 【code 935 bug2】胶囊高度下限（dp）：可用带不足时按钮缩到这个值就不再缩。 */
    private static final int CAPSULE_MIN_H_DP = 20;
    /** 【code 935 bug2】胶囊高度（px）；-1 = 还没算过。【code 936】恒为 32dp。 */
    private static int sCapsuleHPx = -1;
    /** 【code 936 bug2】简介底边逐帧翻转时的迟滞阈值（dp）。 */
    private static final int DESC_HYSTERESIS_DP = 24;
    /** 【code 936 bug2】去抖后采信的简介底边（px）；-1 = 还没采信过。 */
    private static int sDescBottomStable = -1;
    /** 两个胶囊之间的间距（dp）。【code 922 问题 3】8 -> 10。 */
    private static final int CAPSULE_GAP_DP = 10;
    /**
     * 【code 923 几何 2】胶囊圆角（dp）—— **固定 15dp**。
     *
     * 旧写法是 {@code setCornerRadius(999f)}，靠 GradientDrawable 把超出部分自动钳到
     * h/2 来实现「全圆角」。h=35dp 时它等于 17.5dp；改到 h=30dp 后会**悄悄变成 15dp**
     * —— 圆角跟着高度走，是典型的「改一处连带动另一处」。
     * 需求现在明确要 15dp，那就写死，并在 dex 层验得到（见 createCapsuleDrawable）。
     */
    private static final int CAPSULE_RADIUS_DP = 16;
    /** 胶囊文字大小（sp）。 */
    private static final float CAPSULE_TEXT_SP = 13f;

    /**
     * 【1.21.13 问题 2】按钮底色的三种模式。文字 / alpha / 底色三者必须**同一个口径、
     * 一起判等**，否则就会出现「文字已变、底色没变」的错位（1.21.12 就是这样把绿色
     * 底色留在了「无字幕」按钮上）。
     */
    // 【v57】BTN_BG_MODE_* 三常量已删除 —— 新设计只有「开/关」两态
    // （见 CAPSULE_BG_ON / CAPSULE_BG_OFF），不再需要三态模式枚举。
    // 【v57】LONG_PRESS_MS 也已删除 —— 长按交互整体取消（需求：「均通过点击触发」）。

    // 【v57】BUTTON_W_DP / BUTTON_H_DP 已删除 —— 尺寸口径统一到
    // CAPSULE_W_DP / CAPSULE_H_DP / CAPSULE_GAP_DP（见上面那段）。
    // 两套尺寸常量并存必然出现「改一处忘一处」，这是本项目反复踩过的老坑。

    /** 按钮距屏幕右缘的边距（dp）。 */
    private static final int BUTTON_RIGHT_DP = 16;

    /**
     * 【v57】按钮组底边距**主滑条中心**的距离（dp）。
     *
     * 旧版按钮在右下角常量位（距屏底 96dp），那是 v32 的结论 ——
     * 当时理由是「播放页可滚动，主滑条位置是变量，跟着它走会让按钮满屏跳」。
     * 那个结论在**按钮位于底部空白带**时成立；现在需求明确要把它放到
     * 「音轨大标题与进度条之间靠右」，位置**必须**由滑条几何推导。
     *
     * 所以 v57 把「位置是常量」这条规则收紧为：**初始底边是常量，跟随量走 translationY**
     * （位移不触发布局，因此不会复现 v31 那种 setLayoutParams → requestLayout → 布局回环）。
     *
     * 取值：滑条中心往上 32dp 处作为按钮组底边。
     *
     * 【code 922 问题 3】24 -> **32**（整体上移 8dp，需求原话「两个按钮向上移动 8dp」）。
     * 帧标定依据（VID1 真机录屏，588x1280 帧坐标系 -> 真机 1272x2772 / density 3.0）：
     *   · 改前实测「按钮组下沿 y=797、主滑条中心 y≈830.5」→ 间距 33.5px ≈ 24.1dp
     *     —— 与旧常量 24 完全吻合，证明本量测口径可信；
     *   · 需求要再上移 8dp ⇒ 32dp ⇒ 帧内应为 44.5px。
     * 这个位置仍未触及上方简介行（帧内简介行底边在 y≈745 一带），
     * 且离滑条本体的触摸热区更远，不会误触拖动。
     */
    private static final int CAPSULE_ABOVE_SLIDER_DP = 32;
    /**
     * 【code 937 问题2（Ari 指令）】按钮**底边**强制落在「滑条视图顶边」往上这么多 dp 处。
     *
     * 采用值 25（Ari 指令「调整按钮位置在播放进度条上面25dp位置」；
     * 938 的 20 → 939 的 25，单位一律 dp —— Ari 已澄清此前那条「15px」是笔误）。
     * 实测（density 2.975 / screenH 2772 / 935 日志 00:30）：sliderCy=1799、
     * mainSliderH=54 ⇒ 滑条顶边 1772 ⇒ 底边 = 1772 - 25*2.975 ≈ 1698。
     * 该口径**不再参考简介底边**，因此位置与简介行的抖动彻底解耦（每帧只跟滑条走）。
     *
     * ⚠️ 【code 939】本值必须与 {@link #stableDensity} 一起看：转场瞬间被污染的
     * density（2.975 -> 3.5 / 3.875）会让本值与 CAPSULE_H_DP 同时放大，
     * 光调这个数字消不掉抖动。
     */
    private static final int CAPSULE_ABOVE_SLIDER_TOP_DP = 25;
    /** 【code 932 bug4】按钮底边距滑条中心的最小净距（dp）。20dp=60px，避开滑条本体。 */
    private static final int SLIDER_MIN_CLEAR_DP = 20;

    /**
     * 【code 923 几何 4】识别「一行淡色小字简介」时，允许它在主滑条上方多远（dp）。
     *
     * 比这更远的文本行（音轨大标题之类）不算简介，否则中点会被拉到太高、
     * 按钮跑到标题旁边去。
     */
    private static final int DESC_SEARCH_MAX_DP = 220;

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
     * 【1.21.12 问题 2】按钮从「有字幕」切到「无字幕」之前，先压这么久的延时。
     *
     * 换轨后仓库会进「待确认」窗口（{@link io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository}
     * 的 `NO_SUBTITLE_GRACE_MS` = 3000ms）：字幕先挂起不显示，等新音轨的字幕 JSON；
     * 等到窗口结束还没到才裁决「本音轨无字幕」（清 cues + 关悬浮窗）。
     *
     * 按钮原先只看 `hasSubtitles()`（= cues 非空），而 cues 在窗口期内**还没清**
     * -> 按钮一直显示「悬浮开」，死等 3 秒才变 —— Ari：「用了三秒才切换为无字幕」。
     *
     * 为什么 500ms 够：work_diag_35 全量日志里「换轨 -> 字幕 JSON 到达」的实测延迟是
     * **115ms**（15:01:03.295 -> .410）与 **345ms**（15:25:18.527 -> .872）。
     * 压 500ms 就能把「新音轨其实有字幕」的情况整个滤掉（全程不闪），
     * 而真没字幕的音轨也能在 ~0.5s 表态，比原来的 3s 快 6 倍。
     *
     * ⚠️ 这只是**提前表态 UI**，数据侧那 3000ms 裁决窗一个字没动 ——
     * 提前显示「无字幕」不等于清 cues，JSON 真迟到时窗口还会自动恢复。
     */
    private static final long BUTTON_NO_SUB_DELAY_MS = 500L;

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
     * 【code 948】锚点**面积归零**（屏幕上不剩一个像素）时的确认窗。
     *
     * 为什么需要这一档（真机取证 work_diag_76）：
     *   「点简介回作品页」时播放页是**瞬切**的（无过渡动画），进度条与标题同时消失，
     *   而按钮却晚了 **~0.75s** 才收起。视频逐帧像素：播放页特征区在 t≈1.05s 归零、
     *   胶囊按钮区到 t≈1.80s 才归零；日志侧对应的是
     *   {@code anchor dead for 0ms (area=0% evidence=false need=600ms)} → 600ms 后才 hide。
     *   即：延迟全部来自 {@link #ANCHOR_DEAD_MIN_MS} 这条防假死确认窗。
     *
     * 为什么可以收这么短（不动 600ms 默认档）：
     *   600ms 要防的是「切页过渡期锚点假死」——实测那种假死的表现是容器**部分可见**
     *   （拖动场景实测面积 30~45%，离 0 还差得远，见 {@link #PAGE_HOLD_MIN_AREA}）。
     *   面积真的归零只有两种情形：① 整页被卸载；② 页面被拖到完全看不见且已经停住
     *   （拖动中走 {@link #isPageHeld} 抑制门，根本到不了这里）。两种都该同步收起按钮。
     *
     * 【code 949 真机复测】120ms 仍然太长 —— work_diag_78 的 30fps 视频逐帧像素：
     *   「页面消失 → 胶囊消失」= +133/+100/+200/+200/+67ms；日志侧 hide 延迟 120~167ms。
     *   首次采样其实与「页面消失」同帧（廉价探针由锚点活/死翻转触发），但 need=120ms
     *   迫使它**再等一个 120ms 探针周期**才能越过门槛 —— 于是永远吃满一整个周期。
     *   ⇒ 归零档改为 need=0：首次采样即收起（预期 ≤1 帧，视觉上与进度条同时消失）。
     *   防误收改由下面的 ANCHOR_DEAD_GONE_STILL_MS 前置门承担（need=0 会让
     *   {@link #sLastPageMotionMs} 那条「页面还在动就别收」的门失效，因为 deadFor>=0 恒真）。
     */
    private static final long ANCHOR_DEAD_GONE_MS = 0L;

    /**
     * 【code 949】归零档的**前置门**：页面必须已经静止这么久，才允许「面积归零 ⇒ 立刻收起」。
     *
     * 为什么需要：need 从 120ms 降到 0 之后 {@code deadFor >= 0} 恒真，
     *   {@link #sLastPageMotionMs} 那条门（{@code since = max(死亡起点, 最后运动)}）就形同虚设了。
     *   而 fling / 惯性滚动中锚点被滚出屏幕，正是 area=0% 最常见的来源 ——
     *   那时候绝不该把按钮收掉（手指松开后页面还会回弹，收掉再亮就是「闪一下」）。
     *
     * 取值依据（真机 work_diag_78，9 次真实离场）：首次判死时日志里的
     *   {@code still=} 实测为 209/211/227/249/442/496/573/628/630/683/699/707/729ms，
     *   最小值 209ms —— 全部远超此门；而惯性滚动中该值只有几十 ms。
     *   80ms（<1 个探针周期）既放行全部真实离场，又挡住滚动中的瞬时归零。
     */
    private static final long ANCHOR_DEAD_GONE_STILL_MS = 80L;

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
     * 跟帧循环的**静止帧数**上限（≈ 1s @60fps）：连续这么多帧参考点没动，
     * 就认为页面停了、注销帧回调。空闲时**零成本**（没有注册任何帧回调）。
     *
     * ⚠️ v44 从 12（≈130ms）放宽到 90。原因是「上一版剩下的那种偶尔不跟手」：
     * 注销之后循环**只能靠宿主结构事件或心跳重新拉起**，而起手那一两帧就是跟不上的
     * —— 实测一边上下揉页面一边看日志，68 秒里循环被拆装 **14 次**
     * （`page follow on` 14 行），每次重启都要等一次事件。
     *
     * 现在页面停手后循环再「热待机」约 1 秒：热待机期间位移恒为 0 → **不写、不打日志**，
     * 只是每帧读一次参考点（~15 个字段读，注释里早就论证过可忽略），
     * 于是「揉」的时候根本不存在拆装，起手零断档；真正的长时间静止仍然会注销。
     *
     * 连带修正见 {@link #captureFollowBaseline()}：那道门原来读的是「循环没在跑」，
     * 而循环现在会在静止后再待机 ~1s，所以改读「循环自己报告页面已静止」。
     */
    private static final int FOLLOW_STILL_FRAMES = 90;

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
     * v44：**跟手进行中把主线程让出来** —— 跳过扫描时改用的心跳间隔（ms）。
     *
     * 问题（2026-09-16 11:18/11:19 日志实测）：拖动播放页时宿主每帧 setTranslationY，
     * 结构事件被合并成 ~50ms 一趟，每趟跑一次**整树扫描**（实测单次 6~8ms）+ 2 行长日志，
     * 而跟帧回调跟它**抢同一个主线程** → 日志里出现真实的跟手断档：
     * `11:18:30.398` 写下 202px 之后，下一帧直到 `11:18:30.508` 才来（**110ms**），
     * 那一窗里模块刚做了 2 次扫描、写了 6 行日志；11:19 里还有一批 55/78ms 的稀疏段。
     *
     * 处理：跟帧循环正在跟（{@code sFollowing}）+ 页面刚动过（{@link #PAGE_MOTION_HOLD_MS}
     * 窗口内）→ {@link #detectAndLayout} 直接跳过整树扫描（拖动期间扫描没有决策价值，
     * 理由写在跳过点），只把心跳按这个短间隔续上。
     *
     * 为什么是 120ms：跳过期间每 120ms 还是一趟（一趟只有几次字段读，成本≈0），
     * 而**页面一停就立刻恢复完整扫描** —— 所以「松手 / 页面被卸载」的判定最多晚 120ms，
     * 肉眼无感，但拖动过程中主线程净省下扫描与日志两块开销。
     */
    private static final long FOLLOW_QUIET_HEARTBEAT_MS = 120L;

    /** v44 诊断：本轮「跟手静默」连续跳过了多少次扫描（恢复扫描时打一行）。 */
    private static int sQuietScanSkips = 0;

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
     *
     * ⚠️ v43：这个「时间上限」曾经是假的 —— {@link #pageVisualOffset()} 每次被调用
     * （心跳每 55ms 一次）都会无条件续期 {@link #sHeldOffsetMs}，于是窗口永远从 0 起算，
     * 判据③永久成立。现已改成「位移真的变了才续期」，所以这条上限才真正生效。
     * 值同时从 1500 提到 2600：窗口以前从「最后一次**变动**」起算，而实测
     * 「页面已到位、但还没回弹」的空窗最长 2.1s（见上），1500 会在老场景里提前放行。
     */
    private static final long HOLD_SNAPSHOT_MS = 2600L;

    /**
     * 切回前台时，允许多信任「上次结论」的窗口（v43，ms）。
     *
     * {@link #ensureButton} 用「onPause 之前 ≤ 这个窗口内确实见到过播放页」来决定
     * 新按钮是否直接以 VISIBLE 重建。旧实现是无条件用 {@code sLastDecision} 初始化，
     * 而那个值可能是**过期的 true**（离开播放页时抑制门把 hide 挡掉了 → 见
     * {@link #pageVisualOffset()} 的 v43 复盘）→ 于是回到前台时按钮凭空出现在首页上。
     *
     * 判据数据（2026-09-15 四次复现）：最后一次 `player anchor alive=true` 在 19:31:48，
     * 四次 onPause 分别在 19:34:04 / 19:35:59 / 19:44:14 / 19:57:52，间隔全部 ≥136s
     * —— 判定为「不信任」→ 按钮先隐藏，由紧随其后的检测决定，症状彻底消失。
     * 而从播放页切出去马上切回来（≤1.5s）时照旧直接显示，不会有「闪一下」的回归。
     */
    private static final long RESUME_TRUST_MS = 1500L;

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
    /**
     * 【code 923 bug1】宿主布局常数偏置的**独立备份**。
     *
     * 它不受 {@link #invalidateFollow} 影响，只在「真的换页」（全量作废）时才清 ——
     * 存在的理由见 {@link #ensureHostBias()} 的复盘：靠「别去清 sVisualBias」堵不住。
     */
    private static int sHostBias = FOLLOW_NO_BASELINE;
    /** {@link #sHostBias} 是否已标定过。 */
    private static boolean sHostBiasValid = false;
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

    /**
     * 本次「前台会话」里是否**已经确认过当前是播放页**（v43）。
     *
     * 由 {@link #ensureButton}（每次 onResume）置 false，播放页证据出现时置 true
     * （PLAYER 判定分支 / 锚点存活分支）。{@link #isPageHeld()} 拿它当总闸：
     * 「门」的前提是「播放页确实在眼前」，切回前台时屏幕上可能是首页/书架，
     * 此时按「页面被拖开」处理就会把按钮永久留在非播放页上。
     */
    private static boolean sPlayerConfirmedInSession = false;

    /** 上一次 onPause 的时刻（v43，见 {@link #RESUME_TRUST_MS}）。 */
    private static long sPauseAtMs = 0L;
    /** 上一次 onPause 那一刻「最近一次见到播放页」的时刻（0 = 本会话没见过）。 */
    private static long sPausePlayerSeenMs = 0L;
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

    /**
     * 【v57】悬浮窗字幕开关按钮（右）。
     *
     * ⚠️ 命名沿革：v57 之前这是**唯一**的按钮（`sButton`），短按切悬浮窗、长按切状态栏。
     * 现在它只负责悬浮窗，状态栏那个是 {@link #sStatusBarButton}。保留原名是为了让
     * 既有 57 处引用（跟随、判空、可见性）**不改语义地**继续工作 —— 它们关心的本来就是
     * 「按钮组整体在不在」，而容器 {@link #sButtonGroup} 承担了那个角色。
     */
    private static TextView sButton;

    /**
     * 【v57】状态栏字幕开关按钮（左）。
     *
     * 需求：「如无字幕，状态栏字幕按键消失」→ 无字幕时本按钮 {@code View.GONE}，
     * 而不是变灰/禁用 —— 消失就是消失（需求原话「消失」）。
     */
    private static TextView sStatusBarButton;

    /**
     * 【v57】承载两个胶囊的容器。
     *
     * 为什么必须有容器：两个按钮要作为**一组**一起跟随滑条、一起显隐。
     * 若各自 setLayoutParams 定位，一次变更会触发两次 requestLayout；
     * 有容器则「组的位置」只由容器一个 translationY 决定（不触发布局）。
     *
     * 容器是 {@code LinearLayout}（horizontal），本身**不设背景**，
     * 尺寸 wrap_content —— 它的作用是布局与位移，不是视觉。
     */
    private static LinearLayout sButtonGroup;

    /**
     * 【v57】按钮组底边当前跟随到的 y（屏幕坐标，px）；{@code NO_FOLLOW_Y} = 尚未定位。
     *
     * 迟滞用：只有目标值与它相差超过 {@link #CAPSULE_FOLLOW_DEADZONE_PX} 才写 translationY，
     * 否则逐帧微抖会灌爆「相等才跳过」的缓存（1.21.10 踩过）。
     */
    private static int sCapsuleFollowY = Integer.MIN_VALUE;

    /** 【v57】上一次写入的 translationY（px），与 {@link #applyCapsuleOffset} 的判等用。 */
    private static int sCapsuleAppliedDy = Integer.MIN_VALUE;

    /** 【v57】跟随迟滞阈值（px）：目标位移与当前位移差小于它就**不动**。 */
    private static final int CAPSULE_FOLLOW_DEADZONE_PX = 6;

    /**
     * 【v57】最近一次扫描到的主滑条中心 y（**屏幕坐标**，px）；{@code -1} = 未知。
     *
     * 为什么缓存它而不是把 scan 结果整个存下来：本类只需要这一个数来算按钮位置，
     * 存整个 ScanResult 会让「新鲜证据」的生命周期变长（scan 的契约是「所有字段都是
     * 本次的新鲜证据，绝不跨帧保留」）。只取一个 int，语义最小、最不容易出错。
     *
     * 只在主线程读写（扫描与 showButton 都在主线程）。
     */
    private static int sLastSliderCy = -1;
    /** 【code 934 bug3】最近一次扫描到的主滑条**视图高度**（px）；-1 = 未知。
     *  用于把「滑条中心」换算回「滑条视图顶部」（MID 分支的几何基准）。 */
    private static int sLastSliderH = -1;
    /**
     * 【code 923 几何 3】最近一次扫描到的**主滑条右缘**屏幕 x（px）；{@code -1} = 未知。
     * 与 {@link #sLastSliderCy} 同样的更新契约：只在扫到滑条时才写，扫不到保持上次值。
     */
    private static int sLastSliderRightPx = -1;
    /**
     * 【code 923 几何 4】最近一次扫描到的「一行淡色小字简介」**底边**屏幕 y（px）；
     * {@code -1} = 未知。同上：只在扫到时才写。
     */
    private static int sLastDescBottomY = -1;
    /** 【code 924】几何 4 诊断：上次打过的候选数 / 命中底边（变了才打）。 */
    private static int sLastDescCand = Integer.MIN_VALUE;
    private static int sLastDescBottomLogged = Integer.MIN_VALUE;
    /** 【code 924】几何 4 的上次落地分支 / 结果，用于「变了才打日志」。 */
    private static boolean sLastMidUsed = false;

    /**
     * 【code 927 问题 3】「按钮位置是基于哪一组几何算出来的」快照。
     *
     * 真机根因（work_diag_52 铁证）：
     *   · `[几何5] button created at FALLBACK bottom=286px (sLastSliderCy=-1)` ×2
     *   · 此后 `[几何4] capsuleBottom` **0 次** —— 位置再没被重算过
     *   · `verdict=PLAYER` 全场只 4 次、`button shown (player page)` 只 1 次
     *   ⇒ 位置重算只挂在 showButton/ensureButton 上，而 showButton 依赖的 sLastSliderCy
     *     又是**同一轮 scan** 才更新 ⇒ **自引用、永远差一拍**：
     *     ensureButton 时 sLastSliderCy=-1 → 落兜底位；等它变成 1799 时
     *     showButton 已经不跑了 ⇒ 位置永不纠正。
     *   修法：几何更新点**独立触发**重落位；本快照用于「几何真的变了才重算」。
     */
    private static int sPlacedGeoSliderCy = Integer.MIN_VALUE;
    private static int sPlacedGeoDescBottom = Integer.MIN_VALUE;
    private static int sPlacedGeoSliderRight = Integer.MIN_VALUE;
    /** 【code 927】capsuleBottomForSlider 调用计数（诊断：区分「没调用」与「调了没变」）。 */
    private static int sCbCallCount = 0;
    /** 【code 927 问题 3】按钮是否由兜底位创建（几何未就绪时的临时落位，必须补纠正）。 */
    private static boolean sCapsulePlacedByFallback = false;
    private static int sLastMidCenterY = Integer.MIN_VALUE;
    /**
     * 【code 923】最近一次拿到的 displayMetrics.density（px/dp）。
     * {@link #createCapsuleDrawable} 要按 dp 算圆角，但它没有 Context 参数 ——
     * 由 {@link #dip2px} 与 {@link #createButtonGroup} 顺手维护，不在调用链上加参数。
     */
    private static float sDensityPx = 0f;

    /**
     * 【code 939 bug2 根修】「不抖的 density」。
     *
     * ── 真机实证（work_diag_64 / 2026-09-22 09:12 日志）──
     * `centerY = sliderTop - dip2px(20) - dip2px(32)/2` 与右侧的 dip2px 都直接吃
     * {@code ctx} 的 {@code DisplayMetrics.density}，而**打开播放界面转场的那几秒**
     * 它会被从一个不稳定的配置上下文里读到：
     *   · 常态 d=2.9688（476dpi）→ capsuleH=95、右距 59px；
     *   · 转场中 d=3.5（560dpi）  → capsuleH=112、右距 70px；
     *   · 转场中 d=3.875（620dpi）→ capsuleH=124、右距 77px。
     * 三者恰好是 OPPO「屏幕缩放」档位表
     * {@code ro.density.screenzoom.qdh=[500,476,560,600,620]} 的第 2/3/5 档
     * ⇒ **不是布局真的变了**（同一时刻滑条仍在 1799、简介仍在 1722、
     * 屏幕仍是 1272x2772），只是 density 读数被污染。
     * 按钮因此上下瞬移 19~32px、左右同时偏 11~18px（斜着抖）——
     * 正是 Ari 反馈的「打开播放界面按钮会上下抖动一下」。
     *
     * ── 取法 ──
     * 一次性锁定，之后只有两种情况才允许改：
     *   ① 像素屏幕尺寸变了（旋转 / 分屏 / 换屏）—— 用 widthPixels*31+heightPixels 指纹判；
     *   ② 与锁定值持续不一致超过 {@link #DENSITY_RELOCK_MS} 且样本数够
     *      （覆盖「用户真的改了显示大小」：此时 px 不变、density 持久变化）。
     * 其余一律沿用锁定值，并打一行 {@code density spike ignored} 留痕。
     */
    private static float sLockedDensity = 0f;
    /** 锁定时的像素屏幕尺寸指纹（w*31+h）。变了才认作真正的屏幕/窗口重配置。 */
    private static int sLockedPxKey = 0;
    /** 与锁定值不一致的起始时刻（uptimeMillis，0 = 当前一致）。 */
    private static long sDensityDiffSinceMs = 0L;
    /** 与锁定值不一致的累计样本数（进入不一致态即重置）。 */
    private static int sDensityDiffCount = 0;
    /** 持续不一致多久才认作「真的改了显示大小」而重新锁定。 */
    private static final long DENSITY_RELOCK_MS = 20000L;
    /** 持续不一致至少积累多少个样本才允许重新锁定（防抖）。 */
    private static final int DENSITY_RELOCK_SAMPLES = 100;
    private static Activity sActivity;
    private static ViewTreeObserver.OnGlobalLayoutListener sLayoutListener;
    private static long sLastDetectMs = 0L;
    // 【v57】sLastBtnText / sLastBtnBgMode 已合并为 sLastBtnSig（见其注释）。
    /**
     * 【1.21.12 问题 2】「切进无字幕」的延时是否已排队（见 {@link #BUTTON_NO_SUB_DELAY_MS}）。
     * 存这个是为了让窗口期内反复来的观察者通知别重复排队。
     * 只在主线程读写（{@link #applyButtonText} 一律跑在 uiHandler 上）。
     */
    private static boolean sBtnNoSubPending = false;
    /** 代次：任何一次状态落笔/状态一致都 +1，用来作废排队中的延时切换。 */
    private static int sBtnNoSubGen = 0;

    /**
     * 【v57】上次落笔的**两个**按钮的文字（悬浮窗钮 / 状态栏钮，以 '\u0000' 分隔）。
     *
     * 为什么合成一个字符串：{@link #applyButtonText} 的判等是「全部视觉属性都一致才跳过」，
     * 而两个按钮各有文字 + 底色 + 可见性 —— 拆成 6 个字段判等容易漏（1.21.13/1.21.14
     * 就是漏字段导致「文字变了底色没变」）。合成一个签名串，判等天然覆盖全部。
     *
     * 签名格式（顺序固定）：
     *   {@code 悬浮窗文字 | 悬浮窗底色 | 状态栏文字 | 状态栏底色 | 状态栏可见性}
     */
    private static String sLastBtnSig = null;

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
                XposedCompat.log(TAG + " anchor watch error: " + e.getMessage());
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
        XposedCompat.log(TAG + " page follow rebase: ref -> "
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
        // v38：留一份「页面刚才被拖开过」的快照，供 isPageHeld 的判据③用。
        // 页面被拖到极限后参考点会失去「可用」资格、面积也趋近 0，判据②会失效；
        // 这份快照把这段空窗撑过去（实测最长 2.1s 的 hide→show 间隔）。
        //
        // ⚠️ v43 修一个致命陷阱：原来是**每次读到就续期**（无条件写 sHeldOffsetMs）。
        // 而本函数被心跳（notePageMotion）、跟帧循环、以及 isPageHeld() 自己反复调用，
        // 于是 HOLD_SNAPSHOT_MS 这个「有效期」永远从 0 开始计时 —— 只要 |位移| ≥
        // PAGE_HOLD_PX 且参考点还 attached，判据③就**永久成立**、抑制门永不失效，
        // 与下面 HOLD_SNAPSHOT_MS 注释里「不会赖着不走」的设计意图正好相反。
        //
        // 实测后果（2026-09-15 19:34 / 19:42 / 19:55 / 20:05，四次 onResume 全部复现）：
        // 播放页被滑走后容器停在 translateY=2772px（= 一屏高，正是「拖到极限」的停靠位——
        // 与真实拖动 2710~2772px 完全同量级，位移本身无法区分）。切后台再回前台时
        // sLastDecision 还停在过期的 true → 按钮以 VISIBLE 重建 → 此后每次心跳都刷新快照
        // → hide 被永久压制 → 按钮在**首页**上一挂几十秒（直到 onPause 才 removeButton）。
        //
        // 现在改成「位移变化 ≥ FOLLOW_DEADZONE_PX 才续期」：快照 = 「最后一次真的动过」，
        // 时间上限才真的会到期。匀速拖动每帧都在变 → 窗口照样被持续续期，不影响拖手保护。
        if (sHeldOffset == FOLLOW_NO_BASELINE
                || Math.abs(vis - sHeldOffset) >= FOLLOW_DEADZONE_PX) {
            sHeldOffset = vis;
            sHeldOffsetMs = SystemClock.uptimeMillis();
            sHoldProbeRef = new WeakReference<>(v);
        }
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
                XposedCompat.log(TAG + " page follow channel: transform-sum active (first motion "
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
            XposedCompat.log(TAG + " page follow channel: transform-sum active (first motion "
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
        // ⓪ v43 总闸：本次前台会话里**还没确认过播放页** → 这道门一律不放行。
        // 门的意义是「别在用户拖播放页时把按钮藏了」，前提是播放页确实在眼前；
        // 切后台再回前台时屏幕上可能是首页/书架，播放页容器只是**停靠在屏外**
        // （translateY=2772px），此时按「拖开」处理会让 hide 被永久压制
        // —— 按钮就挂在非播放页上不走了（详见 pageVisualOffset() 的 v43 复盘）。
        // 放行不会误伤：真的在播放页上时锚点很快就会被确认存活（同帧或下一次扫描），
        // 确认之后门照常生效；拖动本来也来不及在确认之前开始。
        if (!sPlayerConfirmedInSession) {
            return false;
        }
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
     * 跟帧循环是否**报告页面已静止**（{@link #captureFollowBaseline()} 的门①）。
     *
     * v44：以前这道门直接读 {@code !sFollowing}，而循环现在会在页面停手后再
     * 热待机 {@link #FOLLOW_STILL_FRAMES} 帧（≈1s，见那条常量的注释）才注销 ——
     * 若还按「没在跑」判断，基线 / 偏置采集会被推迟 1 秒，跟手反而更晚就绪。
     *
     * 语义：循环没在跑 → 当作静止（与老行为一致）；在跑但已连续 FOLLOW_STILL_FRAMES
     * 帧读到同一个位移 → 同样是静止。**「位移为 0」由调用方另行把关**，
     * 所以这个判据只回答「页面还在不在动」。
     */
    private static boolean followReportsStill() {
        return !sFollowing || sFollowStill >= FOLLOW_STILL_FRAMES;
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
        // v44：门① 从「循环没在跑」改成「循环自己报告页面已静止」。
        // 循环现在会在页面静止后再热待机 ~1s（见 FOLLOW_STILL_FRAMES），若仍按
        // 「没在跑」判断，基线 / 偏置的采集会被无谓推迟整整 1 秒 —— 而 transform 通道
        // 要等 sVisualBias 标定完才敢用，等于把「跟手就绪」整整推迟 1 秒，反倒更不跟手。
        // 新判据语义等价：位移为 0（下一行）+ 连续 FOLLOW_STILL_FRAMES 帧没动 = 页面确实静止。
        if (!followReportsStill() || sFollowAppliedY != 0
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
            XposedCompat.log(TAG + " page follow bias calibrated: " + sVisualBias
                    + "px (ref=" + (sFollowRefIsPlayBtn ? "play-button" : "anchor") + ")");
        }
        // 【code 923 bug1】标定成功 → 立刻另存一份到「宿主偏置备份」。
        // 之后不管谁把 sVisualBias 清了，ensureHostBias() 都能把它填回去。
        sHostBias = sVisualBias;
        sHostBiasValid = true;
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
        ensureHostBias(); // 【code 923 bug1】同上，偏置在这里也要先补回来
        if (sFollowing || sActivity == null || sButtonGroup == null
                || sButtonGroup.getVisibility() != View.VISIBLE) {
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
    /**
     * 【code 923 bug1】把「宿主布局常数偏置」从备份里**补回来**。
     *
     * ── 为什么需要它（code 922 那次修复为什么没生效）──
     * 922 把按钮重建 / onPause 两处的 {@code invalidateFollow()} 改成
     * {@code invalidateFollow(true)} 以保留 sVisualBias。但真机日志（22:49:53 那一轮，
     * 45 分钟 / 28 次 onResume）显示：
     *   · {@code page follow bias calibrated} 仍然出现 **26 次**
     *     （它只在 {@code sVisualBias == FOLLOW_NO_BASELINE} 时才打）；
     *   · {@code page follow skipped: no baseline & no transform channel} **29 次**，
     *     几乎每条 onResume→ensureButton 后面都跟一条。
     * 而 smali 层能写 sVisualBias 的位置**只有 3 处**（clinit / captureFollowBaseline /
     * invalidateFollow(Z) 且被 {@code if-nez p0} 守护），两处调用点传参实为
     * {@code const/4 vN, 0x1}，无参重载 {@code invalidateFollow()V} **没有任何调用点**。
     * ⇒ 存在一条源码与 smali 都查不到的清零路径，「别去清它」这条路堵不住。
     *
     * ── 所以改成主动兜底 ──
     * 偏置一旦标定过就另存一份；之后**在每一个要用它的入口**先调用本函数。
     * 于是无论那条未知路径什么时候把它清掉，下一次读取前都会被填回来：
     *   · {@link #syncFollowOffsetOnShow()} 首帧就能算出位移 → 按钮**不会**先在
     *     translationY=0 露脸再跳（= 用户说的「闪现」）；
     *   · {@link #startPageFollow()} 立刻具备 transform 通道 → 不再打 no-baseline 空转；
     *   · {@link #maybeStartPageFollow()} 的「页面此刻被拖开」判据也能立刻成立。
     */
    private static void ensureHostBias() {
        if (sVisualBias != FOLLOW_NO_BASELINE || !sHostBiasValid) {
            return;
        }
        sVisualBias = sHostBias;
        sVisualSeen = true;
        XposedCompat.log(TAG + " page follow bias restored from host cache: " + sHostBias + "px");
    }

    private static void startPageFollow() {
        if (sFollowing) {
            return;
        }
        ensureHostBias(); // 【code 923 bug1】先把可能被清掉的宿主偏置补回来
        boolean transformReady = sVisualSeen && sVisualBias != FOLLOW_NO_BASELINE;
        if (sFollowBaseY == FOLLOW_NO_BASELINE && !transformReady) {
            // 诊断（v36 新增）：v35 这里**静默失败** —— 页面明明在动、循环就是起不来，
            // 日志里一个字都没有，只能靠猜。这条行就是给下一轮排查用的。
            long now = SystemClock.uptimeMillis();
            if (now - sFollowRejectLogMs >= 1000L) {
                sFollowRejectLogMs = now;
                XposedCompat.log(TAG + " page follow skipped: no baseline & no transform channel");
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
     * 什么时候用：参考点真的没了、**换页面了** —— 也就是「旧的位移参考系整体失效」
     * 的时刻。**不要**在普通的 hide 里用它（那正是 v36 的病根：
     * 拖动中 hide 顺手扔掉基线 → 重新显示后按钮卡在原位）。
     *
     * ⚠️ 【code 922 问题 2】**按钮重建不再走这里**（改用 {@code invalidateFollow(true)}）——
     * 宿主偏置与按钮新旧无关，跟着按钮一起作废会导致「切前台首帧跟手空转 + 按钮闪现」。
     * 只有「页面真的换了」（宿主布局偏置本身可能变了）才该用本入口全量作废。
     */
    private static void invalidateFollow() {
        invalidateFollow(false);
    }

    /**
     * 【code 922 问题 2】参考系作废。{@code keepHostBias=true} 时**保留**
     * {@link #sVisualBias} 与 {@link #sVisualSeen}。
     *
     * ── 为什么必须区分（问题 2 的根因之一）──
     *
     * {@code sVisualBias} 是**宿主布局的常数偏置** —— 祖先链里常驻的非零
     * {@code translationY}（系统 insets、宿主布局补偿）。它由
     * {@link #captureFollowBaseline()} 在「确认页面静止」那一刻采一次差得到，
     * 与「我们的按钮是新是旧」**毫无关系**。
     *
     * 旧实现只有「全量作废」一个入口，于是 {@link #removeButton} → {@link #ensureButton}
     * （切后台再切回，一次会话里实测发生 21 次）每次都把偏置抹掉。后果实测：
     *   · 每次 onResume 后 15~21ms 必有一条
     *     `page follow skipped: no baseline & no transform channel`
     *     —— 切前台后的**第一帧跟手必然空转**；
     *   · {@link #syncFollowOffsetOnShow()} 见 {@code sVisualBias == FOLLOW_NO_BASELINE}
     *     直接 return → 按钮在 {@code translationY=0} 的**基准位先露脸**，
     *     等 `page follow bias calibrated` 完成（实测 54~637ms 后）才挪到正确位
     *     —— 这就是用户说的「**两个按钮会闪现**」。
     *
     * 保留偏置后：偏置仍准确（同一个宿主进程、同一套 insets），
     * 且 {@link #followOffset()} 第一帧就走 transform 通道 ⇒ 上面两个症状一起消失。
     * 偏置在**换页 / 参考点真的没了**时仍会被全量作废（{@code keepHostBias=false}），
     * 不会把「页面换了」误当成「宿主偏置没变」。
     *
     * 注意：{@link #sVisualOffset} 是「本帧读到的原始累加值」，任何情况下都要清 ——
     * 它是**瞬时量**，留着会让下一帧的差值算错。
     */
    private static void invalidateFollow(boolean keepHostBias) {
        stopPageFollow(true);
        sFollowAppliedY = 0;
        sFollowBaseY = FOLLOW_NO_BASELINE;
        sBaseCandY = FOLLOW_NO_BASELINE;
        sVisualOffset = FOLLOW_NO_BASELINE;
        if (!keepHostBias) {
            sVisualBias = FOLLOW_NO_BASELINE;
            sVisualSeen = false;
            // 真的换页 → 宿主布局本身可能变了，备份一并作废。
            sHostBias = FOLLOW_NO_BASELINE;
            sHostBiasValid = false;
        }
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
                        if (sActivity == null || sButtonGroup == null
                                || sButtonGroup.getVisibility() != View.VISIBLE) {
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
                                // v44：这个阈值已放宽到 90 帧（≈1s 热待机）—— 见常量的注释。
                                // 静止期间位移恒为 0 → 不写、不打日志，只有每帧一次参考点读。
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
        if (sButtonGroup == null || dy == sFollowAppliedY) {
            return;
        }
        sFollowAppliedY = dy;
        sSelfTranslate = true; // 挡住我们自己的 setTranslationY 触发的结构事件
        try {
            sButtonGroup.setTranslationY(dy);
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
            // 【code 933 精简】移除每帧 follow 日志（真机 443 行/会话，纯噪音）。
            //   位移的实时观测改由 statusbar/verdict 等低频日志承担。
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
            XposedCompat.log(TAG + " structure event -> instant scan [" + reason
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
            XposedCompat.log(TAG + " anchor attach-state watched");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " watchAnchorAttachment failed: " + e.getMessage());
        }
    }

    /** 【code 924】双击关闭请求的接收器（App 进程侧，唯一执行落点）。 */
    private static android.content.BroadcastReceiver sDismissReceiver;

    /**
     * 【code 924】注册「双击状态栏字幕 -> 关闭」的请求接收器。
     *
     * SystemUI 进程发 {@link StatusBarSubtitleBridge#ACTION_DISMISS_REQUEST}；
     * 本方法在 App 进程收到后，走**与胶囊按钮点击完全相同**的路径：
     *   canToggle 判据 -> toggleAppEnabled -> resendCurrentFromRepo -> 刷新按钮。
     * 这样「状态栏字幕开关」在整个系统里只有一个写入口（唯一计算函数）。
     */
    private static void registerDismissReceiver(final Activity activity,
                                                final SubtitleRepository repo) {
        if (sDismissReceiver != null) {
            return;
        }
        try {
            sDismissReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent it) {
                    if (it == null
                            || !StatusBarSubtitleBridge.ACTION_DISMISS_REQUEST
                                    .equals(it.getAction())) {
                        return;
                    }
                    try {
                        if (!StatusBarSubtitleBridge.canToggle(repo)) {
                            XposedCompat.log(TAG + " dismiss ignored: no subtitles");
                            return;
                        }
                        boolean on = StatusBarSubtitleBridge.toggleAppEnabled(activity);
                        StatusBarSubtitleBridge.resendCurrentFromRepo(activity, repo);
                        XposedCompat.log(TAG + " status bar subtitle " + (on ? "ON" : "OFF")
                                + " (double tap on status bar)"
                                + " reason=" + it.getStringExtra(
                                        StatusBarSubtitleBridge.EXTRA_DISMISS_REASON));
                        uiHandler.post(() -> applyButtonText(repo, true));
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " dismiss receiver failed: " + t);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(
                    StatusBarSubtitleBridge.ACTION_DISMISS_REQUEST);
            try {
                activity.registerReceiver(sDismissReceiver, f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                activity.registerReceiver(sDismissReceiver, f);
            }
            XposedCompat.log(TAG + " dismiss receiver registered (double tap)");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerDismissReceiver failed: " + t);
        }
    }

    // ==================================================================
    // 【code 941】SystemUI 作用域授权探测
    //
    // 需求：用户**没有**在 LSPosed 里勾选 com.android.systemui 作用域时，
    //       播放页不显示「状态栏 开/关」那个胶囊。
    //
    // 为什么要「探测」而不是直接读配置：LSPosed 的作用域配置在 /data/adb 下，
    // 宿主 App 没有 root 读不到。但「勾了作用域」有一个**可观测的后果** ——
    // 模块会被注入 SystemUI 进程，于是那边有人能应答广播。于是用握手：
    //   App 发 PING -> 被注入的 SystemUI 回 PONG -> App 侧「已授权」。
    // 没勾选时没有接收方，PING 静默消失，永远收不到 PONG。
    //
    // ⚠️ 本判据把「勾了但装完没重启 SystemUI」也算作未授权（那时 SystemUI 里跑的
    //    还是没有这段代码的旧 dex）—— 这是**有意**的：那种状态下按钮同样点不动
    //    （广播没人收），显示出来只会让人以为坏了。App 侧看到构建号不一致会打一行
    //    WARN 提示重启 SystemUI。
    // ==================================================================

    /** 【code 941】心跳间隔。取 3s：比 SystemUI 重启（约 1~2s）长一点，别在重启窗口里误判。 */
    private static final long SCOPE_PING_INTERVAL_MS = 3000L;
    /**
     * 【code 944】首次探测的**快速补探**时刻（ms，相对 registerScopeWatch）。
     *
     * 为什么需要：只发一次即刻 PING 的话，若那一拍正好赶上 SystemUI 侧接收器
     * 还没注册好（SystemUI 刚重启），就白白等满一个心跳 3s —— 进播放页会先看到
     * 一个缺了左胶囊的按钮组，几秒后才补上。补两拍把确认时间压到接近即时。
     * 未授权时这两拍也只是静默无回包，零副作用。
     */
    private static final long SCOPE_KICK_1_MS = 400L;
    private static final long SCOPE_KICK_2_MS = 1200L;
    /**
     * 【code 944】快速补探任务：只发 PING，不参与心跳链
     * （心跳链由 {@link #sScopePingRunnable} 独占，别把两条链搅在一起）。
     */
    private static final Runnable sScopeKickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context c = sScopePingCtx;
                if (c != null) {
                    StatusBarSubtitleBridge.sendScopePing(c);
                }
            } catch (Throwable t) {
                XposedCompat.log(TAG + " scope kick failed: " + t);
            }
        }
    };
    /** 【code 941】PONG 接收器。注册在**应用级 Context** 上，与 Activity 生命周期无关。 */
    private static android.content.BroadcastReceiver sScopePongReceiver;
    /** 【code 941】探测是否已启动（幂等；每次进播放页的 onResume 都会走到调用点）。 */
    private static boolean sScopeWatchStarted = false;
    /** 【code 941】发心跳用的 Context（应用级：onPause 后 Activity 会被清空，不能拿它当锚）。 */
    private static Context sScopePingCtx;
    /** 【code 941】最近一次**落笔时**的授权态 —— 只用来「翻转才打日志 / 才重画」。 */
    private static boolean sLastScopeAuthorized = false;
    /** 【code 941】是否已打过「首次状态」日志（见 refreshScopeState）—— 保证至少有线索。 */
    private static boolean sScopeEverReported = false;

    /**
     * 【code 941】作用域心跳：发 PING，然后看授权态有没有翻转。
     *
     * 为什么必须有心跳（而不是开一次探一次就完）：
     *   ① 首次探测可能赶在 SystemUI 重启窗口里（接收器还没就绪）-> 要能自己重试；
     *   ② 用户可能中途在 LSPosed 里**取消**勾选（重启 SystemUI 后生效）-> 要能回到未授权。
     * 两者都靠「心跳 + 新鲜期」（{@link StatusBarSubtitleBridge#SCOPE_FRESH_MS}）覆盖。
     */
    private static final Runnable sScopePingRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context c = sScopePingCtx;
                if (c != null) {
                    StatusBarSubtitleBridge.sendScopePing(c);
                }
                refreshScopeState("heartbeat");
            } catch (Throwable t) {
                XposedCompat.log(TAG + " scope heartbeat failed: " + t);
            }
            // 永不断链：即使上面抛异常也继续下一拍。
            uiHandler.postDelayed(this, SCOPE_PING_INTERVAL_MS);
        }
    };

    /**
     * 【code 941】授权态**翻转时**才动手：打一行日志 + 重画两个胶囊。
     * 状态栏钮的可见性由 {@link #statusBarButtonVisible} 决定，重画即生效。
     */
    private static void refreshScopeState(String why) {
        boolean now = StatusBarSubtitleBridge.isSystemUiScopeAuthorized();
        if (now == sLastScopeAuthorized) {
            if (!sScopeEverReported) {
                // 首次报告：**必须**留一行 —— 否则「没有 PONG 所以按钮不显示」这条会
                // 在日志里完全静默，事后排查只能靠猜。
                sScopeEverReported = true;
                XposedCompat.log(TAG + " systemui scope: no pong yet -> status bar button"
                        + " stays hidden (com.android.systemui scope not granted,"
                        + " or SystemUI was not restarted after install)");
            }
            return;
        }
        sScopeEverReported = true;
        sLastScopeAuthorized = now;
        int build = StatusBarSubtitleBridge.getScopePongBuild();
        XposedCompat.log(TAG + " systemui scope -> " + (now ? "authorized" : "revoked")
                + " (" + why + ", pongBuild=" + build
                + ", appBuild=" + BuildConfig.VERSION_CODE + ")");
        if (now && build > 0 && build != BuildConfig.VERSION_CODE) {
            XposedCompat.log(TAG + " WARN systemui process runs an older build ("
                    + build + " != " + BuildConfig.VERSION_CODE
                    + ") -> restart SystemUI to load this build");
        }
        final SubtitleRepository repo = SubtitleRepository.getInstance();
        if (repo != null) {
            uiHandler.post(() -> applyButtonText(repo, true));
        }
    }

    /**
     * 【code 941】启动作用域探测（幂等）。
     *
     * 用**应用级 Context**：本探测的生命周期是「整个 App 进程」，不该跟着
     * onResume/onPause 断链 —— 否则切后台再回来时 PONG 已过期，状态栏钮会先消失
     * 几秒再回来（可见的闪烁）。
     */
    private static void registerScopeWatch(Context activityCtx) {
        if (sScopeWatchStarted) {
            return;
        }
        sScopeWatchStarted = true;
        try {
            Context appCtx = activityCtx != null ? activityCtx.getApplicationContext() : null;
            if (appCtx == null) {
                appCtx = activityCtx;
            }
            if (appCtx == null) {
                sScopeWatchStarted = false;
                return;
            }
            sScopePingCtx = appCtx;
            sScopePongReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent it) {
                    if (it == null
                            || !StatusBarSubtitleBridge.ACTION_SCOPE_PONG
                                    .equals(it.getAction())) {
                        return;
                    }
                    try {
                        int build = it.getIntExtra(
                                StatusBarSubtitleBridge.EXTRA_PONG_BUILD, -1);
                        StatusBarSubtitleBridge.onScopePong(build);
                        // 首次确认要**立刻**刷新（别等下一拍心跳），否则进播放页会先看到
                        // 一个没有状态栏钮的按钮组、几十~几百毫秒后才补上。
                        refreshScopeState("pong");
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " scope pong failed: " + t);
                    }
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(StatusBarSubtitleBridge.ACTION_SCOPE_PONG);
            try {
                // SystemUI 与本 App **不同 UID** ⇒ 收它的广播必须声明 EXPORTED，
                // 否则 Android 13+ 注册直接抛 SecurityException（与 SystemUI 侧收
                // ACTION_LINE 是同一类问题，见 StatusBarSubtitleHook#registerReceiver）。
                appCtx.registerReceiver(sScopePongReceiver, f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                appCtx.registerReceiver(sScopePongReceiver, f);
            }
            StatusBarSubtitleBridge.sendScopePing(appCtx);   // 先探一次，别干等 3s
            // 【code 944】再补两拍：覆盖「SystemUI 侧接收器晚一步就绪」这个窗口。
            uiHandler.postDelayed(sScopeKickRunnable, SCOPE_KICK_1_MS);
            uiHandler.postDelayed(sScopeKickRunnable, SCOPE_KICK_2_MS);
            uiHandler.removeCallbacks(sScopePingRunnable);
            uiHandler.postDelayed(sScopePingRunnable, SCOPE_PING_INTERVAL_MS);
            XposedCompat.log(TAG + " systemui scope watch started (ping "
                    + SCOPE_PING_INTERVAL_MS + "ms, fresh "
                    + StatusBarSubtitleBridge.SCOPE_FRESH_MS + "ms)");
        } catch (Throwable t) {
            // 注册失败就允许下次进播放页重试（否则一个异常会把探测永久废掉）。
            sScopeWatchStarted = false;
            XposedCompat.log(TAG + " registerScopeWatch failed: " + t);
        }
    }

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> activityClass = XposedCompat.findClass("android.app.Activity", cl);
            Method onResume = XposedCompat.findMethodExact(activityClass, "onResume");
            XposedCompat.hookMethod(onResume, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    Object self = chain.getThisObject();
                    if (!(self instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) self;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedCompat.log(TAG + " activity onResume -> ensureButton");
                    ensureButton(activity, repo);
                }
            });

            Method onPause = XposedCompat.findMethodExact(activityClass, "onPause");
            XposedCompat.hookMethod(onPause, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    Object self = chain.getThisObject();
                    if (!(self instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) self;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedCompat.log(TAG + " activity onPause -> removeButton");
                    removeButton(activity);
                }
            });

            repo.addObserver(() -> updateButtonText(repo));
            // 【1.21.13 问题 2】状态栏字幕开关（胶囊底色的真源）一变就立刻重画按钮底色。
            // 这条链路与上面的仓库观察者**互相独立**：开关由「点击状态栏胶囊」改动，
            // 该动作发生在点击回调里、不经过仓库观察者，所以不保证还会再触发一次 applyButtonText。
            // 【v57】旧版这里是「长按 1s 触发」，长按已取消（需求：「只保留点击」），
            //        但**这条监听链路要保留** —— 它解决的是「开关变了底色没跟上」，
            //        跟触发方式是长按还是点击无关。
            StatusBarSubtitleBridge.setEnabledListener(() ->
                    uiHandler.post(() -> updateButtonDrawable(repo)));
            XposedCompat.log(TAG + " hooked Activity lifecycle");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " hook failed: " + e.getMessage());
        }
    }

    private static void ensureButton(Activity activity, SubtitleRepository repo) {
        uiHandler.post(() -> {
            try {
                sActivity = activity;
                sDensityPx = stableDensity(activity); // 【code 939】走锁定值，避免转场伪 density
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                sButtonGroup = decor.findViewById(BUTTON_ID);
                // 【v57】容器在树上时，两个子按钮必须**一起捞回来**。
                // 走复用分支（切后台回来）时不会进 createButtonGroup，若不在这里补捞，
                // sButton/sStatusBarButton 会停在上一轮的悬空引用上 —— 表现是按钮不再更新
                // （白板或看不见变化），且没有任何异常日志。旧版单按钮时容器即按钮，
                // 一次 findViewById 就够了，所以这是双按钮改造**新引入**的缺陷。
                syncCapsuleRefsFromGroup();
                boolean freshButton = false;
                boolean trustLastPlayer = false;
                // v43：本次前台会话以「尚未确认播放页」开场 —— 抑制门在确认之前一律不放行。
                sPlayerConfirmedInSession = false;
                if (sButtonGroup == null) {
                    sButtonGroup = createButtonGroup(activity, repo);
                    // v42：初始位置直接放右下角（与 showButton 判定为播放页后的位置一致），
                    // 不再用左上角占位。否则 onResume 重建按钮（sLastDecision=true → 直接 VISIBLE）
                    // 而检测还没判定为播放页的窗口期（如正滑页面/页面过渡 → 判定 OTHER 走 break 维持原状、
                    // 不调 showButton），按钮会可见地先出现在左上角，直到 showButton 才挪到右下角 ——
                    // 即用户看到的「悬浮窗按钮突然出现在 app 左上角，重开播放页后才正常」。
                    // 【v57】组宽 = 两个胶囊 + 一个间距（这是初始 LayoutParams 的宽；
                    // 实际测量后 setLayoutParams 不再覆写，靠 showButton 的迟滞门控制）。
                    // 【code 923 bug2】容器宽度必须是 **WRAP_CONTENT**，不能写死总宽。
                    //
                    // 旧代码写死「两钮 + 间距」= 190dp：状态栏钮在无字幕时 GONE，
                    // 但容器宽度**不收缩**，LinearLayout 的子钮水平靠左 → 「无字幕」钮
                    // 停在原来状态栏钮的位置，右侧空出整整一个钮 + 间距。
                    // 真机量测：无字幕钮右缘距屏右 ≈ 123dp = 16dp 边距 + 100dp 未收缩宽度，
                    // 与「固定 190dp 宽、只显示 90dp 内容」完全吻合。
                    // （源码里 3270 行那句注释写着「因为 LinearLayout 是 wrap_content」，
                    //  但代码并没有那么写 —— 注释与实现不一致，正是这个 bug 的藏身处。）
                    // 【code 924】双击关闭请求的接收器：按钮组首次创建时注册一次。
        //   放在这里而不是 hook(cl, repo)：hook() 拿不到 Activity，而注册需要它。
        registerDismissReceiver(activity, repo);
        // 【code 941】启动 SystemUI 作用域探测（幂等）—— 未授权时状态栏钮不显示。
        registerScopeWatch(activity);
        int btnMaxW = dip2px(activity, CAPSULE_W_DP * 2 + CAPSULE_GAP_DP);
                    int btnDefH = dip2px(activity, CAPSULE_H_DP);
                    int btnScreenW = activity.getResources().getDisplayMetrics().widthPixels;
                    int btnScreenH = activity.getResources().getDisplayMetrics().heightPixels;
                    // 【code 923 几何 3】右缘对齐滑条最右端（取不到滑条时退回常量）。
                    int btnWantRight = capsuleRightForSlider(activity, btnScreenW);
                    if (btnWantRight + btnMaxW > btnScreenW) {
                        btnWantRight = Math.max(0, btnScreenW - btnMaxW - dip2px(activity, 8));
                    }
                    // 【v57】初始位置也尽量用滑条推导（与 showButton 同口径），
                    // 避免「先出现在右下角、判为播放页后再跳到滑条上方」的闪跳
                    // （这正是 v42 修过的那类问题，只是坐标换了地方）。
                    int btnWantBottom = capsuleBottomForSlider(activity, btnScreenH);
                    boolean btnUsedFallback = false;
                    if (btnWantBottom <= 0) {
                        btnWantBottom = dip2px(activity, BUTTON_BOTTOM_DP);
                        btnUsedFallback = true;
                    }
                    if (btnWantBottom + btnDefH > btnScreenH) {
                        btnWantBottom = Math.max(0, btnScreenH - btnDefH - dip2px(activity, 8));
                    }
                    sCapsuleFollowY = btnWantBottom; // 记下来，showButton 的兜底链能用上
                    // 【code 927 问题 3】记下「建按钮时用的几何」。
                    //   若那时是兜底位（sLastSliderCy<=0），快照保持 MIN_VALUE 哨兵，
                    //   保证几何第一个到手时 replaceCapsuleByGeometry 一定会重算。
                    sCapsulePlacedByFallback = btnUsedFallback;
                    if (!btnUsedFallback && sLastSliderCy > 0) {
                        sPlacedGeoSliderCy = sLastSliderCy;
                        sPlacedGeoDescBottom = sLastDescBottomY;
                        sPlacedGeoSliderRight = sLastSliderRightPx;
                    } else {
                        sPlacedGeoSliderCy = Integer.MIN_VALUE;
                        sPlacedGeoDescBottom = Integer.MIN_VALUE;
                        sPlacedGeoSliderRight = Integer.MIN_VALUE;
                        XposedCompat.log(TAG + " [几何5] button created at FALLBACK bottom="
                                + btnWantBottom + "px (geometry not ready yet, sLastSliderCy="
                                + sLastSliderCy + ") -> will re-place");
                        // 【code 929 bug 4 诊断】FALLBACK 落位的同时记一次 page anchor 当前 y；
                        //   让真机复现时能直接看到第一次出现按钮的初始 translationY 到底落在哪
                        //   —— 如果它已经在锚点附近，catch-up 幅度 < 64px；如果还在屏幕边缘，
                        //   那就是 page follow 还没接管导致用户感觉"先闪一下"。
                        try {
                            int anchorY = -1;
                            if (sFollowRef != null) {
                                View ref = sFollowRef.get();
                                // 【code 930 清理】恢复 F4_diag 误改：参考点存活才读它的位移，
                                //   否则记 -1（catch-up 通道不可用，按钮第一帧在基准位露脸属预期）。
                                if (ref != null && ref.isAttachedToWindow()) {
                                    anchorY = pageVisualOffset();
                                }
                            }
                            XposedCompat.log(TAG + " [几何5b] FALLBACK button anchorY="
                                    + anchorY + " (catch-up effect depends on this)");
                        } catch (Throwable ignored) { }
                    }
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, btnDefH);
                    lp.gravity = Gravity.BOTTOM | Gravity.END;
                    lp.leftMargin = 0;
                    lp.topMargin = 0;
                    lp.rightMargin = btnWantRight;
                    lp.bottomMargin = btnWantBottom;
                    sButtonGroup.setLayoutParams(lp);
                    // v43：可见性**不再盲目沿用「上次结论」** —— 那个值可能是过期的 true
                    // （离开播放页时若抑制门把 hide 挡掉了，sLastDecision 会一直停在 true，
                    //  见 pageVisualOffset() 的 v43 复盘）。盲目沿用会让按钮在回到前台那一刻
                    // 凭空出现在首页/书架上，并且被永久抑制门一直摁住不消失。
                    //
                    // 新判据：只有「切后台前 ≤ RESUME_TRUST_MS 内**确实见到过播放页**」才敢直接
                    // 亮出来（常见场景：从播放页切出去又马上切回来 → 保持原体验、不闪）；
                    // 否则一律先 GONE，把「该不该显示」完全交给紧随其后的检测
                    // （scheduleDetect 在 80/240/520ms 各补一次；在播放页上锚点通常第一次
                    //  检测就被确认存活 → 立即 showButton，肉眼无感）。
                    // 【code 922 问题 2】判据从「时间窗内」收紧为「时间窗内 **且** 位移能同步」。
                    //
                    // 为什么必须加第二个条件：旧判据 `间隔 <= RESUME_TRUST_MS(1500ms)` 是**纯阈值**，
                    // 而实测该间隔的分布极散（19 / 47 / 86 / 147 / 178 / 292 / 348 / 394 /
                    // 1048 / 1373 / 1613 / 1697 / 2128 / 2685 / 2928 / 4505 / 6168ms），
                    // 同一个物理动作（从播放页切出去再切回来）会随机落在阈值两侧 →
                    // 表现就是用户说的「**有时**两个按钮会闪现」。
                    //
                    // 而「闪现」的直接成因是：以 VISIBLE 入场的那一刻
                    // {@link #syncFollowOffsetOnShow()} 因偏置缺失直接 return，
                    // 按钮先在 translationY=0 的基准位露脸，几十~几百 ms 后才跳到正确位。
                    // 所以只要**位移通道不可用，就不抢先显形** —— 交给紧随其后的检测
                    // （scheduleDetect 在 80/240/520ms 各补一次）在位置算得准之后再 show。
                    // 这样「闪现」的两种来源（阈值抖动 + 先露脸再跳）一并消失。
                    boolean canSyncOffset = sVisualSeen && sVisualBias != FOLLOW_NO_BASELINE;
                    trustLastPlayer = sPauseAtMs != 0L && sPausePlayerSeenMs != 0L
                            && (sPauseAtMs - sPausePlayerSeenMs) <= RESUME_TRUST_MS
                            && canSyncOffset;
                    sButtonGroup.setVisibility(trustLastPlayer ? View.VISIBLE : View.GONE);
                    sLastDecision = trustLastPlayer; // 不信任时不能让它冒充「已确认」
                    decor.addView(sButtonGroup);
                    freshButton = true;
                    sLastBtnSig = null; // 新按钮组创建后，强制 updateButtonText 重新落笔
                    sNotPlayerStreak = 0;
                    sNotPlayerSinceMs = 0L;
                    sLastEvidenceSig = null;  // 允许下一次扫描重新打一行证据日志
                    sLastAnchorAlive = null;  // 锚点状态未知，允许重新打一行
                    resetAnchorTracking();
                    sLastProbeAlive = null;
                    // v35：新按钮一律从「零位移 + 无基线」开始（旧按钮的位移不该继承）。
                    // v37：基线/参考点整体作废 —— 新按钮是个全新的参考系。
                    // 【code 922 问题 2】但**宿主偏置要留下**：它是宿主布局的常数，
                    // 与按钮新旧无关。不放行它 → 第一帧跟手空转 + 按钮先在基准位露脸再跳
                    // （= 用户说的「闪现」），见 invalidateFollow(boolean) 的完整复盘。
                    invalidateFollow(true);
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
                    XposedCompat.log(TAG + " button created, init visibility="
                            + sButtonGroup.getVisibility()
                            + " (trustLastPlayer=" + trustLastPlayer + " lastPlayerSeenAgo="
                            + ((sPauseAtMs == 0L || sPausePlayerSeenMs == 0L)
                                    ? -1 : (sPauseAtMs - sPausePlayerSeenMs)) + "ms)");
                }
            } catch (Throwable e) {
                XposedCompat.log(TAG + " ensureButton error: " + e.getMessage());
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
                sButtonGroup = null;
                sButton = null;
                sStatusBarButton = null;
                // v35：跟帧循环必须停 —— 按钮都没了，再跟就是纯浪费（而且 sButton==null
                // 时帧回调里的守卫虽会拦住，但循环会一直挂着不退）。
                // v37：按钮重建 = 旧的位移参考系整体失效 → 作废基线/参考点。
                // 【code 922 问题 2】宿主偏置保留（keepHostBias=true）—— 理由见
                // invalidateFollow(boolean)。这里若全量作废，下一次 onResume 的
                // 首帧跟手又会空转一次，等于白修。
                invalidateFollow(true);
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
                // v43：记下「切后台的时刻」与「那一刻最近一次见到播放页的时刻」，
                // 供 ensureButton 判断新按钮要不要直接以 VISIBLE 重建（见 RESUME_TRUST_MS）。
                // 必须在 sLastPlayerSeenMs 被任何路径改动之前取。
                sPauseAtMs = SystemClock.uptimeMillis();
                sPausePlayerSeenMs = sLastPlayerSeenMs;
                // 切后台后按钮被销毁，但 sLastBtnSig 是静态变量，必须重置。
                // 否则 onResume 重建按钮时 updateButtonText 会认为状态没变而跳过落笔，
                // 导致回到前台按钮只剩背景、没有文字。
                sLastBtnSig = null;
            } catch (Throwable e) {
                XposedCompat.log(TAG + " removeButton error: " + e.getMessage());
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
        if (sButtonGroup == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
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

        // ── v44：跟手期间**不做整树扫描**，把主线程让给跟帧循环 ──
        //
        // 拖动播放页时宿主每帧 setTranslationY → 结构事件被合并成 ~50ms 一趟，
        // 每趟一次整树扫描（实测单次 6~8ms）+ 2 行长日志，而跟帧回调与它**抢同一个主线程**。
        // 实测代价：`11:18:30.398` 写下 202px 后，下一帧直到 `11:18:30.508` 才来（**110ms**），
        // 那一窗里模块刚跑完 2 次扫描、写了 6 行日志 —— 这就是「偶尔不跟手」的真身。
        //
        // 拖动期间扫描**没有决策价值**：
        //   · 结论不可能是「离开播放页」—— 页面就在手指底下；真离开时锚点会 detach，
        //     走 removeView / attach 监听那条路，不靠这里的周期性扫描；
        //   · hide 本来就被 isPageHeld() 的「页面被拖住」判据压着；
        //   · 基线采集要求「页面静止」，拖动中本来就采不到。
        // 所以直接跳过，只把心跳按 FOLLOW_QUIET_HEARTBEAT_MS（120ms）续上 ——
        // 页面一停，本条件立刻不成立，完整扫描自动恢复（含「页面被卸载」的判定）。
        if (sFollowing && sLastPageMotionMs != 0L
                && now - sLastPageMotionMs < PAGE_MOTION_HOLD_MS) {
            sQuietScanSkips++;
            sNextHeartbeatMs = FOLLOW_QUIET_HEARTBEAT_MS;
            return;
        }
        if (sQuietScanSkips > 0) {
            sQuietScanSkips = 0; // 【code 933 精简】不再逐扫描打日志（170 行/会话）
        }

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
            // 【v57】缓存主滑条中心 y —— 按钮组要靠它算「进度条上方」的位置。
            // 只取这一个数（scan 的契约是「绝不跨帧保留」，所以不留整个对象）。
            //
            // ⚠️ **只在扫到滑条时才更新，扫不到就保持上次的值**（`-1` 是「从未扫到过」的
            //    哨兵，不是「本次没扫到」）。理由：这个赋值点同时服务「列表页 → 播放页」
            //    的判定链，列表页 / 切轨瞬间 / 转场动画中都可能 hasMainSlider=false。
            //    若那时打 -1，capsuleBottomForSlider 会返回 -1 → 按钮掉回
            //    BUTTON_BOTTOM_DP 兜底位 → **先跳一下再跳回来**（用户看到的闪跳）。
            //    保持上次值只是「位置短暂不精确」，远比跳一下好；真到了没有滑条的页面，
            //    hideButton 会把整个按钮组藏掉，位置根本不显示。
            //    （同类教训见 1.21.10：布局/几何做输入必须带迟滞，别让瞬时态直接驱动 UI。）
            //
            // 🔴🔴 【code 925】这里还必须再叠一层 `scan.mainSliderStable` ——
            //    code 924 把「稳定性判据」错做进了**探测**里（不稳定就 return），
            //    结果探测被掐死：hasMainSlider 永不置位 → 按钮完全不出现（P0）。
            //    现在稳定性判据回到**消费端**（也就是这里），探测负责如实上报。
            //    语义：不稳定 = 坐标可能是 RN settle 的中间态 → **不采信**，
            //    沿用上一次稳定值（下面 `if` 不成立即天然保持 sLastSliderCy 不变）。
            //    关键差别：这只影响「按钮摆在哪」，**不影响「按钮出不出现」**
            //    （出现与否由 verdict 决定，而 verdict 只看 hasMainSlider）。
            if (scan.hasMainSlider && scan.mainSliderY > 0 && scan.mainSliderStable) {
                sLastSliderCy = scan.mainSliderY;
                sLastSliderH = scan.mainSliderH;
            }
            // 【code 923 几何 3】滑条右缘 x（= 左缘 x + 宽度）—— 按钮右缘要跟它对齐。
            if (scan.hasMainSlider && scan.mainSliderW > 0 && scan.mainSliderX >= 0) {
                sLastSliderRightPx = scan.mainSliderX + scan.mainSliderW;
            }
            // 【code 923 几何 4】简介行底边 y —— 与滑条中心一起决定按钮的垂直中点。
            if (scan.descLineBottom > 0) {
                sLastDescBottomY = scan.descLineBottom;
            }
            // 【code 924】诊断：为什么中点不生效？三种可能各对应一个不同的数字——
            //   ① descCandidateCount == 0  -> 收集条件把简介行整个漏了（高度 / 文本条件）
            //   ② descCandidateCount > 0 但 descLineBottom == -1
            //                              -> 距离上限太紧或滑条顶边算错
            //   ③ descLineBottom > 0 但按钮仍贴滑条 -> 是消费端（capsuleBottomForSlider）的事
            // 计数式日志：只在数字变了时打，避免 55ms 一行。
            if (scan.descCandidateCount != sLastDescCand
                    || scan.descLineBottom != sLastDescBottomLogged) {
                sLastDescCand = scan.descCandidateCount;
                sLastDescBottomLogged = scan.descLineBottom;
                // 【code 933 精简】desc probe 诊断日志移除（103 行/会话）；
                //   落位是否生效改看低频的 [几何4] capsuleBottom / [几何6]。
            }

            int screenW = decor.getWidth() > 0
                    ? decor.getWidth()
                    : decor.getResources().getDisplayMetrics().widthPixels;
            int screenH = decor.getHeight() > 0
                    ? decor.getHeight()
                    : decor.getResources().getDisplayMetrics().heightPixels;
            sLastScreenW = screenW;
            sLastScreenH = screenH;

            // 🔴🔴 【code 927 问题 3】几何更新点**独立触发**按钮重落位。
            //
            //   这是本轮问题 3 的核心修法。真机根因：位置重算只挂在 showButton 里，
            //   而 showButton 依赖的 sLastSliderCy 又是**同一轮 scan** 才更新的
            //   ⇒ 自引用、永远差一拍：ensureButton 落 286px 兜底位后，
            //     等 sLastSliderCy 真的到手时，showButton 早就不跑了 ⇒ 位置永不纠正。
            //   现在：几何（sLastSliderCy / sLastDescBottomY / sLastSliderRightPx）
            //   一更新就在这里重算并落地，与 showButton 的调用时机彻底解耦。
            //   必须先于下面 showButton 的判定调用，这样同一帧里 showButton 也能看到新值。
            replaceCapsuleByGeometry(activity, screenW, screenH, false);

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
                    // 【code 933 精简】不再打视图树 dump（每次最多 260 行，纯结构参考）。
                    sTreeDumped = true;
                    // 更新播放页锚点（页面级容器），供主滑条被回收时继续判定「还在播放页」。
                    if (scan.anchorRef != null) {
                        sPlayerAnchor = scan.anchorRef;
                        sAnchorDetached = false; // 锚点在播放页里被重新确认 → 清掉「已摘除」的硬证据
                        watchAnchorAttachment(scan.anchorRef.get());
                        if (!scan.anchorDesc.equals(sLastAnchorDesc)) {
                            sLastAnchorDesc = scan.anchorDesc;
                            XposedCompat.log(TAG + " player anchor -> " + scan.anchorDesc);
                        }
                    }
                    // v35：确认在播放页 → 顺手把「播放键」认下来，并在页面静止时采集跟随基线。
                    // 只在页面静止（跟帧循环没跑 + 位移为 0）时才采 —— 见 captureFollowBaseline()。
                    captureFollowBaseline();
                    sLastDecision = Boolean.TRUE;
                    sPlayerConfirmedInSession = true; // v43：本会话确认过播放页 → 抑制门解闸
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
                        XposedCompat.log(TAG + " player anchor adopted (unpaired main slider) -> "
                                + scan.anchorDesc);
                    }
                    if (sAnchorDetached) {
                        // v34 硬证据：锚点已**从窗口摘除**（视图都没了）→ 一定不在播放页。
                        // 这是「离开播放页」最干净的一条信号，不必再等 ANCHOR_DEAD_MIN_MS 的确认窗
                        // —— 确认窗本来就是为了防「页面假死又滑回来」，而视图被摘掉不会滑回来。
                        if (Boolean.TRUE.equals(sLastDecision)) {
                            XposedCompat.log(TAG + " anchor detached from window -> hide (hard evidence)");
                        }
                        sLastDecision = Boolean.FALSE;
                        hideButton("anchor detached");
                        break;
                    }
                    boolean anchorAlive = isPlayerAnchorAlive();
                    // 【code 948】面积比在这里一次算好：原版只在「存活状态翻转」时才算一次，
                    //   下面判死路径拿不到它 —— 而它正是本轮「按钮滞后 0.75s」的解法所需
                    //   （见 ANCHOR_DEAD_GONE_MS 的完整推导）。
                    float anchorRatio = anchorAreaRatio(screenW, screenH);
                    boolean anchorGone = anchorRatio <= 0f;
                    if (sLastAnchorAlive == null || sLastAnchorAlive != anchorAlive) {
                        sLastAnchorAlive = anchorAlive;
                        XposedCompat.log(TAG + " player anchor alive=" + anchorAlive
                                + " (area=" + Math.round(anchorRatio * 100) + "%)");
                    }
                    if (anchorAlive) {
                        // 页面级容器还在屏幕上 → 仍在播放页（主滑条只是被 RN 回收 / 控制条隐藏）。
                        // v25：**立即恢复显示**，不再傻等下一次扫到主滑条（那要 ~0.6s）。
                        sLastPlayerSeenMs = now;
                        sPlayerConfirmedInSession = true; // v43：锚点存活也是播放页证据
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
                                XposedCompat.log(TAG + " UNKNOWN + anchor alive for "
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
                                XposedCompat.log(TAG + " no player evidence for "
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
                        // 【code 949】归零档前置门：面积**严格归零** 且**页面已静止**。
                        //   948 只要求面积归零（need=120ms），真机复测仍残留 100~200ms ——
                        //   因为首次采样与「页面消失」同帧，而 120ms 的 need 逼它再等一个探针周期。
                        //   补上「页面已静止」这条门之后 need 才能取 0：首次采样即收起，
                        //   而 fling / 拖动中（页面仍在动）依旧被这条门与 isPageHeld 挡住。
                        boolean goneStill = anchorGone
                                && (sLastPageMotionMs == 0L
                                    || now - sLastPageMotionMs >= ANCHOR_DEAD_GONE_STILL_MS);
                        // 【code 948/949】三档确认窗：正面证据 200ms < **归零且已静止 0ms** < 默认 600ms。
                        //   归零档取最短 —— 它是最强的一条信号（屏幕上真的一个像素都不剩），
                        //   而 600ms 那条防假死窗对它毫无意义（假死时容器仍部分可见）。
                        long need = evidence ? ANCHOR_DEAD_WITH_EVIDENCE_MS
                                : (goneStill ? ANCHOR_DEAD_GONE_MS : ANCHOR_DEAD_MIN_MS);
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
                            XposedCompat.log(TAG + " anchor dead for " + deadFor + "ms"
                                    + " (area=" + Math.round(anchorAreaRatio(screenW, screenH) * 100) + "%"
                                    + " evidence=" + evidence + " need=" + need + "ms"
                                    + " gone=" + anchorGone + " goneStill=" + goneStill
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
                                XposedCompat.log(TAG + " hide suppressed: page held (vis="
                                        + sVisualOffset + "px ref="
                                        + (sFollowRefIsPlayBtn ? "play-button" : "anchor")
                                        + " snap=" + sHeldOffset + "px/"
                                        + (sHeldOffsetMs == 0L ? -1 : (now - sHeldOffsetMs))
                                        + "ms attached=" + holdProbeStillAttached() + ")");
                            }
                            break;
                        }
                        // 【code 949】归零且已静止时只认 1 次采样（首次检测即收起）；
                        //   其余两档维持「连续 2 次采样」去抖不变。
                        int needSamples = goneStill ? 1 : ANCHOR_DEAD_MIN_SAMPLES;
                        if (deadFor >= need && sAnchorDeadSamples >= needSamples) {
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedCompat.log(TAG + " no player evidence for "
                                        + (sLastPlayerSeenMs == 0L ? -1 : (now - sLastPlayerSeenMs))
                                        + "ms (anchor dead " + deadFor + "ms evidence=" + evidence
                                        + " gone=" + anchorGone
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
                XposedCompat.log(TAG + " verdict=" + SubtitleViewHook.verdictName(verdict)
                        + " | " + scan.describe(screenW, screenH));
            }

            repo.setPlayerPageVisible(Boolean.TRUE.equals(sLastDecision));
        } catch (Throwable e) {
            XposedCompat.log(TAG + " detectAndLayout error: " + e.getMessage());
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

        boolean buttonVisible = sButtonGroup != null
                && sButtonGroup.getVisibility() == View.VISIBLE;
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

    /**
     * 【v57】按主滑条几何算按钮组底边距屏幕底的量（px）。
     *
     * 需求：「按键位置是在音轨大标题和播放进度条中间靠右侧」。
     * 滑条中心 y（屏幕坐标）往上 {@link #CAPSULE_ABOVE_SLIDER_DP} dp 处作为
     * 按钮组的**底边** —— 于是按钮组正好坐在滑条上方、不压滑条、也不碰上方简介。
     *
     * ── 取不到滑条时怎么办（必须回答，否则会有机型/页面结构没有滑条）──
     * 返回 -1，由调用方沿用上次成功的值；再不行才退回旧版常量右下角
     * （{@link #BUTTON_BOTTOM_DP}，v32 验证过「不遮挡任何宿主控件」的安全位）。
     * 宁可位置不对也不能没有位置。
     *
     * @return 底边距（px）；-1 = 本次取不到滑条
     */
    /**
     * 【code 935 bug2】把自适应后的胶囊高度落到两个按钮上（只在变化时写，避免布局回环）。
     */
    private static void applyCapsuleHeight() {
        if (sCapsuleHPx <= 0) {
            return;
        }
        try {
            if (sStatusBarButton != null) {
                ViewGroup.LayoutParams p = sStatusBarButton.getLayoutParams();
                if (p != null && p.height != sCapsuleHPx) {
                    p.height = sCapsuleHPx;
                    sStatusBarButton.setLayoutParams(p);
                }
            }
            if (sButton != null) {
                ViewGroup.LayoutParams p = sButton.getLayoutParams();
                if (p != null && p.height != sCapsuleHPx) {
                    p.height = sCapsuleHPx;
                    sButton.setLayoutParams(p);
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " applyCapsuleHeight failed: " + t);
        }
    }

    private static int capsuleBottomForSlider(Context ctx, int screenH) {
        if (screenH <= 0 || sLastSliderCy <= 0) {
            return -1;
        }
        int gapPx = dip2px(ctx, CAPSULE_ABOVE_SLIDER_DP);
        int capsuleH = dip2px(ctx, CAPSULE_H_DP);
        // 【code 923 几何 4】**垂直居中**于「简介行底边」与「滑条中心」之间 ——
        // 需求原话：「在一行淡色小字简介和播放进度条中间（垂直距离中间，不再用固定距离）」。
        //   按钮组中心 y = (简介行底边 + 滑条中心) / 2
        //   底边屏幕 y   = 中心 + 半高
        //   bottomMargin = screenH - 底边屏幕 y
        // ⚠️ 取不到简介行时退回旧口径（底边落在「滑条中心 - gap」）——
        //    位置不精确可以接受，**因为没位置而跳一下**不可以（1.21.10 的教训）。
        int centerY;
        boolean usedMid;
        // 【code 937 问题2】两个诊断量提到块外：日志要打「去抖后的简介底边」与
        //   「滑条视图顶边」，否则无法判断按钮上沿有没有压到简介行。
        int descStable = -1;
        int sliderTop = -1;
        // 【code 924】几何 3 的可观测性：code 923 落地后**无法从日志判断走到哪一支**
        //   （实测真机截图里按钮仍贴着滑条，但日志里 descBottom 一个字都没有）
        //   -> 这里把三要素全打出来，并**计数**（一次日志查不出「偶发 vs 恒常」）。
        // 【code 934 bug3】按钮压简介根修：sliderCy 是滑条**中心**（SubtitleViewHook
        //   cy = loc[1]+h/2，实测 h=54px）。简介底边(1722)到滑条视图顶部(1772)只有
        //   50px，装不下 95px 整高按钮 -> 旧条件 gap>capsuleH 不成立 -> 走 LEGACY
        //   整高贴 cy-32dp-半高 -> 按钮占 1610~1704、整个压进简介区（截图实证）。
        //   新规则：简介行有效时一律垂直居中于「简介底边 <-> 滑条视图顶部」——
        //   即需求原话「简介和进度条中间」；两侧 view 的内边距区吸收少量重叠。
        if (sLastDescBottomY > 0) {
            // 【code 936 bug2】简介底边在真机上于多个候选间**逐帧翻转**
            //   （00:30 日志实证 1722 <-> 1759，差 37px）-> centreY 跟着每秒抖
            //   十几次 18px。此处做**消费端**去抖（探测层按铁律只如实上报）：
            //   近距离抖动一律收敛到「更靠上」的候选（可用带更宽、离进度条更远），
            //   只有明显位移（> DESC_HYSTERESIS_DP）才认作真的换行并跟随。
            descStable = sLastDescBottomY;
            if (sDescBottomStable > 0
                    && Math.abs(descStable - sDescBottomStable)
                            <= dip2px(ctx, DESC_HYSTERESIS_DP)) {
                descStable = Math.min(descStable, sDescBottomStable);
            }
            sDescBottomStable = descStable;

            int seekHalf = sLastSliderH > 0 ? sLastSliderH / 2 : dip2px(ctx, 9);
            sliderTop = sLastSliderCy - seekHalf;
            // 【code 936 bug2】Ari 明确要求：按钮**恒定 32dp**、无论可用带多窄都强制
            //   居中于「简介底边 <-> 滑条视图顶边」，**不做任何自适应压缩** —— 935 的
            //   自适应把 95px 压到 60px（日志实证 capsuleH=60），观感就是「按钮被压扁」。
            //   实测本页可用带仅 50px（desc=1722 / sliderTop=1772）< 32dp=95px，故剩余
            //   45px 溢出由上下两侧的 view 内边距区均摊（各约 22px）。
            capsuleH = dip2px(ctx, CAPSULE_H_DP);
            sCapsuleHPx = capsuleH;
            // 【code 937 问题2 根修（Ari 指令）；938 改为 - 20dp】底边强制 = 滑条视图顶边 - 20dp。
            //   936 的旧口径「简介底边(1722) <-> 滑条顶边(1772) 取中点」⇒ centerY=1747、
            //   底边 1794 —— 比滑条顶 1772 **还低 23px**，按钮直接压进进度条，
            //   正是 Ari 反馈的「按钮太低了、完全靠近播放进度条」。
            //   新口径与简介底边解耦，位置只跟滑条走（也顺手甩掉了 descBottom 逐帧翻转的影响）。
            centerY = sliderTop - dip2px(ctx, CAPSULE_ABOVE_SLIDER_TOP_DP) - capsuleH / 2;
            usedMid = true;
        } else {
            capsuleH = dip2px(ctx, CAPSULE_H_DP);
            sCapsuleHPx = capsuleH;
            centerY = sLastSliderCy - gapPx - capsuleH / 2;
            usedMid = false;
        }
        // 【code 932 bug4】滑条最小净距钳制。实测（18:06 日志）无字幕时 desc=2017 /
        // slider=2179，MID 居中后按钮底边距滑条中心仅 ~34px -> 按钮怼到进度条顶上。
        // 规则：按钮底边距滑条中心不得小于 SLIDER_MIN_CLEAR_DP，不够就整体上移。
        boolean clamped = false;
        // 【code 934】净距钳制只对 LEGACY 分支生效：MID 已按「滑条视图顶部」对齐，
        //   再套 minClear(20dp、相对滑条中心) 会把按钮重新顶回简介区
        //   （932 钳制的副作用，正是本轮「按钮挡简介」的推手之一）。
        if (!usedMid) {
            int minClear = dip2px(ctx, SLIDER_MIN_CLEAR_DP);
            if (centerY + capsuleH / 2 > sLastSliderCy - minClear) {
                centerY = sLastSliderCy - minClear - capsuleH / 2;
                clamped = true;
            }
        }
        int bottomMargin = screenH - (centerY + capsuleH / 2);
        // 【code 927 问题 3】打点门改为**调用即打 + 节流**。
        //   旧门「结果变了才打」在诊断时无法区分「压根没调用」与「调用了但没变」——
        //   上一轮真机上这行一次都没出现，被误读成「几何不可用」，实际是几何早就好了、
        //   只是**没人再调用**（自引用差一拍）。每 60 次汇总一行保证不刷爆日志。
        sCbCallCount++;
        if (usedMid != sLastMidUsed || centerY != sLastMidCenterY || sCbCallCount % 60 == 0) {
            sLastMidUsed = usedMid;
            sLastMidCenterY = centerY;
            XposedCompat.log(TAG + " [几何4] capsuleBottom"
                    + " descBottom=" + sLastDescBottomY
                    + " descStable=" + descStable
                    + " sliderCy=" + sLastSliderCy
                    + " sliderTop=" + sliderTop
                    + " capsuleH=" + capsuleH
                    + " centerY=" + centerY
                    + " branch=" + (usedMid ? "SLIDER_TOP-25dp" : "LEGACY(gap)")
                    + " density=" + sLockedDensity
                    + (clamped ? " CLAMPED" : "")
                    + " bottomMargin=" + bottomMargin
                    + " calls=" + sCbCallCount);
        }
        // 安全钳制：别把按钮推到屏幕外（分屏 / 极矮屏 / 滑条贴顶）。
        int minBottom = dip2px(ctx, 8);
        int maxBottom = Math.max(minBottom, screenH - capsuleH - dip2px(ctx, 8));
        if (bottomMargin < minBottom) {
            bottomMargin = minBottom;
        }
        if (bottomMargin > maxBottom) {
            bottomMargin = maxBottom;
        }
        return bottomMargin;
    }

    /**
     * 【code 923 几何 3】按钮组**右缘**对齐主滑条**右缘**。
     *
     * 需求原话：「按钮距离屏幕右缘改为和进度条最右端距离屏幕右缘一致」。
     * decor 是全屏宽，所以 {@code rightMargin = screenW - 滑条右缘x} 即可让两者
     * 距屏右的距离**逐像素相等**。
     *
     * 取不到滑条（列表页 / 转场瞬间）时退回常量 {@link #BUTTON_RIGHT_DP} ——
     * 与 {@link #capsuleBottomForSlider} 同一套「宁可不精确、不要跳」的取舍。
     */
    private static int capsuleRightForSlider(Context ctx, int screenW) {
        if (screenW > 0 && sLastSliderRightPx > 0 && sLastSliderRightPx < screenW) {
            int m = screenW - sLastSliderRightPx;
            //  sanity：滑条右缘不可能贴着屏幕左边，超过 1/3 屏宽一定是量错了。
            if (m >= 0 && m <= screenW / 3) {
                return m;
            }
        }
        return dip2px(ctx, BUTTON_RIGHT_DP);
    }

    /**
     * 【code 927 问题 3】几何一变就重算按钮位置并落地 —— 与 showButton 调用时机**解耦**。
     *
     * 为什么必须独立（真机根因）：
     *   位置重算原本只挂在 showButton/ensureButton 里，而 showButton 依赖的
     *   `sLastSliderCy` 又是**同一轮 scan** 才更新的 ⇒ 自引用、永远差一拍：
     *     ensureButton 时 sLastSliderCy=-1 → 落 286px 兜底位；
     *     等 sLastSliderCy 变成 1799 时 showButton 已经不跑了 → 位置再也不纠正。
     *
     * 本方法由**几何更新点**直接调用：只要几何真的变了（快照比对），就把新位置
     * 写进 LayoutParams —— 不管按钮当前可见与否（不可见时写位置也无害，
     * 反而让下次 showButton 一露脸就在正确位置，顺带消掉「闪跳」）。
     *
     * @param force 忽略快照无条件重算（ensureButton 建好按钮、或几何首次到手时用）
     */
    private static void replaceCapsuleByGeometry(Activity activity, int screenW, int screenH,
                                                 boolean force) {
        if (activity == null || sButtonGroup == null || screenH <= 0) {
            return;
        }
        if (sLastSliderCy <= 0) {
            return;   // 几何还没到手：保持现状（兜底位），等下一次几何更新再来
        }
        if (!force
                && sLastSliderCy == sPlacedGeoSliderCy
                && sLastDescBottomY == sPlacedGeoDescBottom
                && sLastSliderRightPx == sPlacedGeoSliderRight) {
            return;   // 几何没变：不折腾（避免每帧 setLayoutParams 造成布局回环）
        }
        try {
            int btnW = sButtonGroup.getWidth() > 0 ? sButtonGroup.getWidth()
                    : dip2px(activity, CAPSULE_W_DP * 2 + CAPSULE_GAP_DP);
            int wantRight = capsuleRightForSlider(activity, screenW);
            if (wantRight + btnW > screenW) {
                wantRight = Math.max(0, screenW - btnW - dip2px(activity, 8));
            }
            int bySlider = capsuleBottomForSlider(activity, screenH);
            applyCapsuleHeight();
            if (bySlider <= 0) {
                return;
            }
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) sButtonGroup.getLayoutParams();
            if (lp == null) {
                return;
            }
            int oldRight = lp.rightMargin;
            int oldBottom = lp.bottomMargin;
            int oldG = lp.gravity;
            sCapsuleFollowY = bySlider;
            sCapsulePlacedByFallback = false;
            sPlacedGeoSliderCy = sLastSliderCy;
            sPlacedGeoDescBottom = sLastDescBottomY;
            sPlacedGeoSliderRight = sLastSliderRightPx;
            if (force || oldG != (Gravity.BOTTOM | Gravity.END) || oldRight != wantRight
                    || Math.abs(oldBottom - bySlider) > CAPSULE_FOLLOW_DEADZONE_PX) {
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.leftMargin = 0;
                lp.topMargin = 0;
                lp.rightMargin = wantRight;
                lp.bottomMargin = bySlider;
                sButtonGroup.setLayoutParams(lp);
                XposedCompat.log(TAG + " [几何6] capsule re-placed by geometry -> bottom="
                        + bySlider + "px right=" + wantRight
                        + "px (descBottom=" + sLastDescBottomY
                        + " sliderCy=" + sLastSliderCy + " force=" + force + ")");
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " replaceCapsuleByGeometry failed: " + t);
        }
    }

    private static void showButton(Activity activity, SubtitleRepository repo,
                                   int screenW, int screenH) {
        int btnW = sButtonGroup.getWidth() > 0 ? sButtonGroup.getWidth()
                : dip2px(activity, CAPSULE_W_DP * 2 + CAPSULE_GAP_DP);
        int btnH = sButtonGroup.getHeight() > 0 ? sButtonGroup.getHeight()
                : dip2px(activity, CAPSULE_H_DP);

        // ── 【v57】位置：主滑条上方靠右（需求）──
        // 类头 v32「位置必须是常量」的结论在**新位置**下不再适用：新位置本身就绑在
        // 滑条上，位置必然随滑条变。防布局回环的手段改为**迟滞 + 只在真变化时写**，
        // 而不是「位置写死」—— 见 patch 头部两层设计说明。
        // 【code 923 几何 3】与 ensureButton 同口径：右缘对齐滑条最右端。
        int wantRight = capsuleRightForSlider(activity, screenW);
        // 安全钳制：极窄 / 极矮屏（分屏、平板、异常 density）下别把按钮顶出可视区。
        if (wantRight + btnW > screenW) {
            wantRight = Math.max(0, screenW - btnW - dip2px(activity, 8));
        }
        // 【v57】底边由滑条推导；取不到滑条时先用上次值，再不行退回 v32 的常量安全位。
        int bySlider = capsuleBottomForSlider(activity, screenH);
        applyCapsuleHeight();
        if (bySlider > 0) {
            sCapsuleFollowY = bySlider;
        }
        int wantBottom = sCapsuleFollowY > 0 ? sCapsuleFollowY : dip2px(activity, BUTTON_BOTTOM_DP);
        if (wantBottom + btnH > screenH) {
            wantBottom = Math.max(0, screenH - btnH - dip2px(activity, 8));
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sButtonGroup.getLayoutParams();
        int wantGravity = Gravity.BOTTOM | Gravity.END;
        // 只在真的变化时才 setLayoutParams —— setLayoutParams 必然 requestLayout，
        // 在 onGlobalLayout 里无条件调用会形成永不停止的布局回环（卡顿主因）。
        // 【v57】滑条位置在页面滚动时变化（实测 y 在 1799~2243 摆），所以这里必然比
        //        旧版更频繁地命中。用**迟滞阈值**兜住逐帧微抖（1.21.10 的教训：
        //        逐帧动画会灌爆「相等才跳过」的缓存）。
        if (lp.gravity != wantGravity
                || lp.leftMargin != 0
                || lp.topMargin != 0
                || lp.rightMargin != wantRight
                || Math.abs(lp.bottomMargin - wantBottom) > CAPSULE_FOLLOW_DEADZONE_PX) {
            lp.gravity = wantGravity;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = wantRight;
            lp.bottomMargin = wantBottom;
            sButtonGroup.setLayoutParams(lp);
        }

        if (sButtonGroup.getVisibility() != View.VISIBLE) {
            // v39：**先把位移同步到「页面当前被拖开的量」，再显示**。
            // hideButton() 会 stopPageFollow(true) → 位移清零。若不同步就显示，
            // 按钮会先在静止位露脸、几十毫秒后再跳到几百 px 外 ——
            // 实测 23:34:07 显示后 62ms 才补上 448px、23:35:33 显示后 62ms 补 345px，
            // 那一瞬间就是用户看到的「位移闪跳」。
            syncFollowOffsetOnShow();
            sButtonGroup.setVisibility(View.VISIBLE);
            updateButtonText(repo);
            XposedCompat.log(TAG + " button shown (player page)" + latencySuffix());
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
        ensureHostBias(); // 【code 923 bug1】先把可能被清掉的宿主偏置补回来
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
        if (sButtonGroup != null && sButtonGroup.getVisibility() != View.GONE) {
            sButtonGroup.setVisibility(View.GONE);
            XposedCompat.log(TAG + " capsule group hidden (" + reason + ")" + latencySuffix());
        }
    }

    /**
     * 【v57】创建**胶囊按钮组**：一个横向 LinearLayout 包着两个独立胶囊。
     *
     * 与旧 createButton 的差别：
     *   · 返回的是容器（旧版返回单个 TextView）；
     *   · 两个按钮**各自点击**，没有长按（需求：「均通过点击触发」「取消长按」）；
     *   · 圆角从 8px 圆角矩形改成 h/2 全圆角（MD3 胶囊的形态特征）；
     *   · 容器 id 用 BUTTON_ID，两个子按钮各用独立 id 便于 findViewById 找到。
     *
     * 返回值即容器；{@link #sButton}（悬浮窗钮）与 {@link #sStatusBarButton}（状态栏钮）
     * 由本方法内部赋值，调用方无需自己去捞。
     */
    private static LinearLayout createButtonGroup(Context ctx, SubtitleRepository repo) {
        LinearLayout group = new LinearLayout(ctx);
        sDensityPx = stableDensity(ctx); // 【code 939】走锁定值，避免转场伪 density
        group.setId(BUTTON_ID);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        // 容器不设背景、不设 padding —— 视觉全交给两个胶囊自己。
        // ⚠️ 容器必须 clickable=false + 不拦截触摸，否则两个子按钮收不到事件。
        group.setClickable(false);

        // ── 左：状态栏字幕 ──
        sStatusBarButton = createCapsule(ctx, CAPSULE_ID_STATUSBAR);
        // 【code 932 bug2】恢复单击开关（931 改双击是理解错了 Ari 的反馈，她明确要求
        // 浮窗按钮保持单击；「双击关」只属于旧的状态栏字幕行交互，而那功能 932 也整体移除了）。
        sStatusBarButton.setOnClickListener(v -> {
            // 【v57】点击直接翻转状态栏字幕开关（旧版是长按 1s，现已取消）。
            // ⚠️ 无字幕时本按钮已被 GONE，正常点不到；这里再做一次防御性判空，
            //    防止「GONE 的瞬间正好有一次点击落在上面」。
            if (!StatusBarSubtitleBridge.canToggle(repo)) {
                XposedCompat.log(TAG + " statusbar toggle ignored: no subtitles");
                return;
            }
            boolean on = StatusBarSubtitleBridge.toggleAppEnabled(ctx);
            StatusBarSubtitleBridge.resendCurrentFromRepo(ctx, repo); // 翻转后立即重推，状态栏即时刷新
            XposedCompat.log(TAG + " status bar subtitle " + (on ? "ON" : "OFF") + " (click)");
            // toggleAppEnabled 内部会 notifyEnabledChanged() -> 已注册的监听会刷新按钮，
            // 这里再主动落一次笔作为冗余保险（与旧版长按路径一致的做法）。
            uiHandler.post(() -> applyButtonText(repo, true));
        });

        LinearLayout.LayoutParams lpStatus = new LinearLayout.LayoutParams(
                dip2px(ctx, CAPSULE_W_DP), dip2px(ctx, CAPSULE_H_DP));
        lpStatus.rightMargin = dip2px(ctx, CAPSULE_GAP_DP);
        group.addView(sStatusBarButton, lpStatus);

        // ── 右：悬浮窗字幕（沿用 sButton，让既有 57 处引用不改语义）──
        sButton = createCapsule(ctx, CAPSULE_ID_FLOATING);
        sButton.setOnClickListener(v -> {
            // 【1.21.16 问题 1】口径统一到 shouldShowNoSubtitles()：它同时覆盖
            // 「真没 cues」「换轨待确认」「软裁决（cues 还在但本音轨没等到 JSON）」。
            // 旧写法只看 hasSubtitles()（= cues 非空），软裁决下会**放行**一次
            // 毫无意义的 toggle —— 而硬裁决下 cues 被清，又变成静默吞点击，
            // 用户只能看到按钮死了却不知道为什么。
            if (repo.shouldShowNoSubtitles()) {
                XposedCompat.log(TAG + " no subtitles available"
                        + " (softNoSub=" + repo.isSoftNoSubtitles()
                        + ", suspended=" + repo.isSuspended()
                        + ", hasCues=" + repo.hasSubtitles() + ")");
                return;
            }
            // 注意：这里不再用 Settings.canDrawOverlays() 拦截。
            // OPPO/ColorOS 上该 API 即使用户已授予悬浮窗权限也返回 false，会导致点击后
            // 每次都跳权限页且无法开启。改为直接 toggle，由 FloatingWindowManager 真实
            // addView；只有真正抛异常（确实没权限）时才提示一次去授权。
            repo.toggleFloatingWindow();
            updateButtonDrawable(repo);
        });

        LinearLayout.LayoutParams lpFloat = new LinearLayout.LayoutParams(
                dip2px(ctx, CAPSULE_W_DP), dip2px(ctx, CAPSULE_H_DP));
        group.addView(sButton, lpFloat);

        // 【v57】长按逻辑整体删除 —— 需求确认「取消长按，只保留点击」。
        // 旧版这里是 tv.setOnTouchListener + 1s postDelayed，两个副作用：
        //   ① 长按会被系统当作「长按」而产生触感反馈，与新交互无关；
        //   ② sLongPressFired 需要在 ACTION_UP 里回吞 click，状态机容易在
        //      ACTION_CANCEL（手指滑出）时留下脏值。删掉后两个问题一起消失。
        return group;
    }

    /**
     * 【v57】创建一个胶囊按钮（MD3 filled button 的胶囊形态）。
     *
     * 关键视觉属性（与设计稿逐项对应）：
     *   · 底色由「开/关」决定：{@link #CAPSULE_BG_OFF}（#212042）/ {@link #CAPSULE_BG_ON}（#584179）；
     *   · **全圆角** = 高/2 —— 这是胶囊与「圆角矩形」的分界，旧版 8px 圆角必须换掉；
     *   · 无描边（旧版有 1px 半透明白描边，新设计是纯填充，去掉更贴近 MD3 filled）；
     *   · 文字 13sp / 白色 / 居中 / **强制粗体**。
     *     【code 922 问题 4】这里原先按 MD3 filled button 的规范写「不假粗体」，
     *     但实机 13sp 在 90dp 宽的胶囊里笔画偏细、小字号下辨识度不足，
     *     需求明确要求强制粗体 -> 改用 {@code Typeface.DEFAULT_BOLD}。
     */
    /**
     * 【v57】从容器里把两个胶囊的引用捞回来（幂等）。
     *
     * 调用点：{@link #ensureButton}（容器已在树上时的复用分支）。
     * 为什么必须做：见调用点注释 —— 不捞就会拿到 null 或悬空引用，
     * 且**症状是静默的**（按钮不更新、无异常）。静默失败是最难查的一类。
     */
    private static void syncCapsuleRefsFromGroup() {
        if (sButtonGroup == null) {
            return;
        }
        if (sButton == null) {
            sButton = sButtonGroup.findViewById(CAPSULE_ID_FLOATING);
        }
        if (sStatusBarButton == null) {
            sStatusBarButton = sButtonGroup.findViewById(CAPSULE_ID_STATUSBAR);
        }
    }

    private static TextView createCapsule(Context ctx, int id) {
        TextView tv = new TextView(ctx);
        tv.setId(id);
        tv.setTextSize(CAPSULE_TEXT_SP);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        // 【code 922 问题 4】需求「按钮内文字强制使用粗体显示」——
        // NORMAL -> BOLD。用 DEFAULT_BOLD 而不是 (null, BOLD)：前者不依赖当前 typeface
        // 的样式位，且**不会被任何后续 setTypeface(null, ...) 覆盖掉粗体位**
        // （更新按钮文字的各条路径都只 setText，不碰 typeface）。
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setClickable(true);
        tv.setFocusable(true);
        // 保证最小可点区域符合无障碍建议（48dp 高不满足时靠 touch delegate 会复杂，
        // 这里 35dp 高度 + 90dp 宽度，实际可点面积足够，不再额外处理）。
        tv.setBackground(createCapsuleDrawable(false));
        return tv;
    }

    /**
     * 【1.21.13 问题 2】按钮底色的**唯一**口径。
     *
     * 之前底色与文字各算各的（底色直接读 {@link StatusBarSubtitleBridge#sAppEnabled}，
     * 文字读仓库），才留下「文字已变成无字幕、底色还是绿色」的错位。
     * 现在文字、alpha、底色三者同出一源：{@link #applyButtonText} 用本函数算 bgMode，
     * {@link #updateButtonDrawable} 也用它 —— 谁都不可能再算歪。
     *
     * ── v54（1.21.14 问题 1）：绿色还要**看当下有没有字幕** ──
     *
     * 1.21.13 把「谁会重画」修好了，但绿色**什么时候**消失还挂在开关真源上：
     * 文字走 {@code noSub}（含 {@link #BUTTON_NO_SUB_DELAY_MS} 预压缩）→ ~0.5s 变「无字幕」；
     * 绿色却要等 {@code NO_SUBTITLE_GRACE_MS}(3000ms) 裁决完、{@code forceDisabledWhenNoSubtitles()}
     * 把 {@code sAppEnabled} 翻成 false 才没（日志实证 16:09:24.662 换轨 SUSPEND →
     * 16:09:27.664 裁决 = 3002ms）。同一块按钮上两条口径差 2.5s ——
     * 用户看到的就是「字很及时、色慢三秒」。
     *
     * 当时只调了**显示**口径：没有字幕可显示时，不管开关开没开都不算绿；
     * 而 {@code sAppEnabled} 的持久状态照旧等 3000ms 数据裁决 —— 两条线互不干扰。
     *
     * ── v56（1.21.15 问题 1）：force-off 整个删掉，底色口径只剩一条 ──
     *
     * 1.21.14 保留 force-off，等于承认「开关会被数据裁决改写」。可那条路径本身就是
     * 问题 1 的根因：切轨时把用户长按开的开关自己关掉，且无法自动恢复。
     * 现在 {@code sAppEnabled} 是**纯用户意图**（只由长按翻转），本函数不必再跟任何
     * 别的口径争时间：没字幕可显示就不算绿，有字幕且用户开着就绿 —— 只看「当下有没有内容」。
     */
    private static boolean statusBarButtonOn(SubtitleRepository repo, boolean noSub) {
        // 无字幕时状态栏钮本来就不显示，这里返回 false 只是为了让签名稳定
        // （避免「不可见但仍参与签名」的钮在无字幕期间还随开关抖动）。
        return !noSub && StatusBarSubtitleBridge.sAppEnabled;
    }

    /**
     * 【code 941】状态栏钮是否该**显示**。
     *
     * 判据 =「当下有字幕可显示」**且**「SystemUI 作用域已确认授权」。
     *
     * 后者为什么必须：没勾选 SystemUI 作用域时，状态栏那条链路
     * （{@link StatusBarSubtitleBridge#ACTION_LINE}）**没有接收方** —— 按钮点下去
     * 只翻转一个没人听的开关，用户看到的是「按了没反应」。与其给一个假按钮，
     * 不如不显示（需求原文：「如果检测到用户没有给插件勾选 com.android.systemui
     * 作用域授权，则播放界面不显示状态栏字幕开关按钮」）。
     *
     * ⚠️ 与 {@link #statusBarButtonOn}（底色 = 开/关）是**两件事**，不要合并：
     *    那个管「什么颜色」，这个管「在不在」。
     * ⚠️ {@link #applyButtonText} 与 {@link #updateButtonDrawable(SubtitleRepository, boolean)}
     *    **必须都走本函数** —— 两条路径各算一套正是 1.21.12 / 1.21.16 反复踩的坑
     *    （「文字变了底色没变」「底色比事实早 9.5 秒」都是这么来的）。
     */
    private static boolean statusBarButtonVisible(SubtitleRepository repo, boolean noSub) {
        return !noSub && StatusBarSubtitleBridge.isSystemUiScopeAuthorized();
    }

    /**
     * 【v57】悬浮窗钮是否呈「开」态。
     *
     * ⚠️ 无字幕时需求是「悬浮窗字幕按键显示无字幕」—— 它的**文字**变成「无字幕」，
     *    底色按关态处理（设计稿的「无字幕状态」就是一个深色胶囊）。
     *    所以这里 `!noSub` 的约束保留：无字幕 → 不算开。
     */
    private static boolean floatingButtonOn(SubtitleRepository repo, boolean noSub) {
        return !noSub && repo.isFloatingWindowOpen();
    }

    /**
     * 【v57】胶囊 drawable —— MD3 filled button 的胶囊形态。
     *
     * 与旧 {@code createButtonDrawable} 的三处差异（都是设计稿要求的明显变化）：
     *   ① 圆角：{@code setCornerRadius(8)} → {@code setCornerRadius(h/2)}（全圆角 = 胶囊）；
     *   ② 描边：旧版有 1px 半透明白描边 → 新版**去掉**（设计稿是纯填充）；
     *   ③ 配色：旧版三色（黑/紫/绿）→ 新版两色（#212042 关 / #584179 开）。
     *      ⚠️ 绿色 #1EB980 彻底废弃 —— 新设计里「开着」统一是紫色，
     *      状态栏与悬浮窗不再靠颜色区分（靠**左右位置**和**文字**区分）。
     *
     * @param on true = 开态（紫），false = 关态（深紫黑）
     */
    private static GradientDrawable createCapsuleDrawable(boolean on) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(on ? CAPSULE_BG_ON : CAPSULE_BG_OFF);
        // 【code 923 几何 2】圆角改为**固定 15dp**。
        // 旧写法 999f 靠「超出部分自动钳到 h/2」实现全圆角 —— 圆角会**跟着高度变**
        // （35dp 高 → 17.5dp；改到 30dp 高就悄悄变 15dp）。现在规格明确是 15dp，写死。
        // density 由 dip2px / createButtonGroup 维护进 sDensityPx（本函数没有 Context 参数）。
        float d = sDensityPx > 0f ? sDensityPx : 3f;
        drawable.setCornerRadius(CAPSULE_RADIUS_DP * d);
        return drawable;
    }

    /**
     * 按钮文字/底色的更新入口（仓库观察者回调，可能来自任意线程 -> 一律切到主线程落笔）。
     */
    private static void updateButtonText(SubtitleRepository repo) {
        if (sButtonGroup == null) {
            return;
        }
        uiHandler.post(() -> applyButtonText(repo, true));
    }

    /**
     * 真正的落笔。**只在主线程调用**。
     *
     * ── v53（1.21.12 问题 2）：修「切到无字幕要等 3 秒」──
     *
     * 原来这里只看 {@link SubtitleRepository#hasSubtitles()}，而它在换轨待确认窗口
     * （`NO_SUBTITLE_GRACE_MS` = 3000ms）里照样返回 true（cues 还没清）→ 按钮一直显示
     * 「悬浮开」，直到 3 秒裁决完才变「无字幕」。而**同一时刻**悬浮窗面板和状态栏字幕
     * 早就按「无字幕」显示了（它们读 `getCues()/getCurrentSubtitles()`，pending 时返回空）
     * —— 只有按钮在自说自话。这是三处 UI 不一致，不是「防闪烁」。
     *
     * 现在：`isSuspended()`（= 换轨待确认）也算「无字幕」，但**压一个短延时**
     * {@link #BUTTON_NO_SUB_DELAY_MS} 再表态 —— 实测新音轨的字幕 JSON 115~345ms 就到，
     * 等这一小段就能让「其实有字幕」的音轨全程不闪，而真没字幕的音轨 ~0.5s 就变。
     *
     * @param allowDelay 允许为「切进无字幕」排队一个短延时。延时到点后的重入**必须**传 false，
     *                   否则会自己给自己再排一次、永远落不了笔。
     */
    private static void applyButtonText(SubtitleRepository repo, boolean allowDelay) {
        if (sButtonGroup == null || sButton == null) {
            return;
        }
        final boolean noSub = repo.shouldShowNoSubtitles();

        // ── 【v57】两个按钮各自的文字 / 底色 / 可见性 ──
        //   悬浮窗钮：始终显示，文字在「悬浮窗 开 / 悬浮窗 关 / 无字幕」三态间切换。
        //   状态栏钮：无字幕时**整个消失**（需求原话「状态栏字幕按键消失」）。
        final String floatText;
        if (noSub) {
            floatText = "无字幕";
        } else if (repo.isFloatingWindowOpen()) {
            floatText = "悬浮窗 开";
        } else {
            floatText = "悬浮窗 关";
        }
        final boolean floatOn = floatingButtonOn(repo, noSub);
        final String statusText = StatusBarSubtitleBridge.sAppEnabled ? "状态栏 开" : "状态栏 关";
        final boolean statusOn = statusBarButtonOn(repo, noSub);
        // 【code 941】可见性多一条「SystemUI 作用域已授权」—— 见 statusBarButtonVisible。
        final boolean statusVisible = statusBarButtonVisible(repo, noSub);

        // 签名 = 全部视觉属性（两钮的文字/底色 + 状态栏钮的可见性 + 悬浮窗钮的 alpha）。
        // 【1.21.13/1.21.14 的教训】判等必须覆盖该控件的**全部**输入，漏任何一项
        // 都会留下「文字变了底色没变」这类错位。合成一个串最不容易漏。
        final float alpha = noSub ? 0.6f : 1.0f;
        final String sig = floatText + '\u0000' + floatOn + '\u0000'
                + statusText + '\u0000' + statusOn + '\u0000' + statusVisible
                + '\u0000' + alpha;
        if (sig.equals(sLastBtnSig)) {
            sBtnNoSubPending = false;
            sBtnNoSubGen++;                    // 状态已经一致 -> 作废排队中的延时切换
            return;                            // 没变化就不折腾（避免每次通知都重建 drawable / 触发重绘）
        }
        if (allowDelay && noSub && sLastBtnSig != null && sLastBtnSig.indexOf("无字幕") < 0) {
            if (sBtnNoSubPending) {
                return;                        // 已经在等，别重复排队
            }
            sBtnNoSubPending = true;
            final int gen = ++sBtnNoSubGen;
            uiHandler.postDelayed(() -> {
                if (gen != sBtnNoSubGen) {
                    return;                    // 期间状态又变了（比如字幕 JSON 到了）-> 本次排队作废
                }
                sBtnNoSubPending = false;
                applyButtonText(repo, false);  // 重新按最新状态落笔
            }, BUTTON_NO_SUB_DELAY_MS);
            return;
        }
        sBtnNoSubPending = false;
        sBtnNoSubGen++;                        // 其它任何转换立即生效，并作废排队中的切换
        // 【1.21.14 问题 1】状态变化留痕：出问题时能直接用「换轨 SUSPEND ->
        // capsule state」两行的时间差对出「显示口径到底跟没跟上文字」。
        // 【v57】留痕内容从「单个 bgMode」扩成「两个按钮各自的文字 + 底色 + 可见性」，
        //        因为现在有两个独立控件，只记一个再也说明不了问题。
        XposedCompat.log(TAG + " capsule state: float=" + floatText
                + "/" + (floatOn ? "on" : "off")
                + " status=" + statusText + "/" + (statusOn ? "on" : "off")
                + (statusVisible ? "/visible" : "/gone")
                + " (noSub=" + noSub + ", statusBarOn="
                + StatusBarSubtitleBridge.sAppEnabled + ")");
        sLastBtnSig = sig;

        // ── 悬浮窗钮（右）──
        sButton.setText(floatText);
        sButton.setAlpha(alpha);
        sButton.setBackground(createCapsuleDrawable(floatOn));

        // ── 状态栏钮（左）──
        // 【v57】无字幕时**整个消失**（需求原话「状态栏字幕按键消失」）。
        // 用 GONE 而不是 INVISIBLE：GONE 会把宽度也让出去，剩下的悬浮窗钮会
        // 自动贴到容器右端（因为 LinearLayout 是 wrap_content + 外层靠右对齐），
        // 视觉上不会留下一个空洞。
        if (sStatusBarButton != null) {
            sStatusBarButton.setText(statusText);
            sStatusBarButton.setBackground(createCapsuleDrawable(statusOn));
            int want = statusVisible ? View.VISIBLE : View.GONE;
            if (sStatusBarButton.getVisibility() != want) {
                sStatusBarButton.setVisibility(want);
            }
        }
        // 【1.21.15 问题 1】这里原本还会调 StatusBarSubtitleBridge
        // .forceDisabledWhenNoSubtitles()，把「无字幕」升级成「把用户的开关关掉」。已删除 ——
        // 无字幕这件事只该影响**显示**（文字 + 底色，上面几行已经落笔），不该改写用户意图。
        // 【1.21.16 问题 1】底色必须与文字**同一次判定**共用 noSub：
        // 旧版底下那个 updateButtonDrawable 会**自己重算**一次 noSub，而文字那条路径
        // 受 allowDelay 约束、要压 BUTTON_NO_SUB_DELAY_MS 才落笔 —— 于是底色先跑：
        // 日志实测 11:32:37.793 SUSPEND → 11:32:38.296 底色就变暗（503ms），
        // 而真正的裁决在 11:32:47.794（10s 后）。底色比事实早了 9.5 秒。
        updateButtonDrawable(repo, noSub);
    }

    /** 便捷重载：调用方没算 noSub 时，按当前状态自取一次。 */
    private static void updateButtonDrawable(SubtitleRepository repo) {
        updateButtonDrawable(repo, repo.shouldShowNoSubtitles());
    }

    /**
     * 【v57】只重画两个胶囊的**底色与可见性**（不动文字）。
     *
     * 保留这个函数是因为状态栏开关的监听链路（{@code setEnabledListener}）只需要
     * 「开关变了 → 底色跟着变」，跟文字无关。走 {@link #applyButtonText} 会走一遍
     * 全套判等与延时逻辑，在「开关变了但文字没变」的场景下反而绕。
     *
     * ⚠️ 两处口径必须与 {@link #applyButtonText} 完全一致 —— 同一个事实（开关状态）
     *    被两条路径读取时，最容易出的就是「文字这条更新了、底色那条没更新」
     *    或者反过来（1.21.12 问题 2 就是这么来的）。所以这里**复用**同样的
     *    {@link #floatingButtonOn} / {@link #statusBarButtonOn}，不另算一套。
     *
     * @param noSub 与本轮文字**同一次**判定得到的口径，保证文字与底色同粒度。
     */
    private static void updateButtonDrawable(SubtitleRepository repo, boolean noSub) {
        if (sButton == null) {
            return;
        }
        sButton.setBackground(createCapsuleDrawable(floatingButtonOn(repo, noSub)));
        if (sStatusBarButton != null) {
            sStatusBarButton.setBackground(createCapsuleDrawable(statusBarButtonOn(repo, noSub)));
            // 【code 941】与 applyButtonText 同口径（含作用域授权判据），不得各算一套。
            int want = statusBarButtonVisible(repo, noSub) ? View.VISIBLE : View.GONE;
            if (sStatusBarButton.getVisibility() != want) {
                sStatusBarButton.setVisibility(want);
            }
        }
        // 注意：这里**不**写 sLastBtnSig —— 签名由 applyButtonText 统一维护。
        // 若在这里改签名，applyButtonText 的判等就会被绕乱（它以为已经落过笔了）。
    }

    /**
     * 【code 939 bug2】取一个**不会抖**的 density —— 见 {@link #sLockedDensity} 的长注释。
     * 读数与锁定值不一致且像素尺寸没变时，一律沿用锁定值（转场伪值）。
     */
    private static float stableDensity(Context ctx) {
        float d = 0f;
        int pxKey = 0;
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            d = dm.density;
            pxKey = dm.widthPixels * 31 + dm.heightPixels;
        } catch (Throwable t) {
            return sLockedDensity > 0f ? sLockedDensity : 3f;
        }
        if (d <= 0f) {
            return sLockedDensity > 0f ? sLockedDensity : 3f;
        }
        // 首次锁定 / 像素屏幕尺寸真的变了（旋转、分屏、换屏）
        if (sLockedDensity <= 0f || pxKey != sLockedPxKey) {
            if (sLockedDensity > 0f && Math.abs(d - sLockedDensity) > 0.0001f) {
                XposedCompat.log(TAG + " density re-locked (screen changed): "
                        + sLockedDensity + " -> " + d + " pxKey=" + pxKey);
            }
            sLockedDensity = d;
            sLockedPxKey = pxKey;
            sDensityDiffSinceMs = 0L;
            sDensityDiffCount = 0;
            return d;
        }
        if (Math.abs(d - sLockedDensity) <= 0.0001f) {
            sDensityDiffSinceMs = 0L;
            sDensityDiffCount = 0;
            return sLockedDensity;
        }
        // 像素尺寸没变但 density 变了 —— 真机实证这是「打开播放页转场瞬间」的伪值
        long now = SystemClock.uptimeMillis();
        if (sDensityDiffSinceMs == 0L) {
            sDensityDiffSinceMs = now;
        }
        sDensityDiffCount++;
        if (now - sDensityDiffSinceMs >= DENSITY_RELOCK_MS
                && sDensityDiffCount >= DENSITY_RELOCK_SAMPLES) {
            XposedCompat.log(TAG + " density re-locked (sustained diff): "
                    + sLockedDensity + " -> " + d + " samples=" + sDensityDiffCount
                    + " overMs=" + (now - sDensityDiffSinceMs));
            sLockedDensity = d;
            sDensityDiffSinceMs = 0L;
            sDensityDiffCount = 0;
            return d;
        }
        if (sDensityDiffCount == 1) {
            XposedCompat.log(TAG + " density spike ignored: read=" + d
                    + " locked=" + sLockedDensity + " pxKey=" + pxKey);
        }
        return sLockedDensity;
    }

    private static int dip2px(Context ctx, float dp) {
        float d = stableDensity(ctx);
        if (d > 0f) {
            sDensityPx = d; // 【code 923】顺手维护，createCapsuleDrawable 算圆角要用
        }
        return (int) (dp * d + 0.5f);
    }
}
