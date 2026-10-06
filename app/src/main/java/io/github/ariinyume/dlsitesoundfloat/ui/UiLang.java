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

import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

import io.github.ariinyume.dlsitesoundfloat.config.SubtitleConfig;
import io.github.ariinyume.dlsitesoundfloat.util.I18n;

/**
 * 【M1】设置页界面语言（PRD §FR-02）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ① 三个具体语言 + 一个「跟随系统」：取值与 PRD §十 的 {@code ui_language} 枚举一一对应
 *    （{@code system} / {@code zh-CN} / {@code zh-TW} / {@code en}）。
 *
 * ② **只影响设置页 UI 与预览区示例字幕**，不影响插件在 DLsiteSound 内的悬浮字幕语言
 *    （那条链路走 {@link I18n}，按系统语言判定，PRD §FR-02 规则 2）。两者刻意解耦：
 *    用户可以把设置页切成英文，而悬浮窗按钮仍显示宿主系统的语言。
 *
 * ③ 系统语言判定**复用** {@link I18n#from(Locale)}：繁体识别（BCP-47 的 Hant/Hans +
 *    TW/HK/MO 地区回退）那套规则已经在真机上验证过，这里不重复实现。
 * ─────────────────────────────────────────────────────────────────────
 */
public enum UiLang {

    /** 跟随系统（PRD §FR-02 规则 3）。 */
    SYSTEM,
    ZH_CN,
    ZH_TW,
    EN;

    /** 写进配置的字符串。 */
    public String configValue() {
        switch (this) {
            case ZH_CN:
                return SubtitleConfig.LANG_ZH_CN;
            case ZH_TW:
                return SubtitleConfig.LANG_ZH_TW;
            case EN:
                return SubtitleConfig.LANG_EN;
            case SYSTEM:
            default:
                return SubtitleConfig.LANG_SYSTEM;
        }
    }

    /** 由配置值解析（未知值 → {@link #SYSTEM}）。 */
    public static UiLang fromConfigValue(String v) {
        if (SubtitleConfig.LANG_ZH_CN.equals(v)) {
            return ZH_CN;
        }
        if (SubtitleConfig.LANG_ZH_TW.equals(v)) {
            return ZH_TW;
        }
        if (SubtitleConfig.LANG_EN.equals(v)) {
            return EN;
        }
        return SYSTEM;
    }

    /** 把「跟随系统」解析成具体语言（其余原样返回）。 */
    public UiLang resolve() {
        return this == SYSTEM ? detectSystem() : this;
    }

    /** 当前系统语言（简体 / 繁体 / 其它→英文）。 */
    public static UiLang detectSystem() {
        Locale locale = null;
        try {
            Configuration cfg = Resources.getSystem().getConfiguration();
            if (cfg != null && cfg.getLocales() != null && cfg.getLocales().size() > 0) {
                locale = cfg.getLocales().get(0);
            }
        } catch (Throwable ignored) {
        }
        if (locale == null) {
            try {
                locale = Locale.getDefault();
            } catch (Throwable ignored) {
            }
        }
        if (locale == null) {
            return ZH_CN;
        }
        switch (I18n.from(locale)) {
            case HANT:
                return ZH_TW;
            case EN:
                return EN;
            case HANS:
            default:
                return ZH_CN;
        }
    }

    /** 该语言的自称（语言选择弹层里每一项都用**它自己**的语言写，PRD §FR-01 规则 6）。 */
    public String nativeName() {
        switch (this) {
            case ZH_TW:
                return "繁體中文";
            case EN:
                return "English";
            case ZH_CN:
            case SYSTEM:
            default:
                return "简体中文";
        }
    }

    /**
     * 该语言的**短名**（v1.4 需求 1.1.1 / 6.4）：设置页右上角语言按钮上显示的就是它。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 为什么单独出这个方法、而不复用 {@link #nativeName()}
     *
     * 语言按钮已经按 NFR-03 缩到 32dp 高、内边距压到 4dp/1.6dp（需求 6.4 要求再减 60%），
     * 「简体中文」四个汉字在这个尺寸里放不下（会被 MaterialButton 挤成换行/省略号）。
     * 短名三个字符以内，与胶囊高度同量级，是唯一能稳住的写法。
     *
     * {@link #SYSTEM} 返回的是**解析后**那个具体语言的短名 —— 按钮要表达的是
     * 「当前页面实际在用哪种语言」，而不是「配置里写的是 system 这个字符串」。
     * 完整的「跟随系统 · 简体中文」语义由 {@code contentDescription} 承担
     * （见 SettingsActivity#applyStaticTexts），可见文字只给短名。
     * ─────────────────────────────────────────────────────────────────────
     */
    public String nativeShortName() {
        switch (resolve()) {
            case ZH_TW:
                return "繁中";
            case EN:
                return "EN";
            case ZH_CN:
            default:
                return "简中";
        }
    }

    /**
     * 在**三语平行表**里的列下标（v1.4 §FR-03 规则 2 的预览示例字幕用）。
     *
     * <p>列序固定为 {@code 0 = 简体中文 / 1 = 繁體中文 / 2 = English}，与
     * {@code SettingsActivity.PREVIEW_LINES} 那张 {@code [行][列]} 表的列序、
     * 以及翻译表 xlsx 的列序一致（三处必须同序，改一处就要改三处）。
     *
     * <p>{@link #SYSTEM} 先 {@link #resolve()} 再取列 —— 「跟随系统」不是第四种文案。
     */
    public int colIndex() {
        switch (resolve()) {
            case ZH_TW:
                return 1;
            case EN:
                return 2;
            case ZH_CN:
            default:
                return 0;
        }
    }
}
