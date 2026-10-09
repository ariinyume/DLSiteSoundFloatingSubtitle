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
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ariinyume.dlsitesoundfloat.hook;

/**
 * 锚点死亡**确认窗**的判定内核（从 {@code ActivityButtonHook} 抽出，2.2.14 / code 995 起）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么要抽这一块
 *
 * {@code detectAndLayout} 有 417 行（现存本类最大方法），其中「锚点判死后要等多久才真的
 * 收起按钮」这一段是**纯算术**：给定 5 个入参（正面证据、归零、已静止时长、死亡时刻、
 * 页面运动时刻）算出 3 个出参（确认窗need、起算点since、已历时deadFor），不碰任何 View、
 * 不写任何静态字段、不打任何日志。
 *
 * 但它夹在 417 行switch 分支中间，夹着 8 处静态字段写、4 处 {@code LogGate} 和3 次
 * {@code hideButton()} —— 想验证「600ms 那一档到底怎么算出来的」只能真机试。
 *
 * 而这几档正是这个模块**反复返工**的地方（v30 把「4 次采样」改成墙钟、v37 加手指拖动门、
 * v38 把起算点从「判死时刻」改成「页面最后运动时刻」、code 948 缩到 200ms、code 949 加归零档）。
 * 五轮改动全在这十几行算术里，且**每一档都出过「误隐藏」或「赖着不走」的观感问题**。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ⚠️ 铁律（抽取时的取证结论，改本类前必读）
 *
 * 1. **本类必须保持无状态、可单测**。原逻辑里有 4 处静态字段读（{@code sLastOtherSeenMs} /
 *    {@code sLastPageMotionMs} / {@code sAnchorDeadSinceMs} / {@code sAnchorDeadSamples}），
 *    那些**必须由调用方读出来传入** —— 本类一行状态都不持有。
 *
 * 2. **阈值全部由调用方以常量入参传入**。本类不碰 {@code ActivityButtonHook} 的常量，
 *    避免「改了hook 的常量却忘了纯内核」这类双份真值。
 *
 * 3. **判据顺序一字不改**。特别是这两条顺序敏感处：
 *    · 确认窗 need 的三档优先级 =<b>有正面证据 &gt; 归零且已静止 &gt; 默认</b>；
 *    · 起算点 since 的三次修正 =<b>先取死亡时刻 → 正面证据更早则改用它 → 页面运动更晚则改用它</b>。
 *      （「页面运动更晚」这条是 v38 的根修：拖动/fling 期间必须从页面停稳后开始计时，
 *      否则确认窗趁手指还在拖就走完 → 按钮闪消失。真机取证 2026-09-14 23:16:26~27。）
 *
 * 4. **不碰日志、不碰副作用**。{@code anchor dead for ...} 那条诊断日志带 8 个原始量，
 *    留在 hook 侧打 —— 那是「可观测性口径」，与本类的算术无关。
 * ─────────────────────────────────────────────────────────────────────
 */
final class AnchorDeadPolicy {

    private AnchorDeadPolicy() {
    }

    /**
     * 确认窗计算结果。
     *
     * <p>这三个值原先靠局部变量带不出来（因为要塞进日志），抽出来后作为出参显式传回，
     * 行为与日志内容与抽取前逐字一致。
     */
    static final class Window {
        /** 确认窗长度need（ms）：正面证据档最短，归零且已静止档次之，默认档最长。 */
        final long needMs;
        /** 起算点（ms）：页面运动时刻可能把它推到死亡时刻之后。 */
        final long sinceMs;
        /** 是否走了「归零且页面已静止」这档（诊断日志要打 {@code goneStill}）。 */
        final boolean goneStill;
        /** 是否走了「有正面证据」这档（决定起算点是否被修正过）。 */
        final boolean hasEvidence;

        Window(long needMs, long sinceMs, boolean goneStill, boolean hasEvidence) {
            this.needMs = needMs;
            this.sinceMs = sinceMs;
            this.goneStill = goneStill;
            this.hasEvidence = hasEvidence;
        }
    }

    /**
     * 【code 948/949】算「锚点判死」这一趟的确认窗。
     *
     * <p>三档确认窗：<b>正面证据 200ms &lt; 归零且已静止 0ms &lt; 默认 600ms</b>。
     * 归零档取最短 —— 它是最强的一条信号（屏幕上真的一个像素都不剩），
     * 而 600ms 那条防假死窗对它毫无意义（假死时容器仍部分可见，真机实测假死可达 1.326s）。
     *
     * <p>【code 949】归零档的<b>前置门</b>是「面积<b>严格归零</b> 且 <b>页面已静止</b>」：
     * 948 只要求面积归零（need=120ms），真机复测仍残留 100~200ms ——
     * 因为首次采样与「页面消失」同帧，而 120ms 的 need 逼它再等一个探针周期。
     * 补上「页面已静止」之后 need 才能取 0：首次采样即收起；
     * 而 fling / 拖动中（页面仍在动）依旧被这条门与 {@code isPageHeld} 挡住。
     *
     * @param anchorGone         锚点面积**严格归零**（不是「很小」）
     * @param pageStillMs        页面已静止多久（ms）；{@code 0} 表示从未动过
     * @param goneStillThresholdMs  {@code ANCHOR_DEAD_GONE_STILL_MS}，页面静止多久才算「已静止」
     * @param hasFreshEvidence   近期是否见过 mini-player 滑条这条**正面证据**
     * @param withEvidenceMs     {@code ANCHOR_DEAD_WITH_EVIDENCE_MS}
     * @param goneMs             {@code ANCHOR_DEAD_GONE_MS}
     * @param minMs              {@code ANCHOR_DEAD_MIN_MS}
     * @param deadSinceMs        锚点第一次判死的时刻（{@code sAnchorDeadSinceMs}）
     * @param lastOtherSeenMs    最近一次见到其他播放控件的时刻（{@code sLastOtherSeenMs}）；{@code 0} = 从没见过
     * @param lastPageMotionMs   页面最后一次运动的时刻（{@code sLastPageMotionMs}）；{@code 0} = 从未动过
     * @return 确认窗 + 起算点；{@code deadSinceMs <= 0} 时返回 {@code null}（调用方应先初始化判死时刻）
     */
    static Window confirmWindow(
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
        if (deadSinceMs <= 0L) {
            return null;
        }

        // 【code 949】归零档前置门：面积**严格归零** 且 **页面已静止**。
        boolean goneStill = anchorGone
                && (pageStillMs == 0L || pageStillMs >= goneStillThresholdMs);

        // 【code 948/949】三档确认窗。顺序敏感：证据档 > 归零档 > 默认档。
        long need = hasFreshEvidence ? withEvidenceMs
                : (goneStill ? goneMs : minMs);

        long since = deadSinceMs;
        // 正面证据优先于负面推断：从**最早的离开信号**起算。
        if (hasFreshEvidence && lastOtherSeenMs != 0L && lastOtherSeenMs < since) {
            since = lastOtherSeenMs;
        }
        // v38根修：确认窗从「页面**最后一次运动**之后」才开始走。
        //
        // 为什么：拖动 / fling 会把页面拖到「看不见播放控件」的地方，此时「扫不到播放页证据」
        // 是这一刻的**正常现象**，根本不是「离开播放页」。而 deadSinceMs 从「判死」起算 ——
        // 那正好是拖动**刚开始**，于是确认窗趁手指还在拖就一路走完 → 按钮 hide，
        // 页面一回弹又 shown。真机取证（2026-09-14 23:16:26~27）一次 fling：
        //   26.905 锚点判死 → 27.014 页面到位 → 27.561 deadFor 已达 656ms > 600ms → hide
        //   → 27.892 页面回弹、锚点回来 → shown。用户看到的是「闪消失 + 闪现」。
        // 改成从页面运动时刻起算后，同场景 deadFor 只有 547ms < 600ms，不隐藏。
        // 真·离开播放页时页面也会停（离场动画 ≤300ms），确认窗随即正常推进。
        if (lastPageMotionMs != 0L && lastPageMotionMs > since) {
            since = lastPageMotionMs;
        }
        return new Window(need, since, goneStill, hasFreshEvidence);
    }

    /**
     * 【code 949】这一趟是否**够格收起按钮**。
     *
     * <p>只判两件事（顺序与原实现逐行一致）：
     * <ol>
     *   <li>已历时够长：{@code deadFor >= need}；</li>
     *   <li>采样次数够：归零且已静止时只认 <b>1</b> 次（首次检测即收起），
     *       其余两档维持 {@code ANCHOR_DEAD_MIN_SAMPLES} 次去抖。</li>
     * </ol>
     *
     * ⚠️ <b>不要在这里加「页面是否已静止」的门。</b>原实现的分工是：
     * <ul>
     *   <li>「页面在动」这件事由 <b>{@code isPageHeld(now)}</b> 在<b>调用方</b>先行拦截
     *       ——它是五道门的复合判定（快照 + 视觉位移 + 锚点面积），
     *       语义上远比「静止时长 &gt; 某个阈值」丰富，<b>不能被简化成一个毫秒阈值</b>
     *       （v37 注释明写：拖动期间锚点面积掉到 30~45%、verdict 在 PLAYER/OTHER 之间跳，
     *       这类情况 {@code isPageHeld} 能挡住，而「静止 80ms」这种粗判会误杀）。</li>
     *   <li>「页面已静止」在本类里的作用是<b>参与选档</b>
     *       （{@link #confirmWindow} 的 {@code goneStill} → {@code need=0}），
     *       <b>不参与</b>「够不够格收起」的判定。</li>
     * </ul>
     * 换句话说：<b>「已静止」是选档条件，「isPageHeld」才是收起前的最后一道门。</b>
     *
     * @param deadFor     已经历（ms）
     * @param need        确认窗长度（{@link Window#needMs}）
     * @param samples锚点死亡后的采样次数
     * @param goneStill   是否走了归零且已静止档
     * @param minSamples  {@code ANCHOR_DEAD_MIN_SAMPLES}
     * @return true = 可以收起
     */
    static boolean shouldHide(long deadFor, long need, int samples,
                              boolean goneStill, int minSamples) {
        int needSamples = goneStill ? 1 : minSamples;
        return deadFor >= need && samples >= needSamples;
    }
}
