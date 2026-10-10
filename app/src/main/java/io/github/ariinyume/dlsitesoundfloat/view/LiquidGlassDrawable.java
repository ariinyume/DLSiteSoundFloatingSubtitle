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

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * 「液态玻璃」自渲染背景（【2.2.10】按 ColorOS17 WLAN 弹窗实测口径重构）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 这是什么 / 不是什么（改本类前必读）
 *
 * ① **背景的"真实模糊"由别的类负责，本类只画"玻璃本身"。**（【2.2.12】起）
 *    这是本类与前一版最大的区别，改之前务必先分清职责：
 *      · **背景模糊** → {@code util/BackdropBlur}：直接把模糊设到**我们自己的图层**上
 *        （{@code setBackgroundBlurRadius} + {@code setBlurRegions}），由合成器执行。
 *        真机已验收：面板背后是真糊过的画面，可调、可全局、零权限、零额外功耗。
 *        因此本类**不再自己采背景、也不再自己糊位图**；
 *      · **玻璃本身**（底色/受光/边光/颗粒）→ 本类。
 *      · **背景亮度** → 由设置页「环境背景亮度」滑条给出（{@link #setBackdropLum}），
 *        只用来自适应霜面，**不贴位图、不采样**。
 *        ⚠️【code 1003】原先那套「采宿主窗口均值亮度 + 上下缘颜色」已整体删除：
 *        亮度改由用户给定，光圈也固定为冷白色（{@link #P_RIM_TOP}）。
 *    历史坑（避免重新踩）：窗口标志 {@code FLAG_BLUR_BEHIND} 在这台机上只有整屏效果，
 *    且 App 侧显式设置模糊区域**实测无效**；公开的 {@code View#setBackgroundBlurRadius}
 *    在本 ROM 里**已被删除**。详见 {@code ColorOS17_液态玻璃_悬浮窗可行性分析.md} §7。
 *
 * ② **v2（本版）为什么大改：上一次的"灰泥感"是被自己叠出来的。**
 *    用 ColorOS17 WLAN 弹窗截图与本插件悬浮窗截图逐像素对照（两者面板尺寸几乎相同，
 *    1052×1167 vs 1124×1172，密度 3.0），量得：
 *      · 参考弹窗：填充亮度 **0.52–0.57**，只比它背后的模糊背景高约 +0.15；
 *        边缘是**一条 1px 亮线**（上缘 +0.11、下缘是更宽的柔和受光），**没有泛光**；
 *        面内基本是**平的**（无条纹、无大块反光）。
 *      · 我们 v1：填充 **0.585–0.681** —— 被「整块径向反光 + 斜向焦散条纹 + 顶部乳白薄雾」
 *        从基色的 ~114 一路抬到 ~165，于是变成"浅灰泥"，而且**白字对比度大幅下降**。
 *    本版据此把面内层数砍到最少：近**平的**底色 + 均匀薄雾 + 顶部一条软受光 +
 *    底部薄受光 + **细噪点霜化**（真实磨砂玻璃的微颗粒感，参考弹窗放大也能看到颗粒）；
 *    换掉泛光为**一条 1px 渐变边光**（上缘最亮、下缘最暗，与参考同向）。
 *
 * ③ **为什么不像参考那样做成"浅色霜面"（重要取舍）**
 *    参考弹窗是**浅色霜面**，那是因为它背后是系统给的**已模糊的暗背景**，所以落点约 0.53。
 *    本插件的浮窗是 overlay，背后可能是**纯白页面**（DLsiteSound 的浅色页实测就是 253）。
 *    同样的浅霜叠在 253 上会落到 0.8 上下 —— 白色字幕直接不可读。
 *    所以本类坚持**烟熏中性玻璃**（暗底冷灰），保证白字在任意背景上都有对比度；
 *    观感靠"干净平面 + 1px 边光 + 颗粒霜化"去贴近参考，而不是靠把填充调亮。
 *
 * ④ **它不依赖任何 API 31/33+ 的新特性**（{@code RuntimeShader} / {@code RenderEffect}），
 *    全部是 API 1 就有的 Canvas / Shader 绘制，模块 minSdk = 24 同样满足。
 *
 * ⑤ **本类禁止引用任何模块资源 ID**（运行在目标进程，那里的 Resources 里没有本模块资源）。
 * ─────────────────────────────────────────────────────────────────────
 *
 * ── 三种形态（{@link #KIND_*}）───────────────────────────────────────
 *   · {@link #KIND_PANEL}       —— 悬浮窗面板：烟熏中性玻璃，配白色字幕；
 *   · {@link #KIND_CAPSULE_ON}  —— 播放页胶囊「开」态：紫玻璃；
 *   · {@link #KIND_CAPSULE_OFF} —— 播放页胶囊「关」态：深紫黑玻璃。
 */
public class LiquidGlassDrawable extends Drawable {

    public static final int KIND_PANEL = 0;
    public static final int KIND_CAPSULE_ON = 1;
    public static final int KIND_CAPSULE_OFF = 2;

    // ==================================================================
    // 调色（每形态一组；alpha 已按各层叠加后的最终观感调好）
    // ==================================================================

    // —— 面板：烟熏中性玻璃（近乎平的中性暗灰，冷调极轻）——
    //
    // ⚠️ 这两档 alpha 是被「白字对比度」钉住的，不是随便挑的：
    //    在纯白页面（实测 253）上，面板中央最终落在约 0.49 亮度（≈ 4:1 对比度），
    //    而旧版 GlassPanelDrawable 档是约 0.57（≈ 3.2:1）。再往下调会让面板
    //    "看起来更高级"，但白字幕就要开始糊了 —— 两档 alpha 就是这条界线。
    /** 顶部底色 61%（与底部只差 3% ⇒ 面内近"平"，与参考弹窗一致）。 */
    private static final int P_BASE_TOP = 0x9C101217;
    /** 底部底色 58%。 */
    private static final int P_BASE_BOTTOM = 0x9414161C;
    /** 均匀薄雾 5%：给暗背景上的玻璃一个"亮度地板"，避免落在纯黑里失去玻璃感。 */
    private static final int P_VEIL = 0x0DFFFFFF;
    /**
     * 顶部软受光 14%。**只覆盖上缘 5% 高度**（「模糊强度」会把它拉宽，见 {@link #setBlurPct}）。
     *
     * ⚠️ 高度是量出来的：参考弹窗的面内在顶部约 10px 内是 148、到 12% 高度就落到 138，
     *    也就是说它的"顶部受光"只有**十来个像素**；v2 初版铺了 22% 高度（257px），
     *    那会把面板上四分之一整片提亮，正是"灰"的残留来源。
     */
    private static final int P_GLOSS = 0x24FFFFFF;
    /** 顶部受光的高度占比（相对面板高）。 */
    private static final float P_GLOSS_H_RATIO = 0.05f;
    /** 底部薄受光 9%（参考弹窗下缘约 6px 略亮于中央，这里同向且同样窄）。 */
    private static final int P_BOTTOM_LIGHT = 0x16FFFFFF;
    /** 底部受光的高度占比。 */
    private static final float P_BOTTOM_LIGHT_H_RATIO = 0.025f;
    /**
     * 折射边光（= 用户要的「边缘光圈」）：上缘 69%。
     *
     * ⚠️ 【2.2.12b】从 46% 提到 69%：46% 是照着"参考弹窗实测 +0.17"调的，那在**静止截图**上
     *    是对的，但真机使用中 Ari 反馈"完全看不到光圈" —— 浮窗是压在**运动画面**上的，
     *    参考弹窗是压在静止设置页上的，两者对边光的可辨识度要求不同。取更亮的一档。
     *
     * 【code 1003】**光圈固定为冷白色**（不再是纯白、也不再随背景上下缘染色）：
     * 底色取略偏蓝的冷白 {@code 0xEFF4FF}，观感比纯白更"薄"、更像玻璃边缘的折射。
     */
    private static final int P_RIM_TOP = 0xB0EFF4FF;
    /** 折射边光：下缘 42%（同步提亮，保持"上亮下暗"的关系不变）。 */
    private static final int P_RIM_BOTTOM = 0x6BEFF4FF;
    /** 内圈二次折射（更淡的一圈，给玻璃边"厚度层次"）。 */
    private static final int P_RIM_INNER_TOP = 0x0CEFF4FF;
    /** 【2.2.12b】光圈描边宽度（dp）。1dp 在 3x 屏上只有 3px，压在运动画面上太细。 */
    private static final float RIM_WIDTH_DP = 1.4f;
    private static final int P_RIM_INNER_BOTTOM = 0x10EFF4FF;
    /** 颗粒霜化强度（白点 alpha 上限）：均值约 5%。 */
    private static final int P_GRAIN_MAX_ALPHA = 0x1C;

    // —— 【2.2.11】自适应霜面（有真实背景时）——
    //
    // 有背景位图时不再用固定的深色底，而是按**背景的均值亮度**把面板压到一个统一的落点：
    //   result = backdrop × (1-a) + veilColor × a，令 result ≈ TARGET_LUM。
    // 于是：亮背景（白色页面）自动上深色霜 → 面板仍是中灰、白字可读（与原来一致）；
    //       暗/彩色背景（封面、深色页）自动上白色霜 → 面板变亮、背景的模糊色透出来，
    //       这正是 ColorOS17 那种「深色背景下是一块发亮的玻璃」的观感。
    // a 在背景亮度恰为 TARGET_LUM 时连续归零（不上霜，画面本体即玻璃），因此**没有跳变**；
    // 此时仍靠顶部受光 / 边光 / 霜化颗粒表达"这是玻璃"，不会退化成"贴了张图"。
    //
    // ⚠️【989 关键修正：霜量必须**反解**，不能沿用一个斜率】
    //    2.2.11 ~ 2.2.14 里霜量写的是「斜率 × 与目标亮度的差」，它只在"霜不被任何东西
    //    缩放"时才恰好把面板压到目标亮度；而 2.2.13 给霜面加了一层 ×fill（模糊强度）
    //    之后，实际落点系统性偏浅（默认 55% 模糊时只有约四成补偿）。后果不是"不好看"，
    //    而是**面板亮度跟着背景走**：真机日志（`LSPosed_20261009_113557`）实测宿主
    //    两个页面的采样均值在 0.4259 ↔ 0.5300 之间来回切，当前落点分别是 0.440 / 0.493，
    //    即每次切页面板亮度跳约 0.053（≈13/255）—— Ari 反复反馈的
    //    「宿主内来回切页，浮窗亮度还是会跳」正是它。
    //    989 起直接解方程（见 {@link #draw}）：α 由背景亮度**唯一确定**，
    //    面板亮度在任何背景上都钉在 {@link #TARGET_LUM}，切页时只有背景的**色相**
    //    透过玻璃变化，亮度不再跳。
    //
    // ⚠️【990：目标值由 0.50 降到 0.42 —— "较暗背景整体发白"的返工】
    //    989 先把目标定成了 0.50，那是**错的**：0.50 是旧斜率曲线在**亮背景侧**的落点，
    //    把它当成恒定目标，等于把暗背景也硬拉到同一个亮度上。真机复测（录屏
    //    `Record_2026-10-09-12-15-46` + 日志 `LSPosed_20261009_121641`）实测：
    //    宿主采样均值 0.39 的紫色页上，面板从旧版的 **0.421 抬到 0.508**（亮 22 级灰度），
    //    Ari 的判词是「整体发白」——白字对比度也跟着掉了。
    //    目标改取"用户一直没抱怨过的那一档"：旧斜率曲线在暗背景侧给出的 0.42。
    //    于是同一个页面上霜面从 22% 白降到 **8%** 白，面板回到 0.42；
    //    亮背景侧（纯白页）则由旧版的 0.58 收到 0.42，白字对比度**同时变好**
    //    （这与 2.2.14 "空白页面几乎有点看不太清字幕"的诉求同向）。
    //    钉住亮度这条机制一字未动 —— 切页仍旧不跳，只是整块的落点更暗、更"烟熏"。
    /** 面板最终亮度的目标落点（见上方 990 段的标定依据）。 */
    private static final float TARGET_LUM = 0.42f;
    /**
     * 【991】霜面的**固定密度**（有真实背景时，面内那一层"玻璃料"的不透明度）。
     *
     * ── 为什么密度要固定下来（本版最关键的一处口径变化）──────────────
     * 989/990 的写法和本值相反：霜色固定成白/黑两种，由**霜量**去反解亮度。
     * 面板亮度确实钉住了，但代价是霜量必须随背景大幅变化 —— 按真实页面算：
     * 深色页（背景 0.25）要蒙 24% 白霜、亮封面页（0.73）要蒙 45% 黑霜。
     * 于是切页时面板虽然亮度一样，**观感却在"发白 ↔ 发暗"之间跳**，
     * 这正是 Ari 2026-10-09 第三次反馈的「亮度跳变还蛮明显」。
     * 物理上这是必然的：**单层半透明面板不可能同时做到"亮度恒定"和"糊度恒定"**。
     *
     * 991 选择"糊度恒定"：霜量写死（本值 × 模糊强度），改由**霜色**把亮度补到目标值
     * （见 {@link #draw} 的反解）。于是"亮度"和"糊度"都不随页面动。
     *
     * ⚠️【992：0.45 → 0.22 —— "玻璃颜色变奇怪"的返工】
     *    991 为了让**最亮的页面**也能被压到 {@link #TARGET_LUM}，把本值抬到了 0.45
     *    （依据：{@code (1-α)·X ≤ TARGET}，本机最亮背景 ≈0.73 ⇒ α ≥ 0.43）。
     *    但密度一大，霜面就只能用**灰**去补亮度 —— 面板变成一层 45% 的灰/灰紫水洗色，
     *    把页面本身的颜色洗掉了。真机录屏（`Record_2026-10-09-12-49-42`）实测：
     *    亮页上几乎发白灰、紫页上发灰紫，Ari 的判词是「玻璃的颜色反而变奇怪了，
     *    不如之前的版本」。
     *
     *    所以本值改取 **0.22**：玻璃仍只占两成二，**背景的颜色以约 78% 的比例透出来**
     *    （之前的版本之所以"颜色对"，正是因为透出比例高）。代价是**很亮的页面压不到
     *    目标亮度**了 —— {@code X > TARGET/(1-α) ≈ 0.54} 的页面会停在 {@code (1-α)·X}
     *    上（例如 0.73 的亮封面页落在 0.57），这与 990 之前的老版本同一量级，
     *    也正是用户一直没抱怨过的那一档观感。
     *    ⚠️ 本值只决定"玻璃占多少"，不再承担"把最亮页也压暗"的职责；
     *       往上调＝更实更暗但会洗色，往下调＝更透、颜色更接近页面本身。
     *    霜色同时改回**中性冷灰**（去掉 991 那套"取背景色相"的做法 —— 那是"变奇怪"的另一半）。
     */
    private static final float ADAPTIVE_VEIL_ALPHA = 0.22f;
    /**
     * 【989】「玻璃材质量」的淡出起点（以 {@code fill} 计）。
     *
     * {@code fill = (blurPct/100)^FILL_EXP}；本值 0.20 对应模糊强度约 16%。口径：
     *   · {@code fill ≥ 0.20}（模糊强度 ≳16%）⇒ 霜面按 {@link #ADAPTIVE_VEIL_ALPHA} 全量铺；
     *   · {@code fill < 0.20} ⇒ 霜面按比例淡出 —— 保住「模糊强度 0% = 全透明」这条既有语义
     *     （2.2.13 的原始需求）。
     */
    private static final float MATERIAL_FADE_FILL = 0.20f;
    /**
     * 【2.2.14】玻璃底上恒定的一层薄黑（8%）。
     *
     * Ari：「帮我在液态玻璃的底上加一点点黑色底」——自适应霜面要等背景统计回来才生效、
     * 且亮侧压到 0.32 就到头了；这一层与背景亮度无关，给白字一个最低对比度地板，
     * 同时让玻璃本体更像"一块有厚度的材质"而不是纯色片。
     * 与所有面内填充一致 ×fill（模糊强度 0% 时不留，见 FILL_EXP）。
     */
    private static final int ADAPTIVE_BASE_BLACK = 0x14000000;

    // —— 【2.2.13】"玻璃填充量"随「模糊强度」走 ——
    //
    // Ari 的需求原文：「模糊度调到 0% 的时候，应该是全透明的，但是悬浮窗背景它会有一点发白」。
    // 旧实现里霜面 alpha 只由背景亮度决定，与滑条**完全无关** —— 0% 时真实模糊已经关了，
    // 可霜面还在，深色页上就剩下一层白蒙蒙（发白）。
    // 现在：填充量 fill = blurPct/100，作用在**所有"面内填充"**上——
    //   自适应霜面 / 静态底色 / 均匀薄雾 / 霜化颗粒，全部乘 fill；
    //   0% ⇒ 全部归零 = 全透明（只剩顶部受光与边光表达"这是玻璃"）；
    //   100% ⇒ 与旧观感一致。顶部受光 / 底部受光 / 边光**不缩**（它们是玻璃本体，不是霜）。
    private static final float FILL_EXP = 0.75f;

    /**
     * 【2.2.13】接触阴影贴着玻璃那一圈的不透明度上限（0–255）。
     *
     * ⚠️【2.2.14 由 64 降到 34】Ari 第二次真机反馈「中心有一个长方形透明度比四周高，
     *   四周这一圈可以删掉吗」—— 旧实现的两处错让这一圈看起来是一道**黑框**而不是阴影：
     *   ① 描边宽度取 1.9×圈距 ⇒ 相邻圈大面积互相叠加，而叠加是**累乘**的，
     *      贴边那圈实际落到 40%–45% 黑（远高于标识的 25%）；
     *   ② 峰值又偏高，于是"四周一圈"比"中间"明显更暗，边界又是一条硬线。
     *   现在：圈宽改成 ≈1.02×圈距（不叠加、各圈只负责自己那一环的 alpha），
     *   峰值 41 ≈ 16%（贴玻璃处最实，向外 12dp 内衰减到 0）—— 这才是
     *   「四周有一点阴影、把整个按钮托起来」的量级：亮页面看得见阴影，
     *   暗页面几乎不可见（黑上叠黑），且不再有硬边。
     */
    private static final int SHADOW_MAX_ALPHA = 41;

    /** 接触阴影的圈数（圈数越多剖面越平滑，绘制代价线性增长；7 圈肉眼已看不出台阶）。 */
    private static final int SHADOW_RINGS = 7;

    /**
     * 边光的"衰减分界"（相对面板高）。
     *
     * ⚠️ 这是 v2 的一个关键发现：**参考弹窗的左右边缘中点是几乎没有边光的**
     *    （左缘实测 142 vs 面内 143，等于没有）。也就是说它的边光是**横向上下两条**，
     *    而不是套一圈。用「上缘亮 → 30% 处归零 → 70% 处仍为零 → 下缘再亮」这条
     *    四档渐变就能精确表达；若用普通两点渐变，左右边缘中点会得到 12% 左右的白线，
     *    玻璃就变成"描了一圈边"的卡片，与参考的观感不同。
     */
    private static final float RIM_FADE_TOP = 0.30f;
    private static final float RIM_FADE_BOTTOM = 0.70f;

    // —— 胶囊「开」：紫玻璃（沿用设计稿 #584179 的色相）——
    private static final int CON_BASE_TOP = 0xE86B4FA8;
    private static final int CON_BASE_BOTTOM = 0xE2493577;
    private static final int CON_GLOSS = 0x26FFFFFF;
    private static final int CON_RIM_TOP = 0x7AFFFFFF;
    private static final int CON_RIM_BOTTOM = 0x1FFFFFFF;

    // —— 胶囊「关」：深紫黑玻璃（沿用设计稿 #212042 的色相）——
    private static final int COFF_BASE_TOP = 0xE031305F;
    private static final int COFF_BASE_BOTTOM = 0xE01A1938;
    private static final int COFF_GLOSS = 0x1CFFFFFF;
    private static final int COFF_RIM_TOP = 0x56FFFFFF;
    private static final int COFF_RIM_BOTTOM = 0x14FFFFFF;

    // ==================================================================
    // 颗粒霜化贴图（进程内共享、只建一次）
    // ==================================================================

    /** 128×128 的白点噪点，alpha 随机 0..{@link #P_GRAIN_MAX_ALPHA}。 */
    private static volatile Bitmap sGrain;

    private static Bitmap grainBitmap() {
        Bitmap b = sGrain;
        if (b != null) {
            return b;
        }
        synchronized (LiquidGlassDrawable.class) {
            if (sGrain == null) {
                try {
                    final int n = 128;
                    int[] px = new int[n * n];
                    // 固定种子：每次装机颗粒完全一致（避免"每次重启纹理都变"的廉价感，
                    // 也便于截图前后对照）
                    Random rnd = new Random(0x20261008L);
                    for (int i = 0; i < px.length; i++) {
                        int alpha = rnd.nextInt(P_GRAIN_MAX_ALPHA + 1);
                        px[i] = (alpha << 24) | 0x00FFFFFF;
                    }
                    sGrain = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    // 建贴图失败就退化成"没有颗粒"的平玻璃，绝不让它影响绘制主流程
                    return null;
                }
            }
            return sGrain;
        }
    }

    // ==================================================================
    // 状态
    // ==================================================================

    private final int kind;
    private final float radiusPx;
    private final float density;

    /**
     * 【2.2.13】玻璃外阴影的"环宽"（px）。0 = 不画阴影（胶囊 / 设置页预览 / 旧调用方）。
     *
     * 面板档用一个正值：窗口比玻璃大一圈，这一圈由本类画成柔和的**接触阴影**，
     * 玻璃因此看起来"浮"在页面上（Ari：ColorOS17 的液态玻璃按钮有一点阴影、
     * 看得出来是悬浮在页面上的；旧版是整块融进背景）。面内子视图由
     * {@code FloatingSubtitleView} 用同样的内边距让开这一圈。
     */
    private final float shadowInsetPx;

    private final RectF rect = new RectF();
    private final RectF bounds = new RectF();
    private final RectF tmp = new RectF();
    private final Path clipPath = new Path();
    private final Path rimPath = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Shader grainShader;
    private int globalAlpha = 255;

    // ── 【2.2.12】系统背景模糊 + 背景统计 ──
    /**
     * 系统是否已经把「**本图层背后**」的内容模糊掉了。
     *
     * 由 {@code util/BackdropBlur} 申请、{@code FloatingSubtitleView} 告知本类。
     * 这是本版最重要的一处变化：**真正的背景模糊由合成器做**（零采集、零权限、
     * 零额外功耗，且天生全局），本类只负责"玻璃本身"，不再自己糊一张背景位图。
     */
    private boolean systemBlurActive = false;
    /** 背景均值亮度（0..1）；< 0 = 未知（用户没给值）。用于自适应霜面。 */
    private float backdropMeanLum = -1f;
    /** 【2.2.12】仅供设置页预览的背景位图（真窗恒为 null，见 {@link #setPreviewBackdrop}）。 */
    private Bitmap previewBackdrop;
    private final RectF pvDst = new RectF();

    /**
     * 【2.2.11b】模糊强度（0–100，默认 60）。
     *
     * ⚠️ 系统背景模糊生效时**本类不参与**（模糊半径由 {@code util/BackdropBlur} 直接设给图层，
     *    见 {@code FloatingSubtitleView}）—— 否则用户拉滑条会看到两处叠加、因果关系说不清。
     *    只有在系统模糊**没生效**的兜底档，它才用来柔化本类自己的受光与边光
     *    （强度越高 → 顶部受光带越宽、边光衰减越缓、内圈细边光越淡 ⇒ 越"磨砂"）。
     */
    private int blurPct = 60;

    // ==================================================================
    // 【code 1000】用户可调的两个旋钮（默认 = 上面那几个标定常量，零回归）
    // ==================================================================
    //
    // 由 SettingsActivity 的两个滑条驱动（配置项 liquid_glass_panel_lum /
    // liquid_glass_transparency），经 {@link #setPanelTuningFromPct} 换算到这里。
    // ⚠️ 不调用 setter 时，三个字段恒等于历史标定值 ⇒ 观感与改动前**完全一致**。
    //
    //   ① panelTargetLum —— 面板最终落在多亮（自适应霜面的目标落点）
    //   ② panelVeilAlpha —— 霜面密度（越小越透）
    //   ③ panelBaseBlack —— 薄黑底的 alpha（越大越实，也给白字更高对比度）
    //
    // 「背景透出率」= (1 − panelBaseBlack的α) × (1 − panelVeilAlpha)，
    // 它同时决定「明暗稳不稳」：透出越多，面板越跟着背后的页面走
    // （可压上限 = panelTargetLum / 透出率，超出上限的亮页会压不到目标）。

    /** 面板最终亮度的目标落点（默认 0.42，见 {@link #TARGET_LUM}）。 */
    private float panelTargetLum = TARGET_LUM;
    /** 霜面密度（默认 0.22，见 {@link #ADAPTIVE_VEIL_ALPHA}）。 */
    private float panelVeilAlpha = ADAPTIVE_VEIL_ALPHA;
    /** 薄黑底颜色（默认 0x14000000，见 {@link #ADAPTIVE_BASE_BLACK}；只有 alpha 有意义）。 */
    private int panelBaseBlack = ADAPTIVE_BASE_BLACK;

    /** 面板玻璃（烟熏中性）。{@code density} 用于把「1dp 描边」换算成 px。 */
    public LiquidGlassDrawable(float radiusPx, float density) {
        this(radiusPx, density, 0f);
    }

    /**
     * 【2.2.13】带外阴影的面板构造。
     *
     * @param shadowInsetPx 玻璃四周的阴影环宽（px）；窗口（Drawable bounds）比玻璃大
     *                      2×该值，多出的一圈画柔和接触阴影。0 = 与旧观感一致。
     */
    public LiquidGlassDrawable(float radiusPx, float density, float shadowInsetPx) {
        this(KIND_PANEL, radiusPx, density, shadowInsetPx);
    }

    public LiquidGlassDrawable(int kind, float radiusPx, float density) {
        this(kind, radiusPx, density, 0f);
    }

    public LiquidGlassDrawable(int kind, float radiusPx, float density, float shadowInsetPx) {
        this.kind = kind;
        this.radiusPx = radiusPx;
        this.density = density > 0f ? density : 3f;
        this.shadowInsetPx = Math.max(0f, shadowInsetPx);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 播放页胶囊的便捷构造（{@code on} = 开/关态）。 */
    public static LiquidGlassDrawable capsule(float radiusPx, float density, boolean on) {
        return new LiquidGlassDrawable(on ? KIND_CAPSULE_ON : KIND_CAPSULE_OFF,
                radiusPx, density);
    }

    /**
     * 【2.2.12】告知本类「系统背景模糊是否已生效」（由 {@code FloatingSubtitleView} 在
     * {@code util/BackdropBlur.apply()} 之后调用）。
     *
     * 它决定面板是"压在**真糊过**的背景上"（可以很透）还是"没有背景可透"（必须自己给出
     * 足够的底色保证白字可读）。两者是**互斥**的两套填充策略，所以必须显式告知。
     */
    public void setSystemBlurActive(boolean active) {
        if (systemBlurActive == active) {
            return;
        }
        systemBlurActive = active;
        invalidateSelf();
    }

    public boolean isSystemBlurActive() {
        return systemBlurActive;
    }

    /**
     * 【code 1000】按用户的两个滑条值（0–100）换算并设置面板观感参数。
     *
     * <p><b>明暗度</b>（0–100，默认 50）—— 分段线性，分段点即默认值：
     * {@code pct ≤ 50 ⇒ 0.20 + pct × 0.0044}（0 → **0.20** 最暗）、
     * {@code pct > 50 ⇒ 0.42 + (pct − 50) × 0.0036}（100 → **0.60** 最亮）；
     * **默认 50 严格 = 0.42**，与历史标定一致（【code 1003】分段点由 40 随默认值移到 50）。
     * ⚠️ 面板的**实际**亮度还受「通透度」限制：透出率高时面板基本等于背后页面的亮度，
     * 想暗下去必须同时把通透度往「实」调（可压下限 ≈ L × (1−薄黑底) × (1−霜面α)）。
     *
     * <p><b>通透度</b>（0–100，默认 50）：分段线性插值，**默认值必须落回历史观感** ——
     *
     * <pre>
     *   通透度  霜面 α   薄黑底 α   背景透出
     *     0     0.55     0x60       0.62×0.45 ≈ 28%
     *    50     0.22     0x14       0.92×0.78 ≈ 72%   ← 默认（= 历史值）
     *   100     0.06     0x08       0.97×0.94 ≈ 91%
     * </pre>
     *
     * ⚠️ 通透度越高 ⇒ 面板越跟着背后的页面明暗走（这是物理，不是 bug）：
     * {@code 可压上限 L_max = TARGET_LUM / 透出率}，超过上限的亮页会压不到目标。
     * 默认档的 {@code L_max ≈ 0.58}，而实测最亮页面约 0.59 —— 刚好压不到；
     * 用户把通透度往「实」调、或把明暗往「暗」调，都能把这个缺口补上。
     *
     * @param lumPct          面板明暗 0–100（越界自动夹取）
     * @param transparencyPct 通透度 0–100（越界自动夹取）
     */
    public void setPanelTuningFromPct(int lumPct, int transparencyPct) {
        final int lp = Math.max(0, Math.min(100, lumPct));
        final int tp = Math.max(0, Math.min(100, transparencyPct));
        // 【code 1001 / 1003】分段线性，**在默认 50 处严格等于 0.42**（零回归）：
        //     pct ≤ 50 ⇒ 0.20 → 0.42（每档 0.0044）· pct > 50 ⇒ 0.42 → 0.60（每档 0.0036）
        //   为什么加暗端（1001）：旧版是单段 0.30 + pct×0.003，暗端最低只到 0.30 ——
        //   离默认 0.42 只有 **-29%**，而亮端有 +43%，两边不对称，
        //   用户拿它调"偏暗"时明显觉得够不着（Ari 2026-10-09 的诉求就是这个）。
        //   现在暗端到 0.20（离默认 **-52%**），与亮端量级对称。
        //   ⚠️【code 1003】分段点随默认值由 40 移到 50（默认 50 仍严格 = 0.42，零回归）。
        //   ⚠️ 与「通透度」联动看：透出率越高，面板越跟着背后的页面走 ——
        //   想真正"暗下去"要把通透度往**实**调（见 setPanelTuningFromPct 的说明）。
        panelTargetLum = lp <= 50 ? (0.20f + lp * 0.0044f)
                                  : (0.42f + (lp - 50) * 0.0036f);

        final float t;
        final int blackAlpha;          // 0–255
        if (tp <= 50) {
            t = tp / 50f;                                    // 0 = 最实 … 1 = 默认
            panelVeilAlpha = 0.55f + t * (ADAPTIVE_VEIL_ALPHA - 0.55f);
            blackAlpha = Math.round(0x60 + t * (0x14 - 0x60));
        } else {
            t = (tp - 50) / 50f;                             // 0 = 默认 … 1 = 最透
            panelVeilAlpha = ADAPTIVE_VEIL_ALPHA + t * (0.06f - ADAPTIVE_VEIL_ALPHA);
            blackAlpha = Math.round(0x14 + t * (0x08 - 0x14));
        }
        panelBaseBlack = blackAlpha << 24;                   // RGB 恒为 0（纯黑）
        invalidateSelf();
    }

    /** 【code 1000】当前「背景透出率」（0..1）—— 供诊断日志用。 */
    public float transparency() {
        return (1f - ((panelBaseBlack >>> 24) & 0xFF) / 255f) * (1f - panelVeilAlpha);
    }

    /** 【code 1000】当前自适应可压上限（背景亮度超过它就会压不到目标）。 */
    public float maxCompensableLum() {
        final float tr = transparency();
        return tr <= 0.01f ? 9f : panelTargetLum / tr;
    }

    /**
     * 【code 1002 / 1003】设置面板的**背景亮度**输入 —— 自适应霜面据此反解霜色。
     *
     * <p>【code 1003】起这个值**只有一个来源**：用户在设置页「环境背景亮度」里填的固定值
     * （{@code pct / 100}）。原先的「采宿主窗口均值亮度」那套（含自动/手动开关）已整体删除，
     * 因此本方法不再接收上下缘颜色 —— 光圈已固定为冷白色，不再染色。
     *
     * @param meanLum 背景亮度（0..1）
     */
    public void setBackdropLum(float meanLum) {
        if (this.backdropMeanLum == meanLum) {
            return;
        }
        this.backdropMeanLum = meanLum;
        invalidateSelf();
    }

    /**
     * 【2.2.13】继承另一块玻璃的**运行时状态**（系统模糊是否生效 + 背景亮度）。
     *
     * 修的是「保存模糊强度后面板先变黑，要拖一下才恢复」的另一半根因：
     * 改滑条会 {@code applyPanelBackground()} 重建一整块玻璃 —— 新玻璃
     * {@code systemBlurActive=false}、没有亮度输入，会先走静态深色底。现在重建时把旧玻璃
     * 这两个状态**原样带过来**：新玻璃第一帧就画得和旧玻璃一模一样。
     * 必须在 {@code setBackground()} 之前调用（本方法不主动 invalidate）。
     */
    public void inheritRuntimeStateFrom(LiquidGlassDrawable old) {
        if (old == null) {
            return;
        }
        systemBlurActive = old.systemBlurActive;
        backdropMeanLum = old.backdropMeanLum;
    }

    /** 是否拿到了背景统计（决定自适应霜面与光圈染色是否启用）。 */
    private boolean hasStats() {
        return backdropMeanLum >= 0f;
    }

    /**
     * 【2.2.12】**仅供设置页预览**：铺一张背景位图在面内。
     *
     * ⚠️ 真窗**不用**这个：真窗的背景模糊由合成器直接对本图层做（{@code util/BackdropBlur}），
     *    面板根本不需要自己画背景。设置页是另一回事 —— 它在自己的进程里、面板底下是
     *    预览卡自己画的那层壁纸，合成器的"图层背后"取不到它，所以只能由预览自己
     *    把"按模糊强度软化过的底图"铺进来，才能看到滑条的连续变化。
     */
    public void setPreviewBackdrop(Bitmap bmp) {
        if (this.previewBackdrop == bmp) {
            return;
        }
        this.previewBackdrop = bmp;
        invalidateSelf();
    }

    /**
     * 【2.2.12b】当前模糊强度（0–100）。
     *
     * 视图层要用它把「背景模糊半径」下发给合成器（见 {@code FloatingSubtitleView
     * #applyBackdropBlur}）—— 之所以由 Drawable 回答而不是去读样式：**底是谁建的，
     * 半径就跟着谁**，这样"模糊"与"当前画的是什么底"永远不会错位。
     */
    public int blurPct() {
        return blurPct;
    }

    /** 【2.2.11b】设置模糊强度（0–100）。语义见 {@link #blurPct}。 */
    public void setBlurPct(int pct) {
        int v = Math.max(0, Math.min(100, pct));
        if (v != blurPct) {
            blurPct = v;
            invalidateSelf();
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================
    //
    // 层序（层数刻意压到最少 —— 上一版就是"层叠出来的灰"）：
    //   ① 裁圆角 → ② 底色(近平) → ③ 均匀薄雾 → ③b 真实背景(若有) → ③c 自适应霜面(若有)
    //   → ④ 顶部软受光 → ⑤ 底部薄受光 → ⑥ 颗粒霜化 → ⑦ 1px 渐变边光 → ⑧ 内圈细边光(面板)
    //
    // ②③ 始终先画：它们是"没有背景覆盖的地方"（面板有一部分落在宿主窗口之外）的兜底，
    // 保证任何情况下都不会出现一块透明洞。
    //
    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        bounds.set(b.left, b.top, b.right, b.bottom);
        // 【2.2.13】rect 恒指"玻璃本体"：bounds 内缩 shadowInsetPx（无阴影时与 bounds 相同）。
        //   外圈多出来的环由 drawOuterShadow 画成柔和接触阴影。
        rect.set(bounds.left + shadowInsetPx, bounds.top + shadowInsetPx,
                bounds.right - shadowInsetPx, bounds.bottom - shadowInsetPx);
        final float w = rect.width();
        final float h = rect.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        final float r = Math.max(2f, Math.min(radiusPx, Math.min(w, h) / 2f));

        // 【2.2.13】玻璃填充量随「模糊强度」走（0% ⇒ 全透明；语义见 FILL_EXP 注释）。
        final float fill = (float) Math.pow(
                Math.max(0f, Math.min(1f, blurPct / 100f)), FILL_EXP);

        // 【2.2.13】先画玻璃外围的接触阴影（在最底层，压在透明环上、玻璃之下）。
        if (shadowInsetPx > 0f) {
            drawOuterShadow(canvas, r);
        }

        // 【2.2.11b/2.2.12】模糊强度的"材质柔化"只在**系统背景模糊没生效**时用（见 blurPct）：
        //   强度越高 → 顶部受光带越宽、边光衰减越缓、内圈细边光越淡 ⇒ 观感越"磨砂"。
        final float soft = !systemBlurActive
                ? Math.max(0f, Math.min(1f, blurPct / 100f)) : 0f;
        final float glossRatio = P_GLOSS_H_RATIO * (1f + 1.8f * soft);
        final float rimFadeTop = RIM_FADE_TOP + 0.16f * soft;
        final float rimFadeBottom = Math.max(rimFadeTop + 0.08f, RIM_FADE_BOTTOM - 0.16f * soft);
        final float innerScale = 1f - 0.8f * soft;

        // 每帧从干净状态起画（上一帧的 drawRim 会把 paint 置为 STROKE，这里显式复位）
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setStrokeWidth(0f);

        // ── ① 裁进圆角矩形：面内所有层都不会溢出玻璃面 ──
        clipPath.reset();
        clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
        final int save = canvas.save();
        canvas.clipPath(clipPath);

        // ── ②③ 面内填充：两套**互斥**策略，取决于"背后到底糊没糊" ──
        //
        //   A. 系统背景模糊已生效 + 拿到背景亮度（{@code util/BackdropBlur} + {@link #setBackdropLum}）
        //      → **自适应霜面**：把面板的均值亮度压/抬向 {@link #TARGET_LUM}。
        //        【991/992】口径：**霜量写死**（{@link #ADAPTIVE_VEIL_ALPHA} × 模糊强度）、
        //        霜色是**中性灰**、由霜色的亮度去补足差额。于是切页时"糊度"不动；
        //        亮度在可压到的范围内钉住，很亮的页面则自然跟着亮（见该类头 992 段）。
        //        背景的**颜色**主要由透出来的那 {@code 1-α}（本版 ≈78%）决定。
        //        ★ 此时**不画**静态底色 —— 背后是真糊过的画面，再叠一层实底就浪费了它。
        //   B. 其余情况（取不到宿主画面 / 系统模糊没生效）→ 静态两档：自己的底色必须够，
        //      否则就是"一块什么也没有的透明片"。
        // 背景像素：真窗由合成器糊（这里什么都不画）；预览则由 previewBackdrop 提供。
        if (previewBackdrop != null && !previewBackdrop.isRecycled()) {
            pvDst.set(rect);
            paint.setShader(null);
            paint.setAlpha(255);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(previewBackdrop, null, pvDst, paint);
            paint.setFilterBitmap(false);
        }

        if ((systemBlurActive || previewBackdrop != null) && hasStats()) {
            // 【2.2.14】恒定薄黑底（×fill）：先铺在霜面之下，作为白字的对比度地板。
            final int baseBlack = a(scaleAlpha(panelBaseBlack, fill));
            if ((baseBlack >>> 24) != 0) {
                paint.setShader(null);
                paint.setColor(baseBlack);
                canvas.drawRect(rect, paint);
            }
            // ──【991 / 992】霜面：**密度写死，由霜色的亮度去补到目标落点** ──
            //
            // 演进（每一版的反例都写在类头 ADAPTIVE_VEIL_ALPHA 段）：
            //   · 旧版：霜量 = 斜率 × 与目标的差 × fill ⇒ 密度随背景大幅摆动
            //     （7.9%~42.4%）⇒ "一会发白、一会发暗"；
            //   · 989/990：霜色写死白/黑，反解**霜量**把亮度钉住 ⇒ 密度摆得更凶（到 58%）；
            //   · 991：霜量写死、反解**霜色** ⇒ 密度与亮度都稳，但密度取到 0.45 时
            //     只能用灰去补亮度，面板被洗成一片灰水洗色（"颜色变奇怪"）；
            //   · 992（本版）：密度降到 0.22（背景色透出约 78%，回到用户认可的观感），
            //     霜色回到**中性灰**（不再自己带背景色相），仍由它补亮度。
            // 现在就一件事：解 X·(1-α) + cv·α = TARGET_LUM ⇒ cv = (TARGET − X·(1-α)) / α。
            final float l = Math.max(0f, Math.min(1f, backdropMeanLum));
            final float ab = ((baseBlack >>> 24) & 0xFF) / 255f;
            final float cb = lum(panelBaseBlack);
            final float xAfterBase = l * (1f - ab) + cb * ab;
            final float material = Math.min(1f, fill / MATERIAL_FADE_FILL);
            final float aFinal = panelVeilAlpha * material;
            if (aFinal > 0.004f) {
                // 解 X·(1-α) + cv·α = TARGET_LUM ⇒ cv = (TARGET − X·(1-α)) / α
                float cv = (panelTargetLum - xAfterBase * (1f - aFinal)) / aFinal;
                if (!(cv > 0f)) {
                    cv = 0f;     // 背景比目标还亮、压不到 ⇒ 霜色到底（面板会略亮于目标）
                } else if (cv > 1f) {
                    cv = 1f;
                }
                final int veilAdaptive =
                        (Math.round(aFinal * 255f) << 24) | veilRgb(cv);
                paint.setShader(null);
                paint.setColor(a(veilAdaptive));
                canvas.drawRect(rect, paint);
            }
        } else {
            // ── 静态档：底色上深下浅，两档只差 3% ⇒ 面内近"平"（与参考弹窗一致）──
            // 【2.2.13】底色 alpha 同样 × fill（0% ⇒ 透明；兜底档不再永远压一层深底）。
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    a(scaleAlpha(baseTop(), fill)), a(scaleAlpha(baseBottom(), fill)),
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);

            // ── 均匀薄雾：暗背景上的"亮度地板"，让玻璃不至于落进纯黑 ──
            final int veil = a(scaleAlpha(veil(), fill));
            if ((veil >>> 24) != 0) {
                paint.setShader(null);
                paint.setColor(veil);
                canvas.drawRect(rect, paint);
            }
        }

        // ── ④ 顶部软受光：只占上缘 5% 高度（v1 铺满整块 = 灰的主因）──
        final int gloss = gloss();
        if ((gloss >>> 24) != 0) {
            final float glossBottom = rect.top + h * glossRatio;
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, glossBottom,
                    a(gloss), a(gloss & 0x00FFFFFF), Shader.TileMode.CLAMP));
            canvas.drawRect(rect.left, rect.top, rect.right, glossBottom, paint);
        }

        // ── ⑤ 底部薄受光（仅面板）：参考弹窗下缘略亮于中央，这里同向且同样窄 ──
        if (kind == KIND_PANEL) {
            final float lightTop = rect.bottom - h * P_BOTTOM_LIGHT_H_RATIO;
            paint.setShader(new LinearGradient(rect.left, lightTop, rect.left, rect.bottom,
                    a(P_BOTTOM_LIGHT & 0x00FFFFFF), a(P_BOTTOM_LIGHT), Shader.TileMode.CLAMP));
            canvas.drawRect(rect.left, lightTop, rect.right, rect.bottom, paint);
        }

        // ── ⑥ 颗粒霜化（仅面板）：磨砂玻璃的微颗粒感 ──
        //   【2.2.13】颗粒也是"霜"的一部分 ⇒ alpha × fill（0% 时彻底消失，不再有白蒙蒙）。
        if (kind == KIND_PANEL && globalAlpha > 200) {
            drawGrain(canvas, fill);
        }

        paint.setShader(null);
        canvas.restoreToCount(save);

        // ── ⑦ 折射边光：一条描边，上缘最亮 → 下缘最暗（竖直渐变）──
        drawRim(canvas, r, Math.max(1f, RIM_WIDTH_DP * density), rimTop(), rimBottom(),
                rimFadeTop, rimFadeBottom);

        // ── ⑧ 内圈细边光（仅面板）：很淡的一条，给玻璃边"厚度层次" ──
        if (kind == KIND_PANEL && innerScale > 0.05f) {
            drawRim(canvas, r, Math.max(1f, 0.9f * density),
                    scaleAlpha(P_RIM_INNER_TOP, innerScale),
                    scaleAlpha(P_RIM_INNER_BOTTOM, innerScale),
                    rimFadeTop, rimFadeBottom);
        }
    }

    /**
     * 【2.2.13】玻璃外围的柔和接触阴影 —— "整块浮在页面上"的体积感来源。
     *
     * 画法：沿玻璃圆角矩形向外铺 {@link #SHADOW_RINGS} 圈黑色描边，贴玻璃那一圈最实，
     * 向外按幂次渐隐。
     *
     * ⚠️【2.2.14 关键修正：圈宽必须 ≈ 圈距，不能重叠】旧版把圈宽取成 1.9×圈距，
     *   本意是"让相邻圈融合成连续渐变"，但描边是 SRC_OVER **累乘**叠加的：
     *   任何一点都被约两圈盖住 ⇒ 实际 alpha ≈ 1-(1-a)²，贴边整片冲到 40% 以上，
     *   于是"四周一圈"变成一道明显的黑框（Ari 反馈的就是它）。
     *   现在圈宽 1.02×圈距 ⇒ 每点只被一圈覆盖，画出来的就是标注多少就多少的平滑剖面。
     *
     * 刻意**不用** BlurMaskFilter：它在部分机型的硬件管线上不受支持（画不出来即静默消失），
     * 同心圈是 API 1 就有的绘制，任何机器上都成立。
     * 只在 {@code shadowInsetPx > 0}（面板档）时调用；画在裁切**之前**，覆盖玻璃外的透明环。
     * 最外圈的 alpha 已趋近 0，因此窗口边界上不会出现硬边。
     */
    private void drawOuterShadow(Canvas canvas, float r) {
        final float pad = shadowInsetPx;
        final int rings = SHADOW_RINGS;
        final float step = pad / rings;
        final float sw = step * 1.02f; // ≈ 圈距：相邻圈只差一条抗锯齿轮廓，不产生叠加
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        for (int i = rings; i >= 1; i--) {
            final float d = (i - 0.5f) * step;   // 本圈中心线到玻璃边缘的距离
            final float t = d / pad;             // 0 = 贴玻璃 … 1 = 最外
            final int alpha = Math.round(SHADOW_MAX_ALPHA * (float) Math.pow(1f - t, 1.6));
            if (alpha <= 1) {
                continue;
            }
            paint.setAlpha(alpha);
            paint.setStrokeWidth(sw);
            tmp.set(rect.left - d, rect.top - d, rect.right + d, rect.bottom + d);
            final float rr = Math.max(1f, r + d);
            canvas.drawRoundRect(tmp, rr, rr, paint);
        }
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 铺一层平铺的颗粒贴图（已在圆角裁切内，不会溢出玻璃面）。【2.2.13】alpha 随 fill 缩放。 */
    private void drawGrain(Canvas canvas, float fill) {
        if (fill <= 0.01f) {
            return;
        }
        Shader g = grainShader;
        if (g == null) {
            Bitmap bmp = grainBitmap();
            if (bmp == null) {
                return;
            }
            g = new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            grainShader = g;
        }
        paint.setShader(g);
        paint.setAlpha(Math.round(255f * fill));
        canvas.drawRect(rect, paint);
        paint.setAlpha(255);
    }

    /**
     * 画一道圆角描边（沿矩形内缩半个线宽，避免被窗口边缘裁掉一半）。
     *
     * {@code fadeTop/fadeBottom} 是「边光归零」的两个分界（相对面板高）：四档竖直渐变
     * 「上缘最亮 → fadeTop 处归零 → fadeBottom 处仍为零 → 下缘再亮回来」。
     * 中间两档归零是关键：这样左右边缘的中点**没有边光**（与参考弹窗实测一致），
     * 边光只出现在上下两条边上。模糊强度会把这四档拉宽（越宽越"磨砂"）。
     */
    private void drawRim(Canvas canvas, float r, float strokeW, int colorTop, int colorBottom,
                         float fadeTop, float fadeBottom) {
        if ((colorTop >>> 24) == 0 && (colorBottom >>> 24) == 0) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeW);
        final float inset = strokeW / 2f;
        tmp.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
        final float rr = Math.max(1f, r - inset);
        rimPath.reset();
        rimPath.addRoundRect(tmp, rr, rr, Path.Direction.CW);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                new int[]{a(colorTop), a(colorTop & 0x00FFFFFF),
                        a(colorBottom & 0x00FFFFFF), a(colorBottom)},
                new float[]{0f, fadeTop, fadeBottom, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(rimPath, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * 【989】一个颜色的感知亮度（Rec.709 加权，0..1）。
     *
     * 自适应霜面用它把「薄黑底之后的背景亮度」与「霜色」放进同一条方程里反解霜量
     * （见 {@link #draw}）。Rec.709 加权（0.2126/0.7152/0.0722）。
     */
    private static float lum(int color) {
        return (0.2126f * ((color >> 16) & 0xFF)
                + 0.7152f * ((color >> 8) & 0xFF)
                + 0.0722f * (color & 0xFF)) / 255f;
    }

    /**
     * 【991 / 992】按目标亮度 {@code cv} 生成霜色（RGB，不含 alpha）—— **中性灰**。
     *
     * ── 992 为什么砍掉了 991 的"带背景色相" ──────────────────────────────
     * 991 让霜色取自背景上下缘平均色，本意是"让玻璃像这一页被压暗后的样子"。
     * 但真机上它帮了倒忙：亮页面的霜色被缩放到很暗时，一个"被压暗的浅色"看起来
     * 就是一层发闷的灰（越暗越灰），再加上 45% 的高密度，面板整体变成一片灰/灰紫
     * 水洗色 —— Ari 的原话是「玻璃的颜色反而变奇怪了，不如之前的版本」。
     * 992 起霜面回到**中性灰**：面板的颜色只由透出来的背景（约 78%）决定，
     * 霜只负责把亮度补到目标值，不再自己带色。
     */
    private static int veilRgb(float cv) {
        final int v = clamp255(Math.round(cv * 255f));
        return (v << 16) | (v << 8) | v;
    }

    /** 按倍数缩放颜色的 alpha（上限 255），RGB 不变。 */
    private static int scaleAlpha(int color, float scale) {
        int a = Math.round(((color >>> 24) & 0xFF) * scale);
        if (a > 255) {
            a = 255;
        }
        if (a < 0) {
            a = 0;
        }
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ==================================================================
    // 取色
    // ==================================================================

    private int baseTop() {
        switch (kind) {
            case KIND_CAPSULE_ON:
                return CON_BASE_TOP;
            case KIND_CAPSULE_OFF:
                return COFF_BASE_TOP;
            case KIND_PANEL:
            default:
                return P_BASE_TOP;
        }
    }

    private int baseBottom() {
        switch (kind) {
            case KIND_CAPSULE_ON:
                return CON_BASE_BOTTOM;
            case KIND_CAPSULE_OFF:
                return COFF_BASE_BOTTOM;
            case KIND_PANEL:
            default:
                return P_BASE_BOTTOM;
        }
    }

    /** 均匀薄雾（只有面板有）。 */
    private int veil() {
        return kind == KIND_PANEL ? P_VEIL : 0;
    }

    private int gloss() {
        switch (kind) {
            case KIND_CAPSULE_ON:
                return CON_GLOSS;
            case KIND_CAPSULE_OFF:
                return COFF_GLOSS;
            case KIND_PANEL:
            default:
                return P_GLOSS;
        }
    }

    private int rimTop() {
        switch (kind) {
            case KIND_CAPSULE_ON:
                return CON_RIM_TOP;
            case KIND_CAPSULE_OFF:
                return COFF_RIM_TOP;
            case KIND_PANEL:
            default:
                return P_RIM_TOP;
        }
    }

    private int rimBottom() {
        switch (kind) {
            case KIND_CAPSULE_ON:
                return CON_RIM_BOTTOM;
            case KIND_CAPSULE_OFF:
                return COFF_RIM_BOTTOM;
            case KIND_PANEL:
            default:
                return P_RIM_BOTTOM;
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 按 {@link #globalAlpha} 缩放一个颜色的 alpha（RGB 不变）。 */
    private int a(int color) {
        if (globalAlpha >= 255) {
            return color;
        }
        int alpha = ((color >>> 24) * globalAlpha) / 255;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // ==================================================================
    // Drawable 契约
    // ==================================================================

    @Override
    public void setAlpha(int alpha) {
        int v = Math.max(0, Math.min(255, alpha));
        if (v != globalAlpha) {
            globalAlpha = v;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
