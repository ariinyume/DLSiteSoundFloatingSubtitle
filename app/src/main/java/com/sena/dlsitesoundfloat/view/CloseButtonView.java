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
package com.sena.dlsitesoundfloat.view;

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
