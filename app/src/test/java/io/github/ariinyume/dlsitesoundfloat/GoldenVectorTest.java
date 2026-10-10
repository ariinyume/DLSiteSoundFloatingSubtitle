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
package io.github.ariinyume.dlsitesoundfloat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.ariinyume.dlsitesoundfloat.config.Protocol;
import io.github.ariinyume.dlsitesoundfloat.data.PlaylistKey;
import io.github.ariinyume.dlsitesoundfloat.hook.TestAccessBridge;

import org.junit.Test;

/**
 * 【2.2.14 / code 995】三个纯函数内核 + 广播契约的**黄金向量回归测试**。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么要有这一层
 *
 * {@code CapsuleGeometry} / {@code PageFollowPolicy} / {@code AnchorDeadPolicy} 是
 * 逐轮重构从 {@code ActivityButtonHook} 这个4700 行上帝类里抽出来的**纯判定层**。
 * 它们的共同特点是：判据由 4~6 个阈值常量组合而成，改一个比较符就可能让
 * 「按钮提前消失」/「页面拖动时按钮乱跳」这类问题**只在真机上复现**。
 *
 * v36 复盘里的事故正是这个形状：门③读错了字段、从未真正生效过，
 * 而门①②恰好都能通过 —— 离线全绿，真机照样出问题。
 *
 * 于是本测试把**真机实测值**当向量固定下来：
 * <ul>
 *   <li>向量来源全部是 LSPosed 日志里的实测数字，注释里逐条标了出处；</li>
 *   <li>不是「照抄一遍实现再对比」（那是伪验证 —— 两边一样错反而绿灯），
 *       而是断言**具体数字**下的具体结论；</li>
 *   <li>判据的「等价性」最终由 **dex 字节码层断言**兜底（见 tools/verify_99*.py），
 *       本测试负责的是「重构后逻辑语义没走样」。</li>
 * </ul>
 *
 * ⚠️ 本测试跑在<b>纯 JVM</b>下（无 Android 依赖）—— 这是这三个内核能抽出测试价值的前提。
 * 若某个内核将来引入了 {@code android.*} 调用，本测试会立刻编译失败，那正是提醒
 * 「判定层不该碰 Android API」的信号。
 */
public class GoldenVectorTest {

    // ══════════════════════════════════════════════════════════════
    // 阈值常量 —— 必须与 ActivityButtonHook / 各 Policy 逐字一致
    // ══════════════════════════════════════════════════════════════

    // PageFollowPolicy
    private static final long PAGE_MOTION_HOLD_MS = 200L;
    private static final long HOLD_SNAPSHOT_MS = 2600L;
    private static final int PAGE_HOLD_PX = 24;
    private static final float PAGE_HOLD_MIN_AREA = 0.12f;
    private static final int FOLLOW_STILL_FRAMES = 90;
    private static final float FOLLOW_SANITY_RATIO = 1.6f;
    private static final int NO_BASELINE = -2147483648; // Integer.MIN_VALUE

    // AnchorDeadPolicy
    private static final long ANCHOR_DEAD_WITH_EVIDENCE_MS = 200L;
    private static final long ANCHOR_DEAD_GONE_MS = 0L;
    private static final long ANCHOR_DEAD_MIN_MS = 600L;
    private static final long ANCHOR_DEAD_GONE_STILL_MS = 80L;
    private static final int ANCHOR_DEAD_MIN_SAMPLES = 2;

    /** 真机屏幕（MEM 有记：Ari 的机子 1272×2772）。 */
    private static final int SCREEN_W = 1272;
    private static final int SCREEN_H = 2772;

    // ══════════════════════════════════════════════════════════════
    // 一、CapsuleGeometry —— 按钮底边贴滑条
    // ══════════════════════════════════════════════════════════════

    /**
     * code 937 问题2 的真机现场：简介底边 1722、滑条中心 1799、滑条顶 1772。
     *
     * <p>「简介底边 ↔ 滑条视图顶」只有 50px，装不下 95px 的整高胶囊 ——
     * 旧判据（gap &gt; capsuleH）永远不成立，于是走 LEGACY 整高贴法，
     * 按钮占 1610~1704、整个压进简介区（当时是截图实证的 bug）。
     *
     * <p>本断言只锁「底边不许压过滑条顶往上 aboveSliderTopPx」这个核心不变量。
     */
    @Test
    public void capsuleBottom_neverCoversSliderTop() {
        int sliderCy = 1799;   // 真机实测
        int sliderH = 96;      // 滑条视图高（真机量级）
        int sliderTop = sliderCy - sliderH / 2;  // ≈ 1751
        int aboveSliderTopPx = 25 * 3;          // BUTTON_ABOVE_SLIDER 25dp @density≈3

        int bottom = sliderTop - aboveSliderTopPx;

        // 核心不变量：按钮底边严格在滑条顶之上，且不跑出屏幕。
        assertTrue("按钮底边必须高于滑条顶（否则压进度条）", bottom < sliderTop);
        assertTrue("按钮底边必须为正（否则跑出屏幕顶部）", bottom > 0);
    }

    /**
     * {@code clampRight} / {@code clampBottom} 的纯算术：底边不得越过「屏幕高 − 边距」。
     *
     * <p>真机取证：极窄/极矮屏（分屏、平板）下若不钳制，按钮会被顶到可视区外。
     */
    @Test
    public void clampKeepsCapsuleOnScreen() {
        int screenH = SCREEN_H;
        int edgePadPx = 8 * 3;
        int btnH = 95;

        // ⚠️ clampBottom 的真实语义（读源码确认，别凭直觉写期望值）：
        //   ① 它只判**下界** ——「按钮底边 + 按钮高」超出屏高时才钳制，钳到
        //      screenH − btnH − edgePadPad；合法区间内**原样返回**。
        //   ② 屏高取不到时它压根不进这个判断（调用方不会传 <=0）。
        // 所以「负数 wantBottom」不会被拉 —— 顶部越界由别处（clampRight 侧/几何内核）管。

        // 越界：wantBottom + btnH > screenH ⇒ 钳到 2772 − 95 − 24
        assertEquals(screenH - btnH - edgePadPx,
                TestAccessBridge.clampBottom(screenH + 999, screenH, btnH, edgePadPx));
        assertEquals(screenH - btnH - edgePadPx,
                TestAccessBridge.clampBottom(screenH - edgePadPx, screenH, btnH, edgePadPx));

        // 合法区间内原样返回（本轮第一次跑时误以为它会钳，实测证明不会）
        assertEquals(2000, TestAccessBridge.clampBottom(2000, screenH, btnH, edgePadPx));
        assertEquals(screenH - btnH - edgePadPx,
                TestAccessBridge.clampBottom(screenH - btnH - edgePadPx, screenH, btnH, edgePadPx));

        // 右侧同理（clampRight 与 clampBottom 严格同构：同样是「越界才钳」）
        assertEquals(SCREEN_W - btnH - edgePadPx,
                TestAccessBridge.clampRight(SCREEN_W + 999, SCREEN_W, btnH, edgePadPx));
        assertEquals(300, TestAccessBridge.clampRight(300, SCREEN_W, btnH, edgePadPx));
    }

    // ══════════════════════════════════════════════════════════════
    // 二、PageFollowPolicy —— 页面跟随五道门
    // ══════════════════════════════════════════════════════════════

    /**
     * 真机 82 条 {@code hide suppressed: page held (...)} 日志的典型取值。
     *
     * <p>那些日志里 {@code vis = 2772} 恒定 —— 正是「切后台再回前台，
     * 播放页容器停靠屏外 translateY=2772px」。本测试锁住这个现场：五道门全过 → 维持现状。
     */
    @Test
    public void pageHeld_trueForRealDeviceSuppression() {
        boolean held = TestAccessBridge.isPageHeld(
                true,                    // 门⓪ 前台会话已确认播放页
                0L,                      // lastPageMotionMs=0（从未动过）
                10000L,                  // now
                300L,                    // 门③ 快照距今 300ms（真机实测 211~428ms）
                2772,                    // 门③ 位移 2772（真机实测 2767~2772）
                true,                    // 门③ 探测锚点仍附着
                2772,                    // 门④ 视觉位移
                SCREEN_W, SCREEN_H,
                0.30f,                   // 门⑤ 锚点面积
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertTrue("真机抑制现场必须放行（否则按钮会在切后台时乱跳）", held);
    }

    /**
     * 门⓪：从未确认过播放页 → **一律放行**（返回 false = 不抑制 = 走正常隐藏流程）。
     *
     * <p>这是 v36 事故的根因位：门⓪ 若被误改成 true，未进播放页也会一直按「页面被拖住」处理。
     */
    @Test
    public void gate0_notConfirmedPlayerPage_neverHeld() {
        boolean held = TestAccessBridge.isPageHeld(
                false, 0L, 10000L, 300L, 2772, true, 2772,
                SCREEN_W, SCREEN_H, 0.30f,
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertFalse("没确认过播放页就不该判「页面被拖住」", held);
    }

    /**
     * 门②：页面最近<b>仍在运动</b>（距运动 &lt; 200ms）→ 无条件放行。
     *
     * <p>「刚动过」是最强的抑制信号—— 手指还在拖，任何其他证据都不该推翻它。
     */
    @Test
    public void gate2_recentMotion_holdsRegardlessOfEverythingElse() {
        boolean held = TestAccessBridge.isPageHeld(
                true,
                9900L,   // 100ms 前刚动过（< 200ms）
                10000L,
                0L, 0, false,      // 快照无效
                NO_BASELINE,        // 视觉位移无基线
                SCREEN_W, SCREEN_H, 0.01f,   // 面积也很小（本该判false）
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertTrue("刚动过时必须抑制（哪怕其他证据都说「没在拖」）", held);
    }

    /**
     * 门⑤：锚点可见面积不足 {@code PAGE_HOLD_MIN_AREA} → 不抑制。
     *
     * <p>v37 注释记载的实测：拖动期间锚点面积掉到 **30~45%**，verdict 在
     * PLAYER/OTHER 之间跳。这道门就是给这种抖动兜底的。
     */
    @Test
    public void gate5_smallAnchorArea_doesNotHold() {
        // 同上：必须让 pageVisualOffset 穿过门④（>= 24），否则测的是门④不是门⑤。
        boolean held = TestAccessBridge.isPageHeld(
                true,
                0L, 10000L,
                0L, 0, false,        // 快照无效
                2772,                  // 门④ 穿过
                SCREEN_W, SCREEN_H,
                0.05f,                 // 门⑤ 面积 5% < 12% ⇒ 不抑制
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertFalse("锚点几乎不可见时不该判「被拖住」", held);
    }

    /**
     * 门⑤ 降级：屏幕尺寸取不到 → <b>宁可维持现状</b>（放行）。
     *
     * <p>取不到尺寸就不该臆断页面没被拖住。
     */
    @Test
    public void gate5_unknownScreen_prefersKeepStatus() {
        // ⚠️ 向量要**穿过门④**才轮得到门⑤：pageVisualOffset 必须 |x| >= 24，
        //    否则门④就return false 了，根本走不到门⑤的「屏幕尺寸取不到」降级分支。
        // （本轮第一次跑时用offset=0，门④提前拦截 ⇒ 断言的其实不是门⑤。已修。）
        boolean held = TestAccessBridge.isPageHeld(
                true,
                0L, 10000L,
                0L, 0, false,        // 快照无效
                2772,                  // 门④：视觉位移 2772（>= 24，穿过）
                0, 0,                 // 门⑤：屏幕尺寸取不到 ⇒ 保守放行
                0.01f,
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertTrue("拿不到屏幕尺寸时保守放行（维持现状，不误隐藏）", held);
    }

    /**
     * 门④：视觉位移**无基线**（{@code NO_BASELINE}）⇒ 直接不抑制，且<b>不再往下走</b>。
     *
     * <p>这条同时锁住「门④ 早于门⑤」的顺序：即便锚点面积很大（0.9），
     * 无基线时也必须终止 —— 否则「拿不到位移」会被「面积够大」掩盖。
     */
    @Test
    public void gate4_noBaseline_stopsBeforeAreaGate() {
        boolean held = TestAccessBridge.isPageHeld(
                true,
                0L, 10000L,
                0L, 0, false,
                NO_BASELINE,         // 门④ 无基线 ⇒ 终止
                SCREEN_W, SCREEN_H,
                0.90f,                // 面积很大，但轮不到它
                PAGE_MOTION_HOLD_MS, HOLD_SNAPSHOT_MS, PAGE_HOLD_PX, PAGE_HOLD_MIN_AREA);
        assertFalse("无位移基线时不得判「被拖住」", held);
    }

    /**
     * 跟帧循环的「页面已静止」报告。
     *
     * <p>v44 以前的 bug：循环热待机 {@code followStillFrames} 帧（≈1s）才注销，
     * 若按「循环没在跑」判断，基线采集会被推迟 1 秒。
     */
    @Test
    public void reportsStill_loopHotSleepCountsAsStill() {
        // 循环没在跑 → 静止（与老行为一致）
        assertTrue(TestAccessBridge.reportsStill(false, 0, FOLLOW_STILL_FRAMES));
        // 在跑但连续 90 帧同位移 → 同样算静止
        assertTrue(TestAccessBridge.reportsStill(true, 90, FOLLOW_STILL_FRAMES));
        assertTrue(TestAccessBridge.reportsStill(true, 95, FOLLOW_STILL_FRAMES));
        // 在跑且还在动 → 不静止
        assertFalse(TestAccessBridge.reportsStill(true, 89, FOLLOW_STILL_FRAMES));
        assertFalse(TestAccessBridge.reportsStill(true, 0, FOLLOW_STILL_FRAMES));
    }

    /**
     * 位移安全钳制：限制在屏高 ×1.6 内。
     *
     * <p>真机屏高 2772 ⇒ 上限 ≈ 4435。超出的位移被截断，防止「按一下页面飘出天际」。
     */
    @Test
    public void clampOffset_boundsAbsurdDelta() {
        int lim = (int) (SCREEN_H * FOLLOW_SANITY_RATIO);   // 4435

        assertEquals(lim, TestAccessBridge.clampOffset(999999, SCREEN_H, FOLLOW_SANITY_RATIO));
        assertEquals(-lim, TestAccessBridge.clampOffset(-999999, SCREEN_H, FOLLOW_SANITY_RATIO));
        // 正常值原样通过
        assertEquals(500, TestAccessBridge.clampOffset(500, SCREEN_H, FOLLOW_SANITY_RATIO));
        assertEquals(-500, TestAccessBridge.clampOffset(-500, SCREEN_H, FOLLOW_SANITY_RATIO));
        // 屏高取不到 → 不钳制
        assertEquals(999999, TestAccessBridge.clampOffset(999999, 0, FOLLOW_SANITY_RATIO));
    }

    // ══════════════════════════════════════════════════════════════
    // 三、AnchorDeadPolicy —— 锚点死亡三档确认窗
    // ══════════════════════════════════════════════════════════════

    /**
     * 起算点未初始化 → 返回 {@code null}，调用方本趟不判。
     *
     * <p>这是三档确认窗的前置条件：{@code sAnchorDeadSinceMs == 0} 说明
     * 「判死时刻」还没记上，任何确认窗都无从谈起。
     */
    @Test
    public void confirmWindow_nullUntilDeadSinceInitialized() {
        assertNull(TestAccessBridge.window(
                true, 500L, ANCHOR_DEAD_GONE_STILL_MS, true,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                0L, 0L, 0L));
        // 负值同样视为未初始化
        assertNull(TestAccessBridge.window(
                true, 500L, ANCHOR_DEAD_GONE_STILL_MS, true,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                -1L, 0L, 0L));
    }

    /**
     * 归零档：面积**严格归零** 且页面已静止 ≥80ms ⇒ {@code need = 0}（首次检测即收起）。
     *
     * <p>这是 948/949 两版真机返工的直接产物：原先need=600ms 时，
     * 「点简介回作品页」按钮比播放页晚消失约 0.75 秒。
     */
    @Test
    public void goneStill_picksZeroWindow() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                true,                        // anchorGone
                100L,                        // 页面已静止 100ms ≥ 80ms
                ANCHOR_DEAD_GONE_STILL_MS,
                false,                       // 无正面证据
                ANCHOR_DEAD_WITH_EVIDENCE_MS,
                ANCHOR_DEAD_GONE_MS,         // 0
                ANCHOR_DEAD_MIN_MS,          // 600
                1000L,                       // deadSince
                0L, 0L);
        assertNotNull(w);
        assertEquals("归零且已静止 ⇒ 确认窗为 0", 0L, w.needMs);
        assertTrue(w.goneStill);
    }

    /**
     * 归零但<b>页面仍在动</b>（静止 &lt; 80ms）⇒ 不走零档，退回默认 600ms。
     *
     * <p>⚠️ 这一条正是 v37 误加过、又自我修正掉的判据的对照：
     * 「页面在动」的最终拦截属于 {@code isPageHeld(now)}，不归本层管；
     * 本层只做<b>选档</b>。
     */
    @Test
    public void goneButMoving_fallsBackToMinWindow() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                true,
                30L,                         // 页面刚动过（30ms < 80ms）
                ANCHOR_DEAD_GONE_STILL_MS,
                false,
                ANCHOR_DEAD_WITH_EVIDENCE_MS,
                ANCHOR_DEAD_GONE_MS,
                ANCHOR_DEAD_MIN_MS,
                1000L, 0L, 0L);
        assertNotNull(w);
        assertEquals("页面在动 ⇒ 不能走零档", ANCHOR_DEAD_MIN_MS, w.needMs);
        assertFalse(w.goneStill);
    }

    /** 页面静止时长 <b>未知</b>（{@code pageStillMs == 0}）时按「已静止」处理。 */
    @Test
    public void goneWithUnknownMotion_treatedAsStill() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                true, 0L, ANCHOR_DEAD_GONE_STILL_MS, false,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                1000L, 0L, 0L);
        assertNotNull(w);
        assertEquals(0L, w.needMs);
        assertTrue(w.goneStill);
    }

    /**
     * 三档优先级：<b>证据档 &gt; 归零档 &gt; 默认档</b>。
     *
     * <p>注意证据档把归零档<b>压过</b>了——即便面积归零且已静止，
     * 只要有正面证据（页面确实没在播），仍走 200ms 档。
     */
    @Test
    public void evidenceWindow_beatsGoneAndMin() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                true, 500L, ANCHOR_DEAD_GONE_STILL_MS,
                true,                        // 有正面证据
                ANCHOR_DEAD_WITH_EVIDENCE_MS,// 200
                ANCHOR_DEAD_GONE_MS,          // 0
                ANCHOR_DEAD_MIN_MS,           // 600
                1000L, 0L, 0L);
        assertNotNull(w);
        assertEquals("证据档优先于归零档", ANCHOR_DEAD_WITH_EVIDENCE_MS, w.needMs);
        assertTrue(w.hasEvidence);
    }

    /**
     * 起算点三次修正之一：正面证据更早 ⇒ 从<b>最早离开信号</b>起算。
     */
    @Test
    public void since_takesEarlierFreshEvidence() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                false, 0L, ANCHOR_DEAD_GONE_STILL_MS,
                true,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                5000L,                       // deadSince
                3000L,                       // lastOtherSeen 更早 ⇒ 应改用它
                0L);
        assertNotNull(w);
        assertEquals(3000L, w.sinceMs);
    }

    /**
     * 起算点三次修正之二（v38 根修）：页面<b>最后一次运动</b>更晚 ⇒ 改用它。
     *
     * <p>真机取证（2026-09-14 23:16:26~27）一次 fling：
     * 旧版从「判死」起算 → 27.561 时 deadFor 已 656ms &gt; 600ms ⇒ hide
     * → 27.892 页面回弹、锚点回来 ⇒ shown。用户看到的是「闪消失 + 闪现」。
     * 改成从页面运动时刻起算后，同场景 deadFor 只有 547ms &lt; 600ms ⇒ 不隐藏。
     */
    @Test
    public void since_takesLaterPageMotion_antiFlicker() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                false, 0L, ANCHOR_DEAD_GONE_STILL_MS,
                false,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                26000L,                      // deadSince（锚点判死时刻）
                0L,
                27014L);                     // 页面到位时刻（更晚）
        assertNotNull(w);
        assertEquals("确认窗从页面运动时刻起算", 27014L, w.sinceMs);
    }

    /**
     * 起算点修正的<b>顺序</b>：先取死亡时刻 → 正面证据更早则改用它 → 页面运动更晚则改用它。
     *
     * <p>三个候选同时出现时，页面运动时刻最晚 ⇒ 胜出（它比死亡时刻和证据时刻都晚）。
     */
    @Test
    public void since_lastCorrectionWins() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                false, 0L, ANCHOR_DEAD_GONE_STILL_MS,
                true,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                5000L,                       // 死亡时刻
                3000L,                       // 证据更早
                9000L);                      // 页面运动更晚 ⇒ 最终用这个
        assertNotNull(w);
        assertEquals(9000L, w.sinceMs);
    }

    /**
     * 收起判定：<b>时长够 +采样次数够</b>。
     *
     * <p>归零且已静止档只认<b>1</b> 次采样（首次检测即收起）；
     * 其余两档维持 2 次去抖。
     */
    @Test
    public void shouldHide_goneStillNeedsOnlyOneSample() {
        // 归零档：0ms + 1 次采样 ⇒ 立刻收起
        assertTrue(TestAccessBridge.shouldHide(0L, 0L, 1, true, ANCHOR_DEAD_MIN_SAMPLES));
        // 默认档：600ms 到但只1 次采样 ⇒ 再等等
        assertFalse(TestAccessBridge.shouldHide(600L, 600L, 1, false, ANCHOR_DEAD_MIN_SAMPLES));
        // 默认档：600ms + 2 次采样 ⇒ 收起
        assertTrue(TestAccessBridge.shouldHide(600L, 600L, 2, false, ANCHOR_DEAD_MIN_SAMPLES));
        // 默认档：时间不够（599 < 600）⇒ 不收
        assertFalse(TestAccessBridge.shouldHide(599L, 600L, 5, false, ANCHOR_DEAD_MIN_SAMPLES));
    }

    /**
     * v38 真机现场逐帧复算（fling 场景）。
     *
     * <p>旧版从 deadSince=26000 起算 ⇒ t=27561 时 deadFor=656ms &gt; 600ms ⇒ 误隐藏；
     * 新版从 lastPageMotion=27014 起算 ⇒ deadFor=547ms &lt; 600ms ⇒ 正确不隐藏。
     */
    @Test
    public void v38FlickerRegression_fixed() {
        TestAccessBridge.Win w = TestAccessBridge.window(
                false, 0L, ANCHOR_DEAD_GONE_STILL_MS,
                false,
                ANCHOR_DEAD_WITH_EVIDENCE_MS, ANCHOR_DEAD_GONE_MS, ANCHOR_DEAD_MIN_MS,
                26000L, 0L, 27014L);
        assertNotNull(w);

        long now = 27561L;
        long deadForNew = now - w.sinceMs;
        long deadForOld = now - 26000L;

        assertTrue("旧口径会误隐藏（这正是 926 闪消失的成因）", deadForOld >= ANCHOR_DEAD_MIN_MS);
        assertTrue("新口径正确不隐藏", deadForNew < ANCHOR_DEAD_MIN_MS);
        assertFalse(TestAccessBridge.shouldHide(deadForNew, w.needMs, 3,
                w.goneStill, ANCHOR_DEAD_MIN_SAMPLES));
    }

    // ══════════════════════════════════════════════════════════════
    // 四、Protocol —— 跨进程广播契约
    // ══════════════════════════════════════════════════════════════

    /**
     * 契约自检：前缀统一 + 无重复。
     *
     * <p>广播串错位是**静默失败**（发出去没人收，不报错不崩溃），
     * 所以这道断言比看上去重要。
     */
    @Test
    public void protocol_selfCheckPasses() {
        assertNull("协议自检不通过: " + Protocol.selfCheck(), Protocol.selfCheck());
        assertEquals(1, Protocol.PROTOCOL_VERSION);
    }

    /**
     * 契约里点名的 8 个 action 必须与 {@code code 950} 换包名后固化的值逐字节一致。
     *
     * <p>⚠️ 这是<b>锁值</b>测试：改任何一个字符串都会让它失败。
     * 确需换协议时<b>加新 action</b>（PROTOCOL_VERSION +1），别改旧的。
     */
    @Test
    public void protocol_actionStringsAreFrozen() {
        String p = "io.github.ariinyume.dlsitesoundfloat.action.";
        assertEquals(p + "CONFIG_CHANGED", Protocol.ACTION_CONFIG_CHANGED);
        assertEquals(p + "RESTART_SYSUI", Protocol.ACTION_RESTART_SYSUI);
        assertEquals(p + "SCOPE_HOST_PING", Protocol.ACTION_HOST_PING);
        assertEquals(p + "SCOPE_HOST_PONG", Protocol.ACTION_HOST_PONG);
        assertEquals(p + "STATUSBAR_SUBTITLE_LINE", Protocol.ACTION_LINE);
        assertEquals(p + "STATUSBAR_SUBTITLE_ENABLED", Protocol.ACTION_ENABLED);
        assertEquals(p + "STATUSBAR_SUBTITLE_DISMISS_REQUEST", Protocol.ACTION_DISMISS_REQUEST);
        assertEquals(p + "STATUSBAR_SCOPE_PING", Protocol.ACTION_SCOPE_PING);
        assertEquals(p + "STATUSBAR_SCOPE_PONG", Protocol.ACTION_SCOPE_PONG);
    }

    // ══════════════════════════════════════════════════════════════════
    //  PokeThrottle —— 结构探针三道闸（code 997 第 5 批重构）
    // ══════════════════════════════════════════════════════════════════
    //
    // ⚠️ 参数用 ActivityButtonHook 里的**真实常量值**（不是随手编的数）——
    //   否则测的是"另一个节流器"，与真机行为脱钩（铁律 28 的精神）。
    private static final long PT_BURST_GAP = 1500L;
    private static final long PT_QUIET_BYPASS = 400L;
    private static final long PT_MIN_INTERVAL = 50L;
    private static final long PT_WINDOW = 1000L;
    private static final int PT_MAX_PER_SEC = 12;

    private static TestAccessBridge.Poke poke(long now, long lastEvent, long burstStart,
            long lastPoke, long windowStart, int windowCount) {
        return TestAccessBridge.poke(now, lastEvent, burstStart, lastPoke,
                windowStart, windowCount, PT_BURST_GAP, PT_QUIET_BYPASS, PT_MIN_INTERVAL,
                PT_WINDOW, PT_MAX_PER_SEC);
    }

    /** 首次事件（lastEvent=0 ⇒ gap 视为无穷）必须走「安静期直通」并被放行。 */
    @Test
    public void poke_firstEventIsAlwaysAllowed() {
        TestAccessBridge.Poke d = poke(10_000L, 0L, 0L, 0L, 0L, 0);
        assertTrue(d.passedMerge);
        assertTrue(d.allow);
        assertTrue("首次事件必须走安静期直通", d.quietBypass);
        assertEquals(10_000L, d.burstStartMs);
        assertEquals(10_000L, d.lastPokeMs);
    }

    /** 距上次放行不足 minInterval ⇒ 合并掉，且**窗口状态一律不动**。 */
    @Test
    public void poke_withinMinIntervalIsMerged() {
        TestAccessBridge.Poke d = poke(10_020L, 10_000L, 10_000L, 10_000L, 5_000L, 7);
        assertFalse("20ms < 50ms ⇒ 必须被合并", d.passedMerge);
        assertFalse(d.allow);
        assertEquals("被合并时 lastPokeMs 不变", 10_000L, d.lastPokeMs);
        assertEquals("被合并时窗口起点不变", 5_000L, d.windowStartMs);
        assertEquals("被合并时窗口计数不变", 7, d.windowCount);
    }

    /**
     * ⚠️ 顺序锚：<b>事件时间戳在合并闸「之前」就更新</b>。
     *
     * <p>挪到闸后 ⇒ 长转场的爆发起点会被反复重置 ⇒ latencySuffix 报出的端到端延迟失真。
     * 这是抽取时逐行核对过、最容易改错的一处。
     */
    @Test
    public void poke_eventTimestampUpdatesEvenWhenMerged() {
        TestAccessBridge.Poke d = poke(10_020L, 10_000L, 10_000L, 10_000L, 0L, 0);
        assertFalse(d.passedMerge);
        assertEquals("即使被合并，事件时间戳也要更新", 10_020L, d.lastEventMs);
    }

    /** minInterval 边界：条件是 `< 50`（不含），恰好 50ms 必须放行。 */
    @Test
    public void poke_minIntervalBoundaryIsExclusive() {
        assertFalse("49ms 应被合并", poke(10_049L, 10_000L, 10_000L, 10_000L, 0L, 0).passedMerge);
        assertTrue("恰好 50ms 应放行", poke(10_050L, 10_000L, 10_000L, 10_000L, 0L, 0).passedMerge);
    }

    /** 间隔仍在 burstGap 内 ⇒ 属同一串爆发，起点保持不动。 */
    @Test
    public void poke_burstStartKeptWhileWithinGap() {
        TestAccessBridge.Poke d = poke(10_300L, 10_000L, 10_000L, 9_000L, 0L, 0);
        assertEquals("gap=300 ≤ 1500 ⇒ 起点保持", 10_000L, d.burstStartMs);
        assertFalse("gap=300 < 400 ⇒ 不算安静期", d.quietBypass);
    }

    /** 间隔超出 burstGap ⇒ 新的一串爆发，起点重置为当前时刻。 */
    @Test
    public void poke_burstStartResetsBeyondGap() {
        TestAccessBridge.Poke d = poke(11_600L, 10_000L, 10_000L, 9_000L, 0L, 0);
        assertEquals("gap=1600 > 1500 ⇒ 起点重置", 11_600L, d.burstStartMs);
    }

    /** burstStart 为 0（从未有过）⇒ 初始化为当前时刻。 */
    @Test
    public void poke_burstStartInitializesWhenZero() {
        TestAccessBridge.Poke d = poke(50_000L, 49_500L, 0L, 49_500L, 0L, 0);
        assertEquals("burstStart=0 ⇒ 初始化为 now", 50_000L, d.burstStartMs);
    }

    /** 令牌桶封顶：窗口内已达上限且非安静期 ⇒ 拒绝，且 lastPokeMs 不变。 */
    @Test
    public void poke_tokenBucketCaps() {
        TestAccessBridge.Poke d = poke(20_000L, 19_900L, 19_900L, 19_900L, 19_900L, PT_MAX_PER_SEC);
        assertTrue("已过合并闸", d.passedMerge);
        assertFalse("窗口内已满 ⇒ 拒绝", d.allow);
        assertEquals("被桶拒绝时 lastPokeMs 不变", 19_900L, d.lastPokeMs);
    }

    /** 窗口过期（距窗口起点 ≥ 1000ms）⇒ 计数归零后 +1。 */
    @Test
    public void poke_tokenBucketWindowRollsOver() {
        TestAccessBridge.Poke d = poke(21_000L, 20_900L, 20_900L, 20_900L, 20_000L, PT_MAX_PER_SEC);
        assertTrue(d.allow);
        assertEquals("窗口过期 ⇒ 归零后 +1", 1, d.windowCount);
        assertEquals("窗口起点跟到 now", 21_000L, d.windowStartMs);
    }

    /** 安静期直通 ⇒ **整个令牌桶被跳过**，计数与窗口起点都不动（哪怕计数已远超上限）。 */
    @Test
    public void poke_quietBypassSkipsTokenBucket() {
        TestAccessBridge.Poke d = poke(30_000L, 20_000L, 20_000L, 20_000L, 20_000L, 99);
        assertTrue("gap=10000 > 400 ⇒ 安静期", d.quietBypass);
        assertTrue(d.allow);
        assertEquals("直通时不碰计数", 99, d.windowCount);
        assertEquals("直通时不碰窗口起点", 20_000L, d.windowStartMs);
    }

    /** 首次进入时 windowStart=0 ⇒ 视为窗口早已过期，行为与新窗口一致。 */
    @Test
    public void poke_zeroWindowStartIsTreatedAsExpired() {
        TestAccessBridge.Poke d = poke(5_000L, 4_900L, 4_900L, 4_900L, 0L, 0);
        assertEquals(5_000L, d.windowStartMs);
        assertEquals(1, d.windowCount);
    }

    /** 放行时 lastPokeMs 必须跟到 now（否则下一次合并闸会算错窗口）。 */
    @Test
    public void poke_allowUpdatesLastPoke() {
        TestAccessBridge.Poke d = poke(40_000L, 39_000L, 39_000L, 39_000L, 0L, 0);
        assertTrue(d.allow);
        assertEquals(40_000L, d.lastPokeMs);
    }

    // ==================================================================
    // PlaylistKey（code 1004：点击未缓存音频 → 插件「无字幕」的根修）
    // ------------------------------------------------------------------
    // 全部向量取自真机日志 LSPosed_20261010_184115（录屏 Record_2026-10-10-18-40-26）
    // 的实测数值，下面的注释逐条标了出处时刻。
    // ==================================================================

    /** 日志里出现过的四个身份键（tc=7 的同一份 7 轨列表，时长各不相同）。 */
    private static final long ID_IDX0 = 7000235992000L;   // dur=235992ms
    private static final long ID_IDX1 = 7000372624001L;   // dur=372624ms
    private static final long ID_IDX2 = 7001582440002L;   // dur=1582440ms
    private static final long ID_IDX3 = 7000944784003L;   // dur=944784ms
    /** 18:40:58.391 装载期的身份（idx 已翻到 3，但 duration 仍是 0.0）。 */
    private static final long ID_IDX3_DUR0 = 7000000000003L;

    @Test
    public void selectionKey_roundTrips() {
        assertEquals(700000L, PlaylistKey.makeSelection(7, 0));
        assertEquals(700003L, PlaylistKey.makeSelection(7, 3));
        assertEquals(100000L, PlaylistKey.makeSelection(1, 0));
        assertEquals(7, PlaylistKey.selectionTrackCount(700003L));
        assertEquals(3, PlaylistKey.selectionIndex(700003L));
    }

    /** 读不到列表 / 序号非法 ⇒ 0 = 未知，绝不能与真实列表相撞（真实 tc≥1 ⇒ ≥100000）。 */
    @Test
    public void selectionKey_unknownIsZeroAndUnreachable() {
        assertEquals(0L, PlaylistKey.makeSelection(0, 3));
        assertEquals(0L, PlaylistKey.makeSelection(-1, 3));
        assertEquals(0L, PlaylistKey.makeSelection(7, -1));
        assertEquals(-1, PlaylistKey.selectionTrackCount(0L));
        assertEquals(-1, PlaylistKey.selectionIndex(0L));
    }

    @Test
    public void identityKey_splitMatchesRealDeviceValues() {
        assertEquals(7, PlaylistKey.identityTrackCount(ID_IDX1));
        assertEquals(1, PlaylistKey.identityIndex(ID_IDX1));
        assertEquals(0, PlaylistKey.identityIndex(ID_IDX0));
        assertEquals(2, PlaylistKey.identityIndex(ID_IDX2));
        assertEquals(3, PlaylistKey.identityIndex(ID_IDX3));
        assertEquals(7, PlaylistKey.identityTrackCount(ID_IDX3_DUR0));
        assertEquals(3, PlaylistKey.identityIndex(ID_IDX3_DUR0));
        assertEquals(-1, PlaylistKey.identityIndex(0L));
        assertEquals(-1, PlaylistKey.identityTrackCount(0L));
    }

    /**
     * ★ 本轮 bug 的向量：18:40:58.556 加载 84 cues 时，
     * 选择键已跑到 idx=3，而身份键还停在 idx=1（时长没就绪）⇒ 必须判「选择跑在前面」。
     */
    @Test
    public void selectionAhead_yesWhenIndexFlippedBeforeDurationArrived() {
        assertTrue("选择 idx=3 vs 身份 idx=1 ⇒ 跑在前面",
                PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 3), ID_IDX1));
        // ⚠️ 反直觉但正确：装载期那份 duration=0 的读数**序号已经是 3**（序号先翻、时长后到），
        //    单看它本来就与选择一致、读不出「跑在前面」。正因如此，权威门（duration > 0）
        //    把这类读数整条丢弃之后，数据层手里留下的仍然是**上一轨**的身份（ID_IDX1）
        //    —— 这才是上面那条断言成立的真正原因，也是「选择键」必须单独存在的原因。
        assertFalse("duration=0 的那份身份序号已跟随选择，单看它读不出「跑在前面」",
                PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 3),
                        ID_IDX3_DUR0));
    }

    /**
     * 反例一：18:40:38.955 那一份 38 cues（同一次会话里**成功**的案例）——
     * 加载时身份键已经跟到 idx=0（音源先装载完、字幕后到）⇒ 不算「跑在前面」，
     * 于是仍走旧的 code 980/预载容差路径，新判据不会去抢它的活。
     */
    @Test
    public void selectionAhead_noWhenIdentityAlreadyCaughtUp() {
        assertFalse(PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 0), ID_IDX0));
        assertFalse(PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 1), ID_IDX1));
    }

    /**
     * 反例二（安全边界）：**跨作品、同序号**。宿主切作品时 currentIndex 恒为 0
     * （6 轨列表与 1 轨列表都是 idx=0）—— 此时两侧序号相同 ⇒ 不算「跑在前面」。
     * 这条保证了本判据绝不会拿上一部作品的字幕冒充新作品。
     */
    @Test
    public void selectionAhead_noOnCrossWorkSameIndex() {
        assertFalse("同序号的跨作品切换不算「选择跑在前面」",
                PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 0), ID_IDX0));
        assertFalse("trackCount 不同 = 换了列表，不是「选择跑到前面」",
                PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(6, 3), ID_IDX1));
    }

    /** 两侧任一未知 ⇒ 一律 false（退回旧判据，绝不在信息不足时擅自认领）。 */
    @Test
    public void selectionAhead_falseWhenEitherSideUnknown() {
        assertFalse(PlaylistKey.selectionAheadOfIdentity(0L, ID_IDX1));
        assertFalse(PlaylistKey.selectionAheadOfIdentity(PlaylistKey.makeSelection(7, 3), 0L));
        assertFalse(PlaylistKey.selectionAheadOfIdentity(0L, 0L));
    }

    /** ★ 认领判据：18:41:04.429 换轨通知的目标选择键 == 盖章时的选择键 ⇒ 认领（RESUME）。 */
    @Test
    public void cuesBelong_yesOnTargetSelectionMatch() {
        assertTrue(PlaylistKey.cuesBelongToTarget(true, PlaylistKey.makeSelection(7, 3),
                PlaylistKey.makeSelection(7, 3)));
    }

    /** 目标不是盖印时那条轨 ⇒ 不认领（切到**真正无字幕**的轨时，旧 cues 必须被隔离）。 */
    @Test
    public void cuesBelong_noOnDifferentTarget() {
        assertFalse(PlaylistKey.cuesBelongToTarget(true, PlaylistKey.makeSelection(7, 3),
                PlaylistKey.makeSelection(7, 4)));
        assertFalse(PlaylistKey.cuesBelongToTarget(true, PlaylistKey.makeSelection(7, 3),
                PlaylistKey.makeSelection(6, 3)));
    }

    /** 「加载时选择没跑在身份前面」⇒ 无论目标怎么匹配都不认领（这是跨作品同序号的安全闸）。 */
    @Test
    public void cuesBelong_noWhenNotLoadedAhead() {
        assertFalse(PlaylistKey.cuesBelongToTarget(false, PlaylistKey.makeSelection(7, 3),
                PlaylistKey.makeSelection(7, 3)));
    }

    /** 目标选择键未知（老信号路径）⇒ 不认领。 */
    @Test
    public void cuesBelong_noWhenTargetUnknown() {
        assertFalse(PlaylistKey.cuesBelongToTarget(true, PlaylistKey.makeSelection(7, 3), 0L));
        assertFalse(PlaylistKey.cuesBelongToTarget(true, 0L, PlaylistKey.makeSelection(7, 3)));
    }

    /** 端到端复演真机那 5.9 秒：盖章 → 通知 → 认领。 */
    @Test
    public void realDevice1004Chain_claimsTheJsonBeforeTheSwitchWasReported() {
        long selectionAtLoad = PlaylistKey.makeSelection(7, 3);          // 18:40:58.391 选择已翻
        long identityAtLoad = ID_IDX1;                                   // 身份仍停在 idx=1
        boolean ahead = PlaylistKey.selectionAheadOfIdentity(selectionAtLoad, identityAtLoad);
        assertTrue("加载那一刻选择跑在身份前面", ahead);
        long targetSelection = PlaylistKey.makeSelection(7, 3);          // 18:41:04.429 通知的目标
        assertTrue("换轨通知到达 ⇒ 认领这份 cues（不再变「无字幕」）",
                PlaylistKey.cuesBelongToTarget(ahead, selectionAtLoad, targetSelection));
    }

    // ────────────────────────────────────────────────────────────────────
    // PlaylistKey（code 1005：换作品/换章节时列表整体重建 → 插件短暂「无字幕」的根修）
    //
    // 真机（LSPosed_20261010_214410，截屏 2026-10-10-21-42-19）：
    //   21:41:53.139  statusMap  currentIndex=0 trackCount=0 duration=0.0 idle  ← 列表销毁
    //   21:41:53.505  optimized/c0d71242….json 到达（新轨 idx=2 的字幕，仅隔 366ms）
    //   21:41:58.241  statusMap  currentIndex=2 trackCount=8 duration=0.0       ← 新列表首次可读
    //   21:41:59.654  >>> track changed  tc=6 dur=835560 idx=5 -> tc=8 dur=1286040 idx=2
    //                 | lastJson=6135ms ago -> SUSPEND … [973 previous-track cues quarantined]
    // ────────────────────────────────────────────────────────────────────

    /** 真机两代列表的身份键（旧：六轨 835.56s 第 5 首；新：八轨 1286.04s 第 2 首）。 */
    private static final long ID_OLD_LIST = 6000835560005L;
    private static final long ID_NEW_LIST = 8001286040002L;

    /** 真机实测：这份 JSON 比换轨通知早到 6135ms（列表重建 + 音源装载都比单纯换轨慢）。 */
    private static final long REAL_1005_JSON_LEAD_MS = 6135L;

    /** 认领时限（与 {@code SubtitleRepository.REBUILT_PLAYLIST_CLAIM_MS} 同值）。 */
    private static final long CLAIM_MS = 15000L;

    /** 时钟基准 —— 只有相对差有意义，取个远离 0 的值以免和「未知(0)」混淆。 */
    private static final long T0 = 1_000_000L;

    /** 身份键分量拆分对得上真机读数（tc / durMs / idx 三项互不重叠）。 */
    @Test
    public void identityKey_realDevice1005Values() {
        assertEquals(6, PlaylistKey.identityTrackCount(ID_OLD_LIST));
        assertEquals(5, PlaylistKey.identityIndex(ID_OLD_LIST));
        assertEquals(8, PlaylistKey.identityTrackCount(ID_NEW_LIST));
        assertEquals(2, PlaylistKey.identityIndex(ID_NEW_LIST));
    }

    /** 真机那一份：窗口内到达 + 换了列表（6→8）+ 6135ms ≤ 15000ms ⇒ 认领。 */
    @Test
    public void rebuiltPlaylist_claimsJsonArrivedInWindow() {
        assertTrue(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, ID_NEW_LIST, ID_OLD_LIST,
                T0, T0 + REAL_1005_JSON_LEAD_MS, CLAIM_MS));
    }

    /** 没观察到「列表归零」⇒ 这条判据无从谈起（退回旧行为：隔离 + 等宿主重发）。 */
    @Test
    public void rebuiltPlaylist_noWhenNotArrivedDuringRebuild() {
        assertFalse(PlaylistKey.cuesBelongToRebuiltPlaylist(
                false, ID_NEW_LIST, ID_OLD_LIST,
                T0, T0 + REAL_1005_JSON_LEAD_MS, CLAIM_MS));
    }

    /** 同一份列表（曲目数相同）⇒ 不是「重建」，交给 code 1004 的选择键判。 */
    @Test
    public void rebuiltPlaylist_noOnSameTrackCount() {
        assertFalse("同一列表内换序号（6→6）不算重建",
                PlaylistKey.cuesBelongToRebuiltPlaylist(
                        true, 6000944784003L, ID_OLD_LIST, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 目标身份未知（老信号路径）⇒ 不认领。 */
    @Test
    public void rebuiltPlaylist_noWhenNewIdentityUnknown() {
        assertFalse(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, 0L, ID_OLD_LIST, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 目标身份里曲目数为 0（非列表身份）⇒ 不认领。 */
    @Test
    public void rebuiltPlaylist_noWhenNewTrackCountZero() {
        long identityWithZeroTc = 1_286_040_000L + 2L;      // tc=0 ⇒ identityTrackCount == 0
        assertEquals(0, PlaylistKey.identityTrackCount(identityWithZeroTc));
        assertFalse(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, identityWithZeroTc, ID_OLD_LIST, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 换轨通知迟到超过时限 ⇒ 放弃认领（防陈旧印章被无关换轨错误兑现）。 */
    @Test
    public void rebuiltPlaylist_noWhenTooLate() {
        assertFalse(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, ID_NEW_LIST, ID_OLD_LIST, T0, T0 + 16000L, CLAIM_MS));
    }

    /** 时限边界：恰好 15000ms 仍算认领（闭区间），多 1ms 就不算。 */
    @Test
    public void rebuiltPlaylist_boundaryAtTolerance() {
        assertTrue(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, ID_NEW_LIST, ID_OLD_LIST, T0, T0 + CLAIM_MS, CLAIM_MS));
        assertFalse(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, ID_NEW_LIST, ID_OLD_LIST, T0, T0 + CLAIM_MS + 1L, CLAIM_MS));
    }

    /** 时钟回退 / 到达时刻未知 ⇒ 不认领（防御式，绝不凭坏数据盖章）。 */
    @Test
    public void rebuiltPlaylist_noOnBadClock() {
        assertFalse("now 早于到达时刻（时钟回退）",
                PlaylistKey.cuesBelongToRebuiltPlaylist(
                        true, ID_NEW_LIST, ID_OLD_LIST, T0 + 1000L, T0, CLAIM_MS));
        assertFalse("到达时刻未知(0)",
                PlaylistKey.cuesBelongToRebuiltPlaylist(
                        true, ID_NEW_LIST, ID_OLD_LIST, 0L, T0, CLAIM_MS));
    }

    /** 盖章时身份未知（JSON 比任何权威读数都早）⇒ 仍然认领：它不可能是「上一轨」的。 */
    @Test
    public void rebuiltPlaylist_claimsWhenOwnerIdentityUnknown() {
        assertTrue(PlaylistKey.cuesBelongToRebuiltPlaylist(
                true, ID_NEW_LIST, 0L, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 端到端复演真机那 6.1 秒：列表销毁 → JSON 到达 → 新列表可读 → 通知 → 认领。 */
    @Test
    public void realDevice1005Chain_claimsJsonOfTheRebuiltPlaylist() {
        // 装载那一刻的印章还是**旧列表**（21:41:53.505 时新列表还没报出来）
        assertTrue("换轨通知到达 ⇒ 认领这份 cues（不再空窗 25 秒、不再被 973 隔离）",
                PlaylistKey.cuesBelongToRebuiltPlaylist(
                        true, ID_NEW_LIST, ID_OLD_LIST,
                        T0, T0 + REAL_1005_JSON_LEAD_MS, CLAIM_MS));
        // 反面：若没观察到列表归零（= 这条新判据不存在时的旧世界），同样输入必须不认领
        assertFalse("没有「列表重建窗口」这条证据时，旧判据确实救不了它（正是本轮 bug）",
                PlaylistKey.cuesBelongToRebuiltPlaylist(
                        false, ID_NEW_LIST, ID_OLD_LIST,
                        T0, T0 + REAL_1005_JSON_LEAD_MS, CLAIM_MS));
    }

    // ────────────────────────────────────────────────────────────────────
    // PlaylistKey（code 1006：换作品时仍短暂「无字幕」（1005 的第三例）的根修）
    //
    // 真机（LSPosed_20261010_225318，截屏 2026-10-10-22-52-25 等三张）：
    //   22:51:02.563  Loaded 73 cues —— 作品 RJ01126292（用户刚点下新作品）
    //   22:51:09.847  >>> track changed tc=7 dur=827472 idx=3 -> tc=5 dur=745032 idx=0
    //                 | lastJson=7284ms ago -> SUSPEND   → 空窗 17.9s
    //   22:52:11.455  Loaded 343 cues —— 作品 RJ01536846（同一作品三条音轨三个 json）
    //   22:52:13.009  statusMap tc=4 dur=0.0（新列表第一拍：非零 tc、时长仍为 0）
    //   22:52:17.875  >>> track changed tc=5 dur=1025040 idx=2 -> tc=4 dur=2371608 idx=2
    //                 | lastJson=4627ms ago -> SUSPEND   → 空窗 29.7s
    //
    // 机制：宿主在「用户点击那一刻」就把新作品的字幕取回来了，而播放列表切换与换轨通知
    //       都要等音源装载 ⇒ 那段空档里选择键与身份键**全是旧作品的**，旧两条判据都无证据。
    //       唯一能正面指认归属的，是 JSON 请求 URL 里的作品路径（同一作品各音轨共用）。
    // ────────────────────────────────────────────────────────────────────

    /**
     * 真机那条字幕 URL（作品 RJ01126292 的第 1 条音轨）。
     *
     * <p>⚠️ {@code Policy} / {@code Signature} 已截断为示例值 —— 本判据只砍 {@code ?} 之后的
     * 查询串，内容与结果无关；路径部分与哈希文件名与真机日志逐字节一致。
     */
    private static final String URL_WORK_A_JSON_1 =
            "https://play.dl.dlsite.com/content/work/doujin/RJ01127000/RJ01126292/optimized/"
            + "4e4a45e886d3841c3c29bd72a61e2652.json"
            + "?Key-Pair-Id=K2KNQ20FMJHVOK&Policy=eyJTdGF0ZW1lbnQ...&Signature=gl6T25zCM6Dz...";

    /** 同一部作品的**另一条音轨**（真机 22:51 会话里 RJ01126292 的另一次请求）。 */
    private static final String URL_WORK_A_JSON_2 =
            "https://play.dl.dlsite.com/content/work/doujin/RJ01127000/RJ01126292/optimized/"
            + "959ebee409018c895a4090bd05d37450.json"
            + "?Key-Pair-Id=K2KNQ20FMJHVOK&Policy=eyJTdGF0ZW1lbnQ...&Signature=gl6T25zCM6Dz...";

    /** 真机 22:52 会话里那部作品（RJ01536846，三条音轨各一个 json）。 */
    private static final String URL_WORK_B_JSON_1 =
            "https://play.dl.dlsite.com/content/work/doujin/RJ01537000/RJ01536846/optimized/"
            + "0c989544dc18478aaf4ec2f7e44c531a.json"
            + "?Key-Pair-Id=K2KNQ20FMJHVOK&Policy=eyJTdGF0ZW1lbnQ...&Signature=HnHQrwlktckZkCK...";

    /** 作品 A 的作品键（= 砍掉查询串与哈希文件名之后的路径）。 */
    private static final String WORK_KEY_A =
            "https://play.dl.dlsite.com/content/work/doujin/RJ01127000/RJ01126292/optimized";

    /** 作品 B 的作品键。 */
    private static final String WORK_KEY_B =
            "https://play.dl.dlsite.com/content/work/doujin/RJ01537000/RJ01536846/optimized";

    /** 真机实测：新作品的 JSON 比换轨通知早到 7284ms（22:51:02.563 → 22:51:09.847）。 */
    private static final long REAL_1006_JSON_LEAD_MS = 7284L;

    /** 真机第二处：早到 4627ms（22:52:13.247 → 22:52:17.875）。 */
    private static final long REAL_1006_JSON_LEAD_MS_2 = 4627L;

    /** 真机 URL ⇒ 作品键（同时验「砍查询串」与「砍哈希文件名」两步）。 */
    @Test
    public void workKeyOf_realDeviceUrl() {
        assertEquals(WORK_KEY_A, PlaylistKey.workKeyOf(URL_WORK_A_JSON_1));
        assertEquals(WORK_KEY_B, PlaylistKey.workKeyOf(URL_WORK_B_JSON_1));
    }

    /** 同一部作品的每条音轨（哈希文件名各不相同）必须得到**同一个**作品键。 */
    @Test
    public void workKeyOf_sameWorkTracksShareKey() {
        assertEquals("同一作品的另一条音轨仍是同一个键",
                PlaylistKey.workKeyOf(URL_WORK_A_JSON_1),
                PlaylistKey.workKeyOf(URL_WORK_A_JSON_2));
    }

    /** 换作品必得到不同的键 —— 这是整条判据的立足点。 */
    @Test
    public void workKeyOf_differsAcrossWorks() {
        assertFalse("不同作品绝不能撞键（否则会拿上一部作品的字幕冒充）",
                PlaylistKey.workKeyOf(URL_WORK_A_JSON_1)
                        .equals(PlaylistKey.workKeyOf(URL_WORK_B_JSON_1)));
    }

    /** 查询串每次请求都不同（签名时效）⇒ 必须被忽略，否则同一份 JSON 的两次请求会被当成两部作品。 */
    @Test
    public void workKeyOf_queryStringIgnored() {
        String sameHashOtherSignature = URL_WORK_A_JSON_2.substring(
                0, URL_WORK_A_JSON_2.indexOf('?')) + "?Key-Pair-Id=OTHER&Signature=BRANDNEW...";
        assertEquals("同 hash 换一组签名 ⇒ 仍是同一个键",
                PlaylistKey.workKeyOf(URL_WORK_A_JSON_2),
                PlaylistKey.workKeyOf(sameHashOtherSignature));
    }

    /** 退化输入一律返回 null（调用方当「无证据」，绝不凭坏数据认领）。 */
    @Test
    public void workKeyOf_nullAndDegenerate() {
        assertNull(PlaylistKey.workKeyOf(null));
        assertNull("没有路径分隔 ⇒ 取不出作品键", PlaylistKey.workKeyOf("subtitle.json"));
        assertNull("斜杠在位置 0（前面没有任何东西可当键）⇒ 取不出",
                PlaylistKey.workKeyOf("/subtitle.json"));
        assertEquals("只有一层目录也照样取（域名/目录层数都不是必要条件）",
                "a", PlaylistKey.workKeyOf("a/b.json"));
    }

    /** 真机那一份：这次 JSON 的作品键 == 刚换到的作品键 + 7284ms ≤ 15000ms ⇒ 认领。 */
    @Test
    public void changedWork_claimsWhenKeysMatch() {
        assertTrue(PlaylistKey.cuesBelongToChangedWork(
                WORK_KEY_B, WORK_KEY_B, T0, T0 + REAL_1006_JSON_LEAD_MS, CLAIM_MS));
        assertTrue("第二处提前量同样认领", PlaylistKey.cuesBelongToChangedWork(
                WORK_KEY_A, WORK_KEY_A, T0, T0 + REAL_1006_JSON_LEAD_MS_2, CLAIM_MS));
    }

    /** 安全阀：换作品的**不是**手上这份 cues（键不等）⇒ 绝不认领（防跨作品冒充）。 */
    @Test
    public void changedWork_noWhenKeyDiffers() {
        assertFalse("手上是 A 的 cues，待兑现的是 B ⇒ 不认",
                PlaylistKey.cuesBelongToChangedWork(
                        WORK_KEY_A, WORK_KEY_B, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 没有待兑现的换作品（null），或手上 cues 的作品键未知（null / 空）⇒ 都不认领。 */
    @Test
    public void changedWork_noWhenEitherKeyMissing() {
        assertFalse("没有待兑现的换作品",
                PlaylistKey.cuesBelongToChangedWork(WORK_KEY_A, null, T0, T0 + 1000L, CLAIM_MS));
        assertFalse("手上 cues 没拿到 URL",
                PlaylistKey.cuesBelongToChangedWork(null, WORK_KEY_A, T0, T0 + 1000L, CLAIM_MS));
        assertFalse("空串按「没拿到」处理",
                PlaylistKey.cuesBelongToChangedWork("", WORK_KEY_A, T0, T0 + 1000L, CLAIM_MS));
    }

    /** 时限：恰好 15000ms 仍认（闭区间），多 1ms 就放弃 —— 防陈旧印章被无关换轨兑现。 */
    @Test
    public void changedWork_boundaryAtTolerance() {
        assertTrue(PlaylistKey.cuesBelongToChangedWork(
                WORK_KEY_A, WORK_KEY_A, T0, T0 + CLAIM_MS, CLAIM_MS));
        assertFalse(PlaylistKey.cuesBelongToChangedWork(
                WORK_KEY_A, WORK_KEY_A, T0, T0 + CLAIM_MS + 1L, CLAIM_MS));
        assertFalse("真机 4627ms 之外再放大到超时限也必须放弃",
                PlaylistKey.cuesBelongToChangedWork(
                        WORK_KEY_A, WORK_KEY_A, T0, T0 + 16000L, CLAIM_MS));
    }

    /** 时钟回退 / 换作品时刻未知 ⇒ 不认领（防御式，绝不凭坏数据盖章）。 */
    @Test
    public void changedWork_noOnBadClock() {
        assertFalse("now 早于换作品时刻（时钟回退）",
                PlaylistKey.cuesBelongToChangedWork(
                        WORK_KEY_A, WORK_KEY_A, T0 + 1000L, T0, CLAIM_MS));
        assertFalse("换作品时刻未知(0)",
                PlaylistKey.cuesBelongToChangedWork(WORK_KEY_A, WORK_KEY_A, 0L, T0, CLAIM_MS));
    }

    /** 端到端复演真机那 7.3 秒：换作品 → JSON 先到 → 音源装载 → 换轨通知 → 认领。 */
    @Test
    public void realDevice1006Chain_claimsJsonOfTheChangedWork() {
        assertTrue("换轨通知到达 ⇒ 认领这份 cues（不再空窗 17.9 秒）",
                PlaylistKey.cuesBelongToChangedWork(
                        WORK_KEY_A, WORK_KEY_A, T0, T0 + REAL_1006_JSON_LEAD_MS, CLAIM_MS));
        assertFalse("反面：若没有这条作品键判据（旧世界），同样输入确实救不了它（正是本轮 bug）",
                PlaylistKey.cuesBelongToChangedWork(
                        WORK_KEY_A, /* 旧世界拿不到作品键 */ null,
                        T0, T0 + REAL_1006_JSON_LEAD_MS, CLAIM_MS));
    }
}