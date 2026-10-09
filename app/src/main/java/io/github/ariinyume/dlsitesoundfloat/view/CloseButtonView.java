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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

/**
 * 右上角「关闭」按钮（v14 新增，替代原先的 TextView "✕"）。
 *
 * 【2.2.13 液态玻璃化 / 987 分档】Ari：悬浮窗、尺寸调整按钮与关闭按钮要"看起来是一个
 * 协调的整体"—— 「整体」的前提是三者材质一致，所以本类有两种语言、由液态玻璃开关选定。
 * **开启时**圆盘与面板同一套玻璃语言（渐变受光 + 1px 边光）：
 *   · **碟身**：竖直白色渐变（上缘受光更亮 → 下缘更透），即面板"顶部软受光"的微缩版；
 *   · **描边**：1dp 竖直渐变圆环，上亮下暗 —— 与面板的折射边光同方向同语言；
 *   · **✕**：保持镂空（{@code BlendMode.CLEAR} 从碟身里挖掉），
 *     露出面板自身的底色 —— 仍然是"真·透明"，不引入贴纸感的近似色。
 *
 * 尺寸与位置由 {@code FloatingSubtitleView} 决定：直径 **30dp**，
 * 距窗口上/右边缘各 **10dp**（显示圆盘 = 点击区域，同一个 View 尺寸）。
 * 本类只管画圆和挖 ✕，不处理尺寸/边距/点击（点击由外层 setOnClickListener 接管）。
 *
 * 注：✕ 的臂长按半径比例（{@link #X_HALF_RATIO}）绘制，圆盘放大时自动等比放大。
 *
 * 【987 两档绘制语言】Ari：「这俩控件的液态玻璃效果只有在液态玻璃模式开启时才生效，
 * 如果用户没开启液态玻璃模式，这俩控件还是恢复到原先的扁平角标效果。」
 * 2.2.13 把本类材质**无条件**换成了玻璃（面板却可能是扁平色块）—— 材质因此错配。
 * 现在由 {@link #setLiquidGlass(boolean)} 显式选档：
 *   · **开** → 玻璃碟（竖直受光渐变 + 1dp 上亮下暗圆环 + 镂空 ✕）；
 *   · **关** → v14~2.2.12 的扁平角标（灰/白平底 40%、无描边、镂空 ✕ 不变）。
 */
public class CloseButtonView extends View {
    /** 玻璃碟填充：上缘受光 / 下缘透光（白，与面板同语言的高光渐变）。 */
    private static final float DISC_TOP_ALPHA = 0.26f;
    private static final float DISC_BOTTOM_ALPHA = 0.10f;
    /**
     * 【2.3.1 §6.1.1】面板底色为黑白之外的彩色时整体抬一档 —— 白色在饱和色相上
     * 要更实才看得清（与旧版"转白"同一动机，只是材质换成了玻璃渐变）。
     */
    private static final float DISC_TOP_ALPHA_COLORED = 0.40f;
    private static final float DISC_BOTTOM_ALPHA_COLORED = 0.20f;
    /** 玻璃碟描边（1dp 圆环）：上亮 / 下暗的 alpha（×0xFF 白）。与面板边光同方向。 */
    private static final int RIM_TOP_ALPHA = 0x99;    // 60%
    private static final int RIM_BOTTOM_ALPHA = 0x2E; // 18%
    /** ✕ 臂半长 = 圆的半径 × 该比例。 */
    private static final float X_HALF_RATIO = 0.34f;
    /** ✕ 线宽（dp）。v15：1.6 → 2.0dp（匹配放大后的 40dp 圆盘）。 */
    private static final float X_STROKE_DP = 2.0f;

    // ── 【987】扁平角标档（液态玻璃关闭时用）──────────────────────────────
    /** 平底圆盘的不透明度（2.2.12 及更早版本的观感，与缩放手柄同值）。 */
    private static final float DISC_FLAT_ALPHA = 0.40f;
    /** 平底圆盘的灰度（与缩放手柄同色，视觉上成一对）。 */
    private static final int DISC_FLAT_GRAY = 0xB3B3B3;
    /** 彩色面板档的平底颜色（§6.1.1：白色在饱和色相上对比稳定；黑/白面板仍走中灰）。 */
    private static final int DISC_FLAT_WHITE = 0xFFFFFF;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean onColoredPanel = false;
    /** 【987】true = 走 2.2.13 的玻璃碟；false = 走 v14 的扁平角标（默认关，与旧行为一致）。 */
    private boolean liquidGlass = false;
    /** 着色器缓存：尺寸 / "彩色面板" / "液态玻璃开关"变了才重建。 */
    private boolean shadersDirty = true;

    public CloseButtonView(Context context) {
        super(context);
        discPaint.setStyle(Paint.Style.FILL);

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

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1.0f));
    }

    /**
     * 【2.3.1 §6.1.1】按面板底色切换玻璃碟的浓度档。
     *
     * @param onColoredPanel 面板基色是**黑白之外的彩色**时为 true ⇒ 玻璃碟整体提亮一档
     */
    public void setOnColoredPanel(boolean onColoredPanel) {
        if (this.onColoredPanel != onColoredPanel) {
            this.onColoredPanel = onColoredPanel;
            shadersDirty = true;
            invalidate();
        }
    }

    /**
     * 【987】选绘制语言：液态玻璃开 ⇒ 玻璃碟；关 ⇒ 扁平角标（v14 观感）。
     *
     * 由 {@code FloatingSubtitleView#applyControlTint()} 在「面板底重建 / 玻璃开关变化」
     * 两处统一下发，所以开关一保存，两个角标与面板是**同时**换材质的。
     */
    public void setLiquidGlass(boolean liquidGlass) {
        if (this.liquidGlass != liquidGlass) {
            this.liquidGlass = liquidGlass;
            shadersDirty = true;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shadersDirty = true;
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

        // ── 【987】扁平档：一枚灰/白平底圆盘 + 镂空 ✕（无渐变、无描边）──
        if (!liquidGlass) {
            discPaint.setShader(null);
            int flatRgb = onColoredPanel ? DISC_FLAT_WHITE : DISC_FLAT_GRAY;
            discPaint.setColor((Math.round(DISC_FLAT_ALPHA * 255f) << 24) | (flatRgb & 0x00FFFFFF));
            float halfFlat = r * X_HALF_RATIO;
            int flatLayer = canvas.saveLayer(0f, 0f, w, h, null);
            canvas.drawCircle(cx, cy, r, discPaint);
            canvas.drawLine(cx - halfFlat, cy - halfFlat, cx + halfFlat, cy + halfFlat, xPaint);
            canvas.drawLine(cx + halfFlat, cy - halfFlat, cx - halfFlat, cy + halfFlat, xPaint);
            canvas.restoreToCount(flatLayer);
            return;
        }

        if (shadersDirty) {
            buildShaders(w, h);
            shadersDirty = false;
        }
        float rimW = rimPaint.getStrokeWidth();
        float discR = r - rimW;

        // 在独立图层里先画玻璃碟，再用 CLEAR 把 ✕ 挖空；
        // 必须 saveLayer —— 否则 CLEAR 会把整块画布（连同面板背景）一起擦掉。
        int layer = canvas.saveLayer(0f, 0f, w, h, null);
        canvas.drawCircle(cx, cy, discR, discPaint);
        float half = discR * X_HALF_RATIO;
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, xPaint);
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, xPaint);
        canvas.restoreToCount(layer);

        // 描边在图层**外**画：CLEAR 只挖碟身，不伤边光；边光压住碟身外沿，玻璃更"有厚度"。
        canvas.drawCircle(cx, cy, r - rimW / 2f, rimPaint);
    }

    /** 按"当前尺寸 × 彩色面板档"重建两支着色器（碟身渐变 + 描边渐变）。 */
    private void buildShaders(int w, int h) {
        float topA = onColoredPanel ? DISC_TOP_ALPHA_COLORED : DISC_TOP_ALPHA;
        float botA = onColoredPanel ? DISC_BOTTOM_ALPHA_COLORED : DISC_BOTTOM_ALPHA;
        discPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                (Math.round(topA * 255f) << 24) | 0x00FFFFFF,
                (Math.round(botA * 255f) << 24) | 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        rimPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                (RIM_TOP_ALPHA << 24) | 0x00FFFFFF,
                (RIM_BOTTOM_ALPHA << 24) | 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
