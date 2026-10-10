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
package io.github.ariinyume.dlsitesoundfloat.data;

/**
 * 【code 1004】播放列表「选择键 / 身份键」的**零状态纯判定层**。
 *
 * <h3>── 为什么需要它（Ari 2026-10-10 报的 bug）──</h3>
 * 现象：**点击未缓存音频，App 显示有字幕，但插件显示「无字幕」**（录屏
 * {@code Record_2026-10-10-18-40-26} + 日志 {@code LSPosed_20261010_184115}）。
 *
 * <p>真机日志把根因钉死在**两个键的可见时机不同**上：
 * <pre>
 *   18:40:58.391  statusMap: currentIndex=1→3  duration=0.0  isBuffering=true    ← 宿主已选新轨
 *   18:40:58.556  Loaded 84 cues from JSON                                       ← 新轨的字幕已到
 *   18:41:00.391  statusMap: currentIndex=3    duration=0.0  （仍是 0）
 *   18:41:04.430  statusMap: currentIndex=3    duration=944.784  isLoaded=true   ← 时长才就绪
 *   18:41:04.429  >>> track changed … idx=1 -> idx=3 [index-gate]                ← 换轨通知迟到 5.9s
 * </pre>
 *
 * <p>{@code currentIndex} 在**切换音轨那一瞬间**就翻了，而 {@code duration} 要等新音源
 * 装载完才有值。模块的换轨通知用的是三元组身份 {@code (trackCount, durationMs, currentIndex)}
 * —— 时长没就绪前那一路读数被当作「非活跃列表」整条丢弃（见
 * {@code PlayerPositionHook#consume} 的 {@code authoritative} 门），于是：
 * <ol>
 *   <li>宿主在时长就绪**之前**就把新轨的字幕 JSON 发过来了（实测提前量 165ms）；</li>
 *   <li>模块给这份 JSON 盖的「主人印章」还是**上一轨**的身份；</li>
 *   <li>换轨通知 5.9s 后才到，此时 {@code lastJson=5873ms ago} 已远超
 *       {@code PRELOAD_TOLERANCE_MS(3500)} ⇒ 模块把它当成「上一轨的旧数据」隔离掉
 *       ⇒ 显示层清空、{@code softNoSubtitles=true}、悬浮窗 5s 后自动关闭；</li>
 *   <li>宿主**不会重发**这份 JSON（它已经发过了）⇒ 状态永不撤销 ⇒ **永久「无字幕」**，
 *       而 App 自己照常显示字幕。</li>
 * </ol>
 *
 * <h3>── 解法：多一个「早就能读到」的选择键 ──</h3>
 * 只取 {@code (trackCount, currentIndex)} 打包成<b>选择键</b>。它在宿主切换选择的那一刻
 * 就可读，不依赖时长。于是可以在**加载 JSON 的那一刻**判断：
 * {@code 选择键} 已经跑到 {@code 身份键} 前面 ⇒ 这份 JSON 只可能是**新轨**的，
 * 换轨通知到达时直接认领（{@link #cuesBelongToTarget}），不必等宿主重发。
 *
 * <h3>── 为什么不能直接用选择键当换轨判据 ──</h3>
 * 宿主切<b>作品</b>时 {@code currentIndex} 恒为 0（6 轨列表与 1 轨列表都是 idx=0，见
 * {@code PlayerSourceHook} 的 code 978 段），单看选择键**一次都发现不了**。
 * 所以：<b>换轨判据仍用三元组身份</b>（一字不改），选择键只用于**归属判定**。
 *
 * <h3>── 为什么「选择跑在前面」这条判据是安全的 ──</h3>
 * 只有当 {@code (trackCount, currentIndex)} 与身份键里的 {@code (trackCount, currentIndex)}
 * **同一列表、不同序号**时才算「跑在前面」。跨作品同序号的情形（选择键与身份键序号相同）
 * 一律返回 false ⇒ 退回旧行为（隔离 + 等宿主重发），不会拿上一部作品的字幕冒充新作品。
 *
 * <p>本类**只做纯算术**：不持有任何状态、不读不写任何字段、无 Android 依赖 ⇒ 可单测
 * （见 {@code GoldenVectorTest} 里的真机黄金向量）。
 *
 * <h3>── 【code 1005】选择键也失效的那一半：换作品 ──</h3>
 * {@code Ari 2026-10-10 21:42} 又报了一次同样的现象，但这次是**切作品/章节**：
 * 宿主把播放列表整个销毁重建，重建期报 {@code trackCount == 0}（选择键无法取值），
 * 而新轨的 JSON 恰恰落在「列表已销毁、新列表还没报出来」的那 4.7 秒里。
 * 补上的判据是{@link #cuesBelongToRebuiltPlaylist 列表重建窗口}：从 {@code trackCount == 0}
 * 到新的非零 {@code trackCount} 之间到达的 JSON，只可能属于重建后的新列表。
 */
public final class PlaylistKey {

    private PlaylistKey() {
    }

    /** 选择键里 {@code trackCount} 的权重（{@code trackCount ≤ 99999}、{@code idx ≤ 99999} ⇒ 不碰撞）。 */
    private static final long SELECTION_TC_SCALE = 100000L;

    /**
     * 身份键里 {@code idx} 的低位掩码 —— 与
     * {@code PlayerSourceHook#makeIdentity} 的 {@code tc*10^12 + durMs*10^3 + idx} 对齐。
     */
    private static final long IDENTITY_IDX_SCALE = 1000L;

    /** 身份键里 {@code trackCount} 的权重（同上）。 */
    private static final long IDENTITY_TC_SCALE = 1000000000000L;

    /**
     * 把 {@code (trackCount, currentIndex)} 打包成**选择键**。
     *
     * @return {@code 0} = 未知（读不到列表 / 序号非法）—— 调用方一律把 0 当「不认识」，
     *         绝不能与真实列表相撞（真实列表 {@code trackCount ≥ 1} ⇒ 选择键 {@code ≥ 100000}）。
     */
    public static long makeSelection(int trackCount, int idx) {
        if (trackCount <= 0 || idx < 0) {
            return 0L;
        }
        return (long) trackCount * SELECTION_TC_SCALE + idx;
    }

    /** 选择键里的 {@code trackCount}；{@code -1} = 未知。 */
    public static int selectionTrackCount(long selection) {
        return selection <= 0L ? -1 : (int) (selection / SELECTION_TC_SCALE);
    }

    /** 选择键里的 {@code currentIndex}；{@code -1} = 未知。 */
    public static int selectionIndex(long selection) {
        return selection <= 0L ? -1 : (int) (selection % SELECTION_TC_SCALE);
    }

    /** 身份键里的 {@code trackCount}；{@code -1} = 未知。 */
    public static int identityTrackCount(long identity) {
        return identity <= 0L ? -1 : (int) (identity / IDENTITY_TC_SCALE);
    }

    /** 身份键里的 {@code currentIndex}；{@code -1} = 未知。 */
    public static int identityIndex(long identity) {
        return identity <= 0L ? -1 : (int) (identity % IDENTITY_IDX_SCALE);
    }

    /**
     * 「宿主的选择已经跑在身份前面了吗」—— 在**加载字幕 JSON 的那一刻**用它判断这份 JSON
     * 的归属。
     *
     * <p>为 true 的唯一含义：<b>同一份播放列表上，选择键的序号与身份键的序号不一致</b>
     * ⇒ 宿主的 {@code currentIndex} 已经翻了，而带时长的那一路读数还没跟上。
     * 此刻到达的字幕 JSON 只可能是宿主为新选择取回的那一份。
     *
     * <p>两侧任一未知、或 {@code trackCount} 不同（= 换了作品/列表，不是「选择跑到前面」）
     * 都返回 {@code false}，让调用方退回旧判据。
     */
    public static boolean selectionAheadOfIdentity(long selection, long identity) {
        if (selection <= 0L || identity <= 0L) {
            return false;
        }
        if (selectionTrackCount(selection) != identityTrackCount(identity)) {
            return false;
        }
        return selectionIndex(selection) != identityIndex(identity);
    }

    /**
     * 手上的 cues 是不是这次换轨**目标轨**的字幕 —— {@code [code 1004]} 的认领判据。
     *
     * @param loadedAheadOfIdentity 这份 cues 加载时「选择已跑在身份前面」（{@link #selectionAheadOfIdentity}）
     * @param cuesOwnerSelection    这份 cues 加载那一刻的选择键
     * @param targetSelection       本次换轨目标的选择键
     * @return true = 宿主早已为新轨取回字幕，直接恢复渲染即可（不必等它重发）
     */
    public static boolean cuesBelongToTarget(boolean loadedAheadOfIdentity,
                                             long cuesOwnerSelection,
                                             long targetSelection) {
        return loadedAheadOfIdentity
                && targetSelection > 0L
                && targetSelection == cuesOwnerSelection;
    }

    /**
     * 【code 1005】手上的 cues 是不是**列表重建后的新轨**的字幕 —— 第二条认领判据。
     *
     * <h3>── 为什么 1004 的选择键在换作品时会失效 ──</h3>
     * 宿主切<b>作品 / 章节</b>时会把播放列表整个销毁重建，重建过程中会报出
     * {@code trackCount == 0}：
     * <pre>
     *   21:41:53.139  statusMap: currentIndex=0 trackCount=0 duration=0.0 idle   ← 列表已销毁
     *   21:41:53.505  optimized/c0d71242….json 到达（= 新轨 idx=2 的字幕，仅隔 366ms）
     *   21:41:58.241  statusMap: currentIndex=2 trackCount=8 duration=0.0        ← 新列表第一次可读
     *   21:41:59.654  >>> track changed  tc=6 dur=835560 idx=5 -> tc=8 dur=1286040 idx=2
     *                 | lastJson=6135ms ago -> SUSPEND … [973 previous-track cues quarantined]
     * </pre>
     * {@code trackCount == 0} ⇒ {@link #makeSelection} 只能返回 {@code 0}（未知），
     * 选择键**根本没得记**；而新列表的身份键（{@code tc=8}）又与旧列表（{@code tc=6}）
     * {@code trackCount} 不同 ⇒ {@link #selectionAheadOfIdentity} 也必然 false
     * （那是防「跨作品冒充」的安全阀，不能拆）。
     *
     * <h3>── 判据：只在「重建窗口」内到达的 JSON 才认领 ──</h3>
     * 「重建窗口」= <b>从观察到 {@code trackCount == 0} 起，到新的非零 {@code trackCount} 出现止</b>。
     * 这段窗口里宿主的旧列表**已经不存在了**，此刻请求并返回的字幕 JSON 只可能属于
     * <b>重建后的新列表</b> ⇒ 换轨通知到达时直接认领，不必等宿主重发（它也不会重发）。
     *
     * <p>再加两道保险，防止「陈旧印章」被后来的无关换轨错误兑现：
     * <ol>
     *   <li><b>换了列表</b>：换轨目标的 {@code trackCount} 必须与盖章时那份**不同**
     *       （相同 ⇒ 只是同一列表内换序号，那该走 code 1004 的选择键，不是这条）；</li>
     *   <li><b>时限</b>：从这份 cues 到达到换轨通知不得超过 {@code toleranceMs}
     *       —— 真机实测是 6135ms。</li>
     * </ol>
     *
     * @param arrivedDuringRebuild 这份 cues 是否在「列表重建窗口」内到达
     * @param newIdentity          本次换轨目标轨的身份键（0 = 未知）
     * @param cuesOwnerIdentity    这份 cues 盖的主人是哪份列表的身份键（0 = 盖章时未知）
     * @param arrivedAtMs          这份 cues 的到达时刻（{@code uptimeMillis}）
     * @param nowMs                本次换轨通知的时刻
     * @param toleranceMs          认领时限（超过则放弃）
     * @return true = 这是重建后新列表的字幕，直接恢复渲染
     */
    public static boolean cuesBelongToRebuiltPlaylist(boolean arrivedDuringRebuild,
                                                      long newIdentity,
                                                      long cuesOwnerIdentity,
                                                      long arrivedAtMs,
                                                      long nowMs,
                                                      long toleranceMs) {
        if (!arrivedDuringRebuild || newIdentity == 0L) {
            return false;
        }
        int newTc = identityTrackCount(newIdentity);
        if (newTc <= 0 || newTc == identityTrackCount(cuesOwnerIdentity)) {
            return false;                       // 同一份列表 ⇒ 不是「重建」，交给 code 1004 判
        }
        if (arrivedAtMs <= 0L || nowMs < arrivedAtMs) {
            return false;
        }
        return nowMs - arrivedAtMs <= toleranceMs;
    }
}
