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
package io.github.ariinyume.dlsitesoundfloat.view;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

/**
 * 右上角「关闭」按钮（v14 新增，替代原先的 TextView "✕"）。
 *
 * 观感与右下角 {@link GripIndicatorView} 保持同一套语言：**灰底 + 40% 不透明度**，
 * 区别只是把三角换成 ✕，且 ✕ 是**镂空（真·透明）**的 —— 用 {@code BlendMode.CLEAR}
 * 从灰底圆里把 ✕ 挖掉，露出的就是面板自身的底色，因此不会有"白边白字"那种贴纸感。
 *
 * 尺寸与位置由 {@code FloatingSubtitleView} 决定：v15 起直径 **40dp**，
 * 距窗口上/右边缘各 **10dp**（显示圆盘 = 点击区域，同一个 View 尺寸）。
 * 本类只管画圆和挖 ✕，不处理尺寸/边距/点击（点击由外层 setOnClickListener 接管）。
 *
 * 注：✕ 的臂长按半径比例（{@link #X_HALF_RATIO}）绘制，因此圆盘放大到 40dp 时
 * ✕ 会自动等比放大，无需另调；只有线宽是绝对值，故 v15 由 1.6 → 2.0dp。
 */
public class CloseButtonView extends View {
    /** 圆底不透明度（与缩放手柄保持一致）。 */
    private static final float DISC_ALPHA = 0.40f;
    /** 圆底灰度（与缩放手柄同色，视觉上成一对）。 */
    private static final int DISC_GRAY = 0xB3B3B3;
    /**
     * 【2.3.1 §6.1.1】面板底色为**除黑白之外的其他颜色**时，控件圆底改为白色。
     *
     * ── 为什么需要单独一档 ──────────────────────────────────────────────
     * 现状是恒用中灰 {@link #DISC_GRAY}（0xB3B3B3）。中灰在**黑/白面板**上对比度够，
     * 但在彩色面板（例如红/绿/紫）上会「吃色」—— 灰圆盘和面板底色亮度接近，看不清。
     * Ari 的实测截图（Screenshot_2026-10-05-16-21-13-66）就是彩色面板下控件几乎隐形。
     * 改白的理由是：白色在所有饱和色相上都有稳定对比，且**不透明度沿用原值**
     * （§6.1.1 原话「不透明度与现有的控件不透明度设定一致」）⇒ 视觉重量不变、
     * 只是色相从灰换成白。
     * ⚠️ 黑白面板**不套用**这一档：白面板上一枚白色控件 = 隐形，所以仍走中灰。
     */
    private static final int DISC_WHITE = 0xFFFFFF;
    /** ✕ 臂半长 = 圆的半径 × 该比例。 */
    private static final float X_HALF_RATIO = 0.34f;
    /** ✕ 线宽（dp）。v15：1.6 → 2.0dp（匹配放大后的 40dp 圆盘）。 */
    private static final float X_STROKE_DP = 2.0f;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CloseButtonView(Context context) {
        super(context);
        discPaint.setStyle(Paint.Style.FILL);
        discPaint.setColor(GripIndicatorView.alphaColor(DISC_ALPHA, DISC_GRAY));

        xPaint.setStyle(Paint.Style.STROKE);
        xPaint.setStrokeCap(Paint.Cap.ROUND);
        xPaint.setStrokeWidth(dp(X_STROKE_DP));
        // 颜色本身无意义 —— 下面以 CLEAR 混合模式把它从圆底里"挖掉"。
        xPaint.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            xPaint.setBlendMode(BlendMode.CLEAR);
        } else {
            //noinspection deprecation
            xPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }
    }

    /**
     * 【2.3.1 §6.1.1】按面板底色切换控件圆底的「灰 / 白」。
     *
     * @param panelColor 当前悬浮窗面板基色（ARGB）；面板是**黑白之外的彩色**时传 true 让它转白
     *
     * 判据（在调用侧算好传进来）：只看「是否黑白」——极度接近纯黑或纯白的（含默认的
     * 深蓝黑 {@code #0E1420}）都算「黑白档」，保持中灰；其余一律白。
     * ⚠️ alpha 沿用 {@link #DISC_ALPHA}，不做改动（§6.1.1 要求不透明度与现有控件一致）。
     */
    public void setOnColoredPanel(boolean onColoredPanel) {
        int base = onColoredPanel ? DISC_WHITE : DISC_GRAY;
        discPaint.setColor(GripIndicatorView.alphaColor(DISC_ALPHA, base));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        // 在独立图层里先画灰底圆，再用 CLEAR 把 ✕ 挖空；
        // 必须 saveLayer —— 否则 CLEAR 会把整块画布（连同面板背景）一起擦掉。
        int layer = canvas.saveLayer(0f, 0f, w, h, null);
        canvas.drawCircle(cx, cy, r, discPaint);
        float half = r * X_HALF_RATIO;
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, xPaint);
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, xPaint);
        canvas.restoreToCount(layer);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
