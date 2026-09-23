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

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.util.XposedCompat;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 播放进度 Hook —— 让悬浮窗能「按时间轴」定位当前字幕行。
 *
 * 背景（逆向结论）：
 * DLsiteSound 是 Expo/RN 应用，音频由 expo-audio 驱动，底层是
 * {@code androidx.media3.exoplayer.ExoPlayer}（Media3）。
 * App 自身的字幕逻辑是：Math.floor(currentPosition) 与 cue 的 start/end 比对。
 * 因此我们只要拿到同一个 currentPosition，就能精确复刻它的字幕对齐。
 *
 * 之前失败的根因：靠「抓屏取字幕文本」再与 cue 比对，抓回来的是界面文案，比不中 →
 * 索引恒为 -1 → 走了降级分支渲染了一堆 UI 文字。
 *
 * 三个采集点（按可靠性排序）：
 *   0. androidx.media3.exoplayer.ExoPlayerImpl.getCurrentPosition()  -> long(ms)  ← 最准
 *   1. expo.modules.audio.AudioPlaylist.getCurrentTime()             -> double(s)
 *   2. expo.modules.audio.AudioPlayer.getCurrentTime()               -> double(s)
 * 高优先级源一旦产出数据，低优先级源即被忽略（避免两个源秒边界抖动导致重复重绘）。
 */
public class PlayerPositionHook {
    private static final String TAG = "[DLsiteSoundFloat:Pos]";

    private static final int PRIO_EXO = 0;
    private static final int PRIO_PLAYLIST = 1;
    private static final int PRIO_PLAYER = 2;

    private static final String EXO_IMPL = "androidx.media3.exoplayer.ExoPlayerImpl";
    /**
     * v29：「播放状态」的轮询间隔。
     *
     * 为什么不直接只 hook {@code getPlaybackState()}：JS 侧从来不调用它（和当年
     * {@code getCurrentTrackIndex()} 一模一样的坑），挂上去会静默 0 命中。
     * 因此改在**已经确认会被高频调用**的 {@code getCurrentPosition()} 之后顺带反射读一次状态，
     * 并用本间隔节流（状态是 int，读一次开销极小，节流只为省锁竞争）。
     */
    private static final long STATE_POLL_INTERVAL_MS = 300L;
    private static volatile long sLastStatePollMs = 0L;

    /** 当前生效的源优先级；越小越优先。 */
    private static volatile int sActivePriority = Integer.MAX_VALUE;

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        hookExoPosition(cl, repo);
        hookPlaybackState(cl, repo);
        hookExpoCurrentTime(cl, repo, "expo.modules.audio.AudioPlaylist", PRIO_PLAYLIST);
        hookExpoCurrentTime(cl, repo, "expo.modules.audio.AudioPlayer", PRIO_PLAYER);
    }

    /** 采集点 0：Media3 ExoPlayer 的真实播放位置（毫秒）。 */
    private static void hookExoPosition(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method m = XposedCompat.findMethodByName(cls, "getCurrentPosition");
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    if (r instanceof Long) {
                        feed((Long) r, PRIO_EXO);
                        pollPlaybackState(chain.getThisObject()); // v29：顺带采一次播放状态
                    }
                    return r; // 位置读取必须原样返回，否则会改写宿主播放进度
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl.getCurrentPosition() [prio=0]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " ExoPlayerImpl.getCurrentPosition hook failed: " + e.getMessage());
        }
    }

    /**
     * v29：播放状态采集（双保险）——「播放结束自动关窗」依赖它。
     *
     * ① 主动 hook {@code getPlaybackState()}：万一 JS / Media3 内部会读，就能第一时间拿到；
     * ② 位置回调里顺带轮询（见 {@link #pollPlaybackState}）：这条才是主力，
     *    因为它挂在**确定会被调用**的方法上。
     *
     * 状态取值来自 {@code androidx.media3.common.Player}：
     * 1=IDLE / 2=BUFFERING / 3=READY / 4=ENDED。
     * 只有 ENDED 会被仓库当成「播放结束」，IDLE 不处理（加载新内容时也会短暂 IDLE，误判会乱关窗）。
     */
    private static void hookPlaybackState(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method m = XposedCompat.findMethodByName(cls, "getPlaybackState");
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    if (r instanceof Integer) {
                        SubtitleRepository.getInstance().setPlaybackState((Integer) r);
                    }
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl.getPlaybackState() [playback-ended detection]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " ExoPlayerImpl.getPlaybackState hook failed: " + e.getMessage());
        }
        hookPlayWhenReady(cl);
    }

    /**
     * v45：播放 / 暂停采集 —— 「暂停后状态栏字幕消失、恢复播放后再出现」依赖它。
     *
     * ⚠️ 不能只看 {@code getPlaybackState()}：它的取值只有 IDLE/BUFFERING/READY/ENDED，
     *    **READY 同时覆盖「正在播」和「已暂停」**；只有 {@code getPlayWhenReady()} 能区分。
     *
     * 三条路一起上（任一命中即可）：
     *   ① hook {@code setPlayWhenReady(boolean)} —— 语义最直接；
     *   ② hook {@code play()} / {@code pause()} —— expo-audio 实际走这两个。注意 ExoPlayerImpl
     *      的 {@code play()} 内部调的是**私有**的 setPlayWhenReadyInternal，不一定经过公开的
     *      {@code setPlayWhenReady}，所以这两个必须单独挂；
     *   ③ 位置回调里顺带轮询 {@code getPlayWhenReady()}（见 pollPlaybackState）—— 兜底，
     *      万一 ①② 都挂不上。
     * 仓库侧对「未知」是宽容的（未知 = 当在播、不隐藏字幕），所以三条全挂也只会退化成
     * 旧行为，不会把字幕彻底弄没。
     */
    private static void hookPlayWhenReady(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method m = XposedCompat.findMethodExact(cls, "setPlayWhenReady", boolean.class);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    Object a = chain.getArg(0);
                    if (a instanceof Boolean) {
                        SubtitleRepository.getInstance().setPlaying((Boolean) a);
                    }
                    return r; // setPlayWhenReady 返回 void → 原样返回 null
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl.setPlayWhenReady(boolean) [play/pause]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " ExoPlayerImpl.setPlayWhenReady hook failed: " + e.getMessage());
        }
        hookNoArgPlayControl(cl, "play", true);
        hookNoArgPlayControl(cl, "pause", false);
    }

    /** play() / pause() 无参重载 —— expo-audio 暂停/继续真正走的路径。 */
    private static void hookNoArgPlayControl(ClassLoader cl, String method, final boolean playing) {
        try {
            Class<?> cls = XposedCompat.findClass(EXO_IMPL, cl);
            Method m = XposedCompat.findMethodExact(cls, method);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    SubtitleRepository.getInstance().setPlaying(playing);
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked ExoPlayerImpl." + method + "() [play/pause]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " ExoPlayerImpl." + method + " hook failed: " + e.getMessage());
        }
    }

    /** 带节流地反射读一次播放状态 / 播放意愿；任何异常都静默（不能影响 App 自身调用）。 */
    private static void pollPlaybackState(Object player) {
        if (player == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastStatePollMs < STATE_POLL_INTERVAL_MS) {
            return;
        }
        sLastStatePollMs = now;
        try {
            Object st = XposedCompat.callMethod(player, "getPlaybackState");
            if (st instanceof Integer) {
                SubtitleRepository.getInstance().setPlaybackState((Integer) st);
            }
            Object pwr = XposedCompat.callMethod(player, "getPlayWhenReady");
            if (pwr instanceof Boolean) {
                SubtitleRepository.getInstance().setPlaying((Boolean) pwr);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 采集点 1/2：expo-audio 包装类暴露给 JS 的 currentTime（秒）。 */
    private static void hookExpoCurrentTime(ClassLoader cl, SubtitleRepository repo, String className, int prio) {
        try {
            Class<?> cls = XposedCompat.findClass(className, cl);
            Method m = XposedCompat.findMethodByName(cls, "getCurrentTime");
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object r) {
                    if (r instanceof Double) {
                        double sec = (Double) r;
                        if (sec >= 0) {
                            feed((long) (sec * 1000.0), prio);
                        }
                    } else if (r instanceof Long) {
                        feed((Long) r, prio);
                    }
                    return r;
                }
            });
            XposedCompat.log(TAG + " hooked " + className + ".getCurrentTime() [prio=" + prio + "]");
        } catch (Throwable e) {
            XposedCompat.log(TAG + " " + className + ".getCurrentTime hook failed: " + e.getMessage());
        }
    }

    /**
     * 把位置喂给仓库，但只认「当前最高优先级」的源。
     * 这样即使三个源同时上报，也只会用一个，避免抖动。
     */
    private static void feed(long ms, int priority) {
        int active = sActivePriority;
        if (priority > active) {
            return; // 已有更高优先级源在供数
        }
        if (priority < active) {
            sActivePriority = priority;
        }
        SubtitleRepository.getInstance().setPlaybackPositionMs(ms);
    }
}
