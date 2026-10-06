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
package io.github.ariinyume.dlsitesoundfloat.config;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 【M1】字幕外观**标准配置**的数据模型 —— PRD §十「配置键表 · 单一事实源」的代码化身。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 设计口径（改本文件前必读）
 *
 * ① **键名冻结**（PRD §FR-09 规则 4）：新增键只增不改名；废弃键保留解析兼容。
 *    所有键名字符串都定义在 {@code K_*} 常量里，读/写两边一律引用常量，禁止再写裸字符串。
 *
 * ② **范围一律夹紧，不抛异常**（PRD §9.2「校验范围值(越界夹紧)」）：
 *    外部数据（恢复导入的文件 / 旧版本配置 / 被手改坏的配置）进到本类后必须调用
 *    {@link #clamp()}，任何越界值都被拉回 [{@code MIN}, {@code MAX}]，非法枚举回落默认值。
 *
 * ③ **缺失键回落默认值**：JSON 与 SharedPreferences 两条通道都用「读不到就用默认」，
 *    这样新增键对旧配置天然兼容（读旧配置不丢字段、不炸）。
 *
 * ④ **两个存储通道共用同一份字段**：
 *    · {@link #toJson()}/{@link #fromJson(JSONObject)} —— 标准配置文件（PRD §FR-09，备份/恢复口径）；
 *    · {@link #writeTo(SharedPreferences.Editor)}/{@link #fromPrefs(SharedPreferences)}
 *      —— 平铺键值的 SharedPreferences 镜像，供 hook 侧经
 *      {@code XposedInterface#getRemotePreferences} 跨进程读取（PRD §5.3 方案 A1）。
 *    两者必须同时更新——**新增配置键时三处一起改**（字段 + 两个映射）。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class SubtitleConfig {

    // ==================================================================
    // schema / 存储位置
    // ==================================================================

    /** 配置 schema 版本（PRD §FR-09：本版 = 1）。 */
    public static final int SCHEMA_VERSION = 1;

    /**
     * SharedPreferences 名 —— 同时是 hook 侧
     * {@code XposedInterface#getRemotePreferences(group)} 的 group 名。
     * ⚠️ 改名等于断开与既有安装的兼容，不要改。
     */
    public static final String PREFS_NAME = "dlsitefloat_config";

    /** 标准配置 JSON 文件名（置于模块 {@code filesDir} 下；PRD §13.1 指定的落点）。 */
    public static final String FILE_NAME = "dlsitefloat_config.json";

    // ==================================================================
    // 键名（PRD §十 键表，逐条对应，禁止改名）
    // ==================================================================

    public static final String K_SCHEMA_VERSION = "schema_version";
    public static final String K_SAVED_AT = "saved_at";
    public static final String K_UI_LANGUAGE = "ui_language";
    public static final String K_SUBTITLE_COLOR = "subtitle_color";
    public static final String K_SHADOW_COLOR = "shadow_color";
    public static final String K_SHADOW_STRENGTH = "shadow_strength";
    public static final String K_SHADOW_RADIUS = "shadow_radius";
    public static final String K_ACTIVE_HIGHLIGHT = "active_highlight";
    public static final String K_ACTIVE_SCALE = "active_scale";
    public static final String K_INACTIVE_BLUR_ENABLED = "inactive_blur_enabled";
    public static final String K_INACTIVE_BLUR_STEPS = "inactive_blur_steps";
    public static final String K_INACTIVE_SCALE_ENABLED = "inactive_scale_enabled";
    public static final String K_INACTIVE_SCALE_PCT = "inactive_scale_pct";
    public static final String K_FONT_WEIGHT = "font_weight";
    public static final String K_TEXT_ALIGN = "text_align";
    public static final String K_LINE_SPACING_DP = "line_spacing_dp";
    public static final String K_WRAP_EXTRA_SPACING_DP = "wrap_extra_spacing_dp";
    public static final String K_STATUSBAR_SUBTITLE_ENABLED = "statusbar_subtitle_enabled";
    public static final String K_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String K_HOT_RELOAD = "hot_reload";
    /**
     * 【2.3.0】悬浮窗底色（面板渐变基色）。
     *
     * 口径：存的是**基色**（{@code #AARRGGBB}，默认不透明深蓝黑 {@link #FLOAT_WINDOW_COLOR_DEF}），
     * 面板顶部/底部两档 alpha 由 {@code SubtitleStyle} 换算，用户只挑「什么颜色」，不挑透明度。
     *
     * ⚠️ 键名冻结（PRD §FR-09 规则 4）：本键为新增键，旧配置读不到时回落默认值，天然兼容。
     */
    public static final String K_FLOAT_WINDOW_COLOR = "float_window_color";

    // ==================================================================
    // 枚举取值（PRD §十「范围/枚举」列）
    // ==================================================================

    // —— ui_language ——
    public static final String LANG_SYSTEM = "system";
    public static final String LANG_ZH_CN = "zh-CN";
    public static final String LANG_ZH_TW = "zh-TW";
    public static final String LANG_EN = "en";

    // —— font_weight（PRD §14.2 DK-01：按 4 段实现）——
    public static final String WEIGHT_SYSTEM = "system";
    public static final String WEIGHT_REGULAR = "regular";
    public static final String WEIGHT_MEDIUM = "medium";
    public static final String WEIGHT_BOLD = "bold";

    // —— text_align ——
    public static final String ALIGN_LEFT = "left";
    public static final String ALIGN_CENTER = "center";
    public static final String ALIGN_RIGHT = "right";

    // ==================================================================
    // 范围与默认值（PRD §十 键表；界面也是从这里取值，保证「小字说明」= 真实默认）
    // ==================================================================

    public static final int SHADOW_STRENGTH_MIN = 0;
    public static final int SHADOW_STRENGTH_MAX = 100;
    /**
     * 【2.3.0】默认阴影强度 80 → 50。
     *
     * 真机观感：80 档在浅色封面上糊出一圈灰边（Ari 2026-10 反馈），50 档与 PRD §十
     * 「默认 50%」的口径一致，也是设置页滑块刻度的中点，用户上下都还有余量。
     */
    public static final int SHADOW_STRENGTH_DEF = 50;

    public static final int SHADOW_RADIUS_MIN = 5;
    public static final int SHADOW_RADIUS_MAX = 20;
    public static final int SHADOW_RADIUS_DEF = 10;

    public static final int HIGHLIGHT_MIN = 50;
    public static final int HIGHLIGHT_MAX = 100;
    public static final int HIGHLIGHT_DEF = 100;

    /** 放大倍数：×0.8–×1.5，步长 0.1，默认 ×1.0（PRD §14.2 DK-03）。 */
    public static final float ACTIVE_SCALE_MIN = 0.8f;
    public static final float ACTIVE_SCALE_MAX = 1.5f;
    public static final float ACTIVE_SCALE_STEP = 0.1f;
    public static final float ACTIVE_SCALE_DEF = 1.0f;

    /** 模糊半径档位：0–16，每档 0.5px（0–8px）。【2.3.0】默认 8 档（4px）→ 6 档（3px）。 */
    public static final int BLUR_STEPS_MIN = 0;
    public static final int BLUR_STEPS_MAX = 16;
    public static final int BLUR_STEPS_DEF = 6;

    public static final int INACTIVE_SCALE_PCT_MIN = 50;
    public static final int INACTIVE_SCALE_PCT_MAX = 100;
    public static final int INACTIVE_SCALE_PCT_DEF = 90;

    /**
     * 字幕间行距：-5.0 – 5 dp，默认 0（PRD §14.2 DK-02）。
     *
     * 【2.3.0】上限 10 → 5：实测 5dp 以上行与行已经散成两屏，滑条右半段没有可用观感。
     */
    public static final float LINE_SPACING_MIN = -5.0f;
    public static final float LINE_SPACING_MAX = 5.0f;
    public static final float LINE_SPACING_DEF = 0f;

    /** 长字幕内换行额外行距：0 – 5 dp，默认 0。 */
    public static final float WRAP_SPACING_MIN = 0f;
    public static final float WRAP_SPACING_MAX = 5f;
    public static final float WRAP_SPACING_DEF = 0f;

    public static final int SUBTITLE_COLOR_DEF = 0xFFFFFFFF;
    public static final int SHADOW_COLOR_DEF = 0xFF000000;

    /** 【2.3.0】悬浮窗面板渐变基色（深蓝黑）；两档 alpha 由 {@code SubtitleStyle} 叠加。 */
    public static final int FLOAT_WINDOW_COLOR_DEF = 0xFF0E1420;

    // ==================================================================
    // 字段（默认值即 PRD §十「默认值」列）
    // ==================================================================

    public int schemaVersion = SCHEMA_VERSION;
    /** 最后一次保存时间（ISO 8601）；只读展示用，不影响渲染。 */
    public String savedAt = "";

    public String uiLanguage = LANG_SYSTEM;

    public int subtitleColor = SUBTITLE_COLOR_DEF;
    public int shadowColor = SHADOW_COLOR_DEF;
    public int shadowStrength = SHADOW_STRENGTH_DEF;
    public int shadowRadius = SHADOW_RADIUS_DEF;
    public int activeHighlight = HIGHLIGHT_DEF;
    public float activeScale = ACTIVE_SCALE_DEF;

    public boolean inactiveBlurEnabled = true;
    public int inactiveBlurSteps = BLUR_STEPS_DEF;
    public boolean inactiveScaleEnabled = true;
    public int inactiveScalePct = INACTIVE_SCALE_PCT_DEF;

    public String fontWeight = WEIGHT_SYSTEM;
    public String textAlign = ALIGN_CENTER;
    public float lineSpacingDp = LINE_SPACING_DEF;
    public float wrapExtraSpacingDp = WRAP_SPACING_DEF;

    /** 【2.3.0】悬浮窗面板渐变基色（RGB 生效，alpha 由 {@code SubtitleStyle} 换算）。 */
    public int floatWindowColor = FLOAT_WINDOW_COLOR_DEF;

    /** PRD §FR-07：SystemUI 未授权时**只置灰 UI、不写这个键**，避免把「未授权」记成「用户主动关」。 */
    public boolean statusbarSubtitleEnabled = true;
    public boolean keepScreenOn = true;
    /** PRD §5.3 A1：热重载开关（界面不展示）；关掉即退化成 A2「保存后需手动重启 SystemUI」。 */
    public boolean hotReload = true;

    public SubtitleConfig() {
    }

    // ==================================================================
    // 默认值 / 复制
    // ==================================================================

    /** 一份全新的默认配置（PRD §9.1 状态机里「恢复默认」的取值来源）。 */
    public static SubtitleConfig defaults() {
        return new SubtitleConfig();
    }

    public SubtitleConfig copy() {
        SubtitleConfig c = new SubtitleConfig();
        c.schemaVersion = schemaVersion;
        c.savedAt = savedAt;
        c.uiLanguage = uiLanguage;
        c.subtitleColor = subtitleColor;
        c.shadowColor = shadowColor;
        c.shadowStrength = shadowStrength;
        c.shadowRadius = shadowRadius;
        c.activeHighlight = activeHighlight;
        c.activeScale = activeScale;
        c.inactiveBlurEnabled = inactiveBlurEnabled;
        c.inactiveBlurSteps = inactiveBlurSteps;
        c.inactiveScaleEnabled = inactiveScaleEnabled;
        c.inactiveScalePct = inactiveScalePct;
        c.fontWeight = fontWeight;
        c.textAlign = textAlign;
        c.lineSpacingDp = lineSpacingDp;
        c.wrapExtraSpacingDp = wrapExtraSpacingDp;
        c.floatWindowColor = floatWindowColor;
        c.statusbarSubtitleEnabled = statusbarSubtitleEnabled;
        c.keepScreenOn = keepScreenOn;
        c.hotReload = hotReload;
        return c;
    }

    /** 只比较**参数**，不含 {@link #savedAt} / {@link #schemaVersion}（用于「配置无变化」判定）。 */
    public boolean sameParametersAs(SubtitleConfig o) {
        if (o == null) {
            return false;
        }
        return subtitleColor == o.subtitleColor
                && shadowColor == o.shadowColor
                && shadowStrength == o.shadowStrength
                && shadowRadius == o.shadowRadius
                && activeHighlight == o.activeHighlight
                && Float.compare(activeScale, o.activeScale) == 0
                && inactiveBlurEnabled == o.inactiveBlurEnabled
                && inactiveBlurSteps == o.inactiveBlurSteps
                && inactiveScaleEnabled == o.inactiveScaleEnabled
                && inactiveScalePct == o.inactiveScalePct
                && eq(fontWeight, o.fontWeight)
                && eq(textAlign, o.textAlign)
                && Float.compare(lineSpacingDp, o.lineSpacingDp) == 0
                && Float.compare(wrapExtraSpacingDp, o.wrapExtraSpacingDp) == 0
                && floatWindowColor == o.floatWindowColor
                && statusbarSubtitleEnabled == o.statusbarSubtitleEnabled
                && keepScreenOn == o.keepScreenOn
                && hotReload == o.hotReload
                && eq(uiLanguage, o.uiLanguage);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ==================================================================
    // 校验与夹紧（PRD §9.2）
    // ==================================================================

    /** 把所有字段拉回合法范围；非法枚举回落默认值。**原地修改**。 */
    public void clamp() {
        if (schemaVersion <= 0) {
            schemaVersion = SCHEMA_VERSION;
        }
        uiLanguage = normalizeEnum(uiLanguage, LANG_SYSTEM,
                LANG_SYSTEM, LANG_ZH_CN, LANG_ZH_TW, LANG_EN);
        fontWeight = normalizeEnum(fontWeight, WEIGHT_SYSTEM,
                WEIGHT_SYSTEM, WEIGHT_REGULAR, WEIGHT_MEDIUM, WEIGHT_BOLD);
        textAlign = normalizeEnum(textAlign, ALIGN_CENTER,
                ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT);

        shadowStrength = clampInt(shadowStrength, SHADOW_STRENGTH_MIN, SHADOW_STRENGTH_MAX);
        shadowRadius = clampInt(shadowRadius, SHADOW_RADIUS_MIN, SHADOW_RADIUS_MAX);
        activeHighlight = clampInt(activeHighlight, HIGHLIGHT_MIN, HIGHLIGHT_MAX);
        inactiveBlurSteps = clampInt(inactiveBlurSteps, BLUR_STEPS_MIN, BLUR_STEPS_MAX);
        inactiveScalePct = clampInt(inactiveScalePct, INACTIVE_SCALE_PCT_MIN, INACTIVE_SCALE_PCT_MAX);

        activeScale = clampFloat(activeScale, ACTIVE_SCALE_MIN, ACTIVE_SCALE_MAX);
        lineSpacingDp = clampFloat(lineSpacingDp, LINE_SPACING_MIN, LINE_SPACING_MAX);
        wrapExtraSpacingDp = clampFloat(wrapExtraSpacingDp, WRAP_SPACING_MIN, WRAP_SPACING_MAX);
    }

    public static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float clampFloat(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return min;
        }
        return v < min ? min : (v > max ? max : v);
    }

    private static String normalizeEnum(String v, String def, String... allowed) {
        if (v != null) {
            for (String a : allowed) {
                if (a.equals(v)) {
                    return v;
                }
            }
        }
        return def;
    }

    // ==================================================================
    // JSON（标准配置文件通道，PRD §FR-09）
    // ==================================================================

    /** 序列化为标准配置 JSON（含 {@code schema_version} 与 {@code saved_at}）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put(K_SCHEMA_VERSION, SCHEMA_VERSION);
            o.put(K_SAVED_AT, savedAt == null ? "" : savedAt);
            o.put(K_UI_LANGUAGE, uiLanguage);
            o.put(K_SUBTITLE_COLOR, argbToHex(subtitleColor));
            o.put(K_SHADOW_COLOR, argbToHex(shadowColor));
            o.put(K_SHADOW_STRENGTH, shadowStrength);
            o.put(K_SHADOW_RADIUS, shadowRadius);
            o.put(K_ACTIVE_HIGHLIGHT, activeHighlight);
            o.put(K_ACTIVE_SCALE, round1(activeScale));
            o.put(K_INACTIVE_BLUR_ENABLED, inactiveBlurEnabled);
            o.put(K_INACTIVE_BLUR_STEPS, inactiveBlurSteps);
            o.put(K_INACTIVE_SCALE_ENABLED, inactiveScaleEnabled);
            o.put(K_INACTIVE_SCALE_PCT, inactiveScalePct);
            o.put(K_FONT_WEIGHT, fontWeight);
            o.put(K_TEXT_ALIGN, textAlign);
            o.put(K_LINE_SPACING_DP, round1(lineSpacingDp));
            o.put(K_WRAP_EXTRA_SPACING_DP, round1(wrapExtraSpacingDp));
            o.put(K_FLOAT_WINDOW_COLOR, argbToHex(floatWindowColor));
            o.put(K_STATUSBAR_SUBTITLE_ENABLED, statusbarSubtitleEnabled);
            o.put(K_KEEP_SCREEN_ON, keepScreenOn);
            o.put(K_HOT_RELOAD, hotReload);
        } catch (Throwable ignored) {
            // JSONObject.put 只在传 null 键时抛，本方法不传 null
        }
        return o;
    }

    /**
     * 从标准配置 JSON 反序列化。**缺失键回落默认值**，任何解析异常都吞掉并回落默认，
     * 最后统一 {@link #clamp()}（PRD §FR-09 规则 3：读取失败/半套参数一律整体回退默认值）。
     */
    public static SubtitleConfig fromJson(JSONObject o) {
        SubtitleConfig c = new SubtitleConfig();
        if (o == null) {
            return c;
        }
        int fileVersion = o.optInt(K_SCHEMA_VERSION, SCHEMA_VERSION);
        // 过新的 schema：不认识的键一律忽略，只按本版能懂的键读（PRD §9.3 的版本闸在导入侧拦）
        c.schemaVersion = fileVersion;
        c.savedAt = o.optString(K_SAVED_AT, "");
        c.uiLanguage = o.optString(K_UI_LANGUAGE, c.uiLanguage);
        c.subtitleColor = hexToArgb(o.optString(K_SUBTITLE_COLOR, null), c.subtitleColor);
        c.shadowColor = hexToArgb(o.optString(K_SHADOW_COLOR, null), c.shadowColor);
        c.shadowStrength = o.optInt(K_SHADOW_STRENGTH, c.shadowStrength);
        c.shadowRadius = o.optInt(K_SHADOW_RADIUS, c.shadowRadius);
        c.activeHighlight = o.optInt(K_ACTIVE_HIGHLIGHT, c.activeHighlight);
        c.activeScale = (float) o.optDouble(K_ACTIVE_SCALE, c.activeScale);
        c.inactiveBlurEnabled = o.optBoolean(K_INACTIVE_BLUR_ENABLED, c.inactiveBlurEnabled);
        c.inactiveBlurSteps = o.optInt(K_INACTIVE_BLUR_STEPS, c.inactiveBlurSteps);
        c.inactiveScaleEnabled = o.optBoolean(K_INACTIVE_SCALE_ENABLED, c.inactiveScaleEnabled);
        c.inactiveScalePct = o.optInt(K_INACTIVE_SCALE_PCT, c.inactiveScalePct);
        c.fontWeight = o.optString(K_FONT_WEIGHT, c.fontWeight);
        c.textAlign = o.optString(K_TEXT_ALIGN, c.textAlign);
        c.lineSpacingDp = (float) o.optDouble(K_LINE_SPACING_DP, c.lineSpacingDp);
        c.wrapExtraSpacingDp = (float) o.optDouble(K_WRAP_EXTRA_SPACING_DP, c.wrapExtraSpacingDp);
        c.floatWindowColor = hexToArgb(o.optString(K_FLOAT_WINDOW_COLOR, null), c.floatWindowColor);
        c.statusbarSubtitleEnabled = o.optBoolean(K_STATUSBAR_SUBTITLE_ENABLED, c.statusbarSubtitleEnabled);
        c.keepScreenOn = o.optBoolean(K_KEEP_SCREEN_ON, c.keepScreenOn);
        c.hotReload = o.optBoolean(K_HOT_RELOAD, c.hotReload);
        c.clamp();
        return c;
    }

    // ==================================================================
    // SharedPreferences（跨进程读取通道，PRD §5.3 A1）
    // ==================================================================

    /** 把所有键平铺写入 SharedPreferences（hook 侧经 getRemotePreferences 读的就是这份）。 */
    public void writeTo(SharedPreferences.Editor e) {
        e.putInt(K_SCHEMA_VERSION, SCHEMA_VERSION);
        e.putString(K_SAVED_AT, savedAt == null ? "" : savedAt);
        e.putString(K_UI_LANGUAGE, uiLanguage);
        e.putInt(K_SUBTITLE_COLOR, subtitleColor);
        e.putInt(K_SHADOW_COLOR, shadowColor);
        e.putInt(K_SHADOW_STRENGTH, shadowStrength);
        e.putInt(K_SHADOW_RADIUS, shadowRadius);
        e.putInt(K_ACTIVE_HIGHLIGHT, activeHighlight);
        e.putFloat(K_ACTIVE_SCALE, activeScale);
        e.putBoolean(K_INACTIVE_BLUR_ENABLED, inactiveBlurEnabled);
        e.putInt(K_INACTIVE_BLUR_STEPS, inactiveBlurSteps);
        e.putBoolean(K_INACTIVE_SCALE_ENABLED, inactiveScaleEnabled);
        e.putInt(K_INACTIVE_SCALE_PCT, inactiveScalePct);
        e.putString(K_FONT_WEIGHT, fontWeight);
        e.putString(K_TEXT_ALIGN, textAlign);
        e.putFloat(K_LINE_SPACING_DP, lineSpacingDp);
        e.putFloat(K_WRAP_EXTRA_SPACING_DP, wrapExtraSpacingDp);
        e.putInt(K_FLOAT_WINDOW_COLOR, floatWindowColor);
        e.putBoolean(K_STATUSBAR_SUBTITLE_ENABLED, statusbarSubtitleEnabled);
        e.putBoolean(K_KEEP_SCREEN_ON, keepScreenOn);
        e.putBoolean(K_HOT_RELOAD, hotReload);
    }

    /** 从 SharedPreferences 读；未写过的键（{@code contains == false}）保留默认值。 */
    public static SubtitleConfig fromPrefs(SharedPreferences p) {
        SubtitleConfig c = new SubtitleConfig();
        if (p == null) {
            return c;
        }
        c.schemaVersion = p.getInt(K_SCHEMA_VERSION, c.schemaVersion);
        c.savedAt = p.getString(K_SAVED_AT, c.savedAt);
        c.uiLanguage = p.getString(K_UI_LANGUAGE, c.uiLanguage);
        c.subtitleColor = p.getInt(K_SUBTITLE_COLOR, c.subtitleColor);
        c.shadowColor = p.getInt(K_SHADOW_COLOR, c.shadowColor);
        c.shadowStrength = p.getInt(K_SHADOW_STRENGTH, c.shadowStrength);
        c.shadowRadius = p.getInt(K_SHADOW_RADIUS, c.shadowRadius);
        c.activeHighlight = p.getInt(K_ACTIVE_HIGHLIGHT, c.activeHighlight);
        c.activeScale = p.getFloat(K_ACTIVE_SCALE, c.activeScale);
        c.inactiveBlurEnabled = p.getBoolean(K_INACTIVE_BLUR_ENABLED, c.inactiveBlurEnabled);
        c.inactiveBlurSteps = p.getInt(K_INACTIVE_BLUR_STEPS, c.inactiveBlurSteps);
        c.inactiveScaleEnabled = p.getBoolean(K_INACTIVE_SCALE_ENABLED, c.inactiveScaleEnabled);
        c.inactiveScalePct = p.getInt(K_INACTIVE_SCALE_PCT, c.inactiveScalePct);
        c.fontWeight = p.getString(K_FONT_WEIGHT, c.fontWeight);
        c.textAlign = p.getString(K_TEXT_ALIGN, c.textAlign);
        c.lineSpacingDp = p.getFloat(K_LINE_SPACING_DP, c.lineSpacingDp);
        c.wrapExtraSpacingDp = p.getFloat(K_WRAP_EXTRA_SPACING_DP, c.wrapExtraSpacingDp);
        c.floatWindowColor = p.getInt(K_FLOAT_WINDOW_COLOR, c.floatWindowColor);
        c.statusbarSubtitleEnabled = p.getBoolean(K_STATUSBAR_SUBTITLE_ENABLED, c.statusbarSubtitleEnabled);
        c.keepScreenOn = p.getBoolean(K_KEEP_SCREEN_ON, c.keepScreenOn);
        c.hotReload = p.getBoolean(K_HOT_RELOAD, c.hotReload);
        c.clamp();
        return c;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 当前时间的 ISO 8601 字符串（用于 {@code saved_at}）。 */
    public static String nowIso8601() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /** ARGB int → {@code #AARRGGBB}（PRD §十 用 {@code #FFFFFFFF} 这种写法）。 */
    public static String argbToHex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    /**
     * {@code #RRGGBB} / {@code #AARRGGBB} → ARGB int；解析失败返回 {@code fallback}。
     * 与设置页取色面板的 HEX 输入共用同一套口径（PRD §FR-04）。
     */
    public static int hexToArgb(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            if (s.length() == 6) {
                return 0xFF000000 | (int) Long.parseLong(s, 16);
            }
            if (s.length() == 8) {
                return (int) Long.parseLong(s, 16);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static double round1(float v) {
        return Math.round(v * 10d) / 10d;
    }

    /** 一行日志摘要（排查「hook 侧到底读到哪份配置」时看它）。 */
    public String summary() {
        return "lang=" + uiLanguage
                + " color=" + argbToHex(subtitleColor)
                + " shadow=" + argbToHex(shadowColor) + "/" + shadowStrength + "%/" + shadowRadius + "%"
                + " highlight=" + activeHighlight + "% scale=" + round1(activeScale)
                + " inactive[blur=" + (inactiveBlurEnabled ? inactiveBlurSteps : "off")
                + ", scale=" + (inactiveScaleEnabled ? inactiveScalePct + "%" : "off") + "]"
                + " weight=" + fontWeight + " align=" + textAlign
                + " spacing=" + round1(lineSpacingDp) + "/" + round1(wrapExtraSpacingDp)
                + " floatWindow=" + argbToHex(floatWindowColor);
    }

    @Override
    public String toString() {
        return "SubtitleConfig{" + summary() + "}";
    }
}
