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
 * <h3>✅ 本版方案：曲目序号由「状态 Map」提供</h3>
 * 换轨判据的<b>本质</b>是「当前曲目序号变了没有」。而序号这个值，
 * expo-audio 状态 Map 里以字符串键 {@code currentIndex} 直接给出
 * （离线反汇编确认：{@code currentIndex -> Integer}，由播放器实例的 {@code p0()I} 填入）。
 * ⇒ 由 {@link PlayerPositionHook} 在消费状态 Map 时一并检测序号变化并发换轨通知，
 * <b>本文件不再自己挂钩任何换轨方法</b>。
 *
 * <h3>为什么保留本文件（而不是直接删掉）</h3>
 * ① {@link #notifyTrackChanged} 是换轨通知的<b>唯一出口</b>（含去重），
 * 仍被 {@link PlayerPositionHook} 调用；
 * ② 序号相关的判据（{@link #isListReset}「X→0 是列表重置不是换轨」、
 *    {@link #INDEX_STALE_MS}「进程重建后首次读数只种基线」）都集中在这里，便于维护；
 * ③ 老宿主（未混淆）上仍能挂到那五个信号，作为<b>状态 Map 之外的第二条换轨来源</b>。
 *
 * <h3>真伪换轨的判据（沿用并保留全部历史教训）</h3>
 * <ul>
 *   <li><b>首次 / 距上次太久</b>（进程重建、长时间后台）→ 只重建基线，<b>不发通知</b>。
 *       否则数据层会用被清零的播放位置（0）重算字幕行 ⇒ <b>画面跳回已播过的开头字幕</b>。</li>
 *   <li><b>X → 0</b>（且旧序号 &gt; 1）→ 判为「播放列表被重置 / 切作品」，
 *       <b>不</b>当换轨（否则会误清字幕、误判「无字幕」）。</li>
 *   <li><b>相邻变化</b>（4→3 / 2→1）→ 真换轨，通知。</li>
 *   <li>序号读不到 → 退化为旧行为（无条件通知），由数据层的弱兜底再判一次。</li>
 * </ul>
 * ⚠️ 拖动进度条走 seekTo，不改序号 ⇒ 不会误清字幕。
 */
public class PlayerSourceHook {
    private static final String TAG = "[DLsiteSoundFloat:Source]";

    /** 同一次切换可能触发多个 hook 点，去重窗口。 */
    private static final long DEDUP_MS = 500L;
    /**
     * 距上次「进程内已观测到序号」超过这么久，说明中间大概率经历了进程重建 / 长时间后台，
     * 此时读到的新序号不可信，<b>只重建基线、不发换轨通知</b>。
     */
    private static final long INDEX_STALE_MS = 30000L;

    private static volatile long sLastNotifyMs = 0L;
    /** 最近一次观察到的播放列表曲目序号（{@code null} = 尚未观测到）。 */
    private static volatile Integer sLastTrackIndex = null;
    /** 最近一次观测到序号的时间（用于识别进程重建后的「首次读数」）。 */
    private static volatile long sLastIndexSeenMs = 0L;

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
     * 由 {@link PlayerPositionHook} 在读到状态 Map 的 {@code currentIndex} 时调用。
     *
     * <p>这是 2.1.4 的<b>主换轨通道</b>：状态 Map 每次被读都会走到这里，
     * 而「读状态」在播放中必然频繁发生（宿主自己 + 我们的兜底轮询）。
     *
     * @return 本次是否发出了换轨通知（仅供诊断）
     */
    public static boolean onTrackIndexFromStatusMap(int idx, String where) {
        long now = SystemClock.uptimeMillis();
        long since = now - sLastIndexSeenMs;
        Integer last = sLastTrackIndex;
        sLastIndexSeenMs = now;

        // 首次 / 距上次太久 → 只种基线，不发通知（否则进程重建会被误判成换轨）
        if (last == null || since > INDEX_STALE_MS) {
            sLastTrackIndex = idx;
            XposedCompat.log(TAG + " seeded track index=" + idx + " from " + where
                    + " status map (first/stale" + (since > INDEX_STALE_MS ? ", since=" + since + "ms" : "")
                    + ", no change notification)");
            return false;
        }
        if (idx == last) {
            return false; // 序号没变 ⇒ 不是换轨
        }
        sLastTrackIndex = idx;
        if (isListReset(last, idx)) {
            XposedCompat.log(TAG + " ignored " + last + "->" + idx
                    + " (playlist reset, not a track change)");
            return false;
        }
        notifyTrackChanged(null, where + " " + last + "->" + idx);
        return true;
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
        long since = now - sLastIndexSeenMs;
        sLastIndexSeenMs = now;
        if (last == Integer.MIN_VALUE || since > INDEX_STALE_MS) {
            sLastTrackIndex = idx;
            XposedCompat.log(TAG + " seeded track index=" + idx + " from " + where
                    + " (first/stale observation, no change notification)");
            return;
        }
        if (idx != last) {
            sLastTrackIndex = idx;
            if (isListReset(last, idx)) {
                XposedCompat.log(TAG + " ignored " + where + " " + last + "->" + idx
                        + " (playlist reset, not a track change)");
                return;
            }
            notifyTrackChanged(repo, where + " " + last + "->" + idx);
            return;
        }
        XposedCompat.log(TAG + " ignored " + where + " (track index unchanged=" + idx
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
     * 换轨通知的唯一出口（带去重）。
     *
     * @param repo 可为 {@code null}（状态 Map 路径直接用单例，见下）
     */
    private static void notifyTrackChanged(SubtitleRepository repo, String where) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastNotifyMs < DEDUP_MS) {
            return; // 同一次切换的多个 hook 点，只处理一次
        }
        sLastNotifyMs = now;
        XposedCompat.log(TAG + " >>> track changed: " + where);
        SubtitleRepository target = repo != null ? repo : SubtitleRepository.getInstance();
        target.onTrackChanged(where);
    }
}
