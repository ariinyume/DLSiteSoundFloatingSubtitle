package com.sena.dlsitesoundfloat.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
 *   · 锚点**失效须连续 {@link #ANCHOR_DEAD_STREAK} 次**才隐藏（覆盖 0.8s 的假死窗口）；
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
 */
public class ActivityButtonHook {
    private static final String TAG = "[DLsiteSoundFloat:Button]";
    private static final String ACTIVITY_CLASS = "jp.co.eisys.dlsitesound.MainActivity";
    private static final int BUTTON_ID = 0x7F999001;
    private static final int BUTTON_BG_NORMAL = 0x99000000;
    private static final int BUTTON_BG_ACTIVE = 0x993A3968;

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
     * 锚点失效须**连续**这么多次采样才隐藏（v25 新增）。
     *
     * 为什么不能单次就隐藏：RN 切页过渡期页面容器会瞬时不可见，实测播放页上锚点
     * 「假死」约 **807ms**（06.051 dead → 06.858 alive）。取 4 次 × 200ms 跟踪心跳 ≈ 800ms，
     * 正好覆盖这个窗口。
     * 「真正离开播放页」有底部 mini-player 滑条这条正面证据兜底（{@link #HIDE_ON_OTHER_STREAK}=1），
     * 所以放宽这里不会让按钮在列表页赖着。
     */
    private static final int ANCHOR_DEAD_STREAK = 4;

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

    private static TextView sButton;
    private static Activity sActivity;
    private static ViewTreeObserver.OnGlobalLayoutListener sLayoutListener;
    private static long sLastDetectMs = 0L;
    private static String sLastBtnText;
    /** 连续判「非播放页」的采样次数。一旦判为播放页立即清零。 */
    private static int sNotPlayerStreak = 0;
    /** 本轮「连续判非播放页」的起点时刻（uptimeMillis）；0 = 当前不在连败中。 */
    private static long sNotPlayerSinceMs = 0L;
    /** 进入「未知」状态的时刻（uptimeMillis）；0 表示当前不是未知状态。 */
    private static long sUnknownSinceMs = 0L;
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
    /** 锚点**连续**失效的采样次数（v25，见 {@link #ANCHOR_DEAD_STREAK}）。 */
    private static int sAnchorDeadStreak = 0;

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

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedBridge.log(TAG + " activity onResume -> ensureButton");
                    ensureButton(activity, repo);
                }
            });

            XposedHelpers.findAndHookMethod(activityClass, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (!activity.getClass().getName().equals(ACTIVITY_CLASS)) {
                        return;
                    }
                    XposedBridge.log(TAG + " activity onPause -> removeButton");
                    removeButton(activity);
                }
            });

            repo.addObserver(() -> updateButtonText(repo));
            XposedBridge.log(TAG + " hooked Activity lifecycle");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook failed: " + e.getMessage());
        }
    }

    private static void ensureButton(Activity activity, SubtitleRepository repo) {
        uiHandler.post(() -> {
            try {
                sActivity = activity;
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                sButton = decor.findViewById(BUTTON_ID);
                boolean freshButton = false;
                if (sButton == null) {
                    sButton = createButton(activity, repo);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            dip2px(activity, 76),
                            dip2px(activity, 34));
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.leftMargin = dip2px(activity, 16);
                    lp.topMargin = dip2px(activity, 200);
                    sButton.setLayoutParams(lp);
                    // 用上次结论初始化，而不是一律 GONE。
                    // 否则每次 onResume 重建按钮都要从"隐形"开始，干等下一次扫描才出现。
                    sButton.setVisibility(Boolean.TRUE.equals(sLastDecision) ? View.VISIBLE : View.GONE);
                    decor.addView(sButton);
                    freshButton = true;
                    sLastBtnText = null; // 新按钮创建后，强制 updateButtonText 重新 setText
                    sNotPlayerStreak = 0;
                    sNotPlayerSinceMs = 0L;
                    sUnknownSinceMs = 0L;
                    sLastEvidenceSig = null;  // 允许下一次扫描重新打一行证据日志
                    sLastAnchorAlive = null;  // 锚点状态未知，允许重新打一行
                    sAnchorDeadStreak = 0;
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
                if (freshButton) {
                    XposedBridge.log(TAG + " button created, init visibility="
                            + sButton.getVisibility());
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " ensureButton error: " + e.getMessage());
            }
        });
    }

    private static void removeButton(Activity activity) {
        uiHandler.post(() -> {
            try {
                sActivity = null;
                uiHandler.removeCallbacks(detectRunnable);
                uiHandler.removeCallbacks(heartbeatRunnable);
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                if (sLayoutListener != null) {
                    decor.getViewTreeObserver().removeOnGlobalLayoutListener(sLayoutListener);
                    sLayoutListener = null;
                }
                View btn = decor.findViewById(BUTTON_ID);
                if (btn != null) {
                    decor.removeView(btn);
                }
                sButton = null;
                // 注意：sLastDecision **刻意不重置** —— 回到前台时用它初始化按钮可见性，
                // 避免「切后台回前台按钮文字/可见性丢失」。已确认页面类型后由检测覆盖。
                sNotPlayerStreak = 0;
                sNotPlayerSinceMs = 0L;
                sUnknownSinceMs = 0L;
                sLastVerdict = -1;
                sStableStreak = 0;
                sAnchorDeadStreak = 0;
                // 切后台后按钮被销毁，但 sLastBtnText 是静态变量，必须重置。
                // 否则 onResume 重建按钮时 updateButtonText 会认为文字没变而跳过 setText，
                // 导致回到前台按钮只剩背景、没有文字。
                sLastBtnText = null;
            } catch (Throwable e) {
                XposedBridge.log(TAG + " removeButton error: " + e.getMessage());
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
        if (sButton == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
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

            int screenW = decor.getWidth() > 0
                    ? decor.getWidth()
                    : decor.getResources().getDisplayMetrics().widthPixels;
            int screenH = decor.getHeight() > 0
                    ? decor.getHeight()
                    : decor.getResources().getDisplayMetrics().heightPixels;

            verdict = scan.pageVerdict();
            // 两条宽滑条同时在 → RN 正在做页面切换（旧页还没卸载）。这不是"未知页面"，
            // 而是"过渡态"，应当尽快复检定型，别让按钮在过渡期做错决定。
            ambiguous = scan.hasMainSlider && scan.hasBottomSlider;

            switch (verdict) {
                case SubtitleViewHook.PAGE_PLAYER: {
                    sNotPlayerStreak = 0;
                    sNotPlayerSinceMs = 0L;
                    sUnknownSinceMs = 0L;
                    sAnchorDeadStreak = 0;
                    sLastPlayerSeenMs = now;
                    sLastAnchorAlive = Boolean.TRUE;
                    // 更新播放页锚点（页面级容器），供主滑条被回收时继续判定「还在播放页」。
                    if (scan.anchorRef != null) {
                        sPlayerAnchor = scan.anchorRef;
                        if (!scan.anchorDesc.equals(sLastAnchorDesc)) {
                            sLastAnchorDesc = scan.anchorDesc;
                            XposedBridge.log(TAG + " player anchor -> " + scan.anchorDesc);
                        }
                    }
                    sLastDecision = Boolean.TRUE;
                    showButton(activity, repo, screenW, screenH);
                    break;
                }

                case SubtitleViewHook.PAGE_OTHER:
                    sUnknownSinceMs = 0L;
                    sAnchorDeadStreak = 0;
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

                default: { // PAGE_UNKNOWN：没有任何**能确认**的滑条证据
                    sNotPlayerSinceMs = 0L;
                    if (sUnknownSinceMs == 0L) {
                        sUnknownSinceMs = now;
                    }
                    // v26 兜底：上半屏主滑条在、页面容器几乎占满屏幕，只是**时间文本没配上**
                    // （换机型 / 换布局 / 文本被虚拟化回收）。此时仍把这个容器收作锚点，
                    // 让下面的锚点通道把按钮撑住 —— 配对判据万一失效，也不至于让功能整个失灵。
                    if (scan.hasMainSlider && !scan.hasBottomSlider && scan.anchorRef != null
                            && sPlayerAnchor == null) {
                        sPlayerAnchor = scan.anchorRef;
                        XposedBridge.log(TAG + " player anchor adopted (unpaired main slider) -> "
                                + scan.anchorDesc);
                    }
                    boolean anchorAlive = isPlayerAnchorAlive();
                    if (sLastAnchorAlive == null || sLastAnchorAlive != anchorAlive) {
                        sLastAnchorAlive = anchorAlive;
                        XposedBridge.log(TAG + " player anchor alive=" + anchorAlive
                                + " (area=" + Math.round(anchorAreaRatio(screenW, screenH) * 100) + "%)");
                    }
                    if (anchorAlive) {
                        // 页面级容器还在屏幕上 → 仍在播放页（主滑条只是被 RN 回收 / 控制条隐藏）。
                        // v25：**立即恢复显示**，不再傻等下一次扫到主滑条（那要 ~0.6s）。
                        sLastPlayerSeenMs = now;
                        sAnchorDeadStreak = 0;
                        if (now - sUnknownSinceMs >= ANCHOR_HARD_TIMEOUT_MS) {
                            // 兜底：锚点一直活着却一直 UNKNOWN（锚点可能选得过高）。保守隐藏。
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " UNKNOWN + anchor alive for "
                                        + (now - sUnknownSinceMs) + "ms -> hide (fallback)");
                            }
                            sLastDecision = Boolean.FALSE;
                            hideButton("unknown + anchor alive timeout");
                        } else if (!Boolean.TRUE.equals(sLastDecision)) {
                            sLastDecision = Boolean.TRUE;
                            showButton(activity, repo, screenW, screenH);
                        }
                    } else if (sPlayerAnchor == null) {
                        // 从未取到过锚点（findPlayerAnchor 返回 null 的页面结构）：只能靠时间宽限兜底。
                        if (sLastPlayerSeenMs == 0L || now - sLastPlayerSeenMs >= NO_ANCHOR_GRACE_MS) {
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " no player evidence for "
                                        + (sLastPlayerSeenMs == 0L ? -1 : (now - sLastPlayerSeenMs))
                                        + "ms (anchor=never) -> hide");
                            }
                            sLastDecision = Boolean.FALSE;
                            hideButton("player page left (no anchor)");
                        }
                    } else {
                        // 锚点失效：可能"真的离开了"，也可能"切页过渡期假死"（实测播放页上假死 ~807ms）。
                        // v25：连续 {@link #ANCHOR_DEAD_STREAK} 次都失效才隐藏，单次/两次失效不再误伤播放页。
                        sAnchorDeadStreak++;
                        if (sAnchorDeadStreak >= ANCHOR_DEAD_STREAK) {
                            if (Boolean.TRUE.equals(sLastDecision)) {
                                XposedBridge.log(TAG + " no player evidence for "
                                        + (sLastPlayerSeenMs == 0L ? -1 : (now - sLastPlayerSeenMs))
                                        + "ms (anchor=dead #" + sAnchorDeadStreak + ") -> hide");
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
                XposedBridge.log(TAG + " verdict=" + SubtitleViewHook.verdictName(verdict)
                        + " | " + scan.describe(screenW, screenH));
            }

            repo.setPlayerPageVisible(Boolean.TRUE.equals(sLastDecision));
        } catch (Throwable e) {
            XposedBridge.log(TAG + " detectAndLayout error: " + e.getMessage());
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

        boolean buttonVisible = sButton != null && sButton.getVisibility() == View.VISIBLE;
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

    /** 锚点在屏上的可见面积占比（仅用于日志诊断，判断锚点是否选得过高）。 */
    private static float anchorAreaRatio(int screenW, int screenH) {
        WeakReference<View> ref = sPlayerAnchor;
        View v = ref == null ? null : ref.get();
        return SubtitleViewHook.visibleAreaRatio(v, screenW, screenH);
    }

    private static void showButton(Activity activity, SubtitleRepository repo, int screenW, int screenH) {
        // 计算按钮位置：固定在「屏幕右下、底部播放控制条上方」。
        int wantRight = dip2px(activity, 16);
        int wantBottom = dip2px(activity, 96);
        if (sButton.getWidth() > 0 && wantRight + sButton.getWidth() > screenW) {
            wantRight = Math.max(0, screenW - sButton.getWidth() - dip2px(activity, 8));
        }
        if (sButton.getHeight() > 0 && wantBottom + sButton.getHeight() > screenH) {
            wantBottom = Math.max(0, screenH - sButton.getHeight() - dip2px(activity, 8));
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sButton.getLayoutParams();
        int wantGravity = Gravity.BOTTOM | Gravity.END;
        // 只在真的变化时才 setLayoutParams —— setLayoutParams 必然 requestLayout，
        // 在 onGlobalLayout 里无条件调用会形成永不停止的布局回环（卡顿主因）。
        if (lp.gravity != wantGravity
                || lp.leftMargin != 0
                || lp.topMargin != 0
                || lp.rightMargin != wantRight
                || lp.bottomMargin != wantBottom) {
            lp.gravity = wantGravity;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = wantRight;
            lp.bottomMargin = wantBottom;
            sButton.setLayoutParams(lp);
        }

        if (sButton.getVisibility() != View.VISIBLE) {
            sButton.setVisibility(View.VISIBLE);
            updateButtonText(repo);
            XposedBridge.log(TAG + " button shown (player page)");
        }
    }

    private static void hideButton(String reason) {
        if (sButton.getVisibility() != View.GONE) {
            sButton.setVisibility(View.GONE);
            XposedBridge.log(TAG + " button hidden (" + reason + ")");
        }
    }

    private static TextView createButton(Context ctx, SubtitleRepository repo) {
        TextView tv = new TextView(ctx);
        tv.setId(BUTTON_ID);
        tv.setTextSize(12);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dip2px(ctx, 8), 0, dip2px(ctx, 8), 0);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setBackground(createButtonDrawable(false));

        tv.setOnClickListener(v -> {
            if (!repo.hasSubtitles()) {
                XposedBridge.log(TAG + " no subtitles available");
                return;
            }
            // 注意：这里不再用 Settings.canDrawOverlays() 拦截。
            // OPPO/ColorOS 上该 API 即使用户已授予悬浮窗权限也返回 false，会导致点击后
            // 每次都跳权限页且无法开启。改为直接 toggle，由 FloatingWindowManager 真实
            // addView；只有真正抛异常（确实没权限）时才提示一次去授权。
            repo.toggleFloatingWindow();
            updateButtonDrawable(repo);
        });
        return tv;
    }

    private static GradientDrawable createButtonDrawable(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(active ? BUTTON_BG_ACTIVE : BUTTON_BG_NORMAL);
        drawable.setCornerRadius(8);
        drawable.setStroke(1, 0x80FFFFFF);
        return drawable;
    }

    private static void updateButtonText(SubtitleRepository repo) {
        if (sButton == null) {
            return;
        }
        final String text;
        final float alpha;
        if (!repo.hasSubtitles()) {
            text = "无字幕";
            alpha = 0.6f;
        } else if (repo.isFloatingWindowOpen()) {
            text = "悬浮开";
            alpha = 1.0f;
        } else {
            text = "悬浮关";
            alpha = 1.0f;
        }
        if (text.equals(sLastBtnText) && sButton.getAlpha() == alpha) {
            return; // 没变化就不折腾（避免每次通知都重建 drawable / 触发重绘）
        }
        sLastBtnText = text;
        uiHandler.post(() -> {
            if (sButton == null) {
                return;
            }
            sButton.setText(text);
            sButton.setAlpha(alpha);
            updateButtonDrawable(repo);
        });
    }

    private static void updateButtonDrawable(SubtitleRepository repo) {
        if (sButton == null) {
            return;
        }
        boolean active = repo.hasSubtitles() && repo.isFloatingWindowOpen();
        sButton.setBackground(createButtonDrawable(active));
    }

    private static int dip2px(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
