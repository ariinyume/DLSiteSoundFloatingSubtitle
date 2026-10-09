/*
 * DLsiteSound Floating Subtitle - Xposed module for DLsite Sound
 * Copyright (C) 2026 ariinyume
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
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
 * 字幕开关胶囊的**几何计算内核**（从 {@code ActivityButtonHook} 抽出，2.2.14 / code 993 起）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么要抽这一块
 *
 * 胶囊落位是这个模块里**最容易出「差半个字 / 差半行」残差**的地方 ——
 * 923→937 五轮返工全在这几十行算术里（垂直居中口径两次改、滑条净距钳制、
 * 简介底边逐帧翻转去抖）。但它原先埋在 {@code ActivityButtonHook} 的 4,780 行里、
 * 与视图生命周期、页面跟随状态机、作用域探测混在同一个类，
 * **既无法单测，也无法在不启动宿主的情况下验算**。
 *
 * 本类把「给定几何输入 → 算出胶囊落位」这段算术**原样搬出**，不改一行口径。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ⚠️ 铁律（抽取时的取证结论，改本类前必读）
 *
 * 1. **本类必须保持无状态、可单测**。原先两个方法都**不是**纯函数 ——
 *    `capsuleBottomForSlider` 里有 7 处静态字段写入（去抖状态 `sDescBottomStable`、
 *    胶囊高 `sCapsuleHPx` ×2、调用计数 `sCbCallCount`、打点快照 ×2），
 *    且它调的 `dip2px → capsuleDip → capsuleMetricsDensity` 会**懒锁定并写**
 *    `sCapsuleMetricsDensity` 与 `sDensityPx`。
 *    ⇒ 那些副作用**必须留在调用方**，通过入参 / 出参传递，本类一行状态都不持有。
 *    （这是「先取证再动手」的典型：看着像纯函数，抠下去全是状态。）
 *
 * 2. **所有 dp→px 一律由调用方算好后传进来**。本类只做整数算术，
 *    不碰 {@code Context}、不碰 {@code Resources}、不碰密度锁定 ——
 *    这样「真机密度不同（App 2.9688 / SystemUI 3.0）」这件事在单测里可以直接喂数字。
 *
 * 3. **不碰任何日志**。日志（含节流打点）留在 {@code ActivityButtonHook}：
 *    那是「调用即打 + 每 60 次汇总」的可观测性口径，与本类的算术无关。
 *
 * 4. 口径与原实现**逐行一致**，包括注释里那几处「实测 1722/1772 → 1794」的历史取证。
 *    任何改动都必须回看原注释，别凭直觉「优化」。
 * ─────────────────────────────────────────────────────────────────────
 */
final class CapsuleGeometry {

    private CapsuleGeometry() {
    }

    /**
     * 简介底边去抖后的结果 + 本轮落位所需的全部诊断量。
     *
     * <p>这些原本靠返回值带不出来（因为要塞进日志），抽出来后作为出参显式传回，
     * 行为与日志内容都与抽取前逐字一致。
     */
    static final class BottomResult {
        /** bottomMargin（px），已过安全钳制 —— 直接写进 {@code LayoutParams.bottomMargin}。 */
        final int bottomMargin;
        /** 胶囊高（px）；调用方需写回 {@code sCapsuleHPx}。 */
        final int capsuleHPx;
        /** 去抖后采信的简介底边（px）；日志诊断量。-1 = 未采信（走了 LEGACY 分支）。 */
        final int descStable;
        /** 滑条视图顶边（px）；日志诊断量。-1 = 未采信。 */
        final int sliderTop;
        /** 实际落位的中心 y；日志诊断量。 */
        final int centerY;
        /** 是否走了「对齐滑条顶边」分支（false = LEGACY 兜底）。 */
        final boolean usedMid;
        /** 是否触发了滑条最小净距钳制。 */
        final boolean clamped;

        BottomResult(int bottomMargin, int capsuleHPx, int descStable, int sliderTop,
                     int centerY, boolean usedMid, boolean clamped) {
            this.bottomMargin = bottomMargin;
            this.capsuleHPx = capsuleHPx;
            this.descStable = descStable;
            this.sliderTop = sliderTop;
            this.centerY = centerY;
            this.usedMid = usedMid;
            this.clamped = clamped;
        }
    }

    /**
     * 【code 923 几何 4】胶囊**底边**（bottomMargin）。
     *
     * <p>垂直居中于「简介行底边」与「滑条中心」之间 ——
     * 需求原话：「在一行淡色小字简介和播放进度条中间（垂直距离中间，不再用固定距离）」。
     * <ul>
     *   <li>按钮组中心 y = (简介行底边 + 滑条中心) / 2</li>
     *   <li>底边屏幕 y   = 中心 + 半高</li>
     *   <li>bottomMargin = screenH - 底边屏幕 y</li>
     * </ul>
     *
     * ⚠️ 取不到简介行时退回旧口径（底边落在「滑条中心 - gap」）——
     * 位置不精确可以接受，**因为没位置而跳一下**不可以（1.21.10 的教训）。
     *
     * @param screenH           屏幕高（px）
     * @param sliderCy          最近一次扫描到的主滑条中心 y；{@code <=0} 表示未知
     * @param sliderH           滑条视图高（px）；{@code <=0} 时按 {@code fallbackSeekHalfPx} 半高算
     * @param descBottomY       简介行底边 y；{@code <=0} 表示取不到
     * @param prevDescStable    上一次去抖后采信的简介底边（px）；{@code <=0} 表示还没采信过
     * @param gapPx             {@code CAPSULE_ABOVE_SLIDER_DP} 的 px 值（LEGACY 分支用）
     * @param capsuleHPx        胶囊高 px（{@code CAPSULE_H_DP} 的 px 值）
     * @param aboveSliderTopPx  {@code CAPSULE_ABOVE_SLIDER_TOP_DP} 的 px 值
     * @param minClearPx        {@code SLIDER_MIN_CLEAR_DP} 的 px 值
     * @param descHysteresisPx  {@code DESC_HYSTERESIS_DP} 的 px 值
     * @param fallbackSeekHalfPx 滑条高未知时的半高兜底（px）
     * @param edgePadPx         安全钳制用的屏幕边距（px）
     * @return 落位结果；{@code sliderCy <= 0} 时返回 {@code null}（调用方保持现状）
     */
    static BottomResult capsuleBottomForSlider(
            int screenH, int sliderCy, int sliderH, int descBottomY, int prevDescStable,
            int gapPx, int capsuleHPx, int aboveSliderTopPx, int minClearPx,
            int descHysteresisPx, int fallbackSeekHalfPx, int edgePadPx) {
        if (screenH <= 0 || sliderCy <= 0) {
            return null;
        }

        // 【code 937 问题2】两个诊断量提到块外：日志要打「去抖后的简介底边」与
        //   「滑条视图顶边」，否则无法判断按钮上沿有没有压到简介行。
        int descStable = -1;
        int sliderTop = -1;
        int centerY;
        boolean usedMid;

        // 【code 934 bug3】按钮压简介根修：sliderCy 是滑条**中心**。简介底边(1722)到滑条
        //   视图顶部(1772)只有 50px，装不下 95px 整高按钮 -> 旧条件 gap>capsuleH 不成立
        //   -> 走 LEGACY 整高贴 cy-32dp-半高 -> 按钮占1610~1704、整个压进简介区（截图实证）。
        //   新规则：简介行有效时一律对齐「简介底边 <-> 滑条视图顶部」；
        //   两侧 view 的内边距区吸收少量重叠。
        if (descBottomY > 0) {
            // 【code 936 bug2】简介底边在真机上于多个候选间**逐帧翻转**
            //（00:30 日志实证 1722 <-> 1759，差 37px）-> centreY跟着每秒抖十几次 18px。
            //   此处做**消费端**去抖（探测层按铁律只如实上报）：
            //   近距离抖动一律收敛到「更靠上」的候选（可用带更宽、离进度条更远），
            //   只有明显位移（> DESC_HYSTERESIS_DP）才认作真的换行并跟随。
            descStable = descBottomY;
            if (prevDescStable > 0 && Math.abs(descStable - prevDescStable) <= descHysteresisPx) {
                descStable = Math.min(descStable, prevDescStable);
            }

            int seekHalf = sliderH > 0 ? sliderH / 2 : fallbackSeekHalfPx;
            sliderTop = sliderCy - seekHalf;
            // 【code 936 bug2】Ari 明确要求：按钮**恒定 32dp**、无论可用带多窄都强制
            //   对齐「简介底边 <-> 滑条视图顶边」，**不做任何自适应压缩** —— 935 的
            //   自适应把95px 压到 60px（日志实证 capsuleH=60），观感就是「按钮被压扁」。
            //   实测本页可用带仅 50px（desc=1722 / sliderTop=1772）< 32dp=95px，故剩余
            //   45px 溢出由上下两侧的 view 内边距区均摊（各约 22px）。
            //
            // 【code 937 问题2 根修（Ari 指令）；938 改为 - 20dp】底边强制 = 滑条视图顶边 - 20dp。
            //   936 的旧口径「简介底边(1722) <-> 滑条顶边(1772) 取中点」⇒ centerY=1747、
            //   底边 1794 —— 比滑条顶 1772 **还低 23px**，按钮直接压进进度条，
            //   正是 Ari 反馈的「按钮太低了、完全靠近播放进度条」。
            //   新口径与简介底边解耦，位置只跟滑条走（也顺手甩掉了 descBottom 逐帧翻转的影响）。
            centerY = sliderTop - aboveSliderTopPx - capsuleHPx / 2;
            usedMid = true;
        } else {
            centerY = sliderCy - gapPx - capsuleHPx / 2;
            usedMid = false;
        }

        // 【code 932 bug4】滑条最小净距钳制。实测（18:06 日志）无字幕时 desc=2017 /
        //   slider=2179，MID 居中后按钮底边距滑条中心仅 ~34px -> 按钮怼到进度条顶上。
        //   规则：按钮底边距滑条中心不得小于 SLIDER_MIN_CLEAR_DP，不够就整体上移。
        boolean clamped = false;
        // 【code 934】净距钳制只对 LEGACY 分支生效：MID 已按「滑条视图顶部」对齐，
        //   再套 minClear(20dp、相对滑条中心) 会把按钮重新顶回简介区
        //   （932 钳制的副作用，正是本轮「按钮挡简介」的推手之一）。
        if (!usedMid) {
            if (centerY + capsuleHPx / 2 > sliderCy - minClearPx) {
                centerY = sliderCy - minClearPx - capsuleHPx / 2;
                clamped = true;
            }
        }

        int bottomMargin = screenH - (centerY + capsuleHPx / 2);
        // 安全钳制：别把按钮推到屏幕外（分屏 / 极矮屏 / 滑条贴顶）。
        int minBottom = edgePadPx;
        int maxBottom = Math.max(minBottom, screenH - capsuleHPx - edgePadPx);
        if (bottomMargin < minBottom) {
            bottomMargin = minBottom;
        }
        if (bottomMargin > maxBottom) {
            bottomMargin = maxBottom;
        }
        return new BottomResult(bottomMargin, capsuleHPx, descStable, sliderTop,
                centerY, usedMid, clamped);
    }

    /**
     * 【code 923 几何 3】胶囊组**右缘**对齐主滑条**右缘**。
     *
     * <p>需求原话：「按钮距离屏幕右缘改为和进度条最右端距离屏幕右缘一致」。
     * decor 是全屏宽，所以 {@code rightMargin = screenW - 滑条右缘x} 即可让两者
     * 距屏右的距离**逐像素相等**。
     *
     * <p>取不到滑条（列表页 / 转场瞬间）时退回常量 {@code BUTTON_RIGHT_DP} ——
     * 与 {@link #capsuleBottomForSlider} 同一套「宁可不精确、不要跳」的取舍。
     *
     * @param screenW      屏宽（px）
     * @param sliderRightPx 滑条右缘 x；{@code <=0} 或 {@code >= screenW} 表示取不到
     * @param fallbackRightPx {@code BUTTON_RIGHT_DP} 的 px 值
     * @return rightMargin（px）
     */
    static int capsuleRightForSlider(int screenW, int sliderRightPx, int fallbackRightPx) {
        if (screenW > 0 && sliderRightPx > 0 && sliderRightPx < screenW) {
            int m = screenW - sliderRightPx;
            //  sanity：滑条右缘不可能贴着屏幕左边，超过 1/3 屏宽一定是量错了。
            if (m >= 0 && m <= screenW / 3) {
                return m;
            }
        }
        return fallbackRightPx;
    }

    /**
     * 把「期望右缘」钳进可视区：越界时退回「屏宽 − 按钮宽 − 边距」。
     *
     * <p>原先这段在 {@code showButton} 与 {@code replaceCapsuleByGeometry} 里各写了一遍，
     * 抽取时统一到这里，避免两处口径漂移。
     *
     * @param wantRight   期望右缘
     * @param screenW     屏宽（px）
     * @param btnW按钮宽（px）
     * @param edgePadPx   屏幕边距（px）
     */
    static int clampRight(int wantRight, int screenW, int btnW, int edgePadPx) {
        if (wantRight + btnW > screenW) {
            return Math.max(0, screenW - btnW - edgePadPx);
        }
        return wantRight;
    }

    /**
     * 把「期望底边」钳进可视区：越界时退回「屏高 − 按钮高 − 边距」。
     *
     * @param wantBottom 期望底边（bottomMargin 口径）
     * @param screenH    屏高（px）
     * @param btnH       按钮高（px）
     * @param edgePadPx  屏幕边距（px）
     */
    static int clampBottom(int wantBottom, int screenH, int btnH, int edgePadPx) {
        if (wantBottom + btnH > screenH) {
            return Math.max(0, screenH - btnH - edgePadPx);
        }
        return wantBottom;
    }
}
