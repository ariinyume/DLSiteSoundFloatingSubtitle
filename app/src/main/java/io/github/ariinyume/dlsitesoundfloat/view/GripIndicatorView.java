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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

/**
 * 右下角「缩放手柄」指示器：一个**三个角全部倒圆**的直角三角形（v13 新增）。
 *
 * 原先用 "◢"（U+25E2）字符绘制，字形三个顶点都是尖角，与面板 20dp 的圆角风格割裂。
 * 本类改为自绘：直角在右下、两腰贴住右/下边，三个顶点统一按半径倒圆
 * （直线走到切点 → 以顶点为控制点做二次贝塞尔），圆润度与悬浮窗圆角观感统一。
 *
 * v14：填充改灰底 40%（克制感）；INSET_DP 2 → 3dp（贴角）。
 *
 * v15：{@link #INSET_DP} 3 → 10dp —— 与右上角 ✕ 按钮的 10dp 外边距对齐，
 *   两个装饰件到边缘的距离一致，视觉上更像一套。
 *
 * v16：{@link #LEG_DP} 由 private 改 public —— 窗口层的缩放热区改为
 *   **以三角形本身为中心对齐**，其中心距窗口右下角 = {@link #INSET_DP} + {@link #LEG_DP}/2，
 *   需要把边长暴露出去（见 {@code FloatingSubtitleView#hitResizeArea}）。
 *
 * 【2.2.13 液态玻璃化 / 987 分档】Ari：悬浮窗、尺寸调整按钮与关闭按钮要"协调的整体"。
 * 平涂灰三角换成与面板同语言的玻璃材质：斜向白色受光渐变（右下亮、左上透）
 * + 1dp"上亮下暗"描边（与面板折射边光同款）；彩色面板档整体抬一档
 * （§6.1.1 的延续，判据与 ✕ 按钮一致）。
 *
 * 【987】但这套材质**只在液态玻璃开启时才该出现** —— 2.2.13 是无条件换的，
 * 于是"面板是扁平色块、手柄却是玻璃"的错配一直挂着（Ari 2026-10-09 点名）。
 * 现在由 {@link #setLiquidGlass(boolean)} 选档：开 ⇒ 玻璃三角；
 * 关 ⇒ v14~2.2.12 的扁平角标（灰/白 40% 平涂、无描边）。
 *
 * 只做视觉提示，**不处理触摸** —— 缩放热区由
 * {@code FloatingSubtitleView.GRIP_HIT_DP} / {@code FloatingSubtitleView#hitResizeArea} 判定；
 * 本 View 不消费事件，因此触摸会照常传给窗口根视图，拖动手感不受影响。
 */
public class GripIndicatorView extends View {
    /** 三角形直角边长（dp）。public：缩放热区按三角形中心对齐，需要此值。 */
    public static final float LEG_DP = 18f;
    /** 三角形距窗口右下角的留白（dp）。v15：3 → 10dp（与 ✕ 按钮外边距对齐）。 */
    public static final float INSET_DP = 10f;
    /** 倒圆半径 = 直角边长 × 该比例（上限 0.45，避免相邻圆角互相吃掉）。 */
    private static final float CORNER_RATIO = 0.36f;
    /**
     * 【2.2.13 液态玻璃化】填充改为与面板同语言的"受光渐变"：
     * 上亮下透的白玻璃（斜向：左上暗 → 右下亮，顺应三角形贴角的方向感）。
     * 旧的"灰 40% 平涂"是 flat 贴纸语言，与面板/✕ 的玻璃材质不搭。
     */
    private static final float FILL_TOP_ALPHA = 0.12f;
    private static final float FILL_BOTTOM_ALPHA = 0.30f;
    /** 【2.3.1 §6.1.1】彩色面板档整体抬一档（白色在饱和色相上要更实才看得清）。 */
    private static final float FILL_TOP_ALPHA_COLORED = 0.20f;
    private static final float FILL_BOTTOM_ALPHA_COLORED = 0.42f;
    /** 玻璃描边：与面板边光同语言的"上亮下暗"竖直渐变（1dp）。 */
    private static final int RIM_TOP_ALPHA = 0x80;    // 50%
    private static final int RIM_BOTTOM_ALPHA = 0x26; // 15%
    // ── 【987】扁平角标档（液态玻璃关闭时用）──────────────────────────────
    /** 平涂不透明度（v14 的值：灰底 40%）。 */
    private static final float FLAT_ALPHA = 0.40f;
    /** 平涂灰度（v14 的"灰底"，非纯白 —— 纯白在半透明玻璃上会显得发亮、太实）。 */
    private static final int FLAT_GRAY = 0xB3B3B3;
    /** 彩色面板档的平涂颜色（§6.1.1，与 ✕ 按钮同一判据）。 */
    private static final int FLAT_WHITE = 0xFFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path rimPath = new Path();

    private boolean onColoredPanel = false;
    /** 【987】true = 走 2.2.13 的玻璃三角；false = 走 v14 的扁平角标（默认关，与旧行为一致）。 */
    private boolean liquidGlass = false;
    /** 着色器缓存：尺寸 / "彩色面板" / "液态玻璃开关"变了才重建。 */
    private boolean shadersDirty = true;

    public GripIndicatorView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1.0f));
    }

    /**
     * 【2.3.1 §6.1.1】按面板底色切换玻璃三角的浓度档（与 {@code CloseButtonView} 同一判据）。
     *
     * @param onColoredPanel 面板基色是**黑白之外的彩色**时为 true ⇒ 填充整体提亮一档
     */
    public void setOnColoredPanel(boolean onColoredPanel) {
        if (this.onColoredPanel != onColoredPanel) {
            this.onColoredPanel = onColoredPanel;
            shadersDirty = true;
            invalidate();
        }
    }

    /**
     * 【987】选绘制语言：液态玻璃开 ⇒ 玻璃三角；关 ⇒ 扁平角标（v14 观感）。
     *
     * 由 {@code FloatingSubtitleView#applyControlTint()} 在「面板底重建 / 玻璃开关变化」
     * 两处统一下发，所以开关一保存，手柄与面板是**同时**换材质的。
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
        float leg = dp(LEG_DP);
        float inset = dp(INSET_DP);
        float right = getWidth() - inset;
        float bottom = getHeight() - inset;
        float left = right - leg;
        float top = bottom - leg;
        if (leg <= 0f || left < 0f || top < 0f) {
            return;
        }
        // 三个顶点：A=右上、B=右下（直角）、C=左下
        float[] xs = {right, right, left};
        float[] ys = {top, bottom, bottom};
        float r = Math.min(leg * CORNER_RATIO, leg * 0.45f);
        buildRoundedPolygon(path, xs, ys, r);

        // ── 【987】扁平档：灰/白 40% 平涂，无描边（v14~2.2.12 的观感）──
        if (!liquidGlass) {
            paint.setShader(null);
            int flatRgb = onColoredPanel ? FLAT_WHITE : FLAT_GRAY;
            paint.setColor((Math.round(FLAT_ALPHA * 255f) << 24) | (flatRgb & 0x00FFFFFF));
            canvas.drawPath(path, paint);
            return;
        }

        if (shadersDirty) {
            float topA = onColoredPanel ? FILL_TOP_ALPHA_COLORED : FILL_TOP_ALPHA;
            float botA = onColoredPanel ? FILL_BOTTOM_ALPHA_COLORED : FILL_BOTTOM_ALPHA;
            paint.setShader(new android.graphics.LinearGradient(0f, top, 0f, bottom,
                    (Math.round(topA * 255f) << 24) | 0x00FFFFFF,
                    (Math.round(botA * 255f) << 24) | 0x00FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP));
            rimPaint.setShader(new android.graphics.LinearGradient(0f, top, 0f, bottom,
                    (RIM_TOP_ALPHA << 24) | 0x00FFFFFF,
                    (RIM_BOTTOM_ALPHA << 24) | 0x00FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP));
            shadersDirty = false;
        }
        canvas.drawPath(path, paint);
        // 描边往内收半线宽，与面板边光同款"细亮线贴边"的画法（见 LiquidGlassDrawable#drawRim）。
        buildRoundedPolygon(rimPath, xs, ys, r);
        canvas.drawPath(rimPath, rimPaint);
    }

    /**
     * 把凸多边形的每个顶点按半径 r 倒圆。
     * 对每个顶点 P（相邻顶点 prev / next）：
     *   入切点 tIn = P + normalize(prev - P) * r，出切点 tOut = P + normalize(next - P) * r，
     *   路径为 lineTo(tIn) → quadTo(P, tOut)（以顶点为控制点，曲线与两条边相切）。
     * 注意：斜边上的切点必须按**单位向量**推进（45° 斜边时 x/y 各偏移 r/√2 而非 r），
     * 否则斜边那个角会显得被拉长、三个角的圆润度不一致。
     */
    private static void buildRoundedPolygon(Path path, float[] xs, float[] ys, float r) {
        int n = xs.length;
        float[] tInX = new float[n];
        float[] tInY = new float[n];
        float[] tOutX = new float[n];
        float[] tOutY = new float[n];
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            float d1x = xs[prev] - xs[i];
            float d1y = ys[prev] - ys[i];
            float d2x = xs[next] - xs[i];
            float d2y = ys[next] - ys[i];
            float l1 = (float) Math.hypot(d1x, d1y);
            float l2 = (float) Math.hypot(d2x, d2y);
            if (l1 == 0f || l2 == 0f) {
                continue;
            }
            tInX[i] = xs[i] + d1x / l1 * r;
            tInY[i] = ys[i] + d1y / l1 * r;
            tOutX[i] = xs[i] + d2x / l2 * r;
            tOutY[i] = ys[i] + d2y / l2 * r;
        }
        path.reset();
        path.moveTo(tOutX[0], tOutY[0]);
        for (int k = 1; k <= n; k++) {
            int cur = k % n;
            path.lineTo(tInX[cur], tInY[cur]);
            path.quadTo(xs[cur], ys[cur], tOutX[cur], tOutY[cur]);
        }
        path.close();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
