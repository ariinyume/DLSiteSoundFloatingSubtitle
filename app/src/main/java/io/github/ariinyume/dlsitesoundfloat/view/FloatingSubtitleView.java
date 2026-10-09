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
package io.github.ariinyume.dlsitesoundfloat.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.ariinyume.dlsitesoundfloat.config.RemoteConfig;
import io.github.ariinyume.dlsitesoundfloat.data.SubtitleCue;
import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;

import java.util.List;

import io.github.ariinyume.dlsitesoundfloat.util.BackdropBaseline;
import io.github.ariinyume.dlsitesoundfloat.util.BackdropBlur;
import io.github.ariinyume.dlsitesoundfloat.util.HostBackdrop;
import io.github.ariinyume.dlsitesoundfloat.util.I18n;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 悬浮窗内的字幕视图。
 *
 * 重要：本类运行在被 hook 的目标进程（DLsiteSound）中，目标进程的 Resources
 * 并不包含本模块的编译资源，因此【禁止】使用 R.layout / R.id / R.drawable 等
 * 模块资源 ID（会触发 ResourceNotFound）。所有子 View 一律用代码构建。
 *
 * v5：只渲染当前 cue 前后各 WINDOW_RADIUS 条；内层 ScrollView 不拦截触摸；右下角缩放手柄。
 * v6：当前行缩放 1.3→1.1；窗口最小尺寸受当前行限制；当前行保持垂直居中。
 * v7：横向宽度不再受文字自然长度限制，当前高亮行强制换行 ≤3 行。
 * v8：关闭滚动条；右上角 ✕ 关闭按钮（点击面板切换显隐）。
 * v9：玻璃拟态质感 —— GlassPanelDrawable、圆角 20dp、文字投影、当前行加粗、
 *     setBlurBehindActive() 自适应通透度。
 *
 * v11：左右内边距 14 → 22dp；✕ 全链路诊断日志；✕ 30 → 32dp、边距 6 → 8dp。
 *
 * v12：关闭系统级 FLAG_BLUR_BEHIND —— 实测（ColorOS）它会把【整个屏幕】糊掉，而不是只模糊
 *   面板身后那一小块。改为提高玻璃底色不透明度（fillScale 1.9）来维持质感与字幕可读性。
 *
 * v13：
 *   1) 面板去掉【顶部高光 / 外辉光 / 轮廓边缘光】三层装饰，只留半透明渐变底 —— 更干净。
 *   2) 左右内边距 22 → 16dp（{@link #PANEL_PAD_H_DP}），最小宽度随之自动重算。
 *   3) 当前字幕行缩放 1.1 → 1.0 倍（{@link #CURRENT_SCALE}），仍靠"加粗 + 全白"区分。
 *   4) 右下角缩放手柄 "◢" 字符 → 自绘 {@link GripIndicatorView}（三个角全部倒圆）。
 *
 * v14：两个小按钮的"体量与位置"重排
 *   1) **根视图不再设内边距** —— 原先 padding 加在 FrameLayout 自己身上，导致所有子 View
 *      （含 ✕ 与缩放手柄）都被向内推 16/13dp，"贴不到窗口边缘"。
 *      现在内边距下移到 {@link #container}（内容层），装饰性按钮得以贴角。
 *   2) 缩放手柄：填充改「灰 + 40% 不透明」（见 {@link GripIndicatorView}），
 *      距窗口右下角 3dp。
 *   3) ✕ 按钮：TextView 字符 → 自绘 {@link CloseButtonView}（灰底 + 镂空透明 ✕），
 *      直径 32 → 18dp，距窗口上/右边缘各 3dp，与手柄成同一套视觉语言。
 *
 * v15：两个装饰件的体量与边距再放大
 *   1) ✕ 按钮 18 → **40dp**（{@link #CLOSE_BTN_DP}）—— 显示圆盘与**点击区域同时放大**，
 *      解决"按钮太小、手指点不中"的问题；
 *   2) ✕ 外边距 3 → **10dp**（{@link #CLOSE_BTN_MARGIN_DP}）；
 *   3) 缩放手柄外边距 3 → **10dp**（{@link GripIndicatorView#INSET_DP}），与 ✕ 对齐。
 *
 * v16：缩放热区收窄并与三角形对齐
 *   1) 热区边长 44 → **30dp**（{@link #GRIP_HIT_DP}）；
 *   2) 热区不再"贴窗口右下角"，而是**以手柄三角形为中心**（{@link #hitResizeArea}）——
 *      原先落在窗口角落 44dp 见方内就判定为缩放，会吃掉面板右下角一大片可点击区；
 *   3) 手柄视图容器尺寸常量由 GRIP_SIZE_DP 更名为 {@link #GRIP_VIEW_DP}（值仍 44dp，仅作容器）。
 *
 * v17：**彻底关掉滚动条**。原先调了 setScrollbarFadingEnabled(false)，其副作用是把
 *   ScrollabilityCache 状态置为 ON（常显），于是每次 scrollTo（典型场景：拖右下角手柄
 *   缩放时 recenterCurrent 让当前行重新居中）都会让滚动条闪现一下。
 *   现在：不调 setScrollbarFadingEnabled(false)、setScrollBarSize(0)，
 *   并覆写 awakenScrollBars() 返回 false —— 滚动条状态永远停在 OFF。
 *
 * v18：✕ 按钮收到 30dp。显示圆盘与点击区域同步缩小（{@link #CLOSE_BTN_DP} 40 → 30），
 *   距窗口上 / 右边缘仍为 {@link #CLOSE_BTN_MARGIN_DP}（10dp）不变。
 *
 * v20：本轮四条观感/手感优化（Ari 提出）
 *   1) 【窗口纵向上限】最长不得超过屏幕高度的一半 —— 实现在
 *      {@code FloatingWindowManager.GestureListener} 的缩放 clamp（本类只配合）。
 *   2) 【当前行恒定居中】此前当前行是首行 / 尾行时会贴顶 / 贴底：因为 ScrollView 的滚动量
 *      被夹在 [0, 内容高-视口高] 内，没有多余空间把首尾行推到中间。
 *      现在给 {@link #container} 上下各留 **半个视口高度**的空白内边距
 *      （{@link #applyCenterPadding()}，随窗口尺寸自动重算），首行/尾行同样能滚到正中。
 *   3) 【最小留白 15dp】{@link #PANEL_PAD_H_DP} 16 → 13dp、{@link #PANEL_PAD_V_DP} 13 → 10dp，
 *      叠加每行文字自身的 2dp / 5dp 内边距后，窗口缩到最小时字幕四周**恰好**留 15dp；
 *      {@link #MIN_TEXT_PAD_DP}(15dp) 只是兜底下限，且作用在"有效留白"上（不叠加）。
 *   4) 【平滑上滚】换行时不再瞬间跳位，改用 {@link ValueAnimator} 在
 *      {@link #SCROLL_ANIM_MS} 毫秒内减速滚动到新位置（视觉上字幕向上滚动）。
 *      仅在"播放推进导致当前行变化"时动画；窗口缩放 / 首次定位仍为立即跳转。
 *      ⚠️ v20 的实现有一个致命前提错误，见 v40。
 *
 * v40：修「上滚动效有时不生效、下一句直接出现」。
 *   v20 把动画写成「旧滚动量 → 新滚动量」，但内容是**以当前句为中心重建**的：
 *   y = 内边距 + 前面若干行高度和 + 当前行高/2，而"前面若干行"永远是从窗口左边
 *   数到当前句 —— 相邻两句行数相同时 y 一模一样，差值恒为 0 → 动画等于没跑。
 *   （行数不同时差值是"半个行高"，所以用户才会说"有时候生效"。）
 *   现在滚动量一次写死、动画只走「上一句块中心 → 这一句块中心」的几何距离
 *   （写在 container 的 translationY 上），并且这一整套在**绘制前**（pre-draw）
 *   装好，保证第一帧的起点与上一帧画面严丝合缝。
 *
 * v41（code 944）：✕ 的生命周期由「点一下显示 / 再点一下隐藏」改成
 *   「点一下显示 + {@link #CLOSE_BTN_AUTO_HIDE_MS} 内没人点就自动隐藏」
 *   （Ari 需求原文：点击悬浮窗内出现 ✕ 关闭按钮，5s 内没点关闭则 ✕ 消失）。
 *
 * v42（code 945）：✕ 的显隐恢复成「开关」，并与自动隐藏**并存** —— 点一下出现；
 *   在自动隐藏到期之前**再点一次面板空白处**（✕ 自身以外的区域）即刻收回；
 *   两次点击之间一直没人动，则照旧 {@link #CLOSE_BTN_AUTO_HIDE_MS} 后自动收回。
 *   （Ari 需求原文：再点按钮外的其他悬浮窗区域则 ✕ 消失，与 5s 后消失并存。）
 */
public class FloatingSubtitleView extends FrameLayout {
    private static final String TAG = "[DLsiteSoundFloat:View]";

    /** 缩放手柄视图的布局尺寸（dp）—— 仅作容器，图形按 {@link GripIndicatorView} 的参数绘制。 */
    private static final int GRIP_VIEW_DP = 44;
    /**
     * 右下角缩放热区边长（dp）。命中判定见 {@link #hitResizeArea(float, float)}：
     * 热区是**以三角形为中心**、边长为本值的正方形（不再贴窗口右下角）。
     * v16：44 → 30dp（原先 44dp 见方既过大，又与三角形错位）。
     */
    public static final int GRIP_HIT_DP = 30;
    /** 当前行前后各渲染多少条 cue。 */
    private static final int WINDOW_RADIUS = 8;
    /** 当前字幕行的基准字号（sp），放大倍率作用于此。 */
    private static final float BASE_TEXT_SP = 17f;
    /** 实时播放中的字幕行缩放倍率（v13：1.1 → 1.0，与上下文行同字号）。 */
    private static final float CURRENT_SCALE = 1.0f;
    /** 当前高亮行允许的最大换行行数。 */
    private static final int MAX_LINES = 3;

    // —— 玻璃拟态外观参数 ——
    /** 面板圆角（dp）。 */
    private static final int CORNER_RADIUS_DP = 20;
    /**
     * 【2.2.10】**液态玻璃**专用面板圆角（dp）。
     *
     * 为什么单独给一个值：ColorOS17 的玻璃弹窗实测圆角 ≈110px（密度 3.0 ⇒ ≈36dp，
     * 弹窗宽 1124px ≈ 375dp，圆角/宽 ≈ 0.098）；按我们面板宽（1052px）比例折算约 34dp。
     * 取 **28dp** 作为折中 —— 明显比原来的 20dp 更"软"、更接近参考，又不会圆到像气泡
     * 而与宿主 App 自己的卡片语言打架。
     *
     * ⚠️ 只作用于液态玻璃档：关掉开关后仍走 {@link #CORNER_RADIUS_DP}=20dp 的老观感，
     *    不改动任何存量用户的既有外观。
     */
    private static final int LIQUID_GLASS_CORNER_RADIUS_DP = 28;
    /**
     * 【987 / 2.2.14b】玻璃四周"接触阴影环"宽度（dp）——窗口每边比玻璃大出的那一圈。
     *
     * ── 为什么回到 0（本常量曾经是 12dp）────────────────────────────────
     * 2.2.13 为了让面板"看得出来是浮在页面上"，把**窗口**做大一圈、玻璃内缩，
     * 多出来的一圈由 {@code LiquidGlassDrawable#drawOuterShadow} 画柔和接触阴影
     * （滚动调参：2.2.14 把圈宽收到 ≈圈距、峰值降到 16%）。但这一圈在真机上
     * **无论怎么调都躲不开三个副作用**（Ari 2026-10-09 真机复测，第三次点名）：
     *   ① 环是**透明的**（要透出实时背景），可它**不在背景模糊区域内** ——
     *      于是环里是"没糊过的原始背景"，玻璃里是"糊过的背景"，
     *      两者亮度/锐度不一样，肉眼看到的就是「中心一块长方形比四周更透」，
     *      像是"在原来的悬浮窗外又裹了一层"；
     *   ② ✕ 与缩放手柄的 10dp 外边距是相对**玻璃**算的，而玻璃又比窗口小 12dp
     *      ⇒ 它们离**窗口边缘**实际有 22dp，看起来不再贴边（v15/v18 的既定观感被破坏）；
     *   ③ 缩放热区、尺寸下限、模糊区域、统计采样矩形全都要跟着这圈做换算，
     *      每加一处就多一处会算错的地方。
     *
     * 结论：**这一圈整体删掉**（本常量归 0），窗口即玻璃 —— 回到 2.2.12 及更早版本的几何：
     *   · 玻璃 Drawable 与窗口完全重合（{@code shadowInsetPx == 0} ⇒ 不画外阴影）；
     *   · 根视图不再补 padding，✕ / 缩放手柄回到"距窗口边缘 10dp"；
     *   · 模糊区域与统计采样矩形就是整个窗口，不再有偏移；
     *   · 默认尺寸 / 最小尺寸 / 缩放热区全部回到未加环宽的旧算式。
     * ⚠️ {@code LiquidGlassDrawable#drawOuterShadow} 与 12dp 这套实现**原样保留**在
     *    Drawable 里（由 {@code shadowInsetPx > 0} 守护），以后若真要重做悬浮感，
     *    只需把本常量改回非零 —— 但那时必须先把上面①的"模糊区域也得跟着走"一并解决。
     */
    private static final int GLASS_SHADOW_INSET_DP = 0;
    /**
     * 【2.2.12】「模糊强度」100% 对应的**背景模糊半径**（dp）。
     *
     * 语义：0% → 完全不糊（背后画面清晰透出）；100% → 24dp（很强，只剩色块感）。
     * 由 {@link BackdropBlur} 直接设给图层，不经过本类的绘制。
     */
    private static final float BLUR_MAX_DP = 24f;
    /**
     * 面板左右内边距（dp）。想再宽/再窄改这一个数就行（最小宽度会自动跟着重算）。
     * v20：16 → 13dp —— 叠加每行文字自身的 {@link #LINE_PAD_H_DP}(2dp) 后，
     * 窗口缩到最小时字幕左右**恰好**留 15dp。
     */
    private static final int PANEL_PAD_H_DP = 13;
    /**
     * 面板上下内边距（dp）。
     * v20：13 → 10dp —— 叠加每行文字自身的 {@link #LINE_PAD_V_DP}(5dp) 后，
     * 窗口缩到最小时字幕上下**恰好**留 15dp。
     * （v20 中途曾改成 15dp，Ari 要求与左右统一成"有效留白 15dp"，故再下调。）
     */
    private static final int PANEL_PAD_V_DP = 10;
    /** 每行字幕 TextView 自身的左右内边距（dp）—— 与 {@link #PANEL_PAD_H_DP} 相加 = 实际留白。 */
    private static final int LINE_PAD_H_DP = 2;
    /** 每行字幕 TextView 自身的上下内边距（dp）—— 与 {@link #PANEL_PAD_V_DP} 相加 = 实际留白。 */
    private static final int LINE_PAD_V_DP = 5;
    /**
     * v20：当前字幕行与窗口边缘的**最小留白**（dp）。
     *
     * ⚠️ 比较的是「面板内边距 + 文字自身内边距」这个**有效值**，不是单独的面板内边距 ——
     * 否则会算成 {@code max(13, 15) + 2 = 17dp}，比要求的 15dp 更宽。
     * 正常配置下（横向 13+2、纵向 10+5）有效值正好 15dp；本常量只是
     * "以后有人把 PAD 调得更小"时的兜底下限。
     */
    private static final int MIN_TEXT_PAD_DP = 15;
    /** v20：字幕换行时的平滑滚动时长（ms）。 */
    private static final int SCROLL_ANIM_MS = 360;
    /** 是否使用浅色（奶白）玻璃。想换风格把这里改成 true 即可。 */
    private static final boolean LIGHT_GLASS = false;
    /** 字幕投影：让文字在模糊/明亮背景上仍清晰。 */
    private static final float TEXT_SHADOW_RADIUS = 7f;
    private static final int TEXT_SHADOW_COLOR = 0xCC000000;
    /** 右上角 ✕ 按钮尺寸（dp）：**显示圆盘与点击区域同为该值**。v15：18 → 40dp；v18：→ 30dp。 */
    private static final int CLOSE_BTN_DP = 30;
    /** ✕ 按钮距窗口上/右边缘的距离（dp）。v15：3 → 10dp。 */
    private static final int CLOSE_BTN_MARGIN_DP = 10;
    /**
     * 【code 944】✕ 的自动隐藏延时（ms）；【code 945】起与「再点一次即收回」并存。
     *
     * 需求（Ari，2026-09-23）：点一下悬浮窗面板 → ✕ 出现；**5s 内没点它就一直藏回去**。
     * 【code 945 补】再点一次面板上 ✕ 之外的区域 → **立刻**收回（见 onPanelTapped）。
     * 即「有第二下点击就马上收，没有就等满 5 秒」，两条收法互不打架，
     * 所以 ✕ 不会长期占着面板右上角。
     */
    private static final long CLOSE_BTN_AUTO_HIDE_MS = 5000L;

    private NonInterceptScrollView scrollView;
    private LinearLayout container;
    private TextView hint;
    private CloseButtonView closeBtn;
    /** 【2.3.1 §6.1.1】右下角缩放手柄 —— 需要按面板底色切换灰/白，故留一个字段引用。 */
    private GripIndicatorView gripView;
    /** 【code 944】✕ 自动隐藏用的主线程 Handler（视图随窗口重建，Handler 也跟着废弃）。 */
    private final Handler closeBtnHandler = new Handler(Looper.getMainLooper());
    /** 【code 944】自动隐藏任务 —— 到点把 ✕ 收回 GONE（已隐藏则不重复打日志）。 */
    private final Runnable closeBtnHideTask = new Runnable() {
        @Override
        public void run() {
            if (closeBtn == null || closeBtn.getVisibility() != VISIBLE) {
                return;
            }
            closeBtn.setVisibility(GONE);
            LogGate.debug(TAG, " close button auto-hidden after "
                    + CLOSE_BTN_AUTO_HIDE_MS + "ms");
        }
    };

    // 渲染缓存：内容没变就跳过重建
    private String lastRenderKey = null;
    private boolean lastHintVisible = false;

    // 当前字幕行在 container 中的局部索引（用于居中滚动）
    private int currentLocalIndex = -1;

    /**
     * v20：上一次已经"居中过"的**全局** cue 索引。
     * 用于区分「播放推进导致当前行换了」（→ 平滑上滚）与「首次定位 / 缩放后重排」（→ 立即跳转）。
     */
    private int lastCenteredCueIndex = -1;

    /** v20：平滑滚动动画（同一时刻只允许一个，新的会取消旧的）。 */
    private ValueAnimator scrollAnim;

    /** 【2.1.5】onMeasure 预置内边距是否可信（预置值与真实布局值不符时永久停用，见 applyCenterPadding）。 */
    private boolean predictPadOk = true;
    /** 【2.1.5】上一次被 onMeasure 预置过的「可用高」（用于发现预置值与真实布局值不一致）。 */
    private int predictedVp = -1;
    /** 【2.1.6】本次尺寸变化还欠一次「同帧重新居中」：onViewportSizeChanged 置起、preDraw 消费。 */
    private boolean pendingResizeRecenter = false;
    /** 【2.1.6】兜底：万一这一帧没走到 preDraw，下一帧补一次；已被 preDraw 消费则空转。 */
    private final Runnable mResizeFallback = this::recenterForResizeSameFrame;

    /**
     * v40：{@link #renderCues} 本次渲染时，每个 cue 在 container 里占据的
     * 子 View 下标区间（首/末）。用于算「上一句 → 这一句」的几何位移距离。
     */
    private int cueBlockFrom = -1;
    private int[] cueBlockStart;
    private int[] cueBlockEnd;

    /** v40：待在「布局完成后、绘制前」执行的居中滚动；{@code pendingTravelLocal < 0} 表示无。 */
    private int pendingTravelLocal = -1;
    /** v40：本次位移动画的起点句（上一句的全局 cue 下标），-1 = 无（退化为原来的瞬时定位）。 */
    private int pendingTravelFromCue = -1;

    // 当前字幕行完整显示所需的最小尺寸（像素）。0 表示尚未测量 / 无当前行。
    private int minWidthPx = 0;
    private int minHeightPx = 0;

    // 系统级跨窗模糊是否生效（影响玻璃的可透度）
    private boolean blurActive = false;

    /**
     * 【M1】由标准配置换算出来的绘制参数（PRD §十 / §14.1 RK-03）。
     *
     * ⚠️ 换算只走 {@link SubtitleStyle#of}，本类**不再持有一份自己的外观常量默认值**：
     *    所有外观参数（颜色 / 字号 / 字重 / 对齐 / 阴影 / 模糊 / 行距）唯一真源是配置文件。
     *    M2 的预览区会用同一个映射函数渲染，两边不可能求出两份结果。
     */
    private volatile SubtitleStyle style = null;

    // ── 【2.2.12】面板背后的「真实模糊」与「背景统计」──
    /** 面板矩形提供者（由窗口层注入；本类不反向依赖 window 包）。 */
    private HostBackdrop.Target backdropTarget;
    /** 背景**统计**结果（均值亮度 + 上下缘颜色）落到当前 LiquidGlassDrawable 上。 */
    private final HostBackdrop.Listener backdropListener = this::onBackdropStats;
    /**
     * 【2.2.13 / 2.2.14 / 987 / 988】背景均值亮度的**过渡缓冲**（喂给自适应霜面的那个值）。
     *
     * 修「宿主内切页时悬浮窗白一下、然后又模糊回去」：页面转场的一两帧 PixelCopy 会采到
     * 过渡画面（半截旧页 / 空窗 / 全黑），霜面按它算 ⇒ 面板一瞬被拉成大 alpha 的霜再弹回。
     *
     * ── 前三版为什么都没修住，988 又改了什么 ─────────────────────────────
     * · 13 版 = EMA + 单拍最多挪 0.30：对**大台阶**天生无能为力 —— 亮页面（0.9x）瞬态掉到
     *   0.6 时深色霜量从 0.64 直掉到 0.14，挪 0.30 一下就足够把面板洗白约一半。
     * · 14 版 = 候选确认：方向对了，但**锚点是活的**（每命中一次就把候选值改写成最新样本），
     *   于是"连续三拍都指向同一个新值"退化成"连续三拍**每拍只挪一点点**"——
     *   转场里背景亮度本来就是**斜坡下滑**（0.90 → 0.76 → 0.62 → 0.50），
     *   每相邻两拍的差正好 ≤ 旧容差 0.15 ⇒ 三拍接力"命中" ⇒ 采信 ⇒ 面板白闪。
     * · 987 = 锚点固定 + 未确认期间仍允许每拍挪 0.02。方向对，但**噪声照样能推进面板**：
     *   真机日志（`LSPosed_20261009_101421`，10:12:55 ~ 10:13:15）实测宿主页面的背景亮度
     *   在 **0.385 ↔ 0.432 之间长期来回抖**（相邻两拍的差 0.047），每次都算"换了候选"，
     *   于是面板被这些**来回抖**推着一起抖 —— 这正是 Ari 复测的「**还是**会闪一点」。
     *
     * 988 的三条修正（{@link #smoothLum}）——把"跟得动真变化"与"跟不动噪声"彻底分开：
     *   ① **同档只缓挪**：与当前值相差 ≤ {@link #LUM_STABLE_TOL} 时**不再直接采纳**
     *      （987 是 `smoothedLum = v`，来回抖的样本因此一比一被抄进面板），
     *      改为每拍最多挪 {@link #LUM_TRACK_STEP} —— 真缓变跟得上，来回抖被平均掉；
     *   ② **越档先进观察期，期间一步不动**：与当前值相差超过容差时先当候选，锚点固定，
     *      观察期内**完全不改画面**（987 的 0.02/拍 在这段仍然会动）⇒ 任何一两拍的
     *      转场瞬态都进不了面板；
     *   ③ **两条出观察期的路**：要么连续 {@link #LUM_CONFIRM_HITS} 拍命中同一锚点
     *      （真·换了一整页，按 {@link #LUM_CONFIRM_STEP}/拍平滑走完），要么候选持续超过
     *      {@link #LUM_CONFIRM_MS} 仍越档（背景真的换了但样本本身很吵，例如视频画面），
     *      这时才按 {@link #LUM_SLOW_ALPHA} 做**指数平均**很慢地跟过去 —— 既不会永久冻住，
     *      也绝不可能闪。
     */
    private float smoothedLum = -1f;
    /**
     * 候选**锚点**亮度（-1 = 当前没有候选）。
     *
     * ⚠️【987 关键修正】它一经写入就**不再随后续样本漂移**（旧版每命中一次就把锚点
     *    改写成最新样本 ⇒ 只要每拍挪一点点就能"接力"命中，见 {@link #smoothLum}）。
     */
    private float pendingLum = -1f;
    /** 候选锚点已被连续命中的拍数。 */
    private int pendingHits;
    /**
     * 【988】候选**首次**越档的时刻（一次候选只记一次，重锚点不重置它）。
     *
     * 它撑起第 ③ 条里的第二条出路：候选迟迟攒不满三拍命中（画面本身在动、样本很吵）时，
     * 只要**持续越档**超过 {@link #LUM_CONFIRM_MS}，就按 {@link #LUM_SLOW_ALPHA} 慢慢跟过去。
     */
    private long pendingSinceMs;
    /** 【988】候选已进入"很慢地跟"这条慢速通道（越档超时后置起，回到同档即清）。 */
    private boolean pendingSlow;
    /** 候选日志的节流时刻（转场来回抖时每拍都会换锚点，不节流会刷屏）。 */
    private long lastLumLogMs;
    /**
     * 【988】背景统计的**上一次有效值**（喂给玻璃的那个亮度 + 上下缘颜色）。
     *
     * 用途：普通悬浮窗 → 液态玻璃切换时，旧底不是玻璃（没有可继承的运行时状态），
     * 新玻璃本来会退回**不透明深色底**；而此刻用户多半正停在设置页里（宿主在后台），
     * 采样拿不到新值 —— 面板就会一直黑着，直到用户切回宿主、甚至要拖一下才有统计。
     * 有了这一份缓存，换后端时新玻璃第一帧就画成自适应玻璃，不会再"先黑一下"。
     */
    private boolean hasLastStats = false;
    /** 上一次有效统计的上缘平均色（0 = 未知）。 */
    private int lastEdgeTop = 0;
    /** 上一次有效统计的下缘平均色（0 = 未知）。 */
    private int lastEdgeBottom = 0;
    /** 「同一个背景档」的判据带：与当前值相差在此以内即视为**同一档**（来回抖被这条吸收）。 */
    private static final float LUM_STABLE_TOL = 0.05f;
    /** **同档**内每拍允许的最大位移（跟得住真缓变、跟不动来回抖）。 */
    private static final float LUM_TRACK_STEP = 0.008f;
    /** 候选锚点必须连续命中的拍数（一拍 400ms ⇒ 约 1.2s）。 */
    private static final int LUM_CONFIRM_HITS = 3;
    /**
     * 候选**持续越档**多久之后走慢速通道（ms）。
     *
     * 为什么要这条：背景真的换了、但新画面的亮度本身很吵（样本每拍都跳），三拍命中永远
     * 攒不满 —— 没有这条面板就会**永久冻在旧值**上。设得比一条页面转场（约 1~2s）略长，
     * 保证转场整段都落在"一步不动"的观察期里。
     */
    private static final long LUM_CONFIRM_MS = 1600L;
    /**
     * **已确认**（三拍命中）后每拍允许的最大位移（分几拍平滑走完，不是硬跳）。
     *
     * 【989】0.16 → 0.06：989 起霜面改成"按背景亮度**反解**霜量"（见
     *   {@code LiquidGlassDrawable} 的 TARGET_LUM 段），面板亮度本身已经被钉住；
     *   这时还让整段 0.10 的跳变在一拍里走完，剩下的就只是**霜面密度**一步跳完
     *   （Ari 反馈的"还是会跳"）。收小步长后，切页时面板亮度几乎不动（中途最多偏
     *   约 4/255、持续 0.4s），霜面密度则在约 0.8s 内平滑换过去。
     */
    private static final float LUM_CONFIRM_STEP = 0.06f;
    /**
     * 【988】慢速通道的比例系数（背景很吵、三拍永远攒不满时走这条）。
     *
     * ⚠️ 这里刻意用**指数平均的比例步**而不是"每拍朝当前样本固定挪一段"：
     *    固定步长在**来回抖**的样本上会来回走（离线对照里它能被 0.658/0.476/0.570/0.424
     *    这样的序列一路推到 0.658）；而按比例走等于对这串样本做指数平均，
     *    来回抖的分量**正负相消**、只留下真实均值 —— 既绝不会闪，也不会跟着噪声漂。
     */
    private static final float LUM_SLOW_ALPHA = 0.06f;

    public FloatingSubtitleView(Context context) {
        super(context);
        init();
    }

    /**
     * 【2.2.11】注入「面板当前的屏幕矩形」提供者（窗口层调用）。
     *
     * 为什么用回调而不是让本类去问 {@code FloatingWindowManager}：本类**刻意不反向依赖
     * window 包**（window → view 是单向的），否则包间会绕成一圈。
     */
    public void setBackdropTarget(HostBackdrop.Target target) {
        this.backdropTarget = target;
    }

    /**
     * 【2.2.12】拿到一帧背景统计 → 交给当前玻璃 Drawable。
     *
     * 两个用途：① 均值亮度选「自适应霜面」（亮背景上深色霜，保证白字可读）；
     * ② 上下缘颜色给「边缘光圈」染色（光圈颜色随实时背景走）。
     * **不再贴任何背景位图** —— 背景模糊已由合成器完成（见 {@link BackdropBlur}）。
     */
    private void onBackdropStats(float meanLum, int edgeTop, int edgeBottom) {
        // 【988/989】**无论当前底是不是玻璃**都先并入过渡缓冲并留底：
        //   989 起"液态玻璃没开"时也在留底采样（见 {@link #syncBackdropCapture}），
        //   这份值就是"下次开启"的第一帧底 —— 少了它，切换那一刻只能画不透明深色底
        //   （Ari 看到的"先变成黑色"），且只能等用户切回宿主才慢慢恢复。
        final float lum = smoothLum(meanLum);
        hasLastStats = true;
        lastEdgeTop = edgeTop;
        lastEdgeBottom = edgeBottom;
        // 【991】顺手落盘留底（内部自己节流：数值没怎么变 + 距上次不到 10s 都不写）。
        //   目的只有一个：让"冷启动后第一次开液态玻璃"也有值可打底（见 util/BackdropBaseline）。
        BackdropBaseline.save(getContext(), lum, edgeTop, edgeBottom);
        Drawable bg = getBackground();
        if (bg instanceof LiquidGlassDrawable) {
            ((LiquidGlassDrawable) bg).setBackdropStats(lum, edgeTop, edgeBottom);
        }
    }

    /**
     * 【991】用**落盘留底**给新玻璃打底（本进程还没采到过任何统计时的最后一道底）。
     *
     * 命中后写入与 {@link #onBackdropStats} 相同的那些字段，于是"本进程已有一份留底"
     * 这件事会被后续重建复用；真实样本回来后由 {@link #smoothLum} 正常接手修正。
     *
     * @return true = 打底成功
     */
    private boolean seedFromPersistedBaseline(LiquidGlassDrawable glass) {
        if (glass == null) {
            return false;
        }
        final float[] lum = new float[1];
        final int[] edges = new int[2];
        if (!BackdropBaseline.load(getContext(), lum, edges)) {
            return false;
        }
        smoothedLum = lum[0];
        hasLastStats = true;
        lastEdgeTop = edges[0];
        lastEdgeBottom = edges[1];
        glass.setBackdropStats(smoothedLum, lastEdgeTop, lastEdgeBottom);
        LogGate.debug(TAG, " glass seeded from persisted baseline: lum=" + smoothedLum);
        return true;
    }

    /**
     * 【987 / 988】把一帧背景亮度并入过渡缓冲（设计缘由见 {@link #smoothedLum}）。
     * 只在拿到**真实新样本**时调用；首帧直接采信（此时还没有"旧值"可保护）。
     *
     * 规则（三条，缺一不可）：
     *   ① 与**当前绘制值**同档（|Δ| ≤ {@link #LUM_STABLE_TOL}）⇒ 每拍最多挪
     *      {@link #LUM_TRACK_STEP}（真缓变跟得上，来回抖被平均掉），并清候选；
     *   ② 否则进候选观察：锚点**固定**，**观察期内一步不动** —— 锚点不再滚、画面也不动，
     *      任何一两拍的转场瞬态都进不了面板；
     *   ③ 出观察期只有两条路：连续 {@link #LUM_CONFIRM_HITS} 拍命中同一锚点（真换了一整页，
     *      按 {@link #LUM_CONFIRM_STEP}/拍平滑走完），或候选持续越档超过
     *      {@link #LUM_CONFIRM_MS}（背景真换了但样本本身很吵，按 {@link #LUM_SLOW_ALPHA}
     *      做指数平均慢慢跟过去）。两条都走到同档即收工、清候选。
     *
     * @return 本次实际生效（喂给霜面）的亮度
     */
    private float smoothLum(float fresh) {
        final float v = Math.max(0f, Math.min(1f, fresh));
        if (smoothedLum < 0f) {
            smoothedLum = v;
            resetLumCandidate();
            return smoothedLum;
        }
        // ① 同档：真实背景在缓变（或只是噪声）⇒ 只跟一点点，并清掉可能残留的候选。
        //   ⚠️ 988 起这里**不再**直接 `smoothedLum = v` —— 那正是"来回抖被一比一抄进面板"
        //   的入口（真机日志实测背景亮度在 0.385↔0.432 之间长期来回抖）。
        if (Math.abs(v - smoothedLum) <= LUM_STABLE_TOL) {
            smoothedLum = Math.max(0f, Math.min(1f,
                    smoothedLum + clampStep(v - smoothedLum, LUM_TRACK_STEP)));
            resetLumCandidate();
            return smoothedLum;
        }
        // ② 明显不同：锚点固定不漂移；观察期内**一步不动**（pendingSlow 之前）。
        final long now = android.os.SystemClock.uptimeMillis();
        if (pendingLum < 0f) {
            pendingLum = v;
            pendingHits = 1;
            pendingSinceMs = now;
            pendingSlow = false;
        } else if (Math.abs(v - pendingLum) <= LUM_STABLE_TOL) {
            pendingHits++;
        } else {
            // 没有候选 / 新样本离锚点太远（斜坡、抖动都会走到这里）⇒ 重新起锚。
            //   ⚠️ 刻意**不重置** pendingSinceMs：候选"持续越档多久"是按第一次越档起算的。
            pendingLum = v;
            pendingHits = 1;
        }
        final boolean hitsConfirmed = pendingHits >= LUM_CONFIRM_HITS;
        if (!hitsConfirmed && !pendingSlow && now - pendingSinceMs >= LUM_CONFIRM_MS) {
            pendingSlow = true;
            logLum("backdrop lum persistent drift -> slow track from " + smoothedLum);
        }
        if (hitsConfirmed || pendingSlow) {
            final float before = smoothedLum;
            final float move = hitsConfirmed
                    ? clampStep(v - smoothedLum, LUM_CONFIRM_STEP)      // 真换了一整页：限速走完
                    : (v - smoothedLum) * LUM_SLOW_ALPHA;               // 很吵的背景：指数平均
            smoothedLum = Math.max(0f, Math.min(1f, smoothedLum + move));
            if (Math.abs(v - smoothedLum) <= LUM_STABLE_TOL) {
                resetLumCandidate();
                logLum("backdrop lum settled at " + smoothedLum + " (from " + before + ")");
            }
        } else if (pendingHits == 1) {
            // 起锚（候选）。转场来回抖时每拍都会起锚 ⇒ 必须节流，否则刷屏。
            logLum("backdrop lum candidate " + smoothedLum + " -> " + v
                    + " (need " + LUM_CONFIRM_HITS + " stable hits within "
                    + LUM_STABLE_TOL + ", observe " + LUM_CONFIRM_MS + "ms)");
        }
        return smoothedLum;
    }

    /** 【988】清掉亮度候选（回到"同档"或已到位时调用）。 */
    private void resetLumCandidate() {
        pendingLum = -1f;
        pendingHits = 0;
        pendingSinceMs = 0L;
        pendingSlow = false;
    }

    /** 把一步位移夹到 ±max（0 ≤ max）。 */
    private static float clampStep(float delta, float max) {
        if (delta > max) {
            return max;
        }
        if (delta < -max) {
            return -max;
        }
        return delta;
    }

    /** 候选/确认两条日志的公共节流口（2s 一条；这条链路只在切页时会活跃）。 */
    private void logLum(String msg) {
        final long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLumLogMs < 2000L) {
            return;
        }
        lastLumLogMs = now;
        LogGate.debug(TAG, msg);
    }

    /**
     * 按「液态玻璃是否开启 + 视图是否在窗口上 + 有没有矩形来源」三件事，对齐统计采样开关。
     * 幂等，随便多调。
     */
    private void syncBackdropCapture() {
        final SubtitleStyle st = style();
        // 【989】采样不再与"液态玻璃开没开"绑定（Ari 2026-10-09 提议）：
        //   开着 → 正常档（400ms），驱动自适应霜面与光圈染色；
        //   没开 → **留底档**（2000ms），唯一目的是给"下次开启"留一份可直接打底的基线
        //          —— 因为用户点保存那一刻宿主在后台、采不到画面，基线只能在"宿主可见"时攒。
        //   两种情况都要求"视图挂在窗口上 + 有矩形来源"；关掉悬浮窗即刻 stop，不留常驻。
        boolean want = isAttachedToWindow() && backdropTarget != null;
        if (want) {
            HostBackdrop.get().start(backdropTarget, backdropListener);
            HostBackdrop.get().setPassive(!st.liquidGlass);
        } else {
            HostBackdrop.get().stop();
        }
    }

    /**
     * 【2.2.12】把「面板背后」的模糊交给**合成器**（{@link BackdropBlur}）。
     *
     * 半径跟着「模糊强度」滑条走：0% → 不糊，100% → {@link #BLUR_MAX_DP}dp。
     * 圆角一并下发 —— 这样模糊区域是**圆角**的，不会在面板四角露出四个直角。
     *
     * ⚠️ 三种情况必须重设（模糊区域是按图层尺寸/圆角记的）：
     *   ① 首次布局完成；② 缩放窗口（{@code onSizeChanged}）；③ 改模糊强度/开关玻璃。
     * 关掉液态玻璃时也要下发一次**半径 0**，把之前设上的模糊撤掉。
     *
     * ⚠️⚠️ 【2.2.12b 重要修正】判断依据从「样式里 liquidGlass 是不是 true」改成
     *   「**当前背景 Drawable 到底是不是液态玻璃**」（{@link #getBackground()}）。
     *
     * 为什么必须改：真机日志抓到过「样式说液态玻璃开着、这里却按关掉下发半径 0」的错位 ——
     * 于是模糊被撤掉、Drawable 又被告知"系统模糊不可用"从而退回**不透明深色底**，
     * 用户看到的就是「保存后浮窗变黑，拖动一下才恢复」。
     * 而"当前底是什么"是**已经画出来的事实**，不可能与画面对不上：
     *   底是玻璃 → 必有模糊；底是普通玻璃/其它 → 必无模糊。两者从此不可能互相矛盾。
     */
    private void applyBackdropBlur() {
        // 【2.2.13 / 987】模糊区域 = **玻璃矩形**（窗口内缩阴影环）。
        //   ⚠️ 987 起阴影环宽度为 0（窗口即玻璃，见 GLASS_SHADOW_INSET_DP），
        //   所以这里等价于"整个窗口"；保留 inset 参与运算是为了让"改回非零"时仍然自洽。
        final int inset = getFrameInsetPx();
        final int w = getWidth() - 2 * inset;
        final int h = getHeight() - 2 * inset;
        if (w <= 0 || h <= 0) {
            return;                      // 还没布局；onSizeChanged 会补
        }
        final Drawable bg = getBackground();
        if (!(bg instanceof LiquidGlassDrawable)) {
            BackdropBlur.apply(this, inset, inset, w, h, 0, 0);   // 撤掉模糊
            applySystemBlurState(false);
            return;
        }
        // 半径由**这个 Drawable 自己**回答（它记着创建时的模糊强度），不再走 style()
        final LiquidGlassDrawable glass = (LiquidGlassDrawable) bg;
        final boolean ok = BackdropBlur.apply(this, inset, inset, w, h,
                dp(glass.blurPct() / 100f * BLUR_MAX_DP),
                dp(LIQUID_GLASS_CORNER_RADIUS_DP));
        applySystemBlurState(ok);
    }

    /**
     * 【2.2.13】玻璃四周阴影环宽度（px）—— 窗口与玻璃的每边差，窗口层也要用。
     * 【987】恒为 0（窗口即玻璃，见 {@link #GLASS_SHADOW_INSET_DP}）。
     */
    public int getFrameInsetPx() {
        return dp(GLASS_SHADOW_INSET_DP);
    }

    /**
     * 【2.2.12b】自愈：当前样式若与最新配置不一致，按配置重算一次。
     *
     * 背景：真机日志里出现过「保存后浮窗短暂变成普通玻璃档（深色底 + 无模糊）」，
     * 而同一条日志后面又出现 rev 更高的样式刷新 —— 说明**样式有一段时间是旧的**。
     * 这里不追究它当时是怎么旧的（那是配置链的问题，另有日志盯着），
     * 只在"视图尺寸变化 / 重新挂到窗口"这两个必然发生、且代价极低的时机做一次对账：
     * 只要样式里的液态玻璃开关与配置不一致，就立刻重算（重算会重建底 + 重下发模糊）。
     */
    private void refreshStyleIfStale() {
        try {
            final SubtitleStyle cur = style;
            if (cur == null) {
                return;                  // 还没算过样式 ⇒ 交给正常的首次刷新
            }
            // 【2.2.13】判据从"只有液态玻璃开关"扩到"玻璃观感的全部三把键"：
            //   模糊强度 / 面板色同样会让画面错位，而它们**不在** ConfigBus 的即时重画
            //   判据之外也要能自愈 —— 后台进程漏掉广播时，靠本方法在必然发生的
            //   尺寸变化 / 重新挂窗口时机把样式拉回与配置一致。
            if (RemoteConfig.get().liquidGlass != cur.liquidGlass
                    || RemoteConfig.get().liquidGlassBlurPct != cur.liquidGlassBlurPct
                    || (RemoteConfig.get().floatWindowColor & 0x00FFFFFF)
                    != (cur.panelColorTop & 0x00FFFFFF)) {
                LogGate.debug(TAG, " style stale (view=" + cur.liquidGlass
                        + "/" + cur.liquidGlassBlurPct
                        + " config=" + RemoteConfig.get().liquidGlass
                        + "/" + RemoteConfig.get().liquidGlassBlurPct + ") -> refreshStyle");
                refreshStyle();
            }
        } catch (Throwable t) {
            // 自愈失败不是致命问题（下次尺寸变化还会再试），绝不往上抛
        }
    }

    /** 把「系统模糊是否生效」同步给背景 Drawable —— 它据此在「透」与「自己给底色」之间选策略。 */
    private void applySystemBlurState(boolean active) {
        Drawable bg = getBackground();
        if (bg instanceof LiquidGlassDrawable) {
            ((LiquidGlassDrawable) bg).setSystemBlurActive(active);
        }
    }

    /**
     * 【2.2.11b】由窗口层在拖动/缩放期间调用，暂停背景采集。
     *
     * 目的：手指按住时用户要的是**跟手**，而背后画面本来就在位移（采了也立刻过期）。
     * 松手后 {@code false} 会立即补一帧，背景不会长时间缺。
     */
    public void setBackdropPaused(boolean paused) {
        HostBackdrop.get().setPaused(paused);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshStyleIfStale();
        syncBackdropCapture();
        applyBackdropBlur();
    }

    /**
     * 🔬 诊断专用：去让**合成器**把本图层背后的内容糊掉（见 {@code BlurDiag.applyLayerBackdropBlur}）。
     *
     * 半径跟着「模糊强度」走：0% → 不糊，100% → 24dp。布局还没完成时先跳过，
     * {@link #onSizeChanged} 会再补一次（那时候才有真实宽高）。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 【2.2.12b】先对账样式（万一它落后于配置），再下发模糊 —— 顺序不能反：
        //   样式重建会换掉背景 Drawable，而模糊的判据正是"当前背景是不是玻璃"。
        refreshStyleIfStale();
        // 【2.2.12】尺寸（= 模糊区域）与圆角都变了 ⇒ 必须重设一次，否则糊的还是旧位置。
        applyBackdropBlur();
    }

    /** 当前绘制参数（恒非 null；未初始化时按已保存配置现算一次）。 */
    private SubtitleStyle style() {
        SubtitleStyle s = style;
        if (s == null) {
            s = SubtitleStyle.of(RemoteConfig.get(),
                    getContext().getResources().getDisplayMetrics());
            style = s;
        }
        return s;
    }

    /**
     * 【M1】重新读取标准配置并换算绘制参数。
     *
     * 调用时机：视图构建时 + 每次渲染前（{@link #updateFromRepository}）。
     * 成本极低 —— {@link RemoteConfig#get()} 只做一次 volatile 读，配置变了才会重算
     * （设置页保存后广播 {@code ACTION_CONFIG_CHANGED} → hook 侧 {@code RemoteConfig.reload()}）。
     */
    private void refreshStyle() {
        SubtitleStyle s = SubtitleStyle.of(RemoteConfig.get(),
                getContext().getResources().getDisplayMetrics());
        SubtitleStyle old = style;
        style = s;
        if (old == null || !old.sameAs(s)) {
            // 【2.3.0】面板底色也来自配置：色变了就重建背景（init 里紧接着那次
            // applyPanelBackground 已经按新样式建过，这里只在**运行中改色**时补一次）。
            // 【2.2.9】液态玻璃开关同样要重建背景（它换的是**绘制后端**，不只是颜色）。
            if (old != null && (old.panelColorTop != s.panelColorTop
                    || old.panelColorBottom != s.panelColorBottom
                    || old.liquidGlass != s.liquidGlass
                    || old.liquidGlassBlurPct != s.liquidGlassBlurPct)) {
                applyPanelBackground();
            }
            LogGate.debug(TAG, " style refreshed (config rev=" + RemoteConfig.revision()
                    + ", source=" + RemoteConfig.source() + ") " + s.summary());
            // 【2.3.0】立刻重画：改完配置后可能长时间没有新的字幕行（没在播 / 字幕没换行），
            // 少了这一下新样式要等到下一句字幕才现身（用例 2.1 补充意见）。
            invalidate();
            requestLayout();
        }
    }

    public int getMinWidthPx() {
        return minWidthPx;
    }

    public int getMinHeightPx() {
        return minHeightPx;
    }

    /** 由窗口层告知系统跨窗模糊是否真正生效，用于自适应玻璃通透度。 */
    public void setBlurBehindActive(boolean active) {
        if (this.blurActive == active) {
            return;
        }
        this.blurActive = active;
        applyPanelBackground();
    }

    /** 重算并把当前字幕行滚动到悬浮窗垂直居中（缩放窗口后调用；v20：不带动画，避免拖拽时抖动）。 */
    public void recenterCurrent() {
        if (currentLocalIndex >= 0) {
            scrollToCurrent(currentLocalIndex, -1);
        }
    }

    /**
     * 面板被点一下：✕ 没显示就显示它并开始 {@link #CLOSE_BTN_AUTO_HIDE_MS} 倒计时；
     * ✕ 已显示则**这一下就把它收回去**。
     *
     * 需求（Ari，2026-09-23）两条并存：
     *   ① 【code 944】「点击悬浮窗内出现 ✕ 关闭按钮，如果 5s 内用户没有点击关闭，
     *      则 ✕ 关闭按钮消失。」
     *   ② 【code 945】「再次点击按钮外的其他悬浮窗区域，则 ✕ 关闭按钮消失；
     *      这个与 5s 后消失功能并存。」
     *
     * 于是这里是「再点即收」与「超时自动收」两条路径**并存**：点了第二下就是明确要收，
     * 立刻收；两次点击之间没人动，才交给 {@link #closeBtnHideTask} 等满 5s。
     *
     * ⚠️ 能走进本方法的一定是「✕ 以外的悬浮窗区域」：{@link CloseButtonView} 自带点击监听，
     *    落在它身上的触摸会被它自己消费，不会冒泡到窗口层的 GestureListener。
     * ⚠️ 由窗口层的「点击面板」手势调用（见 FloatingWindowManager.GestureListener）。
     */
    public void onPanelTapped() {
        if (closeBtn == null) {
            XposedCompat.log(TAG + " onPanelTapped: closeBtn == null (view not init?)");
            return;
        }
        // 两条路径（再点即收 / 超时自动收）共用一个计时器，先撤干净再分叉。
        closeBtnHandler.removeCallbacks(closeBtnHideTask);
        if (closeBtn.getVisibility() == VISIBLE) {
            closeBtn.setVisibility(GONE);
            XposedCompat.log(TAG + " panel tapped again -> close button hidden");
            return;
        }
        closeBtn.setVisibility(VISIBLE);
        closeBtnHandler.postDelayed(closeBtnHideTask, CLOSE_BTN_AUTO_HIDE_MS);
        XposedCompat.log(TAG + " panel tapped -> close button VISIBLE (auto-hide "
                + CLOSE_BTN_AUTO_HIDE_MS + "ms)");
    }

    /**
     * 【code 944】窗口被摘除（关闭悬浮窗 / 换轨重建）时清掉排队中的自动隐藏任务 ——
     * 否则那条消息会握着本视图及其 Handler 多活最多 5s。
     */
    @Override
    protected void onDetachedFromWindow() {
        // 【2.2.11】窗口摘除即停采集：采集链在窗口不可见时继续跑没有任何意义，
        //   而且它握着宿主的 Activity 引用与 Handler。
        HostBackdrop.get().stop();
        closeBtnHandler.removeCallbacks(closeBtnHideTask);
        // 【2.1.5】摘掉可能还挂着的缩放探针，避免它握住已废弃的视图
        if (scrollView != null) {
            ViewTreeObserver vto = scrollView.getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnPreDrawListener(mResizeProbe);
            }
        }
        super.onDetachedFromWindow();
    }

    /**
     * 生成玻璃面板背景。
     * 有系统模糊时可更通透；无模糊（当前默认档：ColorOS 上系统模糊会糊掉整个屏幕，
     * 已被 {@code ENABLE_SYSTEM_BLUR_BEHIND=false} 关闭）时提高底色不透明度，
     * 让面板呈现"磨砂卡"质感，并保证白色字幕在任何背景上都清晰可读。
     */
    private void applyPanelBackground() {
        // 【2.2.9】液态玻璃开启时换绘制后端：LiquidGlassDrawable 自带玻璃观感
        //   （1px 折射边光 + 顶部软受光 + 底部薄受光 + 平底色 + 颗粒霜化），
        //   不再吃 float_window_color、也不再需要用 fillScale 补不透明度
        //   —— 它的底色 alpha 是专门为「白字可读」调过的。
        //   详见 LiquidGlassDrawable 类头（含「为什么不是真·背后折射」的出处）。
        final SubtitleStyle st = style();
        final Drawable bg;
        final Drawable oldBg = getBackground();
        if (st.liquidGlass) {
            // 【2.2.13 / 987】面板玻璃的"接触阴影环"宽度 = GLASS_SHADOW_INSET_DP。
            //   ⚠️ 987 起该常量为 **0**（Ari 第三次点名"中心比四周透、像外面裹了一层"，
            //   已整体删除这一圈，见该常量注释）⇒ 玻璃与窗口完全重合、不画外阴影。
            //   这里的传参保留，是为了以后把常量改回非零时一行都不用动。
            LiquidGlassDrawable glass = new LiquidGlassDrawable(dp(LIQUID_GLASS_CORNER_RADIUS_DP),
                    density(), dp(GLASS_SHADOW_INSET_DP));
            // 【2.2.11b】模糊强度：有背景时作用在背景位图上（采集侧读同一份配置），
            //   没有背景时由 Drawable 用来柔化自己的受光/边光 —— 两处都拿到同一个值。
            glass.setBlurPct(st.liquidGlassBlurPct);
            // 【2.2.13】继承旧玻璃的运行时状态（系统模糊生效位 + 背景统计）：
            //   重建后的新玻璃第一帧就与旧玻璃画得一致，不再"先变黑一拍等统计"。
            if (oldBg instanceof LiquidGlassDrawable) {
                glass.inheritRuntimeStateFrom((LiquidGlassDrawable) oldBg);
            } else if (hasLastStats) {
                // 【988】普通悬浮窗 → 液态玻璃：旧底是扁平色块，**没有**可继承的运行时状态。
                //   若不给它打底，新玻璃会退回**不透明深色底**；而此刻用户通常正停在设置页里
                //   （宿主在后台），采样被软跳过 —— 真机日志（`LSPosed_20261009_101421`）实测
                //   10:14:04 切到液态玻璃后直到 10:14:17 才有第一条统计，中间面板一直是深色
                //   （Ari 看到的"先变成黑色"）。这里把本进程上一次采到的统计原样打给新玻璃，
                //   第一帧就是自适应玻璃；真实样本回来后由 {@link #smoothLum} 正常接手修正。
                glass.setBackdropStats(smoothedLum, lastEdgeTop, lastEdgeBottom);
            } else {
                // 【991】本进程还没采到过任何统计（典型：刚被系统回收重启，而用户又是
                //   在设置页里点保存的那一刻才第一次开液态玻璃）⇒ 再退一步读**落盘留底**。
                //   它来自宿主"上一次可见时"采到的值。之所以要这一层：采集源只能是宿主
                //   自己的窗口，用户一回到桌面就再也采不到东西（见 util/BackdropBaseline）。
                seedFromPersistedBaseline(glass);
            }
            bg = glass;
        } else {
            // v13：GlassPanelDrawable 已精简为「只有半透明渐变底」，不再需要传描边宽度。
            // 【2.3.0】渐变两档颜色改由配置换算（SubtitleStyle），无配置时回落原常量。
            GlassPanelDrawable panel =
                    new GlassPanelDrawable(dp(CORNER_RADIUS_DP), LIGHT_GLASS, st);
            panel.setAlpha(blurActive ? 242 : 255);
            panel.setFillScale(blurActive ? 1f : 1.9f);
            bg = panel;
        }
        setBackground(bg);
        // 【2.2.13 / 987】根视图内边距 = 阴影环宽（液态玻璃档）——字幕 / ✕ / 缩放手柄全部
        //   留在**玻璃面内**。⚠️ 987 起环宽为 0 ⇒ 恒等于 v14 的「根视图不设内边距」，
        //   即两个角标重新贴到窗口边缘（距边 10dp，见 CLOSE_BTN_MARGIN_DP / INSET_DP）。
        final int frameInset = st.liquidGlass ? dp(GLASS_SHADOW_INSET_DP) : 0;
        if (getPaddingLeft() != frameInset || getPaddingTop() != frameInset) {
            setPadding(frameInset, frameInset, frameInset, frameInset);
        }
        // 【2.3.1 §6.1.1】面板底色一变就同步 ✕ / 缩放手柄的「灰 ↔ 白」。
        applyControlTint();
        // 【2.2.11】换过绘制后端之后对齐一次采集开关（液态玻璃开 → 采背景；关 → 停）。
        //   这里必须调用：本方法既是「视图构建时」也是「运行中改配置时」的唯一出口。
        syncBackdropCapture();
        // 【2.2.12】模糊强度变了／玻璃开关变了 ⇒ 重新下发模糊半径（关玻璃时下发 0 撤掉）。
        applyBackdropBlur();
    }

    /** 面板圆角换算用的屏幕密度（取不到时回落 3f，与胶囊侧同一兜底口径）。 */
    private float density() {
        try {
            float d = getResources().getDisplayMetrics().density;
            return d > 0f ? d : 3f;
        } catch (Throwable t) {
            return 3f;
        }
    }

    /**
     * 【2.3.1 §6.1.1】按面板基色决定 ✕ 关闭按钮与右下缩放手柄的控件颜色。
     *
     * ── 规则（Ari 原话）────────────────────────────────────────────────
     *   「当悬浮窗背景是**除黑白两色外的其他颜色**时，关闭和尺寸调整改为用白色
     *    （不透明度与现有的控件不透明度设定一致）」
     *
     * ── 判据 ───────────────────────────────────────────────────────────
     * 取面板基色 RGB，看它是否在「黑白档」里：
     *   · 亮度极低（≤ 0.06，含默认的深蓝黑 #0E1420）→ 黑档；
     *   · 亮度极高（≥ 0.94，含纯白）→ 白档；
     *   · 其余（任何有彩度/中等灰度的颜色）→ 彩色档 ⇒ 控件转白。
     * ⚠️ 用感知亮度（Rec.709 加权）而不是简单平均：纯绿 (0,255,0) 的平均值和灰 (85)
     *    差不多，但看起来亮得多，平均法会把它误判成黑白档。
     */
    private void applyControlTint() {
        final SubtitleStyle st = style();
        // 【2.2.9】液态玻璃的面板固定是深色玻璃 ⇒ 视为"黑档"，控件保持默认色（不转白）。
        //   只有非玻璃档才去看 float_window_color 是不是彩色。
        // 【987】同时把「液态玻璃开没开」本身告诉这两个控件 ——
        //   2.2.13 把它们的材质**无条件**换成了玻璃（渐变受光 + 1px 描边），
        //   于是关掉液态玻璃时出现"面板是扁平色块、两个角标却是玻璃"的错配。
        //   现在：开 ⇒ 2.2.13 的玻璃材质；关 ⇒ v14 的扁平角标（灰/白平涂、无描边）。
        final boolean glass = st.liquidGlass;
        final boolean colored = !glass && isColoredPanel(st.panelColorTop);
        if (closeBtn != null) {
            closeBtn.setLiquidGlass(glass);
            closeBtn.setOnColoredPanel(colored);
        }
        if (gripView != null) {
            gripView.setLiquidGlass(glass);
            gripView.setOnColoredPanel(colored);
        }
    }

    /** 面板基色是否属于「黑白之外」的彩色档（判据见 {@link #applyControlTint()}）。 */
    private static boolean isColoredPanel(int panelColorTop) {
        int r = (panelColorTop >> 16) & 0xFF;
        int g = (panelColorTop >> 8) & 0xFF;
        int b = panelColorTop & 0xFF;
        float lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return lum > 0.06f && lum < 0.94f;
    }

    private void init() {
        // 【M1】先把标准配置读出来（此时 sp / 颜色 / 字重等都由配置决定，不再是硬编码常量）
        refreshStyle();
        applyPanelBackground();
        // v14：根视图**不设**内边距 —— ✕ 与缩放手柄需要贴到窗口边缘（3dp）。
        // 内容内边距下移到 container；measureCurrentCue 里的 padW/padH 数值含义不变。

        scrollView = new NonInterceptScrollView(getContext());
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(PANEL_PAD_H_DP), dp(PANEL_PAD_V_DP),
                dp(PANEL_PAD_H_DP), dp(PANEL_PAD_V_DP));

        scrollView.addView(container);

        hint = new TextView(getContext());
        // 【code 954】多语言：简体「无字幕」/ 繁体「無字幕」/ 非中文「No Subtitles」
        //   （见 I18n —— 这里**不能**用宿主 Context 的字符串资源，理由是类头那条：
        //    目标进程的 Resources 里没有本模块的资源。）
        hint.setText(I18n.noSubtitles());
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(TEXT_SHADOW_RADIUS, 0f, dp(1), TEXT_SHADOW_COLOR);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.CENTER;
        hint.setLayoutParams(hintLp);

        // 右下角缩放手柄提示（v13：自绘「三角倒圆」图形；v14：灰底 40%；
        // v15：距窗口右下角 10dp，与 ✕ 的外边距对齐）
        GripIndicatorView grip = new GripIndicatorView(getContext());
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                dp(GRIP_VIEW_DP), dp(GRIP_VIEW_DP));
        gripLp.gravity = Gravity.BOTTOM | Gravity.END;
        grip.setLayoutParams(gripLp);
        gripView = grip;

        // 右上角关闭按钮（✕），默认隐藏，点击面板任意处切换显隐。
        // v15：自绘「灰底圆 + 镂空 ✕」，直径 / 点击区域同为 40dp，距窗口上/右边缘各 10dp。
        // v18：直径 / 点击区域收到 30dp。
        closeBtn = new CloseButtonView(getContext());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dp(CLOSE_BTN_DP), dp(CLOSE_BTN_DP));
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp(CLOSE_BTN_MARGIN_DP);
        closeLp.rightMargin = dp(CLOSE_BTN_MARGIN_DP);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setVisibility(GONE);
        closeBtn.setOnClickListener(v -> {
            XposedCompat.log(TAG + " close button CLICKED -> setFloatingWindowOpen(false)");
            // 【code 944】点过就走：撤掉排队中的自动隐藏，并立刻把 ✕ 收起来 ——
            // 否则关窗/重建期间那条 5s 消息还会回来动一个已经作废的视图。
            closeBtnHandler.removeCallbacks(closeBtnHideTask);
            closeBtn.setVisibility(GONE);
            // 关闭悬浮窗：置 false 后，repo 观察者会驱动 FloatingWindowManager 隐藏窗口，
            // 同时播放页按钮状态同步更新为「悬浮关」。
            SubtitleRepository.getInstance().setFloatingWindowOpen(false);
        });

        addView(scrollView);
        addView(hint);
        addView(grip);
        addView(closeBtn); // 最后添加，保证在最上层、可点击

        // 🔴【2.3.2 §6.1.1 真 bug】补一次控件配色。
        //
        // ── 现象 ────────────────────────────────────────────────────────
        // Ari 连报两轮：「优化悬浮窗背景色 除黑白两色 外的调整控件颜色
        // （！！上次就说了，这里根本没修改，还是灰色）」。
        //
        // ── 根因（不在判据，全在调用时机）────────────────────────────────
        // init() 第 458 行就调了 applyPanelBackground()，而它内部会顺手调
        // applyControlTint()。可那一刻 **closeBtn / gripView 都还是 null**
        // （它们在本方法后半段才 new 出来），于是 applyControlTint() 里两个
        // `if (xx != null)` 全部落空 —— 控件保持 CloseButtonView / GripIndicatorView
        // 构造函数里写死的中灰。
        // 之后唯一的补色入口是 refreshStyle() 里那条「面板色变了才重建背景」的分支，
        // 而视图重建时 style 是**新建的**（old == null 直接 return）⇒ 只要一开就是彩色面板，
        // 这条补色链永远走不到。结果就是「判据写对了、算也算了，颜色却永远是灰的」。
        //
        // 修法：控件建好之后**再补一次** applyControlTint()。它只读 style()（缓存字段），
        // 不重建背景、不重测布局，开销可以忽略。
        // ⚠️ 不要改成「把 applyPanelBackground() 挪到末尾」—— 面板背景是要给
        //    setBackground 用的，两件事分开更清楚，也避免以后有人再挪坏顺序。
        applyControlTint();

        XposedCompat.log(TAG + " view built: padH=" + PANEL_PAD_H_DP + "dp padV=" + PANEL_PAD_V_DP
                + "dp minTextPad=" + MIN_TEXT_PAD_DP + "dp corner=" + CORNER_RADIUS_DP
                + "dp lightGlass=" + LIGHT_GLASS
                + " currentScale=" + CURRENT_SCALE + " closeBtn=" + CLOSE_BTN_DP
                + "dp margin=" + CLOSE_BTN_MARGIN_DP + "dp gripInset="
                + GripIndicatorView.INSET_DP + "dp gripView=" + GRIP_VIEW_DP
                + "dp gripHit=" + GRIP_HIT_DP + "dp scrollAnim=" + SCROLL_ANIM_MS + "ms"
                + " resizeProbe=v216");
    }

    /**
     * 右下角「缩放热区」命中判定。
     *
     * 热区是**以手柄三角形自身为中心**、边长 {@link #GRIP_HIT_DP} 的正方形 ——
     * 三角形绘制在窗口右下角 {@code INSET_DP} ~ {@code INSET_DP + LEG_DP} 之间，
     * 故其中心距窗口右下角 = {@code INSET_DP + LEG_DP / 2}。
     * 手指落在三角形上（或紧邻四周）即命中，而不是"贴着窗口角落"才算。
     *
     * @param x 触摸点相对本视图左上角的 x
     * @param y 触摸点相对本视图左上角的 y
     */
    public boolean hitResizeArea(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        // 【2.2.13 / 987】坐标原点是窗口；手柄三角形画在"玻璃面内"，
        //   玻璃又比窗口小一圈阴影环 ⇒ 中心偏移要先加上环宽（987 起环宽 0，等价旧算式）。
        float centerOff = getFrameInsetPx()
                + (GripIndicatorView.INSET_DP + GripIndicatorView.LEG_DP / 2f) * density;
        float half = GRIP_HIT_DP * density / 2f;
        return Math.abs(x - (getWidth() - centerOff)) <= half
                && Math.abs(y - (getHeight() - centerOff)) <= half;
    }

    public void updateFromRepository() {
        SubtitleRepository repo = SubtitleRepository.getInstance();
        List<SubtitleCue> cues = repo.getCues();
        int currentIdx = repo.findCurrentCueIndex();

        // 【M1】每次渲染前同步一次配置（设置页保存后配置代数会变，渲染缓存键随之变化 → 立即重绘）
        refreshStyle();
        int cfgRev = RemoteConfig.revision();

        // 1) 有完整 cue 列表 → 渲染当前行附近，当前行放大、其余模糊
        if (!cues.isEmpty()) {
            int from = Math.max(0, currentIdx - WINDOW_RADIUS);
            int to = Math.min(cues.size() - 1, (currentIdx < 0 ? 0 : currentIdx) + WINDOW_RADIUS);
            String key = "c" + cfgRev + ":cues:" + cues.size() + ":" + currentIdx + ":" + from + ":" + to;
            if (key.equals(lastRenderKey) && !lastHintVisible) {
                return;
            }
            hint.setVisibility(GONE);
            scrollView.setVisibility(VISIBLE);
            lastHintVisible = false;
            renderCues(cues, currentIdx, from, to);
            lastRenderKey = key;
            // v20：只有「播放推进 → 当前行真的换了」才做平滑上滚；首次定位直接到位。
            // v40：滚动目标几乎每句都一样（见 scrollToCurrent 的注释），所以动画不能靠
            //      「旧滚动量 → 新滚动量」的差值驱动，必须显式走「上一句 → 这一句」的几何距离。
            int prevCue = lastCenteredCueIndex;
            boolean animate = currentIdx >= 0 && prevCue >= 0 && prevCue != currentIdx;
            scrollToCurrent(currentLocalIndex, animate ? prevCue : -1);
            lastCenteredCueIndex = currentIdx >= 0 ? currentIdx : -1;
            return;
        }

        // 2) 降级：只有屏上镜像的当前行（播放进度不可用且未抓到完整列表）
        List<String> current = repo.getCurrentSubtitles();
        if (!current.isEmpty()) {
            String key = "c" + cfgRev + ":mirror:" + String.join("\u0001", current);
            if (key.equals(lastRenderKey) && !lastHintVisible) {
                return;
            }
            hint.setVisibility(GONE);
            scrollView.setVisibility(VISIBLE);
            lastHintVisible = false;
            renderMirrored(current);
            lastRenderKey = key;
            lastCenteredCueIndex = -1;
            return;
        }

        // 3) 真的没有字幕
        if (!lastHintVisible) {
            minWidthPx = 0;
            minHeightPx = 0;
            currentLocalIndex = -1;
            lastCenteredCueIndex = -1;
            pendingTravelLocal = -1; // v40：作废还没来得及执行的位移动画
            pendingTravelFromCue = -1;
            cancelScrollAnim();
            hint.setVisibility(VISIBLE);
            scrollView.setVisibility(GONE);
            container.removeAllViews();
            lastHintVisible = true;
            lastRenderKey = null;
        }
    }

    private void renderMirrored(List<String> lines) {
        container.removeAllViews();
        currentLocalIndex = -1;
        cueBlockFrom = -1; // v40：镜像内容没有 cue 概念，作废块区间
        cueBlockStart = null;
        cueBlockEnd = null;
        minWidthPx = 0;
        minHeightPx = 0;
        SubtitleStyle st = style();
        for (String line : lines) {
            TextView tv = new TextView(getContext());
            tv.setText(line);
            // 【M1】降级镜像行：字号沿用 legacy 的 18sp（不暴露给用户），其余（颜色/字重/对齐/
            // 阴影/内边距）全部走配置 —— 与活动行同一套观感，避免「有 cue 列表 / 没 cue 列表
            // 两种渲染看起来不是同一个悬浮窗」。
            styleLine(tv, st.mirrorSizeSp, st.activeColor, st.activeAlpha, false);
            clearBlur(tv);
            container.addView(tv);
        }
    }

    private void renderCues(List<SubtitleCue> cues, int currentIdx, int from, int to) {
        container.removeAllViews();
        currentLocalIndex = -1;
        // v40：记录每个 cue 的「首/末子 View 下标」——位移动画要靠它算上一句与这一句的距离
        cueBlockFrom = from;
        int n = Math.max(0, to - from + 1);
        cueBlockStart = new int[n];
        cueBlockEnd = new int[n];
        int childIndex = 0;
        SubtitleStyle st = style();
        for (int i = from; i <= to; i++) {
            SubtitleCue cue = cues.get(i);
            boolean isCurrent = (i == currentIdx);
            int k = i - from;
            cueBlockStart[k] = childIndex;
            if (isCurrent) {
                // 当前高亮行：合并为单个可自动换行的文本段落（宽度不足时强制换行，≤3 行）。
                if (currentLocalIndex < 0) {
                    currentLocalIndex = childIndex;
                }
                TextView tv = new TextView(getContext());
                tv.setText(joinSubtitles(cue));
                // 【M1】活动行：字号 = 17sp × 主字幕放大倍数；颜色/高亮/字重/阴影/对齐走配置
                styleLine(tv, st.activeSizeSp, st.activeColor, st.activeAlpha, true);
                tv.setMaxLines(MAX_LINES);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                clearBlur(tv);
                container.addView(tv);
                childIndex++;
            } else {
                for (String line : cue.subtitles) {
                    TextView tv = new TextView(getContext());
                    tv.setText(line);
                    // 【M1】非活动行：字号 = 17sp × 非活动行缩放比例；模糊由配置决定
                    styleLine(tv, st.inactiveSizeSp, st.inactiveColor, st.inactiveAlpha, false);
                    applyBlur(tv);
                    container.addView(tv);
                    childIndex++;
                }
            }
            cueBlockEnd[k] = childIndex - 1; // 该 cue 一行都没有时 < 起点，视为无效区间
        }
        measureCurrentCue(cues, currentIdx);
    }

    /**
     * v40：取某个全局 cue 下标在 {@link #container} 中的子 View 区间。
     *
     * @return {@code [首, 末]}；不在本次渲染窗口内 / 该 cue 无文本行时返回 null
     */
    private int[] cueBlockRange(int cueIdx) {
        if (cueBlockStart == null || cueIdx < cueBlockFrom) {
            return null;
        }
        int k = cueIdx - cueBlockFrom;
        if (k >= cueBlockStart.length) {
            return null;
        }
        int s = cueBlockStart[k];
        int e = cueBlockEnd[k];
        if (s < 0 || e < s) {
            return null;
        }
        return new int[]{s, e};
    }

    /**
     * 统一的字幕行样式：对齐 + 内边距 + 字号 + 颜色 + 不透明度 + 投影 + 字重。
     *
     * 【M1】本方法不再持有任何默认外观常量 —— 对齐、内边距、阴影（半径/偏移/颜色）、
     * 字重全部来自 {@link SubtitleStyle}（即标准配置）；只有**字号 / 颜色 / 不透明度**
     * 三个随行角色（活动行 / 非活动行 / 镜像行）变化的值由调用方显式传入。
     *
     * @param active true = 活动行（用活动行字重，并吃「长字幕内换行额外行距」）；
     *               false = 非活动行 / 镜像行（用非活动行字重，不加额外行距）
     */
    private void styleLine(TextView tv, float sizeSp, int color, float alpha, boolean active) {
        SubtitleStyle st = style();
        tv.setGravity(st.gravity);
        tv.setPadding(dp(st.linePadH), dp(st.linePadV), dp(st.linePadH), dp(st.linePadV));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setAlpha(alpha);
        // 阴影：半径/偏移/颜色（alpha 已按「阴影强度」折算）都来自配置。
        // radius = 0 时 setShadowLayer 自动不画阴影（阴影强度 0% 的效果由此保证）。
        tv.setShadowLayer(st.shadowRadiusPx, 0f, st.shadowDyPx, st.shadowColor);
        tv.setTypeface(active ? st.activeTypeface : st.inactiveTypeface);
        if (active && st.wrapExtraSpacingPx != 0f) {
            // 「长字幕内换行额外行距」只作用于会换行的活动行段落（PRD §FR-06）
            tv.setLineSpacing(st.wrapExtraSpacingPx, 1f);
        }
    }

    /** 把当前 cue 的多条字幕合并成一段文本（以空格连接），用于「强制换行 ≤3 行」的度量与渲染。 */
    private static String joinSubtitles(SubtitleCue cue) {
        StringBuilder sb = new StringBuilder();
        if (cue.subtitles != null) {
            for (String s : cue.subtitles) {
                if (s == null) {
                    continue;
                }
                String t = s.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * 测量「当前高亮文本行」的最小宽高：
     *  - 最小宽度 = 使整段文本恰好排成 ≤ MAX_LINES(3) 行所需的最小宽度（二分求得）。
     *    因此窗口宽度可以一直缩到文字开始折成第 3 行；再窄就要第 4 行，于是锁死。
     *  - 最小高度 = 在该最小宽度下换行后的实际文本高度（含 padding）。
     *
     * v20：每边内边距取 {@code max(面板内边距, }{@link #MIN_TEXT_PAD_DP}{@code ) + TextView 自身内边距}，
     * 硬性保证窗口缩到最小时字幕四周仍 ≥15dp 留白。
     */
    private void measureCurrentCue(List<SubtitleCue> cues, int currentIdx) {
        minWidthPx = 0;
        minHeightPx = 0;
        if (currentIdx < 0 || currentIdx >= cues.size()) {
            return;
        }
        SubtitleCue cue = cues.get(currentIdx);
        String text = joinSubtitles(cue);
        if (text.isEmpty()) {
            return;
        }
        SubtitleStyle st = style();
        float sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                st.activeSizeSp, getContext().getResources().getDisplayMetrics());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sizePx);
        // 必须与渲染用的字体一致（活动行字重由配置决定），否则测量宽度会偏小、导致多出一行。
        paint.setTypeface(st.activeTypeface);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;

        // 每边**有效**留白 = 面板内边距 + 文字自身内边距，且不低于 MIN_TEXT_PAD_DP。
        // ⚠️ max 必须作用在"有效值"上：max(13,15)+2 = 17dp 是错的，应是 max(13+2,15) = 15dp。
        // 【M1】文字自身内边距改为读配置换算结果（「字幕间行距」会改变上下内边距），
        //       于是窗口最小高度天然把行距算进去，不会出现「行距调大后被裁切」。
        int padHSide = Math.max(PANEL_PAD_H_DP + st.linePadH, MIN_TEXT_PAD_DP);
        int padVSide = Math.max(PANEL_PAD_V_DP + st.linePadV, MIN_TEXT_PAD_DP);
        int padW = dp(padHSide) * 2;
        int padH = dp(padVSide) * 2;

        int naturalW = (int) Math.ceil(paint.measureText(text)); // 单行不换行宽度（行数=1，必 ≤3）
        int screenW = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;

        // 二分：找最小的「内容宽度」使行数 ≤ MAX_LINES
        int lo = 1;
        int hi = Math.max(1, naturalW);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lineCount(paint, text, mid) <= MAX_LINES) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        int contentW = lo;

        minWidthPx = Math.min(contentW + padW + 6, screenW); // +6 余量，防字体舍入导致多出一行

        int linesAtMin = lineCount(paint, text, contentW);
        int textH = (int) Math.ceil(linesAtMin * lineH);
        minHeightPx = Math.min(textH + padH, screenH);
    }

    /** 用 Paint.breakText 静态模拟：文本在给定宽度下会折成多少行。 */
    private static int lineCount(Paint paint, String text, int width) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        if (width <= 0) {
            return text.length();
        }
        int n = text.length();
        int i = 0;
        int lines = 0;
        while (i < n) {
            int count = paint.breakText(text, i, n, true, width, null);
            if (count <= 0) {
                count = 1; // 宽度太窄连一个字符都放不下，按 1 字符推进，避免死循环
            }
            i += count;
            lines++;
            if (lines > MAX_LINES + 1) {
                break; // 已超过上限，无需继续
            }
        }
        return Math.max(1, lines);
    }

    /**
     * v20：把 {@link #container} 的上下内边距设为「半个视口高度 + 面板内边距」。
     *
     * 只有内容上下各多出半个视口的空白，ScrollView 才可能把**第一行 / 最后一行**
     * 也滚到窗口正中（否则滚动量被夹在 0 ~ 内容高-视口高，首尾行只能贴边）。
     *
     * 【2.1.5】这个「按视口高算内边距」的动作必须在**内容被测量之前**完成，否则会出现
     * 「视口已是新值、内边距还是旧值」的中间帧。
     * 真正的预置点在 {@link NonInterceptScrollView#onMeasure}（super 之前），本方法保留为
     * **权威真值与兜底修正**，并顺手做一件事：一旦发现预置用的可用高与真实布局高不一致
     * （两条路会在两个取值间反复改内边距 ⇒ 每帧一次 requestLayout），就永久停用预置。
     *
     * @return 本次是否真的改了内边距（改了就需要等一次布局再算滚动位置）
     */
    private boolean applyCenterPadding() {
        int vp = scrollView == null ? 0 : scrollView.getHeight();
        if (predictPadOk && predictedVp > 0 && vp > 0 && predictedVp != vp) {
            predictPadOk = false;
            XposedCompat.log(TAG + " resize probe: predictive padding DISABLED (spec="
                    + predictedVp + " actual=" + vp + ")");
        }
        return applyCenterPaddingFor(vp);
    }

    /** 【2.1.5】按给定的「视口高」把内边距算到目标值（与 applyCenterPadding 同一套算式）。 */
    private boolean applyCenterPaddingFor(int vp) {
        if (container == null || scrollView == null) {
            return false;
        }
        int half = vp / 2;
        int wantH = dp(PANEL_PAD_H_DP);
        int wantV = half + dp(PANEL_PAD_V_DP);
        if (container.getPaddingTop() == wantV && container.getPaddingBottom() == wantV
                && container.getPaddingLeft() == wantH && container.getPaddingRight() == wantH) {
            return false;
        }
        container.setPadding(wantH, wantV, wantH, wantV);
        return true;
    }

    /**
     * 【2.1.6】缩放期间的「同帧重新居中」。
     *
     * <p><b>为什么必须同帧</b>：居中落点 y = 内边距 + 前置行高和 + 行高/2，而**前置行高和只随
     * 宽度变化** —— 窗口变宽/变窄 ⇒ 文字重新折行 ⇒ 当前行的内容坐标整段平移（实测阶跃恰好是
     * 52px 的整数倍），高度变化完全不改它。所以「拖动缩放时字幕上下抖」只在改**宽度**时出现。
     *
     * <p>原来这里是 post(runnable)，落在**下一帧**才执行。2.1.5 真机探针 10395 行铁证：
     * 4940 对相邻帧里 100% 都是「本帧的滚动量 = 上一帧该有的落点」，一帧不差 ——
     * 于是一旦折行，那一帧画出来的还是旧落点，字幕偏 1~2 帧后再自己弹回，观感就是上下抖动。
     *
     * <p>现在改在 preDraw（**同一趟布局之后、绘制之前**）里重新居中：视口与内容重排 →
     * 算落点 → 绘制，全在同一帧内完成，中间帧从结构上消失。
     */
    private void recenterForResizeSameFrame() {
        if (!pendingResizeRecenter) {
            return;
        }
        pendingResizeRecenter = false;
        if (container == null || scrollView == null || currentLocalIndex < 0) {
            return;
        }
        scrollNow(currentLocalIndex);
    }

    /** 视口尺寸变化（拖动缩放窗口）→ 刷新居中内边距 + 登记一次「同帧重新居中」。 */
    private void onViewportSizeChanged() {
        applyCenterPadding();
        if (currentLocalIndex >= 0) {
            probeResizeFrame("v216-layout");
            pendingResizeRecenter = true;
            ViewTreeObserver obs = scrollView.getViewTreeObserver();
            if (obs.isAlive()) {
                obs.removeOnPreDrawListener(mResizeProbe);
                obs.addOnPreDrawListener(mResizeProbe);
            }
            // 兜底：preDraw 是本版的正路；万一这帧没走到（视图不可见等），下一帧补一次。
            post(mResizeFallback);
        }
    }

    /**
     * 【2.1.5】缩放探针；【2.1.6】在同一个 preDraw 里记「修正前 / 修正后」两条：
     * 修正前 = 不修的话这一帧会画成什么样（对照上一版的观感），修正后 = 本帧真正画出去的样子。
     * 纯只读 + 记日志，跑完即自行摘下。
     */
    private final ViewTreeObserver.OnPreDrawListener mResizeProbe =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver obs = scrollView.getViewTreeObserver();
                    if (obs.isAlive()) {
                        obs.removeOnPreDrawListener(this);
                    }
                    probeResizeFrame("v216-pre");
                    recenterForResizeSameFrame();
                    probeResizeFrame("v216-fix");
                    return true;
                }
            };

    /**
     * 【2.1.5】把当前帧的几何写成一行日志（只在窗口尺寸变化时调用）。
     * 【2.1.6】新增 vw（视口宽）与 cw（容器宽）两列，用来直接证明「是宽度在变」。
     *
     * 字段含义：
     *   vp        视口高（scrollView.getHeight()）
     *   vw / cw   视口宽 / 容器宽（宽度变了才会重新折行 ⇒ 落点才会变）
     *   padNow    container 此刻的上下内边距值
     *   padUsed   内容**实际被测量时**用的内边距，由 (容器高 − 内容高) / 2 反推
     *             padUsed != padNow 即 `[PAD-LATE]`：内边距改了但内容还没按新值量
     *   ctrInVp   当前行中心相对视口顶的偏移；居中时应恒等于 vp/2
     *   off       ctrInVp − vp/2，非 0 就是偏了多少像素（正 = 偏下 / 负 = 偏上）
     */
    private void probeResizeFrame(String where) {
        if (container == null || scrollView == null || currentLocalIndex < 0) {
            return;
        }
        int count = container.getChildCount();
        int vp = scrollView.getHeight();
        int vw = scrollView.getWidth();
        if (count <= 0 || vp <= 0) {
            return;
        }
        View target = container.getChildAt(Math.min(currentLocalIndex, count - 1));
        if (target == null) {
            return;
        }
        int contentH = container.getChildAt(count - 1).getBottom()
                - container.getChildAt(0).getTop();
        int padNow = container.getPaddingTop();
        int padUsed = (container.getHeight() - contentH) / 2;
        int ctr = target.getTop() + target.getHeight() / 2 - scrollView.getScrollY()
                + (int) container.getTranslationY();
        int off = ctr - vp / 2;
        LogGate.debug(TAG, " resize probe[" + where + "]: vp=" + vp
                + " vw=" + vw + " cw=" + container.getWidth()
                + " padNow=" + padNow + " padUsed=" + padUsed
                + (padUsed == padNow ? "" : " [PAD-LATE]")
                + " scrollY=" + scrollView.getScrollY()
                + " lineTop=" + target.getTop() + " lineH=" + target.getHeight()
                + " ctrInVp=" + ctr + " expect=" + (vp / 2)
                + (off == 0 ? " OK" : " OFF=" + off));
    }

    /**
     * 把「当前高亮行」滚到视口正中。
     *
     * @param localIndex 当前行在 container 中的子 View 下标
     * @param fromCue    ≥0 = 播放从这一句推进到当前句 → 平滑上滚；-1 = 立即跳转（缩放 / 首次定位）
     */
    private void scrollToCurrent(int localIndex, int fromCue) {
        if (localIndex < 0) {
            return;
        }
        if (fromCue >= 0) {
            // 必须赶在这一帧「绘制之前」装好位移：新内容一旦先按旧滚动量画出来，
            // 用户看到的就是「下一句直接出现」→ 再跳回去滚一遍。
            scheduleTravel(localIndex, fromCue);
            return;
        }
        post(() -> {
            if (applyCenterPadding()) {
                // 内边距刚变 → 等这次布局把新高度量完，否则滚动目标算不准
                post(() -> scrollNow(localIndex));
            } else {
                scrollNow(localIndex);
            }
        });
    }

    /** v40：把居中滚动 + 位移动画排到下一帧的「布局之后、绘制之前」。 */
    private void scheduleTravel(int localIndex, int fromCue) {
        pendingTravelLocal = localIndex;
        pendingTravelFromCue = fromCue;
        ViewTreeObserver obs = scrollView.getViewTreeObserver();
        if (obs.isAlive()) {
            obs.removeOnPreDrawListener(mTravelPreDraw); // 防重复挂
            obs.addOnPreDrawListener(mTravelPreDraw);
        }
        // 兜底：万一这一帧根本没绘制（窗口未挂载 / 刚隐藏），至少保证居中位置是对的
        post(() -> {
            if (pendingTravelLocal < 0) {
                return; // preDraw 已经处理过了
            }
            pendingTravelLocal = -1;
            pendingTravelFromCue = -1;
            if (applyCenterPadding()) {
                post(() -> scrollNow(localIndex));
            } else {
                scrollNow(localIndex);
            }
        });
    }

    private final ViewTreeObserver.OnPreDrawListener mTravelPreDraw =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver obs = scrollView.getViewTreeObserver();
                    if (obs.isAlive()) {
                        obs.removeOnPreDrawListener(this);
                    }
                    int localIndex = pendingTravelLocal;
                    int fromCue = pendingTravelFromCue;
                    pendingTravelLocal = -1;
                    pendingTravelFromCue = -1;
                    if (localIndex < 0) {
                        return true;
                    }
                    if (applyCenterPadding()) {
                        // 内边距刚变 → 这一帧的高度还是旧的，等下次布局再定位（放弃动画）
                        post(() -> scrollNow(localIndex));
                        return true;
                    }
                    applyAnimatedCenter(localIndex, fromCue);
                    return true;
                }
            };

    private void scrollNow(int localIndex) {
        int count = container.getChildCount();
        if (count == 0) {
            return;
        }
        View target = container.getChildAt(Math.min(localIndex, count - 1));
        if (target == null) {
            return;
        }
        int viewport = scrollView.getHeight();
        if (viewport <= 0) {
            return;
        }
        int maxScroll = Math.max(0, container.getHeight() - viewport);
        int y = target.getTop() - viewport / 2 + target.getHeight() / 2;
        y = Math.max(0, Math.min(y, maxScroll));
        cancelScrollAnim();
        scrollView.scrollTo(0, y);
    }

    /**
     * v40：居中滚动 + 「上一句 → 这一句」的位移动画。
     *
     * <p><b>为什么滚动量不能做动画</b>：内容是**以当前句为中心重建**的，前后 cue 行数
     * 相同时，把新当前行滚到正中所需的 {@code y} 与上一句**一模一样**
     * （y = 内边距 + 前面若干行的高度和 + 当前行高/2，而"前面若干行"永远是从窗口
     * 左边数到当前句，行数不变 → 和不变）。于是「旧 y → 新 y」的差值恒为 0，
     * 动画等于没跑 —— 这就是"上滚动效有时不生效、下一句直接出现"的根因。
     *
     * <p>真正该走的距离是「上一句块的中心 → 这一句块的中心」，它和滚动量无关。
     * 所以这里：滚动量一次性写死（不动画），动画只负责把内容从"上一句还在正中"
     * 平移到"这一句正中" —— 起点与上一帧画面严丝合缝，不会先跳一下再滚。
     */
    private void applyAnimatedCenter(int localIndex, int fromCue) {
        int count = container.getChildCount();
        if (count == 0) {
            return;
        }
        View target = container.getChildAt(Math.min(localIndex, count - 1));
        if (target == null) {
            return;
        }
        int viewport = scrollView.getHeight();
        if (viewport <= 0) {
            return;
        }
        int maxScroll = Math.max(0, container.getHeight() - viewport);
        int y = target.getTop() - viewport / 2 + target.getHeight() / 2;
        y = Math.max(0, Math.min(y, maxScroll));

        // 上一句块中心 → 这一句块中心：这才是「字幕上滚」该走的距离
        int travel = 0;
        int[] range = cueBlockRange(fromCue);
        if (range != null) {
            View a = container.getChildAt(range[0]);
            View b = container.getChildAt(range[1]);
            if (a != null && b != null) {
                int fromCenter = (a.getTop() + b.getBottom()) / 2;
                int toCenter = target.getTop() + target.getHeight() / 2;
                travel = toCenter - fromCenter;
            }
        }
        cancelScrollAnim();
        int oldY = scrollView.getScrollY();
        scrollView.scrollTo(0, y);
        LogGate.debug(TAG, " subtitle scroll: cue " + fromCue + "->" + lastCenteredCueIndex
                + " travel=" + travel + "px scrollDelta=" + (y - oldY) + "px");
        if (travel != 0) {
            startTravelAnim(travel);
        }
    }

    /** v40：让内容从「上一句还在正中」滑到「这一句正中」。 */
    private void startTravelAnim(int travelPx) {
        container.setTranslationY(travelPx);
        ValueAnimator anim = ValueAnimator.ofFloat(travelPx, 0f);
        anim.setDuration(SCROLL_ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            if (container != null) {
                container.setTranslationY((Float) a.getAnimatedValue());
            }
        });
        scrollAnim = anim;
        anim.start();
    }

    private void cancelScrollAnim() {
        if (scrollAnim != null) {
            scrollAnim.cancel();
            scrollAnim = null;
        }
        // v40：位移动画写在 container 的 translationY 上，取消时必须归零 ——
        // 否则内容会永久停在一个偏移上（缩放 / 隐藏 / 换轨都会走到这里）。
        if (container != null && container.getTranslationY() != 0f) {
            container.setTranslationY(0f);
        }
    }

    /**
     * 【M1】非活动行模糊。
     *
     * 是否模糊、半径多少全部来自配置（{@code inactive_blur_enabled} /
     * {@code inactive_blur_steps}，1 档 = 0.5px，PRD §FR-05）；关闭或半径为 0 时
     * 明确清掉 RenderEffect（而不是沿用上一次的），否则「关掉模糊」在已渲染的行上看不出来。
     */
    private void applyBlur(TextView tv) {
        SubtitleStyle st = style();
        if (!st.inactiveBlurEnabled || st.blurRadiusPx <= 0f) {
            clearBlur(tv);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                tv.setRenderEffect(RenderEffect.createBlurEffect(
                        st.blurRadiusPx, st.blurRadiusPx, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                // 系统不支持 RenderEffect 时的降级：用更低的不透明度近似「弱化」
                tv.setAlpha(0.3f);
            }
        }
    }

    private void clearBlur(TextView tv) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tv.setRenderEffect(null);
        }
    }

    private int dp(float v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 永不拦截触摸的 ScrollView：滚动只允许程序调用，触摸一律交回面板做拖动/缩放。
     * 同时关闭滚动条与边缘辉光，避免播放时闪现。
     */
    private class NonInterceptScrollView extends ScrollView {
        NonInterceptScrollView(Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
            setHorizontalScrollBarEnabled(false);
            // v17：不能再调 setScrollbarFadingEnabled(false)！
            // 它的副作用是把 ScrollabilityCache.state 置成 ON（= 常显），
            // 于是每次 scrollTo（比如缩放手柄拖动时 recenterCurrent）都会把滚动条画出来一下。
            // 现在保持默认（可淡出），并用下面的 awakenScrollBars 覆写彻底掐断唤醒路径。
            setScrollBarSize(0);                 // 双保险：即使被画出也是 0 宽，不可见
            setOverScrollMode(OVER_SCROLL_NEVER); // 关掉边缘辉光
        }

        /**
         * 【2.1.5】在**内容被测量之前**把「半个视口」的内边距预置好。
         *
         * 为什么必须在这里：onSizeChanged 发生在布局阶段、子内容已经量完之后，
         * 那时改内边距只能再排一趟布局 ⇒ 会出现「新视口 + 旧内边距」的中间帧。
         * 放在 super.onMeasure 之前改，则「视口 → 内边距 → 内容测量」全在同一趟里完成。
         * （2.1.6 真机数据表明绘制帧里内边距其实并没有滞后，本条属于无害的双保险。）
         */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (predictPadOk && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
                int vp = MeasureSpec.getSize(heightSpec);
                if (vp > 0) {
                    predictedVp = vp;
                    FloatingSubtitleView.this.applyCenterPaddingFor(vp);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                // v20：窗口尺寸变了 → 半个视口的居中内边距要跟着重算（现在是兜底修正）
                FloatingSubtitleView.this.onViewportSizeChanged();
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }

        /**
         * v17：View.scrollTo() 内部会调 awakenScrollBars()，ScrollView 自动滚动/回弹也会。
         * 直接覆写成 false，滚动条状态永远停在 OFF，任何情况下都不会闪现。
         */
        @Override
        protected boolean awakenScrollBars() {
            return false;
        }

        @Override
        protected boolean awakenScrollBars(int startDelay) {
            return false;
        }

        @Override
        protected boolean awakenScrollBars(int startDelay, boolean invalidate) {
            return false;
        }
    }
}
