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
package io.github.ariinyume.dlsitesoundfloat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 【2.3.1 §4.1.1 / §4.4 / §4.5】HSV 取色控件 —— 一个控件同时提供「色域」与「色相条」。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么重写：Ari 在 2.3.0 的测试结果里连点两次名 ——
 *   · 「颜色自定义出现**色板 + 编码输入**显示！！不要现在的滑杆样式！！！」（4.1.1）
 *   · 「不要这种滑杆样式！！！非常不直观！！！」（4.4）
 *   · 4.5 阴影颜色同一条
 * 旧的实现是三条 0–255 的 RGB 滑杆（红 R / 绿 G / 蓝 B），要凭数字猜颜色，确实不可用。
 * 参考稿（{@code Pasted image 20261004234038.png}）是一个标准的 HSV 取色器：
 *   左侧大块「饱和度 × 明度」二维色域 + 右侧一条竖直色相条 + 下方 HEX 输入。
 *
 * 本控件的分工：
 *   · 本类只负责**色域 + 色相条**这块「画出来 + 能拖」的部分；
 *   · HEX 输入框、取消/应用按钮由 {@code SettingsActivity#showCustomColorDialog} 组装
 *     （那样才能复用 Material 的输入框与对话框样式）。
 *
 * ⚠️ 与 {@code ColorSwatchRow} 一样，颜色一律**不硬编码语义色值**：
 *    这里出现的十六进制都是**色域本身的数学常量**（纯红/明度梯度等），不是主题色。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class HsvColorPicker extends View {

    /** 当前色相 0–360（度）。 */
    private float hue;
    /** 当前饱和度 0–1。 */
    private float sat;
    /** 当前明度 0–1。 */
    private float val;

    /** 色域矩形（左侧大块）。 */
    private final RectF areaRect = new RectF();
    /** 色相条矩形（右侧竖条）。 */
    private final RectF hueRect = new RectF();
    /** 色域与色相条之间的间隙（px，在 onSizeChanged 里算）。 */

    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hueBorder = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 拖动状态：0 = 没拖，1 = 拖色域，2 = 拖色相。 */
    private int dragging = 0;

    private OnColorChanged listener;

    public interface OnColorChanged {
        void onColorChanged(int argb);
    }

    public HsvColorPicker(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(Math.max(2f, 2f * d));
        markerPaint.setColor(Color.WHITE);
        areaBorder.setStyle(Paint.Style.STROKE);
        areaBorder.setStrokeWidth(Math.max(1f, 1f * d));
        areaBorder.setColor(0x33000000);
        hueBorder.setStyle(Paint.Style.STROKE);
        hueBorder.setStrokeWidth(Math.max(1f, 1f * d));
        hueBorder.setColor(0x33000000);
    }

    public void setOnColorChanged(OnColorChanged cb) {
        this.listener = cb;
    }

    /** 用 ARGB 初始化（保留 alpha 由调用方负责，本控件只处理 RGB）。 */
    public void setColor(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
    }

    /** 当前选中的 RGB（alpha 恒为 255，由调用方补 alpha）。 */
    public int currentColor() {
        return Color.HSVToColor(new float[]{hue, sat, val});
    }

    /** 当前 HEX（不含 alpha，形如 {@code #RRGGBB}）。 */
    public String currentHex() {
        return String.format(Locale.ROOT, "#%06X", currentColor() & 0x00FFFFFF);
    }

    /**
     * 用 HEX 文本更新（供 HEX 输入框回灌）。
     *
     * @return 解析成功与否（调用方据此决定是否提示格式错误）
     */
    public boolean setHex(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            // #RGB → #RRGGBB
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                sb.append(s.charAt(i)).append(s.charAt(i));
            }
            s = sb.toString();
        }
        if (s.length() != 6) {
            return false;
        }
        int rgb;
        try {
            rgb = Integer.parseInt(s, 16);
        } catch (Throwable t) {
            return false;
        }
        setColor(0xFF000000 | rgb);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float d = getResources().getDisplayMetrics().density;
        float gap = 14f * d;
        float hueW = 30f * d;
        areaRect.set(0, 0, Math.max(1f, w - hueW - gap), h);
        hueRect.set(areaRect.right + gap, 0, w, h);

        // 色域：水平 = 饱和度（白→纯色），垂直 = 明度（纯色→黑）
        areaPaint.setShader(new LinearGradient(areaRect.left, 0, areaRect.right, 0,
                Color.WHITE, Color.HSVToColor(new float[]{hue, 1f, 1f}), Shader.TileMode.CLAMP));
        Paint blackOverlay = new Paint();
        blackOverlay.setShader(new LinearGradient(0, areaRect.top, 0, areaRect.bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        areaPaint.setShader(new ComposeShader(areaPaint.getShader(), blackOverlay.getShader(),
                PorterDuff.Mode.SRC_OVER));

        // 色相条：竖直 7 段扫掠（SweepGradient 画不成竖条，用 LinearGradient 折返更方便）
        int[] hues = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        huePaint.setShader(new LinearGradient(0, hueRect.top, 0, hueRect.bottom, hues, null,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float r = 10f * d;

        // 色域
        canvas.drawRoundRect(areaRect, r, r, areaPaint);
        canvas.drawRoundRect(areaRect, r, r, areaBorder);
        // 色相条
        canvas.drawRoundRect(hueRect, r, r, huePaint);
        canvas.drawRoundRect(hueRect, r, r, hueBorder);

        // 色域里的定位圈
        float cx = areaRect.left + sat * areaRect.width();
        float cy = areaRect.top + (1f - val) * areaRect.height();
        canvas.drawCircle(cx, cy, 9f * d, markerPaint);
        markerPaint.setColor(0x66000000);
        markerPaint.setStrokeWidth(Math.max(1f, 1f * d));
        canvas.drawCircle(cx, cy, 10.5f * d, markerPaint);
        markerPaint.setColor(Color.WHITE);
        markerPaint.setStrokeWidth(Math.max(2f, 2f * d));

        // 色相条上的定位环
        float hy = hueRect.top + (hue / 360f) * hueRect.height();
        float hcy = Math.min(hueRect.bottom - 1f, Math.max(hueRect.top + 1f, hy));
        float hcx = hueRect.centerX();
        canvas.drawCircle(hcx, hcy, hueRect.width() / 2f - 1f * d, markerPaint);
        markerPaint.setColor(0x66000000);
        markerPaint.setStrokeWidth(Math.max(1f, 1f * d));
        canvas.drawCircle(hcx, hcy, hueRect.width() / 2f, markerPaint);
        markerPaint.setColor(Color.WHITE);
        markerPaint.setStrokeWidth(Math.max(2f, 2f * d));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = hitTest(e.getX(), e.getY());
                if (dragging == 0) {
                    return false;   // 点在两块的缝里：不接管
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                apply(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging != 0) {
                    apply(e.getX(), e.getY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != 0) {
                    apply(e.getX(), e.getY());
                    dragging = 0;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    // 拖动结束后补一次「定稿」回调（拖动中每次也回调了，这里再发一次无害）
                    performClick();
                    return true;
                }
                return false;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int hitTest(float x, float y) {
        if (areaRect.contains(x, y)) {
            return 1;
        }
        if (hueRect.contains(x, y)) {
            return 2;
        }
        // 容错：稍微超出一点也算命中（手指按住边角时常见）
        float slop = 8f * getResources().getDisplayMetrics().density;
        if (x >= areaRect.left - slop && x <= areaRect.right + slop
                && y >= -slop && y <= getHeight() + slop) {
            return 1;
        }
        if (x >= hueRect.left - slop && x <= hueRect.right + slop
                && y >= -slop && y <= getHeight() + slop) {
            return 2;
        }
        return 0;
    }

    private void apply(float x, float y) {
        if (dragging == 1) {
            sat = clamp01((x - areaRect.left) / Math.max(1f, areaRect.width()));
            val = 1f - clamp01((y - areaRect.top) / Math.max(1f, areaRect.height()));
            // 色域的底色随色相变，色相一动就要重画 shader
            areaPaint.setShader(new LinearGradient(areaRect.left, 0, areaRect.right, 0,
                    Color.WHITE, Color.HSVToColor(new float[]{hue, 1f, 1f}),
                    Shader.TileMode.CLAMP));
            Paint blackOverlay = new Paint();
            blackOverlay.setShader(new LinearGradient(0, areaRect.top, 0, areaRect.bottom,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            areaPaint.setShader(new ComposeShader(areaPaint.getShader(),
                    blackOverlay.getShader(), PorterDuff.Mode.SRC_OVER));
        } else if (dragging == 2) {
            hue = clamp01((y - hueRect.top) / Math.max(1f, hueRect.height())) * 360f;
            areaPaint.setShader(new LinearGradient(areaRect.left, 0, areaRect.right, 0,
                    Color.WHITE, Color.HSVToColor(new float[]{hue, 1f, 1f}),
                    Shader.TileMode.CLAMP));
            Paint blackOverlay = new Paint();
            blackOverlay.setShader(new LinearGradient(0, areaRect.top, 0, areaRect.bottom,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            areaPaint.setShader(new ComposeShader(areaPaint.getShader(),
                    blackOverlay.getShader(), PorterDuff.Mode.SRC_OVER));
        }
        invalidate();
        if (listener != null) {
            listener.onColorChanged(currentColor());
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 供 {@code SweepGradient} 备用（当前用 LinearGradient 折返实现色相条）。 */
    @SuppressWarnings("unused")
    private static Shader sweep(int cx, int cy) {
        return new SweepGradient(cx, cy, new int[]{
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null);
    }
}
