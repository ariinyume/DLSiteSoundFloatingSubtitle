package com.sena.dlsitesoundfloat.hook;

import android.os.SystemClock;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 音轨切换 Hook —— 解决「切到没有字幕的音轨后，悬浮窗仍从头播放上一音轨的缓存字幕」。
 *
 * ─────────────────────────────────────────────────────────────────────
 * v17 修正：v9 只挂了 AudioPlayer.setMediaSource / ExoPlayer.setMediaItems 等。
 * 但逆向确认 App 的音频是 **播放列表形态**（expo.modules.audio.AudioPlaylist，
 * 内含 next / previous / skipTo / emitTrackChanged / getCurrentTrackIndex），
 * 在播放列表里「切换音轨」根本不重新 setMediaSource ——
 * 于是旧版 hook 全程不触发，字幕照旧串台。这正是该 bug 一直没修好的原因。
 *
 * ── v27（1.20.4 重打包）：**「调了方法」≠「真的换轨」**（本轮根治）──
 *
 * 实机 bug：在**第一轨**按「上一首」时，播放器本身是 no-op（继续播第一轨、不跳转），
 * 但 `AudioPlaylist.previous()` 依然被调用 → 旧逻辑无条件当成换轨 → 进入 3 秒待确认 →
 * 期间没有新的字幕 JSON（因为压根没换轨）→ 判定「本音轨无字幕」→
 * **清空 cues + 自动关闭悬浮窗**。日志铁证（15:40:40.092 → 15:40:43.092）：
 *
 *   track changed via AudioPlaylist.previous | lastJson=15375ms ago | cues=88 -> SUSPEND
 *   track decision: NO subtitles for this track (cues were 88) -> auto-closed floating window
 *
 * ── 修法：把 AudioPlaylist 上的方法分成两类 ──
 *   · {@link #PLAYLIST_SIGNALS}（next / previous / skipTo / onManualNavigation /
 *     emitTrackChanged）：**调用后比一次 `getCurrentTrackIndex()`**，
 *     **序号确实变了**才当作换轨；序号没变 → 本次是空操作，忽略并打日志
 *     （`ignored ... no-op navigation`）。
 *   · {@link #PLAYLIST_SOURCE}（AudioPlaylist.setMediaSource）与
 *     {@link #EXO_SOURCE}（ExoPlayer 的 setMediaItems 等）：真的换掉了媒体源 →
 *     无条件当作换轨。
 *
 * ── 序号基线（seeding）为什么必须单独做 ──
 * 实测日志显示 **JS 从不调用 `getCurrentTrackIndex()`**（全量日志里一次都没有触发过序号变化），
 * 所以只靠"变化才通知"的那个 hook，基线会永远停在「未观测到」，
 * 上面那道序号确认就会退化成旧行为、等于没加。
 * 因此额外在 {@code AudioPlaylist.setMediaSource} 时**只读取、不通知**地把基线种进去
 * （见 {@link #seedTrackIndex}）。基线永远滞后一个信号，正好就是我们要的"变更前状态"。
 *
 * 读不到序号（方法缺失 / 抛异常）时退化为旧行为（宁可误报也不漏报），
 * 由 {@link SubtitleRepository#onTrackChanged} 里的「播放位置回退」兜底再判一次。
 *
 * 判定逻辑（是否保留字幕 / 是否关窗）全部在 {@link SubtitleRepository#onTrackChanged} 里。
 * 注意：拖动进度条走的是 seekTo，不会触发以上任何方法，因此不会误清字幕。
 * ─────────────────────────────────────────────────────────────────────
 */
public class PlayerSourceHook {
    private static final String TAG = "[DLsiteSoundFloat:Source]";
    /** 同一次切换可能触发多个 hook 点，去重窗口。 */
    private static final long DEDUP_MS = 500L;

    private static volatile long sLastNotifyMs = 0L;
    /** 最近一次观察到的播放列表曲目序号（Integer.MIN_VALUE = 尚未观测到）。 */
    private static volatile int sLastTrackIndex = Integer.MIN_VALUE;

    /** 播放列表上「可能表示换轨、但不保证真的换了」的方法：必须用曲目序号二次确认。 */
    private static final String[] PLAYLIST_SIGNALS = {
            "emitTrackChanged", "next", "previous", "skipTo", "onManualNavigation"};
    /** AudioPlaylist 上真正换掉媒体源的方法：无条件当作换轨，顺便种序号基线。 */
    private static final String[] PLAYLIST_SOURCE = {"setMediaSource"};
    /** 底层 ExoPlayer 的媒体源替换：无条件当作换轨。 */
    private static final String[] EXO_SOURCE = {
            "setMediaItems", "setMediaItem", "setMediaSource", "setMediaSources"};

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        hookClass(cl, repo, "expo.modules.audio.AudioPlayer",
                new String[]{"setMediaSource"}, false, false);
        hookClass(cl, repo, "expo.modules.audio.AudioPlaylist", PLAYLIST_SOURCE, false, true);
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
            cls = XposedHelpers.findClass(className, cl);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " class not found: " + className);
            return;
        }
        int hooked = 0;
        for (final String name : methodNames) {
            try {
                hooked += XposedBridge.hookAllMethods(cls, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String where = className + "." + name;
                        if (requireIndexChange) {
                            notifyIfTrackIndexChanged(repo, where, param.thisObject);
                            return;
                        }
                        if (seedIndexOnly) {
                            seedTrackIndex(param.thisObject, where);
                        }
                        notifyTrackChanged(repo, where);
                    }
                }).size();
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + " hooked " + className
                + (requireIndexChange ? " [index-verified]"
                        : (seedIndexOnly ? " [unconditional + seed]" : " [unconditional]"))
                + " (" + hooked + " methods)");
    }

    /**
     * 曲目序号变化检测：只在**返回值发生变化**时才当作换轨。
     *
     * ⚠️ 实测 JS 从不调用本方法（基线靠 {@link #seedTrackIndex} 种），
     * 这里保留只是为了万一 App 某版本开始轮询它时仍能兜住。
     */
    private static void hookTrackIndex(ClassLoader cl, SubtitleRepository repo) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass("expo.modules.audio.AudioPlaylist", cl);
        } catch (Throwable e) {
            return;
        }
        try {
            int n = XposedBridge.hookAllMethods(cls, "getCurrentTrackIndex", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (!(res instanceof Integer)) {
                        return;
                    }
                    int idx = (Integer) res;
                    int last = sLastTrackIndex;
                    if (last == Integer.MIN_VALUE) {
                        sLastTrackIndex = idx; // 首次只记录，不当作换轨
                        return;
                    }
                    if (idx != last) {
                        sLastTrackIndex = idx;
                        notifyTrackChanged(repo, "AudioPlaylist.getCurrentTrackIndex " + last + "->" + idx);
                    }
                }
            }).size();
            XposedBridge.log(TAG + " hooked AudioPlaylist.getCurrentTrackIndex (" + n + " methods)");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hookTrackIndex failed: " + e.getMessage());
        }
    }

    /** 只记录序号，不产生任何换轨通知。用于把基线种上。 */
    private static void seedTrackIndex(Object playlist, String where) {
        int idx = readTrackIndex(playlist);
        if (idx != Integer.MIN_VALUE && sLastTrackIndex != idx) {
            sLastTrackIndex = idx;
            XposedBridge.log(TAG + " seeded track index=" + idx + " from " + where);
        }
    }

    /**
     * 只有「当前曲目序号确实变了」才通知换轨。
     *
     * 关键场景：在**第一轨**按「上一首」，App 是 no-op（序号仍是 0），
     * 此时必须忽略，否则会走 3 秒待确认 → 误判「无字幕」→ 清字幕 + 关窗。
     *
     * 序号读不到时退化为旧行为（无条件通知），由数据层的「播放位置回退」兜底。
     */
    private static void notifyIfTrackIndexChanged(SubtitleRepository repo, String where, Object playlist) {
        int last = sLastTrackIndex;           // 先取基线：readTrackIndex 内部可能顺带刷新它
        int idx = readTrackIndex(playlist);
        if (idx == Integer.MIN_VALUE) {
            notifyTrackChanged(repo, where + " [index unknown -> assume changed]");
            return;
        }
        if (last == Integer.MIN_VALUE) {
            sLastTrackIndex = idx;
            notifyTrackChanged(repo, where + " [first observed index=" + idx + "]");
            return;
        }
        if (idx != last) {
            sLastTrackIndex = idx;
            notifyTrackChanged(repo, where + " " + last + "->" + idx);
            return;
        }
        // 序号没变 → 这个方法只是被调了一下，并没有真的换轨
        XposedBridge.log(TAG + " ignored " + where + " (track index unchanged=" + idx
                + ", no-op navigation)");
    }

    /** 读取播放列表当前曲目序号；失败返回 Integer.MIN_VALUE。 */
    private static int readTrackIndex(Object playlist) {
        if (playlist == null) {
            return Integer.MIN_VALUE;
        }
        try {
            Object r = XposedHelpers.callMethod(playlist, "getCurrentTrackIndex");
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
