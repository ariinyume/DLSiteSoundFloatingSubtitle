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
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import io.github.ariinyume.dlsitesoundfloat.config.SubtitleConfig;

/**
 * 【M1】色板行（PRD §FR-04 标注 3；参考稿紧凑化；v1.4 §3.1.2 / §3.1.5 补两件事）。
 *
 * 预设圆形色块单行排列，单选；**末尾的十六进制读数已按参考稿移除**（原来会在窄屏
 * 折成三行，是「界面太丑」的主要来源之一）；若当前颜色不在预设里则没有色块选中，
 * 由**同尺寸的圆形 🎨 图标按钮**（在 {@code SettingsActivity#addColorRow} 里拼接）负责。
 *
 * · 预设色值来自 {@code res/values/colors.xml}（那是**配置数据**，不是主题色）；
 * · **不以颜色作为唯一区分**（PRD §八 NFR-05）：每个色块都带色名的
 *   {@code contentDescription}，选中态还额外画一圈主色环。
 *
 * ── v1.4 §3.1.5：半透明预览（{@link #setTranslucentPreview(int, boolean)}）──
 *
 * 「悬浮窗颜色」这一行的色板要表现的是**面板顶部实际不透明度叠加后的效果**，
 * 而不是实心色。做法：
 *   ① 色块底下**只在该圆内**铺一层棋盘格底纹（像图像编辑器的透明背景）；
 *   ② 色块用「预设基色 + 指定的预览 alpha」画在棋盘上。
 * 于是「半透明」这件事同时在**两个**通道上被表达：棋盘格的透出 + 色块自身的透光。
 * 关闭该模式（{@code previewAlpha = 255}）时行为与本类改造前完全一致。
 *
 * ── 【2.3.1 §4.1.1 / §4.3 / §6.8】两处返工 ──
 *
 * ① **自定义色入口由「黑线画个圈」改成真正的彩虹色环**（{@link CustomWheelView}）：
 *    与预设色点**同尺寸、圆心共线**，一眼就能看出「这里是调色」。
 *    旧实现是行末一枚 outlined MaterialButton，那是一圈灰描边 + 一个小调色板图标，
 *    既比色点大一圈、又不直观 —— 用例 4.1.1 与 4.3 都在骂这件事。
 * ② **棋盘格只在色点圆内**，不再铺满整行（用例 6.8：底纹不要出现在卡片其他区域）。
 *    旧实现是 {@code dispatchDraw} 里铺整行，棋盘会从色点之间透出来，很脏。
 */
public class ColorSwatchRow extends LinearLayout {

    /** 色块点击热区边长（dp）—— 参考稿紧凑化（原 42dp 六块放不下单行）。 */
    private static final int SWATCH_TOUCH_DP = 34;
    /** 色块圆直径（dp）。 */
    private static final int SWATCH_DOT_DP = 22;
    /** 半透明预览模式下棋盘格的格子边长（dp）。 */
    private static final int CHECKER_CELL_DP = 4;

    public interface OnColorSelected {
        void onColorSelected(int color);
    }

    /** 自定义色环被点击。 */
    public interface OnCustomClicked {
        void onCustomClicked();
    }

    private final List<Swatch> swatches = new ArrayList<>();
    private int selectedColor;
    private OnColorSelected listener;
    /**
     * 半透明预览：色块绘制时统一套用的 alpha（255 = 不透明，即本类改造前的行为）。
     * ⚠️ 只影响**画出来的样子**，不影响回传给调用方的色值 —— 传进 {@code setPalette}
     * 的仍是完整 ARGB，配置键写的也仍是基色（见 {@code SubtitleConfig.floatWindowColor}）。
     */
    private int previewAlpha = 255;
    /** 是否画棋盘格底纹（半透明预览模式开启）。 */
    private boolean checkerEnabled;
    /** 行末的彩虹自定义色环；null = 本行不提供自定义入口。 */
    private CustomWheelView customWheel;

    public ColorSwatchRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
    }

    /**
     * 开启/关闭「半透明叠加预览」（v1.4 §3.1.5 悬浮窗颜色行专用）。
     *
     * @param previewAlpha 色块绘制时套用的 alpha（0–255）；255 = 关闭预览、按实心画
     * @param showChecker  是否铺棋盘格底纹（只有真正半透明时才需要）
     */
    public void setTranslucentPreview(int previewAlpha, boolean showChecker) {
        this.previewAlpha = SubtitleConfig.clampInt(previewAlpha, 0, 255);
        this.checkerEnabled = showChecker;
        for (Swatch s : swatches) {
            s.setPreviewAlpha(this.previewAlpha);
            s.setCheckerEnabled(this.checkerEnabled);
        }
        if (customWheel != null) {
            customWheel.setCheckerEnabled(this.checkerEnabled);
        }
        invalidate();
    }

    /**
     * 在色板行末追加一枚**彩虹自定义色环**（用例 4.1.1）。
     *
     * ⚠️ 必须与预设色点**同尺寸、圆心共线**：色点是在 34dp 的 View 里画 22dp 的圆，
     *    所以这里也用 34dp 的 View 画 22dp 的环 —— 否则视觉上会「大一圈」（用例 4.1.1
     *    明确点名了这个症状）。
     */
    public void setCustomEntry(OnCustomClicked cb) {
        if (customWheel == null) {
            customWheel = new CustomWheelView(getContext());
            addView(customWheel);
        }
        customWheel.setVisibility(VISIBLE);
        customWheel.setCheckerEnabled(checkerEnabled);
        customWheel.setOnClickListener(v -> {
            if (cb != null) {
                cb.onCustomClicked();
            }
        });
    }

    /** 是否把「当前选中色不在预设里」表现为色环上的选中环。 */
    public void setCustomSelected(boolean selected) {
        if (customWheel != null) {
            customWheel.setSelected(selected);
        }
    }

    /**
     * 设置色板内容。
     *
     * @param colors   预设色（ARGB）
     * @param names    与 {@code colors} 一一对应的色名（用于 contentDescription）
     * @param selected 当前选中色
     * @param cb       点选回调（null 表示只展示）
     */
    public void setPalette(int[] colors, String[] names, int selected, OnColorSelected cb) {
        this.listener = cb;
        this.selectedColor = selected;
        removeAllViews();
        swatches.clear();
        customWheel = null;

        if (colors != null) {
            for (int i = 0; i < colors.length; i++) {
                Swatch s = new Swatch(getContext(), colors[i]);
                LayoutParams lp = new LayoutParams(dp(SWATCH_TOUCH_DP), dp(SWATCH_TOUCH_DP));
                s.setLayoutParams(lp);
                s.setPreviewAlpha(previewAlpha);
                s.setCheckerEnabled(checkerEnabled);
                String name = (names != null && i < names.length) ? names[i] : "";
                s.setContentDescription(name + " " + SubtitleConfig.argbToHex(colors[i]));
                s.setFocusable(true);
                s.setClickable(true);
                final int c = colors[i];
                s.setOnClickListener(v -> {
                    selectedColor = c;
                    refreshSelection();
                    if (listener != null) {
                        listener.onColorSelected(c);
                    }
                });
                addView(s);
                swatches.add(s);
            }
        }

        refreshSelection();
    }

    /** 外部改了当前颜色（例如「恢复默认」/ 自定义取色）时同步色板选中态。 */
    public void setSelectedColor(int color) {
        this.selectedColor = color;
        refreshSelection();
    }

    private void refreshSelection() {
        for (Swatch s : swatches) {
            s.setSelectedColorEquals(s.color == selectedColor);
        }
        // 选中色不属于任何预设 ⇒ 选中环落在自定义色环上（表示「当前是自定义色」）
        if (customWheel != null && customWheel.getVisibility() == VISIBLE) {
            boolean inPalette = false;
            for (Swatch s : swatches) {
                if (s.color == selectedColor) {
                    inPalette = true;
                    break;
                }
            }
            customWheel.setSelected(!inPalette);
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 棋盘格画笔：只在给定区域内绘制（用例 6.8：底纹不得溢出到色点之外）。 */
    private void drawCheckerInside(Canvas canvas, RectF area, float diameter) {
        float cell = Math.max(1f, CHECKER_CELL_DP * getResources().getDisplayMetrics().density);
        int light = resolveAttrColor(com.google.android.material.R.attr.colorSurfaceVariant,
                Color.LTGRAY);
        int dark = resolveAttrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE);
        // 裁到圆内：底纹只在色点圆里出现（旧实现铺整行，会从色点之间漏出来）
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addCircle(area.centerX(), area.centerY(), diameter / 2f,
                android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        int cols = (int) Math.ceil(area.width() / cell) + 1;
        int rows = (int) Math.ceil(area.height() / cell) + 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                p.setColor(((r + c) & 1) == 0 ? light : dark);
                float l = area.left + c * cell;
                float t = area.top + r * cell;
                canvas.drawRect(l, t, l + cell, t + cell, p);
            }
        }
        canvas.restore();
    }

    private int resolveAttrColor(int attrRes, int fallback) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getContext().getTheme().resolveAttribute(attrRes, tv, true)) {
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                return getContext().getResources().getColor(tv.resourceId, getContext().getTheme());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 单个色块：实心圆（或半透明圆）+ 选中环。 */
    private class Swatch extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 描边：半透明预览时给色块描一圈轮廓，否则浅色圆在浅色棋盘上会「没有边界」。 */
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        final int color;
        private final float dotRadius;
        private final float ringWidth;
        /** 绘制用的 alpha（255 = 不透明）。只影响观感，不影响 {@link #color} 本身。 */
        private int previewAlpha = 255;
        /** 是否在圆内铺棋盘格（半透明预览）。 */
        private boolean checker;

        Swatch(Context c, int color) {
            super(c);
            this.color = color;
            float d = c.getResources().getDisplayMetrics().density;
            this.dotRadius = SWATCH_DOT_DP * d / 2f;
            this.ringWidth = Math.max(2f, 2f * d);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ringWidth);
            ring.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, 1f * d));
            border.setColor(0x33000000);
        }

        /** 设置绘制 alpha（0–255）。{@link #color} 的 RGB 保持不变。 */
        void setPreviewAlpha(int alpha) {
            int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
            if (previewAlpha != a) {
                previewAlpha = a;
                applyFillColor();
                invalidate();
            }
        }

        void setCheckerEnabled(boolean v) {
            if (checker != v) {
                checker = v;
                invalidate();
            }
        }

        /**
         * 把「基色 RGB + 预览 alpha」组合成实际填充色。
         *
         * ⚠️ 与基色自身的 alpha **相乘**：预设色都是 0xFF 不透明，所以常规色板下
         * 结果就是预览 alpha；但自定义取色可能带回一个半透明色，那时两者相乘才是
         * 「叠在棋盘上应该看到的样子」。
         */
        private void applyFillColor() {
            int srcA = (color >>> 24) & 0xFF;
            int outA = Math.round(previewAlpha * (srcA / 255f));
            fill.setColor((outA << 24) | (color & 0x00FFFFFF));
        }

        /**
         * 同步选中态。
         *
         * ⚠️ 复用框架的 {@code View#setSelected} 而不是自建布尔量：这样
         *    TalkBack 会朗读「已选中」，选中态也就不再只靠颜色表达（PRD §八 NFR-05）。
         */
        void setSelectedColorEquals(boolean v) {
            if (isSelected() != v) {
                setSelected(v);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(dotRadius, Math.min(cx, cy) - ringWidth);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            if (checker) {
                // 【2.3.1 §6.8】棋盘格只画在这一个色点的圆内
                drawCheckerInside(canvas, oval, r * 2f);
            }
            canvas.drawOval(oval, fill);
            if (previewAlpha < 255) {
                // 半透明色块：描一圈淡边，让它在棋盘格上有明确形状
                canvas.drawOval(oval, border);
            }
            if (isSelected()) {
                ring.setColor(resolveAccent());
                float rr = r + ringWidth;
                oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
                canvas.drawOval(oval, ring);
            }
        }

        private int resolveAccent() {
            return resolveAttrColor(com.google.android.material.R.attr.colorPrimary, Color.WHITE);
        }
    }

    /**
     * 【2.3.1 §4.1.1】彩虹自定义色环 —— 与预设色点**同尺寸、圆心共线**。
     *
     * 参照 Ari 给的参考图：外圈是七彩扫掠渐变（色相环），圆心一小块浅色圆点；
     * 选中时外面套一圈主题色描边环。这样「这是调色入口」一眼可辨，
     * 不再是过去那种「灰描边圆圈 + 调色板小图标」的老气样子。
     */
    private class CustomWheelView extends View {
        private final Paint wheel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final float dotRadius;
        private final float ringWidth;
        private boolean checker;

        CustomWheelView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            this.dotRadius = SWATCH_DOT_DP * d / 2f;
            this.ringWidth = Math.max(2f, 2f * d);
            setLayoutParams(new LayoutParams((int) (SWATCH_TOUCH_DP * d),
                    (int) (SWATCH_TOUCH_DP * d)));
            setFocusable(true);
            setClickable(true);
            // 色相环：SweepGradient 走一圈 12 段色相（首尾必须同色，否则接缝会有硬边）
            int[] hues = {
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
            wheel.setShader(new SweepGradient(0, 0, hues, null));
            wheel.setStyle(Paint.Style.FILL);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ringWidth);
            ring.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, 1f * d));
            border.setColor(0x33000000);
            hub.setStyle(Paint.Style.FILL);
            hub.setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE));
        }

        void setCheckerEnabled(boolean v) {
            if (checker != v) {
                checker = v;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // SweepGradient 的圆心在 shader 里是 (0,0)，必须挪到控件中心
            float cx = w / 2f;
            float cy = h / 2f;
            int[] hues = {
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
            wheel.setShader(new SweepGradient(cx, cy, hues, null));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(dotRadius, Math.min(cx, cy) - ringWidth);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            if (checker) {
                drawCheckerInside(canvas, oval, r * 2f);
            }
            canvas.drawOval(oval, wheel);
            // 圆心浅色小点：让色环有「环」的形状，而不是一整块彩色饼
            float hubR = r * 0.42f;
            oval.set(cx - hubR, cy - hubR, cx + hubR, cy + hubR);
            canvas.drawOval(oval, hub);
            canvas.drawOval(oval, border);
            if (isSelected()) {
                ring.setColor(resolveAttrColor(com.google.android.material.R.attr.colorPrimary,
                        Color.WHITE));
                float rr = r + ringWidth;
                oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
                canvas.drawOval(oval, ring);
            }
        }
    }
}
