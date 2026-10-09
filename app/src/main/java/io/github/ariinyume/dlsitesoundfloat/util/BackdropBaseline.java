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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * 【991】背景统计的**落盘留底** —— 让"液态玻璃第一帧就是对的"这件事跨进程存活。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么还需要它（{@code FloatingSubtitleView} 里已经有一份内存留底了）
 *
 * 采集的源是**宿主自己的窗口**（同一进程、零权限，见 {@link HostBackdrop} 类头）。
 * 于是有两个它够不到的场景：
 *   ① 用户在设置页里保存配置 —— 那一刻宿主在**后台**，采样被软跳过；
 *   ② 用户保存完直接**回桌面** —— 宿主连窗口都不可见，采样仍然拿不到东西。
 * （这不是"采样停了"：{@link HostBackdrop} 的 tick 照跑，只是每次软跳过。
 *  **不可能**去采桌面或别的应用，那需要截屏权限、且要跨进程取帧。）
 * 于是"下次开启时的打底值"只能来自**宿主上一次可见时**采到的那一份 ——
 * 进程内那份在进程被回收/重启后就没了，本类把它顺手写盘，覆盖"冷启动第一次开启"。
 *
 * ── 开销口径 ──────────────────────────────────────────────────────
 * 只在**数值真的变了**（|Δ亮度| ≥ {@link #MIN_DELTA}）且距上次写盘 ≥
 * {@link #MIN_WRITE_INTERVAL_MS} 时才写一次，用 {@code apply()}（不阻塞主线程）。
 * 正常使用下约每十几秒一次、每次三个键，可以忽略。
 *
 * ⚠️ 数值只是"打底用"：真实样本回来后由 {@code FloatingSubtitleView#smoothLum}
 *    正常接手修正，所以**过期并不可怕**，比"没有任何统计 ⇒ 不透明深色底"好得多。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class BackdropBaseline {

    private static final String TAG = "[DLsiteSoundFloat:Baseline]";

    /**
     * 落盘用的 SharedPreferences 名。
     *
     * ⚠️ 写在**被注入进程**（宿主）自己进程的私有目录里 —— 与
     * {@code RemoteConfig.LOCAL_PREFS_NAME} 同源做法（那份是配置的 hook 侧副本）。
     * 与宿主自己的数据互不干扰：文件名带模块前缀。
     */
    private static final String PREFS_NAME = "dlsitefloat_backdrop";

    private static final String K_LUM = "lum_q";       // 亮度 × 1000（整数，避免 float 精度问题）
    private static final String K_TOP = "edge_top";
    private static final String K_BOT = "edge_bottom";

    /** 写盘节流：距上次至少这么久才允许再写（ms）。 */
    private static final long MIN_WRITE_INTERVAL_MS = 10_000L;
    /** 写盘死区：亮度变化小于它就认为"没变"，不写。 */
    private static final float MIN_DELTA = 0.02f;

    /** 进程内节流状态。 */
    private static long sLastWriteMs = 0L;
    private static int sLastLumQ = Integer.MIN_VALUE;

    private BackdropBaseline() {
    }

    /**
     * 记一份留底（由 {@code FloatingSubtitleView} 在每次拿到真实统计时调用）。
     *
     * 全程 try/catch：落盘失败绝不能影响渲染主流程。
     */
    public static void save(Context ctx, float lum, int edgeTop, int edgeBottom) {
        if (ctx == null) {
            return;
        }
        try {
            final float v = Math.max(0f, Math.min(1f, lum));
            final int q = Math.round(v * 1000f);
            final long now = SystemClock.uptimeMillis();
            if (sLastLumQ != Integer.MIN_VALUE
                    && Math.abs(v - sLastLumQ / 1000f) < MIN_DELTA
                    && now - sLastWriteMs < MIN_WRITE_INTERVAL_MS) {
                return;
            }
            sLastLumQ = q;
            sLastWriteMs = now;
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(K_LUM, q)
                    .putInt(K_TOP, edgeTop)
                    .putInt(K_BOT, edgeBottom)
                    .apply();
        } catch (Throwable t) {
            XposedCompat.log(TAG + " save failed: " + t);
        }
    }

    /**
     * 读一份留底。
     *
     * @param lumOut   长度 ≥1 的 float 数组；命中时写入 0..1 的亮度
     * @param edgeOut  长度 ≥2 的 int 数组；命中时写入 上缘色 / 下缘色（0 = 未知）
     * @return true = 有可用的留底（至少亮度可用）
     */
    public static boolean load(Context ctx, float[] lumOut, int[] edgeOut) {
        if (ctx == null || lumOut == null || lumOut.length < 1 || edgeOut == null
                || edgeOut.length < 2) {
            return false;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!sp.contains(K_LUM)) {
                return false;
            }
            lumOut[0] = Math.max(0f, Math.min(1f, sp.getInt(K_LUM, 500) / 1000f));
            edgeOut[0] = sp.getInt(K_TOP, 0);
            edgeOut[1] = sp.getInt(K_BOT, 0);
            return true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " load failed: " + t);
            return false;
        }
    }
}
