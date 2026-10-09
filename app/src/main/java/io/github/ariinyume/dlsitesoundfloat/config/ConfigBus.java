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
package io.github.ariinyume.dlsitesoundfloat.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 【M1】配置相关的**跨进程广播总线**（模块进程 ↔ DLsiteSound 进程 ↔ SystemUI 进程）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 两条用途（都在同一个接收器里处理，避免在宿主进程挂多个接收器）
 *
 *  ① {@link #ACTION_CONFIG_CHANGED} —— 设置页保存成功后广播，被注入的两个进程收到后
 *     调 {@link RemoteConfig#reload()}（PRD §9.2「成功：通知 SystemUI → 悬浮窗刷新」）。
 *     等价于 PRD §5.3 A1 里 FileObserver 的角色，但用广播实现：
 *     没有跨 UID 的文件监听权限问题，且「收不到」天然退化成 A2（手动重启生效）。
 *
 *  ② {@link #ACTION_SCOPE_PING}/{@link #ACTION_SCOPE_PONG} —— 作用域授权探测。
 *     SystemUI 一路复用既有通道（{@code StatusBarSubtitleBridge.ACTION_SCOPE_PING}，
 *     见 code 941）；这里补的是 **DLsiteSound 进程** 那一路 ——
 *     设置页发 PING，被注入 DLsiteSound 的模块回 PONG，
 *     于是状态卡能**分立**判断「DLsiteSound 已授权」与「SystemUI 已授权」（PRD §FR-01 规则 3/4）。
 *
 * ⚠️ 为什么不用 PRD 原文的「hook 心跳标记文件」（§FR-01 规则 5）：
 *    跨 UID 写文件需要双方都能写的公共目录，而 Android 11+ 的 scoped storage 与
 *    SELinux 都不允许 SystemUI / 普通 App 互写对方数据目录 —— 该方案在真机上不可靠
 *    （这正是 PRD §14 RK-02 的顾虑）。广播握手是本模块 1.21.16 起已在真机验证过的机制
 *    （见 StatusBarSubtitleBridge 的 code 941 注释），这里沿用同一套口径，
 *    并保留「收不到即未知」的降级态（PRD §12 E-10）。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class ConfigBus {

    private static final String TAG = "[DLsiteSoundFloat:ConfigBus]";

    /** 设置页 → 各被注入进程：配置已变更，请重载。 */
    public static final String ACTION_CONFIG_CHANGED =
            "io.github.ariinyume.dlsitesoundfloat.action.CONFIG_CHANGED";

    /** 设置页 → SystemUI 进程：请求重启系统界面（模块收到后自杀，系统会自动把 SystemUI 拉起来）。 */
    public static final String ACTION_RESTART_SYSUI =
            "io.github.ariinyume.dlsitesoundfloat.action.RESTART_SYSUI";

    /** 设置页 → DLsiteSound 进程：作用域探测。 */
    public static final String ACTION_HOST_PING =
            "io.github.ariinyume.dlsitesoundfloat.action.SCOPE_HOST_PING";

    /** DLsiteSound 进程 → 设置页：探测应答。 */
    public static final String ACTION_HOST_PONG =
            "io.github.ariinyume.dlsitesoundfloat.action.SCOPE_HOST_PONG";

    /** PONG 携带的模块构建号（与 {@code BuildConfig.VERSION_CODE} 同源，用于版本一致性告警）。 */
    public static final String EXTRA_BUILD = "build";
    /** PONG 携带的进程标识（"host" / "systemui"）。 */
    public static final String EXTRA_TAG = "tag";
    /**
     * 【2.3.0】宿主 PONG 携带的「DLsiteSound 当前真的在用」标志（boolean）。
     *
     * 判据（与 {@link ScopeProbe.Result#hostAlive} 严格对应）：
     *   宿主**播放页在前台**（{@code SubtitleRepository#isPlayerPageVisible()}）
     *   **或** 播放器**确实在播**（{@code getPlayingState() == 1}，即明示 playing，
     *   不含「未知」）—— 见 {@link #isHostAlive()}。
     * 只表示「正在用」，**不**表示「已授权」；授权仍由「收到带 tag=host 的 PONG」证明。
     */
    public static final String EXTRA_HOST_ALIVE = "host_alive";

    /**
     * {@link #ACTION_CONFIG_CHANGED} 携带的**完整配置 JSON**（{@link SubtitleConfig#toJson()} 产物）。
     *
     * ⚠️ 为什么广播要带负载（M1 真机返工的根修）：设置页写的是模块自己进程的
     * {@code MODE_PRIVATE} SharedPreferences，而 hook 侧 {@code getRemotePreferences}
     * 读的是 LSPosed 框架托管的**另一份存储** —— 真机日志铁证
     * {@code remote prefs getAll(): EMPTY}（设置页明明保存成功）。两边根本不是同一份文件，
     * 「保存后只发空通知、让 hook 自己去读」的旧设计在真机上永远读不到。
     * 修正：广播直接把配置带过去，hook 侧解析后内存生效，并用
     * {@code getRemotePreferences().edit()}（hook 侧持有 XposedInterface，可写）持久化，
     * 下次进程冷启动也有配置可读。
     */
    public static final String EXTRA_CONFIG_JSON = "config_json";

    /** 已挂过接收器的进程标识（同一进程只挂一次）。 */
    private static final Set<String> sInstalled = ConcurrentHashMap.newKeySet();

    private ConfigBus() {
    }

    // ==================================================================
    // 被注入进程侧：安装应答器
    // ==================================================================

    /**
     * 在被注入的进程里挂一个接收器（幂等）。
     *
     * @param ctx 该进程里可用的 Context（DLsiteSound 进程传系统上下文 / Application 上下文；
     *            SystemUI 进程传状态栏所属的 Context）
     * @param tag 进程标识，仅用于日志与 PONG 内容（"host" / "systemui"）
     */
    public static void installResponder(Context ctx, String tag) {
        if (ctx == null || tag == null || !sInstalled.add(tag)) {
            return;
        }
        // 【2.3.1 兜底 · 用例 2.1.1 / 2.6 / 2.8】tag="host" 只允许挂在**宿主进程**里。
        // 根因见 DlsiteSoundSubtitleModule#onPackageLoaded 的注释：SystemUI 进程曾因
        // Application#attach 钩子未按包名过滤，也挂了一个自称 host 的应答器，把
        // hostAlive=false 抢先答给设置页。那里的过滤是主修，这里再加一道按进程名的
        // 硬闸 —— 即便将来又有人误调，也不会再造出「两个进程抢答 host」的局面。
        if ("host".equals(tag) && !isHostProcess(ctx)) {
            sInstalled.remove(tag);
            logWarn("installResponder(host) refused: not the DLsiteSound process");
            return;
        }
        // 优先挂在 Application 上下文上：宿主进程里拿到的可能是「系统上下文」或某个视图的
        // Context，挂在 Application 上可保证接收器生命周期与进程一致，不随页面销毁而失效。
        try {
            Context app = ctx.getApplicationContext();
            if (app != null) {
                ctx = app;
            }
        } catch (Throwable ignored) {
        }
        // 【2.3.0 方案 K】顺手把进程上下文交给 RemoteConfig：hook 侧要靠它把自己收到的配置
        // 写进本进程本地 prefs（remote prefs 在 LSPosed 2.2.0 是只读的，写必抛）。
        RemoteConfig.attachContext(ctx);
        // 【2.3.1】宿主进程上下文留一份给 isHostAlive() 查本进程 importance。
        if ("host".equals(tag)) {
            sHostCtx = ctx;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_CONFIG_CHANGED);
        f.addAction(ACTION_HOST_PING);
        if ("systemui".equals(tag)) {
            // 重启系统界面只由 SystemUI 进程内的模块处理
            f.addAction(ACTION_RESTART_SYSUI);
        }
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) {
                    return;
                }
                try {
                    String action = i.getAction();
                    if (ACTION_HOST_PING.equals(action)) {
                        answerPing(c, tag);
                        return;
                    }
                    if (ACTION_RESTART_SYSUI.equals(action)) {
                        restartSystemUi(tag);
                        return;
                    }
                    if (ACTION_CONFIG_CHANGED.equals(action)) {
                        String json = i.getStringExtra(EXTRA_CONFIG_JSON);
                        boolean applied = false;
                        if (json != null && !json.isEmpty()) {
                            applied = RemoteConfig.applyFromJson(json);
                        }
                        if (!applied) {
                            // 兼容旧格式（不带负载的广播）：退回「自己重读」
                            RemoteConfig.reload();
                        }
                        logDebug("[" + tag + "] config changed -> " + RemoteConfig.get().summary());
                        // 【2.3.0】保存后**立刻**生效：配置已进内存，但渲染只在仓库观察者
                        // 触发时才跑 —— 没在播放/字幕没换行时观察者不会来，新样式要等到下一句
                        // 字幕才现身。这里主动重画一次当前画面（悬浮窗 / 状态栏各行其道）。
                        applyConfigImmediately(c, tag);
                        if ("host".equals(tag)) {
                            // 状态栏字幕开关的真源在宿主进程（见 StatusBarSubtitleBridge.sAppEnabled）：
                            // 配置里带了它的持久化值，这里同步并把状态推给 SystemUI。
                            syncStatusbarEnabledFromConfig(c);
                        }
                    }
                } catch (Throwable t) {
                    // 接收器体绝不把异常抛回宿主
                    logWarn("[" + tag + "] onReceive failed: " + t);
                }
            }
        };
        try {
            // Android 13+ 动态注册必须声明可见性标志；跨 UID（模块进程 ↔ 宿主）必须 EXPORTED，
            // 否则注册即抛 SecurityException（与 StatusBarSubtitleHook#registerReceiver 同因）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            logInfo("[" + tag + "] responder installed");
        } catch (Throwable t) {
            sInstalled.remove(tag);
            logWarn("[" + tag + "] registerReceiver failed: " + t);
        }
    }

    /**
     * 【2.3.1】当前进程是不是宿主的进程。
     *
     * ⚠️ 判据刻意**不用** {@code getPackageName()}：宿主与 SystemUI 拿到的 Context 可能来自
     * 系统上下文（package = "android"），按包名判会两边都判错。用 {@code ActivityThread}
     * 的进程名才是准的 —— 宿主进程名恒为 {@code jp.co.eisys.dlsitesound}（或其子进程），
     * SystemUI 侧恒为 {@code com.android.systemui}(:xxx)。取不到进程名时**放行**
     * （宁可不拦，也不要因为一个反射失败把宿主自己的应答器拦掉）。
     */
    private static boolean isHostProcess(Context ctx) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            String pn = (String) at.getMethod("getProcessName").invoke(cur);
            if (pn == null || pn.isEmpty()) {
                return true;
            }
            int colon = pn.indexOf(':');
            String base = colon > 0 ? pn.substring(0, colon) : pn;
            return HOST_PKG.equals(base);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 宿主包名（仅用于进程名比对，见 {@link #isHostProcess(Context)}）。 */
    private static final String HOST_PKG = "jp.co.eisys.dlsitesound";
    /** SystemUI 包名（显式配置广播的第二目标，见 {@link #sendConfigChanged}）。 */
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    /**
     * 应答作用域 PING。
     *
     * 【2.3.0 根修】只对 **{@code tag == "host"}** 应答：
     * 本接收器在宿主与 SystemUI **两个**进程里都挂（SystemUI 侧 tag="systemui"），
     * 而两边的 PONG 用的是同一个 action —— 于是设置页那条「DLsiteSound 已授权」
     * 只要 SystemUI 答了话就成立，即使用户**根本没勾宿主**（或已在 LSPosed 里划掉），
     * 状态卡照样显示「模块已激活」（用例 1.2 / 1.4）。
     * SystemUI 授权本来就走它自己的 {@code StatusBarSubtitleBridge.ACTION_SCOPE_PING/PONG}
     * 通道，不受这条限制影响。
     */
    private static void answerPing(Context ctx, String tag) {
        if (ctx == null || !"host".equals(tag)) {
            return;
        }
        try {
            Intent out = new Intent(ACTION_HOST_PONG);
            out.putExtra(EXTRA_BUILD, buildCode());
            out.putExtra(EXTRA_TAG, tag);
            out.putExtra(EXTRA_HOST_ALIVE, isHostAlive());
            ctx.sendBroadcast(out);
            logDebug("[host] scope ping answered -> pong sent (hostAlive="
                    + out.getBooleanExtra(EXTRA_HOST_ALIVE, true) + ")");
        } catch (Throwable t) {
            logWarn("answerPing failed: " + t);
        }
    }

    /**
     * 【2.3.1 重做】宿主「这个进程还在正常活着吗」。
     *
     * ─────────────────────────────────────────────────────────────────────
     * ⚠️ 这里**换掉了** 2.3.0 的口径（{@code isPlayerPageVisible() || getPlayingState()==1}），
     *    那套口径在真机上把「没在播放 / 只是没停在播放页」全都算成了「未运行」——
     *    用例 2.1.1 的原始描述就是它：
     *      「DLsiteSound 开启但暂停播放的时候，设置页会显示 DLsiteSound 未运行（但实际软件开在后台）」；
     *    更糟的是它还**自己抖**：用户一打开设置页，DLsiteSound 本来就被切到后台
     *    （{@code isPlayerPageVisible()} 变 false），于是状态卡刚进来就翻成「未运行」，
     *    在播放页与设置页之间来回切时更是反复跳 —— 正是报告里说的「即使在播放的情况下，
     *    也会莫名其妙跳到『未运行』」。
     *
     * 本版的判据分两层，语义上严格对应「用户把 App 划掉了吗」：
     *   ① **首选：本进程的 importance**（{@code ActivityManager#getMyMemoryState}）。
     *      宿主进程只要没被划掉，最差也是 {@code IMPORTANCE_CACHED}；被划掉（或被杀）后
     *      进程根本不会再答 PONG，这个位也就没有意义；而在「退到后台但还活着」时它仍是
     *      {@code IMPORTANCE_FOREGROUND/SERVICE/VISIBLE} 一类的非缓存态 ⇒ **不会再误报**。
     *      判据取 {@code importance <= IMPORTANCE_CACHED} 里**排除**最末档的写法不可靠
     *      （各家 ROM 的 cached 分档不一样），所以这里反过来写：明确把
     *      {@code IMPORTANCE_CACHED} 与更差的档判为「不在用」，其余一律算活。
     *   ② **兜底：播放/播放页**。只有当 importance 拿不到（反射失败、厂商裁了 API）时，
     *      才退回 2.3.0 那套宽松口径（播放页在前台 **或** 明示在播 **或** 播放状态未知），
     *      并且**把「暂停」也算活**（{@code playingState == 0} 不再判死）—— 报告明确说
     *      「暂停播放时也应该算在运行」。真正的「无差别」兜底仍然是 {@code true}，
     *      理由同 2.3.0：取不到状态时宁可显示「已激活」，也不要把正常状态误报成故障。
     */
    private static boolean isHostAlive() {
        // ① 进程 importance（首选）
        try {
            Context ctx = sHostCtx;
            if (ctx != null) {
                android.app.ActivityManager.RunningAppProcessInfo self =
                        new android.app.ActivityManager.RunningAppProcessInfo();
                android.app.ActivityManager.getMyMemoryState(self);
                int imp = self.importance;
                // IMPORTANCE_CACHED(900)、IMPORTANCE_EMPTY(1000) ⇒ 已被系统/用户视为可回收，
                // 视为「不在用」。其余档位（FOREGROUND / VISIBLE / SERVICE / PERCEPTIBLE …）
                // 都代表进程还活跃，正是「开在后台」应有的样子。
                if (imp > 0) {
                    boolean alive = imp
                            < android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
                    Log.i(TAG, "[host] alive by importance = " + alive + " (importance=" + imp
                            + ", process=" + self.processName + ")");
                    return alive;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[host] importance probe failed, fallback: " + t);
        }
        // ② 兜底（宽容侧）：拿不到 importance 时一律按「在用」处理。
        //    2.3.0 那套「播放页在前台 或 明示在播」已废弃 —— 它就是 2.1.1 误报与抖动的来源；
        //    而且它把「暂停」判死，与报告「暂停时软件开在后台应算运行」的口径直接冲突。
        try {
            io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository repo =
                    io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository.getInstance();
            Log.i(TAG, "[host] alive fallback (no importance): playerVisible="
                    + repo.isPlayerPageVisible() + " state=" + repo.getPlayingState());
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 【2.3.1】宿主进程的 Context（由 {@link #installResponder} 注入；仅用于查本进程 importance）。 */
    private static volatile Context sHostCtx;

    /**
     * 【2.3.0】让新配置**当场**落到屏幕上（用例 2.1 补充意见）。
     *
     * 悬浮窗：走一次 {@code FloatingWindowManager#sync} —— 它会调
     * {@code FloatingSubtitleView#updateFromRepository}，渲染缓存键里带配置代数，
     * 于是即使「没在播放、字幕没换行」也会整屏重画（含面板底色）。
     * 状态栏：让 SystemUI 侧把当前那一行按新样式重画一次。
     */
    private static void applyConfigImmediately(Context ctx, String tag) {
        try {
            if ("host".equals(tag)) {
                io.github.ariinyume.dlsitesoundfloat.window.FloatingWindowManager.getInstance()
                        .sync(ctx);
            } else if ("systemui".equals(tag)) {
                io.github.ariinyume.dlsitesoundfloat.hook.StatusBarSubtitleHook
                        .reapplyCurrentLineStyle();
            }
        } catch (Throwable t) {
            logWarn("[" + tag + "] applyConfigImmediately failed: " + t);
        }
    }

    /**
     * SystemUI 进程内收到重启请求：自杀即可 —— SystemUI 是 persistent 进程，
     * 死了系统会立刻把它拉起来（无需 root，也无需要求用户去系统设置重启）。
     */
    private static void restartSystemUi(String tag) {
        if (!"systemui".equals(tag)) {
            return;
        }
        try {
            logInfo("[systemui] restart requested -> killing own process (system will restart it)");
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            logWarn("restartSystemUi failed: " + t);
        }
    }

    /**
     * 配置变更后把「状态栏字幕**功能**」的持久化值同步进宿主进程并推给 SystemUI。
     *
     * 【2.3.0 §2.1.1.4】这里是两级开关最容易串味的地方，口径写死在这里：
     *   · 配置键 {@code statusbar_subtitle_enabled} 是 **L1 功能级总闸**（持久）；
     *   · {@code StatusBarSubtitleBridge.sAppEnabled} 是 **L2 会话级开关**（宿主会话，默认关）。
     * 旧实现直接把 L1 的值**写进 L2**（{@code sAppEnabled = enabled}）—— 于是保存一次配置，
     * 播放页那个「本次会话」开关就被配置顶成开，「重新打开功能后初始为关」根本不成立。
     * 现在：L1 只同步到 {@code sFeatureEnabled}（内部顺带把 L2 复位成关），
     * 推给 SystemUI 的是**生效值** {@code effectiveEnabled() = L1 && L2}。
     */
    private static void syncStatusbarEnabledFromConfig(Context ctx) {
        try {
            boolean enabled = RemoteConfig.get().statusbarSubtitleEnabled;
            io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge.setFeatureEnabled(enabled);
            io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge.sendEnabled(
                    ctx, io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge
                            .effectiveEnabled());
        } catch (Throwable t) {
            logWarn("syncStatusbarEnabledFromConfig failed: " + t);
        }
    }

    // ==================================================================
    // 设置页侧：发送
    // ==================================================================

    /** 保存成功后调用（PRD §9.2）：广播携带完整配置 JSON。 */
    public static void sendConfigChanged(Context ctx, String configJson) {
        if (ctx == null) {
            return;
        }
        // 【2.2.13 根修：保存后悬浮窗先变黑、要拖一下才恢复】的第一环。
        //
        // 旧实现发的是**隐式**广播。真机日志铁证（LSPosed_20261008_214215，21:42:06）：
        // 设置页保存后只有 SystemUI 进程打了 "config applied from broadcast"，
        // **宿主进程一个字都没有** —— Android 8.0 起的后台限制 + 厂商对后台进程的冻结，
        // 会让后台进程的动态接收器收不到隐式广播（与 sendHostScopePing 里 code 975
        // 踩过的是同一条限制，那条已用 setPackage 修过，这里补齐同一手法）。
        // 收不到 ⇒ 宿主里的悬浮窗还挂着旧配置；等进程解冻/返回前台广播才补投，
        // 期间样式错位 ⇒ 观感就是"保存后面板先变黑"。
        // 改成**按目标包各发一条显式广播**：有明确目标，系统直接投递（必要时唤醒进程），
        // 宿主与 SystemUI 各收各的，互不依赖"谁恰好在前台"。
        String json = configJson == null ? "" : configJson;
        String[] targets = {HOST_PKG, SYSTEMUI_PKG};
        for (String pkg : targets) {
            try {
                Intent i = new Intent(ACTION_CONFIG_CHANGED);
                i.setPackage(pkg);
                if (!json.isEmpty()) {
                    i.putExtra(EXTRA_CONFIG_JSON, json);
                }
                ctx.sendBroadcast(i);
            } catch (Throwable t) {
                logWarn("sendConfigChanged -> " + pkg + " failed: " + t);
            }
        }
    }

    /** 设置页「重启系统界面」按钮：广播给 SystemUI 进程内的模块。 */
    public static void sendRestartSystemUi(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.sendBroadcast(new Intent(ACTION_RESTART_SYSUI));
        } catch (Throwable t) {
            logWarn("sendRestartSystemUi failed: " + t);
        }
    }

    /** 状态卡探测：问 DLsiteSound 进程「你在不在」。 */
    public static void sendHostScopePing(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            // 【code 975】改成**显式广播**（带目标包名）。
            //
            // 为什么必须改 —— Ari 2026-10-07 报「DLsiteSound 一直开在后台，但插件经常检测
            // 不到程序在运行、持续显示模块未激活」。根因是 Android 8.0 起的后台执行限制：
            // **处于后台的进程，其动态注册的 BroadcastReceiver 收不到隐式广播**。
            // DLsiteSound 退到后台（没有前台服务）后，本模块注入在它进程里的接收器就再也
            // 收不到这条 `ACTION_HOST_PING` ⇒ 不回 PONG ⇒ 设置页把「在后台活着」误判成
            // 「未运行 / 未激活」。日志佐证：19:16:27 的 PONG 里 hostAlive=false，
            // 而 19:18:21~19:31:10 之间宿主连状态 Map 都不再被读（since=674458ms）。
            //
            // 显式广播（setPackage）**不受**该后台限制影响 —— 它有明确目标，系统会直接投递
            // （必要时唤醒目标进程）。这是本次修复里最关键的一条。
            Intent ping = new Intent(ACTION_HOST_PING);
            ping.setPackage(HOST_PKG);
            ctx.sendBroadcast(ping);
        } catch (Throwable t) {
            logWarn("sendHostScopePing failed: " + t);
        }
    }

    /**
     * 模块构建号。
     *
     * ⚠️ 反射读 {@code BuildConfig.VERSION_CODE}：本类会被**注入进程**加载，
     *    而 BuildConfig 是模块自己的类，注入进程里也能加载（同 APK），但为了在任何
     *    裁剪 / 混淆场景下都不炸，这里用反射 + 兜底 0。
     */
    public static int buildCode() {
        try {
            Class<?> bc = Class.forName("io.github.ariinyume.dlsitesoundfloat.BuildConfig");
            Object v = bc.getField("VERSION_CODE").get(null);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ==================================================================
    // 日志：本类同时跑在两个世界，必须分别对待
    //
    //  · 被注入进程（DLsiteSound / SystemUI）里 in-check 通过 → 走 XposedCompat.log，
    //    这些行才会进 LSPosed 的日志包（排查的唯一入口，见 XposedCompat 类头铁律）；
    //  · 设置页进程里 XposedCompat 从未 attach（模块不会注入自己）→ 退回 android.util.Log，
    //    否则会打出一堆误导性的「FALLBACK no XposedInterface yet」。
    // ==================================================================

    private static void logInfo(String msg) {
        if (XposedCompat.api() != null) {
            XposedCompat.log(TAG + " " + msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    /** 诊断级（仅调试开关开启时输出；设置页进程退回 Log.i 时同样受闸门约束）。 */
    private static void logDebug(String msg) {
        if (!LogGate.enabled()) {
            return;
        }
        logInfo(msg);
    }

    private static void logWarn(String msg) {
        if (XposedCompat.api() != null) {
            XposedCompat.log(TAG + " WARN " + msg);
        } else {
            Log.w(TAG, msg);
        }
    }
}
