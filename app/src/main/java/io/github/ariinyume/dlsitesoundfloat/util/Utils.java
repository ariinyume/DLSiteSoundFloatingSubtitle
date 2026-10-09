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
import android.util.TypedValue;

/**
 * dp → px 的**唯一口径**。
 *
 * <p>【code 995】本类此前是<b>死代码</b>（全仓零调用）—— 真正的 dp→px 分散在 4 个类各自的
 * 私有 helper 里（{@code StatusBarSubtitleHook} 21 处 / {@code SettingsActivity} 57 处 /
 * {@code FloatingSubtitleView} 20 处 / {@code FloatingWindowManager} 7 处），外加
 * {@code ActivityButtonHook} 那个<b>不能动</b>的胶囊专用壳。本轮把这些 helper 收敛到此处。
 *
 * <h3>⚠️ 两套口径，区别只在取整</h3>
 * <ul>
 *   <li>{@link #dip2px} —— {@code (int)(dp * density + 0.5f)}，即<b>四舍五入</b>。
 *       本项目绝大多数调用点（UI 内边距、控件尺寸、圆角）都是这一套。</li>
 *   <li>{@link #applyDimensionDip} —— 走 {@link TypedValue#applyDimension}，
 *       Android 官方实现是 {@code dp * density} <b>直接截断</b>，没有 {@code +0.5f}。</li>
 * </ul>
 * 两者最多差 <b>1px</b>（只在 dp*density 的小数部分 ≥ 0.5 时）。{@code FloatingWindowManager}
 * 的窗口尺寸/模糊半径原本用截断口径，{@code StatusBarSubtitleHook} 等用四舍五入口径。
 * 本轮<b>保留各自原口径</b>（只搬不改），避免在同一次重构里混入像素级行为变更 ——
 * 那正是 {@code MEM_rules.md} 铁律 1（写尺寸 ≠ 读尺寸）提醒的那类半像素抖动。
 *
 * <h3>🚫 严禁把 {@code ActivityButtonHook.dip2px} 也换成本类</h3>
 * 那是有意分叉：胶囊度量吃「首次锁死的密度」（{@code capsuleDip}，code 955 的 bugfix），
 * 不吃当前 density —— 否则用户切「显示大小」胶囊会跟着变，正是被修掉的 bug。
 */
public class Utils {

    /** 无 Context 兜底密度：与 SystemUI 侧 {@code Resources.getSystem()} 的常态一致。 */
    public static final float DEFAULT_DENSITY = 3f;

    /**
     * 四舍五入口径的 dp → px（{@code (int)(dp * density + 0.5f)}）。
     *
     * @param ctx 取密度的 Context；<b>不得为 null</b>，需要兜底请用 {@link #dip2pxOrDefault}
     */
    public static int dip2px(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * {@link #dip2px} 的 null 兜底变体：{@code ctx == null} 时按 {@link #DEFAULT_DENSITY} 算。
     *
     * <p>用于「Context 可能为 null 的早期/异常路径」，例如 {@code StatusBarSubtitleHook.dp()}
     * 在 {@code sRoot} 还没挂上时。
     */
    public static int dip2pxOrDefault(Context ctx, float dp) {
        if (ctx == null) {
            return (int) (dp * DEFAULT_DENSITY + 0.5f);
        }
        return dip2px(ctx, dp);
    }

    /**
     * 官方 {@link TypedValue#applyDimension} 口径（<b>截断</b>，无 {@code +0.5f}）。
     *
     * <p>与 {@link #dip2px} 最多差 1px。保留它是因为 {@code FloatingWindowManager} 的
     * 窗口尺寸 / 模糊半径原本就是这个口径，替换会引入像素级行为变更。
     */
    public static int applyDimensionDip(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}