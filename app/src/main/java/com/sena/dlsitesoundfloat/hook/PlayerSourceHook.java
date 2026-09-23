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
package com.sena.dlsitesoundfloat.hook;

import android.os.SystemClock;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.util.XposedCompat;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 音轨切换 Hook —— 解决「切到没有字幕的音轨后，悬浮窗仍从头播放上一音轨的缓存字幕」。
 *
 * ─────────────────────────────────────────────────────────────────────
 * v17：音频是**播放列表**形态（expo.modules.audio.AudioPlaylist），列表内换轨不走 setMediaSource，
 *      故把 next / previous / skipTo / emitTrackChanged / getCurrentTrackIndex 全挂上。
 *
 * ── v27（1.20.4）：**「调了方法」≠「真的换轨」** ──
 * 第一轨按「上一首」时 App 是 no-op，但 previous() 仍被调用 → 误判无字幕 → 清 cues + 关窗。
 * 修法：给这些信号加「曲目序号二次确认」。
 *
 * ── v28（1.20.5）：**v27 的两处缺陷导致两个新 bug**（本轮修复）──
 *
 * 【缺陷 A：基线从未种入】v27 把 seed 挂在 {@code AudioPlaylist.setMediaSource} 上，
 *   但**该类根本没有这个方法**（日志实测 `[unconditional + seed] (0 methods)`，`seeded` 零命中），
 *   setMediaSource 实际在 {@code AudioPlayer} 上。后果：{@code sLastTrackIndex} 恒为 MIN_VALUE
 *   → 首次观测落进 `[first observed index]` 分支 → **把「进程重建后重新读到序号」当成换轨**。
 *   这正是【问题 1】的来源：App 退到后台被重建、回到前台重新读到序号 → 伪换轨通知 →
 *   数据层用被清零的播放位置（0）重算字幕行 → 画面**跳回该音轨已播过的开头字幕**。
 *
 * 【缺陷 B：序号回 0 被当成换轨】日志里大量 `23->0` / `10->0` / `5->0` / `3->0` ——
 *   这是**播放列表被重置/重新装载**（进程重建、切作品），不是用户在列表内换轨。
 *   用户换轨是 `4->3` / `2->1` 这类**相邻**变化。故新增 {@link #isListReset}：
 *   新序号为 0 且旧序号 &gt;1 → 判为「列表重置」，**不当作换轨通知**
 *   （由后续的 setMediaSource / 字幕 JSON 到达去驱动正常流程）。
 *
 * ── 真伪换轨的最终判据（本轮确立）──
 * v27 用「播放位置是否回退」做兜底判据，前提不成立：实测 54 次换轨里 **52 次 pos=0ms**
 * （切轨瞬间位置就被清零），「回退」恒真 → 真换轨全被误判成假换轨（见【问题 2】）。
 * 本版改为：**序号门负责识别「真换轨」**（相邻变化 = 真换轨；X-&gt;0 = 列表重置，忽略），
 * 位置判据仅在数据层作为**弱兜底**，且不再据其重算字幕行（见 SubtitleRepository）。
 *
 * 判定逻辑（是否保留字幕 / 是否关窗）全部在 {@link SubtitleRepository#onTrackChanged} 里。
 * 注意：拖动进度条走的是 seekTo，不会触发以上任何方法，因此不会误清字幕。
 * ─────────────────────────────────────────────────────────────────────
 */
public class PlayerSourceHook {
    private static final String TAG = "[DLsiteSoundFloat:Source]";
    /** 同一次切换可能触发多个 hook 点，去重窗口。 */
    private static final long DEDUP_MS = 500L;
    /**
     * 距上次「进程内已观测到序号」超过这么久，说明中间大概率经历了进程重建 / 长时间后台，
     * 此时读到的新序号不可信，**只重建基线、不发换轨通知**。
     */
    private static final long INDEX_STALE_MS = 30000L;

    private static volatile long sLastNotifyMs = 0L;
    /** 最近一次观察到的播放列表曲目序号（Integer.MIN_VALUE = 尚未观测到）。 */
    private static volatile int sLastTrackIndex = Integer.MIN_VALUE;
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
        // setMediaSource 在 AudioPlayer 上（**不是** AudioPlaylist）—— 这里同时种基线。
        // v27 误挂在 AudioPlaylist 上，导致 0 methods、基线从未种入（问题 1 的根因之一）。
        hookClass(cl, repo, "expo.modules.audio.AudioPlayer",
                PLAYER_SOURCE, false, true);
        hookClass(cl, repo, "expo.modules.audio.AudioPlaylist", PLAYLIST_SIGNALS, true, false);
        hookTrackIndex(cl, repo);
        hookClass(cl, repo, "androidx.media3.exoplayer.ExoPlayerImpl", EXO_SOURCE, false, false);
    }

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
            XposedCompat.log(TAG + " class not found: " + className);
            return;
        }
        int hooked = 0;
        for (final String name : methodNames) {
            // 迁移对照：旧 XposedBridge.hookAllMethods(cls, name, XC_MethodHook) 返回 Unhook 集合，
            // 代码里只取 .size()；XposedCompat.hookAllMethods 直接返回「成功挂钩的数量」。
            hooked += XposedCompat.hookAllMethods(cls, name, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    String where = className + "." + name;
                    if (requireIndexChange) {
                        notifyIfTrackIndexChanged(repo, where, chain.getThisObject());
                        return r;
                    }
                    if (seedIndexOnly) {
                        seedTrackIndex(chain.getThisObject(), where);
                    }
                    notifyTrackChanged(repo, where);
                    return r;
                }
            });
        }
        XposedCompat.log(TAG + " hooked " + className
                + (requireIndexChange ? " [index-verified]"
                        : (seedIndexOnly ? " [unconditional + seed]" : " [unconditional]"))
                + " (" + hooked + " methods)");
    }

    /**
     * 曲目序号变化检测：只在**返回值发生变化**时才当作换轨。
     *
     * ⚠️ 实测 JS 从不调用本方法（基线靠 {@link #seedTrackIndex} 种），
     * 这里保留是为了万一 App 某版本开始轮询它时仍能兜住。
     */
    private static void hookTrackIndex(ClassLoader cl, SubtitleRepository repo) {
        Class<?> cls;
        try {
            cls = XposedCompat.findClass("expo.modules.audio.AudioPlaylist", cl);
        } catch (Throwable e) {
            return;
        }
        try {
            int n = XposedCompat.hookAllMethods(cls, "getCurrentTrackIndex", new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object res) {
                    if (!(res instanceof Integer)) {
                        return res;
                    }
                    int idx = (Integer) res;
                    long now = SystemClock.uptimeMillis();
                    long since = now - sLastIndexSeenMs;
                    int last = sLastTrackIndex;
                    sLastIndexSeenMs = now;

                    // 首次观测 / 距上次观测太久（进程重建、长时间后台）→ 只重建基线，不发通知。
                    // ⚠️ v27 的 bug：这里发通知会被当成换轨；而此刻播放位置已被清零，
                    //    数据层会用它重算字幕行 → 跳回已播过的字幕（问题 1）。
                    if (last == Integer.MIN_VALUE || since > INDEX_STALE_MS) {
                        sLastTrackIndex = idx;
                        XposedCompat.log(TAG + " seeded track index=" + idx
                                + " (first/stale observation"
                                + (since > INDEX_STALE_MS ? ", since=" + since + "ms" : "") + ")");
                        return res;
                    }
                    if (idx != last) {
                        if (isListReset(last, idx)) {
                            // 列表被重置（切作品 / 重新装载），不是列表内换轨
                            sLastTrackIndex = idx;
                            XposedCompat.log(TAG + " ignored " + last + "->" + idx
                                    + " (playlist reset, not a track change)");
                            return res;
                        }
                        sLastTrackIndex = idx;
                        notifyTrackChanged(repo, "AudioPlaylist.getCurrentTrackIndex " + last + "->" + idx);
                    }
                    return res;
                }
            });
            XposedCompat.log(TAG + " hooked AudioPlaylist.getCurrentTrackIndex (" + n + " methods)");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " hookTrackIndex failed: " + e.getMessage());
        }
    }

    /**
     * 是不是「播放列表被重置」而不是「列表内换轨」。
     *
     * 观测依据：日志里真换轨是相邻变化（4-&gt;3、2-&gt;1），而 {@code 23->0} / {@code 10->0} /
     * {@code 5->0} 这类**骤然归 0** 出现在进程重建、切换作品、重新装载列表时。
     * 这类事件应当**忽略**（后续 setMediaSource 与字幕 JSON 会自然驱动流程），
     * 否则会误清字幕、误判「无字幕」。
     */
    private static boolean isListReset(int last, int idx) {
        return idx == 0 && last > 1;
    }

    /** 只记录序号，不产生任何换轨通知。用于把基线种上。 */
    private static void seedTrackIndex(Object holder, String where) {
        sLastIndexSeenMs = SystemClock.uptimeMillis();
        int idx = readTrackIndex(holder);
        if (idx != Integer.MIN_VALUE && sLastTrackIndex != idx) {
            sLastTrackIndex = idx;
            XposedCompat.log(TAG + " seeded track index=" + idx + " from " + where);
        }
    }

    /**
     * 只有「当前曲目序号确实变了」才通知换轨。
     *
     * 关键场景：在**第一轨**按「上一首」，App 是 no-op（序号仍是 0），
     * 此时必须忽略，否则会走 3 秒待确认 → 误判「无字幕」→ 清字幕 + 关窗。
     *
     * 序号读不到时退化为旧行为（无条件通知），由数据层的弱兜底再判一次。
     */
    private static void notifyIfTrackIndexChanged(SubtitleRepository repo, String where, Object holder) {
        int last = sLastTrackIndex;           // 先取基线：readTrackIndex 内部可能顺带刷新它
        int idx = readTrackIndex(holder);
        if (idx == Integer.MIN_VALUE) {
            notifyTrackChanged(repo, where + " [index unknown -> assume changed]");
            return;
        }
        // 基线尚未建立（或已过期）→ 只种基线，不通知。
        // v27 的 `[first observed index=N]` 分支会把「首次读到序号」当换轨，
        // 而首次读到往往发生在进程重建后、位置已清零，交给数据层就会跳回旧字幕（问题 1）。
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
            if (isListReset(last, idx)) {
                sLastTrackIndex = idx;
                XposedCompat.log(TAG + " ignored " + where + " " + last + "->" + idx
                        + " (playlist reset, not a track change)");
                return;
            }
            sLastTrackIndex = idx;
            notifyTrackChanged(repo, where + " " + last + "->" + idx);
            return;
        }
        // 序号没变 → 这个方法只是被调了一下，并没有真的换轨
        XposedCompat.log(TAG + " ignored " + where + " (track index unchanged=" + idx
                + ", no-op navigation)");
    }

    /** 读取播放列表当前曲目序号；失败返回 Integer.MIN_VALUE。 */
    private static int readTrackIndex(Object holder) {
        if (holder == null) {
            return Integer.MIN_VALUE;
        }
        try {
            Object r = XposedCompat.callMethod(holder, "getCurrentTrackIndex");
            if (r instanceof Integer) {
                return (Integer) r;
            }
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    private static void notifyTrackChanged(SubtitleRepository repo, String where) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastNotifyMs < DEDUP_MS) {
            return; // 同一次切换的多个 hook 点，只处理一次
        }
        sLastNotifyMs = now;
        repo.onTrackChanged(where);
    }
}
