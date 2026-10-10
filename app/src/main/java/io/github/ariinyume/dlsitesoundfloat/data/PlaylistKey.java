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
 *
 * <h3>── 【code 1006】窗口也不可靠：换成「作品键」这个**正证据** ──</h3>
 * {@code Ari 2026-10-10 22:53} 第三次报同一现象（连跳几个作品时又出现一次）。真机日志
 * {@code LSPosed_20261010_225318} 把前两条判据的命门一起暴露了：
 * <pre>
 *   22:51:02.563  Loaded 73 cues（= 新作品 RJ01126292 的字幕，宿主在点击那一刻就取回了）
 *   22:51:02.673  [code 1005] playlist destroyed (trackCount=0)   ← 窗口此时才开，晚了 110ms
 *   22:51:09.847  >>> track changed tc=7 dur=827472 idx=3 -> tc=5 dur=745032 idx=0
 *                 | lastJson=7284ms ago -> SUSPEND … [973 previous-track cues quarantined]
 *   22:51:24.847  [code 960] soft verdict（软裁决定案）
 *   22:51:27.719  Loaded 99 cues（宿主**again** 重发）→ FALSE NEGATIVE，空了 17.9 秒
 * </pre>
 * 两条旧判据各自缺什么，一眼可见：
 * <ul>
 *   <li>{@code trackCount == 0} 不是「销毁」的**边沿** —— 宿主同时轮询多个**已结束**的旧列表实例
 *       （真机 {@code eb292cf}/{@code a5a32f2}/{@code 8dd3553}，永远报 {@code trackCount=0
 *       playbackState=ended}），窗口于是每秒重开 1~3 次，成了永远悬着的噪声；</li>
 *   <li>更要命的是**时序**：这条 73 cues 的 JSON 比「窗口打开」早 110ms、比「换轨通知」早
 *       7284ms 到达 —— 宿主取字幕的时机是**用户点击那一刻**，而播放列表切换与换轨通知都要
 *       等音源装载（实测提前量 4627 / 6419 / 7284ms）。这段时间里选择键与身份键**都还是旧作品的**
 *       ⇒ 两条旧判据都只能看见「旧作品」，永远判不对。</li>
 * </ul>
 * 唯一能在这段空档里**正面指认**这份 JSON 归属的，是它自己的 URL：
 * {@code …/content/work/doujin/RJ01127000/RJ01126292/optimized/<hash>.json} 里的作品路径。
 * 于是第三条判据只看一件事：<b>这份 JSON 的「作品键」与上一份 JSON 的作品键不同</b>
 * ⇒ 宿主的取字幕动作已经跨到另一部作品 ⇒ 换轨通知到达时直接认领
 * （见 {@link #cuesBelongToChangedWork}）。它不依赖 {@code trackCount}、不依赖时长、
 * 也不依赖窗口是否开着，因此不受「窗口被误开/被提前关掉」影响。
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

    /**
     * 【code 1006】从字幕 JSON 的**请求 URL** 里取出「作品键」—— 归属判定的**正证据**。
     *
     * <p>真机 URL 形如：{@code https://play.dl.dlsite.com/content/work/doujin/RJ01127000
     * /RJ01126292/optimized/4e4a45e8….json?Key-Pair-Id=…&Policy=…&Signature=…}。
     * 取法刻意**不认域名、不认路径结构**，只做两步纯字符串处理：
     * <ol>
     *   <li>砍掉 {@code ?} 起的查询串（每次请求都不同，必须去掉，否则同一份 JSON 的两次请求会
     *       被当成两部作品）；</li>
     *   <li>砍掉最后一个 {@code /} 之后的文件名（同一部作品的每一条音轨各有自己的 hash 文件名，
     *       去掉它才能得到「一部作品一个键」）。</li>
     * </ol>
     * 于是同一部作品的所有音轨得到同一个键（…{@code /RJ01126292/optimized}），
     * 换作品必得到不同的键。宿主日后改目录名也不影响 —— 只要作品号还在路径里。
     *
     * @return 作品键；{@code null} = 拿不到（URL 为空 / 没有路径分隔）—— 调用方一律当「无证据」
     */
    public static String workKeyOf(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        String s = q >= 0 ? url.substring(0, q) : url;
        int slash = s.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String key = s.substring(0, slash);
        return key.isEmpty() ? null : key;
    }

    /**
     * 【code 1006】手上的 cues 是不是**刚换到的那部作品**的字幕 —— 第三条认领判据。
     *
     * <h3>── 为什么需要它（code 1004 / 1005 都没覆盖的那段空档）──</h3>
     * 宿主是「用户点击 → 立刻取新轨字幕 → 装载音源 → 才报新索引/新身份」。真机实测这份 JSON 比
     * 换轨通知早 <b>4627 / 6419 / 7284ms</b>（{@code LSPosed_20261010_225318}），
     * 而在这段空档里宿主报的**选择键与身份键全都还是旧作品的**（列表对象直到点击后 100~300ms
     * 才换成新的，而新列表又要几秒才有 {@code duration}）。⇒ 只看那两条键，怎么判都是「旧作品」。
     *
     * <p>本判据改问一个**宿主自己写死的事实**：这份 JSON 的 URL 属于哪部作品。
     * 「这份 JSON 的作品键 ≠ 上一份 JSON 的作品键」只有一个解释：<b>宿主的取字幕动作已经跨到了
     * 另一部作品</b>。所以换轨通知到达时（且这份 cues 还没被别的路径认领）直接认领即可。
     *
     * <h3>── 为什么不会拿上一部作品的字幕冒充（安全阀）──</h3>
     * 判据要求 {@code workChangeKey} 与手上 cues 的作品键**完全相等**（否则返回 false）：
     * {@code workChangeKey} 只在「一次加载的作品键 ≠ 上一次加载的作品键」时才被写下
     * （见 {@code SubtitleRepository#loadFromJsonArrayInternal}），且会被
     * 「平静加载 / 已认领 / 硬裁决」三处清掉。⇒ 想冒充必须同时满足「换轨目标那一次
     * 换作品正是手上这份 cues 带来的」——那本来就是真的。
     *
     * @param cuesWorkKey    手上这份 cues 的作品键（{@code null}/空 = 没拿到 URL ⇒ 不认）
     * @param workChangeKey  最近一次「换了作品」的作品键（{@code null} = 没有待兑现的换作品）
     * @param workChangeAtMs 那次换作品的时刻（{@code uptimeMillis}；{@code ≤ 0} = 无）
     * @param nowMs          本次换轨通知的时刻
     * @param toleranceMs    认领时限（超过则放弃，防陈旧印章被后来的无关换轨兑现）
     * @return true = 这份 cues 就是刚换到的那部作品的，直接恢复渲染
     */
    public static boolean cuesBelongToChangedWork(String cuesWorkKey,
                                                  String workChangeKey,
                                                  long workChangeAtMs,
                                                  long nowMs,
                                                  long toleranceMs) {
        if (cuesWorkKey == null || cuesWorkKey.isEmpty()) {
            return false;
        }
        if (workChangeKey == null || !workChangeKey.equals(cuesWorkKey)) {
            return false;                       // 换作品的不是手上这份 cues ⇒ 无证据
        }
        if (workChangeAtMs <= 0L || nowMs < workChangeAtMs) {
            return false;
        }
        return nowMs - workChangeAtMs <= toleranceMs;
    }
}
