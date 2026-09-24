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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.ariinyume.dlsitesoundfloat.BuildConfig;
import io.github.ariinyume.dlsitesoundfloat.view.NotificationBadgeView;
import io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI 侧：把「当前字幕行」注入状态栏（ColorOS 16 优先，兼容小米/原生/其它）。
 *
 * 逻辑参考 base.apk 的 StatusBarLyricHooks，并按本模块需求扩展：
 *  - 钩 {@code PhoneStatusBarView#onAttachedToWindow} 拿到状态栏根；
 *  - 按**资源名 / 类名特征**找锚点（ColorOS 私有名优先，回退 AOSP），注入
 *    FrameLayout（内含「通知数徽标」+「单行字幕」两个 TextView）；
 *  - **字体与颜色**：字体从状态栏时钟（{@code clock}）同步一次；**颜色每次显示 / 每次心跳
 *    都重新同步**，并额外 hook {@code TextView.setTextColor} 做事件级镜像 —— 浅色状态栏
 *    （深色图标）时字幕会跟着变黑，不再出现「浅底白字隐身」；
 *  - **滚动**：不用系统跑马灯（它会循环着往回弹），改 ValueAnimator 驱动的**单程左移**
 *    （scrollX 0 → 文本宽-可视宽），时长取自该字幕行的播放时长；新行出现时**先静置
 *    {@link #SCROLL_START_DELAY_MS} 再起滚**；
 *  - **遮挡**：字幕显示期间隐藏状态栏时钟 + 左侧通知图标区（**每次重新收集视图引用**，
 *    并 hook {@code View.setVisibility} 做事件级压制 —— 状态栏重建换实例也不会漏），
 *    最左侧画「实心圆 + 数字」的通知数徽标；
 *  - **流体云避让**：动态探测流体云（ColorOS 的胶囊）左边界，把字幕右边界卡在它左边；
 *  - **暂停复位**：App 侧检测到暂停会把 line 置空 → 这里**宽限 {@link #HIDE_GRACE_MS} 后**
 *    隐藏字幕并还原时钟/通知图标（宽限是为了让切页/换轨造成的瞬时暂停不闪出时钟）。
 *
 * ⚠️ Android 13+（API 33+）动态注册广播必须声明可见性标志（RECEIVER_EXPORTED /
 *    RECEIVER_NOT_EXPORTED），否则 registerReceiver 直接抛 SecurityException —— 而本模块
 *    的跨进程通道（App 进程 → SystemUI 进程，两者 UID 不同）必须用 RECEIVER_EXPORTED。
 *    这条修复曾在 1.21.5 因「同文件多次 Edit 互相覆盖」而丢失，最终用「整文件 Write」重做。
 *    **改动本文件请优先整体重写，不要对同一文件连续多次 Edit。**
 *
 * 版本史（排查「SystemUI 到底跑了哪版 dex」时看 hook() 首行日志）：
 *  - 1.21.7 字体/颜色一次性同步 + 单程滚动 + 遮挡 + 徽标 + 暂停复位
 *  - 1.21.8 时钟实例失效导致「切页时时钟弹出」→ 逐次重收集视图 + setVisibility 事件压制；
 *           颜色改为每帧同步 + setTextColor 事件镜像（浅色底黑字）；流体云左边界避让；
 *           新行起滚前静置 100ms
 *  - 1.21.9 ①暂停后时钟不恢复：恢复动作本身被自己的 setVisibility 压制 hook 吃掉
 *           （恢复时 sSuppressed 仍为 true → 刚设回 VISIBLE 又被改回 GONE，且快照已 clear）
 *           → 恢复前先落旗标 + sRestoring 门，恢复动作不再被镜像 hook 拦截；
 *           ②浅色底字形糊边：去掉硬写的 1.5px 黑阴影，改为**跟随状态栏时钟的阴影设置**；
 *           ③字幕仍被流体云压住：照搬 ColorOS Mod（base.apk / com.rikumi.colorosmod）
 *           的 updateLyricWidth —— 流体云真身是 **seeding_card_container**
 *           （类名 CapsulePluginContainer，来自实机 dump 的 tree[1]），
 *           右界 = min(锚点容器右界, status_bar_start_side_container 右界,
 *           cutout_space_view 左界, seeding 容器内**最左可见子视图**左界 - 2dp)，
 *           递归且过滤 alpha<=0.01 的隐形子视图；宽度不变则不重排；
 *           ④新行起滚前静置 200ms
 */
public class StatusBarSubtitleHook {
    private static final String TAG = "[DLsiteSoundFloat:StatusBar]";
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    // 状态栏根视图类：AOSP 优先，再试 ColorOS 变体（ColorOS 16 可能改名/挪包）。
    private static final String[] STATUS_BAR_VIEW_CLS = {
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarView",
            "com.oplus.systemui.statusbar.OplusStatusBarView"
    };

    // 注入锚点资源名：ColorOS 优先，回退 AOSP（start_side_container / start_side_content）。
    private static final String[] ANCHOR_IDS = {
            "status_bar_start_side_content_for_fake",
            "status_bar_start_side_container",
            "status_bar_start_side_content"
    };

    // 时钟：字体/颜色同步源，也是字幕显示期间要遮挡的目标。
    private static final String[] CLOCK_IDS = {
            "clock", "status_bar_clock", "oplus_clock", "status_bar_clock_view"
    };

    // 左侧通知图标区：字幕显示期间要遮挡的目标，同时是「通知数」的来源。
    private static final String[] NOTIF_AREA_IDS = {
            "notification_icon_area",
            "notification_icons",
            "status_bar_notification_icons",
            "status_bar_notification_icon_area",
            "notification_icon_area_inner"
    };

    /**
     * 流体云 / 实时活动胶囊**容器**的资源名 —— 实机 dump 已经实锤：
     * {@code seeding_card_container}（类名 {@code CapsulePluginContainer}）就是 ColorOS 16 上
     * 那颗胶囊的容器，base.apk 的 {@code updateLyricWidth} 用的也正是它。
     * 命中后取**容器内最左可见子视图**的左边界（-2dp）当字幕右界。
     */
    private static final String[] SEEDING_IDS = {
            "seeding_card_container",
            "ongoing_activity_chip_primary",
            "ongoing_activity_chip_secondary"
    };

    /** 起始区容器（锚点所在侧）的右边界 —— 字幕右界的**上限**。 */
    private static final String[] SIDE_CONTAINER_IDS = {
            "status_bar_start_side_container"
    };

    /** 挖孔占位视图：可见时其左边界也参与收缩右界。 */
    private static final String[] CUTOUT_IDS = {
            "cutout_space_view"
    };

    /** 胶囊与字幕之间留的空隙（base.apk 用 2dp）。 */
    /** 流体云前留白（dp）。【code 936】对齐 coloros-mod 的 LYRIC_GAP_BEFORE_FLUID_DP=4（原 2dp 只有它一半，贴得太紧）。 */
    private static final float FLUID_GAP_DP = 4f;

    /**
     * 流体云（ColorOS 状态栏中间的胶囊：录屏指示器 / 实时通知 / 音乐…）候选**资源名**。
     * 命中即用它左边界卡住字幕右边界；全部落空时退回 {@link #findFluidByGeometry()}。
     * 注意：ColorOS 16 上真正的容器名是 {@link #SEEDING_IDS}，这里是**非 ColorOS 兜底**。
     */
    private static final String[] FLUID_IDS = {
            "status_bar_fluid_cloud",
            "fluid_cloud",
            "fluid_cloud_view",
            "oplus_fluid_cloud",
            "status_bar_center_side_content",
            "status_bar_center_side_content_for_fake",
            "status_bar_center_side",
            "status_bar_center_content",
            "status_bar_middle_content",
            "status_bar_middle_content_for_fake",
            "status_bar_live_notification",
            "live_notification_view",
            "status_bar_capsule",
            "capsule_view",
            "status_bar_ongoing_activity"
    };

    /** 流体云候选**类名特征**（资源名全落空时用）。 */
    private static final String[] FLUID_HINTS = {
            "CapsulePluginContainer", "FluidCloud", "Capsule", "OngoingActivity",
            "LiveNotification", "LiveText", "PromptContainer", "CenterSide", "Fluid"
    };

    // 徽标 / 布局尺寸（dp / sp）
    // 【1.21.13 问题 1】整体缩小 15%：13dp → 11.05dp、9sp → 7.65sp（都 ×0.85）。
    // 数字必须同比缩小，否则会顶出圆外。BADGE_GAP_DP 不动 —— 那是徽标与字幕之间的
    // 间隙，不属于「图标尺寸」；applyBadgeInset() 用 BADGE_SIZE_DP 算让位宽度，
    // 所以字幕左插空也会跟着收窄（16dp → 14.05dp）。
    private static final float BADGE_SIZE_DP = 11.05f;
    private static final float BADGE_TEXT_SP = 7.65f;
    private static final float BADGE_GAP_DP = 2f;
    private static final float LINE_LEFT_PAD_DP = 1f;
    private static final float LINE_RIGHT_PAD_DP = 4f;
    /** 流体云避让后字幕至少保留这么宽；比这还窄就认为探测到了假目标，放弃避让。 */
    private static final float MIN_LINE_W_DP = 56f;

    /** 单程滚动时长边界。优先取「字幕行自身播放时长」，夹在该区间内。 */
    private static final long SCROLL_MIN_MS = 1500L;
    private static final long SCROLL_MAX_MS = 12000L;
    private static final long SCROLL_FALLBACK_MS = 4000L;
    /** 速度上限（px/s）：行很长而时长很短时，别糊成一团。 */
    private static final float SCROLL_MAX_SPEED_PX_PER_S = 420f;

    /** 【1.21.11】某行「从没滚过」却因为变窄而要滚时的兜底速度（沿用常态速度量级）。 */
    private static final float SCROLL_FALLBACK_SPEED_PX_PER_S = 140f;
    /** 新字幕行出现后先静置这么久再起滚（问题 4：先静置 200ms 再向后滚）。 */
    private static final long SCROLL_START_DELAY_MS = 200L;
    /**
     * 宽度迟滞：新宽度与已应用宽度相差不足这个量就不重排。
     * 流体云伸缩是逐帧动画，宽度每帧变 1~2px，不设迟滞就会每帧 setLayoutParams（问题 2）。
     * 【code 934】6 -> 24：实测胶囊内动画让 seeding 最左子视图在 448~454 间抖 6px，
     *   旧值 6 正好压线穿透（|341-335|=6 不小于 6）-> 每次都触发 retarget = 闪动。
     */
    private static final int WIDTH_HYSTERESIS_PX = 24;
    /** 宽度去抖窗口：连续变化期间只在这段时间的末尾应用一次。 */
    private static final long WIDTH_SETTLE_MS = 220L;   // code 931：放宽到 layout 稳定后（实测稳定需 ~156ms）
    /** 滚动目标迟滞：新目标与当前目标相差不足这个量就不重启动画（治 1px 级抖动）。 */
    private static final int TARGET_HYSTERESIS_PX = 8;

    /** 字幕显示期间的心跳：① 重新压制被 SystemUI 改回可见的时钟/图标；② 更新徽标；③ 重新同步颜色/流体云边界。 */
    private static final long TICK_MS = 600L;
    /** 隐藏宽限期：切页/换轨会造成瞬时暂停，宽限一下避免时钟闪出（问题 1）。 */
    private static final long HIDE_GRACE_MS = 400L;
    /** 流体云重新探测间隔（几何兜底要遍历视图树，别每次布局都跑）。 */
    private static final long FLUID_FIND_MS = 400L;

    private static View sRoot;
    private static ViewGroup sHost;
    private static FrameLayout sContainer;
    /**
     * 通知数徽标。【code 945】类型由 TextView 换成 {@link NotificationBadgeView}：
     * 数字不再用颜色“涂”出来，而是从圆底里**镂空**挖出来（见该类注释）。
     */
    private static NotificationBadgeView sBadge;
    private static TextView sLineView;
    private static ValueAnimator sAnimator;
    private static ViewTreeObserver.OnGlobalLayoutListener sLayoutListener;

    /** 每次压制都重新收集：状态栏重建后旧引用会失效（问题 1 的根因）。 */
    private static final List<View> sClockViews = new ArrayList<>();
    private static final List<View> sNotifViews = new ArrayList<>();
    /** 被我们压成 GONE 的视图 → 原始可见性（还原用）。 */
    private static final Map<View, Integer> sHiddenPrev = new IdentityHashMap<>();
    /** setVisibility 事件压制用的目标 id 缓存（O(1) 判断，别在热点路径做字符串比较）。 */
    private static int[] sTargetIds = new int[0];
    private static final Map<String, Integer> sResIdCache = new HashMap<>();

    private static boolean sReceiverRegistered = false;
    private static boolean sSuppressed = false;
    /**
     * 「正在还原被遮挡视图」门。
     * 还原动作本身也是一次 {@code setVisibility(VISIBLE)}，会被 {@link #hookVisibilitySuppressor()}
     * 当成「SystemUI 又把时钟露出来了」而立刻压回 GONE —— 于是时钟再也回不来（问题 1 根因）。
     */
    private static boolean sRestoring = false;
    /** 上一次打过的字幕颜色（去 alpha），只用来给日志节流。 */
    private static int sLastLoggedRgb = Integer.MIN_VALUE;

    /**
     * 【code 922 问题 1】状态栏字幕「偶尔抖动」的治本字段 —— 颜色写入**合并窗**。
     *
     * ── 定因（真机日志 + 帧标定）──
     *
     * ColorOS 的状态栏时钟换色不是「一次 setTextColor 搞定」，而是一段**逐帧动画**：
     * 实测每轮 10~14 次、每次间隔 **7~19ms**（整轮 64~92ms），RGB 单调爬升，例如
     *   #8e8e8e → #b3b3b3 → #cccccc → #dcdcdc → #e7e7e7 → #f0f0f0
     *   → #f6f6f6 → #fafafa → #fdfdfd → #ffffff
     * 每一帧都会回调 {@link #onClockColorChanged}，而它**无条件**做两件事：
     *   ① {@code sLineView.setTextColor(color)}；
     *   ② {@link #applyBadgeStyle}（内部 3 次属性写入：setBadgeDiscColor
     *      + setTextSize + setTypeface；【code 945】起不再写 setTextColor，数字已改镂空）。
     * 于是字幕与徽标在 80ms 内被重绘十几遍 —— 用户看到的就是「字幕偶尔抖一下」。
     *
     * 日志佐证：一次 11 分钟会话里这种「连发 run」出现 **19 次**，
     * 每 run 的 n / 时长高度一致（n=10~14、64~92ms），且起点必为 #8e8e8e / #949494 /
     * #fafafa 这类中间值、终点必为 #ffffff 或 #0 —— 是动画首尾，不是「颜色真的在反复横跳」。
     *
     * ── 修法：只落「首帧 + 末帧」，中间帧全部丢弃 ──
     *
     * 关键约束：**不能延迟首帧**。首帧对应「状态栏底色刚开始翻转」的那一瞬，
     * 若把它也一起延后，就会出现「底已经变白、字还是白的」这种 80ms 隐身窗
     * （那正是 1.21.x 里 {@code hookClockColorMirror} 要解决的问题 2）。
     * 所以策略是「首帧立即落笔 + 末帧在静置后落笔」：
     *   · 距**上一帧**已超过 {@link #COLOR_QUIET_MS} → 判定为动画起跳，**立即写**；
     *   · 否则只记进 {@link #sPendingColorArgb}，并（重新）挂一个
     *     {@link #COLOR_SETTLE_TASK}，等**连续 {@link #COLOR_QUIET_MS} 没有新帧**后再写终点色。
     *
     * 计时基准刻意取「**上一帧的时刻**」而不是「上次落笔的时刻」：
     * 后者在动画长于窗口时会让窗口提前到期、把中间帧当成新的起跳帧立刻写下去
     * （= 抖动只减少、不消失）。取上一帧时刻 ⇒ 任务只在**动画真的静下来**之后才落笔，
     * 与动画总时长无关；同时因为首帧是「无帧可参照」的静置态，它必然走「立即写」分支。
     */
    private static final long COLOR_QUIET_MS = 120L;
    /** 合并窗内收到的最新颜色（末帧候选），见 {@link #COLOR_QUIET_MS}。 */
    private static volatile int sPendingColorArgb = Integer.MIN_VALUE;
    /** **上一帧**到达的时刻 —— 静置判定与任务到期计算都用它，见 {@link #COLOR_QUIET_MS}。 */
    private static long sLastClockColorMs = 0L;
    /** 合并窗内是否已挂过末帧任务（避免逐帧重排同一个 Runnable）。 */
    private static boolean sColorSettleScheduled = false;

    private static final Runnable COLOR_SETTLE_TASK = new Runnable() {
        @Override
        public void run() {
            if (!sColorSettleScheduled) {
                return;
            }
            // 还没静置够（动画仍在跑）→ 顺延，等下一轮
            long sinceLast = SystemClock.uptimeMillis() - sLastClockColorMs;
            if (sinceLast < COLOR_QUIET_MS) {
                sHandler.postDelayed(this, COLOR_QUIET_MS - sinceLast);
                return;
            }
            sColorSettleScheduled = false;
            int pending = sPendingColorArgb;
            sPendingColorArgb = Integer.MIN_VALUE;
            if (pending == Integer.MIN_VALUE) {
                return;
            }
            // 已静置：把末帧真正落下去（此时它已是动画终点色）
            applyClockColor(pending);
        }
    };

    private static boolean sEnabled = false;
    private static boolean sPlaying = true;
    private static String sLine = "";
    private static String sShownLine = null;
    private static long sDurationMs = 0L;
    private static int sBadgeCount = -1;

    /** 【code 946】徽标几何自证日志的上一行（变了才打，避免每次布局刷屏）。 */
    private static String sBadgeGeomPrev = null;
    private static int sLastColorArgb = Integer.MIN_VALUE;
    private static boolean sFontSynced = false;
    private static View sFontSource;

    // 流体云避让
    private static View sFluidView;
    private static long sLastFluidFindMs = -99999L;
    // 【code 934 bug1】seeding 边界记忆：胶囊容器还在但子视图暂时全不可见（收起/切换
    //   动画瞬间）时沿用上一次边界，不跳回 cutout 满宽 —— 治 341<->470 来回跳。
    /**
     * 【code 937 问题1】60000 -> 2000。936 把它抬到 60s 是为了「真身消失期别跳回 cutout」，
     *   但那是**误诊**：真身消失时右侧本来就该放宽（Ari 要的是「流体云不在时长一点」）。
     *   60s 的后果是容器一死就把 454 硬撑 60 秒 —— TICK 每 600ms 重算一次 = 连续 100 拍
     *   都输出窄宽 ⇒ Ari 体感「只显示半截、过一会才恢复完全」。
     *   本参数的正确职责只有一个：覆盖胶囊收起/展开动画那一瞬（实测 <500ms），
     *   故取 2s（4 拍）留足余量；「真身真的没了」由 isSeedingUsable + isShown 判据立即识别。
     */
    private static final long SEEDING_GRACE_MS = 2000L;
    private static int sLastSeedingLimit = Integer.MAX_VALUE;
    private static long sLastSeedingOkMs = 0L;
    /**
     * 【code 937 问题1】seeding 容器可用性状态：0=absent 1=present-but-dead 2=usable，
     *   -1 = 还没采样过。只在**状态翻转**时打一行日志（不会刷屏）——
     *   下一轮日志可直接判定「是谁把宽度锁在 454」，不必再靠推断。
     */
    private static int sLastSeedingState = -1;
    /** 【code 935 bug1】被采信的行左界（px，相对 base）；-1 = 还没采信过。 */
    private static int sLineLeftAcc = -1;
    /**
     * 【code 940 bug1 根修】行左界实测值的**拒收计数**：连续多少拍读到与锚点偏离
     *   超过容差的值。只有连续 {@link #LINE_LEFT_RELOCK_STREAK} 拍都稳定停在同一个
     *   新位置，才认为容器真的搬家了（见 {@link #acceptLineLeft(int, int)}）。
     */
    private static int sLineLeftRejectStreak = 0;
    /** 【code 940 bug1】本段拒收样本里的上一次值（判断是「稳定偏离」还是单帧跳变）。 */
    private static int sLineLeftRejectPrev = -1;
    /** 【code 940 bug1】连续拒收达到该拍数即判定容器真搬家，重锁左界。 */
    private static final int LINE_LEFT_RELOCK_STREAK = 3;
    private static boolean sApplyingBoundary = false;
    private static int sAppliedLineWidth = Integer.MIN_VALUE;

    /**
     * 【code 927 问题 1】**这一行的宽度是否已经真正落地**。
     *
     * 为什么必须有（code 926 的教训 —— 只挡宽度落地、没挡起滚时机）：
     *   `showLine` 里 `postScrollWhenLaidOut(0)` 下一帧就拉 `startScroll()`，
     *   而宽度要等去抖窗口（WIDTH_SETTLE_MS=150ms）才落地 ⇒ **起滚跑在了宽度前面**。
     *   那一刻 sLineView 的 LayoutParams 还是初始 MATCH_PARENT ⇒ getWidth() = 满宽
     *   （真机实测 457px）⇒ 先按满宽起滚（用户看到「整行」），150ms 后宽度落成 311
     *   ⇒ retarget 成滚动裁切 ⇒ 这就是「先半截再整行」（实际是先整行再半截）。
     *   ⇒ 修法：**宽度没落地就不许起滚**。
     */
    private static boolean sWidthSettled = false;

    // 滚动状态
    private static int sScrollTarget = 0;
    private static long sScrollDurMs = 0L;

    /**
     * 滚动的**绝对速度**（px/ms），在 startScroll() 时定死一次。
     * 续滚只允许用它算剩余时间，绝不从「剩余时间 / 全程距离」反推 —— 反推会逐次放大速度。
     */
    private static double sScrollSpeedPxPerMs = 0d;

    /**
     * 【1.21.11 问题 1】当前生效的**动态**滚动目标（px）。
     * 流体云伸缩只改它，**不重启动画、不改速度**：动画每帧比一次 ——
     * 目标变小（变宽）则到线即停、停在当前位置；目标变大（变窄）则本段跑完后按原速度续滚。
     * 这是「右端缩到胶囊左边，但滚动速度不变、继续按原速度往前滚」的实现方式。
     */
    private static int sLiveTargetPx = 0;

    /** 本行是否已经起滚（startScroll 跑过）。false 期间的宽度变化交给 startScroll 自己重算。 */
    private static boolean sScrollArmed = false;

    /**
     * 【1.21.11】动画代次：每次「主动停」或「新建动画」都自增。
     * 用来在 onAnimationEnd 里区分「自然跑完」（该按原速度续滚）与「被我们取消」（不该续滚）——
     * android.animation.Animator 接口只有 isStarted()/isRunning()，**没有 isCancelled()**
     * （它只住在 ValueAnimator 上），编译期直接报「找不到符号」。
     */
    private static int sScrollGen = 0;
    private static int sPendingWidth = Integer.MIN_VALUE;
    /**
     * 【code 924 bug2】「有改布局的调用刚发生、还没经过下一帧 layout」标记。
     *
     * 真根因（Ari 日志 22:50:28.868 -> 29.026，158ms 内 260px -> 469px，
     *          两次日志里 cutout left=579 **完全相同**）：
     *   {@link #applyBadgeInset} 改的是 {@code marginStart}，而 {@link #applyFluidBoundary}
     *   算 left 用的是 {@code screenX(sLineView)}（= 逐级累加 getLeft()）。
     *   marginStart 刚写、**下一帧 layout 还没跑** → getLeft() 仍是旧值
     *   → left 偏小 → avail = right - left 偏大?? 实测是偏**小**（260px）：
     *   更精确地说，marginStart 由 0 变成 15dp 时 getLeft() 还没动，
     *   于是 left 比真实值小 15dp，avail 反而变大；但**行视图内部**已经按新 margin
     *   重新测量过一轮（measure 先跑、layout 后跑），于是出现「半截」宽度被画出来。
     *   无论偏大偏小，**结论一样**：布局未落定时算出来的宽度不可信，不能落地。
     *
     * 处置：改完 marginStart 后置位本标记；{@code onGlobalLayout}（= 布局真的跑完了）
     *   里清掉。标记在位期间，{@code force=true} 路径**降级为延迟合并**（不再立即落地）。
     */
    private static boolean sLayoutDirty = false;
    private static String sPendingWidthWhy = "";
    /**
     * 锚点命中来源（自检日志用）：{@code id:xxx} = 资源名命中；
     * {@code fallback:parentOf(xxx)} = 走了「时钟的父容器」兜底；{@code MISSING} = 彻底没找到。
     */
    private static String sAnchorHit = "unknown";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    /** 【code 932】字幕行布局左界（dp）。928/929 实测稳定值 113px / density 2.975 = 38dp。 */
    private static final int FLUID_LINE_LEFT_DP = 38;

    /** 【code 932】WIDTH_TASK 正在执行中（防 applyFluidBoundary 递归再排队）。 */
    private static boolean sInWidthTask = false;

    /** 宽度去抖：流体云伸缩动画结束后再统一落地一次。 */
    private static final Runnable WIDTH_TASK = new Runnable() {
        @Override
        public void run() {
            // 【code 932 bug1 根修】延迟任务 = 重算 + **落地**。931 把它改成只调
            // applyFluidBoundary(false)（内部只会再次 postDelayed 自己，永不落地），
            // 导致首次落地的未稳窄值终身保留 = 「只出现一半」。现在重算后直接落地，
            // sInWidthTask 防止重算路径递归排队。
            sInWidthTask = true;
            try {
                applyFluidBoundary(false);
            } finally {
                sInWidthTask = false;
            }
            applyPendingWidth();
        }
    };

    /**
     * 挂钩入口。
     *
     * 迁移对照（2.0.0）：旧签名 {@code hook(XC_LoadPackage.LoadPackageParam lpparam)} ——
     * 只用到了 {@code lpparam.classLoader} 与 {@code lpparam.packageName} 两条信息；
     * SystemUI 的包名是常量 {@link #SYSTEMUI_PKG}，所以这里**只收 ClassLoader**，
     * 由入口类把 {@code PackageReadyParam.getClassLoader()} 传进来。
     * 好处：本类不再依赖任何 Xposed 回调参数类型，可测性更好。
     */
    public static void hook(ClassLoader cl) {
        // 版本日志：排查「SystemUI 到底跑没跑新 dex」时，先看这一行（配合 App 进程的 BUILD 行）。
        XposedCompat.log(TAG + " hook() enter, module build=" + BuildConfig.VERSION_NAME
                + " (code " + BuildConfig.VERSION_CODE + "), pkg=" + SYSTEMUI_PKG
                + ", api=libxposed-102");

        Class<?> cls = null;
        for (String name : STATUS_BAR_VIEW_CLS) {
            try {
                cls = XposedCompat.findClass(name, cl);
                if (cls != null) {
                    break;
                }
            } catch (Throwable t) {
                // 试下一个候选类
            }
        }
        if (cls == null) {
            XposedCompat.log(TAG + " status bar view class not found, skip status bar subtitle");
            return;
        }
        try {
            Method attached = XposedCompat.findMethodExact(cls, "onAttachedToWindow");
            XposedCompat.hookMethod(attached, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    Object self = chain.getThisObject();
                    if (!(self instanceof View)) {
                        return;
                    }
                    View root = (View) self;
                    try {
                        attach(root.getContext(), root);
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " attach failed: " + t);
                    }
                }
            });
            XposedCompat.log(TAG + " hooked " + cls.getName());
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook onAttachedToWindow failed: " + t);
        }

        hookVisibilitySuppressor();
        hookClockColorMirror();
        // 【code 932】双击关闭功能整体移除（Ari：状态栏上面只要显示字幕就行，不需要点击关闭）。
        // installDoubleTapToDismiss / installDoubleTapOnRoot / observeDoubleTap /
        // tryAttachDoubleTap 方法保留但不再调用，App 侧 dismiss 广播接收器留着无害。
    }

    // ======================================================================
    // 【code 924】双击状态栏字幕 -> 关闭状态栏字幕
    // ======================================================================

    /** 双击判定窗（ms）。真机 150~320ms 是人类双击的常见区间。 */
    private static final long DOUBLE_TAP_WINDOW_MS = 320L;
    /** 两次点击的最大位移（px）。超过就不算「双击同一点」。 */
    private static final int DOUBLE_TAP_SLOP_PX = 60;
    /** 上次单击的时间与坐标。 */
    private static long sLastTapMs = 0L;
    private static int sLastTapX = 0;
    private static int sLastTapY = 0;

    /**
     * 在字幕行视图上挂 {@link View.OnTouchListener}，双击关闭状态栏字幕。
     *
     * ── 为什么挂在 sLineView（而不是整个状态栏）──
     * 需求是「双击**状态栏字幕位置**」，挂在字幕行上语义最准；也天然避开了
     * 左半边的时钟 / 通知图标区（那些地方双击可能是系统手势）。
     *
     * ── 为什么**不吃掉事件**（关键）──
     * 状态栏是「下拉通知栏」手势的起点。若在 DOWN 就 return true，下拉会被废掉。
     * 所以策略是：
     *   · {@code onTouch} 里**只观察、不消费**，永远 return false —— 宿主照常收到事件，
     *     下拉 / 点击 / 滑动一律不变；
     *   · 只有当**确认为双击**（第二次 DOWN 落点与第一次在窗口内且位移很小）时，
     *     才执行关闭动作。事件本身仍放行（系统这一下也做不了什么，因为已经在我们身上）。
     * 代价是双击时系统可能同时收到一次多余的 DOWN/UP —— 实测（状态栏非交互区）
     * 无可见副作用；换来的是「绝不破坏下拉」这个硬要求。
     *
     * ── 为什么要按「动作 + 落点」双重判定 ──
     * 状态栏上的滑动（下拉）也会派发 DOWN/UP；只按时间判会把「快速下拉再点」
     * 误判成双击。加 60px 位移门后，滑动几乎不可能连续两次落在同一点。
     */
    private static View.OnTouchListener sDoubleTapListener;

    /**
     * 【code 927 问题 2】状态栏根视图上的 dispatchTouchEvent hook 是否已装。
     * 出问题时的历史证据：SystemUI 进程整个会话**零 touch 日志**
     * —— 挂在 sLineView 上的 OnTouchListener 从来没被调用过一次。
     */
    private static volatile boolean sDoubleTapOnRootInstalled = false;

    /** 【code 927 问题 2】dispatchTouchEvent 上收到的 DOWN 事件计数（取证：区分「没 hook 上」与「收不到事件」）。 */
    private static int sRootDownCount = 0;

    private static void installDoubleTapToDismiss() {
        // 【code 927 问题 2】事件源不再挂 sLineView，改为挂**状态栏根视图的
        //   dispatchTouchEvent**（见 installDoubleTapOnRoot）。
        //
        //   真机实证（work_diag_52 · SystemUI 进程整个会话）：**0 条 touch 日志**。
        //   字幕行被塞在 status bar 的非交互区，触摸在**窗口层面**就被 systemui 的
        //   下拉手势层吃掉，根本不会派发到子视图 —— 子视图 OnTouchListener 形同虚设。
        //   而 dispatchTouchEvent 是**任何**落到状态栏窗口的事件的第一站，
        //   在那里能拿到真实落点，再用「是否在字幕行屏幕矩形内」判定命中。
        //   具体挂载在 attach 内部调用 installDoubleTapOnRoot(root)。
        XposedCompat.log(TAG + "[双击] 双击关闭功能已启用（window=" + DOUBLE_TAP_WINDOW_MS
                + "ms, slop=" + DOUBLE_TAP_SLOP_PX
                + "px, source=statusbar.dispatchTouchEvent）");
    }

    /**
     * 【code 927 问题 2】在**状态栏根视图**上挂 dispatchTouchEvent 观察者。
     *
     * 为什么必须换事件源：真机实证「挂 sLineView 一个事件都收不到」——
     * 状态栏非交互区的触摸在窗口层面就被下拉手势层吃掉。
     * dispatchTouchEvent 是任何落到状态栏窗口的事件的第一站，
     * 且我们**只观察、不消费**（{@code proceed()} 原样放行，下拉通知栏不受影响）。
     *
     * 命中判定用「落点是否在字幕行视图的屏幕矩形内」——
     * 比「事件是否派发到该视图」可靠（后者依赖 systemui 的命中测试策略）。
     */
    private static void installDoubleTapOnRoot(final View statusBarRoot) {
        if (statusBarRoot == null || sDoubleTapOnRootInstalled) {
            return;
        }
        try {
            final Class<?> cls = statusBarRoot.getClass();
            java.lang.reflect.Method m = XposedCompat.findMethodExact(cls, "dispatchTouchEvent",
                    android.view.MotionEvent.class);
            XposedCompat.hookMethod(m, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object a0 = chain.getArg(0);
                        if (a0 instanceof android.view.MotionEvent) {
                            observeDoubleTap((android.view.MotionEvent) a0);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();   // **永不消费** —— 保住下拉通知栏
                }
            });
            sDoubleTapOnRootInstalled = true;
            XposedCompat.log(TAG + "[双击] hooked " + cls.getName() + ".dispatchTouchEvent");
        } catch (Throwable t) {
            XposedCompat.log(TAG + "[双击] hook dispatchTouchEvent failed: " + t);
        }
    }

    /**
     * 【code 927 问题 2】观察双击：只有落点**在字幕行屏幕矩形内**才计数。
     * 只看 DOWN 沿；{@link #DOUBLE_TAP_WINDOW_MS} 窗 + {@link #DOUBLE_TAP_SLOP_PX} 位移门。
     * 每 40 次 DOWN 汇总一行（取证：一次性日志事后查不出「偶发 vs 恒常」）。
     */
    private static void observeDoubleTap(android.view.MotionEvent e) {
        if (e == null || e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
            return;
        }
        View line = sLineView;
        if (line == null || !line.isShown()) {
            return;
        }
        try {
            int[] loc = new int[2];
            line.getLocationOnScreen(loc);
            int pad = (int) (DOUBLE_TAP_SLOP_PX * 1.5f);   // 状态栏很矮，给点余量
            float x = e.getRawX();
            float y = e.getRawY();
            boolean inside = x >= loc[0] - pad && x <= loc[0] + line.getWidth() + pad
                    && y >= loc[1] - pad && y <= loc[1] + line.getHeight() + pad;
            if (sRootDownCount++ % 40 == 0) {
                XposedCompat.log(TAG + "[双击] root DOWN #" + sRootDownCount
                        + " at (" + (int) x + "," + (int) y + ") inside=" + inside
                        + " lineRect=[" + loc[0] + "," + loc[1] + ","
                        + line.getWidth() + "x" + line.getHeight() + "]");
            }
            if (!inside) {
                sLastTapMs = 0L;   // 离开字幕区 -> 作废上次，避免「点A再点B」被算双击
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean inWindow = sLastTapMs != 0L && now - sLastTapMs <= DOUBLE_TAP_WINDOW_MS;
            boolean near = Math.abs((int) x - sLastTapX) <= DOUBLE_TAP_SLOP_PX
                    && Math.abs((int) y - sLastTapY) <= DOUBLE_TAP_SLOP_PX;
            if (inWindow && near) {
                sLastTapMs = 0L;
                XposedCompat.log(TAG + "[双击] hit at (" + (int) x + "," + (int) y + ")");
                onSubtitleDoubleTapped(line.getContext());
            } else {
                sLastTapMs = now;
                sLastTapX = (int) x;
                sLastTapY = (int) y;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 在（新）字幕行视图上挂双击监听。重复调用无害（先清旧引用）。 */
    private static void tryAttachDoubleTap(final Context ctx, View line) {
        if (line == null) {
            return;
        }
        if (sDoubleTapListener == null) {
            sDoubleTapListener = new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    try {
                        if (e == null) {
                            return false;
                        }
                        if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
                            return false;   // 只看按下沿，松开沿忽略
                        }
                        long now = SystemClock.uptimeMillis();
                        int x = (int) e.getRawX();
                        int y = (int) e.getRawY();
                        boolean inWindow = now - sLastTapMs <= DOUBLE_TAP_WINDOW_MS;
                        boolean near = Math.abs(x - sLastTapX) <= DOUBLE_TAP_SLOP_PX
                                && Math.abs(y - sLastTapY) <= DOUBLE_TAP_SLOP_PX;
                        if (inWindow && near && v.isShown()) {
                            sLastTapMs = 0L;   // 消费掉这组，避免三击连触发
                            onSubtitleDoubleTapped(v.getContext());
                        } else {
                            sLastTapMs = now;
                            sLastTapX = x;
                            sLastTapY = y;
                        }
                    } catch (Throwable ignored) {
                    }
                    return false;   // **永不消费** —— 保住下拉通知栏
                }
            };
        }
        line.setOnTouchListener(sDoubleTapListener);
    }

    /**
     * 双击生效：请求**关闭**状态栏字幕。
     *
     * ── 为什么不能直接调 {@link StatusBarSubtitleBridge#toggleAppEnabled} ──
     * 那个方法改的是 {@code sAppEnabled} 这个**静态字段**。本类跑在 **SystemUI 进程**，
     * 而 `toggleAppEnabled` 的正常调用方（胶囊按钮）跑在 **宿主 App 进程** ——
     * 两个进程各有一份副本。在 SystemUI 里调它只会改本进程副本，
     * App 进程下次推送字幕时又把它当「开着」打回来，表现为「关了又自己开」。
     *
     * ── 正确架构：反向广播 + 唯一落点 ──
     * SystemUI 侧只发一条 {@link StatusBarSubtitleBridge#ACTION_DISMISS_REQUEST}
     * 请求广播；**真正执行**放在 App 进程的 {@code ActivityButtonHook} 里 ——
     * 与胶囊按钮点击**走同一条代码路径**（toggle + resendCurrentFromRepo + 刷按钮）。
     * 这样「状态栏字幕开关」这个事实仍然只有**一个**写入口，
     * 不会变成两个进程各写一半（1.21.12 的教训：同一事实被两个 UI 读到 → 先比口径）。
     *
     * 本地先 GONE 是为了手感（不等广播往返）；App 进程收到后会重推空行，
     * 状态栏随之 hideSubtitleNow，两边的最终态一致。
     */
    private static void onSubtitleDoubleTapped(Context ctx) {
        boolean sent = false;
        try {
            Intent i = new Intent(StatusBarSubtitleBridge.ACTION_DISMISS_REQUEST);
            i.putExtra(StatusBarSubtitleBridge.EXTRA_DISMISS_REASON, "double_tap_status_bar");
            ctx.sendBroadcast(i);
            sent = true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + "[双击] send dismiss request failed: " + t);
        }
        XposedCompat.log(TAG + "[双击] 状态栏字幕位置双击 -> dismiss request sent=" + sent);
        // 立即本地隐藏（手感）：不等 App 进程往返。
        try {
            if (sContainer != null) {
                sContainer.setVisibility(View.GONE);
            }
            if (sLineView != null) {
                sLineView.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    // ======================================================================
    // 事件级压制 / 颜色镜像
    // ======================================================================

    /**
     * hook {@code View.setVisibility}：SystemUI 一旦把我们压掉的时钟/通知图标区改回可见，
     * 立即再压回 GONE。这比「低频轮询」快一个数量级 —— 切页重建后时钟不会再闪出来（问题 1）。
     *
     * 只用 id 比较（整数），热点路径上不做字符串操作；args[0]==GONE 时直接返回，天然防递归。
     */
    private static void hookVisibilitySuppressor() {
        try {
            Method setVis = XposedCompat.findMethodExact(View.class, "setVisibility", int.class);
            XposedCompat.hookMethod(setVis, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    if (sRestoring || !sSuppressed || sTargetIds.length == 0) {
                        return;
                    }
                    Object o = chain.getThisObject();
                    if (!(o instanceof View)) {
                        return;
                    }
                    Object arg = chain.getArg(0);
                    if (!(arg instanceof Integer) || (Integer) arg == View.GONE) {
                        return;
                    }
                    View v = (View) o;
                    int id = v.getId();
                    if (id == View.NO_ID) {
                        return;
                    }
                    for (int t : sTargetIds) {
                        if (t == id) {
                            v.setVisibility(View.GONE);
                            break;
                        }
                    }
                }
            });
            XposedCompat.log(TAG + " hooked View.setVisibility (instant suppression)");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook View.setVisibility failed: " + t);
        }
    }

    /**
     * hook {@code TextView.setTextColor}：状态栏时钟换色（浅色底→深色图标）时，
     * 立刻把同一颜色镜像到字幕 —— 不等轮询，不会出现「浅底白字隐身」（问题 2）。
     */
    private static void hookClockColorMirror() {
        // ⚠️ 同一个 Hooker 实例挂到两个重载上：拦截链本身无状态，这样写与旧版共用一个
        // XC_MethodHook 实例的行为完全一致（也避免重复分配）。
        XposedCompat.SimpleHook mirror = new XposedCompat.SimpleHook() {
            @Override
            protected Object after(XposedInterface.Chain chain, Object r) {
                try {
                    if (sClockViews.isEmpty()) {
                        return r;
                    }
                    Object o = chain.getThisObject();
                    if (o == null || o == sLineView) {
                        return r;
                    }
                    for (View c : sClockViews) {
                        if (c == o && c instanceof TextView) {
                            onClockColorChanged(((TextView) c).getCurrentTextColor());
                            return r;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            }
        };
        try {
            Method byInt = XposedCompat.findMethodExact(TextView.class, "setTextColor", int.class);
            XposedCompat.hookMethod(byInt, mirror);
            Method byCsl = XposedCompat.findMethodExact(TextView.class, "setTextColor", ColorStateList.class);
            XposedCompat.hookMethod(byCsl, mirror);
            XposedCompat.log(TAG + " hooked TextView.setTextColor (color mirror)");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook TextView.setTextColor failed: " + t);
        }
    }

    /**
     * 【code 922 问题 1】时钟颜色回调的**合并入口**（见 {@link #COLOR_QUIET_MS} 的完整定因）。
     *
     * 行为：
     *   · 与已落笔颜色相同 → 直接丢（旧逻辑保留，这一层省的是「同色重复」）；
     *   · 距上次落笔已超合并窗 → **立即落笔**（首帧零延迟，隐身窗不会出现）；
     *   · 仍在窗内 → 只更新 {@link #sPendingColorArgb}，静置后由
     *     {@link #COLOR_SETTLE_TASK} 一次性落末帧。
     *
     * ⚠️ 这里刻意**不更新** {@code sLastColorArgb}（那是「已落笔」的语义），
     * 否则末帧落笔时会被自判为「同色」而丢掉 —— 本项目反复踩过的「Getters 里写状态」
     * 同族错误：不要在判定路径上顺手改状态。
     */
    private static void onClockColorChanged(int color) {
        long now = SystemClock.uptimeMillis();
        boolean quiet = (now - sLastClockColorMs) >= COLOR_QUIET_MS;
        sLastClockColorMs = now;
        if (color == sLastColorArgb) {
            return;                 // 同色重复：不落笔，但上面那行仍更新了「上一帧时刻」
        }
        if (quiet) {
            // 静置态起跳 / 动画已停：首帧立即落笔，视觉零延迟
            applyClockColor(color);
            return;
        }
        // 合并窗内：只记末帧候选，静置后一次性落笔
        sPendingColorArgb = color;
        if (!sColorSettleScheduled) {
            sColorSettleScheduled = true;
            sHandler.removeCallbacks(COLOR_SETTLE_TASK);
            sHandler.postDelayed(COLOR_SETTLE_TASK, COLOR_QUIET_MS);
        }
    }

    /**
     * 【code 922 问题 1】真正把颜色写到字幕与徽标上（合并后的**唯一落笔点**）。
     *
     * 调用者：{@link #onClockColorChanged}（首帧）与 {@link #COLOR_SETTLE_TASK}（末帧）。
     * ⚠️ 任何新的颜色写入路径都必须走这里 —— 绕过它就等于绕过合并窗，抖动会复活。
     */
    private static void applyClockColor(int color) {
        if (color == sLastColorArgb) {
            return;
        }
        sLastColorArgb = color;
        if (sLineView != null) {
            try {
                sLineView.setTextColor(color);
            } catch (Throwable ignored) {
            }
        }
        applyBadgeStyle(color);
        // 日志节流：合并后每个窗口最多写两次，但仍按「RGB 真变」记一笔，
        // 便于事后核对「合并前 n 帧 -> 合并后几次落笔」（取证用，别改成一次性布尔）。
        int rgb = color & 0x00FFFFFF;
        if (rgb != sLastLoggedRgb) {
            sLastLoggedRgb = rgb;
            XposedCompat.log(TAG + " subtitle color <- clock: #" + Integer.toHexString(rgb)
                    + (isLight(color) ? " (light bar -> dark text)" : " (dark bar -> light text)"));
        }
    }

    // ======================================================================
    // 注入
    // ======================================================================

    private static void attach(Context ctx, View root) {
        sRoot = root;
        refreshResIds(ctx);

        boolean alive = sContainer != null
                && sContainer.getParent() instanceof ViewGroup
                && sContainer.isAttachedToWindow();
        if (alive) {
            // 状态栏重建了子视图：只刷新引用 + 重新压制（否则新实例的时钟会露出来）
            refreshSystemViews();
            if (sSuppressed) {
                sSuppressed = false;     // 强制重新压制（收集新实例）
                applySuppression(true);
            }
            logViewTreeOnce(root);
            return;
        }

        View anchor = findAnchor(ctx, root);
        ViewGroup host = anchor instanceof ViewGroup ? (ViewGroup) anchor
                : (anchor != null && anchor.getParent() instanceof ViewGroup
                        ? (ViewGroup) anchor.getParent() : null);
        if (host == null) {
            XposedCompat.log(TAG + " anchor not found, skip attach");
            return;
        }
        sHost = host;

        buildViews(ctx);

        // 布局：把整段起始区填满 —— 时钟与通知图标区会被隐藏，空间全归字幕。
        ViewGroup.LayoutParams lp;
        boolean horizontalLl = host instanceof LinearLayout
                && ((LinearLayout) host).getOrientation() == LinearLayout.HORIZONTAL;
        if (horizontalLl) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            l.gravity = Gravity.CENTER_VERTICAL;
            lp = l;
        } else {
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            l.gravity = Gravity.CENTER_VERTICAL;
            lp = l;
        }
        try {
            host.addView(sContainer, lp);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " addView failed: " + t + " -> fallback");
            try {
                host.addView(sContainer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t2) {
                XposedCompat.log(TAG + " addView fallback failed: " + t2);
                sContainer = null;
                return;
            }
        }

        installLayoutListener(root);
        // 【code 932】双击关闭功能整体移除（不再挂 root dispatchTouchEvent 观察者）。
        // 【code 930 bug 2 修复】删除 sLineView 上的旧双击监听（tryAttachDoubleTap）：
        //   它与 root dispatchTouchEvent 观察者**共享同一组 sLastTapMs/X/Y**，一次单击的
        //   DOWN 先被 root 观察者置 sLastTapMs=now，同一事件派发到行视图 OnTouchListener
        //   又判 inWindow(0ms) && near(同坐标) → 直接命中关闭 = Ari 复现的「单击就关闭」。
        //   真机实证（work_diag_55）：root DOWN #1 at (390,94) 后 1ms 即 hit dismiss。
        //   仅保留 root hook（要求两次独立 DOWN 在 320ms 内且位移 <60px 才算双击）。
        //   tryAttachDoubleTap / sDoubleTapListener 方法保留（不再调用）以免改动 dex 结构。
        refreshSystemViews();
        registerReceiver(ctx);
        logCompatSelfCheck();
        XposedCompat.log(TAG + " attached to status bar"
                + " (host=" + host.getClass().getSimpleName()
                + ", clock=" + (sClockViews.isEmpty() ? "null" : sClockViews.get(0).getClass().getSimpleName())
                + "(" + sClockViews.size() + ")"
                + ", notifArea=" + (sNotifViews.isEmpty() ? "null" : sNotifViews.size())
                + ")");
        logViewTreeOnce(root);
    }

    /**
     * 状态栏每次布局都过一遍：重新压制被露出来的时钟/图标、跟上流体云边界、同步颜色。
     * 这三件事在「切页 / 状态栏重建 / 流体云伸缩」时都需要及时反应（问题 1、2、3）。
     */
    private static void installLayoutListener(final View root) {
        try {
            if (sLayoutListener != null) {
                return;
            }
            sLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    try {
                        // 【code 924 bug2】layout 真的跑完了 -> 清掉「布局脏」标记。
                        //   清在这里（而不是 applyBadgeInset 里自己清）是为了保证：
                        //   「置脏 → 至少等过一帧 layout」这个时序成立。
                        sLayoutDirty = false;
                        logBadgeGeometry("layout");   // 【code 946】徽标几何自证
                        if (sSuppressed) {
                            enforceSuppression();
                        }
                        if (sContainer != null && sContainer.getVisibility() == View.VISIBLE) {
                            syncColorFromClock();
                            applyFluidBoundary(false);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(sLayoutListener);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " installLayoutListener failed: " + t);
        }
    }

    private static void buildViews(Context ctx) {
        sContainer = new FrameLayout(ctx);
        sContainer.setClipChildren(true);
        sContainer.setVisibility(View.GONE);

        // 【code 945】圆底 + **镂空数字**都由 NotificationBadgeView 自绘
        //（saveLayer + BlendMode.CLEAR，与 CloseButtonView 的 ✕ 同一套做法）；
        // 这里只负责建视图、给固定尺寸，不再挂 GradientDrawable 圆底。
        sBadge = new NotificationBadgeView(ctx);
        sBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dp(ctx, BADGE_SIZE_DP), dp(ctx, BADGE_SIZE_DP),
                Gravity.START | Gravity.CENTER_VERTICAL);
        blp.setMarginStart(dp(ctx, LINE_LEFT_PAD_DP));
        sContainer.addView(sBadge, blp);

        sLineView = new TextView(ctx);
        sLineView.setSingleLine(true);
        sLineView.setHorizontallyScrolling(true);
        sLineView.setEllipsize(null);
        sLineView.setSelected(false);           // 关键：不用系统跑马灯（它会循环着往回弹）
        sLineView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sLineView.setIncludeFontPadding(false);
        // 初始样式（拿不到时钟时的兜底），随后由 syncFontFromClock()/syncColorFromClock() 覆盖
        sLineView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        sLineView.setTextColor(ColorStateList.valueOf(0xFFFFFFFF));
        // 阴影一律交给 syncFontFromClock() 跟随时钟 —— 之前硬写 1.5px 黑阴影，浅色底上会糊出灰边（问题 2）。
        sLineView.setShadowLayer(0f, 0f, 0f, 0);

        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        // 【1.21.11 问题 2】默认**不给徽标留位**：参考实机截图里「没有通知的那行歌词」是顶到
        // 状态栏最左的，只有真的有通知时才让出 BADGE_SIZE+BADGE_GAP=15dp。
        // 旧代码无条件留 15dp -> 我们的字幕整整比参考右移一个徽标位（实测 110px vs 66px）。
        // 让位由 applyBadgeInset() 在有通知时按需补上。
        llp.setMarginStart(dp(ctx, LINE_LEFT_PAD_DP));
        llp.setMarginEnd(dp(ctx, LINE_RIGHT_PAD_DP));
        sContainer.addView(sLineView, llp);

        sFontSynced = false;
        sFontSource = null;
        sShownLine = null;
        sBadgeCount = -1;
        sLastColorArgb = Integer.MIN_VALUE;
        // 【code 922 问题 1】合并窗状态一并复位 —— 状态栏重建（SystemUI 重启 / 主题切换）
        // 后若留着旧的 sLastClockColorMs，首帧会被误判为「窗内」而延迟落笔，
        // 那段延迟就是一次新的「底已白、字还白」隐身窗。
        sPendingColorArgb = Integer.MIN_VALUE;
        sLastClockColorMs = 0L;
        sColorSettleScheduled = false;
        sHandler.removeCallbacks(COLOR_SETTLE_TASK);
        sAppliedLineWidth = Integer.MIN_VALUE;
        sPendingWidth = Integer.MIN_VALUE;
        sScrollSpeedPxPerMs = 0d;
        sLiveTargetPx = 0;
        sScrollArmed = false;
        // 【code 927 问题 1】状态栏重建（SystemUI 重启 / 主题切换）= 宽度结论整体作废。
        sWidthSettled = false;
        sHandler.removeCallbacks(WIDTH_TASK);
        sFluidView = null;
    }

    private static View findAnchor(Context ctx, View root) {
        for (String name : ANCHOR_IDS) {
            int id = resId(name);
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) {
                    sAnchorHit = "id:" + name;
                    return v;
                }
            }
        }
        // 兜底：用时钟的父容器当锚点
        View clock = sClockViews.isEmpty() ? null : sClockViews.get(0);
        if (clock == null) {
            clock = findSystemView(root, CLOCK_IDS, "Clock");
        }
        if (clock != null && clock.getParent() instanceof View) {
            sAnchorHit = "fallback:parentOf(" + describe(clock, CLOCK_IDS) + ")";
            return (View) clock.getParent();
        }
        sAnchorHit = "MISSING";
        return null;
    }

    /**
     * 自检用：这个视图的 id 命中候选名单里的哪个名字？
     * 没命中就给出 ``类名#0x十六进制id`` —— 拿这个去补候选数组即可，不用重新逆向。
     */
    private static String describe(View v, String[] names) {
        if (v == null) {
            return "null";
        }
        int vid = v.getId();
        for (String n : names) {
            int rid = resId(n);
            if (rid != 0 && rid == vid) {
                return n;
            }
        }
        return v.getClass().getSimpleName() + "#0x" + Integer.toHexString(vid);
    }

    /**
     * 兼容性自检：attach 时把四道关卡各自「命中了什么 / 走没走兜底」打成一两行。
     *
     * 换 ROM 排障时只看这几行就知道缺谁：{@code MISSING} 的那一项，
     * 到上面 {@code ---- status bar view tree ----} 里找对应的 {@code tree[N] ... id=xxx}，
     * 把名字补进 ANCHOR_IDS / CLOCK_IDS / NOTIF_AREA_IDS / SEEDING_IDS 即可。
     */
    private static void logCompatSelfCheck() {
        try {
            View seeding = findById(SEEDING_IDS);
            View side = findById(SIDE_CONTAINER_IDS);
            View cutout = findById(CUTOUT_IDS);
            StringBuilder sb = new StringBuilder();
            sb.append("compat self-check: root=")
                    .append(sRoot == null ? "null" : sRoot.getClass().getSimpleName());
            sb.append(" host=").append(sHost == null ? "MISSING" : sHost.getClass().getSimpleName());
            sb.append(" anchor=").append(sAnchorHit);
            sb.append(" clock=").append(sClockViews.isEmpty() ? "MISSING"
                    : describe(sClockViews.get(0), CLOCK_IDS) + "(x" + sClockViews.size() + ")");
            sb.append(" notif=").append(sNotifViews.isEmpty() ? "MISSING"
                    : describe(sNotifViews.get(0), NOTIF_AREA_IDS) + "(x" + sNotifViews.size() + ")");
            sb.append(" seeding=").append(seeding == null ? "none"
                    : describe(seeding, SEEDING_IDS) + "(" + seeding.getClass().getSimpleName() + ")");
            sb.append(" side=").append(side == null ? "none" : describe(side, SIDE_CONTAINER_IDS));
            sb.append(" cutout=").append(cutout == null ? "none" : describe(cutout, CUTOUT_IDS));
            XposedCompat.log(TAG + " " + sb);

            String miss = "";
            if (sAnchorHit.equals("MISSING")) {
                miss += "anchor,";
            } else if (sAnchorHit.startsWith("fallback")) {
                miss += "anchor(兜底),";
            }
            if (sClockViews.isEmpty()) {
                miss += "clock,";
            }
            if (sNotifViews.isEmpty()) {
                miss += "notif,";
            }
            if (seeding == null) {
                miss += "seeding,";
            }
            if (miss.isEmpty()) {
                XposedCompat.log(TAG + " compat OK: 四关全命中（仅指 id/类名探测成功，未做实机视觉验证）");
            } else {
                XposedCompat.log(TAG + " compat MISSING: " + miss
                        + " -> 本 ROM 需补 id：把上面 tree[N] 里的 id=xxx 发回来即可");
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " compat self-check failed: " + t);
        }
    }

    /** 每次压制前重新收集：状态栏重建后视图实例会换，旧引用压不住新时钟（问题 1 根因）。 */
    private static void refreshSystemViews() {
        sClockViews.clear();
        sNotifViews.clear();
        collectByIds(sRoot, CLOCK_IDS, sClockViews);
        collectByIds(sRoot, NOTIF_AREA_IDS, sNotifViews);
        if (sClockViews.isEmpty()) {
            View v = findByClassHint(sRoot, "Clock");
            if (v != null) {
                sClockViews.add(v);
            }
        }
        if (sNotifViews.isEmpty()) {
            View v = findByClassHint(sRoot, "NotificationIconContainer");
            if (v != null) {
                sNotifViews.add(v);
            }
        }
        if (sClockViews.isEmpty()) {
            XposedCompat.log(TAG + " clock view not found -> 字体/颜色回退默认，且无法遮挡时钟");
        }
        if (sNotifViews.isEmpty()) {
            XposedCompat.log(TAG + " notification icon area not found -> 无法遮挡通知图标");
        }
    }

    /** 找出 root 下**所有** id 命中的视图（findViewById 只给第一个，重建后可能同时存在新旧实例）。 */
    private static void collectByIds(View root, String[] names, List<View> out) {
        if (root == null) {
            return;
        }
        int[] ids = new int[names.length];
        int n = 0;
        for (String name : names) {
            int id = resId(name);
            if (id != 0) {
                ids[n++] = id;
            }
        }
        if (n == 0) {
            return;
        }
        try {
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 3000) {
                View v = queue.poll();
                int vid = v.getId();
                for (int i = 0; i < n; i++) {
                    if (vid == ids[i]) {
                        out.add(v);
                        break;
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null) {
                            queue.add(c);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static View findSystemView(View root, String[] ids, String classHint) {
        for (String id : ids) {
            try {
                int rid = resId(id);
                if (rid != 0) {
                    View v = root.findViewById(rid);
                    if (v != null) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return findByClassHint(root, classHint);
    }

    /** 广度优先按类名特征找视图（ROM 改资源名时的兜底）。 */
    private static View findByClassHint(View root, String hint) {
        if (root == null) {
            return null;
        }
        try {
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 4000) {
                View v = queue.poll();
                if (v.getClass().getName().contains(hint)) {
                    return v;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null) {
                            queue.add(c);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int resId(String name) {
        Integer cached = sResIdCache.get(name);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        try {
            Context ctx = sRoot != null ? sRoot.getContext() : null;
            if (ctx != null) {
                id = ctx.getResources().getIdentifier(name, "id", SYSTEMUI_PKG);
            }
        } catch (Throwable ignored) {
        }
        sResIdCache.put(name, id);
        return id;
    }

    private static void refreshResIds(Context ctx) {
        try {
            List<Integer> targets = new ArrayList<>();
            for (String n : CLOCK_IDS) {
                int id = ctx.getResources().getIdentifier(n, "id", SYSTEMUI_PKG);
                sResIdCache.put(n, id);
                if (id != 0 && !targets.contains(id)) {
                    targets.add(id);
                }
            }
            for (String n : NOTIF_AREA_IDS) {
                int id = ctx.getResources().getIdentifier(n, "id", SYSTEMUI_PKG);
                sResIdCache.put(n, id);
                if (id != 0 && !targets.contains(id)) {
                    targets.add(id);
                }
            }
            sTargetIds = new int[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                sTargetIds[i] = targets.get(i);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一次性把状态栏视图树打进日志（下一轮排查「流体云到底叫什么」时靠它）。 */
    private static void logViewTreeOnce(View root) {
        try {
            XposedCompat.log(TAG + " ---- status bar view tree ----");
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int i = 0;
            while (!queue.isEmpty() && i < 80) {
                View v = queue.poll();
                String idName = "?";
                try {
                    if (v.getId() != View.NO_ID) {
                        idName = v.getResources().getResourceEntryName(v.getId());
                    } else {
                        idName = "-";
                    }
                } catch (Throwable ignored) {
                }
                String text = "";
                if (v instanceof TextView) {
                    CharSequence cs = ((TextView) v).getText();
                    if (cs != null && cs.length() > 0) {
                        String s = cs.toString();
                        text = " text=\"" + (s.length() > 18 ? s.substring(0, 18) + "…" : s) + "\"";
                    }
                }
                XposedCompat.log(TAG + " tree[" + (i++) + "] " + v.getClass().getSimpleName()
                        + " id=" + idName
                        + " vis=" + v.getVisibility()
                        + " l=" + v.getLeft() + " t=" + v.getTop()
                        + " w=" + v.getWidth() + " h=" + v.getHeight()
                        + text);
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int k = 0; k < g.getChildCount(); k++) {
                        View c = g.getChildAt(k);
                        if (c != null) {
                            queue.add(c);
                        }
                    }
                }
            }
            XposedCompat.log(TAG + " ---- end tree ----");
        } catch (Throwable ignored) {
        }
    }

    // ======================================================================
    // 广播
    // ======================================================================

    private static void registerReceiver(Context ctx) {
        if (sReceiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(StatusBarSubtitleBridge.ACTION_LINE);
        f.addAction(StatusBarSubtitleBridge.ACTION_ENABLED);
        // 【code 941】作用域探测：本接收器**存在**这件事本身就是「已授权」的证据。
        f.addAction(StatusBarSubtitleBridge.ACTION_SCOPE_PING);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) {
                    return;
                }
                try {
                    if (StatusBarSubtitleBridge.ACTION_SCOPE_PING.equals(i.getAction())) {
                        // 【code 941】能收到 PING = 本进程（SystemUI）已被注入本模块
                        // = 用户勾选了 SystemUI 作用域。回一条 PONG 让 App 侧确认。
                        answerScopePing(c);
                        return;
                    }
                    if (StatusBarSubtitleBridge.ACTION_ENABLED.equals(i.getAction())) {
                        sEnabled = i.getBooleanExtra(StatusBarSubtitleBridge.EXTRA_ENABLED, false);
                        if (!sEnabled) {
                            hideSubtitle();
                        }
                        return;
                    }
                    if (StatusBarSubtitleBridge.ACTION_LINE.equals(i.getAction())) {
                        // LINE 随包携带开关态 / 行时长 / 播放态：SystemUI 重启后首条字幕即可恢复
                        sEnabled = i.getBooleanExtra(StatusBarSubtitleBridge.EXTRA_ENABLED, sEnabled);
                        long dur = i.getLongExtra(StatusBarSubtitleBridge.EXTRA_DURATION_MS, 0L);
                        boolean playing = i.getBooleanExtra(StatusBarSubtitleBridge.EXTRA_PLAYING, true);
                        showLine(i.getStringExtra(StatusBarSubtitleBridge.EXTRA_LINE), dur, playing);
                    }
                } catch (Throwable t) {
                    XposedCompat.log(TAG + " onReceive failed: " + t);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 强制声明广播可见性标志。跨 UID 通信（App→SystemUI）必须用 EXPORTED，
                // 否则 SystemUI 收不到来自不同 UID 的字幕广播，注册直接抛 SecurityException（曾导致
                // 状态栏字幕完全不显示）。action 为私有长名，被外部滥用的风险极低。
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            sReceiverRegistered = true;
            XposedCompat.log(TAG + " receiver registered (RECEIVER_EXPORTED)");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerReceiver failed: " + t);
        }
    }

    /** 【code 941】探测应答的日志是否已打过（心跳每 3s 一次，不能刷屏）。 */
    private static boolean sPingAnsweredOnce = false;

    /**
     * 【code 941】应答 App 侧的作用域探测（见 {@link StatusBarSubtitleBridge#ACTION_SCOPE_PING}）。
     *
     * 「本方法被执行过」本身就是证据：只有**被注入到 SystemUI 进程的模块**才会注册
     * {@link StatusBarSubtitleBridge#ACTION_SCOPE_PING} 的接收器（在 {@link #registerReceiver}
     * 里注册）。所以「App 侧收到 PONG」⇔「用户勾选了 SystemUI 作用域，且 SystemUI 里
     * 跑着本模块的代码」。
     *
     * PONG 里带上本进程的构建号：App 侧若发现与自己的构建号不一致，就打一行 WARN ——
     * 那说明 **SystemUI 里还是上一版 dex（装完没重启 SystemUI）**，是排查
     * 「新功能怎么没生效」最快的一条线索。
     */
    private static void answerScopePing(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent pong = new Intent(StatusBarSubtitleBridge.ACTION_SCOPE_PONG);
            pong.putExtra(StatusBarSubtitleBridge.EXTRA_PONG_BUILD, BuildConfig.VERSION_CODE);
            ctx.sendBroadcast(pong);
            if (!sPingAnsweredOnce) {
                sPingAnsweredOnce = true;
                XposedCompat.log(TAG + " scope ping answered -> pong sent"
                        + " (systemui build=" + BuildConfig.VERSION_CODE + ")");
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " answerScopePing failed: " + t);
        }
    }

    // ======================================================================
    // 显示 / 隐藏
    // ======================================================================

    private static final Runnable HIDE_TASK = new Runnable() {
        @Override
        public void run() {
            hideSubtitleNow();
        }
    };

    private static void showLine(String line, long durationMs, boolean playing) {
        sLine = line == null ? "" : line;
        sPlaying = playing;
        if (sContainer == null) {
            return;
        }
        if (!sEnabled || !playing || sLine.isEmpty()) {
            // 宽限一下再收：切页 / 换轨会瞬时把播放态置为暂停，立刻还原会看到时钟闪出来（问题 1）
            sHandler.removeCallbacks(HIDE_TASK);
            sHandler.postDelayed(HIDE_TASK, HIDE_GRACE_MS);
            return;
        }
        sHandler.removeCallbacks(HIDE_TASK);

        syncFontFromClock();
        syncColorFromClock();
        // 【code 930 说明】setText 排在 applyFluidBoundary(true) 之前是 929 的改动，
        //   当时误判为 bug 1 根因；实测宽度取决于「可用空间」而非文字宽度，故它并非根因
        //   （真根是 applyFluidBoundary 的 force 路径在 929 被降级成 150ms 延迟合并，已修复）。
        //   这里保留 setText 在前的顺序（无害，且让 showLine begin 诊断量到的 textW 是已知值）。
        if (sLineView != null) {
            // 先 setText，再 setVisibility 自愈（顺序对了：先有文字，再暴露），
            // 自愈后 setLayoutParams 才会基于"已知文字"算宽度。
            sLineView.setText(sLine);
            sLineView.setScrollX(0);
        }
        applySuppression(true);
        sContainer.setVisibility(View.VISIBLE);
        // 【code 928 问题 1】自愈：行视图若被任何历史路径 GONE（如双击本地隐藏的
        //   旧实现），必须在这里恢复，否则容器可见而行视图 GONE = 状态栏空白。
        // 【code 929 bug 1 加强】同时看 sContainer.isShown() —— parent=VISIBLE 才真显示；
        //   老实现只查 sLineView.getVisibility()，但 parent GONE 时 isShown() 仍是 false，
        //   双击后再开字幕 → 行视图"自己声明 VISIBLE 但被父容器 GONE" → 屏幕看不见。
        if (sLineView != null
                && (sLineView.getVisibility() != View.VISIBLE
                    || !sLineView.isShown())) {
            XposedCompat.log(TAG + " showLine self-heal sLineView: vis="
                    + sLineView.getVisibility() + " shown=" + sLineView.isShown()
                    + " -> VISIBLE");
            sLineView.setVisibility(View.VISIBLE);
        }
        // 【code 929 诊断】开始一行就记录：text 前 32 字符 + textW + viewW。
        // 真机复现 bug 1 时直接拿来定位是 setText 没起效、width 没落地，还是别的。
        if (sLineView != null) {
            try {
                float tw = sLineView.getPaint().measureText(sLine);
                int vw = sLineView.getWidth() - sLineView.getPaddingLeft()
                        - sLineView.getPaddingRight();
                XposedCompat.log(TAG + " showLine begin: text=<"
                        + (sLine.length() > 32 ? sLine.substring(0, 32) + "..." : sLine)
                        + "> textW=" + (int) Math.ceil(tw)
                        + " viewW=" + vw
                        + " vis=" + sLineView.getVisibility());
            } catch (Throwable ignored) { }
        }
        updateBadge();
        startTick();
        applyFluidBoundary(true);

        if (sLine.equals(sShownLine)) {
            return; // 同一行：不重排、不重滚动（避免抖动）
        }
        sShownLine = sLine;
        sDurationMs = durationMs;
        cancelScroll();
        // 新行 = 旧速度作废：起滚时重新定速（定下之后，本行内无论宽度怎么变都不再改）
        sLiveTargetPx = 0;
        sScrollSpeedPxPerMs = 0d;
        sScrollArmed = false;
        // 【code 929 bug 1】setText 已经在调用顶部做过，这里删掉避免重复。
        sLineView.setScrollX(0);
        // 【code 927 问题 1】换行 = 上一行的宽度结论作废：
        //   下一次起滚必须等**新行**的宽度落地（否则又会拿满宽/旧宽起滚）。
        sWidthSettled = false;
        sScrollArmed = false;
        postScrollWhenLaidOut(0);
    }

    private static void hideSubtitle() {
        sHandler.removeCallbacks(HIDE_TASK);
        hideSubtitleNow();
    }

    private static void hideSubtitleNow() {
        cancelScroll();
        if (sContainer != null) {
            sContainer.setVisibility(View.GONE);
        }
        applySuppression(false);
        stopTick();
        sShownLine = null;
        sScrollTarget = 0;
        sLiveTargetPx = 0;
        sScrollArmed = false;
    }

    // ======================================================================
    // 样式：与状态栏时钟保持一致
    // ======================================================================

    /** 字体（typeface / 字号 / 字距 / 字体特性）：对齐时钟即可，换时钟实例时重来一次。 */
    private static void syncFontFromClock() {
        View clock = sClockViews.isEmpty() ? null : sClockViews.get(0);
        if (sLineView == null || clock == null || !(clock instanceof TextView)) {
            return;
        }
        if (sFontSynced && sFontSource == clock) {
            return;
        }
        try {
            TextView c = (TextView) clock;
            sLineView.setTypeface(c.getTypeface());
            sLineView.setTextSize(TypedValue.COMPLEX_UNIT_PX, c.getTextSize());
            sLineView.setLetterSpacing(c.getLetterSpacing());
            sLineView.setFontFeatureSettings(c.getFontFeatureSettings());
            // 阴影也跟时钟一致：状态栏数字本来没有阴影就不加（浅色底上硬加黑阴影会糊边，问题 2）。
            try {
                if (c.getShadowRadius() > 0f) {
                    sLineView.setShadowLayer(c.getShadowRadius(), c.getShadowDx(), c.getShadowDy(),
                            c.getShadowColor());
                } else {
                    sLineView.setShadowLayer(0f, 0f, 0f, 0);
                }
            } catch (Throwable ignored) {
            }
            sFontSynced = true;
            sFontSource = clock;
            XposedCompat.log(TAG + " font synced from clock: typeface=" + c.getTypeface()
                    + ", size=" + c.getTextSize() + "px");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " syncFontFromClock failed: " + t);
        }
    }

    /**
     * 颜色：**每次都重新同步**（不再是「只同步一次」）。
     * 浅色状态栏上系统会把图标/时钟改成深色，字幕必须跟着变黑，否则白字直接隐身（问题 2）。
     */
    private static void syncColorFromClock() {
        View clock = sClockViews.isEmpty() ? null : sClockViews.get(0);
        if (clock == null || !(clock instanceof TextView)) {
            return;
        }
        try {
            onClockColorChanged(((TextView) clock).getCurrentTextColor());
        } catch (Throwable ignored) {
        }
    }

    private static void applyBadgeStyle(int clockArgb) {
        if (sBadge == null) {
            return;
        }
        // 【1.21.12 问题 1】实心圆**不再强制不透明** —— 原先是 `clockArgb | 0xFF000000`。
        //
        // 字幕文字用的是**原样的**时钟色（onClockColorChanged -> sLineView.setTextColor(color)），
        // 而 ColorOS 在深色状态栏上给的是「纯白 + alpha」这种值。alpha 被日志里的
        // `color & 0x00FFFFFF` 抹掉了，所以从日志上完全看不出来（记到的是 #ffffff）。
        //
        // 实测（Ari 截图 6096px 宽，取亮像素峰值）：
        //   - 时钟色 #cccccc（不透明）那一行：徽标填充 203.7 / 字幕字形 201.5 —— 本来就一致；
        //   - 时钟色 #ffffff（带 alpha ≈0.8）那一行：徽标填充 **248.5**(max 255) / 字幕 197.5
        //     —— 徽标亮一大截，正是 Ari 说的「通知图标太亮了」。
        // 两者差的正是被强行补掉的 alpha。现在把 alpha 一并继承，按构造就是同一个颜色。
        int rgb = clockArgb & 0x00FFFFFF;
        int alpha = (clockArgb >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;                       // 时钟色没带 alpha（多数 ROM 直接给不透明色）
        }
        int fill = rgb | (alpha << 24);
        try {
            // 【code 945】只写圆底色。数字**不再着色** —— 它由 NotificationBadgeView
            // 用 CLEAR 从圆底里挖出来，数字区域直接透出状态栏自己画的底，
            // 比旧的 #1A1A1A / #F2F2F2 近似色更准，也不再依赖 isLight() 的明暗猜测。
            sBadge.setBadgeDiscColor(fill);
            sBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, BADGE_TEXT_SP);
            sBadge.setTypeface(Typeface.DEFAULT_BOLD);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLight(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 150;
    }

    // ======================================================================
    // 遮挡时钟 / 左侧通知图标 + 通知数徽标
    // ======================================================================

    private static void applySuppression(boolean hide) {
        if (hide) {
            refreshSystemViews();      // 每次都重收集：状态栏重建后旧引用压不住新实例
            boolean visChanged = false;
            for (View v : sClockViews) {
                visChanged |= hideOne(v);
            }
            for (View v : sNotifViews) {
                visChanged |= hideOne(v);
            }
            // 【code 940 bug1 治本】hideOne 真的改了可见性 ⇒ 下一次 layout 之前
            //   screenX(sLineView) 不可信（实测读到含通知图标占位的旧值 ~251）。
            //   旧代码没置脏 ⇒ showLine 紧随其后 1ms 采样到该失真值并永久锁存。
            //   这里复用 924 的 sLayoutDirty 机制，让紧随其后的采样直接被拦掉。
            //   ⚠️ 只在**真的改了**才置脏：心跳里 hideOne 是幂等的（已 GONE 不再 set），
            //   否则会每 600ms 置脏一次、把实测分支永久掐死。
            if (visChanged) {
                sLayoutDirty = true;
            }
            if (!sSuppressed) {
                XposedCompat.log(TAG + " system parts hidden (clock x" + sClockViews.size()
                        + ", notifArea x" + sNotifViews.size() + ")");
            }
            sSuppressed = true;
        } else {
            if (!sSuppressed && sHiddenPrev.isEmpty()) {
                return;
            }
            // ⚠️ 顺序是关键：必须先落 sSuppressed、再开 sRestoring 门，最后才做还原。
            // 否则还原动作本身（setVisibility(VISIBLE)）会被压制 hook 当成「SystemUI 又露出来了」
            // 立刻压回 GONE —— 时钟就永远回不来（问题 1 的根因）。
            sSuppressed = false;
            int restored = 0;
            sRestoring = true;
            try {
                for (Map.Entry<View, Integer> e : sHiddenPrev.entrySet()) {
                    try {
                        View v = e.getKey();
                        if (v == null) {
                            continue;
                        }
                        int want = e.getValue() == null ? View.VISIBLE : e.getValue();
                        if (v.getVisibility() != want) {
                            v.setVisibility(want);
                        }
                        if (v.getVisibility() == want) {
                            restored++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                sRestoring = false;
            }
            sHiddenPrev.clear();
            // 【code 940 bug1】同理：还原可见性也会触发一次 relayout，算作布局脏。
            if (restored > 0) {
                sLayoutDirty = true;
            }
            XposedCompat.log(TAG + " system parts restored (clock + notification icons), restored="
                    + restored);
        }
    }

    /** @return 是否**真的**改了可见性（改了就会触发一次 relayout，调用方据此置 layoutDirty）。 */
    private static boolean hideOne(View v) {
        if (v == null) {
            return false;
        }
        try {
            if (!sHiddenPrev.containsKey(v)) {
                sHiddenPrev.put(v, v.getVisibility());
            }
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 心跳 / 每次布局：把被 SystemUI 改回可见的时钟、图标再压回去（不重新收集，快）。 */
    private static void enforceSuppression() {
        if (!sSuppressed) {
            return;
        }
        try {
            for (int i = 0; i < sClockViews.size(); i++) {
                View v = sClockViews.get(i);
                if (v != null && v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                }
            }
            for (int i = 0; i < sNotifViews.size(); i++) {
                View v = sNotifViews.get(i);
                if (v != null && v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            enforceSuppression();
            updateBadge();
            syncColorFromClock();
            applyFluidBoundary(false);
            sHandler.postDelayed(this, TICK_MS);
        }
    };

    private static void startTick() {
        sHandler.removeCallbacks(TICK);
        sHandler.postDelayed(TICK, TICK_MS);
    }

    private static void stopTick() {
        sHandler.removeCallbacks(TICK);
    }

    /**
     * 【code 946】徽标几何自证：状态**变了才**打一行。
     *
     * 起因：945 那次「徽标整块不显示」时，日志里只有 `notification badge = 1`
     * 这条**记账**（模块以为它显示了），却没有任何一行能回答「它到底有没有尺寸、
     * 有没有被置 GONE」。这条日志就是补上那一格 —— 一行看穿 ctn/badge/line 三方状态。
     */
    private static void logBadgeGeometry(String when) {
        try {
            StringBuilder sb = new StringBuilder(192);
            sb.append("ctn[vis=").append(visOf(sContainer)).append(' ').append(wh(sContainer)).append(']')
                    .append(" badge[vis=").append(visOf(sBadge));
            if (sBadge != null) {
                sb.append(" l=").append(sBadge.getLeft()).append(" t=").append(sBadge.getTop());
            }
            sb.append(' ').append(wh(sBadge))
                    .append(" text=").append(sBadge == null ? "-" : sBadge.getText()).append(']')
                    .append(" line[vis=").append(visOf(sLineView)).append(' ').append(wh(sLineView))
                    .append(']');
            String cur = sb.toString();
            if (cur.equals(sBadgeGeomPrev)) {
                return;
            }
            sBadgeGeomPrev = cur;
            XposedCompat.log(TAG + " badge geom [" + when + "] " + cur);
        } catch (Throwable ignored) {
        }
    }

    private static int visOf(View v) {
        return v == null ? -1 : v.getVisibility();
    }

    private static String wh(View v) {
        return v == null ? "w=-1 h=-1" : ("w=" + v.getWidth() + " h=" + v.getHeight());
    }

    private static void updateBadge() {
        if (sBadge == null || sContainer == null || sContainer.getVisibility() != View.VISIBLE) {
            return;
        }
        int count = countNotifications();
        if (count == sBadgeCount) {
            return;
        }
        sBadgeCount = count;
        if (count <= 0) {
            sBadge.setVisibility(View.GONE);
            applyBadgeInset(false);   // 【1.21.11 问题 2】没通知 -> 字幕顶到最左，不留徽标位
            // 【code 946】这条分支原先**没有日志** —— 徽标被静默藏起来时无从查证，补上。
            XposedCompat.log(TAG + " notification badge = 0 -> badge hidden");
            logBadgeGeometry("badge=0");
            return;
        }
        applyBadgeStyle(sLastColorArgb == Integer.MIN_VALUE ? 0xFFFFFFFF : sLastColorArgb);
        sBadge.setBadgeText(count > 99 ? "99+" : String.valueOf(count));
        sBadge.setVisibility(View.VISIBLE);
        applyBadgeInset(true);        // 有通知 -> 字幕退到徽标右侧
        XposedCompat.log(TAG + " notification badge = " + count);
        logBadgeGeometry("badge=" + count);   // 【code 946】几何自证
    }

    /**
     * 【1.21.11 问题 2】字幕左内边距：只有通知徽标**真的显示**时才让位。
     *
     * 实测依据（Ari 提供的参考截图，1920px 宽、屏宽 1272 -> 1.509x）：
     *   - 参考插件「无通知」那行歌词最左前景列 x=100 -> 屏 66px；
     *   - 同一张图里我们那行「真拿你没办法」起点 x=166 -> 屏 110px；
     *   - 差 44px = 14.6dp ≈ BADGE_SIZE(13dp) + BADGE_GAP(2dp)，一个不多一个不少。
     * 所以无通知时把这 15dp 让回去，起点就和参考插件对齐了；有通知时再补上。
     *
     * 注意：改 marginStart 会触发一次 relayout（进而被 onGlobalLayout 捕获、重算可用宽度），
     * 所以这里做了「值没变就返回」的门，别让 600ms 心跳把它变成周期性重排。
     */
    private static void applyBadgeInset(boolean badgeVisible) {
        if (sLineView == null) {
            return;
        }
        ViewGroup.LayoutParams lp = sLineView.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        int want = dp(LINE_LEFT_PAD_DP + (badgeVisible ? (BADGE_SIZE_DP + BADGE_GAP_DP) : 0f));
        if (flp.getMarginStart() == want) {
            return;
        }
        flp.setMarginStart(want);
        sLineView.setLayoutParams(flp);
        // 【code 924 bug2】改动 marginStart 后，**下一次 layout 之前**算出来的
        //   screenX(sLineView) 不可信（旧 getLeft + 新 margin 混在一起）-> 置脏。
        //   onGlobalLayout 里清（那才是「layout 真的跑完」的信号）。
        sLayoutDirty = true;
        XposedCompat.log(TAG + " line left inset -> " + want + "px (badge "
                + (badgeVisible ? "shown" : "hidden") + ") [layoutDirty]");
    }

    /**
     * 【code 940 bug1 根修】行左界实测值的采信门 —— 必须拿**常量**当锚，不能拿
     *   {@link #sLineLeftAcc} 自己当锚。
     *
     * ── 真机实证（work_diag_65 / 2026-09-22 11:14 日志 / build 939）──
     *   同一份日志内部自比对（最硬）：
     *     · 11:14:00.138 `[layoutDirty]` 还挂着 -> 用常量 -> max 291px left=404
     *       ⇒ 行左界 = 404-291 = **113**（正常）；
     *     · 11:14:01.713 布局干净了 -> 用实测 -> max 330px (by cutout_space_view
     *       left=581) ⇒ 行左界 = 581-330 = **251**（错）。
     *   同一个 cutout 右界 581：用常量该得 468px，用实测只剩 330px —— 左界白吃
     *   138px ⇒ 字被压成「超级短」。
     *
     * ── 根因 ──
     *   旧口径 {@code sLineLeftAcc <= 0 || |real - sLineLeftAcc| <= dp(20)}：初值 -1
     *   ⇒ **首采样无条件过门**（注释里假设「会跟常量 113 比」，代码里并没有）。
     *   而 {@code showLine()} 在 `system parts hidden` 之后 **1ms** 就调
     *   {@code applyFluidBoundary(true)} —— 那次 hide 的 relayout 还没跑，
     *   {@code screenX(sLineView)} 仍是旧值 251 ⇒ 被锁存；之后真值 ~110 因
     *   |110-251| = 141 > 60 **永久拒收**，直到 SystemUI 重启。
     *
     * @return 是否采用了 real（调用方直接读 {@link #sLineLeftAcc}）
     */
    private static boolean acceptLineLeft(int real, int right) {
        if (real <= 0 || real >= right) {
            return false;
        }
        int anchor = (sLineLeftAcc > 0) ? sLineLeftAcc : dp(FLUID_LINE_LEFT_DP);
        if (Math.abs(real - anchor) <= dp(20)) {
            // 落在容差内 —— 正常采信（含徽标让位带来的 ±15dp 级平移）
            sLineLeftAcc = real;
            sLineLeftRejectStreak = 0;
            sLineLeftRejectPrev = -1;
            return true;
        }
        // 超出容差：先记账。单帧跳变（置脏瞬间的失真值）会因下一拍回到真值而被清空；
        // 只有连续 N 拍都稳定停在同一个新位置，才认作容器真的搬家了。
        if (sLineLeftRejectPrev > 0 && Math.abs(real - sLineLeftRejectPrev) <= dp(20)) {
            sLineLeftRejectStreak++;
        } else {
            sLineLeftRejectStreak = 1;
        }
        sLineLeftRejectPrev = real;
        if (sLineLeftRejectStreak >= LINE_LEFT_RELOCK_STREAK) {
            sLineLeftAcc = real;
            sLineLeftRejectStreak = 0;
            sLineLeftRejectPrev = -1;
            XposedCompat.log(TAG + " line left relocked -> " + real
                    + "px (sustained offset from anchor)");
            return true;
        }
        if (sLineLeftRejectStreak == 1) {
            XposedCompat.log(TAG + " line left rejected -> " + real + "px (anchor="
                    + anchor + ", tol=" + dp(20) + ")");
        }
        return false;
    }

    /**
     * 通知数来源（按可靠性排序）：
     *  ① 通知图标容器里**直接可见的子视图数** —— 与「被遮挡的那几个图标」一一对应，
     *     参考实现显示的正是这个数（实测为 3）。用「可见性」而不看宽度，因为容器被我们
     *     GONE 掉之后子视图不会再重新测量，但有新通知时子视图仍会带着 VISIBLE 被加进来；
     *  ② {@code NotificationManager.getActiveNotifications()} —— 仅当 ① 拿不到（例如 ROM
     *     设置里关掉了状态栏通知图标）时兜底。
     */
    private static int countNotifications() {
        try {
            int n = 0;
            for (View area : sNotifViews) {
                n += countIconChildren(area);
            }
            if (n > 0) {
                return n;
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager)
                    sRoot.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                StatusBarNotification[] arr = nm.getActiveNotifications();
                if (arr != null && arr.length > 0) {
                    return arr.length;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int countIconChildren(View area) {
        if (area == null) {
            return 0;
        }
        ViewGroup container = null;
        if (area instanceof ViewGroup
                && area.getClass().getName().contains("NotificationIconContainer")) {
            container = (ViewGroup) area;
        } else {
            View v = findByClassHint(area, "NotificationIconContainer");
            if (v instanceof ViewGroup) {
                container = (ViewGroup) v;
            }
        }
        if (container == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View c = container.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE) {
                n++;
            }
        }
        return n;
    }

    // ======================================================================
    // 显示宽度（问题 3）：照搬 base.apk（com.rikumi.colorosmod）的 updateLyricWidth
    // ======================================================================

    /**
     * 字幕可用宽度 = 右界 − 左界；右界由「状态栏上占用宽度的元素」逐个向左收缩：
     *  ① 上限 = 锚点宿主（host）右边界，并用 {@code status_bar_start_side_container} 右边界收紧；
     *  ② 挖孔 {@code cutout_space_view} 左边界（可见且宽度>0，且落在区间内）；
     *  ③ 流体云真身 {@code seeding_card_container}（类名 CapsulePluginContainer）内
     *     **最左可见子视图**左边界 − 2dp —— 递归判定，且过滤 alpha≤0.01 的「隐形但还在树里」的子视图；
     *  ④ 非 ColorOS 兜底：{@link #FLUID_IDS}/{@link #FLUID_HINTS}/{@link #findFluidByGeometry()} 找到的胶囊左界。
     *
     * 与 base.apk 一致：结果**带缓存**，宽度没变就不重排（避免每帧 setLayoutParams 抖动）。
     * 探测到的右界离左界太近（< {@link #MIN_LINE_W_DP}）视为假目标，放弃收缩、保持整宽。
     */
    private static void applyFluidBoundary(boolean force) {
        if (sLineView == null || sContainer == null || sRoot == null || sApplyingBoundary) {
            return;
        }
        if (sContainer.getVisibility() != View.VISIBLE) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean needScan = force || !isFluidUsable(sFluidView) || now - sLastFluidFindMs > FLUID_FIND_MS;
        if (needScan) {
            sLastFluidFindMs = now;
        }

        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        String why = "full width (no side parts)";
        try {
            final int base = screenX(sRoot);
            // 【code 932 bug1 根修】行左界改用固定常量 dp(38)（928/929 实测稳定值 113px /
            //   density 2.975）。此前实时探测 screenX(sContainer)+sLineView.getLeft()：
            //   首帧 layout 未稳时该值跳到 ~269 -> 算出 315 半截宽；931 又把该窄值立即落地，
            //   且延迟链只重算不落地 -> 错值终身保留（18:06 日志：首次 315 后所有 showLine 恒
            //   viewW=315，再无第二条 subtitle width 日志）。行是状态栏最左元素、左界由布局
            //   固定 -> 用常量彻底切断「未稳探测 -> 错宽落地」链路。
            int left = dp(FLUID_LINE_LEFT_DP);

            // ① 右界上限 = 宿主容器右边界（拿不到就退回整条状态栏）
            int right;
            if (sHost != null && sHost.getWidth() > 0) {
                right = screenX(sHost) - base + sHost.getWidth();
            } else {
                right = sRoot.getWidth();
            }
            View side = findById(SIDE_CONTAINER_IDS);
            if (side != null && side.getWidth() > 0) {
                right = screenX(side) - base + side.getWidth();
            }
            // 【code 935 bug1】左界要跟着「通知徽标让位」走：applyBadgeInset 把
            //   marginStart 在 42px(有通知) / 3px(无通知) 之间切（日志实证两条都在），
            //   而 932 起的常量 dp(38)=113px 只代表其中一种状态 -> 另一状态下 avail
            //   差约 39px -> 字幕右端到流体云左缘的距离时短时长（Ari 23:41 反馈）。
            //   常量仍作兜底；实测值需「与上次采信值相差 <= 20dp」才采信 —— 932 的
            //   首帧未稳跳变是 269 vs 113（差 156px），远超阈值 -> 被拒。
            if (sLineView != null && !sLayoutDirty && right > 0) {
                int real = screenX(sLineView) - base;
                // 【code 940 bug1 根修】采信门搬进 acceptLineLeft：必须拿常量当锚。
                acceptLineLeft(real, right);
                if (sLineLeftAcc > 0 && sLineLeftAcc < right) {
                    left = sLineLeftAcc;
                }
            }

            // ②③④ 所有「占用元素左边界」候选取最小值
            int limit = Integer.MAX_VALUE;
            String who = "none";
            View cutout = findById(CUTOUT_IDS);
            if (cutout != null && cutout.getVisibility() == View.VISIBLE && cutout.getWidth() > 0) {
                int l = screenX(cutout) - base;
                if (l < limit) {
                    limit = l;
                    who = "cutout_space_view";
                }
            }
            View seeding = findById(SEEDING_IDS);
            // 【code 937 问题1 根修】先判「容器自身现在还活着」，再进去找子视图。
            //   旧口径只看子视图 → 容器 GONE/被摘掉后子视图仍报 VISIBLE ⇒ 死容器给出 454。
            boolean seedingUsable = isSeedingUsable(seeding);
            int seedingState = (seeding == null) ? 0 : (seedingUsable ? 2 : 1);
            if (seedingState != sLastSeedingState) {
                sLastSeedingState = seedingState;
                String extra = "";
                if (seeding != null) {
                    extra = " vis=" + seeding.getVisibility()
                            + " shown=" + seeding.isShown()
                            + " alpha=" + seeding.getAlpha()
                            + " w=" + seeding.getWidth()
                            + " h=" + seeding.getHeight()
                            + " kids=" + (seeding instanceof ViewGroup
                                    ? ((ViewGroup) seeding).getChildCount() : -1);
                }
                XposedCompat.log(TAG + " seeding state -> "
                        + (seedingState == 0 ? "absent"
                                : (seedingState == 2 ? "usable" : "present-but-dead"))
                        + extra);
            }
            if (seedingUsable) {
                int l = leftmostVisibleChildLeft(seeding, base, left);
                if (l != Integer.MAX_VALUE) {
                    l -= dp(FLUID_GAP_DP);
                    sLastSeedingLimit = l;
                    sLastSeedingOkMs = now;
                    if (l < limit) {
                        limit = l;
                        who = "seeding_card_container(" + seeding.getClass().getSimpleName() + ")";
                    }
                } else if (sLastSeedingLimit != Integer.MAX_VALUE
                        && now - sLastSeedingOkMs < SEEDING_GRACE_MS) {
                    // 【code 934 bug1】容器在、子视图暂时全不可见（动画瞬间）：
                    //   沿用上一次 seeding 边界，别把右界跳回 cutout 满宽（闪动源头之一）。
                    if (sLastSeedingLimit < limit) {
                        limit = sLastSeedingLimit;
                        who = "seeding_card_container(grace)";
                    }
                }
            } else if (sLastSeedingLimit != Integer.MAX_VALUE
                    && now - sLastSeedingOkMs < SEEDING_GRACE_MS) {
                // 【code 935 bug1】容器被短暂摘掉（换页 / 视图重建）时同样沿用上次边界：
                //   旧代码这里把 grace 立刻清零 -> 右界瞬间弹回 cutout(583) 又马上弹回
                //   seeding(454)，129px 来回跳，也是「时短时长」的一个来源；
                //   容器真的没了由 SEEDING_GRACE_MS 超时兜底。
                if (sLastSeedingLimit < limit) {
                    limit = sLastSeedingLimit;
                    who = "seeding_card_container(grace-null)";
                }
            }
            if (seeding == null && needScan) {
                // 非 ColorOS 兜底：老那套探测只在没有 seeding 容器时才跑（BFS 不便宜）
                sFluidView = findFluidCloud();
            }
            if (isFluidUsable(sFluidView)) {
                int l = screenX(sFluidView) - base - dp(LINE_RIGHT_PAD_DP);
                if (l < limit) {
                    limit = l;
                    who = "fluid:" + sFluidView.getClass().getSimpleName();
                }
            }

            if (limit != Integer.MAX_VALUE && limit > left && limit < right) {
                right = limit;
            }
            int avail = right - left;
            if (avail >= dp(MIN_LINE_W_DP)) {
                if (limit != Integer.MAX_VALUE && right == limit) {
                    width = avail;
                    why = "max " + avail + "px (by " + who + " left=" + (limit + base) + ")";
                } else {
                    why = "max " + avail + "px (side container only)";
                    width = avail;
                }
            } else {
                why = "candidate too close (" + avail + "px) -> keep full width";
            }
        } catch (Throwable t) {
            why = "probe failed: " + t;
        }

        if (!force && sAppliedLineWidth != Integer.MIN_VALUE
                && Math.abs(width - sAppliedLineWidth) < WIDTH_HYSTERESIS_PX) {
            return; // 迟滞：1~2px 的抖动不重排
        }
        // 【code 927 问题 1 的配套】宽度即将改变 ⇒ 把「已落地」标记复位。
        //   保证新宽度在 applyPendingWidth 落地之前，startScroll 不会拿**旧宽/满宽**去起滚。
        //   与 startScroll 的闸 + applyPendingWidth 的补起滚构成完整闭环：
        //     复位 → (等待/立即) 落地 → 置位 → 补起滚。
        sWidthSettled = false;
        sPendingWidth = width;
        sPendingWidthWhy = why;
        sHandler.removeCallbacks(WIDTH_TASK);
        // 【code 931 bug1 修复】推翻 930 的「force 立即落地」：根因不是 setText 时序、也不是
        //   延迟合并，而是 showLine 刚 VISIBLE+setText 时字幕行屏幕位置尚未 layout 稳定
        //   （lineX 从稳定值 ~113 跳到 ~306 再回 113），applyFluidBoundary 立刻算宽度会拿到
        //   未稳位置 -> 窄值（半截）。930 把这次窄值立即落地，反而固定了半截。
        //   修复：force 也走延迟合并（WIDTH_TASK 改 applyFluidBoundary(false) 重算，基于稳定布局）；
        //   首次（sAppliedLineWidth==MIN）才立即给个宽度避免空白；非首次保持上次稳定满宽，
        //   延迟后重算一致 -> 不再露半截。WIDTH_SETTLE_MS 150->220 保证 layout 稳定后才算。
        if (force) {
            // 【code 932 bug1 根修】左界已是常量、cutout 位置从 attach 起就稳定 ->
            // force 当下算出的就是正确满宽，直接落地（不再有 930/931 纠结的未稳窗口）。
            // 仍补一次延迟重算兜底（流体云动画可能移动右界），迟滞门保证无变化不重排。
            applyPendingWidth();
            if (!sInWidthTask) {
                sHandler.postDelayed(WIDTH_TASK, WIDTH_SETTLE_MS);
            }
        } else {
            if (!sInWidthTask) {
                sHandler.postDelayed(WIDTH_TASK, WIDTH_SETTLE_MS);  // 心跳/布局：延迟合并
            }
        }
    }

    /** 真正落地宽度：由 {@link #applyFluidBoundary} 立即调用，或去抖窗口结束后调用。 */
    private static void applyPendingWidth() {
        int width = sPendingWidth;
        if (width == Integer.MIN_VALUE || sLineView == null) {
            return;
        }
        // 【code 934 bug1】宽度迟滞：差值 <24px 视为无变化（首次落地不受限）。
        //   实测 seeding 最左子视图在 448~454 间抖 6px，每次落地都触发 retarget = 闪动。
        if (sAppliedLineWidth != Integer.MIN_VALUE
                && Math.abs(width - sAppliedLineWidth) < WIDTH_HYSTERESIS_PX) {
            // 【code 933 bug1 修复】连续两条等宽字幕时宽度无变化，原 early-return 让
            // sWidthSettled 永远停在 false（applyFluidBoundary 开头已复位、此处不置位），
            // 于是 startScroll 的 width not settled 闸一直挡着 -> 长字幕(textW>viewW)永不滚动。
            // 修正：宽度无变化即视为已 settle（当前应用的本就是正确满宽），并补起滚。
            sWidthSettled = true;
            if (!sScrollArmed) {
                startScroll();
            } else {
                retargetScrollForNewWidth(width); // 校正目标，沿用原速度，不重启动画
            }
            return;
        }
        int prev = sAppliedLineWidth;
        try {
            sApplyingBoundary = true;
            ViewGroup.LayoutParams lp = sLineView.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).width = width;
                sLineView.setLayoutParams(lp);
            }
            sAppliedLineWidth = width;
            // 【code 927 问题 1】宽度**已落地** ⇒ 开放起滚。
            //   若这一行还没起滚（首次开字幕时被 startScroll 的闸挡掉），在这里补一次；
            //   已起滚的行走 retarget（不重启动画，保住速度）。
            boolean wasSettled = sWidthSettled;
            sWidthSettled = true;
            XposedCompat.log(TAG + " subtitle width: " + sPendingWidthWhy
                    + " (prev=" + (prev == Integer.MIN_VALUE ? "none" : prev + "px") + ")"
                    + " [widthSettled]");
            // 新宽度刚设、还没经过 layout，getWidth() 仍是旧值 -> 显式传入新宽度算目标
            retargetScrollForNewWidth(width);
            if (!wasSettled && !sScrollArmed) {
                XposedCompat.log(TAG + " startScroll armed after width settled");
                startScroll();
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " applyFluidBoundary failed: " + t);
        } finally {
            sApplyingBoundary = false;
        }
    }

    /** 按资源名找状态栏内部视图（带缓存 id，找不到返回 null）。 */
    private static View findById(String[] names) {
        if (sRoot == null) {
            return null;
        }
        for (String n : names) {
            try {
                int rid = resId(n);
                if (rid != 0) {
                    View v = sRoot.findViewById(rid);
                    if (v != null) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 视图的屏幕 x。宽度计算里所有横向量都换算到这个坐标系再比较。 */
    private static int screenX(View v) {
        if (v == null) {
            return 0;
        }
        try {
            int[] p = new int[2];
            v.getLocationOnScreen(p);
            return p[0];
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 【code 937 问题1】流体云容器「此刻真的有内容」判据。
     *
     * 四条同时成立才采信：
     *   · {@code isShown()} —— 自身 VISIBLE 且**所有祖先** VISIBLE（缺这条 = 死容器照样采信）；
     *   · {@code alpha > 0.01} —— 收起动画末尾的残影不算；
     *   · {@code getWidth() > 0 && getHeight() > 0} —— 已被收成 0 尺寸的空壳不算。
     *
     * 不成立时走 {@link #SEEDING_GRACE_MS} 宽限（2s）后回退到 cutout 候选，
     * 于是「流体云不在 ⇒ 字幕自然变长」而不是被一个死容器锁成半截。
     */
    private static boolean isSeedingUsable(View seeding) {
        if (seeding == null) {
            return false;
        }
        try {
            return seeding.isShown()
                    && seeding.getAlpha() > 0.01f
                    && seeding.getWidth() > 0
                    && seeding.getHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【code 937 问题1】「这个视图现在真的会被画出来吗」—— 比 getVisibility() 严格得多。
     *
     * 旧口径的两个漏判（都真机踩到）：
     *   ① 祖先 GONE：子视图自身标志位还是 VISIBLE —— 由 {@code isShown()} 兜住；
     *   ② 祖先 alpha=0（淡出动画期间）：alpha 是**沿祖先链相乘**生效的，但
     *      {@code getAlpha()} 只返回自身那一个值 —— 子视图自己是 1.0、实际全透明。
     *      这里把自身到 {@code stopAt}（含）的整条链都过一遍。
     *
     * @param stopAt 判定上界（流体云容器自身）；为 null 则一路走到根
     */
    private static boolean isEffectivelyVisible(View v, View stopAt) {
        if (v == null || !v.isShown()) {
            return false;
        }
        try {
            View cur = v;
            int guard = 0;
            while (cur != null && guard++ < 12) {
                if (cur.getAlpha() <= 0.01f) {
                    return false;
                }
                if (cur == stopAt) {
                    break;
                }
                ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 递归找 group 内**最左的可见子视图**（base 坐标系）—— 对应 base.apk 的
     * {@code findLeftmostVisibleChildLeft}：只认 VISIBLE、宽>0、alpha>0.01、且 left∈(minValid, best)。
     *
     * alpha 那一项是精髓：胶囊收起/切换时子视图还留在树里、只是 alpha=0，
     * 不排除掉就会把字幕右界一路压到屏左（宽度算法：{@code avail = right - left}）。
     */
    private static int leftmostVisibleChildLeft(View group, int base, int minValid) {
        return leftmostVisibleChildLeft(group, group, base, minValid);
    }

    /** 【code 937 问题1】{@code stopAt} = 有效可见性判定的上界（流体云容器自身）。 */
    private static int leftmostVisibleChildLeft(View group, View stopAt, int base, int minValid) {
        if (!(group instanceof ViewGroup)) {
            return Integer.MAX_VALUE;
        }
        ViewGroup vg = (ViewGroup) group;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            // 【code 937 问题1 根修】旧口径 getVisibility() 只反映**自身标志位**：
            //   容器被 GONE / 从树上摘掉、或祖先正在 alpha 淡出时，子视图自己的
            //   getVisibility() 仍是 VISIBLE、getAlpha() 仍是 1.0、width 仍 > 0
            //   ⇒ 一个「已经画不出来」的容器照样通过判定并返回 left=448/454（真机实证：
            //   让位已从 42px 退回 3px 的那一拍，宽度仍是 "by seeding_card_container left=454"）。
            //   isEffectivelyVisible = isShown()（自身+所有祖先的可见性）+ 祖先链 alpha。
            if (child == null || !isEffectivelyVisible(child, stopAt)) {
                continue;
            }
            try {
                if (child.getAlpha() <= 0.01f) {
                    continue;
                }
                int l = screenX(child) - base;
                if (child.getWidth() > 0 && l > minValid && l < best) {
                    best = l;
                }
            } catch (Throwable ignored) {
            }
            int deeper = leftmostVisibleChildLeft(child, stopAt, base, minValid);
            if (deeper < best) {
                best = deeper;
            }
        }
        return best;
    }

    private static boolean isFluidUsable(View v) {
        if (v == null || v == sContainer || v == sBadge || v == sLineView) {
            return false;
        }
        try {
            if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0) {
                return false;
            }
            if (isAncestorOf(v, sContainer) || isDescendantOf(v, sContainer)) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 名字候选 → 类名候选 → 几何兜底（水平居中、宽度适中的可见视图）。 */
    private static View findFluidCloud() {
        if (sRoot == null) {
            return null;
        }
        for (String n : FLUID_IDS) {
            try {
                int rid = resId(n);
                if (rid != 0) {
                    View v = sRoot.findViewById(rid);
                    if (isFluidUsable(v)) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (String hint : FLUID_HINTS) {
            try {
                View v = findByClassHint(sRoot, hint);
                if (isFluidUsable(v)) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return findFluidByGeometry();
    }

    private static View findFluidByGeometry() {
        View best = null;
        int bestLeft = Integer.MAX_VALUE;
        try {
            int rootW = sRoot.getWidth();
            if (rootW <= 0) {
                return null;
            }
            int[] rl = new int[2];
            sRoot.getLocationOnScreen(rl);
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(sRoot);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 2000) {
                View v = queue.poll();
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null) {
                            queue.add(c);
                        }
                    }
                }
                if (!isFluidUsable(v)) {
                    continue;
                }
                int w = v.getWidth();
                if (w < dp(24) || w > rootW * 0.62f) {
                    continue;   // 太窄不像胶囊；太宽多半是整行容器
                }
                int[] p = new int[2];
                v.getLocationOnScreen(p);
                int left = p[0] - rl[0];
                if (left < dp(40)) {
                    continue;   // 必须在起始区之右
                }
                int cx = left + w / 2;
                if (cx < rootW * 0.34f || cx > rootW * 0.66f) {
                    continue;   // 流体云在水平居中一带
                }
                if (left < bestLeft) {
                    bestLeft = left;
                    best = v;
                }
            }
        } catch (Throwable ignored) {
        }
        if (best != null) {
            XposedCompat.log(TAG + " fluid cloud by geometry: " + best.getClass().getSimpleName()
                    + " left=" + bestLeft + " w=" + best.getWidth());
        }
        return best;
    }

    private static boolean isAncestorOf(View ancestor, View target) {
        View p = target;
        int guard = 0;
        while (p != null && guard++ < 60) {
            if (p == ancestor) {
                return true;
            }
            ViewParent pv = p.getParent();
            p = pv instanceof View ? (View) pv : null;
        }
        return false;
    }

    private static boolean isDescendantOf(View v, View ancestor) {
        return ancestor != null && isAncestorOf(ancestor, v);
    }

    // ======================================================================
    // 单程滚动（替代系统跑马灯）
    // ======================================================================

    /** 等布局完成 → 再静置 {@link #SCROLL_START_DELAY_MS} → 起滚（问题 4）。 */
    private static void postScrollWhenLaidOut(final int attempt) {
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sLineView == null || sContainer == null) {
                    return;
                }
                if (sLineView.getWidth() <= 0 && attempt < 6) {
                    postScrollWhenLaidOut(attempt + 1);
                    return;
                }
                final String expect = sShownLine;
                sHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 静置期间若行已换，放弃这次起滚（新行会自己排一次）
                        if (sLineView == null || !sLine.equals(expect)) {
                            return;
                        }
                        applyFluidBoundary(true);
                        startScroll();
                    }
                }, SCROLL_START_DELAY_MS);
            }
        });
    }

    private static int computeScrollTarget() {
        return computeScrollTarget(-1);
    }

    /**
     * @param widthOverridePx > 0 时用这个宽度算目标：宽度刚写进 LayoutParams、
     *                        还没经过 layout，getWidth() 返回的仍是旧值。
     */
    private static int computeScrollTarget(int widthOverridePx) {
        if (sLineView == null) {
            return 0;
        }
        int rawW = widthOverridePx > 0 ? widthOverridePx : sLineView.getWidth();
        int viewW = rawW - sLineView.getPaddingLeft() - sLineView.getPaddingRight();
        if (viewW <= 0) {
            return 0;
        }
        CharSequence cs = sLineView.getText();
        float textW = sLineView.getPaint().measureText(cs == null ? "" : cs.toString());
        int target = (int) Math.ceil(textW) - viewW;
        if (sLineView.getLayout() != null) {
            int layoutW = (int) Math.ceil(sLineView.getLayout().getWidth()) - viewW;
            if (layoutW > 0) {
                target = Math.min(target, layoutW);
            }
        }
        return Math.max(0, target);
    }

    /**
     * 单程左移：scrollX 0 → (文本宽 - 可视宽)，线性插值，播完停在末尾。
     *
     * 时长优先取「该字幕行自身的播放时长」（← App 侧按 cue 的 start/end 算出并随广播带来），
     * 夹在 [SCROLL_MIN_MS, SCROLL_MAX_MS]，并受 SCROLL_MAX_SPEED_PX_PER_S 约束。
     * 这正是「缓慢而逐渐地从左播到右」，而不是系统跑马灯那种到头就弹回起点。
     */
    private static void startScroll() {
        cancelScroll();
        if (sLineView == null) {
            return;
        }
        try {
            // 【code 927 问题 1】宽度未落地之前**不许起滚**（问题 1 的核心修法）。
            //   真机实证：首次开字幕时 startScroll 跑在宽度落地之前，
            //   getWidth() 还是初始 MATCH_PARENT 的满宽（457px）⇒ 先按满宽起滚
            //   ⇒ 150ms 后宽度落成 311 ⇒ retarget 成裁切 ⇒ 用户看到「先整行再半截」。
            //   ⇒ 直接返回，等 applyPendingWidth 落地后**主动补起滚**。
            if (!sWidthSettled) {
                // 【code 933 精简】移除 width not settled 诊断日志（25 行/会话）。
                return;
            }
            int target = computeScrollTarget();
            sScrollArmed = true;           // 已起滚：之后宽度变化走 retarget（不再重启动画）
            if (target <= 0) {
                sLineView.setScrollX(0);   // 放得下 → 静态显示，不滚动
                sScrollTarget = 0;
                sLiveTargetPx = 0;
                return;
            }
            long dur = sDurationMs > 0 ? sDurationMs : SCROLL_FALLBACK_MS;
            dur = Math.max(SCROLL_MIN_MS, Math.min(SCROLL_MAX_MS, dur));
            long minDurBySpeed = (long) (target * 1000f / SCROLL_MAX_SPEED_PX_PER_S);
            if (dur < minDurBySpeed) {
                dur = minDurBySpeed;
            }
            sScrollTarget = target;
            sLiveTargetPx = target;
            sScrollDurMs = dur;
            // 速度在此**一次性定死**：之后流体云伸缩只改目标，绝不重算速度（1.21.11 问题 1）。
            // 【code 934 bug1 卡住根修】速度下限 = 半倍兜底速度(70px/s)：小目标(target=10px)
            //   会定出 2.5px/s 的微速度并被后续 retarget 沿用 -> 244px 要滚 96 秒
            //   （23:00:06 日志铁证 96770ms）= 「卡住不再继续滚动」。
            double spd = dur > 0 ? (double) target / (double) dur : 0d;
            sScrollSpeedPxPerMs = Math.max(spd, SCROLL_FALLBACK_SPEED_PX_PER_S * 0.5 / 1000d);
            dur = Math.max(60L, (long) (target / sScrollSpeedPxPerMs));
            animateScrollTo(sLineView.getScrollX(), target, dur);
            XposedCompat.log(TAG + " scroll: target=" + target + "px dur=" + dur
                    + "ms (viewW=" + (sLineView.getWidth() - sLineView.getPaddingLeft()
                    - sLineView.getPaddingRight()) + ")");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " startScroll failed: " + t);
        }
    }

    private static void animateScrollTo(final int from, final int to, final long dur) {
        final ValueAnimator a = ValueAnimator.ofInt(from, to);
        final int gen = ++sScrollGen;          // 这一段的代次；被换代就说明有人主动停了它
        a.setDuration(Math.max(60L, dur));
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                if (sLineView == null) {
                    return;
                }
                Object v = anim.getAnimatedValue();
                if (!(v instanceof Integer)) {
                    return;
                }
                int cur = (Integer) v;
                // 【1.21.11 问题 1】目标变小（胶囊收缩/消失、右界变宽）→ 到线即停，
                // 停在当前位置：绝不倒退，也绝不再重启一次动画。
                if (sLiveTargetPx > 0 && cur >= sLiveTargetPx) {
                    if (sAnimator == anim) {
                        sAnimator = null;
                    }
                    sScrollGen++;              // 换代 = 主动停，onAnimationEnd 不续滚
                    sLineView.setScrollX(sLiveTargetPx);
                    anim.cancel();
                    return;
                }
                sLineView.setScrollX(cur);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator anim) {
                if (sAnimator == anim) {
                    sAnimator = null;
                }
                if (gen != sScrollGen || sLineView == null) {
                    return;   // 已被换代（cancelScroll / 到线自停 / 起了新一段）→ 不是自然跑完
                }
                // 【1.21.11 问题 1】目标变大（胶囊出现把右界推窄）→ 本段自然跑完后，
                // 按原速度续滚剩余距离。速度恒定，只是分两段跑。
                if (sLineView.getScrollX() < sLiveTargetPx - 2) {
                    continueScrollToLiveTarget();
                }
            }
        });
        a.start();
        sAnimator = a;
    }

    /**
     * 【1.21.11 问题 1】流体云伸缩 -> 可视宽度变化。
     *
     * 只更新**动态目标** {@link #sLiveTargetPx} 与裁剪宽度，**绝不 cancel/重启动画、绝不重算速度**：
     *  - 目标变小（胶囊收缩 / 消失）→ animateScrollTo 每帧比一次，到线即停（不倒退、不瞬移）；
     *  - 目标变大（胶囊出现把右界推窄）→ 本段动画自然跑完后由 onAnimationEnd 按**原速度**续滚补上。
     *    「新字幕行出现时即使当前行没播完也无所谓」，所以这里不做任何追赶式加速。
     *
     * 旧实现（1.21.9/1.21.10）每变一次宽度就 cancel + 按 remain 重启动画：remain 被 200ms 下限
     * 与 420px/s 上限夹过之后，实际速度已经不等于原速度了；连打几次就是「速度突然变了 / 顿一下」。
     */
    private static void retargetScrollForNewWidth(int newWidthPx) {
        if (sLineView == null) {
            return;
        }
        try {
            int target = computeScrollTarget(newWidthPx);
            if (Math.abs(target - sLiveTargetPx) < TARGET_HYSTERESIS_PX) {
                return;   // 差异太小：连记录都不改（迟滞治抖，别每帧都动）
            }
            int prev = sLiveTargetPx;
            sLiveTargetPx = target;
            sScrollTarget = target;
            int cur = sLineView.getScrollX();
            XposedCompat.log(TAG + " scroll retarget: " + prev + "->" + target + "px cur=" + cur
                    + " speed=" + (int) (sScrollSpeedPxPerMs * 1000f) + "px/s");
            if (!sScrollArmed) {
                return;   // 这一行还没起滚：postScrollWhenLaidOut -> startScroll 会用新宽度重算
            }
            if (target <= 0 || cur >= target) {
                // 【code 934 bug2 根修】变宽 -> 新目标比当前位置还小：旧逻辑 cancel 冻结，
                //   右侧留白最多达宽度差（实测 129px，23:00:22 日志 cur=517 target=388
                //   冻结）直到本条字幕结束 = 「字幕右端离流体云太远」。改为一次性回正
                //   到新目标（文字尾端重新贴齐右边界；目标 0 = 放得下，回行首正常显示）。
                cancelScroll();
                if (target >= 0 && cur - target > TARGET_HYSTERESIS_PX) {
                    sLineView.setScrollX(Math.max(target, 0));
                }
                return;
            }
            if (sAnimator == null) {
                continueScrollToLiveTarget();   // 动画已跑完或被停：按原速度续上（不重新定速）
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【1.21.11 问题 1】按**已定死的原速度**继续滚到当前动态目标。
     *
     * 只做「剩余距离 ÷ 已有速度 = 剩余时间」这一步。速度在这里是**只读**的：
     * 绝不从「剩余时间 / 全程距离」反推（1.21.9 就是这么把速度逐次放大的），
     * 也绝不因为「这行快没时间了」而提速。
     */
    private static void continueScrollToLiveTarget() {
        if (sLineView == null || sAnimator != null) {
            return;
        }
        int target = sLiveTargetPx;
        int cur = sLineView.getScrollX();
        if (target - cur <= 2) {
            return;
        }
        double speed = sScrollSpeedPxPerMs > 0
                ? sScrollSpeedPxPerMs
                : (SCROLL_FALLBACK_SPEED_PX_PER_S / 1000d);
        long remain = (long) ((target - cur) / speed);
        if (remain < 60L) {
            remain = 60L;
        }
        // 【code 934 bug1】上限钳制：速度异常时剩余滚动也绝不超过 12s（双保险）。
        if (remain > SCROLL_MAX_MS) {
            remain = SCROLL_MAX_MS;
        }
        XposedCompat.log(TAG + " scroll continue: " + cur + "->" + target + "px in " + remain
                + "ms speed=" + (int) (speed * 1000f) + "px/s");
        animateScrollTo(cur, target, remain);
    }

    private static void cancelScroll() {
        sScrollGen++;   // 【1.21.11】换代：正在跑的那段 onAnimationEnd 不该再续滚
        if (sAnimator != null) {
            try {
                sAnimator.cancel();
            } catch (Throwable ignored) {
            }
            sAnimator = null;
        }
    }

    private static int dp(float dp) {
        Context ctx = sRoot != null ? sRoot.getContext() : null;
        return dp(ctx, dp);
    }

    private static int dp(Context ctx, float dp) {
        if (ctx == null) {
            return (int) (dp * 3f + 0.5f);
        }
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
