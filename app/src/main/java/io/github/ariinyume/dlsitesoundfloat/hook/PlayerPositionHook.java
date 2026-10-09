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

import android.os.SystemClock;

import io.github.ariinyume.dlsitesoundfloat.config.RemoteConfig;
import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;
import io.github.ariinyume.dlsitesoundfloat.util.Shape;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 播放进度 / 播放态 / 换轨采集 —— 让悬浮窗能「按时间轴」定位当前字幕行。
 *
 * <h3>🔴🔴 2.1.4：本文件因宿主 R8 混淆而整条重写</h3>
 * 老版本挂三个采集点，宿主 2.20.2 上<b>三个全灭</b>（真机日志原文）：
 * <pre>
 * [DLsiteSoundFloat:Pos] ExoPlayerImpl.getCurrentPosition hook failed: androidx.media3.exoplayer.ExoPlayerImpl
 * [DLsiteSoundFloat:Pos] ExoPlayerImpl.getPlaybackState hook failed: ...
 * [DLsiteSoundFloat:Pos] ExoPlayerImpl.setPlayWhenReady hook failed: ...
 * [DLsiteSoundFloat:Pos] ExoPlayerImpl.play hook failed: ...
 * [DLsiteSoundFloat:Pos] ExoPlayerImpl.pause hook failed: ...
 * [DLsiteSoundFloat:Pos] expo.modules.audio.AudioPlaylist.getCurrentTime hook failed: ...
 * [DLsiteSoundFloat:Pos] expo.modules.audio.AudioPlayer.getCurrentTime hook failed: ...
 * </pre>
 * 离线核实：{@code ExoPlayerImpl} <b>这个类已不存在</b>（media3 侧只剩
 * {@code androidx.media3.exoplayer.ExoPlayer} 接口，且只剩 4 个方法）；
 * expo-audio 的 {@code getCurrentTime()} 也被 R8 改名。
 *
 * <h3>✅ 替代方案：一个方法拿全三样</h3>
 * expo-audio 的 {@code AudioPlaylist} / {@code AudioPlayer} <b>类名存活</b>
 * （被 JS 按名 require），且它们都继承 {@code BaseAudioPlayer}，其中有<b>一个无参方法
 * 返回 {@code Map}</b> —— 混淆后叫 {@code y()}，返回的就是播放器状态 Map。实测键（离线反汇编确认）：
 * <pre>
 *   currentIndex   -&gt; Integer   曲目序号（换轨判据）
 *   trackCount     -&gt; Integer
 *   currentTime    -&gt; Double    播放位置（秒）
 *   duration       -&gt; Double    总时长（秒）
 *   playing        -&gt; Boolean   是否在播
 *   playbackState  -&gt; String    idle / buffering / ready / ended / unknown
 *   didJustFinish  -&gt; Boolean
 *   isBuffering / isLoaded / playbackRate / muted / volume / loop ...
 * </pre>
 * ⇒ <b>一个方法同时顶替老版三个采集点</b>，且键名是<b>字符串常量</b>（R8 不会改字符串池），
 * 所以这些键名<b>跨混淆版本稳定</b>。
 *
 * <h3>🔴 调用频率的坑（必须配兜底轮询）</h3>
 * 这个状态 Map 只在 <b>JS 主动取状态</b>时才会被构造 —— 不是高频回调。
 * 实测宿主 JS 并不会每秒去读它 ⇒ 只靠 hook 会导致「字幕长时间不换行」。
 * ⇒ 加一个<b>低频兜底轮询</b>（{@link #POLL_INTERVAL_MS}）主动调一次同一个方法。
 * 轮询只在「已见过播放器实例」之后启动。
 *
 * <h3>🔴🔴 【code 977】真机取证：读数到底有多稀疏</h3>
 * 日志 {@code LSPosed_20261007_204620} 量到：宿主**自调** {@code AudioPlaylist.y()} 的相邻间隔
 * 高达 <b>40~140 秒</b>（{@code since=43814ms / 108921ms}），而当时的兜底轮询已
 * 「15 秒无样本自动停机且永不重启」⇒ 切轨那一刻根本没人读状态 Map ⇒ 换轨检测必然失效。
 * 本条与 {@code PlayerSourceHook} 的「不再按时间重种基线」两处**必须同时存在**才闭环。
 *
 * <h3>🔴🔴 【code 978】真机取证：宿主同时持有 <b>多个</b> AudioPlaylist 实例</h3>
 * 日志 {@code LSPosed_20261007_211706}（用户在 21:17 从作品 A「通话记录1（简体中文版）」切到作品 B
 * 「寒がりの蛇獣人… 本編」）把三件事一次性钉死：
 * <ol>
 *   <li><b>{@code currentIndex} 恒为 0</b> —— 作品 A 的 6 轨列表、作品 B 的 1 轨列表、已停止列表，
 *       三者的 {@code currentIndex} <b>全是 0</b>。切换「作品 / 音轨」时它<b>根本不变化</b>
 *       ⇒ 977 及以前所有以序号为判据的换轨检测<b>一次都没触发</b>（这正是连修五轮无效的原因）。
 *       真正变化的是<b>播放列表身份</b>：{@code (trackCount, duration)} = {@code (6, 731.832s) → (1, 2431.085s)}。</li>
 *   <li><b>多个实例交替上报</b> —— 同一时刻 sLastPlayers 里既有「当前活跃列表」（{@code trackCount>0}、
 *       {@code haltReason=null}），也有「已停止的幽灵列表」（{@code trackCount=0}、{@code haltReason=<str>}），
 *       还有闲置的 {@code AudioPlayer}（{@code playing=false}、{@code isLoaded=false}）。
 *       它们的读数<b>交替</b>流进 {@link #consume} ⇒
 *       ① 进度被幽灵实例的 {@code currentTime=0.0} 反复拉回 0（字幕按旧 cue 列表匹配出<b>上一轨的句子</b>）；
 *       ② {@code playing} 每秒横跳 29 次（`playback resumed` / `paused` 交替刷屏）；
 *       ③ {@code playbackState} 被覆盖 ⇒ 真机 `playback end not confirmed (state came back to 3)` 出现 47 次。
 *       ⇒ 只有「当前活跃列表」这一路读数才有权更新仓库状态。</li>
 *   <li><b>{@code muted=true / volume=0.0} 不是 bug</b> —— 那是宿主「扬声器静音保护」
 *       （真机提示原文「因将透过设备扬声器输出，正在静音播放」），与有没有字幕无关。</li>
 * </ol>
 *
 * <h3>状态值映射</h3>
 * 仓库侧沿用 media3 的口径（1=IDLE / 2=BUFFERING / 3=READY / 4=ENDED），
 * 这里把 expo 的字符串状态映射过去，保证「播放结束自动关窗」等既有行为不变。
 */
public class PlayerPositionHook {
    private static final String TAG = "[DLsiteSoundFloat:Pos]";

    /** 仓库侧沿用的 media3 状态口径。 */
    private static final int PSTATE_IDLE = 1;
    private static final int PSTATE_BUFFERING = 2;
    private static final int PSTATE_READY = 3;
    private static final int PSTATE_ENDED = 4;

    private static final String EXO_IMPL = "androidx.media3.exoplayer.ExoPlayerImpl";

    /** 兜底轮询间隔（ms）。1 秒一次足够让字幕换行，且开销可忽略。 */
    private static final long POLL_INTERVAL_MS = 1000L;

    // ── 【code 977】轮询健壮性 + 诊断 ──────────────────────────────────────
    //
    // 🔴 977 之前：连续 {@link #POLL_IDLE_STOP_MS}(15s) 没有真实样本就 `sPollScheduled=false` 永久停机，
    //    而**唯一的重启入口 `ensurePolling()` 只在模块随进程加载时调一次** ⇒ 一旦停就再也不会恢复。
    //    真机日志铁证：`polling auto-stop (no sample for 107931ms)` 之后再无任何采样，
    //    恰好错过用户切轨的那一刻 ⇒ 换轨检测彻底失效。
    // 977 改为：空闲只**降频**（1s → 3s），只有「十分钟完全无样本」才真停。
    /** 没样本超过这么久 ⇒ 轮询降频（但仍继续）。 */
    private static final long POLL_IDLE_SLOW_MS = 15000L;
    /** 降频后的轮询间隔。 */
    private static final long POLL_SLOW_INTERVAL_MS = 3000L;
    /** 没样本超过这么久 ⇒ 才真正停机（进程还活着但肯定不在播放）。 */
    private static final long POLL_HARD_STOP_MS = 600000L;
    /** 轮询自证日志的最小间隔（每 10s 打一条 `poll tick`，便于确认真机上轮询是否活着）。 */
    private static final long POLL_LOG_EVERY_MS = 10000L;
    /**
     * 状态 Map 全量转储（诊断用）的最小间隔 —— **按来源分别计时**，
     * 这样「宿主自调 vs 兜底轮询」「AudioPlaylist vs AudioPlayer」各自的键集合都能看清。
     */
    private static final long MAP_DUMP_MIN_INTERVAL_MS = 2500L;

    private static volatile long sPollIntervalMs = POLL_INTERVAL_MS;
    private static volatile long sLastPollLogMs = 0L;
    private static volatile int sPollTicks = 0;

    /** 最近一次「状态 Map 里有有效进度」的时刻（用于判断是否该停轮询）。 */
    private static volatile long sLastRealSampleMs = 0L;
    /** 进度采样的去重：同一秒内不重复喂。 */
    private static volatile long sLastFedSec = -1L;
    private static volatile boolean sPollScheduled = false;
    private static volatile boolean sLoggedShape = false;
    /** 最近见过的播放器实例（弱引用表，避免持有宿主对象导致泄漏）。 */
    private static final java.util.List<Object> sLastPlayers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());
    /** 最多记住几个实例。【code 977】4 → 6，且淘汰时优先「同类里最旧的」（见 rememberPlayer）。 */
    private static final int MAX_REMEMBERED_PLAYERS = 6;

    /** 【code 977】各来源（where）最近一次 Map 转储的时刻 —— 按来源分别节流。 */
    private static final java.util.Map<String, Long> sLastDumpMsByWhere =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    /**
     * 【code 978】本进程是否见过「带 {@code trackCount} 键」的状态 Map（= 这是列表型播放器）。
     *
     * <p>用途：一旦见过列表，就只有「活跃列表」有权更新仓库状态，非列表实例（闲置的
     * {@code AudioPlayer}）与已停止的幽灵列表一律不上报 —— 否则真机上进度会被它们的
     * {@code currentTime=0.0} 反复拉回（见类头 978 段）。
     * 若宿主从未暴露列表（老宿主 / 另一条通道），则退化为「全部上报」的旧行为。
     */
    private static volatile boolean sSawPlaylistMap = false;

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        // 老采集点仍尝试挂（未混淆的老宿主上有效）；混淆宿主上会静默失败，无副作用。
        hookExoLegacy(cl);
        // 新采集点：expo-audio 状态 Map（抗混淆，主力）
        hookExpoStatusMap(cl, repo, "expo.modules.audio.AudioPlaylist");
        hookExpoStatusMap(cl, repo, "expo.modules.audio.AudioPlayer");
        // 兜底轮询：状态 Map 不是高频回调，必须补一路主动采样（详见类头「🔴 调用频率的坑」）
        ensurePolling(repo, cl);
    }

    // ======================================================================
    //  新通道：expo-audio 状态 Map
    // ======================================================================

    /**
     * 挂上「返回状态 Map 的无参方法」。
     *
     * @param className {@code expo.modules.audio.AudioPlaylist} 或 {@code ...AudioPlayer}
     */
    private static void hookExpoStatusMap(ClassLoader cl, SubtitleRepository repo, String className) {
        try {
            Class<?> cls = XposedCompat.findClass(className, cl);
            // 状态 Map 在基类 BaseAudioPlayer 上；先试本类，再试父类（matchMethod 会走父类链）
            Method m = Shape.findNoArgReturning(cls, "getStatus", Map.class);
            if (m == null) {
                // 退一步：只要「无参 + 返回 Map 形状」的方法（混淆后名字没了）
                m = Shape.findNoArgAssignableTo(cls, null, Map.class);
            }
            if (m == null) {
                XposedCompat.log(TAG + " " + className
                        + ": no no-arg Map method (status map not exposed) -> "
                        + Shape.describeNoArgReturnTypes(cls, 8));
                return;
            }
            if (!sLoggedShape) {
                sLoggedShape = true;
                XposedCompat.log(TAG + " status-map method = " + className + "." + m.getName()
                        + "()  [resolved BY RETURN TYPE Map, survives obfuscation]");
            }
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    try {
                        if (r instanceof Map) {
                            rememberPlayer(chain.getThisObject());
                            consume((Map<?, ?>) r, repo, className, chain.getThisObject());
                        }
                    } catch (Throwable ignored) {
                        // 钩子体绝不能把异常抛回宿主
                    }
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked " + className + "." + m.getName() + "() -> status Map");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " " + className + " status map hook failed: " + e.getMessage());
        }
    }

    /**
     * 消费一份状态 Map：进度 + 播放态 + 换轨，三样一起更新。
     *
     * <p>⚠️ 键名是<b>字符串常量</b>，R8 不改字符串池 ⇒ 跨混淆版本稳定。
     * 但<b>类型</b>要全部归一（实测 {@code currentTime} 是 {@code Double} 秒、
     * {@code currentIndex} 是 {@code Integer}），且必须防 {@code NaN} / 负值。
     */
    private static void consume(Map<?, ?> m, SubtitleRepository repo, String where, Object inst) {
        // ⓪ 【code 977】诊断：把整张 Map 打出来（按来源节流）—— 用于确定「哪些键、什么值、来自哪个通道」。
        //   【code 978】补上实例标识与 String 实际值（见 dumpStatusMap）。
        dumpStatusMap(m, where, inst);

        // ── 【code 978】「当前活跃播放列表」判定（权威门）────────────────────
        //
        // 真机铁证（见类头 978 段）：宿主同时持有多个 AudioPlaylist 实例，读数交替流进来。
        // 只让「活跃列表」这一路更新仓库状态，否则：
        //   · 幽灵列表的 currentTime=0.0 会把字幕位置反复拉回开头 ⇒ 匹配出上一轨的句子；
        //   · 闲置 AudioPlayer 的 playing=false 会把状态栏字幕闪掉；
        //   · 幽灵列表的 playbackState 会覆盖掉「真结束」⇒ 悬浮窗不关。
        Object tcObj = m.get("trackCount");
        Object durObj = m.get("duration");
        boolean isPlaylistMap = tcObj instanceof Number;
        int trackCount = isPlaylistMap ? ((Number) tcObj).intValue() : -1;
        double durSec = durObj instanceof Number ? ((Number) durObj).doubleValue() : -1.0;
        boolean hasCurrentTime = m.get("currentTime") instanceof Number;

        boolean authoritative;
        if (isPlaylistMap) {
            sSawPlaylistMap = true;
            // 活跃 = 有曲目、有时长、且不是"已作废"的列表。幽灵列表是 trackCount=0（/其它 halt 原因）。
            //
            // 【2.2.13 修复：用户暂停被误判成幽灵列表 → 状态栏字幕暂停后不消失】
            // 真机日志铁证（LSPosed_20261008_214215）：用户按下暂停后，活跃列表回报的是
            //   trackCount=7 / duration=684s / playing=false / haltReason=user_request
            // —— expo-audio 把"用户主动暂停"也记进 haltReason！旧判据 `haltReason != null`
            // 把这一路当成"已停止的幽灵列表"整个丢弃 ⇒ setPlaying(false) 永远到不了仓库
            // ⇒ 状态栏字幕（与开关态无关）一直显示。实测日志里 haltReason 只有
            //   null / user_request 两种取值；幽灵列表真正稳定的特征是 trackCount=0。
            // ⇒ 只有"非用户请求"的 halt 才算作废；user_request 是正常的暂停态，必须上报。
            String haltReason = m.get("haltReason") instanceof String
                    ? (String) m.get("haltReason") : null;
            boolean halted = haltReason != null && !"user_request".equals(haltReason);
            authoritative = trackCount > 0 && durSec > 0.0 && !halted;
        } else {
            // 非列表实例（闲置 AudioPlayer）：只有在本进程**从未见过**列表时才有权更新（老宿主兼容）。
            authoritative = !sSawPlaylistMap && hasCurrentTime;
        }
        if (!authoritative) {
            // 幽灵 / 闲置实例：只贡献诊断，不污染仓库状态。
            return;
        }

        // ① 播放位置（秒 → 毫秒）
        Object ct = m.get("currentTime");
        long ms = Shape.secondsToMillisOrMinus1(Shape.normalize(ct));
        if (ms >= 0) {
            long sec = ms / 1000L;
            if (sec != sLastFedSec) {
                sLastFedSec = sec;
                sLastRealSampleMs = SystemClock.uptimeMillis();
                SubtitleRepository.getInstance().setPlaybackPositionMs(ms);
            }
        }

        // ② 播放 / 暂停（Boolean；缺失时不动，仓库对「未知」宽容）
        Object playing = m.get("playing");
        if (playing instanceof Boolean) {
            SubtitleRepository.getInstance().setPlaying((Boolean) playing);
            sLastRealSampleMs = SystemClock.uptimeMillis();
        }

        // ③ 播放状态（字符串 → media3 口径）
        Object st = m.get("playbackState");
        if (st instanceof String) {
            int mapped = mapPlaybackState((String) st, m);
            if (mapped > 0) {
                SubtitleRepository.getInstance().setPlaybackState(mapped);
            }
        }

        // ④ 【code 978】换轨：判据由「曲目序号」升级为「**播放列表身份**」
        //   （trackCount + duration + currentIndex 三元组）。
        //   🔴 为什么必须换：真机上切「作品 / 音轨」时 currentIndex **恒为 0**
        //      （作品 A 的 6 轨列表 idx=0，作品 B 的 1 轨列表 idx=0），
        //      所以 977 及以前那条「序号变了没」的判据**永远不成立**。
        //   ⇒ 交给 PlayerSourceHook 统一判（它持有全部历史教训）。
        Object idx = m.get("currentIndex");
        if (idx instanceof Number) {
            int cur = ((Number) idx).intValue();
            PlayerSourceHook.onPlaylistStateFromStatusMap(cur, trackCount, durSec, where);
        }
    }

    /**
     * 【code 977 / 978】**诊断专用**：把整张状态 Map 逐键打出来（按 {@code where+实例} 分别节流到 2.5s 一次）。
     *
     * <p>为什么需要：本轮 bug 的根因判决卡在「模块到底多久才能读到一次 {@code currentIndex}、
     * 它是从哪个通道（宿主自调 / 兜底轮询、AudioPlaylist / AudioPlayer）读到的」——
     * 这两个问题**静态判据一律照不到**，只能靠真机把 Map 原样打出来看。
     *
     * <p>【code 978】两处补强 —— 977 的诊断本身把最关键的信息抹掉了：
     * <ul>
     *   <li><b>String 打成实际值</b>（977 一律打 {@code <str>}）—— {@code id} / {@code playbackState} /
     *       {@code haltReason} 的值才是「这是哪个列表、活跃还是已停止」的直接证据；</li>
     *   <li><b>带上实例标识 {@code ihc=}</b>（{@link System#identityHashCode}）—— 宿主同时持有多个
     *       {@code AudioPlaylist} 实例，977 的节流键只有 {@code where}，同类多实例互相覆盖，
     *       导致「到底有几个实例、各自什么状态」看不出来。</li>
     * </ul>
     *
     * <p>输出形如：{@code statusMap via=poll:AudioPlaylist ihc=1a2b3c4d size=16 {id=<str> currentIndex=0(Integer) …}}
     * 本条**只读不改**，不参与任何判定。
     *
     * <p>【2.2.7 / code 979】受设置页「调试日志」开关控制（{@code RemoteConfig.debugLog()}）：
     * 关闭时本方法**直接返回**，一行都不打。开关只影响日志输出，不影响任何功能判定。
     */
    private static void dumpStatusMap(Map<?, ?> m, String where, Object inst) {
        // 【2.2.7 / code 979】诊断日志总闸：关闭（默认）时**整段跳过** ——
        //   连同下面的 StringBuilder 拼接与节流表更新都省掉，热路径零成本。
        //   开闸入口 = 设置页「其他」卡片的「调试日志」开关（配置键 debug_log）。
        if (!LogGate.enabled()) {
            return;
        }
        try {
            int ihc = inst == null ? 0 : System.identityHashCode(inst);
            String key = where + "#" + Integer.toHexString(ihc);
            long now = SystemClock.uptimeMillis();
            Long lastMs = sLastDumpMsByWhere.get(key);
            if (lastMs != null && now - lastMs < MAP_DUMP_MIN_INTERVAL_MS) {
                return;
            }
            sLastDumpMsByWhere.put(key, now);
            StringBuilder sb = new StringBuilder(320);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                sb.append(e.getKey()).append('=');
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.length() > 24) {
                        s = s.substring(0, 24) + "~";
                    }
                    sb.append(s.isEmpty() ? "<empty>" : s);
                } else {
                    sb.append(v).append(v == null ? "(null)" : "(" + v.getClass().getSimpleName() + ")");
                }
                sb.append(' ');
                if (sb.length() > 520) {
                    sb.append("…[truncated]");
                    break;
                }
            }
            XposedCompat.log(TAG + " statusMap via=" + where + " ihc=" + Integer.toHexString(ihc)
                    + " size=" + m.size() + " {" + sb + "}");
        } catch (Throwable ignored) {
        }
    }

    /**
     * expo 的 {@code playbackState} 字符串 → media3 口径。
     *
     * <p>取值实测：{@code idle} / {@code buffering} / {@code ready} / {@code ended} /
     * {@code unknown}。{@code unknown} 映射成 {@link #PSTATE_IDLE}，仓库侧不处理 IDLE
     * （加载新内容时也会短暂 IDLE），所以这是安全的。
     */
    private static int mapPlaybackState(String s, Map<?, ?> m) {
        if ("ended".equals(s)) {
            return PSTATE_ENDED;
        }
        if ("buffering".equals(s) || "loading".equals(s)) {
            return PSTATE_BUFFERING;
        }
        if ("ready".equals(s)) {
            return PSTATE_READY;
        }
        if ("idle".equals(s)) {
            // 兼容另一种口径：宿主直接给了布尔
            if (Boolean.TRUE.equals(m.get("didJustFinish"))) {
                return PSTATE_ENDED;
            }
            return PSTATE_IDLE;
        }
        return 0; // unknown / 未来新增取值 ⇒ 不上报
    }

    // ======================================================================
    //  兜底轮询（状态 Map 不是高频回调，必须补一路主动采样）
    // ======================================================================

    /**
     * 启动一次「延迟自续」的轮询链：第一次执行后按固定间隔自我重投。
     *
     * <p>为什么必须有：见类头「🔴 调用频率的坑」—— 宿主 JS 不会每秒读状态，
     * 只靠 hook 会让字幕长时间不换行，**更会让换轨根本检测不到**
     * （真机 977 前日志：宿主自调状态 Map 的间隔高达 40~140 秒）。
     *
     * <p>【code 977】停止条件：只有连续 {@link #POLL_HARD_STOP_MS}（10 分钟）完全无样本才真停；
     * 平时空闲只降频到 {@link #POLL_SLOW_INTERVAL_MS}。
     * **977 之前**用的是 15 秒就永久停机，且没有重启入口 —— 那是本轮 bug 的直接成因。
     * 用系统 Handler 而非线程池，避免多线程与宿主抢。
     */
    public static void ensurePolling(final SubtitleRepository repo, final ClassLoader cl) {
        if (sPollScheduled) {
            return;
        }
        sPollScheduled = true;
        scheduleNextPoll(repo, cl, 0L);
    }

    private static void scheduleNextPoll(final SubtitleRepository repo, final ClassLoader cl, long delay) {
        try {
            android.os.Handler h = pollHandler();
            if (h == null) {
                sPollScheduled = false;
                return;
            }
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        long now = SystemClock.uptimeMillis();
                        long idle = sLastRealSampleMs > 0 ? (now - sLastRealSampleMs) : 0L;
                        // 【code 977】空闲只**降频**，绝不永久停机 ——
                        //   977 之前的写法是空闲 15s 就 return 且再无重启入口（ensurePolling 只在进程加载时调一次），
                        //   真机日志因此出现 `polling auto-stop (no sample for 107931ms)` 之后再无采样，
                        //   恰好错过用户切轨那一刻。现在只有「十分钟完全无样本」才真停。
                        if (idle > POLL_HARD_STOP_MS) {
                            XposedCompat.log(TAG + " polling stopped (no sample for " + idle + "ms)");
                            sPollScheduled = false;
                            return;
                        }
                        sPollIntervalMs = idle > POLL_IDLE_SLOW_MS ? POLL_SLOW_INTERVAL_MS : POLL_INTERVAL_MS;
                        pollOnce(cl, repo);
                        sPollTicks++;
                        // 【2.2.7 / code 979】轮询自证行也归「调试日志」开关管：
                        //   它每 10s 一条，属于诊断级；关闭时只保留「轮询彻底停」这类状态跃迁日志。
                        if (LogGate.enabled() && now - sLastPollLogMs > POLL_LOG_EVERY_MS) {
                            sLastPollLogMs = now;
                            // 自证「轮询还活着、手上握着几个实例」—— 本轮问题的第一现场就在这条线上
                            XposedCompat.log(TAG + " poll tick #" + sPollTicks
                                    + " players=" + sLastPlayers.size()
                                    + " idle=" + idle + "ms interval=" + sPollIntervalMs + "ms");
                        }
                    } catch (Throwable ignored) {
                    }
                    // 无论成功与否都续上（失败时下一轮再试；hard-stop 分支已 return）
                    if (sPollScheduled) {
                        scheduleNextPoll(repo, cl, sPollIntervalMs);
                    }
                }
            }, delay);
        } catch (Throwable e) {
            sPollScheduled = false;
        }
    }

    private static android.os.Handler sPollHandler;

    private static synchronized android.os.Handler pollHandler() {
        if (sPollHandler == null) {
            android.os.Looper looper = android.os.Looper.getMainLooper();
            if (looper == null) {
                return null;
            }
            sPollHandler = new android.os.Handler(looper);
        }
        return sPollHandler;
    }

    /**
     * 主动调一次状态 Map（兜底路径）。
     *
     * <p>⚠️ 必须拿到<b>已存在的播放器实例</b>才能调 —— 这里从最近一次 hook 到的实例取。
     * 拿不到实例就跳过（不new、不猜），这正是「播放页没开时不空转」的实现方式。
     */
    private static void pollOnce(ClassLoader cl, SubtitleRepository repo) {
        java.util.List<Object> snapshot;
        try {
            snapshot = new java.util.ArrayList<>(sLastPlayers);
        } catch (Throwable e) {
            return;
        }
        for (Object inst : snapshot) {
            if (inst == null) {
                continue;
            }
            Method m = Shape.findNoArgAssignableTo(inst.getClass(), null, Map.class);
            if (m == null) {
                continue;
            }
            try {
                Object r = m.invoke(inst);
                if (r instanceof Map) {
                    consume((Map<?, ?>) r, repo, "poll:" + inst.getClass().getSimpleName(), inst);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ======================================================================
    //  老通道（未混淆宿主）：ExoPlayerImpl
    // ======================================================================

    /**
     * 老采集点：Media3 {@code ExoPlayerImpl}。
     *
     * <p>宿主 2.20.2 上这个类已不存在 ⇒ 全部失败；保留是为了兼容 ≤2.20.1 的宿主。
     */
    private static void hookExoLegacy(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method pos = XposedCompat.findMethodByName(cls, "getCurrentPosition");
            XposedCompat.hookMethod(pos, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    try {
                        if (r instanceof Long) {
                            SubtitleRepository.getInstance().setPlaybackPositionMs((Long) r);
                            rememberPlayer(chain.getThisObject());
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl.getCurrentPosition() [legacy]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " ExoPlayerImpl not available (host is obfuscated) - expected on 2.20.2+");
        }
        hookLegacyState(cl);
    }

    private static void hookLegacyState(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method m = XposedCompat.findMethodByName(cls, "getPlaybackState");
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    try {
                        if (r instanceof Integer) {
                            SubtitleRepository.getInstance().setPlaybackState((Integer) r);
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            Method p = XposedCompat.findMethodByName(cls, "getPlayWhenReady");
            XposedCompat.hookMethod(p, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    try {
                        if (r instanceof Boolean) {
                            SubtitleRepository.getInstance().setPlaying((Boolean) r);
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl getPlaybackState/getPlayWhenReady [legacy]");
        } catch (Throwable e) {
            // 同上：混淆宿主上失败属预期
        }
    }

    /** 记住播放器实例（供兜底轮询调用）。 */
    private static void rememberPlayer(Object p) {
        if (p == null) {
            return;
        }
        try {
            if (sLastPlayers.contains(p)) {
                return;
            }
            if (sLastPlayers.size() >= MAX_REMEMBERED_PLAYERS) {
                // 【code 977】淘汰优先「**同类里最旧的**」，而不是无脑 remove(0)。
                //   原因：只有 AudioPlaylist 的状态 Map 才带 currentIndex（AudioPlayer 的没有）；
                //   宿主高频调 AudioPlayer 时，旧的 remove(0) 会把 AudioPlaylist 挤出去
                //   ⇒ 轮询再也读不到 currentIndex ⇒ 换轨检测失效。保住跨类多样性即可根治。
                String cls = p.getClass().getSimpleName();
                for (int i = 0; i < sLastPlayers.size(); i++) {
                    if (sLastPlayers.get(i).getClass().getSimpleName().equals(cls)) {
                        sLastPlayers.remove(i);
                        break;
                    }
                }
                if (sLastPlayers.size() >= MAX_REMEMBERED_PLAYERS) {
                    sLastPlayers.remove(0);
                }
            }
            sLastPlayers.add(p);
        } catch (Throwable ignored) {
        }
    }
}
