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

/**
 * 音轨切换 Hook —— 解决「切到没有字幕的音轨后，悬浮窗仍从头播放上一音轨的缓存字幕」。
 *
 * <h3>🔴🔴 2.1.4：本文件因宿主 R8 混淆而整条重写</h3>
 * 老版本挂在五个换轨信号上，宿主 2.20.2 上<b>全部</b>失效（真机日志原文）：
 * <pre>
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.emitTrackChanged
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.next
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.previous
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.skipTo
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.onManualNavigation
 * hookAllMethods found no method: expo.modules.audio.AudioPlaylist.getCurrentTrackIndex
 * hookAllMethods found no method: expo.modules.audio.AudioPlayer.setMediaSource
 * [DLsiteSoundFloat:Source] class not found: androidx.media3.exoplayer.ExoPlayerImpl
 * </pre>
 * 离线核实（dex 层）：这些方法名已被 R8 改名（实测 {@code AudioPlaylist} 的方法变成
 * {@code l0()I} / {@code R(I)V} / {@code i0(I)V} …），而 {@code ExoPlayerImpl} 整个类已不存在。
 *
 * <h3>✅ 本版方案：换轨判据由状态 Map 提供</h3>
 * 换轨判据的<b>本质</b>是「现在播的到底是哪一份东西」。expo-audio 状态 Map 里给出了
 * 判定所需的三样字符串键（离线反汇编确认类型）：
 * {@code currentIndex -> Integer}、{@code trackCount -> Integer}、{@code duration -> Double}。
 * ⇒ 由 {@link PlayerPositionHook} 在消费状态 Map 时一并检测变化并发换轨通知，
 * <b>本文件不再自己挂钩任何换轨方法</b>。
 *
 * <p>🔴🔴 <b>【code 978】判据从「序号」升级为「播放列表身份」三元组</b> ——
 * 真机日志（{@code LSPosed_20261007_211706}）证明：宿主切「作品 / 音轨」时
 * {@code currentIndex} <b>恒为 0</b>（作品 A 的 6 轨列表 idx=0 → 作品 B 的 1 轨列表 idx=0），
 * 单看序号<b>一次都发现不了</b>（这就是连修五轮 973/974/975/976/977 全部无效的原因）。
 * 真正变化的是 {@code (trackCount, duration)}。详见
 * {@link #onPlaylistStateFromStatusMap(int, int, double, String)}。
 *
 * <h3>为什么保留本文件（而不是直接删掉）</h3>
 * ① {@link #notifyTrackChanged} 是换轨通知的<b>唯一出口</b>（含去重），
 * 仍被 {@link PlayerPositionHook} 调用；
 * ② 变化判据与去抖闸门（{@link #settleChange}）集中在这里，便于维护；
 *    旧版还有一条「X→0 是播放列表重置、不算换轨」的判据（{@link #isListReset}）——
 *    【973】它在**权威门**上已被证伪并停用（见下），只在老信号路径里继续兜着；
 * ③ 老宿主（未混淆）上仍能挂到那五个信号，作为<b>状态 Map 之外的第二条换轨来源</b>。
 *
 * <h3>真伪换轨的判据（沿用并保留全部历史教训）</h3>
 * <ul>
 *   <li><b>首次观测</b>（进程重建）→ 只重建基线，<b>不发通知</b>。
 *       否则数据层会用被清零的播放位置（0）重算字幕行 ⇒ <b>画面跳回已播过的开头字幕</b>。</li>
 *   <li><b>【973】X → 0 也是真换轨</b>。0 号轨是<b>真实音轨</b>（2026-10-07 真机截图里就是
 *       「本編」）。当天 5.5 小时日志里 `N->0` 共 11 次（56/75/98/100/144/114/36/20/113/5…），
 *       其中 7 次紧跟 50~190ms 出现 `0->Y` —— 全部伴随<b>真实的</b>切作品 / 切轨。
 *       旧版把这类事件当「播放列表被重置」静默吞掉（`ignored N->0`），后果是数据层收不到
 *       换轨通知 ⇒ 旧 cue 列表继续参与渲染 ⇒ 新轨从 0 秒起播、走到几秒时按旧列表匹配出
 *       <b>上一轨的句子</b>（用户 2026-10-07 报的 bug）。
 *       ⚠️ 该判据当初要防的「进程重建」已由「首次观测只种基线」独立拦住，不依赖它。</li>
 *   <li><b>身份变化</b>（trackCount / duration / currentIndex 任一项变）→ 真换轨，通知。</li>
 *   <li>读不到身份 → 退化为旧行为（无条件通知），由数据层的弱兜底再判一次。</li>
 * </ul>
 * ⚠️ 拖动进度条走 seekTo，不改 identity ⇒ 不会误清字幕。
 */
public class PlayerSourceHook {
    private static final String TAG = "[DLsiteSoundFloat:Source]";

    /**
     * 【2.2.7 / code 979】**诊断级**日志出口 —— 受设置页「调试日志」开关控制。
     *
     * <p>{@code debugLog} 关闭（默认）时一个字都不打。用于换轨闸门的「抑制 / 忽略」
     * 这类**每次采样都可能命中**的高频行；里程碑行（钩子挂载、种基线、真正的
     * {@code >>> track changed}）仍走 {@link XposedCompat#log} 常开。
     */
    private static void dbg(String msg) {
        if (RemoteConfig.debugLog()) {
            XposedCompat.log(TAG + " " + msg);
        }
    }

    /** 同一次切换可能触发多个 hook 点，去重窗口。 */
    private static final long DEDUP_MS = 500L;
    /**
     * 【code 976~977 起**已不再用于「重种基线」**，保留仅为历史背景】原意：距上次「进程内已观测到序号」
     * 超过这么久，说明中间大概率经历了进程重建 / 长时间后台，读到的新序号不可信 ⇒ 只重建基线。
     *
     * <p>🔴 977 停用它的原因：真机日志显示状态 Map 的读取间隔**本身**就有 40~140 秒，
     * 这条判据于是几乎每次命中，把「比较两次读数」这一步彻底堵死（换轨 0 次上报）。
     * 进程重建由 {@code last == null} 独立覆盖。
     */
    private static final long INDEX_STALE_MS = 30000L;

    // ── 【code 975→976】序号抖动去抖 ────────────────────────────────────────
    //
    // Ari 2026-10-07 晚**连报两轮**，两轮根因不同，这里把两次都记下来。
    //
    // ── 第一轮（974 上的现象，LSPosed_20261007_193455）─────────────────────
    //   19:17:42.908 → 19:17:56.943 **每秒一次** `>>> track changed: … 2->0 (to first track)`
    //   连续 15 次，每次都跟一句 `… -> SUSPEND subtitles, wait 3000ms`。
    //   数据层 onTrackChanged 每次都会 `pendingToken++` ⇒ 上一次排的裁决任务全部作废
    //   ⇒ resolvePendingTrack **永远到不了点** ⇒ 字幕停在「挂起」、窗口既不关也不再更新。
    //   成因：宿主（R8 后的 expo 状态 Map）换轨期间把 `currentIndex` 在 0 与 2 之间**反复横跳**。
    //
    // ── 第二轮（975 上的现象，LSPosed_20261007_201249）🔴 975 修错了方向 ──
    //   真机复测仍是「换轨后旧字幕原样保留、悬浮窗不关」。取证结果：
    //   **975 时段（20:10:21~20:12:54）整个日志里换轨事件 0 条**（974 时段是每秒一条）
    //   ⇒ 975 那道「等连续稳定」的闸门把通道**彻底堵死**了，数据层压根不知道换过轨。
    //   推演（与代码逐行对得上）：975 在「读到基线值」时会调 `clearPendingIndex()`
    //   把候选的计时**清零**；而宿主的横跳是**规律交替** —— 候选每约 1s 出现一次，
    //   中间夹一次「读回基线」就把计时抹掉 ⇒ 计时**永远攒不满** ⇒ 一次都不上报。
    //   ⇒ 症状从「裁决被作废」变成了「根本没有裁决」，字幕自然一直挂着（比 974 更糟）。
    //
    // ── 976 的判据：**首报即报 + 长抑制** ──────────────────────────────────
    //   · 「离开基线」的候选值**再次出现**即视为真换轨（**不要求连续**、
    //     也**不因中途读回基线而清零**）—— 只用一个短间隔下限滤掉「同一帧被读两次」的噪声；
    //   · 一旦上报，进入 {@link #INDEX_SUPPRESS_MS} 静默期：期内**同一对序号（含反向）**
    //     的任何变化都不再上报 ⇒ 让数据层那一次裁决窗完整跑完到点（这正是 974 缺的东西）；
    //   · 静默期内若出现**第三个不同的序号** ⇒ 判定为用户又主动切了一次轨 ⇒ 立即穿透放行。
    //   ⚠️ 静默窗必须 **大于数据层裁决窗**（NO_SUBTITLE_GRACE_MS = 3000ms），
    //      否则「报告 → 作废 → 报告」的循环会原样复现。
    /** 【code 976】「同一候选值两次出现」的最小间隔 —— 只为滤掉同一次采样帧内的重复回调。 */
    private static final long INDEX_DEBOUNCE_MS = 600L;
    /**
     * 【code 976】换轨上报后的**静默期** —— 只有「同一对序号」（含反向）被抑制，
     * 出现第三个不同的序号则立即穿透。
     *
     * <p>⚠️ 必须 **大于数据层裁决窗** {@code NO_SUBTITLE_GRACE_MS = 3000ms} ——
     * 这正是 974「每 500ms 上报一次 ⇒ 裁决被反复作废」的根治点。
     * 宿主横跳实测持续约 15s，故取 20s 全覆盖（横跳结束前不再打扰数据层）。
     */
    private static final long INDEX_SUPPRESS_MS = 20000L;
    /** 待定中的「候选新序号 / 新身份」（哨兵 = 无待定）。【code 978】int → long（身份是三元组）。 */
    private static volatile long sPendingIdx = Long.MIN_VALUE;
    /** 候选新序号**首次**被读到的时间。 */
    private static volatile long sPendingSinceMs = 0L;
    /** 最近一次**真正上报过**的 (from→to) 与时间 —— 用于同向去重。 */
    private static volatile long sNotifiedFrom = Long.MIN_VALUE;
    private static volatile long sNotifiedTo = Long.MIN_VALUE;
    private static volatile long sNotifiedMs = 0L;

    private static volatile long sLastNotifyMs = 0L;
    /** 最近一次观察到的播放列表曲目序号（{@code null} = 尚未观测到）。 */
    private static volatile Integer sLastTrackIndex = null;
    /** 最近一次观测到序号的时间（用于识别进程重建后的「首次读数」）。 */
    private static volatile long sLastIndexSeenMs = 0L;

    // ── 【code 978】播放列表身份（换轨判据的真正载体）──────────────────────
    //
    // 🔴 为什么引入：真机日志（LSPosed_20261007_211706）铁证 —— 宿主切「作品 / 音轨」时
    //   `currentIndex` **恒为 0**（作品 A 的 6 轨列表 idx=0，作品 B 的 1 轨列表 idx=0），
    //   序号判据于是**一次都没触发**（连修五轮无效的原因）。
    //   真正变化的是「列表身份」：(trackCount, duration) = (6, 731.832) → (1, 2431.085)。
    // ⇒ 身份 = 三元组 (trackCount, durationMs, currentIndex) 打包成一个 long。
    //   · 跨作品 / 换列表 ⇒ trackCount+duration 变 ✓
    //   · 同一列表内换轨 ⇒ currentIndex 变 ✓
    /** 是否已经在**本进程内**观测到过一次播放列表身份（首次只种基线）。 */
    private static volatile boolean sIdentitySeen = false;
    /** 最近一次观测到的播放列表身份。 */
    private static volatile long sLastIdentity = 0L;
    /** 上一次身份的可读描述（日志用）。 */
    private static volatile String sLastIdentityDesc = "?";

    /** 播放列表上「可能表示换轨、但不保证真的换了」的方法：必须用曲目序号二次确认。 */
    private static final String[] PLAYLIST_SIGNALS = {
            "emitTrackChanged", "next", "previous", "skipTo", "onManualNavigation"};
    /** AudioPlayer 上真正换掉媒体源的方法：无条件当作换轨，顺便种序号基线。 */
    private static final String[] PLAYER_SOURCE = {"setMediaSource"};
    /** 底层 ExoPlayer 的媒体源替换：无条件当作换轨。 */
    private static final String[] EXO_SOURCE = {
            "setMediaItems", "setMediaItem", "setMediaSource", "setMediaSources"};

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        // 老信号：仅在宿主「未混淆」时有效（≤2.20.1）。
        // 2.20.2+ 上这些方法名已被 R8 改掉，全部找不到 —— 属预期，不打错误日志。
        hookClass(cl, repo, "expo.modules.audio.AudioPlayer",
                PLAYER_SOURCE, false, true);
        hookClass(cl, repo, "expo.modules.audio.AudioPlaylist", PLAYLIST_SIGNALS, true, false);
        hookClass(cl, repo, "androidx.media3.exoplayer.ExoPlayerImpl", EXO_SOURCE, false, false);

        if (sAnyLegacyHooked) {
            XposedCompat.log(TAG + " legacy change signals active (host NOT obfuscated);"
                    + " status-map index also feeding change detection");
        } else {
            XposedCompat.log(TAG + " legacy change signals absent (expected on obfuscated host)"
                    + " -> track change will be detected via status-map currentIndex");
        }
    }

    /** 是否至少挂到了一条老换轨信号（用于日志判断当前走哪条路）。 */
    private static volatile boolean sAnyLegacyHooked = false;

    /**
     * @param requireIndexChange true = 该方法的调用不足以证明换轨，需再比一次曲目序号
     * @param seedIndexOnly     true = 过一遍只为把序号基线种进去（不产生换轨通知）
     */
    private static void hookClass(ClassLoader cl, SubtitleRepository repo,
                                  String className, String[] methodNames,
                                  boolean requireIndexChange, boolean seedIndexOnly) {
        Class<?> cls;
        try {
            cls = XposedCompat.findClass(className, cl);
        } catch (Throwable e) {
            // 类不存在（混淆后改名 / 该宿主没有）—— 静默跳过，主通道不依赖它
            return;
        }
        int hooked = 0;
        for (final String name : methodNames) {
            hooked += XposedCompat.hookAllMethods(cls, name, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    try {
                        String where = className + "." + name;
                        if (requireIndexChange) {
                            notifyIfTrackIndexChanged(repo, where, chain.getThisObject());
                            return r;
                        }
                        if (seedIndexOnly) {
                            seedTrackIndex(chain.getThisObject(), where);
                        }
                        notifyTrackChanged(repo, where);
                    } catch (Throwable ignored) {
                        // 钩子体绝不能把异常抛回宿主
                    }
                    return r;
                }
            });
        }
        if (hooked > 0) {
            sAnyLegacyHooked = true;
            XposedCompat.log(TAG + " hooked " + className
                    + (requireIndexChange ? " [index-verified]"
                            : (seedIndexOnly ? " [unconditional + seed]" : " [unconditional]"))
                    + " (" + hooked + " methods)");
        }
    }

    // ======================================================================
    //  序号判据（状态 Map 与老信号共用）
    // ======================================================================

    /**
     * 由 {@link PlayerPositionHook} 在读到**活跃播放列表**状态 Map 时调用 —— 2.1.4 起的<b>主换轨通道</b>。
     *
     * <p>状态 Map 每次被读都会走到这里，而「读状态」在播放中必然频繁发生（宿主自己 + 兜底轮询）。
     *
     * <h3>🔴🔴 【code 978】判据由「序号」升级为「播放列表身份」</h3>
     * 真机铁证（{@code LSPosed_20261007_211706}）：宿主切「作品 / 音轨」时
     * {@code AudioPlaylist.currentIndex} <b>恒为 0</b> ——
     * 作品 A「通话记录1」的 6 轨列表 {@code idx=0}，切到作品 B「寒がりの蛇獣人… 本編」的
     * 1 轨列表仍是 {@code idx=0}。⇒ 977 及以前那条「序号变了没」的判据
     * <b>一次都没成立过</b>（连修五轮无效的真正原因）。
     *
     * <p>真正随切换变化的是<b>列表身份</b>：
     * {@code (trackCount, duration)} 从 {@code (6, 731.832s)} 变成 {@code (1, 2431.085s)}。
     * ⇒ 身份 = 三元组 {@code (trackCount, durationMs, currentIndex)}：
     * <ul>
     *   <li>跨作品 / 换列表 ⇒ {@code trackCount}+{@code duration} 变 ⇒ 触发；</li>
     *   <li>同一列表内换轨 ⇒ {@code currentIndex} 变 ⇒ 触发；</li>
     *   <li>三者全同 ⇒ 不是换轨。</li>
     * </ul>
     * 去抖与长静默沿用 976 的两条闸门（{@link #INDEX_DEBOUNCE_MS} / {@link #INDEX_SUPPRESS_MS}），
     * 一字未改 —— 它们要对付的「宿主横跳」在 978 的真机上依然存在（多实例交替读数）。
     *
     * <p>⚠️ 调用方（{@link PlayerPositionHook#consume}）<b>已经</b>过滤掉非活跃实例
     * （幽灵列表 {@code trackCount=0} / {@code haltReason} 有值、闲置 {@code AudioPlayer}），
     * 所以这里拿到的都是「当前真的该在用的那份列表」。
     *
     * @param idx         {@code currentIndex}
     * @param trackCount  {@code trackCount}（无此键时为 -1 = 老宿主）
     * @param durationSec {@code duration}（秒；无此键时为 -1）
     * @return 本次是否发出了换轨通知（仅供诊断）
     */
    public static boolean onPlaylistStateFromStatusMap(int idx, int trackCount, double durationSec,
                                                       String where) {
        long now = SystemClock.uptimeMillis();
        sLastIndexSeenMs = now;
        long identity = makeIdentity(trackCount, durationSec, idx);
        String desc = describeIdentity(trackCount, durationSec, idx);
        // 【980】把「当前活跃列表身份」推给数据层 —— 只为给新加载的 cues 盖主人印章
        //   （宿主对同一轨的响应有缓存，切走再切回不会重发 JSON；有了印章才能在切回时
        //   直接恢复渲染，见 SubtitleRepository#tryResumeFromCache）。
        //   这里**只写一个 volatile 字段**，不触发任何状态变更 —— 宿主会交替读到多个
        //   列表实例，拿它直接驱动 UI 就会变成抖动源。
        SubtitleRepository.getInstance().noteObservedPlaylistIdentity(identity);

        // 【code 977→978】只在「本进程首次观测到身份」时种基线。
        //   🔴 977 之前这里还有一条 `|| since > INDEX_STALE_MS(30000)` 的早退。
        //      真机日志（LSPosed_20261007_204620）显示：模块对状态 Map 的**读取间隔本身就极稀疏** ——
        //      相邻两次真正读到相隔 40~140 秒（日志铁证 `since=43814ms` / `since=108921ms`），
        //      于是「距上次太久 ⇒ 重新种基线」这条几乎**每次**都命中
        //      ⇒ 相邻两次读数**永远走不进「比较」那一步** ⇒ 换轨一次都发现不了（976 那轮的直接成因）。
        //      ⚠️ 976 的离线模拟没能暴露它：模型假设读数间隔 500ms，与真机不符（见 sim_jitter.py 的 977 修订）。
        //   进程重建的误判由 `sIdentitySeen == false` 独立覆盖（静态字段随进程重建清零）；
        //   长时间后台若真换了轨，本来就**应该**通知数据层清字幕并等新 JSON —— 通知本身不重置播放位置。
        if (!sIdentitySeen) {
            sIdentitySeen = true;
            sLastIdentity = identity;
            sLastIdentityDesc = desc;
            sLastTrackIndex = idx;
            clearPendingIndex();
            XposedCompat.log(TAG + " seeded playlist identity " + desc + " from " + where
                    + " (first observation in this process, no change notification)");
            return false;
        }
        if (identity == sLastIdentity) {
            // 【code 976】身份没变 ⇒ 不是换轨。
            //   🔴 这里**不得**再调 clearPendingIndex()：975 正是在这里把候选计时清零，
            //      于是宿主的规律交替（候选 → 读回基线 → 候选 …）永远攒不满，一次都不上报
            //      —— 通道被堵死，数据层压根不知道换过轨，旧字幕就一直挂着。
            //   候选计时必须**跨过**「读回基线」的采样继续累积。
            return false;
        }
        // 【code 976】身份变了 ≠ 立刻就是换轨：可能只是宿主横跳的第一拍。
        //   闸门只做两件事：同一候选**再次出现**的确认 + 上报后的**长静默**
        //   （不删候选计时、不按「连续稳定」判，见 settleChange 的注释）。
        if (!settleChange(sLastIdentity, identity, now)) {
            return false;
        }
        String from = sLastIdentityDesc;
        sLastIdentity = identity;
        sLastIdentityDesc = desc;
        sLastTrackIndex = idx;
        // 【973】此处原来有一道早退：序号归 0 且旧序号 > 1 时，一律当成「播放列表被重置」
        //   而静默忽略。真机取证推翻了它 ——
        //     · 0 号轨是真实音轨（2026-10-07 截图里就是「本編」）；
        //     · 当天 11 次 `N->0` 全部伴随真实的切作品 / 切轨（7 次紧跟 `0->Y`）；
        //     · bug 会话（16:38~16:55）里 `track changed` 一条都没有 ⇒ 换轨从未上报。
        //   被吞掉的后果：数据层收不到换轨通知 ⇒ 旧 cue 列表继续渲染 ⇒ 新轨走到几秒时
        //   按旧列表匹配出上一轨的字幕。
        //   ⚠️ 带上 indexVerified=true：身份门是权威信号，数据层据此跳过「疑似假换轨」的旧兜底。
        notifyTrackChanged(null, where + " playlist " + from + " -> " + desc, true, identity);
        return true;
    }

    /**
     * 【code 978】把「播放列表身份」三元组打包成一个 long，供去抖/去重比较。
     *
     * <p>分量隔离（三项互不重叠）：{@code tc × 10^12 + durMs × 10^3 + idx}。
     * {@code trackCount ≤ 1000}、{@code durationMs ≤ 10^7}、{@code idx ≤ 10^4} 时不会碰撞。
     * 无列表键（老宿主 / AudioPlayer）时 {@code tc=-1,dur=-1} 都归 0 ⇒ 身份退化为 {@code idx}，
     * 老行为（按序号换轨）自然保留。
     */
    private static long makeIdentity(int trackCount, double durationSec, int idx) {
        long tc = trackCount < 0 ? 0L : (long) trackCount;
        long durMs = durationSec > 0 ? Math.round(durationSec * 1000.0) : 0L;
        long i = idx < 0 ? 0L : (long) idx;
        return tc * 1_000_000_000_000L + durMs * 1000L + i;
    }

    /** 【code 978】身份的可读描述（日志用）：{@code tc=6 dur=731832ms idx=0}。 */
    private static String describeIdentity(int trackCount, double durationSec, int idx) {
        String dur = durationSec > 0 ? (Math.round(durationSec * 1000.0) + "ms") : "-";
        return "tc=" + trackCount + " dur=" + dur + " idx=" + idx;
    }

    /**
     * 【code 975→976→978】变化（序号 **或** 播放列表身份）的**去抖闸门**：挡住横跳、但绝不把通道堵死。
     *
     * <p>真机日志（LSPosed_20261007_193455）显示宿主状态 Map 的 {@code currentIndex}
     * 在切轨期间于 0 与 2 之间**每秒横跳一次**；旧实现「一读一变就上报」于是在 15 秒里
     * 连发 15 次 {@code 2->0}，每次都让数据层把上一次排的裁决任务作废
     * ⇒ 裁决永远到不了点 ⇒ 字幕残留、窗口不关。
     *
     * <p>🔴 975 的第一版修法（「要求新值**连续稳定** ≥ INDEX_DEBOUNCE_MS，读到基线就
     * 调 {@link #clearPendingIndex()} 清零计时」）方向错了 —— 宿主是**规律交替**，
     * 候选每次出现后紧跟一次「读回基线」就把计时抹掉 ⇒ **永远攒不满** ⇒ 一次都不上报
     * （真机佐证：975 时段日志里换轨事件 0 条）。通道被彻底堵死，症状反而更糟。
     *
     * <p>976 改成「**首报即报 + 长抑制**」，978 原样沿用（只是比较对象由 int 序号换成 long 身份）：
     * <ol>
     *   <li>同一候选值**再次出现**（间隔 ≥ {@link #INDEX_DEBOUNCE_MS}）⇒ 判定为真变化；
     *       <b>不要求连续</b>，中途读回基线<b>不会</b>清零计时；</li>
     *   <li>上报后进入 {@link #INDEX_SUPPRESS_MS} 静默期：期内**同一对值（含反向）**
     *       不再上报 ⇒ 让数据层的裁决窗完整跑完到点；</li>
     *   <li>静默期内出现**第三个不同的值** ⇒ 用户又主动切了一次 ⇒ 立即穿透放行。</li>
     * </ol>
     * ⚠️ 978 真机上「多实例交替读数」依然存在（活跃列表 ↔ 幽灵列表）—— 所以第 2 条的长静默
     * 比以往任何时候都更重要：它同时挡住了「身份在两个列表之间来回跳」造成的重复上报。
     *
     * @return true = 本次可以认定为真正的变化
     */
    private static boolean settleChange(long from, long to, long now) {
        if (to != sPendingIdx) {
            // 换了一个候选 ⇒ 重新起计（不写基线、不上报）
            sPendingIdx = to;
            sPendingSinceMs = now;
            return false;
        }
        // 【code 976】同一候选「再次出现」⇒ 不是瞬时毛刺。
        //   ⚠️ 与 975 的关键差别：这里**不要求连续**，中途读回基线**不清零**计时。
        //   间隔下限只用来滤掉「同一采样帧被读两次」的重复回调。
        if (now - sPendingSinceMs < INDEX_DEBOUNCE_MS) {
            return false;
        }
        // 【code 976】静默期：同一对值（含反向）不再打扰数据层 —— 让它那次裁决跑完。
        boolean samePair = (from == sNotifiedFrom && to == sNotifiedTo)
                || (from == sNotifiedTo && to == sNotifiedFrom);
        if (samePair && (now - sNotifiedMs) < INDEX_SUPPRESS_MS) {
            // 【980】例外：目标身份 == 我们**手上 cues 的主人** ⇒ 这不是「宿主在两个列表间横跳」，
            //   而是用户切回了他刚才离开的那条轨。数据层对它有现成的 cues，可直接恢复渲染
            //   （见 SubtitleRepository#tryResumeFromCache）—— **没有裁决窗要保护**，
            //   硬静默 20s 只会让「无字幕」白挂十几秒。
            //   真机铁证（LSPosed_20261007_222354）：22:23:38.481 通知 7→1（正确变「无字幕」）后，
            //   用户约 22:23:45 就已切回 7，本条静默却一路挡到 22:23:58.746 才放行。
            if (SubtitleRepository.getInstance().ownsCuesFor(to)) {
                dbg("change " + from + "->" + to
                        + " back to the playlist our cues belong to -> pass through"
                        + " (no " + INDEX_SUPPRESS_MS + "ms silence)");
                // 放行后**让静默窗失效**：否则紧接着的「7→1」会被当成反向同对再静默 20s，
                // 那 20s 里模块会一直渲染**上一份** cues（错内容）。失效后任何一次真换轨都能立刻上报。
                sNotifiedMs = 0L;
            } else {
                dbg("change " + from + "->" + to
                        + " suppressed (same pair within " + INDEX_SUPPRESS_MS
                        + "ms of last notify -> host still jittering)");
                return false;
            }
        }
        clearPendingIndex();
        sNotifiedFrom = from;
        sNotifiedTo = to;
        sNotifiedMs = now;
        return true;
    }

    /** 【code 976】撤销「待定候选」——<b>仅在放行上报后</b>调用（不得在读到基线时调用，见类注释）。 */
    private static void clearPendingIndex() {
        sPendingIdx = Long.MIN_VALUE;
    }

    /**
     * 曲目序号变化检测（老信号路径）：只在<b>序号确实变了</b>时才当作换轨。
     */
    private static void notifyIfTrackIndexChanged(SubtitleRepository repo, String where, Object holder) {
        int last = sLastTrackIndex == null ? Integer.MIN_VALUE : sLastTrackIndex;
        int idx = readTrackIndex(holder);
        if (idx == Integer.MIN_VALUE) {
            notifyTrackChanged(repo, where + " [index unknown -> assume changed]");
            return;
        }
        long now = SystemClock.uptimeMillis();
        sLastIndexSeenMs = now;
        // 【code 977】与序号门一致：只在**本进程首次观测到序号**时种基线，
        //   不再用「距上次太久」重种（那会让稀疏读数永远进不了比较分支，见 onPlaylistStateFromStatusMap 的注释）。
        if (last == Integer.MIN_VALUE) {
            sLastTrackIndex = idx;
            XposedCompat.log(TAG + " seeded track index=" + idx + " from " + where
                    + " (first observation in this process, no change notification)");
            return;
        }
        if (idx != last) {
            // 【code 976→978】与状态 Map 门共用同一条闸门（同一候选再次出现 + 上报后长静默）。
            if (!settleChange(last, idx, now)) {
                return;
            }
            sLastTrackIndex = idx;
            if (isListReset(last, idx)) {
                dbg("ignored " + where + " " + last + "->" + idx
                        + " (playlist reset, not a track change)");
                return;
            }
            notifyTrackChanged(repo, where + " " + last + "->" + idx);
            return;
        }
        // 【code 976】与序号门一致：序号没变时**不得**清候选计时。
        dbg("ignored " + where + " (track index unchanged=" + idx
                + ", no-op navigation)");
    }

    /**
     * 是不是「播放列表被重置」而不是「列表内换轨」。
     *
     * <p>观测依据：真换轨是<b>相邻</b>变化（4→3、2→1），而 {@code 23→0} / {@code 10→0} /
     * {@code 5→0} 这类<b>骤然归 0</b>出现在进程重建、切换作品、重新装载列表时。
     * 这类事件必须<b>忽略</b>，否则会误清字幕、误判「无字幕」。
     */
    static boolean isListReset(int last, int idx) {
        return idx == 0 && last > 1;
    }

    /** 只记录序号，不产生任何换轨通知。用于把基线种上。 */
    private static void seedTrackIndex(Object holder, String where) {
        sLastIndexSeenMs = SystemClock.uptimeMillis();
        int idx = readTrackIndex(holder);
        if (idx != Integer.MIN_VALUE && (sLastTrackIndex == null || sLastTrackIndex != idx)) {
            sLastTrackIndex = idx;
            XposedCompat.log(TAG + " seeded track index=" + idx + " from " + where);
        }
    }

    /** 读取播放列表当前曲目序号；失败返回 {@link Integer#MIN_VALUE}。 */
    private static int readTrackIndex(Object holder) {
        if (holder == null) {
            return Integer.MIN_VALUE;
        }
        // 未混淆宿主：直接按方法名调
        try {
            Object r = XposedCompat.callMethod(holder, "getCurrentTrackIndex");
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable ignored) {
        }
        // 混淆宿主：按「无参 + 返回 int/Integer」形状找
        try {
            Method m = Shape.findNoArgAssignableTo(holder.getClass(), null, Integer.class);
            if (m != null) {
                Object r = m.invoke(holder);
                if (r instanceof Number) {
                    return ((Number) r).intValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 换轨通知出口（老信号路径）—— 信号本身不保证真的换了轨（见类头「真伪换轨的判据」），
     * 所以 {@code indexVerified = false}，数据层保留「疑似假换轨」的兜底。
     */
    private static void notifyTrackChanged(SubtitleRepository repo, String where) {
        notifyTrackChanged(repo, where, false, 0L);
    }

    /**
     * 换轨通知的唯一出口（带去重）。
     *
     * @param repo          可为 {@code null}（状态 Map 路径直接用单例，见下）
     * @param indexVerified 【973】true = 本次换轨来自<b>权威序号门</b>（状态 Map 的
     *                      {@code currentIndex} 确实变了）。序号变了就是播放列表的当前项变了，
     *                      不可能「假」⇒ 数据层据此跳过为老信号路径写的假换轨兜底。
     *                      日志会带上 {@code [index-gate]} 标记，便于真机核对走的是哪条路。
     * @param identity      【980】本次换轨<b>目标</b>的播放列表身份（{@code makeIdentity}；
     *                      0 = 老信号路径，数据层不认识）。
     */
    private static void notifyTrackChanged(SubtitleRepository repo, String where,
                                           boolean indexVerified, long identity) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastNotifyMs < DEDUP_MS) {
            return; // 同一次切换的多个 hook 点，只处理一次
        }
        sLastNotifyMs = now;
        XposedCompat.log(TAG + " >>> track changed: " + where
                + (indexVerified ? " [index-gate]" : ""));
        SubtitleRepository target = repo != null ? repo : SubtitleRepository.getInstance();
        target.onTrackChanged(where, indexVerified, identity);
    }
}
