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
package com.sena.dlsitesoundfloat.util;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;

import com.sena.dlsitesoundfloat.data.SubtitleCue;
import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import java.util.List;

/**
 * 跨进程桥：DLsiteSound 进程 ↔ SystemUI 进程。
 *
 * 字幕文本、行时长、播放态与「状态栏字幕开关」通过广播传递（SystemUI 侧动态注册接收器，
 * 跨 UID 免权限）。两条广播：
 *  - {@link #ACTION_LINE}    当前字幕行（空串 = 清除 / 隐藏）+ 行时长 + 播放态 + 开关态
 *  - {@link #ACTION_ENABLED} 开关状态（由悬浮窗按钮长按 1s 翻转）
 *
 * 设计要点：
 *  - 开关状态 {@link #sAppEnabled} 唯一真源在 App 进程；SystemUI 进程只通过广播同步本地副本。
 *  - App 启动默认关闭，只能通过长按触发（见 {@code ActivityButtonHook}）。
 *  - **【1.21.15 问题 1】开关 = 纯粹的「用户意图」，不再被任何数据推断改写。**
 *    曾经有一条「判定本音轨无字幕就把开关强制关掉」的路径（1.21.11 引入），结果是
 *    切轨时用户长按开的开关被系统自己关掉，而且**没有自动恢复路径** ——
 *    悬浮窗有 {@code autoClosedForNoSubtitle} 会在字幕晚到时自动重开，开关没有，
 *    只能靠用户再长按一次。而「无字幕时状态栏不该挂字幕」其实由**空行广播**已经实现
 *    （{@link #sendCurrentFromRepo} 在无字幕时算出 line 为空串，SystemUI 侧 hideSubtitleNow），
 *    根本不需要动开关。现在：开关只由长按翻转；数据侧只决定「当下有没有内容可显示」。
 *  - 广播从 {@code SubtitleRepository} 的中央观察者发出，与悬浮窗开关/视图生命周期解耦——
 *    即使悬浮窗关闭，只要 DLsite Sound 在播，状态栏字幕照常刷新。
 *  - **暂停即隐藏**：检测到暂停时这里把 line 置空（状态栏据此隐藏字幕并还原时钟/通知图标）；
 *    恢复播放后 line 自动回来，只要开关还开着就继续显示。
 */
public final class StatusBarSubtitleBridge {
    public static final String ACTION_LINE =
            "com.sena.dlsitesoundfloat.action.STATUSBAR_SUBTITLE_LINE";
    public static final String ACTION_ENABLED =
            "com.sena.dlsitesoundfloat.action.STATUSBAR_SUBTITLE_ENABLED";
    /**
     * 【code 924】反向通道：**SystemUI -> App** 的「请求关闭状态栏字幕」。
     *
     * 需求：状态栏字幕开启时，双击状态栏字幕位置可以关闭。
     *
     * 为什么必须是反向广播（而不是 SystemUI 侧直接改开关）：
     *   开关真源 {@link #sAppEnabled} 是**进程内静态字段**。SystemUI 与宿主 App
     *   各持一份副本；在 SystemUI 里改只影响它自己，App 进程下一次
     *   {@code sendLine}/{@code sendEnabled} 会把旧状态整包推回来 -> 出现
     *   「关了又自己开」。所以 SystemUI 只发请求，真正翻开关留在 App 进程，
     *   与胶囊按钮点击共用同一条执行路径。
     */
    public static final String ACTION_DISMISS_REQUEST =
            "com.sena.dlsitesoundfloat.action.STATUSBAR_SUBTITLE_DISMISS_REQUEST";
    /**
     * 【code 941】作用域探测：**App -> SystemUI** 的探测广播。
     *
     * 为什么需要它：LSPosed 的作用域是**按进程**授权的，用户勾了
     * {@code com.android.systemui}，模块才会被注入 SystemUI 进程；没勾，跨进程链路
     * （{@link #ACTION_LINE}）就**没有任何接收方**，广播发出去只会静默消失。
     * App 进程读不到 LSPosed 的配置（需要 root），所以这里用「握手」把
     * 「SystemUI 里到底有没有本模块在跑」变成可观测量：
     * 发一条 PING，被注入的 SystemUI 会回 {@link #ACTION_SCOPE_PONG}；没被注入则永远收不到。
     */
    public static final String ACTION_SCOPE_PING =
            "com.sena.dlsitesoundfloat.action.STATUSBAR_SCOPE_PING";
    /**
     * 【code 941】作用域探测应答：**SystemUI -> App**。
     *
     * ⚠️ 本 action 是隐式广播且未做发送方校验（动态注册的 receiver 拿不到发送方 UID）。
     *    理论上任何应用都能伪造 PONG，但伪造的后果只是「状态栏按钮多显示一个」，
     *    无实质风险，不值得为此引入签名校验的复杂度。
     */
    public static final String ACTION_SCOPE_PONG =
            "com.sena.dlsitesoundfloat.action.STATUSBAR_SCOPE_PONG";
    /** 关闭原因（仅用于日志/取证）。 */
    public static final String EXTRA_DISMISS_REASON = "reason";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_ENABLED = "enabled";
    /** 当前字幕行的播放时长（毫秒）。0 = 未知，状态栏侧退回默认时长。 */
    public static final String EXTRA_DURATION_MS = "duration_ms";
    /** 当前是否在播放。false 时 line 一定是空串。 */
    public static final String EXTRA_PLAYING = "playing";
    /** 【code 941】PONG 携带的 SystemUI 侧构建号（见 {@link #ACTION_SCOPE_PONG}）。 */
    public static final String EXTRA_PONG_BUILD = "pong_build";

    /** App 进程侧的开关状态（唯一真源）。默认关闭；只由长按 1s 翻转。 */
    public static boolean sAppEnabled = false;

    /**
     * 【1.21.13 问题 2】开关状态变化的监听（**只在 App 进程注册**）。
     *
     * 为什么必须有它：按钮底色（绿色 = 状态栏字幕开）直接读 {@link #sAppEnabled}。
     * 1.21.13 时这个开关有**两条**改动路径（长按翻转 + 「无字幕」强制作废），
     * 而强制作废那条是从仓库观察者里调的、调用方不知道按钮存在 → 绿色底色停在那儿，
     * 直到下次文字变化才被顺带刷新（实测：16:09:27.667 打出 `force-off (no subtitles)`，
     * 13 秒后的截图里按钮仍绿着）。
     *
     * 【1.21.15 问题 1】强制作废已删除，现在只剩长按一条路径，而长按的调用方
     * （{@code ActivityButtonHook}）本来就会自己重画按钮 —— 所以本监听当前是**冗余保险**。
     * 保留它是为了防止将来又给开关加第二条写入路径时，重蹈「改了真源、UI 不刷新」的覆辙。
     */
    private static volatile Runnable sEnabledListener;

    // ==================================================================
    // 【code 941】SystemUI 作用域授权探测 —— 状态与判据
    //
    // 真源在这里（App 进程）：SystemUI 只会**应答**，不持有授权态。
    // ==================================================================

    /**
     * 【code 941】最近一次收到 PONG 的时刻（{@code SystemClock.uptimeMillis()}，0 = 从未收到）。
     *
     * 语义：PONG 只可能由**被注入到 SystemUI 进程的模块**发出 ⇒「收到过且还新鲜」
     * 等价于「用户已勾选 SystemUI 作用域，且 SystemUI 里跑着本模块的代码」。
     */
    private static volatile long sScopePongAtMs = 0L;
    /** 【code 941】最近一次 PONG 报告的 SystemUI 侧构建号（-1 = 未知）。 */
    private static volatile int sScopePongBuild = -1;

    /**
     * 【code 941】PONG 的**新鲜期**：超过这么久没收到应答，就判定「未授权 / SystemUI 不可达」。
     *
     * 为什么是「新鲜期」而不是「收到过一次就永远为真」：用户可以在 LSPosed 里**取消**勾选，
     * 也可以在重启 SystemUI 后让它变成一个不含本模块的进程 —— 那时必须回到未授权。
     * 心跳间隔 3s（App 侧 SCOPE_PING_INTERVAL_MS），这里留 8s ≈ 2.5 个周期，
     * 既容忍偶发丢包与主线程忙，也能在 SystemUI 重启（约 1~2s）时不误判。
     */
    public static final long SCOPE_FRESH_MS = 8000L;

    /** 【code 941】发一条作用域探测（App 侧心跳调用）。 */
    public static void sendScopePing(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.sendBroadcast(new Intent(ACTION_SCOPE_PING));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【code 941】记录一次 PONG（App 侧接收器调用）。
     *
     * @return true = 这一次**从「未授权」翻转为「已授权」** —— 调用方据此刷新 UI
     *         （状态栏钮的可见性直接受它影响，不刷新会晚最多 3s 才出现）。
     */
    public static boolean onScopePong(int build) {
        boolean was = isSystemUiScopeAuthorized();
        sScopePongAtMs = SystemClock.uptimeMillis();
        sScopePongBuild = build;
        return !was;
    }

    /**
     * 【code 941】SystemUI 作用域**此刻**是否确认可用 —— 状态栏钮显隐的唯一判据。
     *
     * 见 {@link #ACTION_SCOPE_PING} 的说明：这是「握手 + 新鲜期」得到的结论，
     * 不是「用户配置里勾没勾」的字面读取。
     */
    public static boolean isSystemUiScopeAuthorized() {
        long t = sScopePongAtMs;
        return t > 0L && SystemClock.uptimeMillis() - t <= SCOPE_FRESH_MS;
    }

    /** 【code 941】最近一次 PONG 报告的 SystemUI 侧构建号；-1 = 未知。 */
    public static int getScopePongBuild() {
        return sScopePongBuild;
    }

    /** 注册开关状态变化监听（App 进程的 ActivityButtonHook 调用一次）。 */
    public static void setEnabledListener(Runnable r) {
        sEnabledListener = r;
    }

    /** 开关状态一变就回调一次，让所有以它为输入的 UI 立刻重算。 */
    private static void notifyEnabledChanged() {
        Runnable r = sEnabledListener;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 去重：避免逐帧/逐次更新都发广播。 */
    private static String sLastSentLine = null;
    private static long sLastSentDurationMs = -1L;
    private static boolean sLastSentPlaying = true;

    private StatusBarSubtitleBridge() {
    }

    /** 长按触发：翻转开关并向 SystemUI 广播新状态。返回翻转后的状态。 */
    public static boolean toggleAppEnabled(Context ctx) {
        sAppEnabled = !sAppEnabled;
        sendEnabled(ctx, sAppEnabled);
        notifyEnabledChanged();
        return sAppEnabled;
    }

    /**
     * ── v56（1.21.15 问题 1）：本方法已**删除**，别再把它加回来 ──
     *
     * 原实现（1.21.11 问题 3 引入）：仓库判定「本音轨无字幕」时把 {@link #sAppEnabled}
     * 强制翻成 false，并顺手通知按钮重画。
     *
     * 删它的理由（全部有实测日志支撑）：
     *  1) **前提不成立**。「本音轨无字幕」这条裁决本身假阴性频发 —— 字幕 JSON 到达延迟
     *     实测 0.2s~15.6s，而裁决窗只等 3s。于是「其实有字幕」的音轨被判无字幕，开关跟着
     *     被误关。四次 force-off（17:22:05 / 17:35:05 / 18:00:34 / 18:20:28）全部发生在
     *     假阴性裁决之后；其中 18:00:33.391 用户刚长按打开，**961ms 后**就被自己关掉了。
     *  2) **不可逆**。悬浮窗那条「自动关」有 {@code autoClosedForNoSubtitle} 兜底、
     *     字幕晚到会自动重开；开关这条没有任何恢复路径，用户只能重新长按。
     *     同一个事实（本音轨无字幕）被两个 UI 读到，却只有一边能自愈 —— 口径不对称。
     *  3) **多余**。「无字幕时状态栏不挂字幕」由 {@link #sendCurrentFromRepo} 算出空行
     *     已经实现（SystemUI 侧空行 → hideSubtitleNow，连通知徽标一起 GONE）；
     *     「按钮底色回归普通色」由 {@code ActivityButtonHook.statusBarButtonOn()}
     *     的 {@code !noSub} 已经实现。动开关换不来任何额外效果。
     * 【v57】底色判据已随三态模式一起删除，改为上面两个
     *     per-button 判据（statusBarButtonOn / floatingButtonOn）。
     */

    /** 【1.21.11 问题 3】「无字幕」状态下不允许开启状态栏字幕。
     *  【v57】触发形式已由「长按」改为「点击」，判据本身不变。 */
    // 【1.21.16 问题 1】口径统一到 shouldShowNoSubtitles()（含软裁决 / 换轨待确认）。
    public static boolean canToggle(SubtitleRepository repo) {
        return repo != null && !repo.shouldShowNoSubtitles();
    }

    /** 广播开关状态（App 启动 / 翻转时调用）。 */
    public static void sendEnabled(Context ctx, boolean enabled) {
        if (ctx == null) {
            return;
        }
        Intent i = new Intent(ACTION_ENABLED);
        i.putExtra(EXTRA_ENABLED, enabled);
        ctx.sendBroadcast(i);
    }

    /** 广播当前字幕行 + 行时长 + 播放态 + 开关态。 */
    public static void sendLine(Context ctx, String line, long durationMs, boolean playing) {
        if (ctx == null) {
            return;
        }
        Intent i = new Intent(ACTION_LINE);
        i.putExtra(EXTRA_LINE, line == null ? "" : line);
        i.putExtra(EXTRA_ENABLED, sAppEnabled);
        i.putExtra(EXTRA_DURATION_MS, durationMs);
        i.putExtra(EXTRA_PLAYING, playing);
        ctx.sendBroadcast(i);
    }

    /**
     * 强制重推当前字幕行 + 开关态（绕过去重）。长按翻转开关后立即调用，确保状态栏即时刷新；
     * 也用于 SystemUI 进程重启后由 App 侧重新同步一次最新状态。
     */
    public static void resendCurrentFromRepo(Context ctx, SubtitleRepository repo) {
        if (ctx == null || repo == null) {
            return;
        }
        boolean playing = isPlaying(repo);
        String line = playing ? computeCurrentLine(repo) : "";
        long dur = playing ? computeCurrentDurationMs(repo) : 0L;
        sLastSentLine = line;
        sLastSentDurationMs = dur;
        sLastSentPlaying = playing;
        sendLine(ctx, line, dur, playing);
    }

    /**
     * 从仓库算出当前字幕行并广播；与上次相同则跳过（去重）。
     * 统一从仓库计算，避免依赖悬浮窗视图是否存活——满足「无论悬浮窗开关状态都常驻」。
     */
    public static void sendCurrentFromRepo(Context ctx, SubtitleRepository repo) {
        if (ctx == null || repo == null) {
            return;
        }
        boolean playing = isPlaying(repo);
        String line = playing ? computeCurrentLine(repo) : "";
        long dur = playing ? computeCurrentDurationMs(repo) : 0L;
        if (line.equals(sLastSentLine)
                && dur == sLastSentDurationMs
                && playing == sLastSentPlaying) {
            return;
        }
        boolean playingChanged = playing != sLastSentPlaying;
        sLastSentLine = line;
        sLastSentDurationMs = dur;
        sLastSentPlaying = playing;
        sendLine(ctx, line, dur, playing);
        if (playingChanged) {
            android.util.Log.i("DLsiteSoundFloat",
                    "status bar subtitle -> " + (playing ? "playing" : "paused(clear)")
                            + ", line=" + (line.isEmpty() ? "<empty>" : line)
                            + ", dur=" + dur + "ms");
        }
    }

    /** 仓库侧是「三态」的：hook 全挂时返回 true（宁可多显示，也不要把字幕弄没）。 */
    private static boolean isPlaying(SubtitleRepository repo) {
        try {
            return repo.isPlayingOrUnknown();
        } catch (Throwable t) {
            return true;
        }
    }

    private static String computeCurrentLine(SubtitleRepository repo) {
        List<SubtitleCue> cues = repo.getCues();
        int idx = repo.findCurrentCueIndex();
        if (cues != null && !cues.isEmpty() && idx >= 0 && idx < cues.size()) {
            return join(cues.get(idx));
        }
        List<String> cur = repo.getCurrentSubtitles();
        if (cur != null && !cur.isEmpty()) {
            return TextUtils.join(" ", cur).toString();
        }
        return "";
    }

    /** 当前字幕行的播放时长（毫秒）；0 = 未知。状态栏据此决定单程滚动的用时。 */
    private static long computeCurrentDurationMs(SubtitleRepository repo) {
        try {
            return repo.getCurrentCueDurationMs();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 把一条 cue 的多条字幕用空格合并成单行（与 FloatingSubtitleView.joinSubtitles 等价）。 */
    private static String join(SubtitleCue cue) {
        if (cue == null || cue.subtitles == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : cue.subtitles) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
