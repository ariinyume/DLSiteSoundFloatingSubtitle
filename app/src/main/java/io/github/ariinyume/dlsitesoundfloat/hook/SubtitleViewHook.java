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

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

/**
 * 视图树扫描器（React Native 兼容）。
 *
 * 「一条文本是不是字幕」用【语义过滤】判定：必须出现在已从网络加载到的 cue 列表里。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 页面判定的演进（同一个 bug 复发多次，务必读完再改）：
 *
 * v17：整棵树一次性新鲜扫描（不读取跨帧残留状态，跳过不可见子树）。
 * v18：命中判定由「包含匹配」改精确相等。
 * v19：判定只认结构证据（宽 SeekBar），移除 liveLines；文本证据加屏幕可见性过滤。
 * v20：发现「宽滑条」不够 —— 曲目列表页底部 mini-player 里也有一个同宽滑条。
 * v21(1.20.1)：宽滑条再加「纵向位置」这一维（≤82% 屏高才算主滑条）。
 * v22(1.20.2)：把「没有证据」与「非播放页」彻底分开（三态判定）+ 检测心跳。
 *
 * ── v23(1.20.2 重打包)：**引入「播放页锚点」**（本轮核心修正）──
 *
 * v22 的残留缺陷：UNKNOWN（一条宽滑条都扫不到）时按钮**保持原状**，只有 6 秒超时才隐藏。
 * 实测（首页 / 各 mini-player 页面）这些页面**根本没有 SeekBar**，于是：
 *   · 离开播放页 → 一路 UNKNOWN → 按钮在非播放页上继续显示 **整整 6 秒**。
 * 而如果改成「UNKNOWN 就立刻隐藏」，播放页上主滑条被 RN 回收/控制面板隐藏的瞬间
 * 又会把按钮闪掉 —— 两个方向都错。
 *
 * 解法：判定到播放页（{@link #PAGE_PLAYER}）时，顺手记住主滑条所属的**页面级祖先容器**
 * （{@link ScanResult#anchorRef}，弱引用）。之后：
 *   · 滑条扫不到、但**锚点容器仍然真实可见** → 还在播放页（只是控制条被回收），保持显示；
 *   · 锚点容器**消失 / 不可见 / 被卸载**        → 确实离开了播放页，**立即隐藏**。
 * 这样既不再闪断，也不再在非播放页赖着 6 秒。
 *
 * ── v26：**判据不再依赖「绝对纵坐标」**（本轮根治）──
 *
 * 量化诊断（work_diag_12：录屏逐帧 × 日志时间戳）发现「有时快有时慢」的真身不是节奏参数，
 * 而是**判据本身在过渡期必然失准**：
 *
 *   1) 播放页**入场动画**时，页面从屏幕底部往上滑，它的主滑条会一路扫过整个下半屏。
 *      录屏铁证：`18:32:47.5` 画面正是「播放页从底部滑入」，同一时刻日志却打出
 *      `verdict=OTHER | bottom=y=2324 yRatio=83%` —— 主滑条被 82% 那条绝对位置判据
 *      误判成「mini-player 滑条」，于是按钮被误隐藏。
 *      退场时更极端：`bottom=y=2759 yRatio=99%`，滑条**依然完整落在屏内**（2759 < 2800），
 *      纯纵坐标检查根本拦不住。
 *   2) v25 把 alpha 门限降到 0.02，把「正在淡出的非活动页面」也放了进来 → 幽灵滑条变多。
 *
 * 两条修法：
 *   · **加「页面容器可见面积」这道门**（{@link #PAGE_CONTAINER_MIN_VISIBLE_RATIO}）：
 *     滑条所属的页面级容器必须在屏上占比 ≥60%（静止态实测 ≈92%~100%，过渡态掉到 ~20%~35%）。
 *     这条直接杀掉所有「页面正在滑入/滑出」的假证据，与滑条自身位置无关，故比 82% 稳健得多。
 *   · **PLAYER 必须「主滑条 + 时间文本」配对成功**（{@link #MAIN_SLIDER_TIME_LABEL_DY_DP}）：
 *     播放页主滑条下沿同排恒有 `04:48` / `-13:34` 两个时间文本；mini-player 进度条旁边
 *     一个时间文本都没有。配不上就不敢判 PLAYER（返回 UNKNOWN 交给锚点），
 *     绝不因为「配不上」就判成 OTHER。
 *   · alpha 门限 **0.02 → 0.05** 调回：宁可证据晚 0.2s 出现，也不能让幽灵页面混进来。
 * ─────────────────────────────────────────────────────────────────────
 */
public class SubtitleViewHook {
    private static final String TAG = "[DLsiteSoundFloat:View]";

    /** Class -> Method / Field / Boolean.FALSE(无访问器)。按类缓存，避免异常风暴。 */
    private static final Map<Class<?>, Object> TEXT_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    /** Class -> 是否 SeekBar 家族。 */
    private static final Map<Class<?>, Boolean> SEEK_BAR_CACHE = new ConcurrentHashMap<>();
    private static final Object NO_ACCESSOR = Boolean.FALSE;

    /** 整段文本就是一个时间：00:03 / 1:02:03 / -00:44（剩余时间带负号）。 */
    private static final Pattern TIME_ONLY = Pattern.compile("^-?\\d{1,2}:\\d{2}(:\\d{2})?$");
    /** 同一段文本里含两个时间：0:05 / 4:23 或 0:05 | -4:23。 */
    private static final Pattern TIME_RANGE =
            Pattern.compile("-?\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[/|｜]\\s*-?\\d{1,2}:\\d{2}(?::\\d{2})?");

    /** 宽滑条的宽度下限（占屏宽比例）。低于它的细进度条（曲目行里的播放进度）不作数。 */
    private static final float WIDE_SLIDER_MIN_RATIO = 0.45f;
    /**
     * 主播放滑条的纵向上限（占屏高比例）。超过它即判定为「底部播放条 / mini-player」的滑条。
     *
     * 实测（screenH=2800）：
     *   · 播放页**静止**时主滑条恒在 y=1799(64%)，偶尔 2044(73%)；
     *   · 首页/列表页 mini-player 滑条 y ∈ 2299..2759(82%~99%)。
     * 所以 82%（cy=2296）对「静止态」是干净的。
     *
     * ⚠️ 但要清楚它的局限：**页面入场/退场动画期间，播放页自己的主滑条会从屏幕底部一路
     * 滑到 64%**（录屏铁证：18:32:47.5 播放页正从底部滑入，主滑条被扫到 y=2324(83%)，
     * 于是被判成「底部 mini-player 滑条」→ 按钮被误隐藏）。也就是说这条位置判据在
     * **过渡期**必然产生假阳性，必须靠 {@link #PAGE_CONTAINER_MIN_VISIBLE_RATIO}
     * （页面容器必须占满屏幕）来兜住，不能只靠 y。
     */
    private static final float MAIN_SLIDER_MAX_Y_RATIO = 0.82f;
    /**
     * **滑条所属「页面级容器」必须在屏上占据的最小面积比例**（v26 新增，本轮根治手段）。
     *
     * 为什么必须有它：滑条自身的可见性不足以说明问题 —— 页面正在滑出屏幕时，滑条虽然
     * 还完整落在屏幕内（实测 y=2759，仍 <2800），整个页面却已经只剩一小条。此时
     * 「页面容器」的可见面积会骤降，一眼就能认出来。
     * 取 0.6：静止态播放页容器 ≈92%、首页容器 ≈100%；过渡态实测掉到 ~20~35%。
     */
    private static final float PAGE_CONTAINER_MIN_VISIBLE_RATIO = 0.6f;
    /**
     * 「播放页锚点」still-alive 判定所需的最小可见面积比例（v26 新增）。
     * 比 {@link #PAGE_CONTAINER_MIN_VISIBLE_RATIO} 略松（锚点可能被轻微滚动裁切），
     * 但足以在「整个页面滑走了」时判它失活。
     */
    private static final float ANCHOR_MIN_VISIBLE_RATIO = 0.5f;
    /**
     * 主滑条与「时间文本」配对的纵向容差（dp）。
     *
     * 播放页布局：滑条一行，`04:48` / `-13:34` 两个时间文本紧贴其**下沿同一行**
     * （实测文本中心比滑条中心低 30~75dp，取决于屏密度口径）。取 80dp 有充足余量，
     * 又远小于「到状态栏时钟 / 到别行文本」的距离，不会误配。
     * 底部 mini-player 的窄条**没有任何时间文本**（放大帧已确认），所以「能否配上时间文本」
     * 是区分「播放页主滑条」与「mini-player 进度条」的结构性证据。
     */
    private static final float MAIN_SLIDER_TIME_LABEL_DY_DP = 80f;
    /**
     * 祖先链累计 alpha 低于它即视为不可见（RN 用 opacity:0 隐藏非活动页面）。
     *
     * v25 曾 0.05 → 0.02（为治「显示慢」把淡入早期的活动页也放进来），
     * 结果**正在淡出的非活动页面也被放行**，幽灵滑条假阳性变多 → 按钮忽快忽慢。
     * v26 调回 0.05：换页时「证据晚 0.2s 出现」可以接受，**误判必须优先消灭**。
     */
    private static final float MIN_EFFECTIVE_ALPHA = 0.05f;
    /** 时间文本种类：整段就是时间（04:48 / -13:34）＝ 0。 */
    private static final int LABEL_TIME_ONLY = 0;
    /** 时间文本种类：一段文本里含两段时间（04:48 / -13:34）＝ 1。 */
    private static final int LABEL_TIME_RANGE = 1;
    /** 单次扫描最多记多少个时间文本（防御性上限）。 */
    private static final int MAX_TIME_LABELS = 64;
    /**
     * 「播放页锚点」的高度下限（占屏高比例）。
     * 滑条自身与其所在的控制条小容器远达不到这个比例，所以向上找到的第一个
     * ≥ 该比例的祖先就是**页面级容器**（RN 的一个 screen）。
     */
    private static final float ANCHOR_MIN_H_RATIO = 0.5f;

    /** 判定结果：证据不足，保持原状。 */
    public static final int PAGE_UNKNOWN = 0;
    /** 判定结果：播放页。 */
    public static final int PAGE_PLAYER = 1;
    /** 判定结果：非播放页（屏幕上出现了底部 mini-player）。 */
    public static final int PAGE_OTHER = 2;

    public static String verdictName(int v) {
        switch (v) {
            case PAGE_PLAYER: return "PLAYER";
            case PAGE_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    /**
     * 一次扫描的结果。所有字段都是「本次」的新鲜证据，绝不跨帧保留。
     */
    public static final class ScanResult {
        /** 屏上命中已加载字幕库的文本行（仅诊断用，**不参与页面判定**）。 */
        public final List<String> liveLines = new ArrayList<>();

        /** 屏幕中部的宽滑条（= 播放页主滑条）。 */
        public boolean hasMainSlider;
        public int mainSliderW;
        public int mainSliderH;
        public int mainSliderX;
        public int mainSliderY;
        public float mainSliderAlpha = 1f;
        /**
         * 【code 923 几何 4】「一行淡色小字简介」的底边屏幕 y（px）；{@code -1} = 没找到。
         *
         * 判定口径：主滑条**顶边之上**、离滑条最近的那条单行小字文本框的底边。
         * 它和 {@link #mainSliderY}（滑条中心）一起决定按钮组的垂直中点。
         */
        public int descLineBottom = -1;
        /** 【code 924】本次扫描收集到的「单行小字」候选总数（0 = 收集条件把简介行漏了）。 */
        public int descCandidateCount = 0;
        /**
         * 【code 925 修复】本次扫描到的**主滑条坐标是否已稳定**（跨帧一致）。
         *
         * ─────────────────────────────────────────────────────────────────────
         * ⚠️⚠️ code 924 的 P0 回归就出在「把稳定性判据做进了**探测**里」：
         *   `handleSlider()` 在不稳定时直接 {@code return}，于是
         *   {@code r.hasMainSlider} 永远为 false →
         *   ① `scan()` 里算 descLineBottom 的 {@code if (r.hasMainSlider)} 不成立 →
         *      descBottom 恒 -1 → 几何 4 中点永不生效；
         *   ② verdict 直接塌成 UNKNOWN / OTHER → **按钮根本不出来**。
         *   实测（Ari 日志 09:51）：PLAYER 判定从 code 923 的 **90 次** 跌到 **1 次**。
         *   根因是 RN 页面**每帧都在重排**，相邻两次扫描的 getLocationOnScreen
         *   差值几乎总 > 2px ⇒ 判据永远不满足 ⇒ 探测彻底瘫痪。
         *
         * 正确口径：**探测只负责「如实报告看到了什么」，稳定性是「消费端的事」**。
         *   · 本字段照实告诉消费端「这批坐标能不能直接拿来驱动 UI」；
         *   · 消费端（ActivityButtonHook）在 unstable 时**沿用上一次稳定值**，
         *     而不是拒绝这次扫描 —— 「判不出来」绝不能等价于「什么都没看到」。
         *   （同源教训：1.21.16「N 秒超时不是不存在的证据」。）
         * ─────────────────────────────────────────────────────────────────────
         */
        public boolean mainSliderStable = true;

        /** 屏幕底部的宽滑条（= 列表页 mini-player 的进度条）。 */
        public boolean hasBottomSlider;
        public int bottomSliderY;

        /** 本次扫描到几条宽滑条（诊断用）。 */
        public int wideSliderCount;
        /**
         * 本次扫描**否掉**了几条宽滑条（诊断用）。
         * 被否掉 = 所属页面容器在屏上占比 < {@link #PAGE_CONTAINER_MIN_VISIBLE_RATIO}
         * （页面正在滑入/滑出屏幕），或 alpha 不达标。
         */
        public int rejectedSliderCount;
        /** 是否存在「同一行两段时间、横向拉开」的进度显示。 */
        public boolean hasProgressPair;
        /** 屏上「整段就是时间」的文本个数（仅用于诊断）。 */
        public int timeTextCount;
        /**
         * 主滑条**能否配上时间文本**（v26：把它升级为 PLAYER 的必要条件）。
         *
         * 播放页主滑条下方同排必定有 `04:48` / `-13:34` 这样的时间文本；
         * 底部 mini-player 的进度条旁边一个时间文本都没有。所以「配对成功」
         * 是「这条宽滑条真的是播放页主滑条」的结构性证据。
         */
        public boolean mainSliderPaired;
        /** 与主滑条配对上的时间文本个数（诊断用，正常应为 2）。 */
        public int mainSliderTimeLabels;

        /**
         * 「播放页锚点」：主滑条所属的**页面级祖先容器**（弱引用）。
         *
         * 为什么需要它：RN 在切页 / 控制条隐藏 / 列表虚拟化回收时会让主滑条
         * **短暂消失**，此时判定只能是 UNKNOWN。若因此就隐藏按钮，播放页上按钮会闪断；
         * 若一律保持显示，离开播放页后按钮又会赖着不走（实测 6 秒）。
         * 有了锚点就能分辨：滑条没了但**页面容器还在**（还在播放页，保持显示）；
         * 页面容器也没了 / 不可见了（确实离开了，立即隐藏）。
         */
        public WeakReference<View> anchorRef;
        /** 锚点的诊断描述（类名 + 高度 + 子 View 数）。 */
        public String anchorDesc = "none";

        /**
         * 页面三态判定。
         *
         * 关键：**「没有滑条」≠「不是播放页」**。RN 换页 / 列表虚拟化回收 /
         * 切换动画期间主滑条都会短暂消失，此时必须返回 UNKNOWN 让调用方结合
         * {@link #anchorRef} 判断，而不是直接当成「非播放页」。
         *
         * ── v26：主滑条还要**配得上时间文本**才算 PLAYER ──
         * 上半屏的宽滑条 + 旁边有时间文本 → 播放页主滑条；仅有上半屏宽滑条但配不上
         * 时间文本 → 不敢下结论，返回 UNKNOWN（交给锚点通道决定，绝不因此判 OTHER）。
         */
        public int pageVerdict() {
            if (hasMainSlider && hasBottomSlider) {
                // 两条同时在 → RN 正在做页面切换（旧页还未卸载）。此刻判谁都不准。
                return PAGE_UNKNOWN;
            }
            if (hasMainSlider) {
                // 主滑条（上半屏）+ 时间文本配对成功 = 播放页主滑条，这才是干净证据。
                return mainSliderPaired ? PAGE_PLAYER : PAGE_UNKNOWN;
            }
            if (hasBottomSlider) {
                // 底部有 mini-player 的全宽滑条 —— 这是「非播放页」的**正面证据**。
                return PAGE_OTHER;
            }
            // 一条宽滑条都没有：可能是树没铺好，也可能是页面被回收。
            // 交给调用方用锚点 + 宽限期判断，不要在这里下结论。
            return PAGE_UNKNOWN;
        }

        /** 诊断用：输出本次扫描到的全部证据。 */
        public String describe(int screenW, int screenH) {
            return "main=" + (hasMainSlider
                        ? (mainSliderW + "x" + mainSliderH + " y=" + mainSliderY
                            + " yRatio=" + (screenH > 0 ? (mainSliderY * 100 / screenH) + "%" : "-")
                            + " x=" + mainSliderX
                            + " alpha=" + String.format(java.util.Locale.US, "%.2f", mainSliderAlpha))
                        : "none")
                    + " bottom=" + (hasBottomSlider
                        ? ("y=" + bottomSliderY
                            + " yRatio=" + (screenH > 0 ? (bottomSliderY * 100 / screenH) + "%" : "-"))
                        : "none")
                    + " wideSliders=" + wideSliderCount
                    + " seekWidthRatio=" + (hasMainSlider && screenW > 0
                        ? (mainSliderW * 100 / screenW) + "%" : "-")
                    + " progressPair=" + hasProgressPair
                    + " timeTexts=" + timeTextCount
                    + " liveLines=" + liveLines.size()
                    + " anchor=" + anchorDesc
                    + " paired=" + mainSliderPaired
                    + (hasMainSlider ? "(" + mainSliderTimeLabels + ")" : "")
                    + " rejected=" + rejectedSliderCount
                    + previewLiveLines();
        }

        /**
         * 证据指纹：只在**证据本身变化**时打日志。
         * 滑条纵坐标按 50px 量化 —— 它在页面静止时不变，切页时才跳，既能捕捉变化又不会刷屏。
         * 刻意**不含字幕文本内容**（播放中每秒都在变，会刷爆日志）。
         */
        public String evidenceSignature() {
            return (hasMainSlider ? ("M" + (mainSliderY / 50) + "@" + (mainSliderX / 50)) : "-")
                    + "/" + (hasBottomSlider ? ("B" + (bottomSliderY / 50)) : "-")
                    + "/w" + wideSliderCount
                    + "/p" + (hasProgressPair ? 1 : 0)
                    + "/t" + timeTextCount
                    + "/pl" + (mainSliderPaired ? 1 : 0)
                    + "/x" + rejectedSliderCount;
        }

        /** 把命中的字幕行内容打出来（最多 2 条、每条 16 字），便于直接定位误命中来源。 */
        private String previewLiveLines() {
            if (liveLines.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(" [");
            for (int i = 0; i < liveLines.size() && i < 2; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                String t = liveLines.get(i);
                sb.append(t.length() > 16 ? t.substring(0, 16) + "…" : t);
            }
            if (liveLines.size() > 2) {
                sb.append(" | +").append(liveLines.size() - 2);
            }
            return sb.append("]").toString();
        }
    }

    /** 一次扫描共用的上下文（避免每个 View 都去查 DisplayMetrics）。 */
    private static final class Ctx {
        int screenW;
        int screenH;
        float density;
        /** 本次扫描的根（decorView）。用于避免把根当成锚点。 */
        View root;
        /**
         * 【code 923 几何 4】本次扫描收集到的**单行小字**文本框：
         * 每项 = {left, top, right, bottom, height}（屏幕坐标 / px）。
         * 时间文本（{@code 04:48} / {@code 04:48 / 12:00}）不入列 —— 它们不是简介。
         */
        final List<int[]> textBoxes = new ArrayList<>();
        /** 【code 924】本次收集到的「单行小字」候选数（诊断用：真机上是不是 0）。 */
        int textBoxCount = 0;
    }

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        // 结构扫描在 ActivityButtonHook 的定时检测里调用，这里只保留轻量日志
        XposedCompat.log(TAG + " structural scanner ready (fresh+visibility-aware scan + page anchor)");
    }

    /**
     * 单次遍历整棵视图树，收集本次扫描的全部证据。
     * 关键：跳过不可见子树，且不依赖任何历史标记。
     */
    public static ScanResult scan(View root, SubtitleRepository repo) {
        ScanResult r = new ScanResult();
        if (root == null || repo == null) {
            return r;
        }
        Ctx ctx = new Ctx();
        ctx.root = root;
        ctx.density = root.getResources().getDisplayMetrics().density;
        ctx.screenW = root.getWidth() > 0 ? root.getWidth()
                : root.getResources().getDisplayMetrics().widthPixels;
        ctx.screenH = root.getHeight() > 0 ? root.getHeight()
                : root.getResources().getDisplayMetrics().heightPixels;

        // 每个元素 {中心x, 中心y, 左, 上, 宽, 高, 种类}（种类见 LABEL_TIME_ONLY / LABEL_TIME_RANGE）
        List<int[]> labels = new ArrayList<>();
        Rect visRect = new Rect();               // 复用同一个 Rect，避免每个 View 都 new
        collect(root, r, repo, labels, visRect, ctx);

        // 【code 923 几何 4】从收集到的文本框里挑出「一行淡色小字简介」：
        // 主滑条**顶边之上**、且离滑条最近的那一条（= 底边最大的那条）。
        // 距离上限 DESC_GAP 防止把更上面的音轨大标题当成简介。
        r.descCandidateCount = ctx.textBoxCount;
        if (r.hasMainSlider) {
            int sliderTop = r.mainSliderY - r.mainSliderH / 2;
            // 【code 924】距离上限 220dp -> 260dp：真机（1272x2772 @3.5）里
            //   简介行底边到滑条顶边的实测间距在大字 / 多行时会超过 220dp（= 770px），
            //   一超就被判「太远 → 不是简介」而落回旧口径 -> 中点永远不生效。
            //   上限的作用只是「别把更上面的音轨大标题当简介」，260dp 仍远小于标题距离。
            int maxGap = (int) (260f * ctx.density);
            int best = -1;
            for (int[] b : ctx.textBoxes) {
                if (b[3] <= sliderTop && sliderTop - b[3] <= maxGap && b[3] > best) {
                    best = b[3];
                }
            }
            r.descLineBottom = best;
        }

        List<int[]> timeOnly = new ArrayList<>();
        for (int[] L : labels) {
            if (L[6] == LABEL_TIME_ONLY) {
                timeOnly.add(L);
            }
        }
        r.timeTextCount = timeOnly.size();

        // 进度时间对：同一行（y 相近）且横向拉开 ≥ 35% 屏宽
        int maxDy = (int) (24 * ctx.density);
        float minGap = ctx.screenW * 0.35f;
        for (int i = 0; i < timeOnly.size() && !r.hasProgressPair; i++) {
            int[] a = timeOnly.get(i);
            for (int j = i + 1; j < timeOnly.size(); j++) {
                int[] b = timeOnly.get(j);
                if (Math.abs(a[1] - b[1]) <= maxDy && Math.abs(a[0] - b[0]) >= minGap) {
                    r.hasProgressPair = true;
                    break;
                }
            }
        }

        // ── v26 核心：主滑条必须与「时间文本」配对 ──
        // 纵向容差内、且横向与滑条区间有交叠的时间文本，才算「这一行的时间标签」。
        // 播放页主滑条下沿同排恒有 2 个时间文本；mini-player 进度条旁边一个都没有。
        if (r.hasMainSlider) {
            float tol = MAIN_SLIDER_TIME_LABEL_DY_DP * ctx.density;
            int sliderL = r.mainSliderX;
            int sliderR = r.mainSliderX + r.mainSliderW;
            int n = 0;
            for (int[] L : labels) {
                if (Math.abs(L[1] - r.mainSliderY) > tol) {
                    continue;
                }
                if (L[2] + L[4] > sliderL && L[2] < sliderR) {
                    n++;
                }
            }
            r.mainSliderTimeLabels = n;
            r.mainSliderPaired = n > 0;
        }
        return r;
    }

    private static void collect(View v, ScanResult r, SubtitleRepository repo,
                               List<int[]> labels, Rect visRect, Ctx ctx) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return; // 不可见的子树直接跳过（RN 切页后旧页面仍挂在树里）
        }
        try {
            // 这个 View 是否**真的落在屏幕可见区域内**。
            // 只查 getVisibility() 不够 —— RN 切页 / 滚动出屏后，旧页面与已滚走的内容
            // 依旧是 VISIBLE，但已不在屏幕上。
            boolean onScreen = v.getWidth() > 0 && v.getHeight() > 0 && v.getGlobalVisibleRect(visRect);

            if (onScreen) {
                CharSequence cs = getViewText(v);
                if (cs != null && cs.length() > 0) {
                    String t = cs.toString().trim();
                    if (t.length() > 0) {
                        boolean timeOnly = TIME_ONLY.matcher(t).matches();
                        boolean timeRange = !timeOnly && TIME_RANGE.matcher(t).find();
                        if ((timeOnly || timeRange) && labels.size() < MAX_TIME_LABELS) {
                            int[] loc = new int[2];
                            v.getLocationOnScreen(loc);
                            // {中心x, 中心y, 左, 上, 宽, 高, 种类}
                            labels.add(new int[]{
                                    loc[0] + v.getWidth() / 2,
                                    loc[1] + v.getHeight() / 2,
                                    loc[0], loc[1], v.getWidth(), v.getHeight(),
                                    timeOnly ? LABEL_TIME_ONLY : LABEL_TIME_RANGE});
                        }
                        if (timeRange) {
                            r.hasProgressPair = true;
                        }
                        // 【code 923 几何 4】收集单行小字文本框（排除时间文本）。
                        // 【code 924】高度上限 60dp -> **80dp**：真机里简介行常带一行换行，
                        //   57dp 的行高在部分字号下会越界（60dp 是「排除大标题」的约数，不是硬约束）；
                        //   大标题实测高度远大于 80dp（dump 里 252px = 72dp 是「两行标题」，
                        //   单行大标题也在 100dp+），放宽到 80dp 不会把标题吃进来。
                        if (!timeOnly && !timeRange && t.length() >= 2) {
                            int vh = v.getHeight();
                            if (vh > 0 && vh <= 80f * ctx.density) {
                                int[] bl = new int[2];
                                v.getLocationOnScreen(bl);
                                ctx.textBoxes.add(new int[]{
                                        bl[0], bl[1], bl[0] + v.getWidth(), bl[1] + vh, vh});
                                ctx.textBoxCount++;
                            }
                        }
                        if (t.length() >= 2
                                && looksLikeSubtitleText(t)
                                && repo.isKnownSubtitleText(t)
                                && !r.liveLines.contains(t)) {
                            r.liveLines.add(t);
                        }
                    }
                }
            }

            if (isSeekBar(v)) {
                handleSlider(v, r, ctx);
            }

            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    collect(vg.getChildAt(i), r, repo, labels, visRect, ctx);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 处理一条 SeekBar：判断它是不是「宽滑条」，以及它是主滑条还是底部 mini-player 滑条。
     *
     * 单独抽成方法是为了让 {@link #collect} 里**不出现 return**（return 会连带跳过子 View 遍历）。
     *
     * v26 新增三道门：
     *   1) 祖先链累计 alpha ≥ {@link #MIN_EFFECTIVE_ALPHA}（杀掉 opacity:0 的幽灵页面）；
     *   2) 横 / 纵向起点确实落在屏内（杀掉被 translate 推到屏幕外、实测见过 y=4559 的幽灵）；
     *   3) **所属「页面级容器」在屏上占比 ≥ {@link #PAGE_CONTAINER_MIN_VISIBLE_RATIO}** ——
     *      本轮的关键：页面正在滑入 / 滑出屏幕时，滑条自身可能**还完整落在屏内**
     *      （实测 y=2759 < 2800，纯纵向检查根本拦不住），但整个页面已经只剩一小条。
     *      不拦的话，过渡动画会把播放页**自己的主滑条**误当成 mini-player 滑条 → 判 OTHER →
     *      按钮被误隐藏，这正是「有时快有时慢」的根源。
     */
    /** 【code 924 bug1】跨帧保存「上一次采纳的滑条几何」，只用于稳定性判据。 */
    private static int sStableSliderY = Integer.MIN_VALUE;
    private static int sStableSliderH = Integer.MIN_VALUE;
    private static int sStableSliderX = Integer.MIN_VALUE;
    private static int sStableHit = 0;
    /** 【code 924 bug1】连续几次扫描坐标一致才采信。2 = 相邻两次一致。 */
    private static final int SLIDER_STABLE_HITS = 2;
    /** 【code 924 bug1】纵坐标容差（px）。【code 930 bug 3】从 2 放宽到 12：
     *   真机（work_diag_55）实测主滑条 y 在 1799/1805/1807 间抖动（±8px，RN 重排中间态），
     *   2px 容差下永远凑不齐「连续 2 帧一致」→ 925 式死锁 → sLastSliderCy 恒 -1 →
     *   按钮永远兜底位。12px 覆盖真实抖动且按钮位置差 ≤12px 不可感。 */
    private static final int SLIDER_STABLE_TOL = 12;

    private static void handleSlider(View v, ScanResult r, Ctx ctx) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0 || ctx.screenW <= 0 || w < ctx.screenW * WIDE_SLIDER_MIN_RATIO) {
            return;
        }
        if (!v.isShown()) {
            return;
        }
        float alpha = effectiveAlpha(v);
        if (alpha < MIN_EFFECTIVE_ALPHA) {
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int cy = loc[1] + h / 2;
        boolean xOnScreen = loc[0] + w > 0 && loc[0] < ctx.screenW;
        boolean yOnScreen = ctx.screenH <= 0 || (loc[1] < ctx.screenH && cy > 0);
        if (!xOnScreen || !yOnScreen) {
            return;
        }

        // ── 【code 924 bug1 → code 925 修复】滑条坐标的**稳定性判据** ──
        // code 923 的实测铁证（Ari 日志 00:31:06）：
        //   同一批 dump 显示 ReactSlider @70,1789 1132x63（真值 cy=1820），
        //   但相邻两次扫描一次报 y=1890（差 70px）、一次报 1820。
        //   ⇒ getLocationOnScreen 在**页面 settle 期间**会返回中间态坐标
        //     （RN 的重排/回弹是逐帧改 transform，布局尚未落定）。
        // 而 mainSliderY 是「读到就用」的：中间态 → 按钮位置算错 → 用户看到「上下摆动」。
        //
        // 🔴 code 924 的错误修法（已回退，勿重犯）：在这里 `return` 掉不稳定帧。
        //    后果是**判据把探测本身掐死了** —— RN 每帧都在重排，差值几乎总 > 容差，
        //    于是 hasMainSlider 永不置位，vertdict 塌成 UNKNOWN，按钮整个不出来。
        //    （铁证：PLAYER 判定 90 次 → 1 次；descBottom 恒 -1。）
        //
        // ✅ code 925 正确口径：**照实上报**，稳定性交给消费端决策。
        //    · 本函数**永远**把看到的滑条填进 r（探测职责 = 如实报告）；
        //    · r.mainSliderStable 表示「这批坐标能不能直接拿来驱动 UI」；
        //    · ActivityButtonHook 在 unstable 时沿用上一次稳定值（位置短暂不精确，
        //      但绝不跳变，也绝不因为「没判稳」就把按钮藏掉）。
        // 【code 930 bug 3 修复】稳定性判据重新审视（work_diag_55 实锤）：
        //   929 真机 sliderCy 在 1799/1805/1807/1999 间跳动 + 偶发 cy=0 幽灵，旧判据
        //   （sStableSliderY 只在 geomStable 时更新 + 2px 容差）一旦首帧采纳了错误基准
        //   （幽灵 / 转场 / 另一条滑条 at 1999）就永远不收敛 → sLastSliderCy 恒 -1 →
        //   按钮永远兜底位（286px，掉到播放控件下面）= Ari 复现的 bug 3。
        //   修复：① 容差 12px（见 SLIDER_STABLE_TOL，覆盖真实 ±8px 抖动）；② 坐标跳变时
        //   把比较基准钉到「当前帧原始坐标」（else 分支），下一帧只需接近当前帧即可收敛，
        //   不再卡在冻结的旧基准上永不达标；③ 连续 2 帧收敛即采信（SLIDER_STABLE_HITS=2）。
        boolean geomSame = Math.abs(cy - sStableSliderY) <= SLIDER_STABLE_TOL
                && Math.abs(h - sStableSliderH) <= SLIDER_STABLE_TOL
                && Math.abs(loc[0] - sStableSliderX) <= SLIDER_STABLE_TOL;
        if (geomSame) {
            if (sStableHit < SLIDER_STABLE_HITS) {
                sStableHit++;
            }
        } else {
            sStableHit = 1;
            // 基准跟到当前帧：换视图 / 转场 / 抖动导致坐标跳变时，把比较基准挪到这一帧，
            // 下一帧只需接近这一帧就收敛，而不是永远差一个冻结旧值 → 永不达标。
            sStableSliderY = cy;
            sStableSliderH = h;
            sStableSliderX = loc[0];
        }
        boolean geomStable = sStableHit >= SLIDER_STABLE_HITS
                || sStableSliderY == Integer.MIN_VALUE;
        if (geomStable) {
            sStableSliderY = cy;
            sStableSliderH = h;
            sStableSliderX = loc[0];
        }
        // 本条滑条是否「本帧可采信」：不稳定时标记出去，但**绝不 return**。
        r.mainSliderStable = r.mainSliderStable && geomStable;

        // 【v26 关键】页面容器必须真的占着屏幕 —— 否则这条滑条属于一个正在滑入 / 滑出的页面。
        View container = findPageContainer(v, ctx);
        if (container != null && visibleRectRatio(container) < PAGE_CONTAINER_MIN_VISIBLE_RATIO) {
            r.rejectedSliderCount++;
            return;
        }

        boolean inBand = ctx.screenH <= 0 || cy <= ctx.screenH * MAIN_SLIDER_MAX_Y_RATIO;
        r.wideSliderCount++;
        if (inBand) {
            if (!r.hasMainSlider) {
                r.hasMainSlider = true;
                r.mainSliderW = w;
                r.mainSliderH = h;
                r.mainSliderY = cy;
                r.mainSliderX = loc[0];
                r.mainSliderAlpha = alpha;
                // 记录「播放页锚点」：主滑条所属的页面级容器。
                if (container != null) {
                    r.anchorRef = new WeakReference<>(container);
                    r.anchorDesc = container.getClass().getSimpleName()
                            + " h" + container.getHeight()
                            + " vis" + Math.round(visibleRectRatio(container) * 100) + "%"
                            + " kids" + (container instanceof ViewGroup
                                ? ((ViewGroup) container).getChildCount() : 0);
                }
            }
        } else {
            if (!r.hasBottomSlider) {
                r.hasBottomSlider = true;
                r.bottomSliderY = cy;
            }
        }
    }

    /**
     * 从滑条向上寻找它所属的「页面级容器」：第一个高度 ≥ 屏高 {@link #ANCHOR_MIN_H_RATIO} 的祖先。
     *
     * 滑条自身与其所在控制条的小容器都远达不到这个高度比例，所以会一路走到页面容器
     * （mini-player 的滑条也会走到它所在的那个「页面/主容器」，因为 mini-player 本身很矮）。
     * 若一路走到扫描根（decorView）都没找到，则**返回 null** ——
     * 宁可不给容器（此时退化为「不做容器可见性校验」+「无证据宽限期」策略），
     * 也不要给一个永远可见的容器（那会让按钮在非播放页永远藏不掉）。
     */
    private static View findPageContainer(View slider, Ctx ctx) {
        View c = slider;
        for (int guard = 0; c != null && guard < 40; guard++) {
            ViewParent p = c.getParent();
            if (!(p instanceof View)) {
                return null;
            }
            View parent = (View) p;
            if (parent == ctx.root) {
                return null;
            }
            if (ctx.screenH > 0 && parent.getHeight() >= ctx.screenH * ANCHOR_MIN_H_RATIO) {
                return parent;
            }
            c = parent;
        }
        return null;
    }

    /**
     * 一个 View 在屏幕上**实际可见的面积**占它自身面积的比例（0~1）。
     *
     * 与 {@link #effectiveAlpha} 互补：alpha 管「透明度」，这里管「被裁掉多少」。
     * 页面做入场 / 退场动画时 alpha 往往仍是 1，但可见面积会随裁切骤降 ——
     * 这正是区分「正在过渡的页面」与「真的铺在屏幕上的页面」的关键信号。
     */
    private static float visibleRectRatio(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return 0f;
        }
        try {
            Rect rect = new Rect();
            if (!v.getGlobalVisibleRect(rect)) {
                return 0f;
            }
            long visible = (long) Math.max(0, rect.width()) * Math.max(0, rect.height());
            long own = (long) v.getWidth() * v.getHeight();
            if (own <= 0) {
                return 0f;
            }
            return (float) (visible / (double) own);
        } catch (Throwable e) {
            return 0f;
        }
    }

    /**
     * 祖先链累计 alpha（含自身）。任一层不可见 → 返回 0。
     *
     * 用途：RN 的非活动页面常常仍挂在视图树里，只是 opacity:0 / display:none。
     * display:none → GONE（getVisibility() 已能挡）；opacity:0 → setAlpha(0)，只能这样查。
     */
    private static float effectiveAlpha(View v) {
        float a = 1f;
        View c = v;
        for (int guard = 0; c != null && guard < 64; guard++) {
            if (c.getVisibility() != View.VISIBLE) {
                return 0f;
            }
            a *= c.getAlpha();
            if (a < MIN_EFFECTIVE_ALPHA) {
                return 0f;
            }
            ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return a;
    }

    /**
     * 判定一个 View 是否**真的还显示在屏幕上**（供按钮控制器检查播放页锚点是否还存活）。
     *
     * 要求：仍挂在窗口上、VISIBLE、有尺寸、祖先链累计 alpha 达标、**且可见面积不低于自身 50%**
     * （v26 新增最后一条：页面正在滑入 / 滑出时 alpha 常仍是 1，只有可见面积会骤降）。
     * 任一不满足即视为「这个页面已经不在了」。
     */
    public static boolean isEffectivelyVisible(View v) {
        if (v == null || !v.isAttachedToWindow()) {
            return false;
        }
        if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        if (effectiveAlpha(v) < MIN_EFFECTIVE_ALPHA) {
            return false;
        }
        if (visibleRectRatio(v) < ANCHOR_MIN_VISIBLE_RATIO) {
            return false;
        }
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r);
    }

    /**
     * 锚点在屏幕上占据的面积比例（0~1，仅诊断用）。
     *
     * 用于日志里判断「锚点是不是选得过高」（例如选到常驻的导航容器 → 永远贴近 1.0，
     * 那它在非播放页也永远可见，按钮就藏不掉）。健康值应在 **0.6~0.95** 之间。
     */
    public static float visibleAreaRatio(View v, int screenW, int screenH) {
        if (v == null || screenW <= 0 || screenH <= 0) {
            return 0f;
        }
        try {
            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r)) {
                return 0f;
            }
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            return (float) (area / (double) ((long) screenW * screenH));
        } catch (Throwable e) {
            return 0f;
        }
    }

    /** 是否 SeekBar 家族（ReactSlider / AppCompatSeekBar / SeekBar 都算）。按类缓存。 */
    private static boolean isSeekBar(View v) {
        Class<?> cls = v.getClass();
        Boolean cached = SEEK_BAR_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            if (c.getName().endsWith("SeekBar")) {
                result = true;
                break;
            }
            c = c.getSuperclass();
        }
        SEEK_BAR_CACHE.put(cls, result);
        return result;
    }

    /**
     * 兼容 android.widget.TextView 与 RN ReactTextView 的取文本。
     * 反射访问器按 Class 缓存，杜绝「每个 View 都抛异常」。
     */
    private static CharSequence getViewText(View v) {
        if (v == null) {
            return null;
        }
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 0) {
                    return cs;
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> cls = v.getClass();
        Object accessor = TEXT_ACCESSOR_CACHE.get(cls);
        if (accessor == null) {
            accessor = findTextAccessor(cls);
            TEXT_ACCESSOR_CACHE.put(cls, accessor);
        }
        if (accessor == NO_ACCESSOR) {
            return null;
        }
        try {
            Object o;
            if (accessor instanceof Method) {
                o = ((Method) accessor).invoke(v);
            } else {
                o = ((Field) accessor).get(v);
            }
            if (o instanceof CharSequence) {
                return (CharSequence) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 为一个 View 类查找文本访问器：优先 getText()，其次字段 mText。 */
    private static Object findTextAccessor(Class<?> cls) {
        try {
            Method m = cls.getMethod("getText");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
        }
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("mText");
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return NO_ACCESSOR;
    }

    private static boolean looksLikeSubtitleText(String t) {
        if (t == null || t.trim().length() < 2) {
            return false;
        }
        // 必须含至少一个日文（假名/汉字），避开纯数字/英文的 UI 标签
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (isJapanese(cp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJapanese(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)      // ひらがな + カタカナ
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // 漢字
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 拡張漢字
                || (cp >= 0xFF66 && cp <= 0xFF9D)  // 半角カタカナ
                || (cp >= 0x3000 && cp <= 0x303F); // 句読点（、、。等）
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v33 诊断：一次性把视图树结构打到日志
    //
    //  用途（Ari 2026-09-14 需求②）：打算把「悬浮窗开关按钮」**注入到播放器传输控件行**里
    //  （跟播放键同一排），靠宿主视图树的挂载/销毁天然实现「零延迟显隐」。
    //  但 RN 的布局是 Yoga 算好后直接 `view.layout(...)`，ViewGroup 并不参与子视图定位，
    //  盲插一个外来子 View 有把播放器 UI 搞乱的风险 —— 所以先打一份**真实结构**再动手。
    //
    //  ⚠️ v34 教训：第一次 dump 只打到深度 **8**，而 RN 真正的内容从深度 **9** 才开始 ——
    //  日志里最后一行停在第二个 `SafeAreaProvider`，一个播放器控件都没看到。
    //  深度上限改成 22（RN 的包装层很多，8 根本不够）。
    //
    //  另外 v34 已经论证过「注入控件行」**不可行**（ReactViewGroup 无条件拦截触摸，
    //  外来 View 收不到点击，见 ActivityButtonHook 类头 v34 段），
    //  这份 dump 现在只作为**结构参考**保留。
    //
    //  输出格式（每行一个 View，按深度缩进，坐标是**屏幕绝对坐标**）：
    //      ClassName @x,y wxh [vis=…] [clickable] [kids=N] [text="…"]
    // ─────────────────────────────────────────────────────────────────────
    private static final int DUMP_MAX_DEPTH = 22;
    private static final int DUMP_MAX_LINES = 260;

    /** 打一份以 {@code root} 为根的视图树结构；「只打一次」由调用方保证。 */
    public static void dumpViewTree(ViewGroup root) {
        if (root == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int[] budget = {DUMP_MAX_LINES};
        dumpRec(root, 0, 0, 0, sb, budget);
        XposedCompat.log(TAG + " ===== view tree dump begin (<= " + DUMP_MAX_LINES + " lines) =====");
        for (String line : sb.toString().split("\n")) {
            XposedCompat.log(TAG + " DUMP " + line);
        }
        XposedCompat.log(TAG + " ===== view tree dump end =====");
    }

    private static void dumpRec(View v, int depth, int ox, int oy,
                                StringBuilder sb, int[] budget) {
        if (v == null || depth > DUMP_MAX_DEPTH || budget[0] <= 0) {
            return;
        }
        budget[0]--;
        int x = ox + v.getLeft();
        int y = oy + v.getTop();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getSimpleName())
                .append(" @").append(x).append(',').append(y)
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v.getVisibility() != View.VISIBLE) {
            sb.append(" vis=").append(v.getVisibility());
        }
        if (v.isClickable()) {
            sb.append(" clickable");
        }
        if (v instanceof ViewGroup) {
            sb.append(" kids=").append(((ViewGroup) v).getChildCount());
        }
        CharSequence t = getViewText(v);
        if (t != null && t.length() > 0) {
            String s = t.toString().replace('\n', ' ');
            if (s.length() > 30) {
                s = s.substring(0, 30) + "…";
            }
            sb.append(" text=\"").append(s).append('"');
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpRec(g.getChildAt(i), depth + 1, x, y, sb, budget);
            }
        }
    }
}
