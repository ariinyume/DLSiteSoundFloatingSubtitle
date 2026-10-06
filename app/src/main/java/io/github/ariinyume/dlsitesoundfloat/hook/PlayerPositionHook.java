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
 * 轮询只在「已见过播放器实例」之后启动，且带 15s 无活动自动停机，避免空转。
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
    /** 多久没有真实调用就停止轮询（ms）—— 避免离开播放页后一直空转。 */
    private static final long POLL_IDLE_STOP_MS = 15000L;

    /** 最近一次「状态 Map 里有有效进度」的时刻（用于判断是否该停轮询）。 */
    private static volatile long sLastRealSampleMs = 0L;
    /** 进度采样的去重：同一秒内不重复喂。 */
    private static volatile long sLastFedSec = -1L;
    private static volatile boolean sPollScheduled = false;
    private static volatile boolean sLoggedShape = false;
    /** 最近见过的播放器实例（弱引用表，避免持有宿主对象导致泄漏）。 */
    private static final java.util.List<Object> sLastPlayers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());

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
                            consume((Map<?, ?>) r, repo, className);
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
    private static void consume(Map<?, ?> m, SubtitleRepository repo, String where) {
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

        // ④ 换轨：曲目序号变化 → 交给 PlayerSourceHook 统一判（它持有全部历史教训）
        Object idx = m.get("currentIndex");
        if (idx instanceof Number) {
            int cur = ((Number) idx).intValue();
            PlayerSourceHook.onTrackIndexFromStatusMap(cur, where);
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
     * 只靠 hook 会让字幕长时间不换行。
     *
     * <p>停止条件：连续 {@link #POLL_IDLE_STOP_MS} 没有真实样本（已离开播放页）。
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
                        if (sLastRealSampleMs > 0 && now - sLastRealSampleMs > POLL_IDLE_STOP_MS) {
                            XposedCompat.log(TAG + " polling auto-stop (no sample for "
                                    + (now - sLastRealSampleMs) + "ms)");
                            sPollScheduled = false;
                            return;
                        }
                        pollOnce(cl, repo);
                    } catch (Throwable ignored) {
                    }
                    // 无论成功与否都续上（失败时下一轮再试；auto-stop 分支已 return）
                    if (sPollScheduled) {
                        scheduleNextPoll(repo, cl, POLL_INTERVAL_MS);
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
                    consume((Map<?, ?>) r, repo, "poll:" + inst.getClass().getSimpleName());
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
            if (!sLastPlayers.contains(p)) {
                sLastPlayers.add(p);
                if (sLastPlayers.size() > 4) {
                    sLastPlayers.remove(0);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
