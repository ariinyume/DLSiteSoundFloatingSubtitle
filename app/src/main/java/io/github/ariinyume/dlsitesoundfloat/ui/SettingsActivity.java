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
package io.github.ariinyume.dlsitesoundfloat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Locale;

import io.github.ariinyume.dlsitesoundfloat.BuildConfig;
import io.github.ariinyume.dlsitesoundfloat.R;
import io.github.ariinyume.dlsitesoundfloat.config.ConfigBus;
import io.github.ariinyume.dlsitesoundfloat.config.ConfigStore;
import io.github.ariinyume.dlsitesoundfloat.config.SubtitleConfig;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;
import io.github.ariinyume.dlsitesoundfloat.util.ScopeProbe;
import io.github.ariinyume.dlsitesoundfloat.util.Utils;
import io.github.ariinyume.dlsitesoundfloat.view.LiquidGlassDrawable;
import io.github.ariinyume.dlsitesoundfloat.view.SubtitleStyle;

/**
 * 【M1 / v1.4】可视化设置页（PRD §5.1 方案 A）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 本版实现范围
 *
 *   FR-01 模块激活状态卡（分立检测 DLsiteSound / SystemUI + 语言切换按钮）
 *   FR-02 设置页三语 i18n（页内即时切换，含「跟随系统」）
 *   FR-04 主字幕 / 活动行 6 参数 + 恢复默认（色板 8 预设 + 自定义取色）
 *   FR-05 非活动行：模糊开关/半径、缩放开关/比例 + 恢复默认（含联动隐藏）
 *   FR-06 排版：对齐 / 字幕间行距 / 换行额外行距 / **悬浮窗颜色** + 恢复默认
 *   FR-07 SystemUI 重启、状态栏字幕功能总闸
 *   FR-08 全量保存 + 草稿态 + 未保存退出二次确认
 *   FR-09 配置 Schema、原子写、跨进程传递、备份导出/恢复
 *   §9.1 编辑-保存状态机（草稿态只改预览；保存才写盘；恢复默认属于草稿改动）
 *
 * ─────────────────────────────────────────────────────────────────────
 * v1.4（2.3.0 / code 960）本文件承担的真机返工项，逐条索引
 *
 *   §1.1.1 + §6.4 语言按钮：移到页头右上角（XML），文字 = 当前语言短名
 *                （{@link UiLang#nativeShortName()}），主题色填充底 + onPrimary 文字，
 *                内边距在 v1.3 基础上再减 60%，圆角 = 高度/2 = 16dp（XML 里钉死）。
 *   §1.1.2        MD3 / KernelSU 质感：卡片颜色一律取 surfaceContainer 系色槽（见
 *                {@link #refreshCardTints()}），大圆角、低阴影；行间距放宽、分隔克制。
 *   §3.1.3        状态栏区域不染色：由 themes.xml 的 android:statusBarColor=?attr/colorSurface
 *                承担，代码侧不碰 Window（避免与 DayNight / 动态取色打架）。
 *   §3.1.4        夜间不纯黑：values-night/themes.xml 兜底 MD3 暗色分层。
 *   §2.1.1        控件规格：色板各 8 预设 + 圆形 🎨；阴影强度步长 5 默认 50%；
 *                 高亮 50–100% 步长 5；模糊 0–8px 步长 0.5 默认 3px（读数显示 px）；
 *                 缩放 50–100% 步长 5 默认 90%；行距 -5–5dp 步长 0.5；删字重、删常亮。
 *   §3.1.5        新增「悬浮窗颜色」：位置在「长字幕内换行额外行距」正下方；色板圆点画的是
 *                 **面板顶部不透明度叠加后的效果**（{@link ColorSwatchRow#setTranslucentPreview}）。
 *   §2.1.1.4      状态栏字幕功能总闸：见 {@link #onStatusbarFeatureToggled(boolean)} 的注释 ——
 *                 这里把「功能级开关」与「播放页会话级开关」的两级关系写清楚。
 *   §3.1.1        预览窗：渲染复用 {@link SubtitleStyle#of}（不再自己算一套），
 *                 底色做成半透明悬浮窗面板（渐变 + 20dp 圆角），并做滚动吸顶观感。
 *   §5.1.1        导入失败三段提示（非 JSON / 空文件 / 内容损坏），按异常与内容分支 toast。
 *   §6.3          切语言不产生脏态：{@link #isDirty()} 排除 uiLanguage，且切语言
 *                 **立即独立保存**（{@code ConfigStore#saveUiLanguageOnly}）。
 *   §3.11         显示大小/字体大小钉死：见 {@link #attachBaseContext} + {@link #onConfigurationChanged}。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 实现要点（改本文件前必读）
 *
 * ① **草稿态 / 已保存态分离**（PRD §9.1）：{@link #mDraft} 是界面绑定的唯一对象，
 *    任何控件改动只写它；{@link #mSaved} 是磁盘上的现状。二者参数不同即「有未保存改动」，
 *    因此「是否脏」是**派生值**（{@link #isDirty()}），不需要额外的标志位与同步逻辑，
 *    改回去就自动不脏。
 * ② **语言切换后重建全部行**：文案全在 {@link Strings}，切换时重新构建行即可；
 *    控件当前值随后由 {@link #syncWidgets()} 从草稿回灌，用户不会丢任何调整。
 * ③ **回灌期间不回写**（{@link #mSyncing}）：程序性设置 Slider/Switch/ToggleGroup 的值
 *    会触发监听器，不加闸会导致「一切换语言就变成脏」。
 * ④ 所有行都用代码构建：行形态统一（标签 + 控件 + 小字），代码构建比二十多份 XML
 *    更省维护成本，也让「控件 ↔ 配置键」的映射集中在本文件里一处可见（PRD §15.2）。
 * ⑤ 颜色一律取 MD3 色槽（{@code colorPrimaryContainer} / {@code colorErrorContainer} …），
 *    禁止硬编码十六进制（PRD §15.2）；唯二例外是色板预设与兜底色，那是**配置数据**。
 * ⑥ **预览与真窗必须同源**（§3.1.1① / PRD §14.1 RK-03）：预览的一切绘制参数都从
 *    {@link SubtitleStyle#of(SubtitleConfig, DisplayMetrics)} 取，绝不在本文件里
 *    另写一套字号/颜色/阴影换算 —— 否则用户调完发现「预览跟真窗不一样」。
 * ─────────────────────────────────────────────────────────────────────
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "DLsiteFloat:Settings";

    /**
     * 悬浮窗面板顶部的预览不透明度（0–255）。
     *
     * §3.1.5 明确要求色板圆点显示「面板顶部实际不透明度叠加后的效果」：
     * 渲染端的真实口径是「基色 alpha 0x4D × 1.9 增益 ≈ 146 ≈ 0x92」，即约 57%。
     * 这里**只用于色板预览**，不参与任何配置写入 —— 配置里存的是**基色 RGB**，
     * 真实渐变由渲染端（SubtitleStyle / GlassPanelDrawable）负责，两边口径见
     * {@code SubtitleConfig#floatWindowColor} 的注释。
     */
    private static final int PANEL_PREVIEW_TOP_ALPHA = 0x92;

    /**
     * 【2.2.10】液态玻璃档的预览圆角（dp）。
     *
     * ⚠️ 必须等于渲染端 {@code FloatingSubtitleView#LIQUID_GLASS_CORNER_RADIUS_DP}
     * （28dp）。两处各写各的就会重现 PRD §14.1 RK-03 的「预览漂移」：
     * 用户在预览里看到 20dp 的角、真窗却是 28dp。
     *
     * ⚠️ 本类**不引用** view 包的常量是有意的：SettingsActivity 是模块进程的 Activity，
     * 而 FloatingSubtitleView 是注入进程的视图类，把它的私有常量提为 public 只为了
     * 一个圆角并不划算；这里用注释把两处钉在一起，并在 PRD 的预览同源铁律里留有出处。
     */
    private static final int LIQUID_GLASS_PREVIEW_RADIUS_DP = 28;

    // ── 【2.3.1 §1.14 / §7.1.1】全页统一的行距 / 卡片内距 ────────────────
    //
    // §7.1.1 原话：「四个卡片内的上下边距都有视觉上的差距，以『字幕排版』卡片的上下留白
    // 作为参考，统一所有卡片内上下留空」。§1.14 原话：「每个选项之间有细微的页面底留白间隔，
    // 卡片之间的留白间隔可以再大一些」。
    //
    // 做法：把「卡片内距」与「行距」都收敛成**两个常量**，所有 addXxxRow 一律走它们，
    // 不再各处写 8/10/12/14 的魔数（那正是「四个卡片边距不一致」的来源）。

    /** 卡片内顶部留白（dp）—— 与「字幕排版」卡片对齐后的统一值。 */
    private static final int CARD_PAD_TOP_DP = 6;
    /** 卡片内底部留白（dp）。 */
    private static final int CARD_PAD_BOTTOM_DP = 6;
    /**
     * 【2.3.2 §2.3】行与行之间、分隔线上下的呼吸位（dp）。
     *
     * 行与行之间**不再只靠留白**：{@link #beginRow} 会在除首行之外的每一行上方插一根
     * 1dp 的 {@code colorOutlineVariant} 细分隔线 —— 这才是 Ari 连点三次要的
     * 「卡片内选项的间隔效果」（参考稿是 KernelSU 那种「一张卡里若干行、行间有极细分隔」
     * 的形态，而不是 v1.5 的「一坨没有界限的行」）。
     * 分隔线上下各留本常量这么多，行与行才分得开。
     */
    private static final int ROW_GAP_DP = 4;
    /**
     * 【2.3.2 §2.6】每一行统一的最小高度（dp）。
     *
     * ── 为什么必须有这个常量 ────────────────────────────────────────────
     * §2.6 原话：「每个卡片内上边距以**文字**为准（不要以开关上边缘为准算距离），
     * 与字幕排版上面距离一致」。
     * v1.5 的行高是「被内容撑开」的：开关行被 32dp 的 MaterialSwitch 撑高、文字在
     * 垂直居中 ⇒ 文字顶边比「纯文字行」低 6dp；分段行只有文字 ⇒ 顶边就是行顶边。
     * 于是四张卡片首行的文字起始位置各不相同 —— 这正是 §2.6 报的观感。
     * 现在**所有行**都钉一个 40dp 的最小高度并垂直居中 ⇒ 「首行文字顶边」
     * 在任何卡片里都等于 {@code CARD_PAD_TOP_DP + (40 - 文字高)/2}，逐卡一致。
     */
    private static final int ROW_MIN_H_DP = 40;
    /**
     * 【2.3.2 §2.4 / §2.5】行末端小字自己的底部留白（dp）。
     *
     * 取 0：§2.4（恢复默认下方提示）与 §2.5（导出/导入 下方）要的都是「底距 = 6dp」，
     * 而卡片自身已有 {@link #CARD_PAD_BOTTOM_DP} = 6dp 的下内边距 —— 两处相加会变成 12dp。
     * 所以小字只负责与上方内容的间距，最底下那 6dp 交给卡片统一收口。
     */
    private static final int HINT_BOTTOM_PAD_DP = 0;
    /** 行内控件（按钮 / 开关）的统一高度（dp）：MD3 按钮下限 40dp。 */
    private static final int CONTROL_H_DP = 40;
    /**
     * 【code 975 §4】小号 tonal 按钮高度（dp）—— 与页头的语言胶囊**完全同高**。
     *
     * 语言胶囊在 XML 里就是 {@code android:layout_height="32dp"}，这里取同值，
     * 让「恢复默认」换上去之后和它一排看过去齐平（Ari §4 的原话：一样大）。
     */
    private static final int SMALL_CONTROL_H_DP = 32;
    /**
     * 【code 975 §5】M3E 标准按钮高度（dp）—— 只给底栏两枚（重启系统界面 / 保存设置）。
     *
     * 48dp 是 MD3 的标准档，也是 XML 里原有的 {@code minHeight}；这里在 Java 侧重申一次，
     * 是为了把「内缩 / 圆角 / 水平内边距」这三样一起按 M3E 口径收口（见 applyM3eButtonMetrics）。
     */
    private static final int M3E_BUTTON_H_DP = 48;

    // ── 【code 974 §1】M3 Expressive 滑块形态 ─────────────────────────────
    //
    // Ari 2026-10-07 指令：「优化滑块呈现形式：M3 Expressive 的粗轨道和竖长手柄。
    // 手柄左侧为 primary，右侧为 secondaryContainer。可拖动改变数值。」
    //
    // 器材全部是 Material Slider 的**公开 API**（不需要自绘控件、也不碰反射）：
    //   · setTrackHeight(int)                —— 公开，把 4dp 细轨改成 16dp 粗轨；
    //   · setTrackActiveTintList(...)        —— 手柄**左侧**（已走过的一段）= colorPrimary；
    //   · setTrackInactiveTintList(...)      —— 手柄**右侧**（还没走到的一段）= colorSecondaryContainer；
    //   · setThumbRadius(int) + setCustomThumbDrawable(Drawable)
    //     —— 后者在 **Slider** 上是 public（只有 BaseSlider 里才是包私有），
    //        传一条 4dp×44dp 的圆角竖条进去就得到「竖长手柄」。
    //
    // ⚠️ 手柄的**实际绘制高度恒等于 2×thumbRadius**：BaseSlider 会把自定义手柄按
    //    scale = 2r / max(手柄固有宽, 固有高) 等比缩放进 2r×2r 的方框
    //    （见 BaseSlider#adjustCustomThumbDrawableBounds 的字节码：两个 bounds 都按它算）。
    //    所以「44dp 高」只能由 setThumbRadius(22dp) 换来，竖条的宽度则按固有比例 = 4dp。
    // ⚠️ 调用顺序不能反：**先 setThumbRadius 再 setCustomThumbDrawable** ——
    //    缩放比例是设置手柄那一刻用当时的 thumbRadius 算的，反了就会按默认半径 10dp
    //    算出一个小一圈的手柄。
    // ⚠️ 别指望 app:thumbWidth / app:thumbHeight：Material 1.9.0 的 Slider 里
    //    **没有**这两个属性（只有圆形的 thumbRadius），本项目也不升级 Material（离线构建）。
    /** M3E 粗轨道高度（dp）。 */
    private static final int SLIDER_TRACK_H_DP = 16;
    /** M3E 竖长手柄宽度（dp）—— 只表达「手柄固有宽高比」，实际尺寸由 thumbRadius 推。 */
    private static final int SLIDER_THUMB_W_DP = 4;
    /** M3E 竖长手柄高度（dp）—— 必须与 setThumbRadius(本值 / 2) 配对。 */
    private static final int SLIDER_THUMB_H_DP = 44;

    // ── 【code 974 §2】卡内去掉 1dp 分隔线之后的等价留白 ─────────────────
    //
    // Ari 2026-10-07 指令：「参考图片优化卡片之间、卡片内选项、卡片内上下端的间距和
    // 呈现方式（不要直接用分割线之间分开功能选项）」。参考稿是 LSPosed 那种
    // 「一张卡里若干行、行间没有任何线、全靠留白分开」的形态。
    //
    // 旧版每行上方插一根「1dp 线 + 上下各 ROW_GAP_DP(4dp)」⇒ 实际留白 9dp。
    // 现在整根线删掉、改插一个**透明留白**占位，总留白与旧版逐像素一致
    // （Ari 在本次确认里明确选了「去线，留白维持现状」），只是不再有可见的分隔线。
    /** 卡内行间纯留白高度（dp）= 旧版的「1dp 线 + 上下各 4dp 呼吸位」。 */
    private static final int ROW_SPACER_DP = 9;

    // ── 状态 ──────────────────────────────────────────────────────────
    private ConfigStore mStore;
    /** 已保存态（磁盘现状）。 */
    private SubtitleConfig mSaved;
    /** 草稿态（界面绑定对象，PRD §三「预览草稿态」）。 */
    private SubtitleConfig mDraft;
    /** 当前界面语言（由 {@code ui_language} 解析而来）。 */
    private UiLang mLang;
    /** 探测结果；null = 还在探测中。 */
    private ScopeProbe.Result mProbe;
    /**
     * 【code 975】本次打开设置页以来，是否**曾经**探测到宿主 DLsiteSound。
     *
     * <p>用途见 {@link #renderStatusCard()} 里的 {@code hostAuthorized}：
     * 宿主在后台被系统降级/冻结时，偶尔会来不及应答（或来不及被唤醒），
     * 单次超时就翻成「模块未激活」是 Ari 报的那个「经常检测不到程序在运行」。
     * 只要本会话内成功过一次，就认为「模块确实装在宿主里且作用域已勾选」这一**事实**成立，
     * 后续偶发超时不再降级；重新打开设置页（新 Activity）会重置，所以用户真去 LSPosed
     * 取消勾选后，重进本页仍会正确显示「未激活」。
     */
    private boolean mHostSeenOnce;
    /** 程序性回灌控件值时置位，避免「一刷新就变脏」。 */
    private boolean mSyncing;
    /** 回收 {statusbar_button_tint} 用：上次应用到状态栏开关行的 tint（避免每次 renderStatusCard 重复改）。 */
    private int mAppliedStatusbarTint = 0;

    // ── 视图 ──────────────────────────────────────────────────────────
    /** 【2.3.2】根 CoordinatorLayout —— 页面大底色写在这里（夜间比 MD3 surface 提亮一档）。 */
    private View mRoot;
    /** 【2.3.2 §2.7】吸顶区容器：底色必须与大底完全一致，否则吸顶时两侧会显出「卡片色边条」。 */
    private View mAppBar;
    /** 【2.3.2 §2.7】吸底栏容器：同样与大底同色。 */
    private View mBottomBar;
    private MaterialCardView mStatusCard;
    private ImageView mStatusIcon;
    private TextView mStatusTitle;
    private TextView mStatusSubtitle;
    private TextView mStatusGuide;
    /** 【2.3.2 ⑤】语言胶囊（自绘 LinearLayout），点击整块都可点。 */
    private View mLanguageButton;
    /** 胶囊里显示当前语言短名（简中 / 繁中 / EN）的那个 TextView。 */
    private TextView mLanguageText;
    private MaterialButton mSaveButton;
    private MaterialButton mRestartButton;
    private LinearLayout mPreviewLines;
    private View mPreviewBackdrop;
    /** 【2.3.2 ③】悬浮窗面板层（半透明渐变），夹在壁纸层与字幕层之间。 */
    private View mPreviewPanel;
    /** 预览卡容器 —— v1.6 起是普通 FrameLayout（MaterialCardView 会顶掉 setBackground）。 */
    private View mPreviewCard;
    private TextView mHeaderSubtitle;
    private LinearLayout mGroupMain;
    private LinearLayout mGroupInactive;
    private LinearLayout mGroupTypo;
    private LinearLayout mGroupMisc;
    /** 节标题（在卡片外，参考稿口径）—— 语言切换时随文案重刷。 */
    private TextView mTitlePreview;
    private TextView mTitleMain;
    private TextView mTitleInactive;
    private TextView mTitleTypo;
    private TextView mTitleMisc;

    // ── 行控件（重建行后重新赋值；用于 syncWidgets 回灌）────────────────
    private SliderRow mRowStrength;
    private SliderRow mRowShadowRadius;
    private SliderRow mRowHighlight;
    private SliderRow mRowScale;
    private SliderRow mRowBlurRadius;
    private SliderRow mRowInactiveScalePct;
    private SliderRow mRowLineSpacing;
    private SliderRow mRowWrapSpacing;
    private SwitchRow mRowBlurEnabled;
    private SwitchRow mRowInactiveScaleEnabled;
    private SwitchRow mRowStatusbar;
    /** 【2.2.7 / code 979】「其他」卡片里的「调试日志」开关行。 */
    private SwitchRow mRowDebugLog;
    /** 【2.2.9】「液态玻璃」开关行（「其他」卡片）。 */
    private SwitchRow mRowLiquidGlass;
    /** 【2.2.11b】「模糊强度」滑条行（只在液态玻璃开启时显示）。 */
    private SliderRow mRowLiquidGlassBlur;
    /** 「模糊强度」滑条下方的常驻说明小字（与滑条同显隐）。 */
    private TextView mHintLiquidGlassBlur;
    private SegmentedRow mRowAlign;
    private ColorSwatchRow mSwatchSubtitle;
    private ColorSwatchRow mSwatchShadow;
    private ColorSwatchRow mSwatchPanelColor;
    /** 【2.2.9】「悬浮窗颜色」行的标签与容器：液态玻璃开启时整行置灰用。 */
    private TextView mPanelColorLabel;
    private LinearLayout mPanelColorBody;

    // ── 【2.2.11】预览用"背景小位图"（见 applyPreviewPanel 的液态玻璃分支）──
    /** 由预览卡的壁纸渐变化成的 64×64 位图；null = 还没建。 */
    private Bitmap mPreviewBackdropBmp;
    /** 上面那张位图的均值亮度（0..1），供玻璃选自适应霜面。 */
    private float mPreviewBackdropLum = 0.5f;
    /** 烤图时用的壁纸颜色指纹（颜色没变就不重建位图）。 */
    private int mPreviewBackdropSeed = Integer.MIN_VALUE;
    /** 【2.2.11b】按「模糊强度」软化后的壁纸小位图（演示滑条效果用）与其对应的强度值。 */
    private Bitmap mPreviewBackdropBlurBmp;
    private int mPreviewBackdropBlurPct = -1;
    /**
     * 【2.2.12】预览用的玻璃 Drawable 实例（**复用**，不每次重建 —— 重建会让面板闪一帧空背景）。
     * 与其创建时用的圆角一并记下（圆角变了才重建）。
     */
    private LiquidGlassDrawable mPreviewGlass;
    private int mPreviewGlassRadius = -1;
    /** 状态栏字幕功能开关行（SystemUI 未授权时要整行置灰，故保留引用）。 */
    private LinearLayout mStatusbarRowRoot;
    private TextView mHintMain;
    private TextView mHintInactive;
    private TextView mHintTypo;

    /** 配置导出 / 导入（SAF；PRD §FR-09 的备份口径）。 */
    private final ActivityResultLauncher<String> mExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri != null) {
                            exportConfigTo(uri);
                        }
                    });
    private final ActivityResultLauncher<String[]> mImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importConfigFrom(uri);
                }
            });

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * PRD §八 NFR-03 / v1.4 §3.11：设置页 UI **固定系统最小字号与显示大小**（fontScale = 1.0、
     * densityDpi = 系统强制密度），不让系统「显示大小 / 字体大小」把三组参数行挤到疯狂换行、按钮错位。
     *
     * ─────────────────────────────────────────────────────────────────────
     * v1.3 只钉了 fontScale，「显示大小」（density）仍然跟随 ⇒ 用例 3.11 失败。
     * 本版在 fontScale 之外再尝试钉 densityDpi，取值来源是 {@link #readForcedDensityDpi()}：
     *
     * · **读到了才钉**。`display_density_forced` 是 Android 在「设置 → 显示 → 显示大小」
     *   真的被调过之后才写进 {@link Settings.Secure} 的（默认档位下这个键往往不存在）。
     *   读不到就**照旧跟随系统** —— 这是**有意保留的降级**：页面会等比缩放、字号也跟着变，
     *   但行形态（标签 + 控件 + 小字）是代码构建的流式布局，最坏情况只是换行多一点，
     *   不会像「绝对定位」那样错位。验收口径就是「排版不疯狂换行」（§3.11 原话）。
     * · ⚠️ 读这个键**不需要任何权限**（它是 Secure 表里少见的非敏感项），但也**不是公开 API**，
     *   所以整个读取包在 try/catch 里，任何异常/空值都退化成「不钉」。
     * · 钉 densityDpi 会连带把 {@code density} 与 {@code scaledDensity} 一起重算
     *   （见 {@link #applyPinnedMetrics(Configuration)}），否则 dp→px 与 sp→px 会脱节，
     *   控件尺寸和字号会对不上。
     * ─────────────────────────────────────────────────────────────────────
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration cfg = new Configuration(newBase.getResources().getConfiguration());
        cfg.fontScale = 1.0f;
        int forcedDpi = readForcedDensityDpi(newBase);
        if (forcedDpi > 0) {
            // 只有真的读到系统强制密度才钉；读不到保持原密度（降级：等比缩放但不崩排版）
            cfg.densityDpi = forcedDpi;
        }
        // 【2.3.1 §1.18–1.20】用 applyOverrideConfiguration 把「钉住的口径」写进
        // Activity 自己的 Resources，而**不是**只 createConfigurationContext 一个子 Context。
        //
        // ── 为什么必须换写法（2.3.0 的真机症状）────────────────────────────
        // 旧写法是 `super.attachBaseContext(newBase.createConfigurationContext(cfg))`：
        //   · 子 Context 的 Configuration 确实被钉住了，但 **Activity 自己的 Resources**
        //     （也就是 setContentView / getResources() 用的那个）仍然继承 BaseContext 的
        //     metrics —— 实测「显示大小」拉大后整页仍等比放大、语言胶囊被撑宽溢出屏幕。
        //   · createConfigurationContext 也不可靠：部分 ROM（ColorOS）会忽略传入的
        //     densityDpi，或把 density/scaledDensity 算成另一套值 ⇒ dp→px 与 sp→px 脱节。
        // applyOverrideConfiguration 是官方给「改写本 Activity 资源配置」的入口，
        // 在 attachBaseContext 里调用，能同时作用于 BaseContext 与 Activity 自身的 Resources。
        // ⚠️ 必须在 super.attachBaseContext **之前**调用（之后调用会抛
        //    IllegalStateException: Resources already created）。
        try {
            applyOverrideConfiguration(cfg);
        } catch (Throwable t) {
            // 个别 ROM 上已被 attach 过会抛，保守降级回 createConfigurationContext
            try {
                super.attachBaseContext(newBase.createConfigurationContext(cfg));
                return;
            } catch (Throwable ignored) {
            }
        }
        super.attachBaseContext(newBase);
        // ⚠️ 再钉一次 DisplayMetrics：个别 ROM 在 attach 之后会用 BaseContext 的
        //    metrics 覆盖 override 结果，这一步是「覆盖它的覆盖」（见方法注释）。
        applyPinnedMetrics(cfg);
    }

    /**
     * 读系统「显示大小」的强制密度（{@code display_density_forced}）。
     *
     * @return 有效的 densityDpi；读不到 / 值不合法 / 抛异常一律返回 -1（= 不钉）
     */
    private static int readForcedDensityDpi(Context ctx) {
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), "display_density_forced");
            if (raw == null || raw.trim().isEmpty()) {
                return -1;
            }
            int dpi = Integer.parseInt(raw.trim());
            // 合理性闸：低于 72dpi 或高于 1000dpi 的值不可能来自真实档位，宁可不钉也别把页面搞坏
            return (dpi < 72 || dpi > 1000) ? -1 : dpi;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把「钉住的 fontScale / densityDpi」重算进当前 Resources 的 DisplayMetrics。
     *
     * ⚠️ 顺序：**先 super 再改 metrics**（在 {@link #onConfigurationChanged} 里），
     * 因为 super 内部会用新配置重排 / 重刷主题，我们改 metrics 是「覆盖它的结果」。
     * 若反过来，super 会把我们钉的值再覆盖回去，等于没钉。
     */
    private void applyPinnedMetrics(Configuration cfg) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (dm == null || cfg.densityDpi <= 0) {
                return;
            }
            // 三者必须同源重算，否则 dp→px 与 sp→px 会脱节（控件尺寸与字号对不上）
            dm.density = cfg.densityDpi / 160f;
            dm.scaledDensity = dm.density * cfg.fontScale;
            dm.densityDpi = cfg.densityDpi;
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.4 §3.11②：接管这些配置变化，**不重建 Activity**。
     *
     * ⚠️ 这里刻意**不含 uiMode**：深浅色切换（DayNight）必须让 Activity 重建一次，
     * 重建后主题会重新解析 colorSurface 等色槽，页面才会真正换到夜间配色。
     * 若接管 uiMode，就得自己调 {@code recreate()} 或手动重刷所有色槽，得不偿失。
     *
     * 接管这些之后，系统的「字体大小 / 显示大小」变了不会重建，但 Resources 的
     * DisplayMetrics 会被系统刷新 ⇒ 必须在这里把钉住的值**再钉回去**。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // ① 先把新配置交给父类（它会重刷 Resources 与主题）
        super.onConfigurationChanged(newConfig);
        // ② 再用「钉住的口径」覆盖回来：字号恒 1.0，密度优先取系统强制档
        Configuration pinned = new Configuration(newConfig);
        pinned.fontScale = 1.0f;
        int forcedDpi = readForcedDensityDpi(this);
        if (forcedDpi > 0) {
            pinned.densityDpi = forcedDpi;
        } else {
            // 没读到强制档就用父类刷完之后的当前值，避免把密度意外改成 0
            pinned.densityDpi = getResources().getDisplayMetrics().densityDpi;
        }
        applyPinnedMetrics(pinned);
        // ③ 色板 / 预览的观感与密度相关，重刷一次（语言与草稿值都不动）
        refreshCardTints();
        updatePreview();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // PRD §八 NFR-01 / v1.4 §1.1.2：动态取色跟随系统壁纸（Tonal Spot，Android 12+；
        // 低版本自动回落主题里的兜底色）。
        // ⚠️ 必须在 setContentView **之前**调用：applyToActivityIfAvailable 是给
        //    Activity 的 Theme 打补丁，晚于 setContentView 的话，已经 inflate 出来的视图
        //    会先按旧主题解析一遍属性（后续虽然是动态的，但一些 inflate 期固化的值会留在旧色上）。
        try {
            DynamicColors.applyToActivityIfAvailable(this);
        } catch (Throwable ignored) {
        }
        setContentView(R.layout.activity_settings);

        mStore = ConfigStore.get(this);
        mSaved = mStore.load();
        // 滑块能表达的精度是固定的（倍数 0.1 / 行距 0.5dp）：进门先把两侧都对齐到刻度，
        // 否则「打开设置页 → 直接保存」会把 1.05 之类的历史值悄悄改写，且刚进门就是脏态。
        quantize(mSaved);
        mDraft = mSaved.copy();
        mLang = UiLang.fromConfigValue(mDraft.uiLanguage);

        bindViews();
        refreshCardTints();
        buildRows();
        syncWidgets();
        applyStaticTexts();

        if (mStore.hasSavedConfig() && mStore.loadResetHappened()) {
            // PRD §12 E-03：打开时检测到配置损坏 → 明确提示「配置已重置」
            toast(Strings.LOAD_RESET.get(mLang));
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        renderStatusCard();
        startProbe();
    }

    /** 把只由滑块表达的浮点参数对齐到滑块的刻度。 */
    private static void quantize(SubtitleConfig c) {
        c.activeScale = Math.round(c.activeScale * 10f) / 10f;
        c.lineSpacingDp = Math.round(c.lineSpacingDp * 2f) / 2f;
        c.wrapExtraSpacingDp = Math.round(c.wrapExtraSpacingDp * 2f) / 2f;
    }

    /** 语言切换 / 重建行之后必须调一次，把静态文案与状态卡重新刷一遍。 */
    private void applyStaticTexts() {
        setTitle(Strings.PAGE_TITLE.get(mLang));
        if (mHeaderSubtitle != null) {
            mHeaderSubtitle.setText(Strings.HEADER_SUBTITLE.get(mLang));
        }
        mSaveButton.setText(Strings.SAVE.get(mLang));
        if (mRestartButton != null) {
            mRestartButton.setText(Strings.RESTART_SYSUI.get(mLang));
        }
        // §1.1.1 / §6.4：胶囊可见文字 = **当前语言的短名**（简中/繁中/EN），
        // 三字符以内才能塞进 32dp 高的胶囊（长名会被挤成省略号）。
        // 「跟随系统」时短名是**解析后**那个语言的短名 —— 胶囊要表达「当前实际在用哪种语言」。
        // 【2.3.2 ⑤】文字落在胶囊内部独立的 TextView 上（与图标是两个兄弟 View），
        // 因此不可能再出现「图标把文字吃掉」的现象。
        if (mLanguageText != null) {
            mLanguageText.setText(mLang.nativeShortName());
        }
        // 完整语义（含「跟随系统 · 简体中文」）走无障碍朗读，不占用可见空间
        if (mLanguageButton != null) {
            mLanguageButton.setContentDescription(Strings.LANGUAGE.get(mLang) + ": "
                    + (mLang == UiLang.SYSTEM
                    ? Strings.FOLLOW_SYSTEM.get(mLang) + " · " + mLang.resolve().nativeName()
                    : mLang.nativeName()));
        }

        // 节标题在**卡片外**（〈页面布局参考〉口径），由这里统一刷文案
        setTitleSafe(mTitlePreview, Strings.PREVIEW_TITLE.get(mLang));
        setTitleSafe(mTitleMain, Strings.GROUP_MAIN.get(mLang));
        setTitleSafe(mTitleInactive, Strings.GROUP_INACTIVE.get(mLang));
        setTitleSafe(mTitleTypo, Strings.GROUP_TYPO.get(mLang));
        setTitleSafe(mTitleMisc, Strings.GROUP_MISC.get(mLang));

        renderStatusCard();
    }

    private static void setTitleSafe(@Nullable TextView tv, String text) {
        if (tv != null) {
            tv.setText(text);
        }
    }

    /**
     * v1.4 §1.1.2 / §3.1.4：把「MD3 分层」落实到每张卡片上。
     *
     * 为什么必须在代码里设、不能写死在 style 里：
     *  · 状态卡要按探测结果**换色**（primaryContainer / errorContainer / …），
     *    一个静态 style 表达不了；
     *  · 动态取色（壁纸 Tonal Spot）是运行时把色槽整体替换掉的，只有走
     *    `?attr/colorSurfaceContainer*` 这类**色槽引用**才能跟着变；
     *    XML 里写死 `@color/xxx` 或在代码里写死十六进制都会让它失效。
     *
     * 分层口径（KernelSU 管理器的质感）：
     *  · 页面底 = colorSurface（奶白 / 夜间 #1C1B1F）
     *  · 分组卡 = colorSurfaceContainerLow（比底**亮一档**，这就是「卡片浮起来」的来源）
     *  · 预览卡 = 由 {@link #applyPreviewPanel()} 画成半透明悬浮窗面板，不在这里上色
     *  · 吸底栏 = colorSurface（与页面同色，只靠一根色槽分隔线区分，不抢视觉）
     */
    private void refreshCardTints() {
        applyPageBackground();
        int cardBg = cardBgColor();
        tintCard(findViewById(R.id.card_main), cardBg);
        tintCard(findViewById(R.id.card_inactive), cardBg);
        tintCard(findViewById(R.id.card_typo), cardBg);
        tintCard(findViewById(R.id.card_misc), cardBg);
        // 状态卡底色由 renderStatusCard() 按状态设，这里不动它（否则会盖掉状态色）
        applyPreviewPanel();
    }

    // ── 【2.3.2 §2.8】页面大底 / 卡片底色的取色口径 ──────────────────────
    //
    // 浅色：页面底 = colorSurface（奶白）、卡片 = colorSurfaceContainerLow（纯白）
    // 夜间：页面底 = colorSurfaceContainerLow、卡片 = colorSurfaceContainerHigh
    //
    // ── 为什么夜间要整体上抬一档 ────────────────────────────────────────
    // MD3 的 colorSurface 在暗色下**极暗**（有动态取色时约 #131316），Ari 的反馈是
    // 「夜间模式的背景还是太黑」；同时卡片只比它亮 5 档 ⇒「卡片也没有底色」。
    // 上抬一档后：底 ≈ #211F26，卡片 ≈ #36343D，两者都提亮、且对比拉到 10 档。
    // ⚠️ 两个模式都**必须取色槽**（不能写死十六进制），否则动态取色会把页面留在旧色上。

    /** 当前是否处于系统夜间模式（DayNight 由 Activity 重建驱动，这里读的是解析后的配置）。 */
    private boolean isNightMode() {
        try {
            return (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 页面大底色（状态栏 / 导航栏 / 吸顶区 / 吸底栏全部与它同色，§3.1.3）。 */
    private int pageBgColor() {
        return isNightMode()
                ? attr(com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFF211F26)
                : attr(com.google.android.material.R.attr.colorSurface, 0xFFFDF8F6);
    }

    /** 分组卡片底色。 */
    private int cardBgColor() {
        return isNightMode()
                ? attr(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF36343D)
                : attr(com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFFF7F2FA);
    }

    /**
     * 【2.3.2 §2.7 / §2.8】把「页面大底色」写到所有**不随内容滚动**的大面积表面上：
     * 根布局、吸顶区（AppBarLayout）、吸底栏，以及系统状态栏 / 导航栏。
     *
     * ── §2.7 的根因 ────────────────────────────────────────────────────
     * Ari 反馈「调整预览窗吸顶后边缘会变成卡片底色，这里使用设置页的大底色就行」。
     * 吸顶区（AppBarLayout）与根布局分别取色，只要两者有一点差异，钉住的那一条就会
     * 在左右两侧显出「另一种底色」；本方法把它们**绑到同一个值**上，差异不可能存在。
     * §3.1.3「状态栏区域不染色」也靠这里落地 —— 状态栏 = 大底色，不是主题色。
     */
    private void applyPageBackground() {
        int page = pageBgColor();
        setBg(mRoot, page);
        setBg(mAppBar, page);
        setBg(mBottomBar, page);
        if (mPreviewLines != null) {
            // 空态（无字幕行）时露出的就是壁纸层，这里不再额外铺色，保持透明。
            mPreviewLines.setBackground(null);
        }
        try {
            android.view.Window w = getWindow();
            if (w != null) {
                w.setStatusBarColor(page);
                w.setNavigationBarColor(page);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setBg(@Nullable View v, int color) {
        if (v != null) {
            v.setBackgroundColor(color);
        }
    }

    /**
     * 【2.3.2 ⑤】吸底栏两枚按钮的显式配色。
     *
     * ⚠️ 为什么不只靠 XML 的 `app:backgroundTint`：`setBackgroundTintList()` 会**覆盖**
     * XML 里那条 `?attr/colorXxx` 引用，而 MD3 动态取色是在主题层替换色槽的 ——
     * 只要显式写一次，之后动态取色 / 深浅色切换都不会再影响它。所以这里刻意**不**写死，
     * 而是每次 {@link #onConfigurationChanged} 都重取一次色槽（见 refreshCardTints 的调用链）。
     */
    private void styleBottomBar() {
        styleTonalButton(mRestartButton, null);
        if (mSaveButton != null) {
            mSaveButton.setBackgroundTintList(colorStateList(
                    attr(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4)));
            mSaveButton.setTextColor(colorStateList(
                    attr(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)));
        }
        // 【code 975 §5】底栏两枚补齐 M3E 度量（14sp / 无内缩 / 20dp 水平内边距 / 全圆角 48dp），
        // 与卡片里的 M3E 控件（滑块粗轨、小号 tonal、对齐分段）收进同一条视觉语言。
        // ⚠️ 必须放在上述 tint 设置**之后** —— applyM3eButtonMetrics 只碰度量与内缩，
        //    不动配色；放在前面也不会被覆盖，但按「先配色后度量」的顺序读起来更清楚。
        applyM3eButtonMetrics(mRestartButton);
        applyM3eButtonMetrics(mSaveButton);
    }

    /**
     * 【2.3.2 §1.1.2】造一枚 MD3 **Tonal**（浅色容器底 + 深色字/图标）按钮。
     *
     * ─────────────────────────────────────────────────────────────────────
     * Ari 在 2026-10-05 code961 测试 1.2 里连点两处按钮问题：
     *   「**重置 / 对齐** 按钮全部异常（上下距离过窄），文字为黑色阅读性低，
     *     需要改成白色（夜间模式按钮内文字改为暗色）」+「导出 / 导入 文字前面需要加图标」。
     *
     * 拍板结论（本轮已与 Ari 确认）：四组按钮统一走 **MD3 浅色 Tonal**
     * —— 与底栏「重启系统界面」同一视觉语言：浅色容器底 + 深色字，
     * 深色模式自动翻成「深容器底 + 浅字」，不存在「黑字压在深底上」的读不清。
     *
     * 🔴 旧实现的真 bug（不要退回）：
     *   v1.5 用 `new MaterialButton(this, null, R.style.Widget_Material3_Button_TonalButton)`
     *   想套 tonal 样式 —— 但第三个参数是 **defStyleAttr（属性）**，传 style 资源进去
     *   解析不到，结果既没拿到 tonal 的浅底、也没拿到它的 onSecondaryContainer 字色，
     *   真机上就渲染成「深底 + 黑字」。正确做法 = 造普通 MaterialButton 后**显式**设
     *   backgroundTint / textColor / iconTint 三条。
     *
     * @param iconRes 可空；给了就放在文字**前面**（§1.1.3 要求导出/导入必须有图标）
     */
    private MaterialButton makeTonalButton(@Nullable Integer iconRes) {
        MaterialButton b = new MaterialButton(this);
        int bg = attr(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        int fg = attr(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B);
        b.setBackgroundTintList(colorStateList(bg));
        b.setTextColor(colorStateList(fg));
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setStrokeWidth(0);
        b.setMinHeight(dp(CONTROL_H_DP));
        b.setMinimumHeight(dp(CONTROL_H_DP));
        b.setCornerRadius(dp(CONTROL_H_DP / 2f));
        b.setPadding(dp(16), 0, dp(16), 0);
        if (iconRes != null) {
            b.setIconResource(iconRes);
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            b.setIconPadding(dp(6));
            b.setIconSize(dp(18));
            b.setIconTint(colorStateList(fg));
        }
        return b;
    }

    /**
     * 【code 975 §4 + §5】M3 Expressive 的**小号** tonal 按钮（XS/S 档）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * Ari 2026-10-07 晚指令：
     *   · §4「重置按钮调整的和**语言切换按钮**一样大，现在的重置按钮占的位置有点太大了」；
     *   · §5「查看页面内还有什么其他按钮或者控件可以修改为 M3E 形态 …一并改」。
     *
     * 语言胶囊（{@code R.id.language_button}）的实测度量就是本方法的规格：
     *   高 32dp / 全圆角 16dp / 文字 12sp / 图标 14dp / 水平内边距 8~10dp。
     * 而 974 的 tonal 按钮是 40dp 高 + 16dp 图标 —— 在每张卡片末行确实显得过大。
     *
     * ⚠️ 只用于「恢复默认」这类**行末辅助按钮**；导出/导入 与底栏仍是标准档，
     *    因为它们在本轮没有被点名，且 40dp 已满足 MD3 的触达下限。
     * ⚠️ 32dp 低于 MD3 的 40dp 建议高度，但它与页头语言胶囊完全对齐，
     *    是 Ari 明确要的观感；点击热区仍靠父行的 {@link #ROW_MIN_H_DP} 补足。
     */
    private MaterialButton makeSmallTonalButton(@Nullable Integer iconRes) {
        MaterialButton b = new MaterialButton(this);
        int bg = attr(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        int fg = attr(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B);
        b.setBackgroundTintList(colorStateList(bg));
        b.setTextColor(colorStateList(fg));
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setStrokeWidth(0);
        b.setMinHeight(dp(SMALL_CONTROL_H_DP));
        b.setMinimumHeight(dp(SMALL_CONTROL_H_DP));
        b.setCornerRadius(dp(SMALL_CONTROL_H_DP / 2f));
        b.setPadding(dp(10), 0, dp(12), 0);
        if (iconRes != null) {
            b.setIconResource(iconRes);
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            b.setIconPadding(dp(4));
            b.setIconSize(dp(14));
            b.setIconTint(colorStateList(fg));
        }
        return b;
    }

    /**
     * 【code 975 §5】把一枚**底栏**按钮套上 M3 Expressive 度量。
     *
     * <p>底栏两枚（重启系统界面 / 保存设置）本来就是 48dp + 24dp 全圆角（XML 里写的），
     * 但缺了 M3E 的三样：**更大的水平内边距**、**更大的字号**、**去掉控件内缩**。
     * 这里统一补齐，让它们与滑块、与卡片里的 M3E 控件是同一条视觉语言。
     */
    private void applyM3eButtonMetrics(@Nullable MaterialButton b) {
        if (b == null) {
            return;
        }
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setStrokeWidth(0);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinHeight(dp(M3E_BUTTON_H_DP));
        b.setMinimumHeight(dp(M3E_BUTTON_H_DP));
        b.setCornerRadius(dp(M3E_BUTTON_H_DP / 2f));
    }

    /** 见 {@link #styleBottomBar()}：把一枚已有按钮改造成 tonal 观感。 */
    private void styleTonalButton(@Nullable MaterialButton b, @Nullable Integer iconRes) {
        if (b == null) {
            return;
        }
        int bg = attr(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        int fg = attr(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B);
        b.setBackgroundTintList(colorStateList(bg));
        b.setTextColor(colorStateList(fg));
        if (iconRes != null) {
            b.setIconResource(iconRes);
            b.setIconTint(colorStateList(fg));
        }
    }

    /**
     * 带「禁用态」的 ColorStateList：MD3 的禁用态是**同色 + 38% 不透明度**。
     *
     * ⚠️ 必须显式给禁用态，否则配置不到位时会渲染成完全透明 ——
     * Ari 在 code961 的录屏里看到的「导出 / 导入 位置一片空白」就是这个现象
     * （当时整页处于「模块未激活 ⇒ 全部控件 disabled」状态，按钮却直接不见了）。
     */
    private static android.content.res.ColorStateList colorStateList(int color) {
        int disabled = (Math.round(255 * 0.38f) << 24) | (color & 0x00FFFFFF);
        return new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}},
                new int[]{disabled, color});
    }

    private void tintCard(@Nullable View card, int color) {
        if (card instanceof MaterialCardView) {
            ((MaterialCardView) card).setCardBackgroundColor(color);
            ((MaterialCardView) card).setStrokeWidth(0);
            try {
                ((MaterialCardView) card).setCardElevation(0f);
            } catch (Throwable ignored) {
            }
        }
    }

    private void bindViews() {
        mRoot = findViewById(R.id.settings_root);
        mAppBar = findViewById(R.id.settings_appbar);
        mBottomBar = findViewById(R.id.bottom_bar);
        mStatusCard = findViewById(R.id.status_card);
        mStatusIcon = findViewById(R.id.status_icon);
        mStatusTitle = findViewById(R.id.status_title);
        mStatusSubtitle = findViewById(R.id.status_subtitle);
        mStatusGuide = findViewById(R.id.status_guide);
        mLanguageButton = findViewById(R.id.language_button);
        mLanguageText = findViewById(R.id.language_text);
        mSaveButton = findViewById(R.id.save_button);
        mRestartButton = findViewById(R.id.restart_button);
        mPreviewLines = findViewById(R.id.preview_lines);
        mPreviewBackdrop = findViewById(R.id.preview_backdrop);
        mPreviewPanel = findViewById(R.id.preview_panel);
        mPreviewCard = findViewById(R.id.preview_card);
        mHeaderSubtitle = findViewById(R.id.header_subtitle);
        mGroupMain = findViewById(R.id.group_main);
        mGroupInactive = findViewById(R.id.group_inactive);
        mGroupTypo = findViewById(R.id.group_typo);
        mGroupMisc = findViewById(R.id.group_misc);
        // 节标题在**卡片外**（参考稿口径），由 applyStaticTexts 刷文案
        mTitlePreview = findViewById(R.id.title_preview);
        mTitleMain = findViewById(R.id.title_main);
        mTitleInactive = findViewById(R.id.title_inactive);
        mTitleTypo = findViewById(R.id.title_typo);
        mTitleMisc = findViewById(R.id.title_misc);

        mLanguageButton.setOnClickListener(v -> showLanguageDialog());
        mSaveButton.setOnClickListener(v -> doSave());
        mRestartButton.setOnClickListener(v -> confirmRestartSystemUi());

        // 【2.3.2】吸底栏按钮也要「夜间自动换深浅」：重启按钮走 tonal、保存按钮走 primary，
        // 两枚的 tint 都显式写死（不依赖样式继承），见 styleBottomBar()。
        styleBottomBar();

        // 【code 976 §5】底部彩蛋：滚到最底后继续上拉 ⇒ 淡入一行居中小字（SMILE! :D）。
        setupScrollEgg();

        // 【2.1.4 真吸底】底栏现在是**真实占位**的兄弟节点（垂直 LinearLayout 的第二个孩子），
        // 滚动区高度 = 剩余空间 ⇒ 滚到底时最后一行必然完整露在栏上方。
        // ⚠️ 这里原先有一段「量底栏高 → 写进滚动内容 paddingBottom」的让位补丁，已整体删除：
        //    它依赖「底栏先量到最终高度」+「滚动区可滚动高度与预期一致」两个前提，
        //    任一不成立最后一行就会被压在栏下且滚不出来（Ari 2026-10-06 截图实证「拉不到底」）。
        //    改成真实占位后这两个前提都不再需要。
    }

    // ==================================================================
    // FR-01 状态卡
    // ==================================================================

    private void startProbe() {
        ScopeProbe.probe(this, ScopeProbe.DEFAULT_WINDOW_MS, result -> {
            mProbe = result;
            if (result.hostAuthorized) {
                // 【code 975】记下「本会话内确实见过宿主」——后续偶发超时不降级（见字段注释）。
                mHostSeenOnce = true;
            }
            // 【code 995】诊断级 → 走 LogGate（探测每 3s 一轮，常开会刷屏）。
            LogGate.debug(TAG, "status probe -> " + result);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            renderStatusCard();
            if (result.hostAuthorized) {
                // 自愈补推：宿主进程现在活着，把**已保存**的配置再广播一次，
                // 让宿主把 remote prefs 持久化补上（用户可能在宿主未运行时保存过）。
                mStore.pushSavedConfigToHooks();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // PRD FR-07 规则（STA-02）：重新在前台打开设置页时再检测一次授权状态 ——
        // 用户可能刚去启动了 DLsiteSound / 重启了系统界面，回到本页要能看到新状态。
        // 首次探测由 onCreate 负责（mProbe == null 时不重复触发）。
        if (mProbe != null) {
            startProbe();
        }
    }

    /**
     * 四态渲染（PRD §FR-01 规则 1/2/3/4 + §12 E-01/E-02/E-10 + §16.2 BUG-01）。
     *
     * 底色**只用 MD3 色槽**（禁止硬编码十六进制，PRD §15.2）：
     * 探测中 = {@code colorSurfaceVariant}，已激活 = {@code colorPrimaryContainer}，
     * 未运行 = {@code colorTertiaryContainer}，未激活 = {@code colorErrorContainer}。
     *
     * ─────────────────────────────────────────────────────────────────────
     * v1.4 返工（§16.2 BUG-01 / 用例 1.2）：「已激活」的判据不再只看授权位。
     * 旧的 Q-6 判别态用「SystemUI 有响应 + 宿主没响应」去猜「宿主没运行」，
     * 猜不准 —— 宿主被划掉后进程往往还在，PING 照样收到 PONG，于是 1.2 一直判成已激活。
     * 现在宿主在 PONG 里直接回一个存活位（{@code ScopeProbe.Result#hostAlive}），
     * 状态卡按它做三分支，判据见方法体里的 {@code active} / {@code hostNotRunning}。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void renderStatusCard() {
        if (mStatusTitle == null) {
            return;
        }
        boolean probing = mProbe == null;
        // 【code 975】`|| mHostSeenOnce`：宿主在后台被降级时偶发不应答，不应把状态卡打回
        // 「模块未激活」（Ari 报的「经常检测不到程序在运行」）。本会话成功过一次即认账，
        // 重开本页会重置 ⇒ 真取消勾选仍能正确显示未激活。详见字段注释。
        boolean hostAuthorized = !probing && (mProbe.hostAuthorized || mHostSeenOnce);
        /**
         * 宿主**当前真的在用**（播放页在前台 / 正在播放）。
         *
         * ⚠️ 这个位与 {@code hostAuthorized} 是两件事，也是用例 1.2 的胜负手：
         * 用户把 DLsiteSound 从后台划掉之后，宿主进程**可能还活着**，
         * {@code installResponder(tag="host")} 照样会答 PONG，
         * {@code hostAuthorized} 仍然是 true —— 只看授权位就会把「划掉了」显示成「已激活」。
         * 存活位由宿主在 PONG 里带上来（见 {@code ConfigBus.EXTRA_HOST_ALIVE}），
         * 缺省 {@code true}（老版本模块不带这个 extra，必须保持「已激活」，不能误降级）。
         */
        boolean hostAlive = !probing && mProbe.hostAlive;
        boolean sysUiActive = !probing && mProbe.systemUiAuthorized;

        // ── 三分支（PRD §16.2 BUG-01）──────────────────────────────────
        // ⚠️ 分支一必须先与 hostAuthorized 相与，不能单用 hostAlive：
        //    hostAlive 在「宿主未授权」时**不会被写成 false**（它只在收到宿主 PONG 时才赋值，
        //    字段缺省就是 true），单用 hostAlive 会让「DLsiteSound 作用域没勾」被判成「已激活」，
        //    用例 1.4 反而退化。所以「已激活」= 已授权 **且** 存活。
        //  · 分支一 active          = hostAuthorized && hostAlive
        //  · 分支二 hostNotRunning  = hostAuthorized && !hostAlive
        //  · 分支三（else）         = !hostAuthorized  ⇒ 未激活 / 未授权引导（1.4 靠这条）
        boolean active = hostAuthorized && hostAlive;
        boolean hostNotRunning = hostAuthorized && !hostAlive;

        int bg;
        int on;
        if (probing) {
            bg = attr(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC);
            on = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F);
            mStatusTitle.setText(Strings.MODULE_UNKNOWN.get(mLang));
            mStatusIcon.setImageResource(R.drawable.ic_settings_status_unknown);
        } else if (active) {
            bg = attr(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF);
            on = attr(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D);
            mStatusTitle.setText(Strings.MODULE_ON.get(mLang));
            mStatusIcon.setImageResource(R.drawable.ic_settings_status_on);
        } else if (hostNotRunning) {
            // 分支二：已授权但当前没在用 ⇒ 「DLsiteSound 未运行」。
            // 用 tertiary 色（不是 error 色）—— 这不是故障，只是一个可恢复的中间态。
            bg = attr(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4);
            on = attr(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D);
            mStatusTitle.setText(Strings.HOST_NOT_RUNNING.get(mLang));
            mStatusIcon.setImageResource(R.drawable.ic_settings_status_unknown);
        } else {
            bg = attr(com.google.android.material.R.attr.colorErrorContainer, 0xFFF9DEDC);
            on = attr(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B);
            mStatusTitle.setText(Strings.MODULE_OFF.get(mLang));
            mStatusIcon.setImageResource(R.drawable.ic_settings_status_off);
        }
        mStatusCard.setCardBackgroundColor(bg);
        mStatusTitle.setTextColor(on);
        mStatusSubtitle.setTextColor(withAlpha(on, 0.8f));
        mStatusIcon.setColorFilter(on);

        String sysUiText = probing
                ? Strings.PROBING.get(mLang)
                : (sysUiActive ? Strings.SYSTEMUI_ON.get(mLang) : Strings.SYSTEMUI_OFF.get(mLang));
        mStatusSubtitle.setText(Strings.STATUS_SUBTITLE.format(mLang,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, sysUiText));

        // 引导文案（PRD §12 E-01 / E-02 / E-10 + Q-6 判别态）—— 按「哪一侧没响应」分
        // 可判别的引导，而不是笼统报一句「未激活」。
        if (probing) {
            mStatusGuide.setVisibility(View.GONE);
        } else if (hostNotRunning) {
            mStatusGuide.setText(Strings.HOST_NOT_RUNNING_HINT.get(mLang));
            mStatusGuide.setVisibility(View.VISIBLE);
        } else if (!hostAuthorized) {
            mStatusGuide.setText((sysUiActive ? Strings.GUIDE_UNKNOWN : Strings.GUIDE_OFF).get(mLang)
                    + "\n" + Strings.MODULE_INACTIVE_HINT.get(mLang));
            mStatusGuide.setVisibility(View.VISIBLE);
        } else if (!sysUiActive) {
            mStatusGuide.setText(Strings.GUIDE_SYSTEMUI_OFF.get(mLang));
            mStatusGuide.setVisibility(View.VISIBLE);
        } else {
            mStatusGuide.setVisibility(View.GONE);
        }

        // STA-07：模块未激活（或探测中）时禁用全部可编辑控件与保存按钮。
        // ⚠️ 分支二「DLsiteSound 未运行」**不禁用**（PRD §16.2 BUG-01 第 3 条硬要求）：
        //    页面保持可编辑、保存按钮可用、**不清空任何参数、不禁用任何控件** ——
        //    配置照改照存，宿主启动后由 hook 侧的补推广播生效。
        //    也就是「能不能编辑」只看**授权位**，不看存活位：
        //      · hostAuthorized == true  ⇒ 可编辑（无论此刻活不活）
        //      · hostAuthorized == false ⇒ 未激活，禁用（STA-07）
        setControlsEnabled(hostAuthorized);

        // v1.4 §2.1.1.4②：SystemUI 未授权 ⇒ 「状态栏字幕功能」开关**置灰禁用 + 保持关闭态**。
        // ⚠️ 两个前置条件都是**技术上必须**的：
        //   · 探测中（mProbe == null）不能assume未授权，否则页面刚打开的一瞬间开关会闪一下灰；
        //   · 宿主未运行时 SystemUI 的授权状态仍然可信（它跟宿主在不在跑无关，看的是 SystemUI 作用域），
        //     所以这里判据只用 sysUiActive，不看 hostAuthorized / hostAlive。
        boolean sysUiAuthorized = !probing && sysUiActive;
        applyStatusbarSwitchEnabled(sysUiAuthorized);
    }

    /**
     * v1.4 §2.1.1.4：把「SystemUI 未授权 ⇒ 开关置灰 + 保持关闭态」落到控件上。
     *
     * ⚠️ 置灰时**只改 UI、不写 {@code mDraft.statusbarSubtitleEnabled}**（PRD §FR-07 的既有口径）：
     * 存储里那个 true 表达的是「用户没主动关过」，与「现在没权限所以用不了」是两件事。
     * 一旦用户之后去授权了 SystemUI，回到本页开关应自动恢复成 true，而不是被我们记成 false。
     */
    private void applyStatusbarSwitchEnabled(boolean enabled) {
        if (mRowStatusbar == null) {
            return;
        }
        mSyncing = true;
        try {
            if (!enabled) {
                mRowStatusbar.sw.setChecked(false);
            } else {
                mRowStatusbar.sw.setChecked(mDraft.statusbarSubtitleEnabled);
            }
        } finally {
            mSyncing = false;
        }
        mRowStatusbar.sw.setEnabled(enabled);
        if (mStatusbarRowRoot != null) {
            mStatusbarRowRoot.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    /**
     * STA-07：模块未激活 / 探测中时禁用全部可编辑控件与保存按钮，避免用户在
     * 模块没生效的状态下改参数（改了也不会落到悬浮窗）。激活后恢复。
     */
    private void setControlsEnabled(boolean enabled) {
        setGroupEnabled(mGroupMain, enabled);
        setGroupEnabled(mGroupInactive, enabled);
        setGroupEnabled(mGroupTypo, enabled);
        setGroupEnabled(mGroupMisc, enabled);
        if (mSaveButton != null) {
            mSaveButton.setEnabled(enabled);
        }
    }

    /** 递归地把分组内所有子视图置为 enabled / disabled（覆盖滑块/开关/色板/分段按钮/重置按钮）。 */
    private void setGroupEnabled(ViewGroup group, boolean enabled) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (child instanceof ViewGroup) {
                setGroupEnabled((ViewGroup) child, enabled);
            }
            child.setEnabled(enabled);
        }
    }

    // ==================================================================
    // FR-02 语言
    // ==================================================================

    /**
     * 语言选择弹层（v1.4 §6.3 修「什么都没改只切语言 → 返回时弹未保存」）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 语言**不属于「字幕外观参数」**：它只影响设置页 UI 与预览区的示例字幕（FR-02 规则 2），
     * 改了它对悬浮窗没有任何影响。因此它不该把页面变「脏」、更不该逼用户在返回时
     * 面对「保存 / 不保存 / 取消」三选 —— 那三选是给**外观改动**用的。
     *
     * 本版按两件事一起做：
     *  ① {@link #isDirty()} **排除 uiLanguage**（比较的口径是「除语言外的参数」）；
     *  ② 切语言后**立即独立保存**（{@code ConfigStore#saveUiLanguageOnly}）——
     *     只把 ui_language 写进设置页自己的 MODE_PRIVATE prefs，不写 JSON、不发广播、
     *     不覆盖其他参数。于是「切了语言」这件事是**当场落盘**的，再按返回直接退出，
     *     既不弹三选、也不丢语言选择。
     *
     * ⚠️ 顺序很关键：**先独立保存、再改 mDraft**。反过来的话，若此刻已有外观草稿改动，
     *    独立保存期间与后来的全量保存会互相覆盖 ui_language，出现「保存后语言又变回去」。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void showLanguageDialog() {
        final UiLang[] options = {UiLang.SYSTEM, UiLang.ZH_CN, UiLang.ZH_TW, UiLang.EN};
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == UiLang.SYSTEM) {
                labels[i] = Strings.FOLLOW_SYSTEM.get(mLang) + " · " + UiLang.detectSystem().nativeName();
            } else {
                labels[i] = options[i].nativeName();
            }
            if (options[i] == mLang) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(Strings.LANG_DIALOG_TITLE.get(mLang))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    UiLang picked = options[which];
                    if (picked != mLang) {
                        applyLanguage(picked);
                    }
                })
                .setNegativeButton(Strings.CANCEL.get(mLang), null)
                .show();
    }

    /** 切语言：立即独立落盘 + 重建全部行 + 回灌草稿值（不产生脏态，见 {@link #showLanguageDialog()}）。 */
    private void applyLanguage(UiLang picked) {
        // ① 立即独立保存（不写 JSON / 不广播 / 不动其他参数）
        try {
            mStore.saveUiLanguageOnly(picked.configValue());
        } catch (Throwable t) {
            Log.w(TAG, "saveUiLanguageOnly failed: " + t);
        }
        // ② 内存两侧一起对齐，避免「保存后切语言」留下脏态
        mLang = picked;
        mDraft.uiLanguage = picked.configValue();
        mSaved.uiLanguage = picked.configValue();
        // ③ 语言变了 → 所有行的标签/小字都要换语言：整组重建，随后回灌草稿值
        buildRows();
        syncWidgets();
        applyStaticTexts();
    }

    // ==================================================================
    // 行的构建（FR-04 / FR-05 / FR-06）
    // ==================================================================

    /**
     * 重建三组 + 「其他」组的行控件。
     *
     * ⚠️ 语言切换会整体重建；卡**内**不再放节标题（〈页面布局参考〉口径节标题在卡片外，
     * 由 applyStaticTexts 刷 {@link #mTitleMain} 等外部 TextView），所以这里只装行控件。
     */
    private void buildRows() {
        if (mGroupMain == null) {
            return;
        }
        mGroupMain.removeAllViews();
        buildMainGroup(mGroupMain);

        mGroupInactive.removeAllViews();
        buildInactiveGroup(mGroupInactive);

        mGroupTypo.removeAllViews();
        buildTypoGroup(mGroupTypo);

        if (mGroupMisc != null) {
            mGroupMisc.removeAllViews();
            buildMiscGroup(mGroupMisc);
            // 重建后要按最新探测结果重刷一次「状态栏字幕功能」的可用性
            applyStatusbarSwitchEnabled(mProbe != null && mProbe.systemUiAuthorized);
        }
    }

    /**
     * v1.4 §2.1.1：色板**统一 8 预设**，且主字幕 / 阴影 / 悬浮窗三行的列表**完全相同**
     * （白 / 黑 / 绿 / 红 / 橙 / 青 / 紫 / 深蓝）。
     *
     * ⚠️ 顺序是需求里逐字给定的，别按「好看」重排 —— Ari 是按这个顺序记位置的。
     * ⚠️ 阴影色板的第一个也是**黑**（而不是像 v1.3 那样第一个是黑、后面接蓝橙紫棕青），
     *    这是本版的变化点：三行共用一张表，用户不用记「这一行第几个是什么颜色」。
     */
    private int[] commonPalette() {
        return new int[]{
                color(R.color.swatch_white), color(R.color.swatch_black), color(R.color.swatch_green),
                color(R.color.swatch_red), color(R.color.swatch_orange), color(R.color.swatch_cyan),
                color(R.color.swatch_purple), color(R.color.swatch_blue)};
    }

    private String[] commonPaletteNames() {
        return new String[]{
                Strings.COLOR_WHITE.get(mLang), Strings.COLOR_BLACK.get(mLang), Strings.COLOR_GREEN.get(mLang),
                Strings.COLOR_RED.get(mLang), Strings.COLOR_ORANGE.get(mLang), Strings.COLOR_CYAN.get(mLang),
                Strings.COLOR_PURPLE.get(mLang), Strings.COLOR_BLUE.get(mLang)};
    }

    /**
     * v1.4 §3.1.5：悬浮窗颜色色板 = **深蓝黑** + 上面那套 8 色里的后 7 个（白/绿/红/橙/青/紫/深蓝）。
     *
     * ⚠️ 需求原话是「8 预设（深蓝黑/白/绿/红/橙/青/紫/深蓝）」—— 也就是把通用表里的**黑**
     *    换成了**深蓝黑**（默认基色 {@code #0E1420}）。深蓝黑与黑在视觉上极近，
     *    若同时出现会让色板看起来有重复项（NFR-05 要求色板不能只靠颜色区分，重复色名就是 bug）。
     */
    private int[] panelPalette() {
        return new int[]{
                color(R.color.swatch_deep_blue_black), color(R.color.swatch_white),
                color(R.color.swatch_green), color(R.color.swatch_red), color(R.color.swatch_orange),
                color(R.color.swatch_cyan), color(R.color.swatch_purple), color(R.color.swatch_blue)};
    }

    private String[] panelPaletteNames() {
        return new String[]{
                Strings.COLOR_DEEP_BLUE_BLACK.get(mLang), Strings.COLOR_WHITE.get(mLang),
                Strings.COLOR_GREEN.get(mLang), Strings.COLOR_RED.get(mLang), Strings.COLOR_ORANGE.get(mLang),
                Strings.COLOR_CYAN.get(mLang), Strings.COLOR_PURPLE.get(mLang), Strings.COLOR_BLUE.get(mLang)};
    }

    /** FR-04 主字幕 / 活动行调整（6 参数 + 恢复默认）。 */
    private void buildMainGroup(LinearLayout parent) {
        mSwatchSubtitle = addColorRow(parent, Strings.SUBTITLE_COLOR,
                commonPalette(), commonPaletteNames(), () -> mDraft.subtitleColor, v -> {
                    mDraft.subtitleColor = v;
                    markDirty();
                }).swatch;

        mSwatchShadow = addColorRow(parent, Strings.SHADOW_COLOR,
                commonPalette(), commonPaletteNames(), () -> mDraft.shadowColor, v -> {
                    mDraft.shadowColor = v;
                    markDirty();
                }).swatch;

        // §2.1.1：步长 5（不是 1）—— 1% 的差别肉眼不可辨，步长 5 让滑块更容易停在整十位
        mRowStrength = addSliderRow(parent, Strings.SHADOW_STRENGTH,
                SubtitleConfig.SHADOW_STRENGTH_MIN, SubtitleConfig.SHADOW_STRENGTH_MAX, 5,
                v -> Strings.VALUE_PCT.format(mLang, v),
                v -> {
                    mDraft.shadowStrength = v;
                    markDirty();
                });

        mRowShadowRadius = addSliderRow(parent, Strings.SHADOW_RADIUS,
                SubtitleConfig.SHADOW_RADIUS_MIN, SubtitleConfig.SHADOW_RADIUS_MAX, 1,
                v -> Strings.VALUE_PCT.format(mLang, v),
                v -> {
                    mDraft.shadowRadius = v;
                    markDirty();
                });

        // §2.1.1：活动行不透明度 50–100%，步长 5，默认 100%
        mRowHighlight = addSliderRow(parent, Strings.ACTIVE_HIGHLIGHT,
                SubtitleConfig.HIGHLIGHT_MIN, SubtitleConfig.HIGHLIGHT_MAX, 5,
                v -> Strings.VALUE_PCT.format(mLang, v),
                v -> {
                    mDraft.activeHighlight = v;
                    markDirty();
                });

        // 放大倍数：滑块走「×10 的整数刻度」，避免浮点步长累积误差
        mRowScale = addSliderRow(parent, Strings.ACTIVE_SCALE,
                Math.round(SubtitleConfig.ACTIVE_SCALE_MIN * 10),
                Math.round(SubtitleConfig.ACTIVE_SCALE_MAX * 10), 1,
                v -> Strings.VALUE_SCALE.format(mLang, fmtScale(v)),
                v -> {
                    mDraft.activeScale = v / 10f;
                    markDirty();
                });

        mHintMain = addResetRow(parent, Strings.RESET_MAIN, this::resetMainGroup);
    }

    /** FR-05 非活动行字幕调整（开关联动 + 恢复默认）。 */
    private void buildInactiveGroup(LinearLayout parent) {
        mRowBlurEnabled = addSwitchRow(parent, Strings.BLUR_ENABLED, v -> {
            mDraft.inactiveBlurEnabled = v;
            // PRD §FR-05：关闭时滑块**隐藏**（占位收回，不是置灰）
            if (mRowBlurRadius != null) {
                mRowBlurRadius.root.setVisibility(v ? View.VISIBLE : View.GONE);
            }
            markDirty();
        });

        // §2.1.1：模糊 0–8px 步长 0.5、默认 3px。
        // 底层仍是整数档位滑块（0–16 档，1 档 = 0.5px）—— 浮点步长在 Slider 上会有
        // 累积误差（0.5 累加十次得到 4.999999），档位制是既有的、已被验证的做法。
        // ⚠️ 变化点：**读数直接显示 px**（3.0px / 7.5px），不再显示「8 × 0.5px」这种算式。
        mRowBlurRadius = addSliderRow(parent, Strings.BLUR_RADIUS,
                SubtitleConfig.BLUR_STEPS_MIN, SubtitleConfig.BLUR_STEPS_MAX, 1,
                v -> Strings.VALUE_PX.format(mLang, fmtOneDecimal(v * SubtitleStyle.BLUR_PX_PER_STEP)),
                v -> {
                    mDraft.inactiveBlurSteps = v;
                    markDirty();
                });

        mRowInactiveScaleEnabled = addSwitchRow(parent, Strings.INACTIVE_SCALE_ENABLED, v -> {
            mDraft.inactiveScaleEnabled = v;
            if (mRowInactiveScalePct != null) {
                mRowInactiveScalePct.root.setVisibility(v ? View.VISIBLE : View.GONE);
            }
            markDirty();
        });

        // §2.1.1：缩放 50–100% 步长 5 默认 90%
        mRowInactiveScalePct = addSliderRow(parent, Strings.INACTIVE_SCALE_PCT,
                SubtitleConfig.INACTIVE_SCALE_PCT_MIN, SubtitleConfig.INACTIVE_SCALE_PCT_MAX, 5,
                v -> Strings.VALUE_PCT.format(mLang, v),
                v -> {
                    mDraft.inactiveScalePct = v;
                    markDirty();
                });

        mHintInactive = addResetRow(parent, Strings.RESET_INACTIVE, this::resetInactiveGroup);
    }

    /**
     * FR-06 字幕排版（v1.4：**已删「字重」整行**，新增「悬浮窗颜色」）。
     *
     * 删字重的理由（§16.3）：真机测试 2.8 实锤「粗体不生效」，Ari 拍板直接删功能；
     * {@code font_weight} 配置键**保留解析兼容**（老配置读进来不丢字段、不炸），
     * 只是界面不再暴露它 —— 渲染侧仍按它算（见 {@link SubtitleStyle}），
     * 默认值 {@code WEIGHT_SYSTEM} 恰好等价于用户没调过的状态。
     */
    private void buildTypoGroup(LinearLayout parent) {
        mRowAlign = addSegmentedRow(parent, Strings.ALIGN, new String[]{
                Strings.ALIGN_LEFT.get(mLang), Strings.ALIGN_CENTER.get(mLang),
                Strings.ALIGN_RIGHT.get(mLang)}, v -> {
            mDraft.textAlign = alignValueOf(v);
            markDirty();
        });

        // 行距：滑块走「×2 的整数刻度」（0.5dp 步长，含负值，PRD §14.2 DK-02）
        // §2.1.1：上限由 10dp 收窄到 5dp（SubtitleConfig.LINE_SPACING_MAX 已改，这里 to 值跟着走）
        mRowLineSpacing = addSliderRow(parent, Strings.LINE_SPACING,
                Math.round(SubtitleConfig.LINE_SPACING_MIN * 2),
                Math.round(SubtitleConfig.LINE_SPACING_MAX * 2), 1,
                v -> Strings.VALUE_DP.format(mLang, fmtDp(v / 2f)),
                v -> {
                    mDraft.lineSpacingDp = v / 2f;
                    markDirty();
                });

        mRowWrapSpacing = addSliderRow(parent, Strings.WRAP_SPACING,
                Math.round(SubtitleConfig.WRAP_SPACING_MIN * 2),
                Math.round(SubtitleConfig.WRAP_SPACING_MAX * 2), 1,
                v -> Strings.VALUE_DP.format(mLang, fmtDp(v / 2f)),
                v -> {
                    mDraft.wrapExtraSpacingDp = v / 2f;
                    markDirty();
                });

        // §3.1.5：悬浮窗颜色 —— **必须在「长字幕内换行额外行距」的正下方**（需求给的位置）。
        // 半透明预览：色板圆点画的是「面板**顶部**实际不透明度叠加后的效果」（≈57%），
        // 而不是实心基色 —— 用户要能一眼看出「这个颜色会让面板透出后面的壁纸」。
        ColorRow panelRow = addColorRow(parent, Strings.FLOAT_WINDOW_COLOR,
                panelPalette(), panelPaletteNames(), () -> mDraft.floatWindowColor, v -> {
                    mDraft.floatWindowColor = v;
                    markDirty();
                }, PANEL_PREVIEW_TOP_ALPHA, true);
        mSwatchPanelColor = panelRow.swatch;
        // 【2.2.9】液态玻璃开启时这一行要整行置灰（标签 + 色点一起），故留三个引用。
        mPanelColorLabel = panelRow.label;
        mPanelColorBody = panelRow.body;

        mHintTypo = addResetRow(parent, Strings.RESET_TYPO, this::resetTypoGroup);
    }

    /**
     * 「其他」节：状态栏字幕功能总闸 / 配置备份与恢复。
     *
     * v1.4 §16.3：**已删「保持屏幕常亮」整行**（真机测试 3.9 实锤无效果）。
     * {@code keep_screen_on} 键保留解析兼容，只是界面不再暴露。
     */
    private void buildMiscGroup(LinearLayout parent) {
        buildStatusbarRow(parent);
        // 【2.2.7 / code 979】「调试日志」开关：诊断级日志的总闸。
        // 摆在「状态栏字幕功能」下方、「备份与恢复」上方（备份那排按钮仍由
        // pinCardLastBottom 钉为卡片最后一行，见本方法末尾）。
        buildDebugLogRow(parent);
        // 【2.2.9】「液态玻璃」开关：悬浮窗面板 + 播放页两个字幕胶囊改用自渲染玻璃。
        // 摆在「调试日志」下方、「备份与恢复」上方（同为「显示类」开关，成组相邻）。
        buildLiquidGlassRow(parent);

        // 【2.3.2 §1.1.3】备份与恢复：导出 / 导入两枚 **MD3 Tonal** 按钮，各自带方向小图标
        // （导出 = 向上箭头 + 托盘，导入 = 向下箭头 + 托盘）。
        // ⚠️ v1.5 的「导出 / 导入 位置一片空白」案例见 code961 录屏 —— 根因是
        //    MaterialButton 的 style 资源被当成 defStyleAttr 传，背景/字色都没落地；
        //    现在显式给色调 + 禁用态，见 makeTonalButton。
        beginRow(parent);
        TextView backupLabel = makeRowLabel(Strings.BACKUP_RESTORE);
        backupLabel.setPadding(0, 0, 0, dp(6));
        parent.addView(backupLabel);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton exportBtn = makeTonalButton(R.drawable.ic_export);
        exportBtn.setText(Strings.EXPORT.get(mLang));
        exportBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        exportBtn.setOnClickListener(v -> {
            String name = "dlsitefloat-config-" + BuildConfig.VERSION_NAME + ".json";
            try {
                mExportLauncher.launch(name);
            } catch (Throwable t) {
                Log.w(TAG, "export launch failed: " + t);
                toast(Strings.EXPORT_FAILED.get(mLang));
            }
        });
        bar.addView(exportBtn);

        MaterialButton importBtn = makeTonalButton(R.drawable.ic_import);
        importBtn.setText(Strings.IMPORT.get(mLang));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.setMarginStart(dp(10));
        importBtn.setLayoutParams(ip);
        importBtn.setOnClickListener(v -> {
            try {
                mImportLauncher.launch(new String[]{"application/json", "*/*"});
            } catch (Throwable t) {
                Log.w(TAG, "import launch failed: " + t);
                toast(Strings.IMPORT_FAILED.get(mLang));
            }
        });
        bar.addView(importBtn);

        parent.addView(bar);
        // 【code 976 §1】「其他」卡片的最后一行是这排导出/导入按钮 —— 它是**容器**（盒底即视觉底边），
        //   与前三张卡（末尾是「恢复默认」下方的小字）走同一个收敛函数，底距口径才统一。
        pinCardLastBottom(parent, bar);

        // 【2.3.2 §2.5】v1.5 在按钮下方还挂了一个**空 TextView**（只有 8dp 内边距、没有任何文字），
        // 叠加卡片自身 6dp 下内边距 ⇒ 按钮下面凭空多出约 20dp 的空白，Ari 两次点名
        // 「导出 / 导入 下面的边界太宽了」。这里直接把这个空 View 删掉，
        // 按钮下方的留白 = 卡片 paddingBottom 6dp，正好是 §2.5 要的 6dp。
    }

    /**
     * v1.4 §2.1.1.4：状态栏字幕功能总闸（含 bug 修复）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 🔴 两级开关的关系（此前做错了，这里写清楚，代码注释就是规格）
     *
     *   L1「状态栏字幕功能」（**本开关**，配置键 {@code statusbar_subtitle_enabled}）
     *        ── 插件级能力总闸，属于**设置页**。
     *        关 ⇒ ① 状态栏字幕停止显示（悬浮窗不受影响）
     *             ② 播放页内那个「状态栏字幕开关按钮」**从界面上消失**
     *             ③ 弹出长提示：本开关撤销不了已授予插件的 SystemUI 权限（见 I 文案）
     *        开 ⇒ 播放页按钮**恢复显示**，且它**初始为关**（状态栏字幕默认关）。
     *
     *   L2「播放页那个开关」（**会话级**，状态存在**宿主侧**）
     *        ── 用户在某个播放会话里的临时开关，只影响当前这次播放。
     *        ⚠️ **不要复用 {@code statusbarSubtitleEnabled} 这个字段表达它** ——
     *        两者的生命周期完全不同（L1 是持久配置、L2 是会话状态），
     *        而且 L1 关闭时 L2 连 UI 都不存在。宿主侧的会话状态由宿主自己维护。
     *
     *   🐞 此前的 bug：设置页这个开关错误地直接关联了「播放页开关的显示」，
     *      语义退化成「L2 的镜像」；结果是关掉它只让播放页按钮闪一下，
     *      状态栏字幕该显示还是显示。修法是让本开关**只表达 L1**，
     *      由 hook 侧去实现「L1 关 ⇒ L2 的按钮不渲染 + 状态栏字幕停显」。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void buildStatusbarRow(LinearLayout parent) {
        beginRow(parent);
        // 用整行容器包住，便于 SystemUI 未授权时整行淡化（只看一个开关发灰不够明显）
        mStatusbarRowRoot = new LinearLayout(this);
        mStatusbarRowRoot.setOrientation(LinearLayout.VERTICAL);

        mRowStatusbar = addSwitchRow(mStatusbarRowRoot, Strings.STATUSBAR_SUBTITLE,
                this::onStatusbarFeatureToggled);

        // 长提示放在开关**下方**（常驻小字，三语），而不是只在关闭时弹一次 Toast ——
        // 用户很可能先关掉、过一会儿才想起来「那权限怎么办」，常驻着才找得到。
        TextView revokeHint = new TextView(this);
        revokeHint.setText(Strings.STATUSBAR_REVOKE_HINT.get(mLang));
        revokeHint.setTextAppearance(R.style.TextAppearance_DLsiteFloat_Hint);
        revokeHint.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        revokeHint.setPadding(0, 0, 0, dp(6));
        mStatusbarRowRoot.addView(revokeHint);

        parent.addView(mStatusbarRowRoot);
    }

    /**
     * 「状态栏字幕功能」被用户翻转。
     *
     * 【2.3.1 §7.8】**不再弹 Toast**：此前关闭时会弹一次「撤销权限」长提示，
     * 但那条长提示本来就已经**常驻**在开关下方的小字里（见 {@link #buildStatusbarRow}），
     * 再弹一次属于重复打扰 —— 用例 7.8 明确要求删除。只留常驻小字。
     */
    private void onStatusbarFeatureToggled(boolean enabled) {
        mDraft.statusbarSubtitleEnabled = enabled;
        markDirty();
    }

    /**
     * 【2.2.7 / code 979】「调试日志」开关（「其他」卡片）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 为什么加这一个开关（Ari 2026-10-07 晚：日志里刷了**大量**这种行）：
     *
     *   为定位「切到无字幕音轨仍残留」这条 bug，977/978 在被注入进程里加了**只读诊断转储**
     *   （{@code statusMap via=…} 把宿主状态 Map 原样打出来，按「来源 + 实例」节流 2.5s）。
     *   它是那两轮排查的唯一证据来源，**必须**留着；但它与兜底轮询相乘（1s 一轮 × 多个实例）
     *   ⇒ 真机上约 20~70 条/分钟，长时间开着会把有用日志淹没。
     *
     *   取舍：把诊断级日志**默认关**，需要排查时在设置页打开 —— 既保住「出问题随时能取证」，
     *   又让日常运行的日志回到只含里程碑（钩子挂载 / 种基线 / {@code >>> track changed} / WARN）的安静状态。
     *
     *   闸门落在 hook 侧（{@code RemoteConfig.debugLog()}），关的是**诊断级**输出，
     *   不影响任何判定逻辑 —— 本开关**不改变功能行为**，只改「打多少日志」。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void buildDebugLogRow(LinearLayout parent) {
        beginRow(parent);
        // 整行容器：开关 + 下方常驻说明小字（与状态栏字幕那一行的排布一致）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        mRowDebugLog = addSwitchRow(root, Strings.DEBUG_LOG, this::onDebugLogToggled);

        TextView hint = new TextView(this);
        hint.setText(Strings.DEBUG_LOG_HINT.get(mLang));
        hint.setTextAppearance(R.style.TextAppearance_DLsiteFloat_Hint);
        hint.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        hint.setPadding(0, 0, 0, dp(6));
        root.addView(hint);

        parent.addView(root);
    }

    private void onDebugLogToggled(boolean enabled) {
        mDraft.debugLog = enabled;
        markDirty();
    }

    /**
     * 【2.2.9】「液态玻璃」开关（「其他」卡片，摆在「调试日志」下方）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 需求（Ari）：做一个「液态玻璃」开关，开启后**播放页两个字幕开关按钮**与
     * **悬浮窗背景**以液态玻璃形式呈现；同时设置页里「悬浮窗颜色」**不可调整**。
     *
     * 渲染后端：{@code view.LiquidGlassDrawable}（自渲染多层玻璃）——
     * 为什么不做「真·背后折射」见该类类头与工作区根目录的可行性分析报告
     * （{@code ColorOS17_液态玻璃_悬浮窗可行性分析.md} §4.3：真折射要走「自己采背景 +
     * 自己渲染」的跨进程高成本路线）。
     *
     * ⚠️ 本开关同时驱动三件事，缺一不可：
     *   ① 悬浮窗面板背景（hook 侧 {@code FloatingSubtitleView.applyPanelBackground}）；
     *   ② 播放页两个胶囊底（{@code ActivityButtonHook.createCapsuleDrawable}）；
     *   ③ 本页「悬浮窗颜色」行**置灰不可调**（{@link #applyPanelColorLock()}）。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void buildLiquidGlassRow(LinearLayout parent) {
        beginRow(parent);
        // 整行容器：开关 + 下方常驻说明小字（与「调试日志」/「状态栏字幕」两行同一排布）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        mRowLiquidGlass = addSwitchRow(root, Strings.LIQUID_GLASS, this::onLiquidGlassToggled);

        TextView hint = new TextView(this);
        hint.setText(Strings.LIQUID_GLASS_HINT.get(mLang));
        hint.setTextAppearance(R.style.TextAppearance_DLsiteFloat_Hint);
        hint.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        hint.setPadding(0, 0, 0, dp(6));
        root.addView(hint);

        // 【2.2.11b】「模糊强度」（0–100%，步长 1，默认 60%）：**只在液态玻璃开启时显示**。
        //   显隐走 root 的 VISIBLE/GONE（与「非活动行模糊半径」那一行同一做法，
        //   见 syncWidgets 里对 mRowBlurRadius 的处理）。
        mRowLiquidGlassBlur = addSliderRow(root, Strings.LIQUID_GLASS_BLUR,
                SubtitleConfig.LIQUID_GLASS_BLUR_PCT_MIN,
                SubtitleConfig.LIQUID_GLASS_BLUR_PCT_MAX,
                SubtitleConfig.LIQUID_GLASS_BLUR_PCT_STEP,
                v -> Strings.VALUE_PCT.format(mLang, v), v -> {
                    mDraft.liquidGlassBlurPct = v;
                    markDirty();
                    updatePreview();      // 预览要立刻反映强度
                });

        mHintLiquidGlassBlur = new TextView(this);
        mHintLiquidGlassBlur.setText(Strings.LIQUID_GLASS_BLUR_HINT.get(mLang));
        mHintLiquidGlassBlur.setTextAppearance(R.style.TextAppearance_DLsiteFloat_Hint);
        mHintLiquidGlassBlur.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        mHintLiquidGlassBlur.setPadding(0, 0, 0, dp(6));
        root.addView(mHintLiquidGlassBlur);

        parent.addView(root);

        applyLiquidGlassBlurVisibility();
    }

    private void onLiquidGlassToggled(boolean enabled) {
        mDraft.liquidGlass = enabled;
        markDirty();
        // 【需求】开启后「悬浮窗颜色」不可调整 —— 立即生效，不等保存。
        applyPanelColorLock();
        // 【2.2.11b】「模糊强度」滑条跟随显隐。
        applyLiquidGlassBlurVisibility();
        // 预览区也要跟着换渲染后端（液态玻璃下不再吃 floatWindowColor）。
        updatePreview();
    }

    /** 【2.2.11b】把「模糊强度」滑条与它的小字按「液态玻璃是否开启」对齐显隐（幂等）。 */
    private void applyLiquidGlassBlurVisibility() {
        final int vis = (mDraft != null && mDraft.liquidGlass) ? View.VISIBLE : View.GONE;
        if (mRowLiquidGlassBlur != null && mRowLiquidGlassBlur.root != null) {
            mRowLiquidGlassBlur.root.setVisibility(vis);
        }
        if (mHintLiquidGlassBlur != null) {
            mHintLiquidGlassBlur.setVisibility(vis);
        }
    }

    /**
     * 【2.2.9】把「悬浮窗颜色」整行置灰 / 恢复（液态玻璃开启时必须不可调）。
     *
     * ⚠️ 与 PRD §FR-07「未授权时只置灰 UI、不写键」同口径：置灰**不改**
     *    {@code mDraft.floatWindowColor}，关掉液态玻璃后原色立即恢复显示。
     */
    private void applyPanelColorLock() {
        final boolean locked = mDraft != null && mDraft.liquidGlass;
        final float alpha = locked ? 0.38f : 1f;
        if (mPanelColorLabel != null) {
            mPanelColorLabel.setAlpha(alpha);
        }
        if (mPanelColorBody != null) {
            mPanelColorBody.setAlpha(alpha);
        }
        if (mSwatchPanelColor != null) {
            mSwatchPanelColor.setInteractive(!locked);
        }
    }

    // ==================================================================
    // 「调整预览窗」—— 按草稿参数实时渲染（参考稿的蓝底样例块）
    // ==================================================================

    /**
     * 预览文案：5 行示例字幕，第 2 行是「活动行」。
     *
     * 【v1.4 修正】此前这里写死的是 5 行**日文**中性命中句，理由是「用中性占位句更安全」，
     * 但那是**实现者的偏好，不是需求** —— PRD §FR-03 规则 2 明确指定了这 5 行的内容，
     * 并要求「**随设置页语言同步切换**（三语版本见翻译表《可视化调整页面翻译.xlsx》）」；
     * §16.4 UI-05② 又重申了一遍，验收 N-05 也把它写成了判据。
     * 所以这里改成**三语表**，随 {@link #mLang} 切换 —— 与页面其他文案同源、同时机刷新。
     *
     * 三语原文逐字取自翻译表（xlsx 第 21–34 号共享串），**不要自行改写措辞**：
     *   简中 / 繁體 / English
     *   1. 因为想被饲养，也因为想饲养别人 / 因為想被飼養，也因為想飼養別人 / Because I want to be nurtured...
     *   2. 我们互相带上项圈吧 / 我們互相戴上項圈吧 / Let's put collars on each other
     *   3. **除了我之外（活动行）** / 除了我之外 / Except me
     *   4. 绝对不会出现有比我能给你幸福的人 / 絕對不會出現有比我能給你幸福的人 / No one could ever make you happier than me
     *   5. 哪里也不要去 / 哪裡也不要去 / Don't go anywhere
     *
     * ⚠️ 第 3 行「除了我之外」在简繁两种中文里**字形完全相同**，翻译表里只占一格 —— 不是漏项。
     * ⚠️ 活动行 = **第 3 行**（0-based index 2），见 {@link #PREVIEW_ACTIVE_INDEX}，三语下都不变。
     */
    private static final String[][] PREVIEW_LINES = {
            {"因为想被饲养，也因为想饲养别人",
                    "因為想被飼養，也因為想飼養別人",
                    "Because I want to be nurtured, and also I want to nurture others"},
            {"我们互相带上项圈吧",
                    "我們互相戴上項圈吧",
                    "Let's put collars on each other"},
            {"除了我之外",
                    "除了我之外",
                    "Except me"},
            {"绝对不会出现有比我能给你幸福的人",
                    "絕對不會出現有比我能給你幸福的人",
                    "No one could ever make you happier than me"},
            {"哪里也不要去",
                    "哪裡也不要去",
                    "Don't go anywhere"},
    };

    /** 取当前界面语言下的 5 行示例字幕。 */
    private String[] previewLines() {
        int col = mLang == null ? 0 : mLang.colIndex();
        return new String[]{
                PREVIEW_LINES[0][col], PREVIEW_LINES[1][col], PREVIEW_LINES[2][col],
                PREVIEW_LINES[3][col], PREVIEW_LINES[4][col]};
    }

    /**
     * 预览里的活动行下标（0-based）。
     *
     * ⚠️ 【v1.4 修正】此前是 {@code 1}（第 2 行）—— 那是旧日文占位句时代的遗留，
     * 与 PRD §FR-03 规则 2 冲突：PRD 的 5 行里**第 3 行「除了我之外」**被显式标注为
     * 「（活动行：加粗、高亮、放大）」，其余四行都标「非活动行」。
     * 既然示例文本已经换成 PRD 指定的那 5 行，活动行就必须跟着换成 PRD 指定的那一行，
     * 否则「预览的活动行」和「PRD 说的活动行」是两行，测的人无法判断谁对。
     */
    private static final int PREVIEW_ACTIVE_INDEX = 2;

    /**
     * v1.4 §3.1.1① / 【2.3.1 §3.5 重做】：预览渲染**必须与真悬浮窗一致** ——
     * 一切绘制参数都从 {@link SubtitleStyle#of(SubtitleConfig, DisplayMetrics)} 取，
     * 且**逐行 View 与真窗同构**（不再是「一个 TextView + span」）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * v1.4 的做法是「一个 TextView + RelativeSizeSpan」把 5 行塞进一个视图里。
     * 那有两个后果，正是 Ari 在 2.3.0 反馈的两条：
     *   · 「完全不一样（字体效果、行间距 都不一致）」（用例 3.5）—— 行距只能用
     *     {@code setLineSpacing} 近似真窗「每行上下内边距各加 lineSpacing/2」的算法，
     *     字号也只能靠相对倍数间接表达；
     *   · 「模糊非活动行 / 模糊半径 在预览窗内失效」（用例 3.4）—— 一个 TextView 里
     *     没法只模糊其中几行。
     *
     * 现在改为与真窗**完全同构**：容器是 LinearLayout，每一行是独立 TextView，
     * 逐行调 {@link #stylePreviewLine}（= 真窗 {@code styleLine} 的同一套参数）。
     *   · 字号：活动行 {@code BASE_TEXT_SP × activeScale}，非活动行
     *     {@code BASE_TEXT_SP × inactiveScalePct%} —— 直接取绝对值，不再走相对倍数；
     *   · 颜色与不透明度：{@code activeColor + activeAlpha} / {@code inactiveColor + 0.21}；
     *   · 阴影：{@code shadowRadiusPx / shadowDyPx / shadowColor} 直接取；
     *   · 模糊：非活动行走 {@link #applyPreviewBlur}（真窗 {@code applyBlur} 同源）。
     * 允许的差异只剩**抗锯齿与栅格化**（PRD §3.1.1 明示允许）。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void updatePreview() {
        if (mPreviewLines == null) {
            return;
        }
        // ★ 唯一换算入口：真窗怎么算，预览就怎么算
        SubtitleStyle st = SubtitleStyle.of(mDraft, getResources().getDisplayMetrics());

        // v1.4 §FR-03 规则 2：示例字幕**随界面语言切换**（三语表见 PREVIEW_LINES）。
        // ⚠️ 必须在每次重绘时现取 —— 切语言走的是 markDirty + updatePreview 这条链，
        //   如果把行数组成字段缓存起来，切语言后预览会停在旧语言上。
        String[] lines = previewLines();

        // 【2.3.1 §3.5】逐行重建：与真窗同构（LinearLayout + 每行一个 TextView），
        // 每行都走 stylePreviewLine()（= 真窗 styleLine() 的同一套参数）。
        // 参数没变时跳过重建，避免拖动滑块时疯狂 inflate（§3.4 要求实时跟随，但不该卡）。
        mPreviewLines.removeAllViews();
        for (int i = 0; i < lines.length; i++) {
            boolean active = (i == PREVIEW_ACTIVE_INDEX);
            TextView tv = new TextView(this);
            tv.setText(lines[i]);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            float sizeSp = active ? st.activeSizeSp : st.inactiveSizeSp;
            int color = active ? st.activeColor : st.inactiveColor;
            float alpha = active ? st.activeAlpha : st.inactiveAlpha;
            stylePreviewLine(tv, st, sizeSp, color, alpha, active);
            mPreviewLines.addView(tv);
        }

        applyPreviewPanel();
    }

    /**
     * 【2.3.1 §3.5】预览行的样式 —— 逐参数对齐真窗 {@code FloatingSubtitleView#styleLine}。
     *
     * ⚠️ 这里是「预览与真窗一致」的唯一落点（用例 3.5 的判据）：
     *    字号、颜色、不透明度、字重、阴影（半径/偏移/颜色）、内边距、行内额外行距
     *    全部取同一个 {@link SubtitleStyle}，两边不可能算出两份结果。
     *    允许的差异只有抗锯齿 / 栅格化（PRD §3.1.1 明示）。
     */
    private void stylePreviewLine(TextView tv, SubtitleStyle st, float sizeSp,
                                  int color, float alpha, boolean active) {
        tv.setGravity(st.gravity);
        tv.setPadding(dp(st.linePadH), dp(st.linePadV), dp(st.linePadH), dp(st.linePadV));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setAlpha(alpha);
        tv.setShadowLayer(st.shadowRadiusPx, 0f, st.shadowDyPx, st.shadowColor);
        tv.setTypeface(active ? st.activeTypeface : st.inactiveTypeface);
        if (active && st.wrapExtraSpacingPx != 0f) {
            tv.setLineSpacing(st.wrapExtraSpacingPx, 1f);
        }
        // 【2.3.1 §3.4】非活动行模糊 / 缩放：真窗在预览里也必须能看见
        if (!active) {
            applyPreviewBlur(tv, st);
            if (st.inactiveScaleEnabled) {
                // 缩放关闭时 SubtitleStyle 已把 inactiveSizeSp 归到 100%，无需额外处理；
                // 开启时字号本身就按 pct 缩过了，这里不再二次缩放。
            }
        }
    }

    /**
     * 【2.3.1 §3.4】把「模糊非活动行」落到预览行上 —— 与真窗 {@code applyBlur} 同源。
     *
     * 旧版预览完全不处理模糊，于是 Ari 反馈「模糊非活动行 / 模糊半径 在预览窗内失效，
     * 实际调整可应用到悬浮窗」—— 调参时看不到反馈。
     */
    private void applyPreviewBlur(TextView tv, SubtitleStyle st) {
        if (!st.inactiveBlurEnabled || st.blurRadiusPx <= 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    tv.setRenderEffect(null);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                tv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        st.blurRadiusPx, st.blurRadiusPx, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                // 与真窗一致的降级：不支持 RenderEffect 时用低不透明度近似
                tv.setAlpha(0.3f);
            }
        }
    }

    /**
     * v1.4 §3.1.1③：预览卡画成**半透明悬浮窗面板**（模拟真窗叠在壁纸上的观感）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 由三块拼出来，全部取自**同一份草稿配置**：
     *
     *  ① {@code preview_backdrop}（壁纸近似底）：真窗是浮在宿主内容上的，预览若只铺
     *     纯色底就看不出「半透明」这件事。这里用一层极淡的斜向渐变 + 取色槽的中间调，
     *     给面板一个「背后有东西」的参照。刻意做得很淡 —— 它只是参照物，不能抢戏
     *     （用户的注意力应该在字幕样式上）。
     *  ② {@code preview_card} 的背景：{@code floatWindowColor} 的**基色 RGB** 配
     *     顶部 {@link #PANEL_PREVIEW_TOP_ALPHA}(0x92≈57%) → 底部一小档的**垂直渐变**，
     *     与渲染端 {@code SubtitleStyle.panelColorTop/Bottom} 的口径一致（上深下浅）。
     *  ③ 圆角 20dp：与渲染端 GlassPanelDrawable 的面板圆角**同值**（§3.1.5 明确 20dp）。
     *
     * 【吸顶】已按 Ari 2026-10-05 拍板做**真吸顶**（§3.1.1③ 第一种方案）：
     *   {@code activity_settings.xml} 的根布局是 CoordinatorLayout，
     *   页头行 + 「调整预览窗」标题 + 预览卡整体在 AppBarLayout 内：
     *   · 页头行 {@code scroll|enterAlways} ⇒ 上滑滚走、下滑立刻回来；
     *   · 标题与预览卡 {@code noScroll} ⇒ 不参与滚动，始终钉在顶部，
     *     下面的设置卡片从它下面穿过去，调参时始终能看到效果。
     *   ⚠️ **不要**把预览卡改成 {@code exitUntilCollapsed}：AppBarLayout 求可滚动高度时
     *   会把该子 View 的 {@code minHeight} 减掉，wrap_content 卡片没设 minHeight ⇒ 吸顶量 0，
     *   整张卡会跟着滚走（已反编译 material 1.9.0 的 getTotalScrollRange 确认）。
     *   ⚠️ 连带两条：① 底栏 {@code bottom_bar} 改为 CoordinatorLayout 子 View
     *   （{@code layout_gravity="bottom"}），滚动区需自行留出底栏高度的 paddingBottom；
     *   ② AppBarLayout 里**第一个 noScroll 子 View 会截断可滚动范围**，顺序不能乱。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void applyPreviewPanel() {
        if (mPreviewCard == null) {
            return;
        }
        // 【2.2.10】圆角随渲染后端走：液态玻璃档用 28dp（与 FloatingSubtitleView 的
        //   LIQUID_GLASS_CORNER_RADIUS_DP 同值 —— 否则预览是 20dp、真窗是 28dp，
        //   又踩 PRD §14.1 RK-03「预览漂移」那条坑）；普通档仍是原来的 20dp。
        final int radius = dp(mDraft.liquidGlass ? LIQUID_GLASS_PREVIEW_RADIUS_DP : 20);

        // ⓪ 卡片自身：**透明圆角矩形**。它不参与观感，只负责给 clipToOutline 提供 20dp 轮廓
        //    （§3.1.5 明确面板圆角 20dp，与渲染端 GlassPanelDrawable 同值）。
        GradientDrawable shell = new GradientDrawable();
        shell.setCornerRadius(radius);
        shell.setColor(Color.TRANSPARENT);
        mPreviewCard.setBackground(shell);
        mPreviewCard.setClipToOutline(true);

        // ① 第 1 层：壁纸近似底。
        // 【2.3.2 §2.7 关键】这一层必须**完全不透明** ——
        // v1.5 用的是 withAlpha(…, 0.85/0.70/0.55) 的半透明渐变，于是整张预览卡是半透明的，
        // 吸顶时底下滚动过去的「模糊半径」等行会**透出来**（Ari 截图里预览卡里出现滑块的怪象），
        // 左右边缘也会显出被覆盖内容的地色。做成不透明后，预览块才是一块真正的实体面板。
        int wallA = attr(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF);
        int wallB = attr(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4);
        int wallC = attr(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC);
        int wallD = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFECE6F0);
        if (mPreviewBackdrop != null) {
            GradientDrawable wallpaper = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{wallA, wallB, wallC, wallD});
            wallpaper.setCornerRadius(radius);
            mPreviewBackdrop.setBackground(wallpaper);
        }

        // ② 第 2 层：悬浮窗面板。真实渲染口径是「基色 RGB × 固定 alpha 档」
        //    （顶 0x4D / 底 0x30，再由 GlassPanelDrawable 的 fillScale 增益到 ≈0x92/0x5B）。
        //    预览按同一比例取「顶 0x92 → 底 0x5B」的上深下浅垂直渐变，与真端口径一致。
        if (mPreviewPanel != null) {
            // 【2.2.9】液态玻璃开启时，预览与真窗**同源**：同样换成 LiquidGlassDrawable
            //   （而不是照旧画基色渐变），否则用户看到的预览会与浮窗不一致（§3.1.1① 的铁律）。
            if (mDraft.liquidGlass) {
                float density = getResources().getDisplayMetrics().density;
                // 【2.2.12】**复用同一个 Drawable 实例**（只在圆角变化 / 底不是它时才重建）：
                //   每次 setBackground 都会让面板出现一帧"没有背景的玻璃"——
                //   那正是拖动滑条时预览"闪跳"的**另一半**原因（另一半是模糊量按整数档跳）。
                if (mPreviewGlass == null || mPreviewGlassRadius != radius
                        || mPreviewPanel.getBackground() != mPreviewGlass) {
                    mPreviewGlass = new LiquidGlassDrawable(radius, density);
                    mPreviewGlassRadius = radius;
                    mPreviewPanel.setBackground(mPreviewGlass);
                }
                ensurePreviewBackdropTile(wallA, wallB, wallC, wallD);
                // 预览的"背后"= 预览卡自己那层壁纸：先按模糊强度把它软化（连续），
                // 再把均值亮度与上下缘颜色喂进去 —— 走的是**真窗同一套**自适应霜面与光圈染色。
                mPreviewGlass.setPreviewBackdrop(previewBackdropFor(mDraft.liquidGlassBlurPct));
                mPreviewGlass.setBackdropStats(mPreviewBackdropLum,
                        0xFF000000 | (wallA & 0x00FFFFFF),
                        0xFF000000 | (wallD & 0x00FFFFFF));
            } else {
                int base = mDraft.floatWindowColor;
                int topAlpha = PANEL_PREVIEW_TOP_ALPHA;
                int bottomAlpha = Math.max(0, Math.round(topAlpha * (0x5B / (float) 0x92)));
                int top = withAlpha(base, topAlpha / 255f);
                int bottom = withAlpha(base, bottomAlpha / 255f);
                GradientDrawable panel = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
                panel.setCornerRadius(radius);
                mPreviewPanel.setBackground(panel);
            }
        }
    }

    /**
     * 【2.2.11】把预览卡的「壁纸底」渐变烤成一张 64×64 小位图，供玻璃的自适应霜面使用。
     *
     * 为什么需要它：真窗的玻璃背景来自宿主画面的 PixelCopy（只在宿主进程拿得到），
     * 而设置页是**本模块自己进程**的 Activity —— 采不到宿主。若预览完全不给背景，
     * 液态玻璃档的预览就会是一块"没有背景的玻璃"，与真窗观感差一截
     * （正是 PRD §14.1 RK-03 预览漂移的老问题）。
     * 这里退而求其次：拿预览卡**自己那层壁纸**当背景（画成小位图 + 算均值亮度），
     * 走的是渲染端**同一套**自适应霜面代码 ⇒ 用户能在预览里看到"玻璃压在背景上"的样子。
     *
     * ⚠️ 只建一次（渐变颜色变了才重建），因为 {@link #applyPreviewPanel()} 会被频繁调用。
     */
    private void ensurePreviewBackdropTile(int a, int b, int c, int d) {
        final int seed = a ^ (b * 31) ^ (c * 131) ^ (d * 3571);
        if (mPreviewBackdropBmp != null && mPreviewBackdropSeed == seed) {
            return;                    // 颜色没变且已有图 → 复用
        }
        mPreviewBackdropSeed = seed;
        try {
            final int n = 64;
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, n, n, new int[]{a, b, c, d}, null,
                    Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, n, n, p);
            // 【2.2.12b】底图里必须放**看得见**的结构。
            //
            // 为什么：壁纸本身是**平滑渐变**，对渐变做模糊在数学上等于没做 —— 上一版加了
            // 8 团 22% 的淡色斑，真机反馈仍然是"拖滑条预览窗没反应"（太淡，糊掉也看不出来）。
            // 这里加两类**高对比**结构，让"越糊越化开"一眼可见：
            //   ① 几团较实的心色圆斑（面板底下的"画面内容"）；
            //   ② 三条横向亮条（形似字幕行），模糊后文字感会化开成光带。
            // ⚠️ 它们只画在**玻璃底下**，而玻璃是不透明的，所以与卡上其它地方并不矛盾
            //    —— 用户看到的就是"面板压住了某块内容"。
            java.util.Random rnd = new java.util.Random(20261008L);
            Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 10; i++) {
                final int col = (i % 2 == 0) ? a : d;
                blob.setColor((0x70 << 24) | (col & 0x00FFFFFF));
                cv.drawCircle(rnd.nextInt(n), rnd.nextInt(n), 5 + rnd.nextInt(12), blob);
            }
            Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
            bar.setColor(0x77FFFFFF);
            for (int i = 0; i < 3; i++) {
                final float by = 12 + i * 18 + rnd.nextInt(4);
                final float bx = 2 + rnd.nextInt(10);
                cv.drawRoundRect(bx, by, n - 2 - rnd.nextInt(10), by + 4, 2, 2, bar);
            }
            int[] px = new int[n * n];
            bmp.getPixels(px, 0, n, 0, 0, n, n);
            long sum = 0;
            for (int v : px) {
                sum += (77 * ((v >> 16) & 0xFF) + 150 * ((v >> 8) & 0xFF) + 29 * (v & 0xFF)) >> 8;
            }
            mPreviewBackdropBmp = bmp;
            mPreviewBackdropLum = (sum / (float) px.length) / 255f;
        } catch (Throwable t) {
            // 建不出来就退回"没有背景"的静态玻璃，预览仍然可用
            mPreviewBackdropBmp = null;
        }
    }

    /**
     * 【2.2.11b】取「按模糊强度软化过」的预览壁纸位图（滑条演示用）。
     *
     * 做法：先缩到 {@code 64/(1+7×强度)} 再拉回 64×64（双线性）—— 强度越高缩得越小、
     * 拉回后越糊。这是预览专用的**近似**，真窗的模糊由采集侧对真实画面做盒式模糊。
     */
    private Bitmap previewBackdropFor(int pct) {
        if (mPreviewBackdropBmp == null) {
            return null;
        }
        final int q = Math.max(0, Math.min(100, pct));
        if (mPreviewBackdropBlurBmp != null && mPreviewBackdropBlurPct == q) {
            return mPreviewBackdropBlurBmp;
        }
        try {
            // 【2.2.12】连续模糊：半径 r 与 r+1 **各糊一遍再按小数部分线性插值**。
            //   ⚠️ 上一版是「整数档位的缩放」（1/8 → 7/8 档），每跨一档跳一次 ——
            //      那正是「预览闪跳而不连续」的直接原因。模糊半径现在是连续的。
            final int n = mPreviewBackdropBmp.getWidth();
            final float rad = q / 100f * 9f;             // 0..9px（在 64×64 的底图上）
            final int r0 = (int) Math.floor(rad);
            final float frac = rad - r0;
            final int[] src = new int[n * n];
            mPreviewBackdropBmp.getPixels(src, 0, n, 0, 0, n, n);
            final int[] b0 = blurTile(src, n, r0);
            final int[] out = blurTile(src, n, r0 + 1);
            if (frac > 0.005f) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = lerpArgb(b0[i], out[i], frac);
                }
            } else {
                System.arraycopy(b0, 0, out, 0, out.length);
            }
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            bmp.setPixels(out, 0, n, 0, 0, n, n);
            mPreviewBackdropBlurBmp = bmp;
            mPreviewBackdropBlurPct = q;
            return bmp;
        } catch (Throwable t) {
            return mPreviewBackdropBmp;
        }
    }

    /** 对 n×n 的像素数组做两遍可分离盒式模糊（半径 0 = 原样返回）。 */
    private static int[] blurTile(int[] src, int n, int radius) {
        int[] a = src.clone();
        if (radius <= 0) {
            return a;
        }
        int[] t = new int[a.length];
        for (int pass = 0; pass < 2; pass++) {
            blurTileH(a, t, n, radius);
            blurTileV(t, a, n, radius);
        }
        return a;
    }

    private static void blurTileH(int[] in, int[] out, int n, int r) {
        final int div = 2 * r + 1;
        for (int y = 0; y < n; y++) {
            final int row = y * n;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + ci(i, n)];
                sa += p >>> 24; sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int x = 0; x < n; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[row + ci(x + r + 1, n)];
                int sub = in[row + ci(x - r, n)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void blurTileV(int[] in, int[] out, int n, int r) {
        final int div = 2 * r + 1;
        for (int x = 0; x < n; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[ci(i, n) * n + x];
                sa += p >>> 24; sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int y = 0; y < n; y++) {
                out[y * n + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[ci(y + r + 1, n) * n + x];
                int sub = in[ci(y - r, n) * n + x];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int ci(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /** 按 f 在两个 ARGB 之间线性插值（逐通道）。 */
    private static int lerpArgb(int a, int b, float f) {
        final int g = Math.round(255 * f);
        final int ia = 255 - g;
        return ((((a >>> 24) * ia + (b >>> 24) * g) / 255) << 24)
                | (((((a >> 16) & 0xFF) * ia + ((b >> 16) & 0xFF) * g) / 255) << 16)
                | (((((a >> 8) & 0xFF) * ia + ((b >> 8) & 0xFF) * g) / 255) << 8)
                | (((a & 0xFF) * ia + (b & 0xFF) * g) / 255);
    }

    // ==================================================================
    // FR-09 配置备份导出 / 导入
    // ==================================================================

    private void exportConfigTo(Uri uri) {
        try {
            String json = mDraft.toJson().toString(2);
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    toast(Strings.EXPORT_FAILED.get(mLang));
                    return;
                }
                os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            }
            toast(Strings.EXPORT_DONE.get(mLang));
        } catch (Throwable t) {
            Log.w(TAG, "export failed: " + t);
            toast(Strings.EXPORT_FAILED.get(mLang));
        }
    }

    /**
     * v1.4 §5.1.1：导入失败按**内容形态**分三档 toast（三语），别一律报「导入失败」。
     *
     * 为什么值得分三档：这三种情况的**用户动作完全不同** ——
     *   · 空文件     → 文件本身是坏的/选错了空文件，重导没用，得换文件；
     *   · 非 JSON    → 选错文件了（比如选了张图片或别的 app 的配置），换文件即可；
     *   · 内容损坏   → 文件是 JSON 但结构不对（手改过 / 传输截断），可能需要重新导出。
     * 一律报「导入失败」会让用户以为是模块的 bug，实际是文件的问题。
     *
     * 分支判据（顺序有意义，先判空、再判非 JSON、最后判内容不合规）：
     *  ① 读到的文本 trim 后为空 → {@link Strings#IMPORT_FAILED_EMPTY}；
     *  ② {@code new JSONObject(text)} 抛 JSONException → 不是合法 JSON → {@link Strings#IMPORT_FAILED_NOT_JSON}；
     *  ③ JSON 合法但 schema 读不出 / fromJson 抛异常 → 内容不合规 → {@link Strings#IMPORT_FAILED_BROKEN}。
     */
    private void importConfigFrom(Uri uri) {
        String text = null;
        try {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    // 打不开流：与「空文件」同档给提示（用户视角都是「这个文件没有内容可用」）
                    toast(Strings.IMPORT_FAILED_EMPTY.get(mLang));
                    return;
                }
                text = ConfigStore.readText(in);
            }
        } catch (Throwable t) {
            Log.w(TAG, "import read failed: " + t);
            toast(Strings.IMPORT_FAILED_EMPTY.get(mLang));
            return;
        }

        // ① 空文件
        if (text == null || text.trim().isEmpty()) {
            toast(Strings.IMPORT_FAILED_EMPTY.get(mLang));
            return;
        }

        // ② 非 JSON
        org.json.JSONObject o;
        try {
            o = new org.json.JSONObject(text);
        } catch (Throwable t) {
            Log.w(TAG, "import rejected: not json");
            toast(Strings.IMPORT_FAILED_NOT_JSON.get(mLang));
            return;
        }

        // ③ 是 JSON 但内容不合规（schema 过新 / 结构坏 / 解析异常）
        try {
            int schema = o.optInt(SubtitleConfig.K_SCHEMA_VERSION, 0);
            if (schema > SubtitleConfig.SCHEMA_VERSION) {
                // PRD §12 E-04：配置比本模块新 → 明确拒绝，绝不猜着降级解析
                toast(Strings.IMPORT_TOO_NEW.get(mLang));
                return;
            }
            // schema 缺失（0）或字段全不对时，fromJson 会回落默认值而不抛 —— 那等于「导入了一份
            // 和默认一模一样的配置」，用户会以为导入成功了却什么都没变。这里额外判一道：
            // 连我们的键名一个都没有，就当内容不合规。
            if (schema <= 0 && !looksLikeSubtitleConfig(o)) {
                Log.w(TAG, "import rejected: json without any known key");
                toast(Strings.IMPORT_FAILED_BROKEN.get(mLang));
                return;
            }
            SubtitleConfig imported = SubtitleConfig.fromJson(o);
            // 导入 = 草稿改动（不立刻写盘），用户还得按保存才生效 —— 与 Henry 的
            // 「保存才落盘」一致，也避免误导入后没法回头。
            mDraft = imported;
            // 语言属于**界面层**、不属于外观参数：导入一份别人的配置不应该把本页语言也换掉
            // （用户刚在本页选过语言，切走会让他莫名奇妙）。与 §FR-02 规则 2 一致。
            mDraft.uiLanguage = mLang.configValue();
            quantize(mDraft);
            buildRows();
            syncWidgets();
            updatePreview();
            toast(Strings.IMPORT_DONE.get(mLang));
        } catch (Throwable t) {
            Log.w(TAG, "import failed: " + t);
            toast(Strings.IMPORT_FAILED_BROKEN.get(mLang));
        }
    }

    /** 粗判：这份 JSON 里有没有任何一个我们认识的配置键（用于区分「空壳 JSON」与真配置）。 */
    private static boolean looksLikeSubtitleConfig(org.json.JSONObject o) {
        return o.has(SubtitleConfig.K_SUBTITLE_COLOR)
                || o.has(SubtitleConfig.K_SHADOW_COLOR)
                || o.has(SubtitleConfig.K_TEXT_ALIGN)
                || o.has(SubtitleConfig.K_LINE_SPACING_DP)
                || o.has(SubtitleConfig.K_FLOAT_WINDOW_COLOR)
                || o.has(SubtitleConfig.K_UI_LANGUAGE);
    }

    // ==================================================================
    // 重启系统界面（参考稿吸底双按钮之一）
    // ==================================================================

    private void confirmRestartSystemUi() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(Strings.RESTART_CONFIRM_TITLE.get(mLang))
                .setMessage(Strings.RESTART_CONFIRM_MSG.get(mLang))
                .setNegativeButton(Strings.CANCEL.get(mLang), null)
                .setPositiveButton(Strings.RESTART_SYSUI.get(mLang), (d, w) -> {
                    try {
                        ConfigBus.sendRestartSystemUi(this);
                    } catch (Throwable t) {
                        Log.w(TAG, "restart broadcast failed: " + t);
                    }
                })
                .show();
    }

    // ==================================================================
    // 自定义取色（2.3.1 §4.1.1：HSV 色域 + 色相条 + HEX 输入）
    // ==================================================================

    /**
     * 自定义取色弹窗（2.3.1 §4.1.1 / §4.4 / §4.5 返工）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 旧实现是「顶部色块 + 红/绿/蓝 三条 0–255 滑杆」，Ari 连点两次名：
     *   「颜色自定义出现**色板 + 编码输入**显示！！不要现在的滑杆样式！！！」
     *   「不要这种滑杆样式！！！非常不直观！！！」
     *
     * 本版按参考稿（{@code Pasted image 20261004234038.png}）重做：
     *   · 主体 = {@link HsvColorPicker}（左：饱和度×明度二维色域；右：竖直色相条）；
     *   · 底部 = HEX 输入框（`#` 前缀 + 实时预览色块），可手动敲色号；
     *   · 颜色按**你看到什么就是什么**直接选，不再需要先把颜色翻译成三个 RGB 数字。
     *
     * ⚠️ 仍然**只调 RGB、保留原 alpha**：字幕色的 alpha 是配置项而非取色意图，
     *    让它在这里被误调成 0 会得到「完全看不见的字幕」，属于不该暴露的危险旋钮。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void showCustomColorDialog(Strings label, int current, OnInt onPick) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(4), pad, 0);

        // ① 色域 + 色相条
        final HsvColorPicker picker = new HsvColorPicker(this);
        picker.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        picker.setColor(current);
        root.addView(picker);

        // ② HEX 输入行：[#] [________] [色块预览]
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        hexRow.setPadding(0, dp(16), 0, 0);
        root.addView(hexRow);

        TextView hexPrefix = new TextView(this);
        hexPrefix.setText("#");
        hexPrefix.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        hexPrefix.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.GRAY));
        hexPrefix.setPadding(0, 0, dp(6), 0);
        hexRow.addView(hexPrefix);

        final EditText hexInput = new EditText(this);
        hexInput.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hexInput.setSingleLine(true);
        hexInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hexInput.setText(picker.currentHex().substring(1));
        hexInput.setHint("RRGGBB");
        hexInput.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        hexRow.addView(hexInput);

        final View hexChip = new View(this);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        chipLp.setMarginStart(dp(10));
        hexChip.setLayoutParams(chipLp);
        hexRow.addView(hexChip);
        applyChip(hexChip, current);

        // 双向同步：拖色域 → 刷新 HEX 文本与色块；敲 HEX → 回灌色域
        final boolean[] syncing = {false};
        picker.setOnColorChanged(argb -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            try {
                hexInput.setText(picker.currentHex().substring(1));
                applyChip(hexChip, argb);
            } finally {
                syncing[0] = false;
            }
        });
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (syncing[0]) {
                    return;
                }
                syncing[0] = true;
                try {
                    if (picker.setHex(s == null ? null : s.toString())) {
                        applyChip(hexChip, picker.currentColor());
                    }
                } finally {
                    syncing[0] = false;
                }
            }
        };
        hexInput.addTextChangedListener(watcher);

        // 只调 RGB、保留原 alpha
        final int alpha = Color.alpha(current);
        new MaterialAlertDialogBuilder(this)
                .setTitle(label.get(mLang))
                .setView(root)
                .setNegativeButton(Strings.CANCEL.get(mLang), null)
                .setPositiveButton(Strings.OK.get(mLang), (dlg, w) ->
                        onPick.onChanged(Color.argb(alpha,
                                Color.red(picker.currentColor()),
                                Color.green(picker.currentColor()),
                                Color.blue(picker.currentColor()))))
                .show();
    }

    private void applyChip(View chip, int argb) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        gd.setColor(argb);
        gd.setStroke(Math.max(1, dp(1)),
                attr(com.google.android.material.R.attr.colorOutline, Color.GRAY));
        chip.setBackground(gd);
    }

    // ==================================================================
    // 草稿回灌
    // ==================================================================

    private void syncWidgets() {
        if (mGroupMain == null) {
            return;
        }
        mSyncing = true;
        try {
            if (mSwatchSubtitle != null) {
                mSwatchSubtitle.setSelectedColor(mDraft.subtitleColor);
            }
            if (mSwatchShadow != null) {
                mSwatchShadow.setSelectedColor(mDraft.shadowColor);
            }
            if (mSwatchPanelColor != null) {
                mSwatchPanelColor.setSelectedColor(mDraft.floatWindowColor);
            }
            setSlider(mRowStrength, mDraft.shadowStrength);
            setSlider(mRowShadowRadius, mDraft.shadowRadius);
            setSlider(mRowHighlight, mDraft.activeHighlight);
            setSlider(mRowScale, Math.round(mDraft.activeScale * 10));
            setSwitch(mRowBlurEnabled, mDraft.inactiveBlurEnabled);
            setSlider(mRowBlurRadius, mDraft.inactiveBlurSteps);
            if (mRowBlurRadius != null) {
                mRowBlurRadius.root.setVisibility(mDraft.inactiveBlurEnabled ? View.VISIBLE : View.GONE);
            }
            setSwitch(mRowInactiveScaleEnabled, mDraft.inactiveScaleEnabled);
            setSlider(mRowInactiveScalePct, mDraft.inactiveScalePct);
            if (mRowInactiveScalePct != null) {
                mRowInactiveScalePct.root.setVisibility(mDraft.inactiveScaleEnabled ? View.VISIBLE : View.GONE);
            }
            setSegmented(mRowAlign, alignIndex(mDraft.textAlign));
            setSlider(mRowLineSpacing, Math.round(mDraft.lineSpacingDp * 2));
            setSlider(mRowWrapSpacing, Math.round(mDraft.wrapExtraSpacingDp * 2));
            // 状态栏字幕功能：真值来自草稿，但 SystemUI 未授权时会被 applyStatusbarSwitchEnabled
            // 覆盖成「关闭 + 置灰」（那个方法自己会再置一次 mSyncing）
            setSwitch(mRowStatusbar, mDraft.statusbarSubtitleEnabled);
            // 【2.2.7 / code 979】调试日志开关（纯日志闸门，不影响功能行为）
            setSwitch(mRowDebugLog, mDraft.debugLog);
            // 【2.2.9】液态玻璃开关
            setSwitch(mRowLiquidGlass, mDraft.liquidGlass);
            // 【2.2.11b】模糊强度（0–100）
            setSlider(mRowLiquidGlassBlur, mDraft.liquidGlassBlurPct);
        } finally {
            mSyncing = false;
        }
        updateHintMain();
        updateHintInactive();
        updateHintTypo();
        updatePreview();
        // 回灌完再按探测结果修一次状态栏开关的可用性（顺序不能颠倒：
        // 上面刚把它设成草稿值，会被这里按授权状态覆盖，这正是我们想要的优先级）
        applyStatusbarSwitchEnabled(mProbe != null && mProbe.systemUiAuthorized);
        // 【2.2.9】最后统一按「液态玻璃」重算一次「悬浮窗颜色」的可用性
        //（探测/导入/恢复默认都可能改到 mDraft，放最后保证它是终态）。
        applyPanelColorLock();
        // 【2.2.11b】同理，「模糊强度」滑条的显隐也放最后按终态收口。
        applyLiquidGlassBlurVisibility();
    }

    private void setSlider(@Nullable SliderRow row, int value) {
        if (row != null) {
            row.setValue(value);
        }
    }

    private void setSwitch(@Nullable SwitchRow row, boolean checked) {
        if (row != null) {
            row.sw.setChecked(checked);
        }
    }

    private void setSegmented(@Nullable SegmentedRow row, int index) {
        if (row != null) {
            row.setCheckedIndex(index);
        }
    }

    // ==================================================================
    // 恢复默认（PRD §9.1：属于**草稿**改动，仍需保存）
    // ==================================================================

    private void resetMainGroup() {
        mDraft.subtitleColor = SubtitleConfig.SUBTITLE_COLOR_DEF;
        mDraft.shadowColor = SubtitleConfig.SHADOW_COLOR_DEF;
        mDraft.shadowStrength = SubtitleConfig.SHADOW_STRENGTH_DEF;
        mDraft.shadowRadius = SubtitleConfig.SHADOW_RADIUS_DEF;
        mDraft.activeHighlight = SubtitleConfig.HIGHLIGHT_DEF;
        mDraft.activeScale = SubtitleConfig.ACTIVE_SCALE_DEF;
        syncWidgets();
        toast(Strings.RESET_DONE.get(mLang));
    }

    private void resetInactiveGroup() {
        mDraft.inactiveBlurEnabled = true;
        mDraft.inactiveBlurSteps = SubtitleConfig.BLUR_STEPS_DEF;
        mDraft.inactiveScaleEnabled = true;
        mDraft.inactiveScalePct = SubtitleConfig.INACTIVE_SCALE_PCT_DEF;
        syncWidgets();
        toast(Strings.RESET_DONE.get(mLang));
    }

    private void resetTypoGroup() {
        // v1.4 §16.3：「字重」已从界面删除，但配置键保留 —— 恢复默认时**仍然把它拉回默认值**，
        // 这样用户从旧版本（可能存过 regular / bold）升上来，点恢复默认也会把它清干净。
        mDraft.fontWeight = SubtitleConfig.WEIGHT_SYSTEM;
        mDraft.textAlign = SubtitleConfig.ALIGN_CENTER;
        mDraft.lineSpacingDp = SubtitleConfig.LINE_SPACING_DEF;
        mDraft.wrapExtraSpacingDp = SubtitleConfig.WRAP_SPACING_DEF;
        // §3.1.5：「恢复默认」小字里已列出「悬浮窗颜色 黑色」，这里必须真的把它恢复，
        // 否则小字写的是什么、按下去变的却是另一套（文案与行为必须一致）
        mDraft.floatWindowColor = SubtitleConfig.FLOAT_WINDOW_COLOR_DEF;
        syncWidgets();
        toast(Strings.RESET_DONE.get(mLang));
    }

    // ==================================================================
    // 小字说明（PRD DK-04：内容 = 该组默认值）
    // ==================================================================

    private void updateHintMain() {
        if (mHintMain == null) {
            return;
        }
        // 固定文案（拍板 MSU-08）：展示默认值，不随草稿值变化。
        mHintMain.setText(Strings.HINT_MAIN.get(mLang));
    }

    private void updateHintInactive() {
        if (mHintInactive == null) {
            return;
        }
        // 固定文案（拍板 INA-06）：展示默认值，不随草稿值变化。
        mHintInactive.setText(Strings.HINT_INACTIVE.get(mLang));
    }

    private void updateHintTypo() {
        if (mHintTypo == null) {
            return;
        }
        // 固定文案（拍板 TYP-08）：展示默认值，不随草稿值变化。
        mHintTypo.setText(Strings.HINT_TYPO.get(mLang));
    }

    private String alignLabel(String v) {
        if (SubtitleConfig.ALIGN_LEFT.equals(v)) {
            return Strings.ALIGN_LEFT.get(mLang);
        }
        if (SubtitleConfig.ALIGN_RIGHT.equals(v)) {
            return Strings.ALIGN_RIGHT.get(mLang);
        }
        return Strings.ALIGN_CENTER.get(mLang);
    }

    private static String alignValueOf(int index) {
        switch (index) {
            case 0:
                return SubtitleConfig.ALIGN_LEFT;
            case 2:
                return SubtitleConfig.ALIGN_RIGHT;
            case 1:
            default:
                return SubtitleConfig.ALIGN_CENTER;
        }
    }

    private static int alignIndex(String v) {
        if (SubtitleConfig.ALIGN_LEFT.equals(v)) {
            return 0;
        }
        if (SubtitleConfig.ALIGN_RIGHT.equals(v)) {
            return 2;
        }
        return 1;
    }

    // ==================================================================
    // FR-08 保存 / 草稿态
    // ==================================================================

    /**
     * 是否处于「草稿态」（PRD §9.1）：由草稿与已保存态的**参数比较**实时导出。
     *
     * ─────────────────────────────────────────────────────────────────────
     * v1.4 §6.3：**排除 uiLanguage**。
     *
     * 语言只影响设置页 UI 与预览示例字幕，改了它对悬浮窗毫无影响（FR-02 规则 2）。
     * 若把它算进「脏」，用户只是切个语言再返回，就会被弹「有未保存的更改」三选 ——
     * 那是给**外观改动**用的对话框，用在这里是误报。
     *
     * 实现口径：**临时把两侧的 uiLanguage 对齐再比**，而不是去改
     * {@code SubtitleConfig.sameParametersAs()}（那个方法在别处也用于「配置无变化」判定，
     * 语义上「整份配置一样」本来就该包含语言，改它会污染别处的判断）。
     * 这里的对齐不产生副作用：比较完立刻还原，两个对象都不会被写坏。
     *
     * ⚠️ 为什么可以放心地在这里动 mSaved/mDraft 的字段：
     *   · 本方法**只在主线程**被调用（控件回调 / 生命周期）；
     *   · uiLanguage 的**真实值**由 {@link #applyLanguage} 维护（切语言时两侧一起写），
     *     所以「对齐」取的是同一个值，还原回去也还是原值。
     * ─────────────────────────────────────────────────────────────────────
     */
    private boolean isDirty() {
        String draftLang = mDraft.uiLanguage;
        String savedLang = mSaved.uiLanguage;
        try {
            // 把两侧语言对齐成同一个值（取草稿的），再比「除语言外的参数」
            mDraft.uiLanguage = savedLang;
            return !mDraft.sameParametersAs(mSaved);
        } finally {
            mDraft.uiLanguage = draftLang;
            mSaved.uiLanguage = savedLang;
        }
    }

    /**
     * 草稿被改动后的统一收口。
     *
     * 「是否脏」是派生值，无需标志位；这里只负责把小字默认值说明刷到最新草稿值。
     * 回灌期间（{@link #mSyncing}）不做事，避免程序性赋值被当成用户改动。
     */
    private void markDirty() {
        if (mSyncing) {
            return;
        }
        updateHintMain();
        updateHintInactive();
        updateHintTypo();
        updatePreview();
    }

    private void doSave() {
        boolean dirty = isDirty();
        mDraft.uiLanguage = mLang.configValue();
        String err = mStore.save(mDraft);
        if (err != null) {
            // PRD §12 E-06：保存失败必须保留草稿 + 可读报错
            Log.w(TAG, "save failed: " + err);
            toast(Strings.SAVE_FAILED.format(mLang, err));
            return;
        }
        mSaved = mDraft.copy();
        toast((dirty ? Strings.SAVE_OK : Strings.SAVE_NO_CHANGE).get(mLang));
    }

    private void handleBack() {
        if (!isDirty()) {
            finish();
            return;
        }
        // PRD §12 E-05：三选「保存 / 不保存 / 取消」
        new MaterialAlertDialogBuilder(this)
                .setTitle(Strings.UNSAVED_TITLE.get(mLang))
                .setMessage(Strings.UNSAVED_MSG.get(mLang))
                .setPositiveButton(Strings.SAVE.get(mLang), (d, w) -> {
                    mDraft.uiLanguage = mLang.configValue();
                    String err = mStore.save(mDraft);
                    if (err != null) {
                        toast(Strings.SAVE_FAILED.format(mLang, err));
                        return; // 留在页面，草稿不丢
                    }
                    mSaved = mDraft.copy();
                    toast(Strings.SAVE_OK.get(mLang));
                    finish();
                })
                .setNeutralButton(Strings.DISCARD.get(mLang), (d, w) -> {
                    mDraft = mSaved.copy();
                    finish();
                })
                .setNegativeButton(Strings.CANCEL.get(mLang), null)
                .show();
    }

    // ==================================================================
    // 行构建原语
    // ==================================================================

    private interface OnInt {
        void onChanged(int value);
    }

    private interface OnBool {
        void onChanged(boolean value);
    }

    private interface ValueText {
        String format(int value);
    }

    /** 惰性取当前草稿值（避免语言切换重建行时把旧快照传进去）。 */
    private interface IntRef {
        int get();
    }

    /** 滑块行：标签 + 右侧读数 + 滑块。 */
    private static final class SliderRow {
        LinearLayout root;
        Slider slider;
        TextView value;
        ValueText formatter;

        void setValue(int v) {
            slider.setValue(v);
            refreshLabel();
        }

        void refreshLabel() {
            if (value != null && formatter != null) {
                value.setText(formatter.format(Math.round(slider.getValue())));
            }
        }
    }

    /** 开关行：标签 + MaterialSwitch。 */
    private static final class SwitchRow {
        LinearLayout root;
        MaterialSwitch sw;
    }

    /**
     * 分段按钮行。
     *
     * 【2.3.2 §1.2】v1.5 用 {@code MaterialButtonToggleGroup} + 把 style 资源当 defStyleAttr
     * 传进 MaterialButton，真机上三段全同色、选中态看不出来（Ari 判成「点选不可用」）。
     * 现在段落是普通 MaterialButton，选中态由 {@link SettingsActivity#paintSegments} 直接画，
     * 不再依赖任何库内部行为。
     */
    private static final class SegmentedRow {
        /** 承载三段的圆角「轨道」。 */
        LinearLayout group;
        /** 三段按钮本体。 */
        MaterialButton[] buttons;
        /** 把「第 index 段选中」重画一遍（语言切换 / 回灌草稿值时用）。 */
        SegRowSync sync;

        void setCheckedIndex(int index) {
            if (sync != null && buttons != null && index >= 0 && index < buttons.length) {
                sync.paint(index);
            }
        }
    }

    /** {@link SegmentedRow} 的重画钩子。 */
    private interface SegRowSync {
        void paint(int index);
    }

    /**
     * 色板行的三个引用（【2.2.9】「悬浮窗颜色」在液态玻璃开启时要**整行置灰**）。
     *
     * 为什么需要 {@code label}/{@code body}：{@link ColorSwatchRow} 只是「色点那一排」，
     * 拿不到它上方的标签与外面的容器；而置灰要的是「标签 + 色点一起变淡」。
     */
    private static final class ColorRow {
        TextView label;
        LinearLayout body;
        ColorSwatchRow swatch;
    }

    private SliderRow addSliderRow(LinearLayout parent, Strings label, int from, int to, int step,
                                   ValueText formatter, OnInt onChange) {
        beginRow(parent);
        SliderRow row = new SliderRow();
        row.formatter = formatter;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = makeRowLabel(label);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setMinWidth(dp(64));
        valueView.setMinHeight(dp(ROW_MIN_H_DP));
        valueView.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelLarge);
        valueView.setTextColor(attr(com.google.android.material.R.attr.colorPrimary, Color.BLACK));
        header.addView(valueView);
        row.value = valueView;

        root.addView(header);

        Slider slider = new Slider(this);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setValue(from);
        slider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        slider.setLabelFormatter(v -> formatter.format(Math.round(v)));
        // 【code 974 §1】M3 Expressive 形态：16dp 粗轨道 + 4×44dp 竖长手柄，
        // 手柄左侧 primary、右侧 secondaryContainer。见 applyExpressiveSlider 的注释。
        applyExpressiveSlider(slider);
        slider.addOnChangeListener((s, value, fromUser) -> {
            row.refreshLabel();
            if (fromUser && !mSyncing) {
                onChange.onChanged(Math.round(value));
            }
        });
        root.addView(slider);
        row.slider = slider;
        row.root = root;

        parent.addView(root);
        return row;
    }

    /**
     * 【code 974 §1】把一枚 Material 滑块改成 **M3 Expressive** 形态。
     *
     * ─────────────────────────────────────────────────────────────────────
     * Ari 2026-10-07 原话：「优化滑块呈现形式：M3 Expressive 的粗轨道和竖长手柄。
     * 手柄左侧为 primary，右侧为 secondaryContainer。可拖动改变数值。」
     *
     * 目标形态（M3E 规格）：
     *   · 轨道：**16dp 粗**的胶囊（旧版是 M3 的 4dp 细线）；
     *   · 手柄：**4dp 宽 × 44dp 高**的竖长圆角条（旧版是 20dp 直径的圆）；
     *   · 配色：手柄**左侧**已走过的一段 = {@code colorPrimary}；
     *           手柄**右侧**未走过的一段 = {@code colorSecondaryContainer}；
     *           手柄本体 = {@code colorPrimary}。
     *
     * ─────────────────────────────────────────────────────────────────────
     * 🔴 三条实现约束（改本方法前必读）
     *
     *  ① **手柄高度由 thumbRadius 决定，不由 Drawable 决定。**
     *     BaseSlider 的自定义手柄会被 `adjustCustomThumbDrawableBounds` 按
     *     `scale = 2×thumbRadius / max(固有宽, 固有高)` 归一化后塞进 2r×2r 的方框，
     *     所以固有尺寸只表达**宽高比**：本方法用 4:44 的竖条 + `thumbRadius = 22dp`
     *     ⇒ 落地就是 4dp × 44dp。想改手柄尺寸，改的是
     *     {@link #SLIDER_THUMB_H_DP}（半径跟着它走），不是 Drawable 的固有高。
     *
     *  ② **顺序：先 setThumbRadius，后 setCustomThumbDrawable。**
     *     缩放比例在「设置手柄」那一刻用**当时**的 thumbRadius 算一次；反过来的话
     *     会按默认半径（M3 是 10dp）算出一个小一圈的手柄。
     *
     *  ③ **不要给轨道描边 / 不要动 tick。**
     *     tick（刻度点）的显隐沿用旧观感，本轮 Ari 没有要求改动，故不碰。
     * ─────────────────────────────────────────────────────────────────────
     *
     * ⚠️ 参数随主题走：所有颜色都从 MD3 色槽现取（{@link #attr}），因此动态取色
     *    （壁纸 Tonal Spot）与深浅色切换后重建行时会自动跟着换，不写死十六进制。
     */
    private void applyExpressiveSlider(Slider slider) {
        int active = attr(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4);
        int inactive = attr(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        // ① 16dp 粗轨道
        slider.setTrackHeight(dp(SLIDER_TRACK_H_DP));
        // ② 手柄左右两段的颜色
        slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(active));
        slider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(inactive));
        // ③ 竖长手柄：半径先定，再装手柄（顺序不可换，见上面 ②）
        slider.setThumbRadius(dp(SLIDER_THUMB_H_DP / 2f));
        slider.setCustomThumbDrawable(makeExpressiveThumbDrawable(active));
    }

    /**
     * M3E 竖长手柄的**固有形状**：4dp × 44dp 的圆角竖条（圆角 = 半宽 ⇒ 胶囊端头）。
     *
     * ⚠️ 这里给的尺寸只是**比例基准**：BaseSlider 会按 2×thumbRadius 把它等比缩放，
     *    所以真正决定落地大小的是 {@code setThumbRadius}（见 {@link #applyExpressiveSlider}）。
     */
    private Drawable makeExpressiveThumbDrawable(int color) {
        GradientDrawable bar = new GradientDrawable();
        bar.setShape(GradientDrawable.RECTANGLE);
        bar.setColor(color);
        bar.setCornerRadius(dp(SLIDER_THUMB_W_DP / 2f));
        bar.setSize(dp(SLIDER_THUMB_W_DP), dp(SLIDER_THUMB_H_DP));
        return bar;
    }

    private SwitchRow addSwitchRow(LinearLayout parent, Strings label, OnBool onChange) {
        beginRow(parent);
        SwitchRow row = new SwitchRow();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setMinimumHeight(dp(ROW_MIN_H_DP));

        TextView labelView = makeRowLabel(label);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(labelView);

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!mSyncing) {
                onChange.onChanged(isChecked);
            }
        });
        root.addView(sw);
        row.sw = sw;
        row.root = root;

        parent.addView(root);
        return row;
    }

    /**
     * 【2.3.2 §1.1.2 / §1.2；code 974 §3 改配色】「对齐」分段按钮 —— MD3 形态，
     * 且**选中态必须一眼可辨**。
     *
     * ─────────────────────────────────────────────────────────────────────
     * Ari 在 code961 反馈「**对齐** 按钮点选不可用」。真机截图里三段全是同一个深青底，
     * 选中段与未选中段**完全一样** ⇒ 用户点完看不出有没有生效，判成「不能用」。
     * 根因同 {@link #makeTonalButton}：v1.5 把 style 资源当 defStyleAttr 传，
     * MaterialButtonToggleGroup 的 checked 态色没落地。
     *
     * 【code 974 §3 改口径】Ari 2026-10-07 指令：「对齐按钮：做一个**已选项为深重点色、
     * 未选项为浅重点色**的 Segmented。」于是两段**都有底**，深浅对比来自同一个「重点色」
     * （primary）的两个色阶：
     *   · 已选段：{@code colorPrimary} 底 + {@code colorOnPrimary} 字 —— 深重点色；
     *   · 未选段：{@code colorSecondaryContainer} 底 + {@code colorOnSecondaryContainer} 字
     *             —— 浅重点色（沿用旧版选中态那枚浅容器底，视觉语言不变）。
     * ⚠️ 因为两段都不再是透明底，旧版那圈 1dp {@code colorOutline} 描边已无意义、反而
     *    会在两段之间画出一道灰缝，故一并去掉（轨道背景保持全透明，只当圆角裁切用）。
     * ─────────────────────────────────────────────────────────────────────
     */
    private SegmentedRow addSegmentedRow(LinearLayout parent, Strings label, String[] options, OnInt onChange) {
        beginRow(parent);
        SegmentedRow row = new SegmentedRow();

        TextView labelView = makeRowLabel(label);
        labelView.setPadding(0, 0, 0, dp(2));
        parent.addView(labelView);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL_H_DP)));
        // 【code 974 §3】两段现在都是实底，旧版那圈 1dp colorOutline 描边会在两段之间
        // 画出一道灰缝，故去掉描边；轨道只保留「圆角裁切」这一个作用。
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(CONTROL_H_DP / 2f));
        trackBg.setColor(Color.TRANSPARENT);
        track.setBackground(trackBg);
        track.setClipToOutline(true);

        final MaterialButton[] segs = new MaterialButton[options.length];
        for (int i = 0; i < options.length; i++) {
            MaterialButton b = new MaterialButton(this);
            b.setText(options[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setStrokeWidth(0);
            b.setMinHeight(dp(CONTROL_H_DP));
            b.setMinimumHeight(dp(CONTROL_H_DP));
            b.setCornerRadius(dp(CONTROL_H_DP / 2f));
            b.setPadding(dp(2), 0, dp(2), 0);
            b.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            // id 固定为「顺序 + 1」：MaterialButtonToggleGroup 按 id 记选中，
            // 这样语言切换重建行后 setCheckedIndex 依然定位得到同一段。
            b.setId(i + 1);
            segs[i] = b;
            track.addView(b);
        }
        // 两段自己画：每次选中变化就把三段重刷一遍颜色（不依赖任何样式继承）。
        // 【code 974 §3】已选 = 深重点色（colorPrimary / colorOnPrimary）；
        //               未选 = 浅重点色（colorSecondaryContainer / colorOnSecondaryContainer）。
        final int checkedBg = attr(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4);
        final int checkedFg = attr(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
        final int plainBg = attr(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        final int plainFg = attr(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B);
        for (int i = 0; i < segs.length; i++) {
            final int idx = i;
            segs[i].setOnClickListener(v -> {
                paintSegments(segs, idx, checkedBg, checkedFg, plainBg, plainFg);
                if (!mSyncing) {
                    onChange.onChanged(idx);
                }
            });
        }
        row.group = track;
        row.buttons = segs;
        row.sync = index -> paintSegments(segs, index, checkedBg, checkedFg, plainBg, plainFg);
        paintSegments(segs, 1, checkedBg, checkedFg, plainBg, plainFg);

        parent.addView(track);
        return row;
    }

    /**
     * 把「第 index 段选中」这件事画到三段上（见 {@link #addSegmentedRow} 的口径）。
     *
     * 【code 974 §3】两段都是实底：已选走深重点色（checkedBg/checkedFg），
     * 未选走浅重点色（plainBg/plainFg）。旧版「未选 = 透明底」已废弃 ——
     * Ari 要的是「深浅两档重点色」，不是「有底 vs 没底」。
     */
    private static void paintSegments(MaterialButton[] segs, int index,
                                      int checkedBg, int checkedFg, int plainBg, int plainFg) {
        for (int i = 0; i < segs.length; i++) {
            boolean on = (i == index);
            segs[i].setBackgroundTintList(colorStateList(on ? checkedBg : plainBg));
            segs[i].setTextColor(colorStateList(on ? checkedFg : plainFg));
            segs[i].setSelected(on);
        }
    }

    /**
     * 色板行：**色块单行 + 行末的彩虹自定义色环**（2.3.1 §4.1.1 返工）。
     *
     * ─────────────────────────────────────────────────────────────────────
     * §4.1.1 原话（Ari 2026-10-05 第二次返工）：
     *   · 「自定义颜色/色板不要用超大按钮占位，用与固定颜色圈**一样大小**的图标显示即可」；
     *   · 「不要现在这种（圈比周围大一圈、也没显示色环那么直观）」；
     *   · 「颜色自定义出现**色板 + 编码输入**显示，不要现在的滑杆样式」。
     *
     * 本版把所有色板行末的自定义入口统一交给 {@link ColorSwatchRow#setCustomEntry}：
     * 画的是与色点**同直径（22dp 圆 / 34dp 热区）**的彩虹色相环，不再是一枚
     * outlined MaterialButton（那正是被点名的「黑线画个圈」）。
     * 点击后弹出 {@link #showCustomColorDialog}（HSV 色域 + 色相条 + HEX 输入）。
     *
     * @param initialRef      取「当前草稿色」的取值器（lambda 捕获 mDraft 字段即可）
     * @param previewAlpha    半透明预览 alpha（255 = 不透明；§3.1.5 的悬浮窗颜色行传 0x92）
     * @param showChecker     是否在色点圆内铺棋盘格（只有真正半透明时才需要）
     * ─────────────────────────────────────────────────────────────────────
     */
    private ColorRow addColorRow(LinearLayout parent, Strings label, int[] colors, String[] names,
                                 IntRef initialRef, OnInt onChange) {
        return addColorRow(parent, label, colors, names, initialRef, onChange, 255, false);
    }

    private ColorRow addColorRow(LinearLayout parent, Strings label, int[] colors, String[] names,
                                 IntRef initialRef, OnInt onChange,
                                 int previewAlpha, boolean showChecker) {
        int initial = initialRef.get();

        beginRow(parent);
        TextView labelView = makeRowLabel(label);
        labelView.setPadding(0, 0, 0, dp(6));
        parent.addView(labelView);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ColorSwatchRow row = new ColorSwatchRow(this);
        if (previewAlpha < 255) {
            // §3.1.5：色板圆点显示的是「面板顶部实际不透明度叠加后的效果」，不画实心色
            row.setTranslucentPreview(previewAlpha, showChecker);
        }
        row.setPalette(colors, names, initial, onChange::onChanged);
        body.addView(row);

        // §4.1.1：行末的**彩虹自定义色环**（与色点等大，圆心共线）
        final int[] holder = {initial};
        row.setCustomEntry(() -> showCustomColorDialog(label, holder[0], picked -> {
            holder[0] = picked;
            row.setSelectedColor(picked);
            onChange.onChanged(picked);
        }));
        row.setCustomSelected(!containsColor(colors, initial));

        parent.addView(body);

        // 【2.2.9】顺手把本行的三个引用交出去（「悬浮窗颜色」行需要整行置灰，
        //   而仅凭 ColorSwatchRow 拿不到它的标签与容器）。
        ColorRow out = new ColorRow();
        out.label = labelView;
        out.body = body;
        out.swatch = row;
        return out;
    }

    private static boolean containsColor(int[] palette, int color) {
        if (palette == null) {
            return false;
        }
        for (int c : palette) {
            if (c == color) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「恢复默认」行：胶囊按钮（↺ + 文字）+ 下方小字默认值说明（PRD §FR-04/05/06 + DK-04）。
     *
     * @return 小字 TextView（供实时刷新）
     */
    private TextView addResetRow(LinearLayout parent, Strings label, Runnable onReset) {
        beginRow(parent);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setMinimumHeight(dp(ROW_MIN_H_DP));

        TextView labelView = makeRowLabel(label);
        labelView.setTextColor(attr(com.google.android.material.R.attr.colorPrimary, Color.BLACK));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(labelView);

        // 【2.3.2 §1.1.1 / §1.1.2】重置按钮 = MD3 Tonal（浅容器底 + 深色字/图标），
        // 与对齐分段、导出/导入、底栏「重启系统界面」同一套视觉语言。
        //
        // 【code 975 §4】改用**小号**档（32dp，与页头语言胶囊同高）——
        // Ari 2026-10-07 晚指令：「重置按钮调整的和语言切换按钮一样大，现在的重置按钮
        // 占的位置有点太大了」。原来这里是 makeTonalButton（40dp 高 + 16dp 图标），
        // 在整卡末行确实偏大；换成 makeSmallTonalButton 后字号 12sp、图标 14dp，
        // 视觉重量与语言胶囊一致。点击热区仍由本行的 ROW_MIN_H_DP（40dp）补足。
        MaterialButton reset = makeSmallTonalButton(R.drawable.ic_restore_default);
        reset.setText(Strings.RESET.get(mLang));
        reset.setOnClickListener(v -> onReset.run());
        root.addView(reset);

        parent.addView(root);

        TextView hint = new TextView(this);
        hint.setTextAppearance(R.style.TextAppearance_DLsiteFloat_Hint);
        hint.setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        // 【2.3.2 §2.4】小字左对齐到与标签同一条左边线，底部留白由卡片 paddingBottom 收口，
        // 这里只留 2dp 的视觉呼吸位（§2.4 要求「提示底距 6dp」，卡片 6dp + 这里 2dp 会偏大，
        // 所以这里取 0，让整卡下留白恰好 6dp）；改用 ROW_GAP 上边距把提示与按钮行分开。
        hint.setPadding(0, dp(ROW_GAP_DP), 0, dp(HINT_BOTTOM_PAD_DP));
        parent.addView(hint);
        // 【code 974 §4 / 976 §1】这行小字是「本卡最下方的那行文字」⇒ 按 Ari 的口径，
        // 它的**字形底端**到卡片底边的距离 = 卡片首行字形顶距 × 2
        // （976 在 975 的「上下对称」口径上再翻一倍 —— Ari：「还是很紧，再扩宽一倍」）。
        pinCardLastBottom(parent, hint);
        return hint;
    }

    /**
     * 【code 974 §4 / 976 §1】把「卡片里最下方那一行」的**视觉底边**压到距卡片底边
     * {@code target} 处，其中 {@code target = 卡片首行文字的「字形顶」距卡片顶边的距离 × 2}。
     *
     * ─────────────────────────────────────────────────────────────────────
     * Ari 的两轮指令（这是同一条观感的两次收敛，口径一路在变宽）：
     *   · 【974】「所有卡片最下方选项文字距离卡片底端 改为 6dp。」（并明确指**字形**，不是控件盒）
     *   · 【975】「调整的和卡片内最上端边缘与第一个选项文字相同的距离一致。」⇒ 上下对称
     *   · 【976】「四个卡片内最下方的文字一直都和卡片底贴的**还是很紧**，**再扩宽一倍**。」
     *     ⇒ 在对称口径上**再×2**：底距 = 首行字形顶距 × 2。
     *
     * ── 为什么只把卡片 paddingBottom 调大达不到这个观感 ──────────────────────
     * 文本视图的**盒子底**与**墨迹底**之间天生隔着两段空档：
     *   ① `includeFontPadding`：盒子底取字体包围盒的 bottom，它一般比 descent 更低
     *      （CJK 字体尤其明显，多出约 2dp）；
     *   ② `lineSpacingMultiplier`（本项目的 Hint 样式是 1.15）：倍数余量会被算进
     *      **最后一行**的行高，于是又多在末行下方留出约 2.5dp。
     * 所以「盒子底 N dp」在真机观感上是 N+3~4dp，必须按**墨迹**量。
     *
     * ── 做法 ─────────────────────────────────────────────────────────────
     *   ① 先关掉 `includeFontPadding`（第 ① 段空档直接消失）；
     *   ② **行距倍数不动**（改了会影响小字的换行可读性），改为在**布局完成后**
     *      量出末行的真实墨迹底 / 盒底，用**底部外边距**把这个差补掉：
     *          curGap + bottomMargin 增量 = target   ← 恒等
     *      线性布局把 margin 计入总高 ⇒ 卡片高度随之变化，而文字/按钮的**位置**不受影响。
     *      ⇒ target 比当前底距大时就加**正** margin（底部留白变宽，这正是 976 要的）；
     *        比当前小时加**负** margin（等效于把内容下沉，975 曾用它做对称）。
     *   ③ 负的那一侧夹住（`floor`），避免把文字推出卡片下沿被裁。
     *
     * ── 末行是「文字」还是「控件盒」────────────────────────────────────────
     *   · **文字**（前三张卡：末尾是「恢复默认」下方的小字）⇒ 对齐**字形底**（要减掉
     *     字体自身的下空档，否则观感偏大）；
     *   · **容器 / 按钮行**（「其他」卡：末尾是导出+导入那排按钮）⇒ 对齐**盒底**，
     *     因为按钮自带填充背景，盒底就是视觉底边；硬去追按钮内文字的字形底会压穿按钮。
     *   两条路共用同一个 target 与同一套 margin 收敛，所以四张卡的底距口径完全一致。
     *
     * ⚠️ 这是**一次性**监听：量完立刻摘掉，否则「改外边距 ⇒ 再布局 ⇒ 再回调」会自激。
     * ─────────────────────────────────────────────────────────────────────
     */
    private void pinCardLastBottom(final LinearLayout group, final View last) {
        if (group == null || last == null) {
            return;
        }
        final boolean inkMode = last instanceof TextView;
        if (inkMode) {
            ((TextView) last).setIncludeFontPadding(false);
        }
        last.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (last.getViewTreeObserver().isAlive()) {
                            last.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        ViewGroup.LayoutParams lp = last.getLayoutParams();
                        if (!(lp instanceof LinearLayout.LayoutParams)) {
                            return;
                        }
                        // ① 目标底距 = 卡片**首行文字**的「字形顶」到卡片顶边的距离 × 2
                        //    （976：在 975 的「上下对称」口径上再翻一倍）
                        int target = topInkGapOf(group) * 2;
                        if (target <= 0) {
                            target = (dp(CARD_PAD_TOP_DP) + dp(ROW_MIN_H_DP) / 3) * 2; // 量不到时的保守值
                        }
                        // ② 当前「视觉底边」到卡片底边的距离
                        int bottomY;
                        if (inkMode) {
                            TextView tv = (TextView) last;
                            android.text.Layout layout = tv.getLayout();
                            if (layout == null || layout.getLineCount() <= 0) {
                                return;
                            }
                            CharSequence cs = tv.getText();
                            if (cs == null) {
                                cs = "";
                            }
                            int line = layout.getLineCount() - 1;
                            int start = Math.min(layout.getLineStart(line), cs.length());
                            int end = Math.min(layout.getLineEnd(line), cs.length());
                            if (end < start) {
                                end = start;
                            }
                            Rect ink = new Rect();
                            tv.getPaint().getTextBounds(cs, start, end, ink);
                            int baseline = layout.getLineBottom(line) - layout.getLineDescent(line);
                            bottomY = topRelativeTo(tv, group)
                                    + tv.getPaddingTop() + baseline + ink.bottom;  // 字形底
                        } else {
                            bottomY = topRelativeTo(last, group) + last.getHeight();   // 盒底
                        }
                        int curGap = group.getHeight() - bottomY;

                        // ③ 线性布局里「子 View 的 bottomMargin 增量」会等量变成「容器高度增量」，
                        //    而 bottomY 不受影响 ⇒ 想要的底距与当前的差就是该加的 margin。
                        int want = target - curGap;
                        // 只夹住「不要把内容压出卡片下沿」这一侧（宁可多留白，也不能裁字）。
                        int floor = -Math.max(dp(CARD_PAD_BOTTOM_DP), 0);
                        if (want < floor) {
                            want = floor;
                        }
                        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                        if (llp.bottomMargin == want) {
                            return;
                        }
                        llp.bottomMargin = want;
                        last.setLayoutParams(llp);
                    }
                });
    }

    // ── 【code 976 §5】底部彩蛋 ────────────────────────────────────────────

    /** 彩蛋行（滚到底后继续上拉才淡入），见 {@code activity_settings.xml} 的 {@code egg_smile}。 */
    private View mEggRow;
    /** 本次手势按下的 Y —— 「手指是否在继续上拉」用它与当前 Y 的差判断。 */
    private float mGestureDownY;
    /** 彩蛋当前是否已经淡入（避免同一手势里反复启动动画）。 */
    private boolean mEggShown;
    /** 触发彩蛋所需的**上拉距离**（dp）：太灵敏会误触，太大又要拉很久。 */
    private static final int EGG_PULL_DP = 16;
    /** 松手后彩蛋再保留多久才淡出（ms）—— 让用户看清这行小字。 */
    private static final long EGG_LINGER_MS = 1600L;
    /** 淡入 / 淡出的时长（ms）。 */
    private static final long EGG_FADE_MS = 180L;
    /** 松手后的延迟淡出任务（会被下一次手势取消）。 */
    private final Runnable mEggHide = () -> setEggShown(false);

    /**
     * 【code 976 §5】底部彩蛋：滚到最底后**继续上拉**，淡入一行居中小字 {@code SMILE! :D}。
     *
     * <p>Ari 2026-10-07 晚：「当用户把滑到页面最底端（就是「其他」卡片的位置最下面），
     * 如果用户此时再继续往下滑（手指运动动作向上拉），下面出现一行居中小字」。
     *
     * <p>── 为什么必须用**触摸监听**而不是滚动回调 ─────────────────────────
     * 滚到最底之后，继续上拉只会走 {@code NestedScrollView} 的 EdgeEffect 回弹，
     * **scrollY 一个像素都不再变** ⇒ 任何基于 scrollY / onScrollChange 的判断
     * 都看不见这个动作。能反映它的只有手势位移本身。
     *
     * <p>── 不干扰既有滚动 ───────────────────────────────────────────────
     * 监听器一律返回 {@code false}（不消费事件）⇒ NestedScrollView 的滚动、
     * AppBarLayout 的吸顶联动、以及预览卡的钉住行为全部不受影响。
     *
     * <p>── 触发 / 收回 / 松手 ───────────────────────────────────────────
     * · 已到底 且 上拉超过 {@link #EGG_PULL_DP} ⇒ 淡入；
     * · 手指改为下拉（pull &lt; 0）⇒ 立刻淡出（跟手收回）；
     * · 松手 ⇒ 延后 {@link #EGG_LINGER_MS} 再淡出（不是立刻消失，好让用户看清）。
     */
    private void setupScrollEgg() {
        mEggRow = findViewById(R.id.egg_smile);
        final View scroll = findViewById(R.id.settings_scroll);
        final View content = findViewById(R.id.settings_content);
        if (mEggRow == null || scroll == null || content == null) {
            return;
        }
        scroll.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mGestureDownY = ev.getY();
                    mEggRow.removeCallbacks(mEggHide);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    // 手指「向上拉」⇒ y 减小 ⇒ pull 为正
                    float pull = mGestureDownY - ev.getY();
                    if (pull > dp(EGG_PULL_DP) && isScrolledToBottom(v, content)) {
                        setEggShown(true);
                    } else if (pull < 0) {
                        setEggShown(false);   // 手指改往下拖 ⇒ 立刻收回
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mEggRow.removeCallbacks(mEggHide);
                    if (mEggShown) {
                        mEggRow.postDelayed(mEggHide, EGG_LINGER_MS);
                    }
                    break;
                default:
                    break;
            }
            return false;   // 不消费 ⇒ 滚动 / 吸顶一切照旧
        });
    }

    /** 是否已经滚到内容最底（留 2dp 容差，抵消像素取整）。 */
    private boolean isScrolledToBottom(View scrollView, View content) {
        int contentH = content.getHeight();
        int viewH = scrollView.getHeight();
        if (contentH <= 0 || viewH <= 0) {
            return false;
        }
        int max = contentH - viewH;
        return max <= 0 || scrollView.getScrollY() >= max - dp(2);
    }

    /** 淡入 / 淡出彩蛋行（幂等：同一状态重复调用不会重启动画）。 */
    private void setEggShown(boolean shown) {
        if (mEggRow == null || mEggShown == shown) {
            return;
        }
        mEggShown = shown;
        mEggRow.animate().cancel();
        mEggRow.animate().alpha(shown ? 1f : 0f).setDuration(EGG_FADE_MS).start();
    }

    /**
     * 【code 975】卡片**首行文字**的「字形顶」到卡片顶边的距离（px）。
     *
     * <p>用于让卡片末行内容的底距与它对称、再翻倍（见 {@link #pinCardLastBottom}）。
     * 首行可能是 {@code TextView}（色板行 / 分段行的标签直接挂在卡片上），
     * 也可能是 {@code LinearLayout}（滑块行 / 开关行先建了一行容器）——
     * 所以这里递归找**第一个可见的 TextView**。
     *
     * @return 距离（px）；找不到可量的文字时返回 -1
     */
    private int topInkGapOf(LinearLayout group) {
        TextView first = findFirstTextView(group);
        if (first == null || first.getLayout() == null || first.getLayout().getLineCount() <= 0) {
            return -1;
        }
        CharSequence cs = first.getText();
        if (cs == null || cs.length() == 0) {
            return -1;
        }
        android.text.Layout lay = first.getLayout();
        int start = Math.min(lay.getLineStart(0), cs.length());
        int end = Math.min(lay.getLineEnd(0), cs.length());
        if (end <= start) {
            return -1;
        }
        Rect ink = new Rect();
        first.getPaint().getTextBounds(cs, start, end, ink);
        int inkTop = lay.getLineBaseline(0) + ink.top;   // 首行字形顶（相对内容区顶）
        return topRelativeTo(first, group) + first.getPaddingTop() + inkTop;
    }

    /** 在子树里找第一个可见的 {@link TextView}（含自身）。 */
    @Nullable
    private static TextView findFirstTextView(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (v instanceof TextView) {
            return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findFirstTextView(g.getChildAt(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** 累加 {@code v} 各层 {@code getTop()}，得到它相对祖先 {@code ancestor} 的纵向偏移。 */
    private static int topRelativeTo(View v, View ancestor) {
        int y = 0;
        View c = v;
        while (c != null && c != ancestor) {
            y += c.getTop();
            android.view.ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return y;
    }

    // ── 【2.3.2 §2.3 / §2.6】行与行的统一「起手式」 ────────────────────

    /**
     * 每一行开始前调用：**非首行**先插一段纯留白。
     *
     * 【code 974 §2 改口径】旧版这里插的是「1dp 细分隔线 + 上下各 4dp 呼吸位」，
     * Ari 2026-10-07 明确要求「**不要**直接用分割线分开功能选项」，参考稿
     * （LSPosed 式列表）卡内也是「若干行 + 行间留白、一根线都没有」。
     * 于是把线整根换掉，留白量与旧版一致（见 {@link #ROW_SPACER_DP}）。
     * ⚠️ 首行仍然不加 —— 否则卡片顶边会凭空多出一段 9dp 的空白。
     */
    private void beginRow(LinearLayout parent) {
        if (parent.getChildCount() > 0) {
            parent.addView(makeRowSpacer());
        }
    }

    /**
     * 【code 974 §2】卡内行间**纯留白**占位（替代旧版的 1dp 分隔线）。
     *
     * ⚠️ 为什么仍要用一个真的 View、而不是给行加 margin：{@link #beginRow} 是在
     *    **那一行还没被造出来**的时候调用的，拿不到它未来的 LayoutParams，
     *    所以留白只能由「前一行之后的这段占位」承担。
     */
    private View makeRowSpacer() {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_SPACER_DP)));
        gap.setBackgroundColor(Color.TRANSPARENT);
        return gap;
    }

    /**
     * 行标签：统一 {@link #ROW_MIN_H_DP} 的最小高度 + 垂直居中。
     *
     * §2.6 要求「每个卡片内上边距以**文字**为准，与字幕排版上面距离一致」——
     * 只要所有行都钉同一个最小高度并居中，「首行文字顶边」在任何卡片里都是同一个值，
     * 不会再出现「开关行被 32dp 的开关撑高、文字被推低 6dp」的差异。
     */
    private TextView makeRowLabel(Strings label) {
        TextView v = new TextView(this);
        v.setText(label.get(mLang));
        v.setTextAppearance(R.style.TextAppearance_DLsiteFloat_RowLabel);
        v.setMinHeight(dp(ROW_MIN_H_DP));
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private int dp(float v) {
        // 【code 995】dp→px 口径收敛到 {@link Utils#dip2px}（本类原有 57 处调用）。
        return Utils.dip2px(this, v);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    /** 取 MD3 色槽值（PRD §15.2：禁止硬编码十六进制色值）。 */
    private int attr(int attrRes, int fallback) {
        try {
            return MaterialColors.getColor(findViewById(R.id.settings_root), attrRes);
        } catch (Throwable t) {
            try {
                TypedValue tv = new TypedValue();
                if (getTheme().resolveAttribute(attrRes, tv, true)) {
                    if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                            && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data;
                    }
                    return getResources().getColor(tv.resourceId, getTheme());
                }
            } catch (Throwable ignored) {
            }
            return fallback;
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(255 * SubtitleConfig.clampFloat(alpha, 0f, 1f));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static String colorName(int color, int[] palette, String[] names) {
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == color) {
                return names[i];
            }
        }
        return SubtitleConfig.argbToHex(color);
    }

    /** 滑块刻度（×10）→ 显示用倍数文本。 */
    private static String fmtScale(int tick) {
        return String.format(Locale.ROOT, "%.1f", tick / 10f);
    }

    private static String fmtDp(float v) {
        if (Math.abs(v - Math.round(v)) < 0.01f) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * 模糊半径读数（px）—— v1.4 §2.1.1 要求**显示 px**，且保留一位小数：
     * {@code 3.0px} / {@code 7.5px}。
     *
     * ⚠️ 与 {@link #fmtDp(float)} 刻意不同：dp 值在整数时省略小数（{@code 0 dp}），
     *    但模糊半径**必须始终带一位小数** —— 「3px」和「3.0px」在 0.5 步长的滑块上
     *    会让人怀疑「是不是只能整数」，写死一位小数才能表达出「半档也是有效的刻度」。
     */
    private static String fmtOneDecimal(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
