package com.sena.dlsitesoundfloat.view;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 悬浮窗玻璃面板背景（v13 精简版）。
 *
 * 只保留一层：**半透明渐变底**（上深下浅 → 玻璃的"厚度感"）。
 *
 * v13 按 Ari 要求移除了另外三层装饰：顶部受光高光、柔和外辉光、对角轮廓边缘光。
 * 面板因此更干净、更接近"纯磨砂卡"，也为将来做别的质感留出空间。
 *
 * 另一处关键背景（重要）：
 *   v10 曾用系统级 {@code FLAG_BLUR_BEHIND} + {@code setBlurBehindRadius()} 做「真实背后模糊」，
 *   但该模糊区域由系统合成器决定，对 TYPE_APPLICATION_OVERLAY 窗口会**糊掉整个屏幕**
 *   （ColorOS 实测），且 App 侧无法限制区域 → v12 起已全局关闭（见 FloatingWindowManager）。
 *   因此"玻璃感"完全靠本类的底色不透明度来实现：无系统模糊时用 {@link #setFillScale(float)}
 *   把底色 alpha 放大（约 1.9×），保证白色字幕在任何背景上都清晰。
 *
 * 注意：本类运行在目标进程（DLsiteSound），不引用任何模块资源 ID。
 */
public class GlassPanelDrawable extends Drawable {

    // —— 深色玻璃（默认）：配白色字幕，可读性最稳 ——
    private static final int DARK_FILL_TOP = 0x4D0E1420;     // ~30% 深蓝黑
    private static final int DARK_FILL_BOTTOM = 0x3010172A;  // ~19%

    // —— 浅色玻璃（可选，更接近"奶白玻璃"）——
    private static final int LIGHT_FILL_TOP = 0x52FFFFFF;    // ~32% 白
    private static final int LIGHT_FILL_BOTTOM = 0x24FFFFFF; // ~14% 白

    private final RectF rect = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radiusPx;
    private final int fillTop;
    private final int fillBottom;

    private int globalAlpha = 255;
    /** 底色不透明度增益：无系统模糊时 >1（约 1.9），让玻璃更"实心"，保证白字可读。 */
    private float fillScale = 1f;

    public GlassPanelDrawable(float radiusPx) {
        this(radiusPx, false);
    }

    public GlassPanelDrawable(float radiusPx, boolean light) {
        this.radiusPx = radiusPx;
        this.fillTop = light ? LIGHT_FILL_TOP : DARK_FILL_TOP;
        this.fillBottom = light ? LIGHT_FILL_BOTTOM : DARK_FILL_BOTTOM;
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        // v13：已无描边/辉光，不再需要向内 inset —— 玻璃底正好铺满窗口，四周不留透明缝。
        rect.set(b.left, b.top, b.right, b.bottom);
        if (rect.width() <= 0f || rect.height() <= 0f) {
            return;
        }
        float r = Math.max(2f, radiusPx);

        // 唯一的绘制层：半透明渐变底（上深下浅 → 厚度感）。
        // fillScale：无系统模糊时把底色 alpha 放大，让面板更像"磨砂卡"而非"透明片"。
        fillPaint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                scaleAlpha(fillTop, fillScale), scaleAlpha(fillBottom, fillScale),
                Shader.TileMode.CLAMP));
        fillPaint.setAlpha(globalAlpha);
        canvas.drawRoundRect(rect, r, r, fillPaint);
    }

    /** 设置底色不透明度增益：1.0 = 原始（约 30% 深色）；无系统模糊时用 ~1.9 提高可读性。 */
    public void setFillScale(float scale) {
        float s = Math.max(1f, Math.min(3f, scale));
        if (s != fillScale) {
            fillScale = s;
            invalidateSelf();
        }
    }

    /** 只按倍数放大颜色的 alpha 通道（上限 255），保留 RGB。 */
    private static int scaleAlpha(int color, float scale) {
        int a = (int) ((color >>> 24) * scale);
        if (a > 255) {
            a = 255;
        }
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void setAlpha(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if (a != globalAlpha) {
            globalAlpha = a;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
