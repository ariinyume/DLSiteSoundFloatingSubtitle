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

import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;

import io.github.ariinyume.dlsitesoundfloat.config.SubtitleConfig;

/**
 * 【M1】「配置参数 → 绘制参数」的**唯一映射函数**。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么单独抽一个类（PRD §14.1 RK-03 的应对措施）
 *
 * 风险登记册写得很清楚：预览区与真实悬浮窗如果各写一套参数换算，迟早会漂移。
 * 所以换算只在这里做一次 —— 悬浮窗（{@link FloatingSubtitleView}）现在用它，
 * M2 的预览渲染层（纯色/壁纸底 + 同样的绘制参数）直接复用同一个
 * {@link #of(SubtitleConfig, DisplayMetrics)}，两边不可能算出两份结果。
 *
 * 换算规则与 legacy 常量（2.1.2 之前的硬编码值）的对应关系逐条写在字段注释里，
 * 便于「用户说默认值看起来和旧版不一样」时一眼定位是哪一个换算改了观感。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class SubtitleStyle {

    // ==================================================================
    // 设计基准常量（与 FloatingSubtitleView 的历史常量同源）
    // ==================================================================

    /** 活动行基准字号（sp）—— legacy {@code BASE_TEXT_SP = 17f}。 */
    public static final float BASE_TEXT_SP = 17f;
    /** 降级镜像行字号（sp）—— legacy {@code renderMirrored} 里的 18f（本轮不暴露给用户）。 */
    public static final float MIRROR_TEXT_SP = 18f;
    /** 行内左右内边距（dp）—— legacy {@code LINE_PAD_H_DP = 2}。 */
    public static final int LINE_PAD_H_DP = 2;
    /** 行内上下内边距基准（dp）—— legacy {@code LINE_PAD_V_DP = 5}。 */
    public static final int LINE_PAD_V_DP = 5;

    /** 阴影半径换算：1% = 0.7px ⇒ 默认 10% **恰好**等于 legacy 的 {@code 7f}。 */
    public static final float SHADOW_RADIUS_PX_PER_PCT = 0.7f;
    /** 阴影纵向偏移（dp）—— legacy {@code dp(1)}。 */
    public static final float SHADOW_DY_DP = 1f;
    /**
     * 阴影强度的换算基准说明（PRD §十 默认 50%）。
     *
     * ⚠️ **观感变更有意为之**：legacy 常量是 {@code 0xCC000000}（alpha ≈ 80%），
     *    而 PRD 把「阴影强度」的默认值拍成了 50%。本类按「强度% 线性映射到 alpha」
     *    实现（50% → alpha 0x80），所以默认阴影比 2.1.2 略淡。
     *    若希望恢复旧观感，把 {@link SubtitleConfig#SHADOW_STRENGTH_DEF} 改成 80 即可，
     *    不需要动这里的换算。**不要**在换算里偷偷加偏移量——那会让滑块刻度失去意义。
     */
    public static final int SHADOW_STRENGTH_LEGACY_PCT = 80;

    /**
     * 非活动行整体不透明度。
     *
     * legacy 的两段是「颜色 alpha {@code 0x99}（0.6）+ 视图 alpha 0.35」，
     * 乘积 = 0.21。这里折成单一个视图 alpha（颜色用配置色），最终不透明度完全一致。
     */
    public static final float INACTIVE_ALPHA = 0.21f;

    /** 模糊半径换算：1 档 = 0.5px（PRD §FR-05）。legacy 固定 6px ⇒ 对应 12 档。 */
    public static final float BLUR_PX_PER_STEP = 0.5f;
    /** legacy 的模糊半径（px），仅用于注释对照。 */
    public static final float BLUR_PX_LEGACY = 6f;

    // ==================================================================
    // 字体
    // ==================================================================

    private static final Typeface TF_NORMAL = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private static final Typeface TF_BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private static final Typeface TF_MEDIUM = createMedium();

    private static Typeface createMedium() {
        try {
            // "sans-serif-medium" 自 API 21 起就是系统内置字族，比 Typeface.create(family, 500)
            // （API 28+）兼容面更广；失败了才退化成粗体。
            return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        } catch (Throwable t) {
            return TF_BOLD;
        }
    }

    // ==================================================================
    // 换算结果
    // ==================================================================

    /** 活动行字号（sp）—— {@code 17 × active_scale}。 */
    public final float activeSizeSp;
    /** 非活动行字号（sp）—— {@code 17 × inactive_scale_pct%}；缩放关闭时也用这个值（只是不缩小）。 */
    public final float inactiveSizeSp;
    /** 降级镜像行字号（sp）。 */
    public final float mirrorSizeSp;

    /** 活动行/非活动行颜色（都取 {@code subtitle_color}，差别只在 alpha）。 */
    public final int activeColor;
    public final int inactiveColor;
    /** 活动行不透明度 —— {@code active_highlight%}（默认 100% ⇒ 与 legacy 的 0xFF 一致）。 */
    public final float activeAlpha;
    /** 非活动行不透明度 —— 恒为 {@link #INACTIVE_ALPHA}。 */
    public final float inactiveAlpha;

    public final Typeface activeTypeface;
    public final Typeface inactiveTypeface;

    /** 行对齐（{@code text_align}）。 */
    public final int gravity;

    /** 非活动行是否模糊（{@code inactive_blur_enabled}）。 */
    public final boolean inactiveBlurEnabled;
    /** 模糊半径（px）。 */
    public final float blurRadiusPx;

    /** 阴影半径（px）、纵向偏移（px）、颜色（已按强度折算 alpha）。 */
    public final float shadowRadiusPx;
    public final float shadowDyPx;
    public final int shadowColor;

    /** 行内边距（dp）：左右固定，上下含「字幕间行距」的一半。 */
    public final int linePadH;
    public final int linePadV;

    /** 长字幕内换行的额外行距（px），作用在活动行段落上。 */
    public final float wrapExtraSpacingPx;

    /** 非活动行缩放是否开启（关闭时字号按 100% 处理）。 */
    public final boolean inactiveScaleEnabled;

    // ── 悬浮窗面板底色（【2.3.0】由 cfg.floatWindowColor 换算）─────────────
    //
    // 换算口径（Ari 给的观感指标反推，写在这里防止后人再猜一次）：
    //   · 用户只挑**基色 RGB**，两档不透明度是本类固定的（渐变上深下浅）；
    //   · 面板实际绘制时 GlassPanelDrawable 还会乘一次 fillScale（无系统模糊时 = 1.9）；
    //   · 于是「屏上看到的不透明度」= 下面这两个 alpha × 1.9：
    //       顶部 0x4D = 77  → 77  × 1.9 = 146 = 0x92 ≈ 57%
    //       底部 0x30 = 48  → 48  × 1.9 =  91 = 0x5B ≈ 36%
    //     正好是 Ari 给的「顶部约 57%、底部约 36%，上深下浅」。
    //   · 因此**默认值算出来恰好等于** GlassPanelDrawable 里的原常量
    //     0x4D0E1420（顶）/ 0x3010172A（底，RGB 差一档，观感等价）—— 行为不变。
    //
    /** 面板顶部不透明度（alpha 档位；×1.9 增益后 ≈ 57%）。 */
    public static final int PANEL_ALPHA_TOP = 0x4D;
    /** 面板底部不透明度（alpha 档位；×1.9 增益后 ≈ 36%）。 */
    public static final int PANEL_ALPHA_BOTTOM = 0x30;

    /** 面板渐变顶色（基色 RGB + 顶部 alpha）。 */
    public final int panelColorTop;
    /** 面板渐变底色（基色 RGB + 底部 alpha）。 */
    public final int panelColorBottom;

    /**
     * 【2.2.9】液态玻璃是否开启（{@code liquid_glass}）。
     *
     * true 时渲染端（{@code FloatingSubtitleView} / {@code ActivityButtonHook}）
     * 改用 {@code LiquidGlassDrawable}，上面两个 {@code panelColor*} **不生效**。
     */
    public final boolean liquidGlass;

    /**
     * 【2.2.11b】模糊强度（0–100，默认 60）。
     * 有真实背景时 = 背景模糊半径；没有背景时 = 玻璃自身的柔化程度。见 {@code LiquidGlassDrawable}。
     */
    public final int liquidGlassBlurPct;

    private SubtitleStyle(SubtitleConfig c, DisplayMetrics dm) {
        float density = (dm == null || dm.density <= 0f) ? 1f : dm.density;
        SubtitleConfig cfg = c == null ? SubtitleConfig.defaults() : c;

        // ── 字号 ──────────────────────────────────────────────────────
        this.activeSizeSp = BASE_TEXT_SP * cfg.activeScale;
        this.mirrorSizeSp = MIRROR_TEXT_SP;
        this.inactiveSizeSp = cfg.inactiveScaleEnabled
                ? BASE_TEXT_SP * (cfg.inactiveScalePct / 100f)
                : BASE_TEXT_SP;

        // ── 颜色与不透明度 ────────────────────────────────────────────
        this.activeColor = cfg.subtitleColor;
        this.inactiveColor = cfg.subtitleColor;
        this.activeAlpha = cfg.activeHighlight / 100f;
        this.inactiveAlpha = INACTIVE_ALPHA;

        // ── 字体 ──────────────────────────────────────────────────────
        // 「跟随系统」= legacy 行为：活动行加粗、其余常规（PRD §FR-06 默认档）
        // 显式选了档位则**活动行与非活动行一起**用该字重（PRD §FR-06 验收标准：
        // 「选择粗体 + 左对齐 → 活动行以粗体左对齐渲染，非活动行同步」）
        if (SubtitleConfig.WEIGHT_REGULAR.equals(cfg.fontWeight)) {
            this.activeTypeface = TF_NORMAL;
            this.inactiveTypeface = TF_NORMAL;
        } else if (SubtitleConfig.WEIGHT_MEDIUM.equals(cfg.fontWeight)) {
            this.activeTypeface = TF_MEDIUM;
            this.inactiveTypeface = TF_MEDIUM;
        } else if (SubtitleConfig.WEIGHT_BOLD.equals(cfg.fontWeight)) {
            this.activeTypeface = TF_BOLD;
            this.inactiveTypeface = TF_BOLD;
        } else {
            this.activeTypeface = TF_BOLD;
            this.inactiveTypeface = TF_NORMAL;
        }

        // ── 对齐 ──────────────────────────────────────────────────────
        if (SubtitleConfig.ALIGN_LEFT.equals(cfg.textAlign)) {
            this.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        } else if (SubtitleConfig.ALIGN_RIGHT.equals(cfg.textAlign)) {
            this.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        } else {
            this.gravity = Gravity.CENTER;
        }

        // ── 阴影 ──────────────────────────────────────────────────────
        this.shadowRadiusPx = cfg.shadowRadius * SHADOW_RADIUS_PX_PER_PCT;
        this.shadowDyPx = SHADOW_DY_DP * density;
        this.shadowColor = setAlpha(cfg.shadowColor,
                Math.round(cfg.shadowStrength / 100f * 255f));

        // ── 非活动行模糊 ──────────────────────────────────────────────
        this.inactiveBlurEnabled = cfg.inactiveBlurEnabled && cfg.inactiveBlurSteps > 0;
        this.blurRadiusPx = cfg.inactiveBlurSteps * BLUR_PX_PER_STEP;

        // ── 行距 ──────────────────────────────────────────────────────
        // 「字幕间行距」作用在**相邻字幕行之间**：实现为每行上下内边距各加一半
        //（container 是 LinearLayout，行距即行间距）。负值时只减到 0，不会吃掉内容。
        int half = Math.round(cfg.lineSpacingDp / 2f);
        this.linePadV = Math.max(0, LINE_PAD_V_DP + half);
        this.linePadH = LINE_PAD_H_DP;
        this.wrapExtraSpacingPx = cfg.wrapExtraSpacingDp * density;

        this.inactiveScaleEnabled = cfg.inactiveScaleEnabled;

        // ── 悬浮窗面板底色（【2.3.0】配置化：只取基色 RGB，alpha 走固定两档）─────
        int panelRgb = cfg.floatWindowColor & 0x00FFFFFF;
        this.panelColorTop = (PANEL_ALPHA_TOP << 24) | panelRgb;
        this.panelColorBottom = (PANEL_ALPHA_BOTTOM << 24) | panelRgb;

        // ── 【2.2.9】液态玻璃开关（渲染后端选型，见 LiquidGlassDrawable）─────────
        this.liquidGlass = cfg.liquidGlass;
        this.liquidGlassBlurPct = cfg.liquidGlassBlurPct;
    }

    /** 「配置 + 屏幕密度」→ 绘制参数（唯一换算入口）。 */
    public static SubtitleStyle of(SubtitleConfig c, DisplayMetrics dm) {
        return new SubtitleStyle(c, dm);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 替换 ARGB 的 alpha 通道。 */
    public static int setAlpha(int argb, int alpha) {
        int a = SubtitleConfig.clampInt(alpha, 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 两份绘制参数是否等价（用于「参数没变就跳过重建」）。 */
    public boolean sameAs(SubtitleStyle o) {
        if (o == null) {
            return false;
        }
        return activeColor == o.activeColor
                && inactiveColor == o.inactiveColor
                && shadowColor == o.shadowColor
                && linePadH == o.linePadH
                && linePadV == o.linePadV
                && gravity == o.gravity
                && inactiveBlurEnabled == o.inactiveBlurEnabled
                && inactiveScaleEnabled == o.inactiveScaleEnabled
                && Float.compare(activeSizeSp, o.activeSizeSp) == 0
                && Float.compare(inactiveSizeSp, o.inactiveSizeSp) == 0
                && Float.compare(activeAlpha, o.activeAlpha) == 0
                && Float.compare(blurRadiusPx, o.blurRadiusPx) == 0
                && Float.compare(shadowRadiusPx, o.shadowRadiusPx) == 0
                && Float.compare(shadowDyPx, o.shadowDyPx) == 0
                && Float.compare(wrapExtraSpacingPx, o.wrapExtraSpacingPx) == 0
                && panelColorTop == o.panelColorTop
                && panelColorBottom == o.panelColorBottom
                && liquidGlass == o.liquidGlass
                && liquidGlassBlurPct == o.liquidGlassBlurPct
                && activeTypeface == o.activeTypeface
                && inactiveTypeface == o.inactiveTypeface;
    }

    /** 一行日志摘要（排查「配置到底生效了没」时看它）。 */
    public String summary() {
        return "active[size=" + activeSizeSp + "sp color=" + hex(activeColor)
                + " alpha=" + activeAlpha + " tf=" + tfName(activeTypeface) + "]"
                + " inactive[size=" + inactiveSizeSp + "sp alpha=" + inactiveAlpha
                + " blur=" + (inactiveBlurEnabled ? blurRadiusPx + "px" : "off")
                + " tf=" + tfName(inactiveTypeface) + "]"
                + " shadow[color=" + hex(shadowColor) + " r=" + shadowRadiusPx + " dy=" + shadowDyPx + "]"
                + " gravity=" + gravity + " padV=" + linePadV + "dp wrapExtra=" + wrapExtraSpacingPx + "px"
                + " panel[" + hex(panelColorTop) + "->" + hex(panelColorBottom)
                + (liquidGlass ? " liquidGlass/" + liquidGlassBlurPct + "%" : "") + "]";
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.ROOT, "#%08X", c);
    }

    private static String tfName(Typeface t) {
        if (t == TF_BOLD) {
            return "bold";
        }
        if (t == TF_MEDIUM) {
            return "medium";
        }
        return "normal";
    }

}
