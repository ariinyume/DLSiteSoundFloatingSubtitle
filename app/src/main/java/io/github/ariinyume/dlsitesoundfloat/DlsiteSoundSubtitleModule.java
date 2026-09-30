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

import android.content.Context;

import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;
import io.github.ariinyume.dlsitesoundfloat.hook.ActivityButtonHook;
import io.github.ariinyume.dlsitesoundfloat.window.FloatingWindowManager;
import io.github.ariinyume.dlsitesoundfloat.hook.NetworkHook;
import io.github.ariinyume.dlsitesoundfloat.hook.PlayerPositionHook;
import io.github.ariinyume.dlsitesoundfloat.hook.PlayerSourceHook;
import io.github.ariinyume.dlsitesoundfloat.hook.SubtitleViewHook;
import io.github.ariinyume.dlsitesoundfloat.hook.StatusBarSubtitleHook;
import io.github.ariinyume.dlsitesoundfloat.hook.StructureWatcher;
import io.github.ariinyume.dlsitesoundfloat.util.StatusBarSubtitleBridge;
import io.github.ariinyume.dlsitesoundfloat.util.NetLogFile;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（libxposed 现代 API，API 102）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 2.0.0 迁移要点（改本文件前必读）
 *
 * ① 入口形态变了：
 *      旧：{@code implements IXposedHookLoadPackage} + {@code handleLoadPackage(LoadPackageParam)}
 *          —— 一个回调，包名从 {@code lpparam.packageName} 拿，classloader 从 {@code lpparam.classLoader} 拿。
 *      新：{@code extends XposedModule}，回调拆成「加载期」与「就绪期」两个：
 *          · {@link #onPackageLoaded}  —— 应用**尚未**创建 Application，能拿 ClassLoader；
 *            但**不能**在这里做任何依赖 App 上下文的初始化（会拿到半成品环境）。
 *          · {@link #onPackageReady}  —— Application 已创建、上下文可用，适合做真正的工作。
 *          两个回调都会带 {@code getPackageName()} 与 {@code getDefaultClassLoader()/getClassLoader()}。
 *
 * ② 作用域语义变了（**这条最容易踩**）：
 *      旧 API 的 scope 只决定「往哪些包注入」，回调只在被注入的包上触发。
 *      新 API 的 scope 是**进程级**的：scope 内的进程里**所有**被加载的包都会触发回调
 *      （RN 宿主会动态加载一堆包，ColorOS 的 SystemUI 更是）。所以必须在回调里
 *      显式按包名过滤 —— 见 {@link #isTarget}。
 *
 * ③ 日志 API 变了：{@code XposedBridge.log(String)}（静态）→ {@code XposedInterface.log(int, String, String)}
 *      （实例）。本模块 152 处日志调用分散在各层，统一走 {@link XposedCompat#log(String)}，
 *      输出形态与旧版逐字节一致（tag=DLsiteSoundFloat，模块内部前缀留在消息里）。
 *
 * ④ 不再有 {@code XposedHelpers} / {@code XC_MethodHook}：反射助手与回调基类由
 *      {@link XposedCompat} 自带（见该类的类头说明）。
 *
 * ⑤ 职责划分（本轮刻意如此）：{@link #onPackageLoaded} 只做「本类内部 Application#attach 的钩子」，
 *      业务初始化（SubtitleRepository.init / 六个 Hook）全部放在 {@link #onPackageReady}。
 *      理由：onPackageLoaded 早于 Application 创建，此时注册的钩子如果立刻触发，
 *      拿到的 Context 还不完整；而 onPackageReady 之后一切就绪，顺序天然正确。
 *      （旧代码把两者塞在同一个回调里，是传统 API 只给一次机会所致，不是有意设计。）
 * ─────────────────────────────────────────────────────────────────────
 */
public class DlsiteSoundSubtitleModule extends XposedModule {
    private static final String TARGET_PKG = "jp.co.eisys.dlsitesound";
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    /** 业务初始化是否已做过 —— 同一进程内两个回调可能都被调用，必须去重。 */
    private static volatile boolean sInitialized = false;
    /** Application#attach 钩子是否已挂 —— 同上，防止重复挂钩。 */
    private static volatile boolean sAppAttachHooked = false;

    // ======================================================================
    // 生命周期回调
    // ======================================================================

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // 框架把模块载入进程后最先调用。这里注入兼容层，让日志在任何后续回调
        // （含异常路径）里都能用。
        //
        // ⚠️ 不能在这一阶段挂钩子：onModuleLoaded 时**还没有任何 ClassLoader**，
        //    连宿主类都加载不了。真正的挂钩在 onPackageLoaded（有 classloader）
        //    与 onPackageReady（Application 已就绪）里。
        XposedCompat.attach(this);
        XposedCompat.log("[DLsiteSoundFloat] onModuleLoaded process=" + param.getProcessName()
                + " isSystemServer=" + param.isSystemServer()
                + " (hooks will be installed on package load)");
    }

    /**
     * 应用加载期（此时 Application 还没创建）。
     *
     * 只做一件事：挂 {@code Application#attach(Context)} 的钩子 —— 需要在 Application
     * 拿到 Context 的**第一时刻**同步状态栏字幕开关、初始化网络诊断日志（见旧代码注释）。
     * 业务初始化放 {@link #onPackageReady}。
     */
    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!isTarget(pkg)) {
            return; // 作用域内的其它包（RN 动态包等）一律不处理
        }
        try {
            hookApplicationAttach(param.getDefaultClassLoader());
        } catch (Throwable t) {
            XposedCompat.log("[DLsiteSoundFloat] hookApplicationAttach failed: "
                    + t.getMessage());
        }
    }

    /**
     * 应用就绪期（Application 已创建、ClassLoader 可用）—— 业务初始化全部在这里。
     */
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isTarget(pkg)) {
            return;
        }
        if (SYSTEMUI_PKG.equals(pkg)) {
            StatusBarSubtitleHook.hook(param.getClassLoader());
            return;
        }
        initTargetApp(pkg, param.getClassLoader());
    }

    // ======================================================================
    // 分派
    // ======================================================================

    /** 这个包是不是本模块要处理的包。作用域是进程级的，必须自己筛。 */
    private boolean isTarget(String pkg) {
        return TARGET_PKG.equals(pkg) || SYSTEMUI_PKG.equals(pkg);
    }

    private void initTargetApp(String pkg, ClassLoader cl) {
        if (sInitialized) {
            return;
        }
        sInitialized = true;

        XposedCompat.log("[DLsiteSoundFloat] Module loaded for " + pkg);
        // 版本标识：每次排查「功能怎么没生效」时，先看这行确认装的是不是最新 APK。
        // ⚠️ 保留版本号、只改括号描述会产生「同日同名包」，装机前务必核这一行。
        XposedCompat.log("[DLsiteSoundFloat] ==== BUILD 2.1.3 / code 958（【2.1.3 修复第 4 轮；Ari 指令：她认可修复前不升版本号，故 versionName 停在 2.1.3、versionCode 保持 958，可直接覆盖安装】根因由上一版的逐帧探针在真机上定量钉死（10395 行探针）：居中落点 = 面板内边距 + 前置行高和 + 当前行高的一半，而**前置行高和只随宽度变化**（窗口变宽变窄 ⇒ 文字重新折行 ⇒ 当前行的内容坐标整段平移，实测阶跃恰是 52px 的整数倍），高度变化完全不改它 —— 所以症状只在改宽度时出现。真机日志里 4940 对相邻帧中 **100%** 都是「本帧的滚动量等于上一帧该有的落点」：一帧不差，说明缩放时的重新居中是用 post 排到下一帧执行的。于是一旦折行，那一帧画出来的还是旧落点，字幕偏 1~2 帧后自己弹回，观感就是上下抖动（实测 201 / 5188 帧错位，中位 52px，最大 1044px）。本版把重新居中从「下一帧的 post」搬到「同一趟布局之后、绘制之前的那一次 preDraw 回调」里执行，使「视口与内容重排 → 计算落点 → 绘制」落在同一帧；post 只留作兜底（已被 preDraw 消费则空转）。另：探针扩成三条（布局 / 修正前 / 修正后）并新增视口宽与容器宽两列，用来直接证明「是宽度在变」并且「修正后本帧即为零偏移」。上一版的「同帧预置内边距」被真机数据证伪（绘制帧里内边距从未滞后），但无害、保留不动。判据：本版 view built 行含带本版标识的缩放探针字段，且新增一个只在本版存在的「同帧重新居中」方法名（上一版没有）。）==== BUILD 2.1.5 / code 958（【2.1.5】只加「缩放探针」+ 一处「同帧预置内边距」，不改可见行为。Ari 真机确认 2.1.4 上拖动缩放手柄时字幕仍会往上闪跳一下。归因：窗口顶边固定、只向下生长，而字幕恒在窗内居中 ⇒ 字幕屏幕位置恒等于「窗口顶边 + 视口高的一半」，缩放时必然跟随窗心移动；滚动落点在代数上与视口高无关（落点 = 面板内边距 + 前置行高和 + 行高一半），所以缩放时唯一真正在变的是容器的居中内边距。该内边距原先在容器尺寸回调里同步修改 —— 那已经是在布局阶段、子内容量完之后，只能再排一趟布局才生效 ⇒ 会出现「视口已是新值、内边距还是旧值」的中间帧，那一帧字幕没跟上、下一帧整块跳半个高度差，观感就是往上闪跳。本版①把内边距的预置点提到内容测量之前，使「视口→内边距→内容测量」落在同一趟布局里（若预置用的可用高与真实布局高不一致，会自我停用并退回旧行为，并打一条 DISABLED 日志）；②在即将绘制的那一刻记录真实几何（视口高、内边距现值、内容实际被测量时的内边距、滚动量、当前行中心相对视口顶的偏移与期望值），用来一次性钉死错位发生在哪一帧、偏了多少像素。判据：本版 view built 行含 resizeProbe=v215（2.1.4 没有）。）==== BUILD 2.1.4 / code 957（【2.1.4】回退 2.1.3 的「钉住 + 松手回中」（缩放锚点）。Ari 2026-09-30 真机取证（LSPosed 日志 + 11 秒录屏逐帧）证明：锚点会被 scrollNow 的 maxScroll 钳位打到窗口顶部（O = vp/2 − p0 − h/2 − B，vp=1350 时 O ≈ 100px，与实测 95~115px 逐帧吻合），松手后 220ms 归位动画又把字幕整段下滑约 600px 回正中，观感「先顶到窗口上沿、再整段掉回正中」，比 2.1.1 的平滑跟随更糟。Ari 决定「只回退，绝不动窗口」⇒ 把 2.1.3 加的那套缩放锚点机制（行锚点字段、采锚点、拖拽开始与结束 API、锚点下界夹取、实测偏移、归位动画字段与取消方法、220ms 归位常量）全部删除；滚动落点恢复成「视口正中」；字幕恒在窗口正中、跟随窗口中心平滑移动；窗口位置与顶边完全不动（缩放时不再补偿窗口 y）。①字幕假阴性（软挂起）与 ②按钮 settle 迟滞（12px 迟滞）保持 2.1.3 不动。本版 view built 行不含任何缩放探针字段（2.1.3 有），这是区分两版最快的判据。）==== BUILD 2.1.3 / code 955 （【2.1.3】三处修复（从 2.2.0 设置页 WIP 中拆出）—— ①拖右下角缩放手柄时字幕上下抖动：窗口 gravity=TOP 且字幕恒居中 ⇒ 字幕被焊死在窗口正中，高度每变 ΔH 就跟着走 ΔH/2（实测摆动 ±298px、0.73s 一周期）；本版加「拖拽期间把当前行钉在屏幕原处、松手后用一段短动画滑回窗口正中」的机制（已在 2.1.4 整体回退，见上）。②字幕假阴性：换轨挂起瞬间 UI 就跳「无字幕」并在 5s 提前收窗；新增 pendingSoftSuspend 软挂起 —— 手上有 cues 就维持显示、不降级不关窗，宽限期 10000→15000ms。③按钮 settle 抖动：几何快照门由严格相等改为 12px 迟滞（GEO_SNAPSHOT_DEADZONE_PX）。诊断：view built 行含缩放探针字段（2.1.4 起不再输出）。）==== BUILD 2.1.2 / code 954 （work_diag_82 第五件事【同版本号第三次打包】⑤【code 955】锁死胶囊大小与文字大小：Ari 2026-09-26 报「按钮大小随显示大小变化」。真机日志 15:34~15:36 铁证：宿主 App 的 density 读数在 OPPO 屏幕缩放档位间长期来回跳（2.9750001 -> 3.875 -> 2.9750001 -> 3.5 -> 2.9750001），而 code 939 的「持续 20s 不一致就重锁」会把每个档位都当新常态锁一遍，于是 `[几何4] capsuleH` 在 95/112/124px 之间反复变（宽、圆角、文字、距滑条偏移同吃一套 dip2px）。本版新增「胶囊度量锁定密度」：首次用到时取宿主稳定密度锁死一次（日志记 appStable 与 system 两个读数），此后 dip2px 一律走它 —— 无论 stableDensity 怎么重锁都不再跟随；胶囊文字同时改为**固定 px**（= 13sp 设计值 × 锁定密度，不吃 scaledDensity）并按「胶囊宽 − 左右各 4dp」用粗体量过当前语言全部文案，放不下就按 0.25dp 步长缩到放得下（下限 10sp 等效），配合 singleLine + 不省略号 + 去字体自带留白 ⇒ 「文字完全显示」是算出来的保证。诊断新增 `[code 955] capsule metrics density locked: …` 与 `[code 955] capsule text locked: …px (design=… avail=… lang=…)` 两行）==== BUILD 2.1.2 / code 954 （work_diag_81 四件事：④【同版本号第二次打包，Ari 2026-09-26 指令】单个胶囊宽由 **75dp 改为 85dp**（versionName/versionCode 均不变，装机前请用本行里的「85dp」确认拿到的是这一版；按钮组总宽随之 160dp→180dp，容器 wrap_content，子视图 CAPSULE_W_DP 是唯一真源）；①【多语言】Ari 2026-09-26 需求 —— 新增 util/I18n 统一对外文案：系统语言为简体中文/中文（简体）时文字不变；为繁體中文/中文（繁體）/繁體中文（中國香港）/繁體中文（中國台灣）/繁體中文（香港）/繁體中文（台灣）时按钮换成 狀態欄 開/關、懸浮窗 開/關、無字幕（悬浮窗占位同为 無字幕）；非中文时换成 Status ON/OFF、Popup ON/OFF、No Sub（悬浮窗占位 No Subtitles）。语言判定走 Resources.getSystem() 的系统配置（不看宿主 App 的 per-app 语言），繁体判定先看 BCP-47 的 script（Hant/Hans）再看地区（TW/HK/MO），进程内只判一次。文案写死在代码里而不用 res/values：注入视图拿的是**宿主** Context，取不到本模块资源。②【无字幕占位 5s】NO_SUBTITLE_EARLY_CLOSE_MS 由 4s 改为 5s（切到无字幕音轨后悬浮窗挂着「无字幕」占位的时长；数据侧裁决窗与「字幕晚到自动开回」逻辑未动）。③并入 code 952/953 两个按钮显隐修复：952 —— 新建按钮不再用「上次结论」预判可见性（一律先 GONE，配 sForceNextDetect 保证播放页上同一帧亮起），治「按钮偶尔在非播放页出现」；953 —— 「整棵树扫成空」的一趟扫描不再当作判隐藏的证据，改为 60ms 后强制补检，治「播放页上按钮每 ~700ms 闪一次」）==== BUILD 2.1.1 / code 951 （work_diag_80 Ari 2026-09-25 反馈「偶尔按键会到页面上不合理的位置（挡住音声的封面）」的根修：**跟手偏置被播放页入场转场位移污染**。日志铁证 09:33:41.155 `page follow bias calibrated: 431px (ref=play-button)`，而同一刻 `[几何4] sliderCy=2233`（稳态 1799，差 434px）—— 打开播放页那一次标定踩进了入场转场中途的 80ms 停顿窗，把「还没走完的 431px」当成宿主静态偏置记了下来；此后 `vis = ΣtranslationY − 431` 在页面真正静止时恒为 −431，被原样写进按钮 translationY ⇒ 两个胶囊整体抬高 431px，从「滑条上方」跑到标题/封面下缘（转场相位更早时抬得更高，正好压在封面上）。旧门「连续两次扫描读到同一个 y」拦不住 —— 转场把扫描踢到 80ms 一次；偏置又只标定一次、`captureFollowBaseline()` 被 `sFollowAppliedY != 0` 挡在门外 ⇒ 标错就整个进程都错，这才是「偶尔」的成因。修法：门①.5「页面最近没动过」接到**位移采样时间轴**（sLastPageMotionMs ≥ 200ms）；门②.5 候选位置连续稳住 400ms（BASE_SETTLE_MS，墙钟口径）；门⑤ 偏置量级闸 64dp（首次标定的 |ΣtranslationY| 超了就不采信为宿主偏置，继续走基线通道，照样跟手）；新增 `healStaleBiasIfAtRest()` 自愈（页面确在布局静止位却还挂着非零位移 → 重标偏置、位移归零，不会误伤「手指按住页面停在半路」）；诊断新增 `baseline deferred:` / `bias NOT adopted:` / `stale bias healed:` 三行。⚠️ 装本包后请**重启 SystemUI 或重启手机**（跨进程握手会带 versionCode，951 != 950 会打一行不一致警告））==== BUILD 2.1.0 / code 950 （work_diag_79 Ari 指令：applicationId 由 com.sena.dlsitesoundfloat 改为 io.github.ariinyume.dlsitesoundfloat，以满足 LSPosed 官方模块仓库对反向域名归你所有的要求，sena.com 不归本项目所有故必须换。Java 包目录树、入口类全名、AndroidManifest 的 package、namespace 与 applicationId 同步迁移；五条跨进程广播 action 串随包名同步改写，App 与 SystemUI 两端一致。本版相对上一版的功能代码零差异，只换包名与版本号。装本包等于装了一个全新 App，旧版可卸载，LSPosed 作用域需重新勾选，装完必须重启 SystemUI 或重启手机）==== BUILD 2.0.3 / code 949 （work_diag_79 Ari 指令：把 948/949 两个「按钮比播放页晚消失」的修复正式发版。948 给锚点面积归零单开 120 毫秒确认窗；949 再压到首次检测即收起并新增页面静止前置门。本版与上一版相比只改 versionName 与构建横幅，代码与此前 949 交付包完全一致）==== BUILD 2.0.2 / code 949 （work_diag_78 Ari 复测：比上一版快了但仍有延时。30fps 视频逐帧像素：页面消失到胶囊消失为 133 100 200 200 67 毫秒，与日志 120 毫秒档完全吻合，残余全部来自 120 毫秒探针周期。本版把归零档改为首次检测即收起，并新增页面静止前置门防惯性滚动误收）==== BUILD 2.0.2 / code 948 （work_diag_76 Ari 报「点击简介回到作品页面时，播放页直接消失而按钮延时了一会才消失」：视频 10fps 逐帧像素取证，播放页特征区在 t=1.05 秒归零而胶囊按钮区到 t=1.80 秒才归零，滞后约 0.75 秒，日志侧对应 anchor dead 那条 need=600ms 的防假死确认窗。本版新增三档确认窗，锚点面积归零时收到 120 毫秒，与播放页同步收起，默认 600 毫秒档保留给拖动场景，诊断日志新增 gone 字段）。==== BUILD 2.0.2 / code 947 （work_diag_74 Ari 报「状态栏字幕打开时徽标里看不到通知数量」：946 的 EVEN_ODD 洞在真机上没出现，实测圆盘 33×33 完全实心、内切 23×23 零个暗像素；根因是每帧调用的 Path.rewind() 会把 FillType 清回 WINDING（Android 的 reset() 专门存取 FillType，rewind() 没有这层保护），于是圆与字形同向取并集成实心圆。本版改用 reset() 并每帧显式再 setFillType 一次兜底，自证日志追加 fill= 与 glyph= 两项直接可验）。==== BUILD 2.0.1 / code 946 （work_diag_72 Ari 报「通知图标直接不显示了」：945 用 saveLayer+BlendMode.CLEAR 画的镂空徽标在这台机器上整块画不出来，实测截图像素证明圆底与数字一起消失；本版改成一条 Path+EVEN_ODD 挖洞（不用图层不用混合模式），并给 onMeasure 加尺寸兜底、给徽标加几何自证日志）。==== BUILD 2.0.1 / code 945 （work_diag_71 Ari 指令：点悬浮窗面板出现的 ✕ 关闭按钮，再点一次按钮外的其他悬浮窗区域立刻收回，并与 5 秒无操作自动隐藏并存；状态栏通知数徽标里的数字改为镂空数字，用 CLEAR 从圆底挖出，数字区域直接透出状态栏自己的底色，不再用近似色填充）。==== BUILD 2.0.1 / code 944 （work_diag_70 Ari 指令：切轨提前收窗阈值由 6 秒收到 4 秒；点悬浮窗面板出现的 ✕ 关闭按钮改为 5 秒内没人点就自动隐藏；未授权 com.android.systemui 作用域时不显示状态栏字幕开关按钮，该闸门本轮才真正打进交付包，并加 0.4 秒与 1.2 秒两拍快速补探）。==== BUILD 2.0.1 / code 943 （work_diag_69：全部 19 个 java 源文件补 GPL-3.0 文件头；提前收窗阈值 2 秒放宽到 6 秒）。==== BUILD 2.0.1 / code 942 （work_diag_68 Ari 指令：切到无字幕音轨时悬浮窗 2 秒内无字幕 JSON 即提前自动关闭，不再挂着无字幕占位等满 10 秒裁决窗；字幕晚到会自动开回）。【bug1 流体云不存在时字幕只显示半截 根修】旧判据只看子视图自身 getVisibility 等于 VISIBLE，容器被摘掉或 GONE 后其子视图仍报 VISIBLE，死容器照样给出 left 等于 454，宽度被压到 341 至 380 即半截；改为容器与子视图一律用 isShown 加上宽高大于零，宽限由 60s 收到 2s，并新增 seeding state 状态翻转日志自证。【bug2 按钮太低 根修】旧口径取简介底边与滑条视图顶边中点，中心 1747 底边 1794，比滑条顶 1772 还低 23px 故压进度条；改为按钮底边强制落在滑条顶边往上 25dp 即 1698，按钮恒定 32dp 不压缩，日志新增 descStable 与 sliderTop 两个诊断量。【bug2 打开播放界面按钮上下抖动 根修】实测转场瞬间 ctx 的密度读数会从常态 2.9688 跳到 3.5 或 3.875（即 476 与 560 与 620dpi，正是 OPPO 屏幕缩放档位表），而按钮底边与右距都直接吃它故按钮上下瞬移 19 至 32px 且左右同偏，斜着抖；改为密度一次性锁定，仅在像素屏幕尺寸变化或持续 20 秒以上不一致时才重新锁定，并打 density spike ignored 诊断行。【bug1 状态栏字幕显示区域被缩到超级短 根修】行左界实测值的采信门旧口径拿自己当锚，sLineLeftAcc 初值为负一故首采样无条件过门，而 showLine 在隐藏时钟通知图标之后一毫秒就采样，那次重排还没跑，读到含通知图标占位的旧值 251 并永久锁存，之后真值 110 因超出容差被永久拒收，字幕宽少 138px 即被压成超级短；改为首采样也以常量 38dp 即 113px 为锚加正负 20dp 容差，单帧失真值直接拒收，并新增连续三拍稳定偏离才重锁的自愈，同时治本，隐藏或还原时钟通知图标真的改了可见性时置布局脏，使紧随其后的采样被 onGlobalLayout 拦掉，日志新增 line left rejected 与 line left relocked 两行诊断。【新增 SystemUI 作用域授权探测】状态栏字幕开关按钮改为仅在 SystemUI 作用域已确认授权时显示，判据用跨进程握手，App 每三秒发一次 PING，被注入 SystemUI 的模块回 PONG，八秒内没收到即视为未授权并隐藏该按钮，PONG 携带 SystemUI 侧构建号，与 App 不一致时打警告提示重启 SystemUI。【承 936 gap 4dp / 935 左界实测 / 934 滚动迟滞与速度下限 / 933 等宽补起滚 / 925 稳定性移回消费端】 基于 2.0.1，含 1.21.1~1.21.16 全部内容） ====");
        XposedCompat.log("[DLsiteSoundFloat] build applicationId=" + BuildConfig.APPLICATION_ID
                + " versionName=" + BuildConfig.VERSION_NAME);

        Context systemCtx = null;
        try {
            Class<?> activityThreadCls = XposedCompat.findClass("android.app.ActivityThread", cl);
            Object activityThread = XposedCompat.callStaticMethod(activityThreadCls, "currentActivityThread");
            systemCtx = (Context) XposedCompat.callMethod(activityThread, "getSystemContext");
        } catch (Throwable t) {
            XposedCompat.log("[DLsiteSoundFloat] getSystemContext failed: " + t.getMessage());
        }

        SubtitleRepository repo = SubtitleRepository.getInstance();
        repo.init(systemCtx, cl);

        NetworkHook.hook(cl, repo);
        PlayerPositionHook.hook(cl, repo);
        PlayerSourceHook.hook(cl, repo);
        SubtitleViewHook.hook(cl, repo);
        ActivityButtonHook.hook(cl, repo);
        // v34：钩住宿主视图树的结构事件（挂载/卸载/显隐/转场），把按钮显隐从「600ms 轮询」
        // 改成「宿主一动就扫」。最后一个装 —— 它依赖 ActivityButtonHook 的静态状态。
        StructureWatcher.hook(cl);

        // 悬浮窗与目标 App 同进程，注册观察者后由 FloatingWindowManager 自动对齐显隐与内容
        // 同时把当前字幕行广播给 SystemUI（与悬浮窗生命周期解耦：悬浮窗关了字幕照样在状态栏）
        final Context sysCtx = systemCtx;
        repo.addObserver(() -> {
            Context c = SubtitleRepository.getInstance().getAppContext();
            if (c == null) c = sysCtx;
            FloatingWindowManager.getInstance().sync(c);
            // 【1.21.15 问题 1】这里原本还有一句
            // StatusBarSubtitleBridge.forceDisabledWhenNoSubtitles(c, repo) ——
            // 含义是「判定本音轨无字幕，就把
            // 用户长按开的状态栏字幕开关强制关掉」。
            // 已删除：它只会让开关在用户不知情的
            // 情况下自己失效，而且没有任何自动
            // 恢复路径。「无字幕时状态栏不挂字幕」
            // 下面这句 sendCurrentFromRepo 已经能保证：
            // 无字幕时它算出 line=""，SystemUI 收到空行就
            // 把字幕容器（连同通知徽标）整体 GONE，
            // 时钟自然还原。
            StatusBarSubtitleBridge.sendCurrentFromRepo(c, SubtitleRepository.getInstance());
        });
    }

    /**
     * 钩 {@code android.app.Application#attach(Context)}，在 Application 拿到 Context 的
     * 第一时间把上下文交给仓库 / 状态栏桥 / 网络诊断日志。
     *
     * 迁移对照：旧 {@code XposedHelpers.findAndHookMethod("android.app.Application", cl,
     * "attach", Context.class, new XC_MethodHook(){ afterHookedMethod … param.args[0] … })}
     * → 新 {@code hook(m).intercept(chain -> { chain.proceed(); chain.getArg(0); return null; })}。
     */
    private void hookApplicationAttach(ClassLoader cl) {
        if (sAppAttachHooked) {
            return;
        }
        sAppAttachHooked = true;
        try {
            Class<?> appCls = XposedCompat.findClass("android.app.Application", cl);
            java.lang.reflect.Method attach = XposedCompat.findMethodExact(appCls, "attach", Context.class);
            XposedCompat.hookMethod(attach, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    try {
                        Context appCtx = (Context) chain.getArg(0);
                        SubtitleRepository repo = SubtitleRepository.getInstance();
                        repo.setAppContext(appCtx);
                        // 同步状态栏字幕开关（默认关闭），让 SystemUI 侧拿到初始状态
                        StatusBarSubtitleBridge.sendEnabled(appCtx, StatusBarSubtitleBridge.sAppEnabled);
                        // 此刻上下文才真正可用：初始化网络诊断日志并打印落盘路径
                        NetLogFile.init(appCtx);
                        XposedCompat.log("[DLsiteSoundFloat] Application attached, context ready");
                        XposedCompat.log("[DLsiteSoundFloat] net log path -> " + NetLogFile.getPath());
                    } catch (Throwable t) {
                        // 钩子体绝不能把异常抛回宿主
                        XposedCompat.log("[DLsiteSoundFloat] onApplicationAttach body failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedCompat.log("[DLsiteSoundFloat] hookApplicationAttach failed: " + t.getMessage());
        }
    }
}
