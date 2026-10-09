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
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ariinyume.dlsitesoundfloat.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import io.github.ariinyume.dlsitesoundfloat.BuildConfig;
import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;
import io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

/**
 * 【code 996 第 4 批重构】SystemUI 作用域授权探测 + 双击状态栏字幕的请求接收器。
 *
 * <p>【code 941起】原本这些字段与方法全部内嵌在 {@link ActivityButtonHook} 里，
 * 本轮整体搬出。
 *
 * ====================================================================
 * 为什么能整体搬（而不是像 993/994/995 那样只抽零状态内核）
 * ====================================================================
 *
 * <p>本类是<b>唯一一组跨组字段数为 0</b>的候选：
 * {@code registerScopeWatch} / {@code registerDismissReceiver} / {@code refreshScopeState}
 * 三个方法，除了本组字段，只额外碰两样东西 ——
 * {@code TAG}（日志 tag）与 {@code uiHandler}（主线程调度器），
 * 两者都是<b>合理的注入依赖</b>，不是状态载体。
 *
 * <p>对照被否决的三组（详见 {@code work_diag_996/996-第4批重构-取证报告.md}）：
 * <ul>
 *   <li><b>G7 结构探针（12 字段）</b>：重置点在 {@code ensureButton} / {@code removeButton}
 *       两个<b>跨 7 个组</b>的枢纽方法里 ⇒ 只能抽纯内核 + 窄接口。
 *   <li><b>G5 快照（6 字段）</b>：散在 6 个方法里，且被 {@code CapsuleGeometry} 的调用侧读
 *       ⇒ 搬走会多一层无收益间接。
 *   <li><b>G3 sPick*（5 字段）</b>：跨组字段为 0，但两个方法都要接收整棵视图树上下文
 *       ⇒ 依赖注入比收益更重。
 * </ul>
 *
 * ====================================================================
 * ⚠️ 已知的重复：与 util/ScopeProbe.java 是同一职责的两处实现
 * ====================================================================
 *
 * <p>{@code util/ScopeProbe} 做的是<b>设置页进程侧</b>的同一件事（发 ping、等 pong、拿授权结果）；
 * 本类做的是<b>宿主 hook 进程内</b>的那一份。两者「同一职责、两处实现」，
 * 是本模块尚未收敛的一处重复。
 *
 * <p><b>本轮刻意不合并</b>：合并要动设置页，且两侧的进程边界不同（设置页要对
 * SystemUI + 宿主两个进程握手，hook 侧只对 SystemUI）。此处只把 hook 侧独立成类，
 * 并把重复显式记下来，供将来收敛时定位。
 *
 * ====================================================================
 * 握手机制（原样保留，未改任何时序）
 * ====================================================================
 *
 * <p>LSPosed 的作用域配置在 {@code /data/adb} 下，宿主 App 没有 root 读不到。
 * 但「勾了作用域」有一个<b>可观测的后果</b>—— 模块会被注入 SystemUI 进程，
 * 于是那边有人能应答广播。于是用握手代替读配置：
 *
 * <ol>
 *   <li>本进程定期发 {@link StatusBarSubtitleBridge#sendScopePing}；</li>
 *   <li>SystemUI 侧若已被注入，会回 {@link StatusBarSubtitleBridge#ACTION_SCOPE_PONG}；</li>
 *   <li>本进程在 {@link #refreshScopeState} 里比对「最近一次落笔时的授权态」，
 *       <b>翻转时才动手</b>（打一行日志 + 重画两个胶囊）。</li>
 * </ol>
 *
 * <p>为什么必须有心跳（而不是开一次探一次就完）：
 * ① 首次探测可能赶在 SystemUI 重启窗口里（接收器还没就绪）→ 要能自己重试；
 * ② 用户可能中途在 LSPosed 里<b>取消</b>勾选（重启 SystemUI 后生效）→ 要能回到未授权。
 */
final class ScopeWatcher {

    private static final String TAG = "DLsiteSoundFloat:Scope";

    /** 【code 941】心跳间隔。取 3s：比 SystemUI 重启（约 1~2s）长一点，别在重启窗口里误判。 */
    private static final long SCOPE_PING_INTERVAL_MS = 3000L;

    /**
     * 【code 944】首次探测的**快速补探**时刻（ms，相对 registerScopeWatch）。
     *
     * <p>为什么需要：只发一次即刻 PING 的话，若那一拍正好赶上 SystemUI 侧接收器
     * 还没注册好（SystemUI 刚重启），就白白等满一个心跳 3s —— 进播放页会先看到
     * 一个缺了左胶囊的按钮组，几秒后才补上。补两拍把确认时间压到接近即时。
     * <p>未授权时这两拍也只是静默无回包，零副作用。
     */
    private static final long SCOPE_KICK_1_MS = 400L;
    private static final long SCOPE_KICK_2_MS = 1200L;

    /** 【code 924】双击关闭请求的接收器（App 进程侧，唯一执行落点）。 */
    private static BroadcastReceiverHolder sDismissReceiver;

    /**
     * 【code 944】快速补探任务：只发 PING，不参与心跳链
     *（心跳链由 {@link #sScopePingRunnable} 独占，别把两条链搅在一起）。
     */
    private static final Runnable sScopeKickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context c = sScopePingCtx;
                if (c != null) {
                    StatusBarSubtitleBridge.sendScopePing(c);
                }
            } catch (Throwable t) {
                XposedCompat.log(TAG + " scope kick failed: " + t);
            }
        }
    };

    /** 【code 941】PONG 接收器。注册在**应用级 Context** 上，与 Activity 生命周期无关。 */
    private static android.content.BroadcastReceiver sScopePongReceiver;
    /** 【code 941】探测是否已启动（幂等；每次进播放页的 onResume 都会走到调用点）。 */
    private static boolean sScopeWatchStarted = false;
    /** 【code 941】发心跳用的 Context（应用级：onPause 后 Activity 会被清空，不能拿它当锚）。 */
    private static Context sScopePingCtx;
    /** 【code 941】最近一次**落笔时**的授权态 —— 只用来「翻转才打日志 / 才重画」。 */
    private static boolean sLastScopeAuthorized = false;
    /** 【code 941】是否已打过「首次状态」日志（见 refreshScopeState）—— 保证至少有线索。 */
    private static boolean sScopeEverReported = false;

    /**
     * 【code 941】作用域心跳：发 PING，然后看授权态有没有翻转。
     *
     * <p>为什么必须有心跳（而不是开一次探一次就完）：
     * ① 首次探测可能赶在 SystemUI 重启窗口里（接收器还没就绪）-> 要能自己重试；
     * ② 用户可能中途在 LSPosed 里<b>取消</b>勾选（重启 SystemUI 后生效）-> 要能回到未授权。
     * 两者都靠「心跳 + 新鲜期」（{@link StatusBarSubtitleBridge#SCOPE_FRESH_MS}）覆盖。
     */
    private static final Runnable sScopePingRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context c = sScopePingCtx;
                if (c != null) {
                    StatusBarSubtitleBridge.sendScopePing(c);
                }
                refreshScopeState("heartbeat");
            } catch (Throwable t) {
                XposedCompat.log(TAG + " scope heartbeat failed: " + t);
            }
            // 永不断链：即使上面抛异常也继续下一拍。
            uiHandler.postDelayed(this, SCOPE_PING_INTERVAL_MS);
        }
    };

    private static Handler uiHandler;
    /**
     * 重画胶囊的回调 —— 由 {@link ActivityButtonHook} 注入（那边才有 applyButtonText 的实现）。
     *
     * <p>⚠️ <b>包级可见，不可改成 private</b>：{@code install} 是包级静态方法，
     * 而 {@code ActivityButtonHook} 同在 {@code hook} 包，方法引用
     * {@code ActivityButtonHook::applyButtonText} 需要能拿到本类型。
     * （code 996 首编译就栽在这里，报「Repaint 在 ScopeWatcher 中是 private 访问权限」。）
     */
    interface Repaint {
        void applyButtonText(SubtitleRepository repo, boolean force);
    }

    private static Repaint sRepaint;

    /**
     * 【code 996】接线。必须先调一次，否则 {@link #uiHandler} 为 null 会 NPE。
     *
     * @param handler   ActivityButtonHook 的主线程 Handler（调度心跳 / 补探 / 重画）
     * @param repaint   重画胶囊的回调（转发到 ActivityButtonHook#applyButtonText）
     */
    static void install(Handler handler, Repaint repaint) {
        uiHandler = handler;
        sRepaint = repaint;
    }

    private static void repaintAsync(final SubtitleRepository repo) {
        uiHandler.post(() -> {
            if (sRepaint != null) {
                sRepaint.applyButtonText(repo, true);
            }
        });
    }

    /**
     * 【code 924】注册「双击状态栏字幕 -> 关闭」的请求接收器。
     *
     * <p>SystemUI 进程发 {@link StatusBarSubtitleBridge#ACTION_DISMISS_REQUEST}；
     * 本方法在 App 进程收到后，走<b>与胶囊按钮点击完全相同</b>的路径：
     * canToggle 判据 -&gt; toggleAppEnabled -&gt; resendCurrentFromRepo -&gt; 刷新按钮。
     * 这样「状态栏字幕开关」在整个系统里只有一个写入口（唯一计算函数）。
     */
    static void registerDismissReceiver(final Activity activity,
                                        final SubtitleRepository repo) {
        if (sDismissReceiver != null) {
            return;
        }
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent it) {
                    if (it == null
                            || !StatusBarSubtitleBridge.ACTION_DISMISS_REQUEST
                                    .equals(it.getAction())) {
                        return;
                    }
                    try {
                        if (!StatusBarSubtitleBridge.canToggle(repo)) {
                            XposedCompat.log(TAG + " dismiss ignored: no subtitles");
                            return;
                        }
                        boolean on = StatusBarSubtitleBridge.toggleAppEnabled(activity);
                        StatusBarSubtitleBridge.resendCurrentFromRepo(activity, repo);
                        XposedCompat.log(TAG + " status bar subtitle " + (on ? "ON" : "OFF")
                                + " (double tap on status bar)"
                                + " reason=" + it.getStringExtra(
                                        StatusBarSubtitleBridge.EXTRA_DISMISS_REASON));
                        repaintAsync(repo);
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " dismiss receiver failed: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(StatusBarSubtitleBridge.ACTION_DISMISS_REQUEST);
            try {
                activity.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                activity.registerReceiver(receiver, f);
            }
            sDismissReceiver = new BroadcastReceiverHolder(receiver);
            XposedCompat.log(TAG + " dismiss receiver registered (double tap)");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerDismissReceiver failed: " + t);
        }
    }

    /**
     * 【code 941】授权态<b>翻转时</b>才动手：打一行日志 + 重画两个胶囊。
     * 状态栏钮的可见性由 {@code ActivityButtonHook#statusBarButtonVisible} 决定，重画即生效。
     */
    private static void refreshScopeState(String why) {
        boolean now = StatusBarSubtitleBridge.isSystemUiScopeAuthorized();
        if (now == sLastScopeAuthorized) {
            if (!sScopeEverReported) {
                // 首次报告：**必须**留一行 —— 否则「没有 PONG 所以按钮不显示」这条会
                // 在日志里完全静默，事后排查只能靠猜。
                sScopeEverReported = true;
                LogGate.debug(TAG, " systemui scope: no pong yet -> status bar button"
                        + " stays hidden (com.android.systemui scope not granted,"
                        + " or SystemUI was not restarted after install)");
            }
            return;
        }
        sScopeEverReported = true;
        sLastScopeAuthorized = now;
        int build = StatusBarSubtitleBridge.getScopePongBuild();
        XposedCompat.log(TAG + " systemui scope -> " + (now ? "authorized" : "revoked")
                + " (" + why + ", pongBuild=" + build
                + ", appBuild=" + BuildConfig.VERSION_CODE + ")");
        if (now && build > 0 && build != BuildConfig.VERSION_CODE) {
            XposedCompat.log(TAG + " WARN systemui process runs an older build ("
                    + build + " != " + BuildConfig.VERSION_CODE
                    + ") -> restart SystemUI to load this build");
        }
        final SubtitleRepository repo = SubtitleRepository.getInstance();
        if (repo != null) {
            repaintAsync(repo);
        }
    }

    /**
     * 【code 941】启动作用域探测（幂等）。
     *
     * <p>用<b>应用级 Context</b>：本探测的生命周期是「整个 App 进程」，不该跟着
     * onResume/onPause 断链 —— 否则切后台再回来时 PONG 已过期，状态栏钮会先消失
     * 几秒再回来（可见的闪烁）。
     */
    static void registerScopeWatch(Context activityCtx) {
        if (sScopeWatchStarted) {
            return;
        }
        sScopeWatchStarted = true;
        try {
            Context appCtx = activityCtx != null ? activityCtx.getApplicationContext() : null;
            if (appCtx == null) {
                appCtx = activityCtx;
            }
            if (appCtx == null) {
                sScopeWatchStarted = false;
                return;
            }
            sScopePingCtx = appCtx;
            sScopePongReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent it) {
                    if (it == null
                            || !StatusBarSubtitleBridge.ACTION_SCOPE_PONG
                                    .equals(it.getAction())) {
                        return;
                    }
                    try {
                        int build = it.getIntExtra(
                                StatusBarSubtitleBridge.EXTRA_PONG_BUILD, -1);
                        StatusBarSubtitleBridge.onScopePong(build);
                        // 首次确认要**立刻**刷新（别等下一拍心跳），否则进播放页会先看到
                        // 一个没有状态栏钮的按钮组、几十~几百毫秒后才补上。
                        refreshScopeState("pong");
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " scope pong failed: " + t);
                    }
                }
            };
            IntentFilter f =
                    new IntentFilter(StatusBarSubtitleBridge.ACTION_SCOPE_PONG);
            try {
                // SystemUI 与本 App **不同 UID** ⇒ 收它的广播必须声明 EXPORTED，
                // 否则 Android 13+ 注册直接抛 SecurityException（与 SystemUI 侧收
                // ACTION_LINE 是同一类问题，见 StatusBarSubtitleHook#registerReceiver）。
                appCtx.registerReceiver(sScopePongReceiver, f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                appCtx.registerReceiver(sScopePongReceiver, f);
            }
            StatusBarSubtitleBridge.sendScopePing(appCtx);   // 先探一次，别干等 3s
            // 【code 944】再补两拍：覆盖「SystemUI 侧接收器晚一步就绪」这个窗口。
            uiHandler.postDelayed(sScopeKickRunnable, SCOPE_KICK_1_MS);
            uiHandler.postDelayed(sScopeKickRunnable, SCOPE_KICK_2_MS);
            uiHandler.removeCallbacks(sScopePingRunnable);
            uiHandler.postDelayed(sScopePingRunnable, SCOPE_PING_INTERVAL_MS);
            XposedCompat.log(TAG + " systemui scope watch started (ping "
                    + SCOPE_PING_INTERVAL_MS + "ms, fresh "
                    + StatusBarSubtitleBridge.SCOPE_FRESH_MS + "ms)");
        } catch (Throwable t) {
            // 注册失败就允许下次进播放页重试（否则一个异常会把探测永久废掉）。
            sScopeWatchStarted = false;
            XposedCompat.log(TAG + " registerScopeWatch failed: " + t);
        }
    }

    /** 只为让 {@link #sDismissReceiver} 的类型保持「非空判过」语义（幂等哨兵）。 */
    private static final class BroadcastReceiverHolder {
        final android.content.BroadcastReceiver receiver;

        BroadcastReceiverHolder(android.content.BroadcastReceiver receiver) {
            this.receiver = receiver;
        }
    }

    private ScopeWatcher() {
    }
}