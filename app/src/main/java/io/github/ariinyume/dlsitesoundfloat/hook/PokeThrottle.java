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
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ariinyume.dlsitesoundfloat.hook;

/**
 * 【code 997 第 5 批重构】结构事件探针的**节流判定内核**（零状态纯函数）。
 *
 * ====================================================================
 * 为什么是「纯内核」而不是「整体搬出一个类」
 * ====================================================================
 *
 * <p>取证（`work_diag_996/996-第4批重构-取证报告.md` §2.2 + `docs/静态字段归属图.md`）：
 * 结构探针组共 12 个字段，其中 10 个的读写点纯聚在本方法对应的
 * {@code pokeStructureChanged} 里 —— 但**重置点不行**：
 * <ul>
 *   <li>{@code ensureButton}（220 行）清了 {@code sPokeLogged}，而该方法**碰 7 个组**；</li>
 *   <li>{@code removeButton}（60 行）清了 5 个探针字段，而该方法**碰 4 个组**。</li>
 * </ul>
 * ⇒ 这两个是**跨组枢纽**，字段搬过去就必然要跨类访问别人的状态（状态分裂）。
 * ⇒ 所以本类**只收判定**，状态与重置点全部留在 {@link ActivityButtonHook}。
 *
 * <p>⚠️ 与 G8（{@code ScopeWatcher}）的区别：G8 的跨组字段数是 **0**，所以能整体搬；
 * 本组不是 —— 这正是「行数不是边界，共享字段才是」的又一例（见铁律 46/47）。
 *
 * ====================================================================
 * 抽出来的实际收益
 * ====================================================================
 *
 * <p>① 这段节流逻辑（爆发检测 + 合并闸 + 令牌桶）**首次可被单测覆盖** ——
 * 它以前嵌在 60 行的钩子回调里，只能靠真机日志事后复盘；
 * ② 「三道闸」的**顺序**从此显式化（顺序错了就是行为变更，而这是本项目踩过多次的坑）。
 *
 * ====================================================================
 * 三道闸的语义（逐字照抄抽取前的实现，未改任何阈值与时序）
 * ====================================================================
 *
 * <ol>
 *   <li><b>合并闸</b>：距上次**真正放行**不足 {@code minIntervalMs} ⇒ 合并掉。
 *       转场时事件成千上万，必须合并。</li>
 *   <li><b>爆发起点</b>：相邻事件间隔都在 {@code burstGapMs} 内 ⇒ 视为同一串爆发，
 *       起点取该串的**第一次**触发时刻（供 {@code latencySuffix()} 报端到端延迟）。</li>
 *   <li><b>令牌桶</b>：**只在「上一事件距今 &lt; {@code quietBypassMs}」时**才生效
 *       （即确属持续动画）；安静期后的第一个事件**永远放行** ——
 *       因为那正是用户点击引发的切换，是最该立刻看的那一刻。</li>
 * </ol>
 *
 * <p>⚠️ 两个**极易改错**的顺序细节（抽取时逐行核对过）：
 * <ul>
 *   <li>{@code lastEventMs} 在**合并闸之前**就更新 —— 即使这一次被合并掉，
 *       爆发检测也要看到它。挪到闸后会让长转场的爆发起点被反复重置。</li>
 *   <li>被合并闸拦下时，**令牌桶的窗口状态一律不动**（原实现的 return 在桶之前）。</li>
 * </ul>
 */
final class PokeThrottle {

    /**
     * 判定结果 —— 把「新状态」与「放行与否」一起交回调用方。
     *
     * <p>刻意做成**不可变**：调用方只能把字段读出去写回自己的静态字段，
     * 不能在内核里留下任何引用（保证零状态）。
     */
    static final class Decision {
        /** 是否通过了**合并闸**（第一道）。false ⇒ 调用方应立刻返回，且**不要**跑副作用。 */
        final boolean passedMerge;
        /** 最终是否放行（过了三道闸）。仅当 {@link #passedMerge} 为 true 时才有意义。 */
        final boolean allow;
        /** 新的爆发起点（未变则原样返回）。 */
        final long burstStartMs;
        /** 新的事件时间戳（**无条件**更新，含被合并闸拦下的那些）。 */
        final long lastEventMs;
        /** 新的「上次真正放行」时刻（未放行则不变）。 */
        final long lastPokeMs;
        /** 新的令牌桶窗口起点（仅在过合并闸且非 quietBypass 时可能变）。 */
        final long windowStartMs;
        /** 新的窗口内计数（同上）。 */
        final int windowCount;
        /** 本次是否走了「安静期直通」（true ⇒ 令牌桶整体跳过）。 */
        final boolean quietBypass;

        Decision(boolean passedMerge, boolean allow, long burstStartMs, long lastEventMs,
                 long lastPokeMs, long windowStartMs, int windowCount, boolean quietBypass) {
            this.passedMerge = passedMerge;
            this.allow = allow;
            this.burstStartMs = burstStartMs;
            this.lastEventMs = lastEventMs;
            this.lastPokeMs = lastPokeMs;
            this.windowStartMs = windowStartMs;
            this.windowCount = windowCount;
            this.quietBypass = quietBypass;
        }
    }

    /**
     * 跑一遍三道闸。**无副作用、无状态**。
     *
     * <p>调用方必须按此顺序使用结果（这是与抽取前一致的关键）：
     * <pre>{@code
     * PokeThrottle.Decision d = PokeThrottle.decide(now, ...);
     * sPokeBurstStartMs = d.burstStartMs;
     * sLastPokeEventMs  = d.lastEventMs;
     * if (!d.passedMerge) return;            // ① 合并闸
     * maybeStartPageFollow();                 // ← 副作用就插在这个缝里
     * sPokeWindowStartMs = d.windowStartMs;   // ② 令牌桶
     * sPokeWindowCount   = d.windowCount;
     * if (!d.allow) return;
     * sLastPokeMs = d.lastPokeMs;             // ③ 放行
     * }
     * </pre>
     *
     * @param now            当前时刻（{@code SystemClock.uptimeMillis()}）
     * @param lastEventMs    上次事件时刻（0 = 从未有过 ⇒ 视为「很久以前」）
     * @param burstStartMs   当前爆发起点（0 = 无）
     * @param lastPokeMs     上次真正放行时刻
     * @param windowStartMs  令牌桶窗口起点
     * @param windowCount    窗口内已计数
     * @param burstGapMs     爆发判定间隔（{@code POKE_BURST_GAP_MS}）
     * @param quietBypassMs  安静期阈值（{@code POKE_QUIET_BYPASS_MS}）
     * @param minIntervalMs  合并闸阈值（{@code POKE_MIN_INTERVAL_MS}）
     * @param windowMs       令牌桶窗口长度（{@code POKE_WINDOW_MS} = 1000）
     * @param maxPerSec      窗口内允许次数（{@code POKE_MAX_PER_SEC}）
     * @return 判定结果，永不为 null
     */
    static Decision decide(long now,
                           long lastEventMs, long burstStartMs, long lastPokeMs,
                           long windowStartMs, int windowCount,
                           long burstGapMs, long quietBypassMs, long minIntervalMs,
                           long windowMs, int maxPerSec) {
        // ── 爆发检测（在合并闸之前 —— 顺序不可挪，见类头）──
        final long gap = lastEventMs == 0L ? Long.MAX_VALUE : (now - lastEventMs);
        long newBurstStart = burstStartMs;
        if (burstStartMs == 0L || gap > burstGapMs) {
            newBurstStart = now;
        }
        final boolean quietBypass = gap > quietBypassMs;
        final long newLastEvent = now;

        // ── ① 合并闸 ──
        if (now - lastPokeMs < minIntervalMs) {
            // ⚠️ 窗口状态原样带回：原实现的 return 在令牌桶**之前**。
            return new Decision(false, false, newBurstStart, newLastEvent,
                    lastPokeMs, windowStartMs, windowCount, quietBypass);
        }

        // ── ② 令牌桶（安静期直通时整体跳过）──
        long newWindowStart = windowStartMs;
        int newWindowCount = windowCount;
        if (!quietBypass) {
            if (now - windowStartMs >= windowMs) {
                newWindowStart = now;
                newWindowCount = 0;
            }
            if (newWindowCount >= maxPerSec) {
                return new Decision(true, false, newBurstStart, newLastEvent,
                        lastPokeMs, newWindowStart, newWindowCount, quietBypass);
            }
            newWindowCount++;
        }

        // ── ③ 放行 ──
        return new Decision(true, true, newBurstStart, newLastEvent,
                now, newWindowStart, newWindowCount, quietBypass);
    }

    private PokeThrottle() {
    }
}