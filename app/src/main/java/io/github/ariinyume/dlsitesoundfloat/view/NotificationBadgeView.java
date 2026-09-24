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
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

/**
 * 状态栏左侧的「通知数徽标」—— 圆底 + **镂空数字**。
 *
 * 需求（Ari，2026-09-23）：「状态栏字幕的通知图标里面数字改为镂空数字显示，
 * 数字区域的颜色需要是状态栏背景的颜色。」
 *
 * 做法：**一条 {@link Path} + {@code FillType.EVEN_ODD}** —— 把「圆」和「字形轮廓」
 * 作为同一条 path 的两类子路径，奇偶填充规则下，字形盖住的地方被两条子路径覆盖
 * ⇒ 那里**不填色**，露出状态栏自己画在那儿的底 = 就是「状态栏背景的颜色」。
 * 字形的内孔（如 0 / 8 的中间）会被盖成奇数次 ⇒ 反而填上圆底色，正是镂空数字该有的样子。
 *
 * ⚠️ 【code 947 教训·必读】**{@code reset()} 保留 FillType，{@code rewind()} 会把它清掉。**
 * Android 源码里 {@code reset()} 专门做了 {@code getFillType()} → {@code native_reset()} →
 * {@code setFillType()} 的存取保护（javadoc 写明 This does NOT change the fill-type setting），
 * 而 {@code rewind()} 没有这层保护，native 侧直接回到 WINDING。
 * 946 在构造函数设了 EVEN_ODD，却每帧 {@code rewind()} ⇒ FillType 被清成 WINDING ⇒
 * 圆（CW）与字形轮廓同向 ⇒ WINDING 取**并集** ⇒ 实心圆、数字的洞消失
 * （真机截图逐像素证实：圆盘 33×33 内部内切 23×23 区域 **0 个暗像素**）。
 * 现在改用 {@code reset()}，并且**每帧显式再 setFillType 一次**兜底；
 * 自证日志追加 {@code fill=} 与 {@code glyph=}，一眼可验 FillType 是否活着、字形是否取到。
 *
 * ⚠️ 【code 946 教训·必读】945 用的是 {@code saveLayer} + {@code BlendMode.CLEAR}
 * （与 {@link CloseButtonView} 的 ✕ 同源），在这台机器的 SystemUI 里**整块画不出来**：
 * 实测截图里徽标方框 3712 个原始像素没有一个亮于背景，而让位空隙（42px）却是真的 ——
 * 即「圆底 + 数字一起消失」。那种做法一旦不落地，症状是**零痕迹**（比画错更难查）。
 * 现在这条 path 只有一个 {@code drawPath}，没有离屏图层、没有混合模式，
 * 抗锯齿也由 path 自己带，行为在任何 canvas 上都一样。
 *
 * ⚠️ {@link #onMeasure} 强制认 LayoutParams 的固定方框：徽标被量成 0×0 时
 * {@link #onDraw} 会静默 return（同样零痕迹），不能再依赖父容器的测量礼貌。
 *
 * 字号 / 字体仍由外层（{@code StatusBarSubtitleHook.applyBadgeStyle}）通过
 * {@code setTextSize} / {@code setTypeface} 写入本 TextView，本类只管「怎么画」：
 * 读 {@link #getText()} 取数字、读 {@link #getTextSize()} 取字号。
 * 因此 {@link #onDraw} **不调用 super** —— 否则 TextView 还会拿自己的颜色再画一遍实心数字。
 */
public class NotificationBadgeView extends TextView {
    private static final String TAG = "[DLsiteSoundFloat:Badge]";

    /**
     * 数字宽度顶到圆内可用宽度时按比例缩小（"99+" 用）。
     * 对镂空这一步是**必须**的：字形越过圆外时，被挖掉的是「圆底之外的像素」，
     * 那里本来什么都没有，挖掉就等于把字形切掉一截（看着像被咬了一口）。
     */
    private static final float TEXT_MAX_W_RATIO = 0.92f;

    /** 兜底方框（dp）：只在拿不到 LayoutParams 时用；正常尺寸由外层 LayoutParams 给。 */
    private static final float FALLBACK_SIZE_DP = 11.05f;

    /** onDraw 自证日志预算（前 3 次）。查完这类问题可以留着，开销可忽略。 */
    private static int sDrawLogBudget = 3;

    /** 圆底画笔 —— 颜色由 {@link #setBadgeDiscColor(int)} 写入。 */
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 量字形 / 取字形轮廓用（本类不拿它上色，颜色无意义）。 */
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 圆 + 字形（EVEN_ODD）合成后的最终轮廓。 */
    private final Path discPath = new Path();
    /** 单次字形轮廓的暂存（避免每帧新建对象）。 */
    private final Path glyphPath = new Path();

    public NotificationBadgeView(Context context) {
        super(context);
        // TextView 默认会画自己的文字，这里必须明确「我会画」并且不让它抢着画：
        // （onDraw 不调 super，见类注释）
        setWillNotDraw(false);
        setIncludeFontPadding(false);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setTypeface(Typeface.DEFAULT_BOLD);

        discPaint.setStyle(Paint.Style.FILL);
        discPaint.setColor(0xFFFFFFFF);

        textPaint.setStyle(Paint.Style.FILL);
        // 起始 x 由本类自己算（Path 需要真实落笔点），所以用默认的 LEFT 对齐。
        textPaint.setTextAlign(Paint.Align.LEFT);

        // 关键：奇偶填充 ⇒ 圆与字形重叠处成为洞。
        discPath.setFillType(Path.FillType.EVEN_ODD);
    }

    /** 圆底颜色（= 状态栏时钟色，由 StatusBarSubtitleHook.applyBadgeStyle 写入）。 */
    public void setBadgeDiscColor(int color) {
        if (discPaint.getColor() == color) {
            return;
        }
        discPaint.setColor(color);
        invalidate();
    }

    /** 徽标里的数字（1..99 / "99+"）。值仍存在 TextView 的 text 里，绘制在 onDraw 里自己来。 */
    public void setBadgeText(CharSequence text) {
        setText(text);
        invalidate();
    }

    /**
     * 尺寸只由本类的 LayoutParams（外层给的固定方框）决定，**不接受父容器的压缩**。
     *
     * 原因：徽标一旦被量成 0×0，{@link #onDraw} 会在第一行 return，画面上不留任何痕迹 ——
     * 这正是 code 945 那个「完全看不见」最难查的形态之一，所以这里直接钉死。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        int w = (lp != null && lp.width > 0) ? lp.width : Math.round(dp(FALLBACK_SIZE_DP));
        int h = (lp != null && lp.height > 0) ? lp.height : w;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 故意不调 super.onDraw()：数字由本类以 EVEN_ODD 挖出，不能让 TextView 再画实心数字。
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            logDraw("skip(empty)", w, h);
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        // 🔴 code 947 修正：**reset() 保留 FillType，rewind() 会把它清掉**（见类注释）。
        //    946 用 rewind() ⇒ EVEN_ODD 被清成 WINDING ⇒ 圆与字形取并集 ⇒ 洞消失（实心圆）。
        discPath.reset();
        // 双保险：不依赖 reset() / rewind() 的语义差异，每帧显式再设一次。
        discPath.setFillType(Path.FillType.EVEN_ODD);
        discPath.addCircle(cx, cy, r, Path.Direction.CW);

        CharSequence cs = getText();
        if (cs != null && cs.length() > 0) {
            textPaint.setTypeface(getTypeface());
            float size = getTextSize();
            textPaint.setTextSize(size);
            String s = cs.toString();
            float tw = textPaint.measureText(s);
            float maxW = w * TEXT_MAX_W_RATIO;
            if (tw > maxW && tw > 0f) {
                size = size * maxW / tw;
                textPaint.setTextSize(size);
                tw = textPaint.measureText(s);
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            glyphPath.reset();   // 同 ②：reset 保留 FillType，rewind 会清掉
            textPaint.getTextPath(s, 0, s.length(), cx - tw / 2f, baseline, glyphPath);
            discPath.addPath(glyphPath);
        }

        canvas.drawPath(discPath, discPaint);
        logDraw("drawn", w, h);
    }

    private void logDraw(String what, int w, int h) {
        if (sDrawLogBudget <= 0) {
            return;
        }
        sDrawLogBudget--;
        try {
            XposedCompat.log(TAG + " onDraw " + what + " #" + (3 - sDrawLogBudget)
                    + " w=" + w + " h=" + h
                    + " text=" + getText()
                    + " size=" + getTextSize()
                    + " fill=" + discPath.getFillType()
                    + " glyph=" + (glyphPath.isEmpty() ? "EMPTY" : "ok")
                    + " disc=#" + Integer.toHexString(discPaint.getColor()));
        } catch (Throwable ignored) {
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
