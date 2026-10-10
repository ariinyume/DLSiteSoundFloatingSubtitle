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
package io.github.ariinyume.dlsitesoundfloat.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

public class SubtitleRepository {
    private static SubtitleRepository instance;

    /** 音轨切换时：距上次成功加载字幕 JSON 多久以内，视为「新音轨预加载的字幕」而保留。 */
    private static final long PRELOAD_TOLERANCE_MS = 3500L;
    /** 音轨切换后等多久仍没等到字幕 JSON，就判定「该音轨没有字幕」（无缓存字幕时）。 */
    private static final long NO_SUBTITLE_GRACE_MS = 3000L;
    /**
     * 【1.21.15 问题 1】进入待确认时**已有缓存 cues** 的情况下，给字幕 JSON 的宽限时间。
     *
     * 原窗口固定 3000ms，而实测新音轨的字幕 JSON 到达延迟跨度极大：缓存命中时 ~0.2s，
     * 冷请求（长时间空闲后首次切轨、要重新发网络请求）可达 11~15.6s。于是
     * 「3s 没等到 JSON ⇒ 该音轨无字幕」这条推断频繁**假阴性** —— 日志实证：
     *   18:20:25.879 判无字幕（cues were 55）→ 18:20:31.355 Loaded 105 cues（同一音轨，5.5s 后到）
     *   17:35:02.521 判无字幕（cues were 81）→ 17:35:07.812 Loaded 152 cues（5.3s 后到）
     *   17:22:02.745 判无字幕（cues were 81）→ 17:22:16.824 Loaded 81 cues（11.1s 后到）
     * 而「进入待确认时已有 cues」本身就说明**这部作品是有字幕的**，值得多等一会儿；
     * cues 本来就是空（用户一直待在无字幕音轨上）则维持 3000ms 结案，零代价。
     *
     * 【2.1.3 问题 1】10s → 15s：冷请求实测尾部到 15.6s
     * （2026-09-29 日志 `FALSE NEGATIVE ... 1311ms after the verdict` 之外，
     *   历史 11.1s / 15.6s 均 ≥ 10s），10s 仍会漏掉尾部长请求。
     * 既然手上有 cues、本作品已证明有字幕，多等 5s 的代价只是「结论晚一点」，
     * 远小于误报「无字幕」的代价。
     */
    private static final long NO_SUBTITLE_GRACE_MS_CACHED = 15000L;
    /**
     * 【code 942】「提前收窗」：进入待确认后等这么久还没等到字幕 JSON，就先把悬浮窗关掉
     * （provisional —— 只关窗，不裁决）。
     *
     * 动机（2026-09-22 实测）：从有字幕音轨切到无字幕音轨时，裁决窗长达 10s
     * （NO_SUBTITLE_GRACE_MS_CACHED），期间悬浮窗一直挂着显示「无字幕」占位
     * （录屏 21:29:51 至 21:30:00 整 10 秒），被当成「没有自动关闭」。
     * 不能靠缩短裁决窗：实测有字幕轨的 JSON 到达延迟双峰（小于 0.7s 或约 5.5s，历史 12s+），
     * 缩窗会误杀慢 JSON 的真字幕轨。所以把「关窗」从「裁决」里拆出来：
     * 5s 无 JSON 就先关窗（code 942 原为 2s，code 943 放宽到 6s，code 944 收到 4s，
     * 【code 954】Ari 2026-09-26 指令定为 **5s**）；之后 JSON 到了，loadFromJsonArrayInternal
     * 里现成的 autoClosedForNoSubtitle 恢复路径会把窗口开回来。裁决（数据侧）仍按原 grace 走。
     */
    private static final long NO_SUBTITLE_EARLY_CLOSE_MS = 5000L;
    /** 【1.21.15】判「无字幕」后若在这么久内又来字幕 JSON，就把它当假阴性记一笔。 */
    private static final long FALSE_NEGATIVE_REPORT_MS = 60000L;
    /**
     * 播放位置比「待确认开始时的位置」回退这么多毫秒以上，就认为确实重开了一轨
     * （新音轨总是从 0 附近开始播）。
     *
     * v27：用来识别**假换轨** —— 例如在第一轨按「上一首」时 App 是 no-op、音频继续播，
     * 播放位置只增不减。此时不该判定「本音轨无字幕」并关窗。
     */
    private static final long POSITION_RESET_TOLERANCE_MS = 1000L;
    /**
     * 待确认窗口内至少收到这么多次播放位置回调，才敢用「位置没回退」去否定换轨。
     * 否则（暂停中 / 位置不可知）保持旧行为，避免误留上一轨的字幕。
     */
    private static final int PENDING_MIN_POS_SAMPLES = 2;
    /**
     * 屏上文本超过这个长度，就不可能是「一条字幕行」（字幕行都很短）。
     * v18 关键修正：曲目列表页的作品标题形如
     * 「Tr2_【独占欲失控】你不是叫其他人吧？以猫咪的本能与你十指紧…」，
     * 旧版 isKnownSubtitleText() 用**包含匹配**（nl.contains(t) || t.contains(nl)），
     * 于是列表页的长标题会命中字幕库里的短行 → liveLines=1 → 列表页被误判成播放页
     * （这正是「切回前台后非播放页仍显示按钮」的根因）。现在改成**精确相等** + 长度上限。
     */
    private static final int MAX_SUBTITLE_LINE_LEN = 60;

    private final Object lock = new Object();
    private Context appContext;
    private ClassLoader appClassLoader;

    private List<SubtitleCue> cues = new ArrayList<>();
    /** 字幕行的**精确**匹配集合（cue 行 normalize 后的去重集），字幕加载时重建。 */
    private final java.util.Set<String> subtitleLineSet = new java.util.HashSet<>();
    private List<String> currentSubtitles = new ArrayList<>();
    private boolean subtitlesVisible = false;
    private boolean floatingWindowOpen = false;
    private boolean playerPageVisible = false;

    /** 最近一次成功加载字幕 JSON 的时刻（uptimeMillis）。 */
    private volatile long lastLoadJsonMs = 0L;

    // ---- 音轨切换 / 字幕可用性判定（v17）----
    /** 是否处于「刚换轨、还不知道新音轨有没有字幕」的待确认状态。 */
    private boolean pendingTrackDecision = false;
    /** 待确认开始时的 lastLoadJsonMs 基线；期间若刷新，说明新音轨的字幕到了。 */
    private long pendingBaselineLoadMs = 0L;
    /** 判定令牌：换轨 / 新字幕到达都会自增，使已排队的旧判定作废。 */
    private long pendingToken = 0L;
    /** 悬浮窗是否是被「本音轨无字幕」判定**自动关掉**的（用于字幕晚到后自动恢复）。 */
    private boolean autoClosedForNoSubtitle = false;
    /**
     * 【1.21.16 问题 1】**软裁决**：本音轨判「无字幕」，但**没有销毁 cues**。
     *
     * ── 为什么需要它（1.21.15 的假阴性根因）──
     *
     * 1.21.15 的裁决在「等待期内没有新的字幕 JSON」时，会**清空 cues** 并把它当作
     * 不可逆事实（`cues = new ArrayList<>()`）。实测（2026-09-18 日志）这条推断有两个反例：
     *
     *   - R4（11:17:04.732 换轨）：cues=225 在手，10s 窗到点 JSON 仍未来 → 清 cues + 判无字幕；
     *   - R6（11:32:37.793 换轨）：cues=49 在手（**同一部作品 3.5 秒前刚加载过**），
     *     10s 窗到点 JSON 仍未来 → 清 cues + 判无字幕 → 用户点按钮被
     *     `createButton()` 的 `!hasSubtitles()` 早退吞掉，按钮一直挂「无字幕」。
     *
     * 但「N 秒内没收到 JSON」**不是「不存在」的证据**。实测同一部作品的 JSON 到达延迟
     * 跨度是 **711ms ~ 11973ms**（R1 711ms / R3 5893ms / R2 **11973ms**，已超过 10s 窗）。
     * 既然手上有 cues 就说明**这部作品有字幕资源**，那这次裁决最多只能"降级显示"，
     * 不能销毁数据 —— 否则 JSON 真的来了也没有读者了。
     *
     * 这正是本仓库铁律里那条：**「N 秒超时」不是「不存在」的证据 —— 量不到就只降级显示、
     * 不销毁数据。** 与 1.21.15 修掉的「推断无权改写用户显式表达的状态」是同一类错误。
     *
     * 语义：
     *   - `true`  → 显示层按「无字幕」处理（按钮文字/底色、悬浮窗、状态栏都降级），
     *               但 `cues` / `subtitleLineSet` 原样保留；
     *   - 任何一次新的 `loadFromJson` 成功都会把它清掉（见 loadFromJsonArrayInternal），
     *     于是显示层自动恢复 —— 不需要用户重新进页面。
     */
    private volatile boolean softNoSubtitles = false;
    /** 待确认开始时的播放位置（-1 = 未知）；用于识别「其实没有真的换轨」。 */
    private long pendingStartPosMs = -1L;
    /** 待确认窗口内是否观察到播放位置**回退**（= 确实重开了一轨）。 */
    private boolean pendingSawPositionReset = false;
    /** 待确认窗口内收到的播放位置回调次数。 */
    private int pendingPosSamples = 0;
    /**
     * 【973】本次换轨通知是否来自<b>权威序号门</b>（状态 Map 的 {@code currentIndex} 确实变了）。
     *
     * 为 true 时 {@link #resolvePendingTrack} 不再走「位置回退 / 位置一直往前走 ⇒ 疑似假换轨」那条兜底：
     * 兜底是给<b>老信号路径</b>（按方法名挂换轨方法，信号本身不保证真的换了轨）准备的；
     * 序号门不一样 —— 序号变了就是播放列表的当前项变了。若仍走兜底，真换轨会被撤销，
     * 于是旧 cue 继续渲染 = 用户 2026-10-07 报的「切到无字幕音轨仍显示历史音轨字幕」。
     * ⚠️ 真机实测（2026-10-07 两份日志）SPURIOUS 从未触发，说明这条兜底在序号门上本就不该参与。
     */
    private boolean pendingIndexVerified = false;
    /**
     * 【973】**上一音轨的 cue 不再参与渲染**（数据一个都不删）。
     *
     * ── 为什么光靠清显示层不够（用户 2026-10-07 报的现象）──
     * 换轨瞬间清空 `currentSubtitles` 只能挡住裁决窗那一段（{@code pendingTrackDecision} 期间
     * {@link #getCues()} 返回空）。裁决窗到点、新 JSON 仍没到时，旧实现会把
     * {@code pendingTrackDecision} 放开 —— 于是<b>旧的 cue 列表又开始渲染</b>：
     * 新音轨从 0 秒起播、走到几秒时 {@code computeIndexAtSecondLocked()} 拿旧列表匹配，
     * 就把上一轨的字幕画了出来（截图：`00:04` 显示上一轨的 cue 1）。
     *
     * ── 本标志把「数据保留」与「可否渲染」彻底分开 ──
     *   · {@code cues} / {@code subtitleLineSet} <b>原样保留</b>（防假阴性：JSON 只是晚到，
     *     一到达就能立刻恢复，不需要用户重进页面）；
     *   · 但只要本标志为 true，{@link #getCues()} / {@link #findCurrentCueIndex()} /
     *     {@link #getCurrentSubtitles()} 一律按「空」返回 ⇒ 显示层不可能再画出上一轨的字幕；
     *   · 任何一次新的 {@code loadFromJson} 成功都会清掉它（新 JSON = 新音轨的字幕）；
     *   · 裁决为「假换轨」（SPURIOUS，仅老信号路径）时也清掉（同一轨，本来就没换）。
     *
     * ⚠️ 与 {@link #softNoSubtitles} 的区别：本标志<b>不</b>驱动 {@link #shouldShowNoSubtitles()}，
     * 即<b>不</b>把三处 UI 降级成「无字幕」（那是 2.1.3 明令避免的假阴性观感），只掐掉渲染。
     */
    private boolean previousTrackCuesHidden = false;
    /**
     * 【2.1.3 问题 1】进入待确认时手上**已有** cues（= 本作品此前已证明有字幕）。
     *
     * 真机日志（2.1.2 / code 954）实证两处假阴性：
     *   20:18:40.926 SUSPEND (cues=107, lastJson=1313913ms ago)
     *   20:18:41.427 capsule state: noSub=true                ← 挂起 501ms 后 UI 就变「无字幕」
     *   20:18:50.926 NO subtitles for this track (cues were 107)
     *   20:18:52.237 FALSE NEGATIVE: json arrived 1311ms after the verdict
     *   20:37:30.966 SUSPEND (cues=46, lastJson=**5166ms** ago)  ← 字幕 5.1s 前刚加载过
     *   20:37:31.485 capsule state: noSub=true
     *   20:37:40.968 NO subtitles for this track (cues were 46)
     *
     * 语义：`true` = 本次挂起是「软挂起」——手上数据完好，只是新轨 JSON 还没确认，
     * 显示层**不该**因此降级成「无字幕」。
     * 与 {@link #softNoSubtitles}（裁决后才置位）互补：本字段覆盖**裁决前**的等待期。
     *
     * 【code 960】「不降级」≠「继续显示上一轨的字幕」：2.1.3 的实现是冻结显示，
     *   于是切到**真正无字幕**的轨时旧字幕一直挂着（用例 7.2）。现在改为
     *   **显示层清空、数据保留**（cues / subtitleLineSet 一个都不动），
     *   但**窗口先留着**：实测假阴性是 1311ms / 5.5s / 11.1s，都在裁决窗内，
     *   提前收窗只会给这些「其实有字幕」的轨凭空加一次窗口闪（2.1.3 换来的收益不能丢）。
     *   收窗交给裁决窗到点的 {@code resolvePendingTrack}（软硬一律关），
     *   JSON 晚到时 loadFromJsonArrayInternal 仍会把显示与窗口一起恢复回来。
     */
    private boolean pendingSoftSuspend = false;
    /**
     * 【1.21.15 问题 1】最近一次判「本音轨无字幕」的时刻（uptimeMillis）；0 = 从未。
     * 只用于事后取证：若判完没多久字幕 JSON 又到了，说明这次裁决是**假阴性**，
     * 直接在日志里点名（见 {@link #loadFromJsonArrayInternal}），不用再靠人肉对时间线。
     */
    private volatile long lastNoSubtitleVerdictMs = 0L;
    /**
     * 【980】**宿主当前活跃播放列表的身份**（{@code PlayerSourceHook#makeIdentity} 的三元组打包；
     * 0 = 未知）。由 {@code PlayerSourceHook.onPlaylistStateFromStatusMap} 在每次读到
     * **活跃列表**时推送（幽灵列表 / 闲置 AudioPlayer 已被权威门挡在外面）。
     *
     * <p>只用于给「刚加载的 cues」盖主人印章（见 {@link #cuesOwnerIdentity}），
     * <b>本身不触发任何状态变更</b> —— 避免把宿主的多实例交替读数变成 UI 抖动源。
     */
    private volatile long observedPlaylistIdentity = 0L;
    /**
     * 【980】**手上这份 cues 属于哪份播放列表**（身份同上；0 = 未知 / cues 已销毁）。
     *
     * <p>── 为什么需要它（Ari 2026-10-07 报的「反向切轨残留」）──
     * 宿主对**同一轨**的字幕响应是有缓存的：切走再切回来时它**不会重新发请求**，
     * 于是模块永远等不到新的字幕 JSON。旧实现把「新 JSON 到达」当成唯一恢复路径
     * （{@link #loadFromJsonArrayInternal}），反向切回时就必然卡住：
     * <pre>
     *   22:23:18.215 Loaded 39 cues                    ← 这份 cues 属于轨 tc=7 dur=278.64s idx=0
     *   22:23:38.481 track changed 7→1 → 软挂起，softNoSubtitles=true（三处 UI 变「无字幕」✅ 正确）
     *   22:23:53.483 soft verdict（cues=39 kept，不再渲染）
     *   22:23:58.746 track changed 1→7 → **又是**软挂起 ❌（本轨字幕其实就在手上）
     *   此后宿主复用缓存、不再发 JSON ⇒ **永久**停在「无字幕」
     * </pre>
     * 有了本字段就能识别「这次换轨的目标正是手上 cues 的主人」⇒ 直接恢复渲染
     * （见 {@link #tryResumeFromCache}），完全不必等宿主重发 JSON。
     *
     * <p>写入时机只有两处：① 一次成功的 JSON 加载（盖章）；② 硬裁决销毁 cues（清章）。
     * 换轨时**不动它** —— cues 没变，主人就没变。
     */
    private volatile long cuesOwnerIdentity = 0L;
    /**
     * 【code 1004】宿主**当前选择**的「选择键」{@code (trackCount, currentIndex)}（0 = 未知）。
     *
     * <p>── 为什么比 {@link #observedPlaylistIdentity} 更早可读（本轮 bug 的根修）──
     * 身份键 {@code (trackCount, durationMs, currentIndex)} 里的**时长**要等新音源装载完才有值；
     * 而宿主在切音轨那一瞬间就把 {@code currentIndex} 翻了。真机日志（{@code LSPosed_20261010_184115}）：
     * <pre>
     *   18:40:58.391  currentIndex=1→3  duration=0.0   ← 选择已翻，时长还未知
     *   18:40:58.556  Loaded 84 cues    （= 新轨 idx=3 的字幕，比换轨通知早 5.9s）
     *   18:41:04.430  duration=944.784  ← 时长这才就绪，身份键第一次变化 ⇒ 通知才发出去
     * </pre>
     * ⇒ 时间差里的这份 JSON 盖的是**上一轨**的印章，随后被当成「上一轨的旧数据」隔离
     * ⇒ 永久「无字幕」（宿主不会重发）。选择键补上这段空档：它在 18:40:58.391 就有值了。
     *
     * <p>由 {@code PlayerPositionHook#consume} 在**每一条**活跃列表读数上推送
     * （**含** {@code duration == 0} 的装载期读数 —— 那正是本键存在的意义），
     * 与 {@link #observedPlaylistIdentity} 一样<b>只写一个 volatile 字段、不触发任何状态变更</b>。
     */
    private volatile long observedPlaylistSelection = 0L;
    /**
     * 【code 1004】手上这份 cues 在**加载那一刻**宿主选择的是哪条轨（选择键；0 = 未知）。
     *
     * <p>与 {@link #cuesOwnerIdentity} 同生共死（同一次加载盖章、硬裁决一起清），
     * 但**取值时机不同**：身份键在时长就绪前拿到的是旧值，选择键不会。
     */
    private volatile long cuesOwnerSelection = 0L;
    /**
     * 【code 1004】这份 cues 加载时「宿主的选择已跑在身份前面」——见
     * {@link PlaylistKey#selectionAheadOfIdentity}。
     *
     * <p>为 true 且换轨目标的选择键与 {@link #cuesOwnerSelection} 相同 ⇒ 这份 cues **就是新轨的**
     * （宿主早已为新轨取回字幕），直接恢复渲染，不必等它重发（见 {@link PlaylistKey#cuesBelongToTarget}）。
     * 只在**同一列表内换序号**时为 true；跨作品（序号相同）一律 false，避免拿上一部作品的字幕冒充。
     */
    private volatile boolean cuesLoadedAheadOfIdentity = false;

    // ---- 播放结束 → 自动关窗（v29）----
    /** Media3 {@code Player} 的播放状态取值（与 App 内部常量对齐）。 */
    private static final int PSTATE_IDLE = 1;
    private static final int PSTATE_BUFFERING = 2;
    private static final int PSTATE_READY = 3;
    private static final int PSTATE_ENDED = 4;
    /** 最近一次收到的播放状态；-1 = 尚未拿到。 */
    private volatile int lastPlaybackState = -1;
    /** 悬浮窗是否是被「播放结束」**自动关掉**的（用于重新开播后自动恢复）。 */
    private boolean autoClosedForPlaybackEnded = false;
    /**
     * 「播放结束」的确认延迟。
     * 自动连播时播放器可能短暂进入 ENDED 后立刻开始下一首，此刻立即关窗会让窗口"闪一下"；
     * 等这段时间过去再复查一次状态，仍是 ENDED 才算真的结束。
     */
    private static final long PLAYBACK_END_CONFIRM_MS = 400L;

    // ---- 播放进度（由 PlayerPositionHook 供数）----
    /** 最近一次播放位置（毫秒）。-1 表示尚未拿到。 */
    private volatile long playbackPositionMs = -1L;
    /** 最近一次被 hook 喂进来的原始值，用于快速去重（无锁快路径）。 */
    private volatile long lastFedMs = -1L;
    /** 按时间轴命中的当前 cue 索引；-1 表示未命中。间隙时保持上一次的值不抖动。 */
    private int currentCueIndex = -1;
    /** 上次做时间轴查找时所在的「秒」，同秒内不重复计算。 */
    private int lastScannedSecond = Integer.MIN_VALUE;

    private final List<Runnable> observers = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat;

    private SubtitleRepository() {
        timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        timeFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static synchronized SubtitleRepository getInstance() {
        if (instance == null) {
            instance = new SubtitleRepository();
        }
        return instance;
    }

    public void init(Context ctx, ClassLoader cl) {
        synchronized (lock) {
            // 【M1 隐患修复】这里只兜底设 appContext —— 只有当 attach 还没把真实宿主
            // Application Context 塞进来时才用传入的 ctx（systemCtx）垫底。
            // 正常时序是 Application#attach → setAppContext(真实宿主 Context) 先于
            // onPackageReady → init(systemCtx)。若这里无条件覆盖，会把真实宿主 Context
            // 又覆盖回 systemCtx，导致 getAppContext() 返回 system 的 Context（悬浮窗/状态栏
            // 桥都会用错 context 类型）。只在空时兜底即可两全：attach 已设则保留，未设则垫底。
            if (this.appContext == null) {
                this.appContext = ctx;
            }
            this.appClassLoader = cl;
        }
    }

    public void setAppContext(Context ctx) {
        synchronized (lock) {
            this.appContext = ctx;
        }
    }

    public Context getAppContext() {
        synchronized (lock) {
            return appContext;
        }
    }

    public ClassLoader getAppClassLoader() {
        synchronized (lock) {
            return appClassLoader;
        }
    }

    // ======================================================================
    // 播放进度 → 当前 cue
    // ======================================================================

    /**
     * 由播放器 Hook 高频调用（可能每秒几十次）。
     * 无锁快路径：值没变直接返回；只在「跨秒」时才做一次区间查找，并仅在命中索引变化时通知观察者。
     */
    public void setPlaybackPositionMs(long ms) {
        if (ms < 0 || ms == lastFedMs) {
            return;
        }
        lastFedMs = ms;
        playbackPositionMs = ms;

        boolean changed = false;
        synchronized (lock) {
            if (pendingTrackDecision) {
                // 待确认期间不参与字幕定位，但要记录「位置是否回退」——
                // 真换轨时新音轨从 0 开始，位置必然大幅回退；假换轨（如第一轨按上一首）
                // 音频照常播，位置只增不减。这条信息给 resolvePendingTrack 当判据。
                pendingPosSamples++;
                if (pendingStartPosMs >= 0 && ms < pendingStartPosMs - POSITION_RESET_TOLERANCE_MS) {
                    pendingSawPositionReset = true;
                }
                return; // 换轨待确认期间不参与定位（避免用旧字幕匹配新音轨的 0 秒）
            }
            int sec = (int) Math.floor(ms / 1000.0);
            if (sec == lastScannedSecond && currentCueIndex >= 0) {
                return; // 同一秒内且已有命中，无需重算
            }
            lastScannedSecond = sec;
            int idx = computeIndexAtSecondLocked(sec);
            // 落在 cue 之间的空隙时不改动索引，保持画面稳定（避免闪烁成「无字幕」）
            if (idx >= 0 && idx != currentCueIndex) {
                currentCueIndex = idx;
                changed = true;
            }
        }
        if (changed) {
            notifyObservers();
        }
    }

    public boolean hasPlaybackPosition() {
        return playbackPositionMs >= 0;
    }

    public long getPlaybackPositionMs() {
        return playbackPositionMs;
    }

    /** 必须在持有 lock 的情况下调用。 */
    private int computeIndexAtSecondLocked(int sec) {
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue c = cues.get(i);
            int s = (int) Math.floor(c.startTime);
            int e = (int) Math.floor(c.endTime);
            if (sec >= s && sec <= e) {
                return i;
            }
        }
        return -1;
    }

    // ======================================================================
    // 加载字幕
    // ======================================================================

    public void loadFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                data = root;
            }
            JSONArray webvtt = data.optJSONArray("webvtt");
            if (webvtt == null) {
                webvtt = data.optJSONArray("subtitles");
            }
            if (webvtt == null) {
                webvtt = data.optJSONArray("cues");
            }
            if (webvtt == null) {
                webvtt = data.optJSONArray("tracks");
            }
            if (webvtt == null) {
                // 有些接口直接返回数组
                try {
                    JSONArray direct = new JSONArray(json);
                    if (direct.length() > 0 && direct.optJSONObject(0) != null) {
                        webvtt = direct;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (webvtt == null) {
                XposedCompat.log("[DLsiteSoundFloat] loadFromJson: no subtitle array found");
                return;
            }
            loadFromJsonArrayInternal(webvtt);
            XposedCompat.log("[DLsiteSoundFloat] Loaded " + getCues().size() + " cues from JSON");
        } catch (Throwable e) {
            XposedCompat.log("[DLsiteSoundFloat] loadFromJson error: " + e.getMessage());
        }
    }

    public void loadFromJsonArray(JSONArray webvtt) {
        try {
            loadFromJsonArrayInternal(webvtt);
        } catch (Throwable e) {
            XposedCompat.log("[DLsiteSoundFloat] loadFromJsonArray error: " + e.getMessage());
        }
    }

    private void loadFromJsonArrayInternal(JSONArray webvtt) throws Exception {
        List<SubtitleCue> newCues = new ArrayList<>();
        // 【1.21.15 问题 1】假阴性取证用的暂存（-1 = 本次没有）
        long falseNegativeLagMs = -1L;
        int falseNegativeCueCount = 0;
        for (int i = 0; i < webvtt.length(); i++) {
            JSONObject cue = webvtt.getJSONObject(i);
            String start = cue.optString("start_time", "00:00:00.000");
            String end = cue.optString("end_time", "00:00:00.000");
            JSONArray lines = cue.optJSONArray("subtitles");
            List<String> lineList = new ArrayList<>();
            if (lines != null) {
                for (int j = 0; j < lines.length(); j++) {
                    lineList.add(lines.getString(j));
                }
            }
            newCues.add(new SubtitleCue(parseTime(start), parseTime(end), lineList));
        }
        boolean reopened = false;
        // 【1.21.16 问题 1】本次是否撤销了「软裁决」降级显示
        boolean softNoSubtitlesCleared = false;
        // 【973】本次是否撤销了「上一轨 cue 禁止渲染」
        boolean quarantineLifted = false;
        synchronized (lock) {
            cues = newCues;
            // 【980】给这份 cues 盖「主人」印章 = 当前活跃播放列表的身份。
            //   宿主对同一轨的响应有缓存（切走再切回不会重发 JSON），
            //   有了印章才能在切回时直接恢复渲染，见 tryResumeFromCache。
            cuesOwnerIdentity = observedPlaylistIdentity;
            // 【code 1004】同一时刻再盖一枚**选择键**印章，并记下「盖印时选择是否已跑在身份前面」。
            //   宿主切音轨时 currentIndex 立刻翻，而带时长的身份键要等新音源装载完才更新
            //   （真机实测 18:40:58.391 翻序号 → 18:41:04.430 才有 duration），
            //   而新轨的字幕 JSON 恰恰落在中间（18:40:58.556 到达）。
            //   ⇒ 只看身份键会把这份 JSON 误认成上一轨的旧数据（永久「无字幕」）；
            //     选择键在装载期就有值，能把归属判对（见 PlaylistKey 的说明）。
            cuesOwnerSelection = observedPlaylistSelection;
            cuesLoadedAheadOfIdentity = PlaylistKey.selectionAheadOfIdentity(
                    observedPlaylistSelection, observedPlaylistIdentity);
            // v18：重建「字幕行精确匹配集」，供 isKnownSubtitleText() 做 O(1) 精确判定
            subtitleLineSet.clear();
            for (SubtitleCue c : newCues) {
                for (String line : c.subtitles) {
                    String nl = normalizeLine(line);
                    if (!nl.isEmpty()) {
                        subtitleLineSet.add(nl);
                    }
                }
            }
            // 换曲后旧的索引/秒缓存作废；若当前播放位置已知，立刻重算一次
            currentCueIndex = -1;
            lastScannedSecond = Integer.MIN_VALUE;
            // 新字幕到达 → 结束「换轨待确认」，并作废已排队的判定
            pendingTrackDecision = false;
            pendingSoftSuspend = false; // 【2.1.3 问题 1】与挂起同生命周期
            // 【973】新 JSON = 新音轨的字幕 ⇒ 撤销「上一轨 cue 禁止渲染」。
            //   数据从来没删（就是同一份 cues 被换了内容），所以这一步就足够让显示层立刻恢复。
            quarantineLifted = previousTrackCuesHidden;
            previousTrackCuesHidden = false;
            pendingIndexVerified = false;
            pendingToken++;
            pendingStartPosMs = -1L;
            pendingSawPositionReset = false;
            pendingPosSamples = 0;
            if (playbackPositionMs >= 0) {
                int sec = (int) Math.floor(playbackPositionMs / 1000.0);
                lastScannedSecond = sec;
                int idx = computeIndexAtSecondLocked(sec);
                if (idx >= 0) {
                    currentCueIndex = idx;
                }
            }
            // 记录加载时刻，供「换轨是否为新音轨预加载」判断
            lastLoadJsonMs = SystemClock.uptimeMillis();
            // 【1.21.15 问题 1】假阴性取证：刚判完「本音轨无字幕」，字幕 JSON 就来了 ——
            // 说明那次裁决是错的。直接把结论写进日志，省掉事后肉眼对时间线。
            if (lastNoSubtitleVerdictMs > 0L) {
                long verdictLag = lastLoadJsonMs - lastNoSubtitleVerdictMs;
                if (verdictLag >= 0L && verdictLag <= FALSE_NEGATIVE_REPORT_MS) {
                    falseNegativeLagMs = verdictLag;
                    falseNegativeCueCount = newCues.size();
                }
                lastNoSubtitleVerdictMs = 0L;
            }
            // 之前被**自动**关掉的窗口（「疑似无字幕」或 v29 的「播放结束」），
            // 新音轨的字幕其实到了 → 恢复回来（用户手动关的不在此列）
            if (autoClosedForNoSubtitle || autoClosedForPlaybackEnded) {
                autoClosedForNoSubtitle = false;
                autoClosedForPlaybackEnded = false;
                floatingWindowOpen = true;
                reopened = true;
            }
            // 【1.21.16 问题 1】软裁决的解除：字幕到了 = 本音轨确实有字幕 →
            // 撤销降级显示。cues 从未被销毁，所以这一步就足够让按钮/悬浮窗/状态栏全部恢复。
            if (softNoSubtitles) {
                softNoSubtitles = false;
                // 位置可能是旧的（硬裁决路径会清 -1L，软裁决不清）→ 强制重扫当前秒，
                // 让恢复后的第一帧立刻落到正确的那条字幕行上。
                lastScannedSecond = Integer.MIN_VALUE;
                softNoSubtitlesCleared = true;
            }
        }
        if (reopened) {
            XposedCompat.log("[DLsiteSoundFloat] subtitles arrived -> reopen floating window"
                    + " (auto-closed earlier)");
        }
        if (quarantineLifted) {
            XposedCompat.log("[DLsiteSoundFloat] [973] previous-track cues released"
                    + " -> new json arrived, rendering restored");
        }
        // 【1.21.15 问题 1】把假阴性结论点出来：打出这行就说明刚才那次「本音轨无字幕」
        // 是误判 —— 该音轨其实有字幕，只是 JSON 比裁决窗来得更晚。裁决窗时长照这些数据调。
        if (falseNegativeLagMs >= 0L) {
            LogGate.debug("[DLsiteSoundFloat] track decision was a FALSE NEGATIVE:"
                    + " subtitle json arrived " + falseNegativeLagMs
                    + "ms after the \"no subtitles\" verdict"
                    + " -> cues=" + falseNegativeCueCount + " (this track does have subtitles)");
        }
        // 【1.21.16 问题 1】软裁决被撤销：数据从未销毁，显示层就此恢复。
        if (softNoSubtitlesCleared) {
            XposedCompat.log("[DLsiteSoundFloat] soft \"no subtitles\" verdict revoked"
                    + " -> subtitles arrived, cues were kept all along; restoring UI");
        }
        notifyObservers();
    }

    private double parseTime(String t) {
        try {
            Date d = timeFormat.parse(t);
            if (d != null) {
                return d.getTime() / 1000.0;
            }
        } catch (Throwable e) {
            // ignore
        }
        return 0;
    }

    // ======================================================================
    // 音轨切换 → 字幕可用性判定
    // ======================================================================

    /**
     * 由 PlayerSourceHook 在「切音轨 / 换曲 / 播放列表换项」时调用。
     *
     * 目标：切到没有字幕的音轨时，绝不继续显示上一音轨的缓存字幕。
     *
     * 判定过程（不依赖抓屏、不依赖 App 内部私有状态）：
     *  1) 若刚刚（≤ {@link #PRELOAD_TOLERANCE_MS}）成功加载过字幕 JSON →
     *     基本可认定是新音轨预加载的字幕 → 保留 cues，只重置索引，按新进度重算；
     *  2) 否则进入「待确认」：**立即挂起字幕显示**（cues 不再参与渲染，悬浮窗显示「无字幕」），
     *     避免新音轨从 0 秒开始播上一音轨的字幕。等 {@link #NO_SUBTITLE_GRACE_MS}：
     *       - 期间有新 JSON 到达 → 新音轨有字幕，自动解除挂起并正常显示；
     *       - 期间没有任何 JSON → 判定新音轨无字幕：清空 cues，并**自动关闭悬浮窗**，
     *         播放页按钮随 hasSubtitles()==false 显示「无字幕」。
     *
     * 若窗口是本次判定自动关掉的，之后字幕 JSON 一旦到达会自动把窗口恢复回来
     * （只恢复"自动关掉"的；用户手动关掉的不会被重新打开）。
     *
     * ── v27：**假换轨保护** ──
     * 上面的流程有个前提：调用方只在**真的换轨**时才喊我们。但 `AudioPlaylist.previous()`
     * 这类方法在第一轨上按「上一首」时是 no-op（音频继续播、不跳转），却照样被调用。
     * 于是 3 秒后必然判定「无字幕」→ 清空 cues + 关窗。
     * 除了在 {@link PlayerSourceHook} 侧加「曲目序号二次确认」之外，这里再加一道与
     * App 内部结构无关的兜底：真换轨时新音轨从 0 开始播，**播放位置必然大幅回退**；
     * 若整个待确认窗口内位置只增不减（且确实收到了位置回调），说明没换轨 →
     * 撤销判定、保留 cues、按当前进度重算当前字幕行（日志 `SPURIOUS track change`）。
     */
    public void onTrackChanged(String where) {
        onTrackChanged(where, false, 0L);
    }

    /**
     * 【973】带「换轨来源是否权威」的入口。
     *
     * @param indexVerified true = 来自权威序号门（状态 Map 的 {@code currentIndex} 变了），
     *                      数据层据此跳过「疑似假换轨」的旧兜底，见 {@link #pendingIndexVerified}。
     */
    public void onTrackChanged(String where, boolean indexVerified) {
        onTrackChanged(where, indexVerified, 0L);
    }

    /**
     * 【980】带「目标播放列表身份」的入口 —— 反向切轨残留修复的落点。
     *
     * @param newIdentity 本次换轨<b>目标</b>的播放列表身份（{@code PlayerSourceHook#makeIdentity}）。
     *                    0 = 老信号路径给不出身份，此时行为与本版之前完全一致。
     */
    public void onTrackChanged(String where, boolean indexVerified, long newIdentity) {
        onTrackChanged(where, indexVerified, newIdentity, 0L);
    }

    /**
     * 【code 1004】再带上「目标选择键」的入口 —— 「宿主早已为新轨取回字幕」修复的落点。
     *
     * <p>为什么单靠 {@code newIdentity} 不够（本轮 bug 的根因）：宿主切音轨时
     * {@code currentIndex} 立刻翻，而身份键里的**时长**要等新音源装载完才有值 ——
     * 新轨的字幕 JSON 恰恰落在中间（真机实测 165ms 之后就到了），于是盖印时身份还是旧的。
     * 5.9s 后换轨通知才到，模块就把这份**新轨**的 JSON 当「上一轨的旧数据」隔离掉 ⇒ 永久「无字幕」。
     *
     * @param newSelection 本次换轨<b>目标</b>的选择键
     *                     （{@code PlayerSourceHook#makeSelection}；0 = 老信号路径给不出）。
     */
    public void onTrackChanged(String where, boolean indexVerified, long newIdentity,
                               long newSelection) {
        // 【980 / code 1004】先看这份 cues 是不是「切回的原轨」或「宿主早已为新轨取回的」
        // —— 是就直接恢复渲染，不进挂起/软裁决。
        if (tryResumeFromCache(where, newIdentity, newSelection)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long token;
        boolean suspended;
        boolean reopenedByTrack = false;
        long ago;
        int cueCount;
        // 【1.21.15 问题 1】本次待确认窗口等多久 —— 有缓存字幕时给慢请求更长时间
        long grace = NO_SUBTITLE_GRACE_MS;
        synchronized (lock) {
            ago = lastLoadJsonMs > 0 ? (now - lastLoadJsonMs) : Long.MAX_VALUE;
            cueCount = cues.size();
            pendingToken++;                 // 作废上一次排队的判定
            token = pendingToken;
            pendingIndexVerified = indexVerified;   // 【973】本次判定的来源
            lastScannedSecond = Integer.MIN_VALUE;
            // v29：窗口若是被「播放结束」自动关掉的，换轨说明又有新内容要播 → 恢复窗口
            if (autoClosedForPlaybackEnded) {
                autoClosedForPlaybackEnded = false;
                floatingWindowOpen = true;
                reopenedByTrack = true;
            }
            if (ago <= PRELOAD_TOLERANCE_MS) {
                // 刚加载过字幕 JSON：大概率就是新音轨的 → 保留
                pendingTrackDecision = false;
                pendingSoftSuspend = false;
                pendingStartPosMs = -1L;
                pendingSawPositionReset = false;
                pendingPosSamples = 0;
                suspended = false;
            } else {
                // 无法确认新音轨是否有字幕 → 挂起显示，稍后判定
                pendingTrackDecision = true;
                pendingBaselineLoadMs = lastLoadJsonMs;
                // 【2.1.3 问题 1】手上已有 cues ⇒ 本作品**已证明有字幕**，本次挂起是软挂起：
                //   不丢抓屏镜像、不让显示层降级成「无字幕」。原实现对两者都无条件执行，
                //   导致挂起瞬间（实测 501ms 后）UI 就从正常跳到「无字幕」并关窗，
                //   而 JSON 往往几百毫秒~十几秒后才到（日志实证 1.3s / 5.5s / 11.1s）。
                //   手上没有 cues 时才按旧口径清镜像（真无字幕作品）。
                pendingSoftSuspend = cueCount > 0;
                // 【code 960】**两种挂起都清掉抓屏镜像**。
                //   原实现只在硬挂起（手上没 cues）时清，软挂起留着「继续显示」——
                //   于是切到**真正无字幕**的音轨时，上一轨的字幕会一直挂在悬浮窗/状态栏上
                //   （用例 7.2），最长能挂满整个 15s 裁决窗。
                //   现在显示层一律清空：cues / subtitleLineSet **一个都不动**（防假阴性的初衷
                //   原样保留），JSON 晚到时 loadFromJsonArrayInternal 照旧能恢复显示。
                currentSubtitles = new ArrayList<>();
                // 【973】上一轨的 cue 从此**不再参与渲染**（数据一个都不删，见字段说明）。
                //   只清 currentSubtitles 挡不住裁决窗之后 —— 那时 pendingTrackDecision 会被放开，
                //   旧 cues 又变成可渲染的 ⇒ 新轨 0 秒起播会按旧列表匹配出上一轨的字幕。
                previousTrackCuesHidden = true;
                // 【code 975】Ari 2026-10-07 晚真机复测：「从有字幕音轨切换到无字幕音轨时，
                //   原先音轨的字幕还是会保留，**并没有切换到无音轨状态并关掉悬浮窗**」。
                //   ⇒ 换轨瞬间就**立刻**把本音轨按「无字幕」呈现：
                //     ① softNoSubtitles 置位 ⇒ 胶囊/状态卡/悬浮窗三处口径统一变成「无字幕」；
                //     ② 上面的 previousTrackCuesHidden 保证旧 cue 一行都渲染不出来；
                //     ③ 下面（见本方法末尾）软挂起也**排提前收窗**，5 秒内没有新 JSON 就关窗。
                //   ⚠️ 这不是「永久结论」：任何一次字幕 JSON 到达都会由
                //     loadFromJsonArrayInternal 自动清掉 softNoSubtitles 并把窗口开回来，
                //     所以真机上 JSON 晚到 5.5s / 11.1s 的那些轨只表现为「先空后自动恢复」，
                //     不会再出现「旧轨字幕一直挂着」这种**错误内容**。
                softNoSubtitles = true;
                // 记录裁决用的位置基线：真换轨时位置会回退到 0 附近
                pendingStartPosMs = playbackPositionMs;
                pendingSawPositionReset = false;
                pendingPosSamples = 0;
                suspended = true;
                // 【1.21.15 问题 1】窗口分级：手上有缓存 cues = 这部作品有字幕，
                // 大概率只是新音轨的 JSON 还在路上（冷请求实测能拖到 15s），多等一会儿。
                grace = cueCount > 0 ? NO_SUBTITLE_GRACE_MS_CACHED : NO_SUBTITLE_GRACE_MS;
            }
        }
        XposedCompat.log("[DLsiteSoundFloat] track changed via " + where
                + (indexVerified ? " [index-gate]" : " [legacy-signal]")
                + " | lastJson=" + (ago == Long.MAX_VALUE ? "never" : ago + "ms ago")
                + " | cues=" + cueCount
                + " | pos=" + (playbackPositionMs < 0 ? "?" : playbackPositionMs + "ms")
                + (suspended
                        ? " -> SUSPEND subtitles, wait " + grace + "ms"
                                + (grace > NO_SUBTITLE_GRACE_MS ? " (had cues)" : "")
                                + (pendingSoftSuspend ? " [soft: display cleared, data kept]" : "")
                                + " [973 previous-track cues quarantined]"
                        : " -> recent json, keep cues"));
        if (reopenedByTrack) {
            XposedCompat.log("[DLsiteSoundFloat] new track after playback end -> reopen floating window");
        }
        notifyObservers();
        if (suspended) {
            mainHandler.postDelayed(() -> resolvePendingTrack(token), grace);
            // 【code 942】提前收窗：比裁决窗更早把悬浮窗关掉（字幕 JSON 晚到会自动开回来）。
            //
            // 【2.1.3 问题 1 / code 960 折中】软挂起（手上有 cues）**不排**这个任务：
            //   实测假阴性的 JSON 延迟是 1311ms / 5.5s / 11.1s，全部落在 15s 裁决窗内 ——
            //   5s 就关窗只会给这些**其实有字幕**的轨凭空加一次「关窗 → 自动开回」的窗口闪，
            //   而用例 7.2 要的是「立刻不再显示上一轨的字幕」，这点**显示层清空**已经做到了
            //   （见上面 currentSubtitles），根本不需要动窗口。
            //   于是最终时序：换轨 → 立刻清内容 → 窗口留着 → JSON 在裁决窗内到达就原地恢复
            //   （无闪，2.1.3 的收益保住）→ 裁决窗到点仍无 JSON 才由 resolvePendingTrack 收窗。
            //   硬挂起（手上一片空白、真可能无字幕）保留 code 942 的 5s 提前收窗，行为不变。
            // 【code 975】软挂起**也**排提前收窗。
            //   2.1.3 / code 960 的原设计是「软挂起不关窗」，理由是「关窗 = 字幕凭空消失」——
            //   但那个前提在 960 里已经被自己推翻了：**挂起瞬间显示层就已清空**
            //   （currentSubtitles = new ArrayList() + previousTrackCuesHidden），
            //   窗口里本来就是空的，留着它只是「一块空白浮着」。
            //   Ari 2026-10-07 晚明确要求「切换到无音轨状态**并关掉悬浮窗**」⇒ 两种挂起一视同仁。
            //   代价：JSON 在 5s 之后才到（实测 5.5s / 11.1s）的那些轨会「先关窗、JSON 到了再自动开回」，
            //   表现为一次轻微的窗口闪 —— 这是「错误内容一直挂着」与「偶尔闪一下」之间的取舍，
            //   本轮按 Ari 的指令选后者。
            mainHandler.postDelayed(() -> provisionalEarlyClose(token),
                    Math.min(NO_SUBTITLE_EARLY_CLOSE_MS, grace));
            if (pendingSoftSuspend) {
                LogGate.debug("[DLsiteSoundFloat] [code 975] soft suspend -> display cleared,"
                        + " window closes in " + Math.min(NO_SUBTITLE_EARLY_CLOSE_MS, grace)
                        + "ms unless json arrives (cues=" + cueCount + " kept)");
            }
        }
    }

    /**
     * 【980】由 {@code PlayerSourceHook} 在每次读到<b>活跃播放列表</b>时推送其身份。
     *
     * <p>只用于给新加载的 cues 盖「主人」印章（见 {@link #cuesOwnerIdentity}），
     * <b>不触发任何状态变更</b> —— 宿主会交替读到多个列表实例，若拿它直接驱动 UI，
     * 就会把「多实例读数」变成字幕窗口的抖动源。
     *
     * @param identity 播放列表身份（{@code PlayerSourceHook#makeIdentity}）
     */
    public void noteObservedPlaylistIdentity(long identity) {
        observedPlaylistIdentity = identity;
    }

    /**
     * 【980】该身份是不是「手上这份 cues 的主人」—— 是的话，切回它可以直接恢复渲染。
     *
     * <p>无锁读（{@link #cuesOwnerIdentity} 是 volatile）：本方法会被
     * {@code PlayerSourceHook} 的去抖闸门高频调用，不能有任何锁竞争。
     */
    public boolean ownsCuesFor(long identity) {
        return identity != 0L && identity == cuesOwnerIdentity;
    }

    /**
     * 【code 1004】由 {@code PlayerPositionHook} 推送「宿主当前选择」的选择键
     * （{@code PlayerSourceHook#makeSelection}；0 = 未知）。
     *
     * <p>与 {@link #noteObservedPlaylistIdentity} 一样<b>只写一个 volatile 字段</b>，
     * 不触发任何状态变更 —— 它只是给「加载那一刻」留个证据，真正的判定在
     * {@link #loadFromJsonArrayInternal} 与 {@link #tryResumeFromCache} 里。
     *
     * <p>⚠️ 必须在 {@code duration == 0} 的装载期读数上也调用（这正是它的价值所在）：
     * 宿主切音轨时 {@code currentIndex} 先翻、时长后到，新轨字幕 JSON 就落在中间。
     */
    public void noteObservedPlaylistSelection(long selection) {
        observedPlaylistSelection = selection;
    }

    /**
     * 【code 1004】该选择键是不是「手上这份 cues 的主人」—— 且盖章时宿主的选择**确实已经
     * 跑在身份前面**（= 这份 cues 是宿主为新轨提前取回的，不是上一轨的残留）。
     *
     * <p>无锁读（两个字段都是 volatile）：本方法会被 {@code PlayerSourceHook} 的去抖闸门
     * 高频调用，不能有任何锁竞争。
     *
     * <p>⚠️ 与 {@link #ownsCuesFor} 的分工：那个认「切回**同一条**轨」，本方法认
     * 「cues 其实**已经是新轨**的了」——两者互不替代（见 code 980 / code 1004 两段注释）。
     */
    public boolean ownsCuesForSelection(long selection) {
        return PlaylistKey.cuesBelongToTarget(
                cuesLoadedAheadOfIdentity, cuesOwnerSelection, selection);
    }

    /**
     * 【980】**反向切轨残留的根修**：切回的正是「手上 cues 的主人」时，直接恢复渲染。
     *
     * <p>── 旧实现错在哪 ──
     * 恢复路径只有一条：{@link #loadFromJsonArrayInternal}（宿主重发 JSON）。
     * 但宿主对**同一轨**的响应有缓存 —— 切走再切回来**不会重新发请求**，
     * 于是「切到无字幕轨 → 切回有字幕轨」必然卡在「无字幕」：
     * <pre>
     *   软挂起 ⇒ softNoSubtitles=true（三处 UI 变「无字幕」）
     *   ⇒ 15s 软裁决定案（cues 保留但 previousTrackCuesHidden 挡住渲染）
     *   ⇒ 无新 JSON ⇒ **永久**停在「无字幕」，而数据其实一直在 cues 里
     * </pre>
     * 真机日志 {@code LSPosed_20261007_222354}（22:23:18 加载 39 cues → 22:23:38 切走 →
     * 22:23:58.746 切回，仍是软挂起）。
     *
     * <p>── 本方法的判据为什么安全 ──
     * {@link #cuesOwnerIdentity} 只在**一次成功的 JSON 加载**时盖章，所以
     * 「身份一致」= 这份 cue 列表就是这条轨的字幕，渲染它不可能画错内容。
     * 身份由 {@code (trackCount, durationMs, currentIndex)} 三元组打包，
     * 跨轨 / 跨作品必变；三者全同才是同一条轨。
     *
     * @return true = 已按缓存恢复（调用方不必再走挂起 / 裁决流程）
     */
    private boolean tryResumeFromCache(String where, long newIdentity, long newSelection) {
        boolean byIdentity = newIdentity != 0L && newIdentity == cuesOwnerIdentity;
        // 【code 1004】第二条认领路径：这份 cues 加载时宿主的选择**已经跑在身份前面**，
        //   而本次换轨的目标恰好就是那个选择 ⇒ 它是**新轨**的字幕（宿主早已取回，不会重发）。
        //   真机 2026-10-10 18:41:04.429 就是这条：84 cues 在 18:40:58.556 到手（身份印章还是 idx=1），
        //   换轨通知 5.9s 后才到（idx=3）——旧判据只会把它隔离成「上一轨的旧数据」⇒ 永久「无字幕」。
        boolean bySelection = ownsCuesForSelection(newSelection);
        if (!byIdentity && !bySelection) {
            return false;
        }
        final String code = byIdentity ? "[code 980]" : "[code 1004]";
        final String why = byIdentity
                ? " -> BACK to the playlist these cues belong to"
                : " -> cues were already the NEW track's (selection ran ahead of identity)";
        final String how = byIdentity
                ? "host reuses its own json, no need to wait"
                : "json arrived before the switch was reported, no need to wait";
        int cueCount;
        long posNow;
        boolean reopened = false;
        boolean wasBlocked;
        synchronized (lock) {
            cueCount = cues.size();
            if (cueCount == 0) {
                // 印章与数据应当同生同灭；真碰上不一致就放弃快路径，交回旧流程。
                return false;
            }
            wasBlocked = previousTrackCuesHidden || softNoSubtitles || pendingTrackDecision;
            // 撤销「挂起 / 软裁决 / 上一轨隔离」：数据一直是好的，只是被挡在渲染之外。
            pendingTrackDecision = false;
            pendingSoftSuspend = false;
            softNoSubtitles = false;
            previousTrackCuesHidden = false;
            pendingIndexVerified = false;
            pendingToken++;                 // 作废已排队的「裁决 / 提前收窗」任务
            pendingStartPosMs = -1L;
            pendingSawPositionReset = false;
            pendingPosSamples = 0;
            lastScannedSecond = Integer.MIN_VALUE;
            currentCueIndex = -1;
            posNow = playbackPositionMs;
            if (posNow >= 0) {
                int sec = (int) Math.floor(posNow / 1000.0);
                lastScannedSecond = sec;
                int idx = computeIndexAtSecondLocked(sec);
                if (idx >= 0) {
                    currentCueIndex = idx;
                }
            }
            // 屏上镜像清掉：本轨内容由 cue 路径负责，下一帧就是正确的行
            currentSubtitles = new ArrayList<>();
            if (autoClosedForNoSubtitle) {
                autoClosedForNoSubtitle = false;
                floatingWindowOpen = true;
                reopened = true;
            }
        }
        XposedCompat.log("[DLsiteSoundFloat] " + code + " track changed via " + where
                + why
                + " | cues=" + cueCount
                + " | pos=" + (posNow < 0 ? "?" : posNow + "ms")
                + " -> RESUME from cache (" + how + ")"
                + (wasBlocked ? " [suspend/soft-verdict/quarantine revoked]" : ""));
        if (reopened) {
            XposedCompat.log("[DLsiteSoundFloat] " + code + " return to cached track"
                    + " -> reopen floating window (auto-closed earlier)");
        }
        notifyObservers();
        return true;
    }

    /**
     * 【code 942】提前收窗：待确认期间 NO_SUBTITLE_EARLY_CLOSE_MS 内没有字幕 JSON 到达，
     * 就先把悬浮窗关掉 —— 不下「无字幕」结论、不动 cues，只关窗。
     *
     * 关窗后：JSON 到了 -> autoClosedForNoSubtitle 让 loadFromJsonArrayInternal 自动开回窗口；
     * 等到裁决窗到点 -> resolvePendingTrack 正常裁决（窗口已关，裁决只补数据侧结论）；
     * 裁决发现是假换轨 -> SPURIOUS 分支负责开回窗口（见 resolvePendingTrack）。
     * 用户在窗口期内手动动过窗口（setFloatingWindowOpen 会清 autoClosedForNoSubtitle），
     * 且 floatingWindowOpen 已变 -> 本方法卫语句下安全 no-op。
     */
    private void provisionalEarlyClose(long token) {
        boolean closed = false;
        synchronized (lock) {
            if (token != pendingToken || !pendingTrackDecision || !floatingWindowOpen) {
                return; // 已裁决 / 又换轨 / 字幕已到 / 窗口已不在
            }
            floatingWindowOpen = false;
            autoClosedForNoSubtitle = true;
            closed = true;
        }
        if (closed) {
            LogGate.debug("[DLsiteSoundFloat] no subtitle json yet"
                    + " -> auto-closed floating window early (will reopen if json arrives)");
            notifyObservers();
        }
    }

    /** 待确认窗口到点：判定新音轨到底有没有字幕。 */
    private void resolvePendingTrack(long token) {
        boolean noSubtitles = false;
        boolean spurious = false;
        boolean closed = false;
        boolean reopenedAfterSpurious = false; // 【code 942】假换轨回收提前收窗
        // 【2.1.3 问题 1】本次软裁决是否「忍住没降级」—— 仅用于日志取证
        boolean heldSoftVerdict = false;
        int cueCount = 0;
        int subCount = 0;
        int samples = 0;
        long startPos = -1L;
        long curPos = -1L;
        boolean sawReset = false;
        synchronized (lock) {
            if (token != pendingToken || !pendingTrackDecision) {
                return; // 期间又换轨、或新字幕已到达（都已作废本次判定）
            }
            pendingTrackDecision = false;
            // 【973】本次判定的来源：权威序号门 ⇒ 不做假换轨兜底（见 pendingIndexVerified）
            final boolean indexVerified = pendingIndexVerified;
            pendingIndexVerified = false;
            pendingSoftSuspend = false; // 【2.1.3 问题 1】与挂起同生命周期
            cueCount = cues.size();
            samples = pendingPosSamples;
            startPos = pendingStartPosMs;
            curPos = playbackPositionMs;
            sawReset = pendingSawPositionReset;
            pendingStartPosMs = -1L;
            pendingSawPositionReset = false;
            pendingPosSamples = 0;
            if (lastLoadJsonMs <= pendingBaselineLoadMs) {
                // 等待期内没有任何新的字幕 JSON。**下结论前先怀疑一次「其实没换轨」**：
                // 这 3 秒里播放位置若一直在往前走、从未回退，说明音频根本没重开新轨
                // （典型场景：在**第一轨**按「上一首」，App 是 no-op，继续播第一轨）。
                // 此时若照旧判定「无字幕」，就会清空 cues 并关掉悬浮窗 —— 这正是 v27 要修的 bug。
                // ⚠️ v28 修正：v27 这里用「位置从未回退」当假换轨的判据，前提**不成立** ——
                // 实测 54 次换轨里 52 次 pos=0ms（切轨瞬间位置就被清零），窗口内位置从 0
                // 一路往上涨，永远满足「没回退」→ **所有真换轨都被误判成假换轨**，
                // 于是切到无字幕音轨时字幕不切、继续播旧字幕（问题 2）。
                // 现在改成**严格要求观察到"位置确实大幅回退"**才算假换轨；
                // 否则按真换轨处理（照旧判「无字幕」），把默认行为改回正确的一侧。
                // 【973】indexVerified（权威序号门）时**不做**这两条假换轨兜底：
                //   序号变了就是换了轨，而兜底是为老信号路径写的；在真换轨上误判会直接
                //   撤销挂起 ⇒ 上一轨的 cue 又变成可渲染 ⇒ 用户报的 bug 原样复现。
                //   （真机 2026-10-07 两份日志里 SPURIOUS 从未触发，佐证它在序号门上本就不参与。）
                if (!indexVerified && pendingSawPositionReset) {
                    spurious = true;
                    // 撤销挂起。**不**用 playbackPositionMs 重算字幕行 ——
                    // 那个值在换轨瞬间会被清零/残留旧值，据其重算会跳回已播过的字幕（问题 1）。
                    // 保留原有 currentCueIndex，交给后续正常的位置回调推进。
                    lastScannedSecond = Integer.MIN_VALUE;
                    previousTrackCuesHidden = false;   // 【973】没换轨 ⇒ 旧 cues 仍是本轨的
                } else if (!indexVerified && samples > 0
                        && pendingStartPosMs > POSITION_RESET_TOLERANCE_MS
                        && playbackPositionMs >= 0
                        && playbackPositionMs > pendingStartPosMs) {
                    // 位置基准明显大于 0（说明基线取到的是"旧轨还在播"的位置），
                    // 而当前位置比它还大 → 音频一直在往前走、从未重开 → 确实没换轨。
                    spurious = true;
                    lastScannedSecond = Integer.MIN_VALUE;
                    previousTrackCuesHidden = false;   // 【973】同上
                } else {
                    // 等待期内没有任何新的字幕 JSON → 该音轨没有字幕
                    // 【1.21.16 问题 1】这里原来无条件 `cues = new ArrayList<>()`，
                    // 把「10s 内没等到 JSON」当成「该音轨无字幕」的**永久**结论。
                    // 实测 JSON 延迟能到 11973ms，且 R6 是「同一作品 3.5s 前刚有 49 cues」，
                    // 所以有 cues 在手时**绝不能销毁数据** —— 改成软裁决：只让显示层降级。
                    noSubtitles = true;
                    // 【1.21.15 问题 1】留个时间戳：这次裁决若被后来的 JSON 打脸，
                    // 加载字幕时会把「假阴性」直接写进日志。
                    lastNoSubtitleVerdictMs = SystemClock.uptimeMillis();
                    if (cueCount > 0) {
                        // **软裁决**：保留 cues / subtitleLineSet，只标记「本音轨按无字幕显示」。
                        // 晚到的 JSON 一到就自动解除（见 loadFromJsonArrayInternal）。
                        //
                        // 【2.1.3 问题 1】软裁决也**不再**置 softNoSubtitles：
                        //   本次挂起是「手上有 cues」的软挂起 —— 本作品已被证明有字幕，
                        //   「没等到 JSON」不足以推翻这一点（实测冷请求可超 15s）。
                        //   置位会让三处 UI 一起变「无字幕」，正是用户报的现象。
                        //   现在降级为「保持数据 + 留时间戳取证」：JSON 到了
                        //   loadFromJsonArrayInternal 会正常刷新，超时也不误报。
                        //   （真判无字幕只在**手上没有 cues** 的硬裁决路径发生。）
                        //
                        // 【code 960】显示层**保持已清空**：进入软挂起时 currentSubtitles
                        //   就已经清了（用例 7.2 —— 切到真正无字幕的轨时不留上一轨的字幕），
                        //   这里再清一次只为口径明确（期间不会有新的抓屏镜像写入：
                        //   setCurrentSubtitles 在待确认期间直接 return）。
                        currentSubtitles = new ArrayList<>();
                        currentCueIndex = -1;
                        lastScannedSecond = Integer.MIN_VALUE;
                        // 注意：**不**清 playbackPositionMs / lastFedMs —— 位置是位置，
                        // 与「有没有字幕」无关；清了反而会让恢复后的首帧字幕落错行。
                        // cues / subtitleLineSet 同样一个都不动（防假阴性）。
                        heldSoftVerdict = true;
                    } else {
                        // 手上本来就没有任何 cues（这部作品压根没加载过字幕）→ 硬裁决，
                        // 行为与旧版一致：清空 + 关窗。
                        cues = new ArrayList<>();
                        subtitleLineSet.clear();
                        // 【980】数据真被销毁了 ⇒ 撤销「主人」印章（否则会拿空 cues 去「恢复」）
                        cuesOwnerIdentity = 0L;
                        // 【code 1004】选择键印章与「选择跑在身份前面」同生共死：数据没了就没有归属可谈。
                        cuesOwnerSelection = 0L;
                        cuesLoadedAheadOfIdentity = false;
                        currentSubtitles = new ArrayList<>();
                        currentCueIndex = -1;
                        lastScannedSecond = Integer.MIN_VALUE;
                        playbackPositionMs = -1L;
                        lastFedMs = -1L;
                    }
                    if (floatingWindowOpen) {
                        // 【2.1.3 问题 1】曾让软挂起（手上有 cues）**留着窗口不关**：
                        //   那时软挂起仍在显示上一轨字幕，关窗 = 字幕凭空消失。
                        // 【code 960 折中】前提已变：显示层在挂起瞬间就清空了，
                        //   等满整个裁决窗仍无 JSON，窗口里已经空了这么久 —— 这时收窗是净收益。
                        //   所以软硬一律关窗（软挂起先不排 5s 提前收窗，就是为了不在
                        //   JSON 5.5s/11.1s 到达的那些轨上闪一下，见 onTrackChanged 的注释）。
                        //   autoClosedForNoSubtitle 照旧置位：万一 JSON 在裁决窗之后才到，
                        //   loadFromJsonArrayInternal 的自动开回路径仍然接得住。
                        floatingWindowOpen = false;
                        autoClosedForNoSubtitle = true;
                        closed = true;
                    }
                }
            } else {
                subCount = cues.size();
            }
            // 【code 942】提前收窗收错了（其实没换轨）-> 立刻开回来，并撤销这份记忆：
            // 否则窗口会被错杀到下一次字幕加载，且那时可能把用户已手动关掉的窗口擅自打开。
            // 安全性：setFloatingWindowOpen 手动开关会清 autoClosedForNoSubtitle，
            // 故此处为 true 当且仅当「是本模块自动关的」（提前收窗或旧裁决路径）。
            if (spurious && autoClosedForNoSubtitle) {
                autoClosedForNoSubtitle = false;
                floatingWindowOpen = true;
                reopenedAfterSpurious = true;
            }
        }
        if (spurious) {
            LogGate.debug("[DLsiteSoundFloat] track decision: SPURIOUS track change"
                    + " (sawPositionReset=" + sawReset + ", samples=" + samples
                    + ", startPos=" + startPos + ", curPos=" + curPos
                    + ") -> keep cues=" + cueCount);
        } else if (noSubtitles) {
            if (heldSoftVerdict) {
                // 【2.1.3 问题 1 + code 960 折中】软裁决仍**不下**「本音轨无字幕」的结论
                // （cues 保留、UI 不降级、不产生假阴性误报），但显示层早已清空、窗口里
                // 空了整整一个裁决窗 —— 这时收窗。JSON 若晚到（>15s）仍会自动开回来。
                LogGate.debug("[DLsiteSoundFloat] [code 960] soft verdict -> no json within grace,"
                        + " display stays cleared (cues=" + cueCount + " kept,"
                        + " no false \"no subtitles\")"
                        + " [973 prev-track cues quarantined, not renderable]"
                        + (closed ? " -> auto-closed floating window" : ""));
            } else {
                XposedCompat.log("[DLsiteSoundFloat] track decision: NO subtitles for this track"
                        + " (cues were " + cueCount + ")"
                        + (closed ? " -> auto-closed floating window" : ""));
            }
        } else {
            XposedCompat.log("[DLsiteSoundFloat] track decision: subtitle json arrived"
                    + " -> keep cues=" + subCount);
        }
        if (reopenedAfterSpurious) {
            LogGate.debug("[DLsiteSoundFloat] track change was spurious"
                    + " -> reopen floating window (early-closed earlier)");
        }
        notifyObservers();
    }

    // ======================================================================
    // 播放结束 → 自动关窗（v29）
    // ======================================================================

    /**
     * 由 {@link io.github.ariinyume.dlsitesoundfloat.hook.PlayerPositionHook} 采集到的 ExoPlayer 播放状态。
     *
     * 需求：**音频播放结束后，悬浮窗要自动关闭**。
     *
     * 旧版完全没有「播放结束」这个概念 —— 窗口打开后只会因三种原因关闭：
     * 用户点 ✕ / 用户点按钮切换 / 判定「本音轨无字幕」。所以一曲（或一整个播放列表）
     * 播完停在末尾时，窗口会一直挂着并显示最后一行字幕。
     *
     * 现在的行为：
     *   - 状态进入 {@link #PSTATE_ENDED} → 自动关窗，并记住「是自动关的」；
     *   - 之后状态回到 {@link #PSTATE_BUFFERING} / {@link #PSTATE_READY}（= 重新开播：
     *     重播本曲、或自动连播下一曲）→ 自动把窗口恢复回来；
     *   - 用户显式开关过窗口 → 撤销这份记忆，绝不擅自打开（见 {@link #setFloatingWindowOpen}）。
     *
     * 不处理 {@link #PSTATE_IDLE}：加载新内容时也会短暂 IDLE，据此关窗会误伤。
     */
    public void setPlaybackState(int state) {
        if (state == lastPlaybackState) {
            return;
        }
        int prev = lastPlaybackState;
        lastPlaybackState = state;
        if (state == PSTATE_ENDED) {
            onPlaybackEnded(prev);
        } else if (autoClosedForPlaybackEnded
                && (state == PSTATE_BUFFERING || state == PSTATE_READY)) {
            boolean reopened = false;
            synchronized (lock) {
                if (autoClosedForPlaybackEnded) {
                    autoClosedForPlaybackEnded = false;
                    floatingWindowOpen = true;
                    reopened = true;
                }
            }
            if (reopened) {
                LogGate.debug("[DLsiteSoundFloat] playback resumed (state " + prev + "->" + state
                        + ") -> reopen floating window (auto-closed on playback end)");
                notifyObservers();
            }
        }
    }

    // ======================================================================
    // 播放 / 暂停（v45：暂停 → 状态栏字幕消失并还原原始状态栏）
    // ======================================================================

    /**
     * 播放意愿三态：0 = 已暂停，1 = 播放中，-1 = 未知。
     *
     * 为什么要三态而不是 boolean：{@code getPlaybackState()} 只有 IDLE/BUFFERING/READY/ENDED，
     * **READY 同时覆盖「正在播」和「已暂停」**，只有 {@code getPlayWhenReady()} 能区分。
     * 而真机上 hook 有可能全挂（ROM 改类名 / expo-audio 换实现）——那时宁可当成「在播」
     * 继续显示字幕，也不要因为读不到状态把功能整个弄没。
     */
    private volatile int playingState = -1;

    /** 由 {@link io.github.ariinyume.dlsitesoundfloat.hook.PlayerPositionHook} 采集到的播放意愿。 */
    public void setPlaying(boolean playing) {
        int v = playing ? 1 : 0;
        if (v == playingState) {
            return;
        }
        int prev = playingState;
        playingState = v;
        LogGate.debug("[DLsiteSoundFloat] playback " + (playing ? "resumed" : "paused")
                + " (playing " + prev + "->" + v + ")"
                + (playing ? "" : " -> status bar subtitle will hide"));
        notifyObservers();
    }

    /** true = 在播或状态未知（未知时按「在播」处理，保持宽容）。 */
    public boolean isPlayingOrUnknown() {
        return playingState != 0;
    }

    /** 最近一次已知的播放意愿原始值（-1 = 未知）。仅用于日志/排查。 */
    public int getPlayingState() {
        return playingState;
    }

    /**
     * 当前字幕行的播放时长（毫秒）；0 = 未知。
     *
     * 状态栏字幕的「单程滚动」用它当动画时长 —— 需求是「根据字幕的播放时长，缓慢而逐渐地
     * 从左播到右」，所以这里取的是 cue 的 endTime-startTime，而不是某个固定速度。
     */
    public long getCurrentCueDurationMs() {
        int idx;
        try {
            idx = findCurrentCueIndex();
        } catch (Throwable t) {
            return 0L;
        }
        synchronized (lock) {
            if (idx >= 0 && idx < cues.size()) {
                SubtitleCue c = cues.get(idx);
                double d = c.endTime - c.startTime;
                if (d > 0) {
                    return (long) (d * 1000.0);
                }
            }
        }
        return 0L;
    }

    private void onPlaybackEnded(int prevState) {
        final int from = prevState;
        mainHandler.postDelayed(() -> confirmPlaybackEnded(from), PLAYBACK_END_CONFIRM_MS);
    }

    /** 「播放结束」的延迟确认：这段时间内若已经又开始播（自动连播 / 重播），本次作废。 */
    private void confirmPlaybackEnded(int prevState) {
        if (lastPlaybackState != PSTATE_ENDED) {
            LogGate.debug("[DLsiteSoundFloat] playback end not confirmed (state came back to "
                    + lastPlaybackState + ") -> keep floating window");
            return;
        }
        boolean closed = false;
        synchronized (lock) {
            if (pendingTrackDecision) {
                // 正在判「新音轨到底有没有字幕」，交给那套流程（它自己会决定关不关窗），别抢
                return;
            }
            if (floatingWindowOpen) {
                floatingWindowOpen = false;
                autoClosedForPlaybackEnded = true;
                closed = true;
            }
        }
        LogGate.debug("[DLsiteSoundFloat] playback ended (state " + prevState + "->" + PSTATE_ENDED + ")"
                + (closed ? " -> auto-closed floating window" : " (window already closed)"));
        if (closed) {
            notifyObservers();
        }
    }

    // ======================================================================
    // 屏上文本镜像（降级通道）
    // ======================================================================

    public void setCurrentSubtitles(List<String> lines) {
        synchronized (lock) {
            if (pendingTrackDecision) {
                return; // 待确认期间不接受抓屏镜像
            }
            boolean changed = !lines.equals(currentSubtitles);
            if (!changed) {
                return;
            }
            currentSubtitles = new ArrayList<>(lines);
        }
        notifyObservers();
    }

    public void setSubtitlesVisible(boolean visible) {
        synchronized (lock) {
            if (subtitlesVisible == visible) {
                return;
            }
            subtitlesVisible = visible;
        }
        notifyObservers();
    }

    public boolean isSubtitlesVisible() {
        synchronized (lock) {
            return subtitlesVisible;
        }
    }

    /** 待确认（刚换轨、尚不知新音轨有无字幕）期间，字幕一律不显示。 */
    public boolean isSuspended() {
        synchronized (lock) {
            return pendingTrackDecision;
        }
    }

    /**
     * 【1.21.16 问题 1】本音轨判了「无字幕」但 cues 还在（软裁决）。
     * 显示层据此降级，数据层据此保住缓存等字幕晚到后自愈。
     */
    public boolean isSoftNoSubtitles() {
        return softNoSubtitles;
    }

    /**
     * 当前是否应当按「无字幕」显示 —— **唯一口径**。
     *
     * 四个来源：
     *   - 手上根本没 cues（真无字幕）；
     *   - 换轨待确认中（`isSuspended`，新音轨的字幕还没到）—— **但软挂起例外**；
     *   - 软裁决已下（本音轨 10s 内没等到 JSON，但 cues 保留着）；
     *   - 挂起期「硬」（手上无 cues）。
     *
     * 【2.1.3 问题 1】`pendingSoftSuspend` 除外：手上已有 cues = 本作品已证明有字幕，
     * 此时的挂起只是「新轨 JSON 在路上」，**不应**让三处 UI 一起降级成「无字幕」。
     * 原实现把 `pendingTrackDecision` 无条件算作无字幕，导致挂起瞬间 UI 就跳变
     * （实测挂起后 501ms 即 `noSub=true`），而 JSON 往往几百毫秒到十几秒后才到 ——
     * 这就是「App 有字幕、插件显示无字幕」的直接原因。
     *
     * ⚠️ 显示层（按钮 / 悬浮窗 / 状态栏）必须**全部**走这个函数，
     * 不要再各自拼 `!hasSubtitles() || isSuspended()` —— 那正是 1.21.16 之前
     * 「三处 UI 口径不一」和「底色提前 9.5 秒变暗」的来源。
     */
    public boolean shouldShowNoSubtitles() {
        synchronized (lock) {
            if (cues.isEmpty() || softNoSubtitles) {
                return true;
            }
            // 软挂起：cues 在手 ⇒ 维持正常显示，不降级
            return pendingTrackDecision && !pendingSoftSuspend;
        }
    }

    public List<String> getCurrentSubtitles() {
        synchronized (lock) {
            // 【973】previousTrackCuesHidden 也按「空」返回：上一轨的 cue 一律不参与渲染
            return (softNoSubtitles || previousTrackCuesHidden
                    || (pendingTrackDecision && !pendingSoftSuspend))
                    ? new ArrayList<>() : new ArrayList<>(currentSubtitles);
        }
    }

    public List<SubtitleCue> getCues() {
        synchronized (lock) {
            // 【973】previousTrackCuesHidden：数据保留，但一律按「空」返回（不参与渲染）
            return (pendingTrackDecision || softNoSubtitles || previousTrackCuesHidden)
                    ? new ArrayList<>() : new ArrayList<>(cues);
        }
    }

    /** 是否「当前有字幕可用」。注意：不把待确认当作无字幕，避免按钮文案闪烁。 */
    public boolean hasSubtitles() {
        synchronized (lock) {
            return !cues.isEmpty();
        }
    }

    /**
     * 文本是否命中已加载的字幕库（用于把抓屏结果里的界面文案过滤掉）。
     *
     * v18：由**包含匹配**改为**精确相等**。包含匹配会把曲目列表页的长标题
     * （如「Tr2_【独占欲失控】你不是叫其他人吧？…」）判成字幕行，
     * 使非播放页也满足「屏上有真字幕」而显示按钮。字幕行在屏幕上是独立 Text，
     * 与 cue 行完全一致，用 equals 既准又快（走 HashSet）。
     */
    public boolean isKnownSubtitleText(String text) {
        if (text == null) {
            return false;
        }
        String t = normalizeLine(text);
        if (t.isEmpty() || t.length() > MAX_SUBTITLE_LINE_LEN) {
            return false;   // 超长的绝对不是字幕行（标题 / 简介 / 列表项文案）
        }
        synchronized (lock) {
            if (pendingTrackDecision) {
                return false;
            }
            return subtitleLineSet.contains(t);
        }
    }

    public void setPlayerPageVisible(boolean visible) {
        synchronized (lock) {
            if (playerPageVisible == visible) {
                return;
            }
            playerPageVisible = visible;
        }
        notifyObservers();
    }

    public boolean isPlayerPageVisible() {
        synchronized (lock) {
            return playerPageVisible;
        }
    }

    private static String normalizeLine(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /**
     * 当前 cue 索引。
     * 优先用播放进度算出的索引；没有进度信息时回退到「屏上文本精确匹配」。
     */
    public int findCurrentCueIndex() {
        synchronized (lock) {
            // 【973】previousTrackCuesHidden：上一轨的 cue 不再定位（否则显示层会画出上一轨的句子）
            if (pendingTrackDecision || previousTrackCuesHidden || cues.isEmpty()) {
                return -1;
            }
            if (currentCueIndex >= 0 && currentCueIndex < cues.size()) {
                return currentCueIndex;
            }
            if (currentSubtitles.isEmpty()) {
                return -1;
            }
            String current = normalize(currentSubtitles);
            if (current.isEmpty()) {
                return -1;
            }
            for (int i = 0; i < cues.size(); i++) {
                if (normalize(cues.get(i).subtitles).equals(current)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(List<String> lines) {
        if (lines == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            sb.append(line.trim().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString().trim();
    }

    // ======================================================================
    // 悬浮窗开关 / 观察者
    // ======================================================================

    public void setFloatingWindowOpen(boolean open) {
        synchronized (lock) {
            if (floatingWindowOpen == open) {
                return;
            }
            floatingWindowOpen = open;
            // 任何「显式开关」（点播放页按钮 / 点 ✕）都代表用户的决定 →
            // 撤销所有「自动关窗」的记忆（无字幕 / 播放结束），
            // 避免之后字幕晚到或重新开播又把用户刚关掉的窗口打开。
            autoClosedForNoSubtitle = false;
            autoClosedForPlaybackEnded = false;
        }
        notifyObservers();
    }

    public boolean isFloatingWindowOpen() {
        synchronized (lock) {
            return floatingWindowOpen;
        }
    }

    public void toggleFloatingWindow() {
        setFloatingWindowOpen(!isFloatingWindowOpen());
    }

    public void addObserver(Runnable observer) {
        synchronized (observers) {
            observers.add(observer);
        }
    }

    public void removeObserver(Runnable observer) {
        synchronized (observers) {
            observers.remove(observer);
        }
    }

    private void notifyObservers() {
        synchronized (observers) {
            for (Runnable r : observers) {
                mainHandler.post(r);
            }
        }
    }
}
