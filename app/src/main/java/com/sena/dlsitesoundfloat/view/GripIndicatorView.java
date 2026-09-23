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
 * v14：
 *   1) 填充由「纯白 35%」（0x59FFFFFF）改为「灰 40%」（{@link #FILL_ALPHA} × {@link #FILL_GRAY}）——
 *      原先在深色玻璃上偏亮、观感太"实"，改成灰底 40% 后更虚、更贴参考图的克制感。
 *   2) {@link #INSET_DP} 2 → 3dp：配合根视图去掉内边距（见 {@link FloatingSubtitleView}），
 *      三角形现在紧贴窗口右下角，仅留 3dp 呼吸位。
 *
 * v15：{@link #INSET_DP} 3 → 10dp —— 与右上角 ✕ 按钮的 10dp 外边距对齐，
 *   两个装饰件到窗口边缘的距离一致，视觉上更像一套。
 *
 * v16：{@link #LEG_DP} 由 private 改 public —— 窗口层的缩放热区改为
 *   **以三角形本身为中心对齐**，其中心距窗口右下角 = {@link #INSET_DP} + {@link #LEG_DP}/2，
 *   需要把边长暴露出去（见 {@code FloatingSubtitleView#hitResizeArea}）。
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
    /** 填充不透明度（0–1）。0.40 = 40% 不透明。 */
    private static final float FILL_ALPHA = 0.40f;
    /** 填充灰度（"灰底"，而非纯白 —— 纯白在半透明玻璃上会显得发亮、太实）。 */
    private static final int FILL_GRAY = 0xB3B3B3;
    /** 最终填充色（灰 + 40% 不透明度）。 */
    private static final int FILL_COLOR = alphaColor(FILL_ALPHA, FILL_GRAY);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public GripIndicatorView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(FILL_COLOR);
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
        canvas.drawPath(path, paint);
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

    /** 把 (0–1 不透明度, RGB) 合成 ARGB 颜色。 */
    static int alphaColor(float alpha, int rgb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
