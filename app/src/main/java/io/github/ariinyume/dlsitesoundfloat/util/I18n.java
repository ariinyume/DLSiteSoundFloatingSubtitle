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
package io.github.ariinyume.dlsitesoundfloat.util;

import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * 【code 954】模块**对外展示文字**的多语言（Ari 2026-09-26 需求）。
 *
 * 需求原话对应关系：
 * <pre>
 *   系统语言 = 简体中文 / 中文（简体）                          → 文字**不变**（现状）
 *   系统语言 = 繁體中文 / 中文（繁體）/ 繁體中文（中國香港）/
 *              繁體中文（中國台灣）/ 繁體中文（香港）/ 繁體中文（台灣） → 繁体替换
 *   其它（非中文）                                          → 英文替换
 * </pre>
 * 替换表（按钮 / 悬浮窗）：
 * <pre>
 *   状态栏 开/关  →  狀態欄 開/關  →  Status ON / Status OFF
 *   悬浮窗 开/关  →  懸浮窗 開/關  →  Popup ON / Popup OFF
 *   无字幕（按钮）→  無字幕        →  No Sub
 *   无字幕（悬浮窗占位）→ 無字幕   →  No Subtitles
 * </pre>
 *
 * ── 为什么写死在代码里、不用 res/values 资源 ──
 * 这些文字是**注入到宿主进程里的按钮/悬浮窗**用的，而注入视图的 {@code Context} 是
 * **DLsiteSound 的 Context**（SystemUI 侧则是 SystemUI 的 Context）—— 用
 * {@code ctx.getString(R.string.x)} 取到的是**宿主 App 的资源**，不是本模块的；
 * 想拿本模块资源还得 {@code createPackageContext(模块包名, …)}，多一条会失败的链路。
 * 一次性写死的字符串最稳，也不受宿主语言资源影响。
 *
 * ── 取「系统语言」而不是「宿主 App 语言」──
 * 需求说的是「系统语言」，所以走 {@link Resources#getSystem()} 的系统配置；
 * 被宿主/系统改过 per-app 语言（Android 13+ 的「应用语言」）也不会串味。
 * 读不到时退回 {@link Locale#getDefault()}，再读不到就按**简体**处理（= 保持历史行为）。
 *
 * ⚠️ 语言在进程内**只判定一次**（{@link #lang()} 惰性缓存）：切换系统语言会让 App
 * 重建、模块随进程重来，所以不需要热更新；这样也免得每帧读 Configuration。
 */
public final class I18n {

    /** 展示语言：简体 / 繁体 / 英文（非中文一律英文）。 */
    public enum Lang {
        HANS, HANT, EN
    }

    private static volatile Lang sLang = null;

    private I18n() {
    }

    /** 当前展示语言（惰性判定一次）。 */
    public static Lang lang() {
        Lang l = sLang;
        if (l == null) {
            l = detect();
            sLang = l;
        }
        return l;
    }

    private static Lang detect() {
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
        return locale == null ? Lang.HANS : from(locale);
    }

    /**
     * 由 locale 判定语言（独立出来便于自测/排查）。
     *
     * 判据顺序（覆盖需求列出的六种繁体写法 + 简体的两种写法）：
     *   ① 语言不是 zh → 英文；
     *   ② 带 script（Android 7+ 的 BCP-47 写法）：{@code Hant} → 繁体、{@code Hans} → 简体；
     *   ③ 没有 script 时按地区推：TW / HK / MO → 繁体；
     *   ④ 其余（{@code zh} / {@code zh-CN} / {@code zh-SG} / {@code zh-MY}）→ 简体。
     * 说明：Android 的「繁體中文」实际会报成 {@code zh-Hant-TW} 这类带 script 的标签，
     * 而部分 ROM 只给 {@code zh-TW} / {@code zh-HK}，所以 ②③ 两道都要有。
     */
    public static Lang from(Locale locale) {
        if (locale == null) {
            return Lang.HANS;
        }
        String language = locale.getLanguage();
        if (language == null || !"zh".equalsIgnoreCase(language.trim())) {
            return Lang.EN;
        }
        String script = locale.getScript();
        if (script != null && script.length() > 0) {
            if ("hant".equalsIgnoreCase(script)) {
                return Lang.HANT;
            }
            if ("hans".equalsIgnoreCase(script)) {
                return Lang.HANS;
            }
        }
        String country = locale.getCountry();
        if (country != null) {
            String c = country.trim().toUpperCase(Locale.ROOT);
            if ("TW".equals(c) || "HK".equals(c) || "MO".equals(c)) {
                return Lang.HANT;
            }
        }
        return Lang.HANS;
    }

    private static boolean hant() {
        return lang() == Lang.HANT;
    }

    private static boolean en() {
        return lang() == Lang.EN;
    }

    // ── 状态栏字幕开关（胶囊左钮）──────────────────────────────────────────
    public static String statusOn() {
        return en() ? "Status ON" : (hant() ? "狀態欄 開" : "状态栏 开");
    }

    public static String statusOff() {
        return en() ? "Status OFF" : (hant() ? "狀態欄 關" : "状态栏 关");
    }

    // ── 悬浮窗开关（胶囊右钮）──────────────────────────────────────────────
    public static String floatingOn() {
        return en() ? "Popup ON" : (hant() ? "懸浮窗 開" : "悬浮窗 开");
    }

    public static String floatingOff() {
        return en() ? "Popup OFF" : (hant() ? "懸浮窗 關" : "悬浮窗 关");
    }

    /** 按钮里的「无字幕」（三态之一）。 */
    public static String noSub() {
        return en() ? "No Sub" : (hant() ? "無字幕" : "无字幕");
    }

    /** 悬浮窗面板里的「无字幕」占位。 */
    public static String noSubtitles() {
        return en() ? "No Subtitles" : (hant() ? "無字幕" : "无字幕");
    }
}
