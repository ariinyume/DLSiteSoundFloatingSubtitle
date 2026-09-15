package com.sena.dlsitesoundfloat.hook;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
            Class<?> cls = XposedHelpers.findClass(EXO_IMPL, cl);
            XposedHelpers.findAndHookMethod(cls, "getCurrentPosition", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Long) {
                        feed((Long) r, PRIO_EXO);
                        pollPlaybackState(param.thisObject); // v29：顺带采一次播放状态
                    }
                }
            });
            XposedBridge.log(TAG + " hooked ExoPlayerImpl.getCurrentPosition() [prio=0]");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " ExoPlayerImpl.getCurrentPosition hook failed: " + e.getMessage());
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
            Class<?> cls = XposedHelpers.findClass(EXO_IMPL, cl);
            XposedHelpers.findAndHookMethod(cls, "getPlaybackState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Integer) {
                        SubtitleRepository.getInstance().setPlaybackState((Integer) r);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked ExoPlayerImpl.getPlaybackState() [playback-ended detection]");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " ExoPlayerImpl.getPlaybackState hook failed: " + e.getMessage());
        }
    }

    /** 带节流地反射读一次播放状态；任何异常都静默（不能影响 App 自身调用）。 */
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
            Object st = XposedHelpers.callMethod(player, "getPlaybackState");
            if (st instanceof Integer) {
                SubtitleRepository.getInstance().setPlaybackState((Integer) st);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 采集点 1/2：expo-audio 包装类暴露给 JS 的 currentTime（秒）。 */
    private static void hookExpoCurrentTime(ClassLoader cl, SubtitleRepository repo, String className, int prio) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedHelpers.findAndHookMethod(cls, "getCurrentTime", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Double) {
                        double sec = (Double) r;
                        if (sec >= 0) {
                            feed((long) (sec * 1000.0), prio);
                        }
                    } else if (r instanceof Long) {
                        feed((Long) r, prio);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked " + className + ".getCurrentTime() [prio=" + prio + "]");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " " + className + ".getCurrentTime hook failed: " + e.getMessage());
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
