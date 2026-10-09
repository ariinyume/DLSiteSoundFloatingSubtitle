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

import io.github.ariinyume.dlsitesoundfloat.config.RemoteConfig;

/**
 * 诊断日志闸门（2.2.14 / code 993 起）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么要有这一层
 *
 * 2.2.7（code 979）已经把「诊断日志默认关」这件事定了口径，但闸门只落在
 * {@code PlayerSourceHook} / {@code PlayerPositionHook} 两处 —— 其余文件里的
 * 逐句 / 逐帧 / 每次采样的诊断行仍然常开，长时间播放会把 LSPosed 日志页刷满
 * （状态栏字幕、悬浮窗滚动、按钮几何、播放态横跳是主要来源）。
 *
 * 本类把闸门抽成**一个公共出口**，让「里程碑 / 告警」与「诊断」两类日志分成两条路：
 *
 *   · 里程碑 / 告警：照旧走 {@link XposedCompat#log(String)}，**常开**；
 *   · 诊断级：走 {@link #debug(String)}，仅在设置页「其他 → 调试日志」打开时输出。
 *
 * ⚠️ 铁律（别踩）：
 *   1. 本类只改「打多少日志」，**不参与任何功能判定** —— 判定逻辑一行不动；
 *   2. 关闸时连字符串拼接都不做（调用点请把拼接放在 {@link #debug} 入参里，
 *      或在热路径先用 {@link #enabled()} 早退），避免「关了还费劲拼串」；
 *   3. 关键取证锚点**必须留在常开那一侧**：{@code >>> track changed}、
 *      钩子挂载 / channel ready、{@code Loaded N cues}、状态跃迁、全部 WARN / failed。
 *      「诊断」指的是那种「正常播放就会持续产生、只在深挖算法细节时才有用」的行。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class LogGate {

    private LogGate() {
    }

    /** 调试日志是否开启。语义同 {@link RemoteConfig#debugLog()}（热路径友好，volatile 读）。 */
    public static boolean enabled() {
        return RemoteConfig.debugLog();
    }

    /**
     * 打一条**诊断级**日志（自带模块内部 tag 前缀，形如 {@code [DLsiteSoundFloat:Button] xxx}）。
     *
     * @param tag 调用点的 {@code TAG} 常量（含 {@code [DLsiteSoundFloat:xxx]} 前缀）
     */
    public static void debug(String tag, String msg) {
        if (!RemoteConfig.debugLog()) {
            return;
        }
        XposedCompat.log(tag + " " + msg);
    }

    /**
     * 打一条**诊断级**日志（消息自带前缀，供 {@code [DLsiteSoundFloat] …} 形态的调用点使用）。
     */
    public static void debug(String msg) {
        if (!RemoteConfig.debugLog()) {
            return;
        }
        XposedCompat.log(msg);
    }
}
