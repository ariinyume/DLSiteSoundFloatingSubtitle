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
package io.github.ariinyume.dlsitesoundfloat.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.github.ariinyume.dlsitesoundfloat.config.ConfigBus;

/**
 * 【M1】作用域授权探测（PRD §FR-01 规则 3/4：两个作用域**分立刻**判定）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 原理：LSPosed 的作用域是**按进程**授权的，用户勾了某个包，模块才会被注入那个进程。
 * 于是「模块进程发一条 PING，被注入的进程回一条 PONG」这件事本身，
 * 就是「该进程已授权且模块已在里面跑起来」的**可观测证据**。
 *
 * 两条通道各问各的，互不依赖（这正是「分立检测」的实现方式）：
 *   · SystemUI —— 复用既有通道 {@link StatusBarSubtitleBridge#ACTION_SCOPE_PING}
 *     （1.21.16 起真机验证过的握手，见该类 code 941 注释）；
 *   · DLsiteSound —— {@link ConfigBus#ACTION_HOST_PING}（本次为设置页新增）。
 *
 * ⚠️ 探测是**异步**的：发出后最多等 {@code windowMs}，收到的算已授权，没收到就算未授权
 *    （PRD §12 E-10 的「未知/未授权」降级态由调用方按是否有历史证据决定如何展示）。
 *    调用必须在**主线程**（内部用主线程 Handler 定时收口，且 receiver 也注册在主线程）。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class ScopeProbe {

    private static final String TAG = "[DLsiteSoundFloat:ScopeProbe]";

    /** 默认探测窗口：够跨进程广播一个来回，又不至于让状态卡长时间停在「检测中」。 */
    public static final long DEFAULT_WINDOW_MS = 1200L;

    /** 探测结果。 */
    public static final class Result {
        /** SystemUI 进程里有本模块在跑（= 用户勾选了 SystemUI 作用域）。 */
        public boolean systemUiAuthorized;
        /** DLsiteSound 进程里有本模块在跑（= 用户勾选了 DLsiteSound 作用域）。 */
        public boolean hostAuthorized;
        /**
         * 【2.3.0】宿主**当前真的在用**（播放页在前台 / 正在播放）—— 与
         * {@link #hostAuthorized} 是两件事：授权只说明模块在宿主进程里，
         * Alive 说明用户此刻正在用它。缺省 {@code true}（老版本模块不带这个 extra）。
         */
        public boolean hostAlive = true;
        /** 两端报告的构建号；-1 = 未收到。 */
        public int systemUiBuild = -1;
        public int hostBuild = -1;

        @Override
        public String toString() {
            return "ScopeProbe.Result{systemUI=" + systemUiAuthorized + "@" + systemUiBuild
                    + ", host=" + hostAuthorized + "@" + hostBuild
                    + (hostAuthorized ? ", hostAlive=" + hostAlive : "") + "}";
        }
    }

    public interface Callback {
        /** 探测收口后回调（保证只调一次，且在主线程）。 */
        void onResult(Result r);
    }

    private ScopeProbe() {
    }

    /** 用默认窗口探测一次。 */
    public static void probe(Context ctx, Callback cb) {
        probe(ctx, DEFAULT_WINDOW_MS, cb);
    }

    /**
     * 探测一次并回调。
     *
     * @param windowMs 等待应答的窗口（毫秒）
     */
    public static void probe(Context ctx, long windowMs, Callback cb) {
        if (ctx == null) {
            if (cb != null) {
                cb.onResult(new Result());
            }
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Result result = new Result();
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) {
                    return;
                }
                String action = i.getAction();
                if (ConfigBus.ACTION_HOST_PONG.equals(action)) {
                    // 【2.3.0 根修】必须**校验 tag** 才能认定宿主已授权：
                    // ConfigBus 的应答器在宿主与 SystemUI 两个进程里都挂，而 PONG 是同一个
                    // action —— 只按 action 判定会把 SystemUI 的应答当成「宿主已授权」
                    // （用例 1.2 / 1.4：宿主压根没勾 / 已划掉，状态卡仍显示「模块已激活」）。
                    if (!"host".equals(i.getStringExtra(ConfigBus.EXTRA_TAG))) {
                        Log.w(TAG, "host pong without host tag -> ignored (tag="
                                + i.getStringExtra(ConfigBus.EXTRA_TAG) + ")");
                        return;
                    }
                    result.hostAuthorized = true;
                    result.hostBuild = i.getIntExtra(ConfigBus.EXTRA_BUILD, -1);
                    result.hostAlive = i.getBooleanExtra(ConfigBus.EXTRA_HOST_ALIVE, true);
                } else if (StatusBarSubtitleBridge.ACTION_SCOPE_PONG.equals(action)) {
                    result.systemUiAuthorized = true;
                    result.systemUiBuild = i.getIntExtra(StatusBarSubtitleBridge.EXTRA_PONG_BUILD, -1);
                }
            }
        };

        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (finished[0]) {
                    return;
                }
                finished[0] = true;
                try {
                    appCtx.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
                int self = ConfigBus.buildCode();
                if (self != 0) {
                    if (result.systemUiAuthorized && result.systemUiBuild != 0
                            && result.systemUiBuild != self) {
                        Log.w(TAG, "SystemUI build " + result.systemUiBuild
                                + " != app build " + self + " -> 建议重启系统界面");
                    }
                    if (result.hostAuthorized && result.hostBuild != 0
                            && result.hostBuild != self) {
                        Log.w(TAG, "host build " + result.hostBuild
                                + " != app build " + self + " -> 建议重启 DLsiteSound");
                    }
                }
                Log.i(TAG, "probe done in " + windowMs + "ms: " + result);
                if (cb != null) {
                    try {
                        cb.onResult(result);
                    } catch (Throwable t) {
                        Log.w(TAG, "callback failed: " + t);
                    }
                }
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(ConfigBus.ACTION_HOST_PONG);
        f.addAction(StatusBarSubtitleBridge.ACTION_SCOPE_PONG);
        try {
            // Android 13+ 动态注册必须声明可见性标志：PONG 来自别的 UID（SystemUI / DLsiteSound）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed: " + t);
            finish.run();
            return;
        }

        ConfigBus.sendHostScopePing(appCtx);
        StatusBarSubtitleBridge.sendScopePing(appCtx);
        handler.postDelayed(finish, Math.max(200L, windowMs));
    }
}
