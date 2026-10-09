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
package io.github.ariinyume.dlsitesoundfloat.hook;


/**
 * 【code 995】测试访问桥：把 {@code hook} 包的 package-private 判定层暴露给测试。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么需要它
 *
 * {@code PageFollowPolicy} / {@code CapsuleGeometry} / {@code AnchorDeadPolicy}
 * 刻意声明为 package-private —— 它们是 {@code ActivityButtonHook} 的**内部实现**，
 * 不该成为模块对外 API 的一部分（对外暴露的只有 {@code ConfigBus} 等广播面）。
 *
 * 但测试类放在根包 {@code io.github.ariinyume.dlsitesoundfloat}（JUnit 的惯例位置），
 * 就跨包访问不到了。三种解法：
 * <ol>
 *   <li>把三个内核改成 public —— ❌ 扩大了对外 API 表面，破坏原设计意图；</li>
 *   <li>把测试塞进 {@code hook} 包 —— 可行，但会让测试跟着实现搬家，且与
 *       {@code Protocol} 等其他被测类的包结构割裂；</li>
 *   <li><b>本桥类</b> —— 唯一入口、零透传逻辑、只转发调用。✅</li>
 * </ol>
 *
 *⚠️ 铁律：<b>本类只许加方法，不许写任何逻辑。</b>
 *   一旦在这里写了 if/switch，三个内核就重新长出了状态，等于白抽。
 *   每个方法体都应当是单行 return。
 */
public final class TestAccessBridge {

    private TestAccessBridge() {
    }

    // ── PageFollowPolicy ───────────────────────────────────────────────

    public static boolean isPageHeld(boolean playerConfirmedInSession,
            long lastPageMotionMs, long now,
            long heldOffsetMs, int heldOffset, boolean holdProbeStillAttached,
            int pageVisualOffset,
            int screenW, int screenH, float anchorAreaRatio,
            long pageMotionHoldMs, long holdSnapshotMs,
            int pageHoldPx, float pageHoldMinArea) {
        return PageFollowPolicy.isPageHeld(playerConfirmedInSession, lastPageMotionMs, now,
                heldOffsetMs, heldOffset, holdProbeStillAttached, pageVisualOffset,
                screenW, screenH, anchorAreaRatio, pageMotionHoldMs, holdSnapshotMs,
                pageHoldPx, pageHoldMinArea);
    }

    public static boolean reportsStill(boolean following, int followStill, int followStillFrames) {
        return PageFollowPolicy.reportsStill(following, followStill, followStillFrames);
    }

    public static int clampOffset(int delta, int screenH, float sanityRatio) {
        return PageFollowPolicy.clampOffset(delta, screenH, sanityRatio);
    }

    // ── CapsuleGeometry ────────────────────────────────────────────────

    public static int clampRight(int wantRight, int screenW, int btnW, int edgePadPx) {
        return CapsuleGeometry.clampRight(wantRight, screenW, btnW, edgePadPx);
    }

    public static int clampBottom(int wantBottom, int screenH, int btnH, int edgePadPx) {
        return CapsuleGeometry.clampBottom(wantBottom, screenH, btnH, edgePadPx);
    }

    // ── AnchorDeadPolicy ───────────────────────────────────────────────

    public static boolean confirmWindow(
            boolean anchorGone,
            long pageStillMs,
            long goneStillThresholdMs,
            boolean hasFreshEvidence,
            long withEvidenceMs,
            long goneMs,
            long minMs,
            long deadSinceMs,
            long lastOtherSeenMs,
            long lastPageMotionMs) {
        return AnchorDeadPolicy.confirmWindow(anchorGone, pageStillMs, goneStillThresholdMs,
                hasFreshEvidence, withEvidenceMs, goneMs, minMs,
                deadSinceMs, lastOtherSeenMs, lastPageMotionMs) != null;
    }

    public static boolean confirmWindowRaw(
            boolean anchorGone,
            long pageStillMs,
            long goneStillThresholdMs,
            boolean hasFreshEvidence,
            long withEvidenceMs,
            long goneMs,
            long minMs,
            long deadSinceMs,
            long lastOtherSeenMs,
            long lastPageMotionMs) {
        return AnchorDeadPolicy.confirmWindow(anchorGone, pageStillMs, goneStillThresholdMs,
                hasFreshEvidence, withEvidenceMs, goneMs, minMs,
                deadSinceMs, lastOtherSeenMs, lastPageMotionMs) != null;
    }

    /**
     * 把package-private 的 {@code AnchorDeadPolicy.Window} 摊平成可断言的四个字段。
     *
     * <p>本桥同样只做搬运，不做任何换算 —— 避免测试读到的是「桥算出来的值」，
     * 而不是内核真正吐出的值。
     */
    public static final class Win {
        public final long needMs;
        public final long sinceMs;
        public final boolean goneStill;
        public final boolean hasEvidence;

public Win(long needMs, long sinceMs, boolean goneStill, boolean hasEvidence) {
            this.needMs = needMs;
            this.sinceMs = sinceMs;
            this.goneStill = goneStill;
            this.hasEvidence = hasEvidence;
        }
    }

    public static Win window(
            boolean anchorGone,
            long pageStillMs,
            long goneStillThresholdMs,
            boolean hasFreshEvidence,
            long withEvidenceMs,
            long goneMs,
            long minMs,
            long deadSinceMs,
            long lastOtherSeenMs,
            long lastPageMotionMs) {
        AnchorDeadPolicy.Window w = AnchorDeadPolicy.confirmWindow(anchorGone, pageStillMs,
                goneStillThresholdMs, hasFreshEvidence, withEvidenceMs, goneMs, minMs,
                deadSinceMs, lastOtherSeenMs, lastPageMotionMs);
        return w == null ? null : new Win(w.needMs, w.sinceMs, w.goneStill, w.hasEvidence);
    }

    public static boolean shouldHide(long deadFor, long need, int samples,
                              boolean goneStill, int minSamples) {
        return AnchorDeadPolicy.shouldHide(deadFor, need, samples, goneStill, minSamples);
    }
}