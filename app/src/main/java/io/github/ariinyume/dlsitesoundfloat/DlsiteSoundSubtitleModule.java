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

import io.github.ariinyume.dlsitesoundfloat.config.ConfigBus;
import io.github.ariinyume.dlsitesoundfloat.config.RemoteConfig;
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
        // 【2.3.1 根修 · 用例 2.1.1 / 2.6 / 2.8】只给**宿主包**挂 Application#attach 的钩子。
        //
        // ⚠️ 这里以前只判 isTarget()，把 SystemUI 也放进来了 —— 那个钩子的钩子体里有一句
        //    ConfigBus.installResponder(appCtx, "host")，于是 SystemUI 进程里**也**挂上了
        //    一个自称 tag="host" 的应答器。后果（真机日志铁证，LSPosed_20261005_204342）：
        //      (com.android.systemui)[…] [host] scope ping answered -> pong sent (hostAlive=false)
        //      (jp.co.eisys.dlsitesound)[…] [host] scope ping answered -> pong sent (hostAlive=true)
        //    设置页那句 PING 会收到**两条** tag="host" 的 PONG，先到的常常是 SystemUI 那条。
        //    而 SystemUI 进程里的 SubtitleRepository 从没被 init 过播放状态
        //    （playerPageVisible 恒 false、playingState 恒 -1），isHostAlive() 于是返回 false
        //    ⇒ 设置页判成「DLsiteSound 未运行」。
        //    这就是「宿主明明在播/在后台，却显示未运行」「只勾 SystemUI 显示未运行（应为未激活）」
        //    「只勾宿主显示未激活（应为已激活）」三个用例的**同一个根因**：应答端串了进程。
        //
        // SystemUI 侧的配置通道由 StatusBarSubtitleHook#installConfigChannel 用 tag="systemui"
        // 单独安装，不经过这里，因此本改动不影响 SystemUI 的任何既有能力。
        if (!TARGET_PKG.equals(pkg)) {
            return;
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
        // ⚠️ 横幅纪律（本项目铁律 13）：本行是**普通字符串字面量**，历史段也全都进常量池，
        //    所以**不要**在横幅里点名任何「已被删掉的标识符 / 探针串 / 文案」——
        //    否则负向锚（「某串必须彻底消失」）会被横幅自己命中而假 FAIL（2.1.4 连踩两次）。
        //    下面这段 2.1.4 描述只用**功能性说法**（「按签名形状定位宿主对象」），
        //    绝不写出具体方法名或被移除的类名。
        XposedCompat.log("[DLsiteSoundFloat] ==== BUILD 2.2.8 / code 980 （【980 —— 反向切轨残留根修：「从无字幕轨切回有字幕轨，插件仍显示『无字幕』」】①用户 2026-10-07 晚反馈：正向（有字幕轨切到无字幕轨）已经能正确判定，但反向切回原先那条有字幕的音轨时，插件仍然显示「无字幕」。真机日志（二十二点二十三分那一份）把过程钉死：十八分十五秒加载了三十九条字幕行，所属播放列表身份为「七轨、二百七十八点六四秒、序号零」；三十八分四八一秒识别到换轨「七轨变一轨」，挂起并把三处界面按「无字幕」呈现（这一步**正确**）；五十三分四八三秒软裁决定案（数据保留但不参与渲染）；五十八分七四六秒又识别到换轨「一轨变回七轨」，**再次**走进同一条挂起路径 —— 而这条轨的字幕其实一直好好地在手上。②根因：字幕的恢复路径**只有一条**，即等宿主重发字幕 JSON（加载成功时清掉挂起与隔离）。可宿主对**同一条轨**的响应是有缓存的，切走再切回来它**不会重新发请求**，于是模块永远等不到新 JSON，就永久停在「无字幕」；数据其实一条都没丢。③修法一（数据层）：给手上的字幕行盖一枚「主人印章」—— 每次成功加载字幕 JSON 时，记下那一刻的活跃播放列表身份；此后换轨时若目标身份**正是这枚印章**，说明这条轨的字幕我们本来就有，于是直接恢复渲染：撤销挂起、撤销软裁决、撤销「上一轨字幕行禁止渲染」，按当前播放进度重新定位到正确的那一句，必要时把先前因无字幕而自动关掉的悬浮窗开回来。判据安全性：印章只在一次成功的字幕加载时盖上，所以「身份一致」等价于「这份字幕行就是这条轨的」，渲染它不可能画错内容。④修法二（换轨闸门）：去抖闸门里那条「同一对身份在二十秒内不再重复上报（含反向）」加一个例外 —— 当目标身份正是手上字幕行的主人时立即放行：这不是宿主在两个列表之间横跳，而是用户切回了他刚离开的那条轨，**没有裁决窗需要保护**，硬静默二十秒只会让「无字幕」白挂十几秒；放行后同时让静默窗失效，避免紧接着的真换轨被「反向同对」吞掉（真机时间线里这两次换轨只隔约七秒，不放行就会白白多显示十几秒「无字幕」）。⑤权威门、软挂起、软裁决、以及「上一轨字幕行禁止渲染」的语义**一行未动**，去抖闸门的两条阈值也一字未改。版本号 2.2.7 升 2.2.8、code 979 升 980）==== BUILD 2.2.7 / code 979 （【979 —— 新增「调试日志」开关，诊断日志默认不再刷屏】①动因：用户反馈日志里出现**大量**状态 Map 转储行（形如「statusMap via=… 实例标识 ihc=…」）。②那是前两版为定位「切到无字幕音轨仍残留」而加的**只读诊断转储**：把宿主整张播放器状态 Map 原样打出来，按「来源加上实例」各自节流二点五秒；它确实是那两轮排查的唯一证据来源、必须留着，但与每秒一次的兜底轮询以及宿主的多个播放器实例相乘，真机上约每分钟二十到七十条，长时间开着会把有用日志淹没。③本版在设置页「其他」卡片新增「调试日志」开关（状态栏字幕功能下方、备份恢复上方），**默认关闭**；关闭时诊断级日志一律不输出 —— 包括状态 Map 全量转储、轮询自证、换轨闸门的抑制与忽略行；只保留里程碑日志：钩子挂载、种基线、真正的换轨上报，以及全部告警。④本开关只改「打多少日志」，**不改任何功能行为** —— 换轨判定、权威门、去抖与静默闸门一行未动。⑤配置键 debug_log（默认关），与其它配置键走同一条链路（本地偏好、标准配置 JSON、跨进程广播），因此设置页一保存即刻在被注入进程生效。版本号 2.2.6 升 2.2.7、code 978 升 979）==== BUILD 2.2.6 / code 978 （【978 —— 换轨判据从「曲目序号」升级为「播放列表身份」】①真机录屏加日志（用户从作品甲切到作品乙）把根因钉死：宿主切「作品 / 音轨」时，播放列表回报的曲目序号**恒为零** —— 六轨列表是零、单轨列表也是零 —— 所以此前五版（九百七十三到九百七十七）所有以序号为核心的判据**一次都没成立过**，这才是连修五轮无效的真正原因。真正随切换变化的是「播放列表身份」：曲目总数与总时长，从「六轨、七百三十一秒」变成「一轨、二千四百三十一秒」。本版把判据升级为三者合一的三元组：曲目总数、总时长、当前序号 —— 跨作品换列表靠前两项触发，同一列表内换轨靠第三项触发，三者全同则不是换轨。去抖与长静默两道闸门一字未动，继续挡住宿主的多实例横跳。②同一份日志还暴露了状态污染：宿主同时持有多个播放列表实例（当前活跃的、已经停止的、以及闲置的单曲播放器），读数交替流进来，于是进度被已停止实例的零值反复拉回起点 —— 字幕因此按旧列表匹配出上一轨的句子；播放态每秒横跳二十几次；播放状态被覆盖，导致「播放结束确认」四十七次全被驳回、悬浮窗不关。本版新增一道权威门：只有「当前活跃的播放列表」（有曲目、有时长、未被停止）这一路读数才有权更新仓库状态，已停止实例与闲置单曲播放器只贡献诊断。宿主从未暴露列表时自动退回旧行为，老环境不受影响。③顺带澄清一条一直被误读的现象：记录里那个「静音、音量为零」并非故障，是宿主的扬声器静音保护（真机提示原文「因将透过设备扬声器输出，正在静音播放」），与有没有字幕无关。④诊断补强两处：状态 Map 转储不再把字符串值一律抹成占位符，而是直接打出实际值（超长截断）；并按实例身份分别节流，这样「同类的实例到底有几个、各自什么状态」一看便知。版本号 2.2.5 升 2.2.6、code 977 升 978）==== BUILD 2.2.5 / code 977 （【977 —— 换轨残留追到「采样层」根修 + 只读诊断】本轮把「换轨检测为什么一次都不触发」追到了采样层，没有再动上一版的去抖判据（上一版的判据本身是对的）。①真机日志铁证：宿主自己读播放器状态 Map 的相邻间隔高达四十到一百四十秒，而兜底轮询当时用的是「连续十五秒没有真实样本就永久停机」的写法，且唯一的重启入口只在进程加载时调一次，于是停机之后再无任何采样，恰好错过用户切轨的那一刻。本版把停机改为只降频 —— 空闲时从一秒一次降到三秒一次，只有「十分钟完全无样本」才真正停；并每十秒打一条轮询自证日志，便于确认真机上轮询还活着。②真机日志还显示：序号判据里有一条「距上次观测太久就重新种基线」的早退，在读数本来就四十到一百四十秒一次的现实下几乎每次都命中，于是相邻两次读数永远走不进「比较」那一步，换轨一次都报不出去。本版改为只在「本进程首次观测到序号」时种基线；进程重建本来就由首次分支独立覆盖，长时间后台若真换了轨也本来就该通知数据层。③只有播放列表对象的状态 Map 才带曲目序号，播放器对象的不带；旧写法在实例表满时无脑淘汰最旧的一个，宿主高频读播放器状态时会把播放列表对象挤出去，轮询就再也读不到序号。本版改为淘汰时优先「同类里最旧的」，并把上限由四提到六，保住跨类多样性。④新增一条按来源分档节流的「状态 Map 全量转储」诊断 —— 本轮根因判决卡在「从哪个通道、多久才读到一次序号」上，静态判据照不到，只能靠真机把 Map 原样打出来看；该诊断只读不改、不参与任何判定。版本号 2.2.4 升 2.2.5、code 976 升 977）==== BUILD 2.2.4 / code 976 （【976 —— 换轨修复返工 + 设置页四条观感调整 + 底部彩蛋】①用户 2026-10-07 晚第二轮真机复测：从有字幕的音轨切到没有字幕的音轨后，上一音轨的字幕依然保留、悬浮窗也不关。上一版的修法方向错了 —— 它要求新序号连续稳定一段时间才采信，而宿主在切轨期间会把曲目序号在两个值之间规律交替，每次交替回来都把那段计时清零，于是换轨通知一次都发不出去、数据层根本不知道换过轨（真机日志佐证：上一版时段里换轨事件一条都没有，再上一版是每秒一条）。本版改判据为「首报即报加长静默」：同一候选序号再次出现即认定换轨，中途回到原值不清零；一旦上报，二十秒内同一对序号（含反向）不再重复上报，好让数据层那一次裁决完整跑完到点（该静默窗必须大于裁决窗，这正是再上一版「每秒上报一次、裁决被反复作废」的根治点）；期间若出现第三个序号则立刻放行，说明用户又主动切了一次轨。②四张卡片最下方内容与卡片底边的距离，在上一版「与首行文字顶距对称」的口径上再翻一倍；同时把「其他」卡片末尾那排按钮也纳入同一套收敛逻辑（上一版只处理了以文字收尾的三张卡）。③预览区底边与下一张卡片主标题之间的距离，在上一版基础上再缩窄百分之三十。④状态卡上方间距翻倍、下方间距再加宽百分之五十。⑤新增页面底部彩蛋：滑到页面最底端后继续上拉，会淡入一行居中小字 SMILE! :D（用触摸位移判定，不拦截事件，故不影响滚动与吸顶）。版本号 2.2.3 升 2.2.4、code 975 升 976）==== BUILD 2.2.3 / code 975 （【975 —— 两条功能修复 + 设置页六条观感返工】①用户 2026-10-07 报：宿主一直开在后台，插件却经常判定它没在运行，状态卡持续停在未激活。根因是两条探测广播过去都是隐式广播，而目标系统在较新版本上禁止后台进程接收隐式广播，宿主退到后台后既收不到探测、也没有机会回话；同时探测窗只有一千二百毫秒，偏短。本版两头都改：探测广播改为显式指定接收方（不受后台广播限制、能直接投递给目标进程），探测窗放宽到二千八百毫秒并在窗口内每四百毫秒重发一轮（约七轮）；另加一层会话内记忆 —— 只要本页曾经确认过一次宿主授权，后续重绘就不再退回未激活。②用户报：从有字幕的音轨切到没有字幕的音轨后，上一音轨的字幕仍然保留、悬浮窗也不关。根因（日志铁证：换轨期间宿主回报的曲目序号在零与二之间每秒来回跳一次，而旧逻辑一读到变化就上报，于是每秒作废一次上一轮的换轨裁决，裁决任务永远等不到点、字幕就一直挂着）。本版两层收口：信号层加「待定 + 连续稳定一点二秒」才采信，并抑制两秒内的同向重复；数据层在换轨瞬间就把显示按无字幕呈现，同时软挂起也排一次提前收窗 —— 五秒内等不到新字幕就关窗。③状态栏字幕允许的末字裁切上限由六像素收紧到五像素（用户复测六像素仍会吃掉一点点笔画）。④整卡最下方那行文字与卡片底边的距离，改成与「卡片顶端到首行文字」的距离一致 —— 做法是用首行的墨迹顶距作目标值，反推末行应补的底边距。⑤恢复默认改为小号按钮，高度与页头的语言胶囊完全一致（原来偏大、占位太多）。⑥页面内其余可改的控件一并收进同一套材质设计最新表达式的度量：底栏两枚按钮统一内缩归零、水平内边距加大、字号升到十四、全圆角；行末辅助按钮统一为小号档。⑦预览区底边与下一张卡片主标题之间的距离缩窄百分之二十（三十 dp 改二十四 dp）。⑧页头行、状态卡、预览窗标题三者过近，状态卡上距与下方标题上距各放宽百分之二十（六 dp 改七点二 dp、十二 dp 改十四点四 dp）。版本号 2.2.2 升 2.2.3、code 974 升 975）==== BUILD 2.2.2 / code 974 （【974 —— 设置页界面五条返工 + 换轨修复首次打包】①用户 2026-10-07 指令：滑块改成材质设计最新表达式的形态 —— 轨道加粗到十六 dp，手柄改成长四十四 dp、宽四 dp 的竖长圆角条，手柄左侧走主色、右侧走次色容器色，拖动改值的交互与刻度维持原样。②卡片内不再用一 dp 细线分隔功能选项，改为等量纯留白（参考稿是每行一块、行与行靠留白分开的列表形态），四个节标题的上边距拉齐，使卡与卡之间的总间距等于参考稿实测的一百八十像素。③对齐分段按钮：已选项铺深重点色、未选项铺浅重点色，并去掉轨道那圈描边。④每张卡片最下方那行文字的字形底端统一压到距卡片底边六 dp —— 做法是布局完成后量出末行的墨迹底与盒子底还差多少，再用等量负底边距抵消，因为行距倍数与字体包围盒都会把这截空档加在末行下方。⑤状态栏字幕允许的末字裁切上限由四像素放宽到六像素，用略多一点的裁切换更短的句尾留白。本轮同时把上一版只改源码、未打包的换轨修复一并出包。版本号 2.2.1 升 2.2.2、code 973 升 974）==== BUILD 2.2.1 / code 973 （【973 —— 切到无字幕音轨不再残留上一音轨字幕】①用户 2026-10-07 报：切到没有字幕的音轨后，插件仍显示上一音轨的字幕（真机截图：新音轨播到四秒，状态栏与悬浮窗却是上一音轨的第二句）。②根因一（权威序号门漏判）：旧版把「曲目序号归零、且旧序号大于一」一律当作播放列表被重置而静默忽略；但零号轨是真实音轨（截图里就是本编），当天五点五小时日志里这类归零事件共十一次，全部伴随真实的切作品或切轨。被吞掉后数据层收不到换轨通知，旧的字幕行列表继续参与渲染，新音轨从零秒起播、走到几秒时按旧列表匹配出上一轨的句子。本版取消该吞并分支：序号一变就上报；进程重建与长时间后台仍由基线过期保护拦住（只重种基线、不发通知）。③根因二（裁决窗到点后旧字幕复活）：换轨瞬间虽已清空显示内容，但裁决窗到点仍没等到新字幕时旧版会放开限制，于是旧列表又能渲染，上一轨字幕十几秒后重新出现。本版把「数据保留」与「可否渲染」彻底分开：旧的字幕行数据一个都不删（新字幕晚到时仍能立刻恢复），但在新字幕到达之前一律不参与渲染。④权威序号门与老信号路径分开：序号门上报的真实换轨不再走「疑似假换轨」的旧兜底（真机两份日志里该兜底从未触发）。⑤新增两条自证日志：换轨上报行会带权威序号门标记；挂起时打一条上一轨字幕行已隔离。版本号 2.2.0 升 2.2.1、code 972 升 973））==== BUILD 2.2.0 / code 972 （【2.2.0 第六版 —— 句尾空白与末字裁切的阈值再收口】"
                + "①上一版（code 971）把吸附改成「就近」，但允许末字被裁的上限设成 8px，Ari 真机反馈仍觉末字偏裁。"
                + "②本轮把该上限从 8px 收紧到 4px（约 1/10 字宽），末字裁切更轻、几乎不可见；"
                + "代价是句尾空白略回升（就近吸附能向下取的空间变小），属于「留白 ↔ 裁切」的正常此消彼长。"
                + "版本号 2.2.0 不变、code 971→972）"
                + "==== BUILD 2.2.0 / code 971 （【2.2.0 第五版 —— 缩短「句尾多余空白」】"
                + "①上一版（code 970）真机结果：最后一个字完整了、也不再飞走，但**每句后面多了一块空白**，观感太大。"
                + "②根因（日志铁证）：滚动终点「吸附到字符边界」时，旧写法只往右取「不小于终点的那个字起点」，"
                + "于是终点被向右多推了平均约 26px、最多 40px（接近一个字宽）—— 这块就是句尾空白。"
                + "③修法：吸附改成「就近」—— 终点取离目标最近的字边界，向右过冲最多半个字、空白砍半；"
                + "但「向左取」必须以「末字仍完整」为前提（向下取会裁掉末尾时自动退回右边界）。"
                + "④新增诊断日志：打印左右两个候选边界与最终选择，便于下一轮取证。"
                + "版本号 2.2.0 不变、code 970→971）"
                + "==== BUILD 2.2.0 / code 970 （【2.2.0 第四版 —— 修上一版引入的「字幕飞走」】"
                + "①上一版（code 969）真机结果：状态栏字幕**被滚出屏幕外**（用户描述「直接飞走」）。"
                + "②根因（新版诊断日志一眼可见）：算「必须完整露出的宽度」时把**排版宽度**也纳入了取最大，"
                + "而真机实测该值在「视图尚未完成一次真实排版」时会返回一个**超大占位值 1048576**（即 1024×1024）——"
                + "于是滚动目标被算到一百多万像素，字幕按最大速度一路滑出屏幕，动画时长也因此被拉到几十分钟。"
                + "③关键教训：**「取最大」只有在「输入可信」的前提下才成立** —— 上一版正是把旧代码里"
                + "一个歪打正着挡住了该脏值的写法改掉，才把它放了进来。"
                + "④修法：三个宽度口径**先过合理性闸门再取最大** —— 任一口径只要比文本步进宽度"
                + "大出一个字宽以上，就判定为脏值、直接忽略并打印一次告警；随后再加一道总闸门与一道"
                + "出口保底（滚动量绝不超过文本总宽）。⑤诊断日志会标出被拒绝的异常值，便于后续取证。"
                + "版本号 2.2.0 不变、code 969→970）"
                + "==== BUILD 2.2.0 / code 969 （【2.2.0 第三版 —— 「半个字」再收口】"
                + "①上一版（code 968）真机结果：左边缘那半个字没有了（终点吸附到字边界有效），"
                + "但用户仍报「最后一个字还是会有一点没显示完全」。"
                + "②把真机截图逐像素量过之后确认：末字右端确实被**垂直硬切**掉约五个像素 —— "
                + "判据是同一行里，普通字的最右墨迹列分散在六列上（笔画自然收尾），"
                + "而末字有七成以上的行都堆积在同一列（硬切边的特征）。"
                + "③根因：算「必须完整露出的宽度」时只用了**字符步进宽度**（advance 总和），"
                + "而字形轮廓、抗锯齿、亚像素定位都可能再往外溢一点点；"
                + "更糟的是旧代码随后还拿「排版宽度」做**向下取最小值**，"
                + "而排版宽度只会小于等于真实需要 —— 两个方向叠加，滚动距离就偏小了几个像素。"
                + "④修法：宽度改成三个口径**取最大**（字符步进 / 字形轮廓 / 排版宽度），"
                + "再补一点末端安全余量；余量只在「确实超出可视宽」时才加，"
                + "免得把「刚好放得下」的短行硬逼成滚动。吸附的上界也加了保护，"
                + "绝不会把可视窗口整段滑到文字之外。⑤起滚日志新增三个宽度口径的数值，"
                + "下一轮核对时可直接看出是哪一项偏小。"
                + "版本号 2.2.0 不变、code 968→969）"
                + "==== BUILD 2.2.0 / code 968 （【2.2.0 第二版 —— 「半个字」根修】"
                + "①上一版（code 967）真机结果：滚动终点已经**算对了**（离线把 9 条实测逐条核对，"
                + "终点全部等于「文字总宽 − 已落地宽度」，0 条不符），但用户仍报「最后一个字只出现一半」。"
                + "②把真机截图放大逐像素量过之后找到真凶：文字本身两端都没被裁，"
                + "**挂掉的是停住那一刻的可视窗口边缘卡在某个字的中间** —— "
                + "终点是个像素值，几乎必然落在字中间，于是屏幕最左边永久残留「半个字」。"
                + "③修法：终点**吸附到字符边界**（取「不小于终点的最小字起点」）。"
                + "副作用是正向的 —— 向右多走最多一个字，文本末尾提前露出，"
                + "**最后一个字反而更完整**（还多留一点空白）。该吸附值必然小于「最后一个字的起点」，"
                + "数学上不可能把末尾推出屏幕。④拿不到字体度量时不吸附，保守维持原行为。"
                + "版本号 2.2.0 不变、code 967→968）"
                + "==== BUILD 2.2.0 / code 967 （【2.2.0 —— 状态栏字幕「最后一个字只出现一半」】"
                + "①症状：状态栏字幕横向滚动时，末尾总差那么一点，最后一个字被裁掉半截；"
                + "短到本该放得下的行也会被裁。②根因：本模块滚动前要先按「可用宽度」算出"
                + "「文字总宽 − 可用宽」当终点，而窗口宽度是**动态**的（右上角那个胶囊会伸缩），"
                + "每次改宽度都只是把新值写进布局参数、真正的排版要等下一帧；"
                + "偏偏算终点时读的是控件的**当前宽度**，它这时还是**上一帧的旧值** —— "
                + "误差恰好等于本次宽度变化量，缩窄时正好是半个到一个汉字，于是末尾被裁。"
                + "③修法：算终点一律改用「刚写进布局参数、下一帧必然生效」的那个宽度，"
                + "而不是去读控件的当前宽度；宽度变化被抖动阈值挡下（实际没改）时，"
                + "重算终点也必须用**真正生效**的宽度，不能用那个被丢弃的值。"
                + "④另加两道兜底：判「放得下、不用滚」之后，下一帧用真实宽度复核一次，"
                + "真放不下就补上滚动；滚动动画自然跑完时再用真实宽度复核一次终点，"
                + "若发现当初算小了就按原速度补完剩余距离 —— 保证末尾一定完整露出来。"
                + "⑤同时把起滚日志补上「已落地宽度」，以后这类问题一眼可辨。"
                + "版本号 2.1.4→2.2.0、code 966→967）"
                + "==== BUILD 2.1.4 / code 966 （【2.1.4 第三版 —— 第二版真机返工】"
                + "①第二版（code 965）真机结果：网络钩子仍然**挂上了但零命中**。两次失败的共性，"
                + "是都在猜「宿主用哪个方法读正文」—— 而同一个 App 里可能同时装了好几套网络栈，"
                + "「类名存活、能挂上」只说明能挂，**不说明那条路会被走**。"
                + "②这一版换思路：不再猜读取方法，改挂在**所有路径都必须经过**的那一步上 —— "
                + "响应对象被构建出来的那一刻。离线实测该步在宿主全包被调用二十处，"
                + "且一旦构建完成，正文对象就挂在响应对象的字段上（方法会被改名内联，**字段不会**）。"
                + "③读正文改成「复刻官方自己的非消费读取」：先把字节拉进响应体**自带的缓冲区**，"
                + "再对缓冲区做一次**写时复制快照**，只消费快照 —— 宿主读到的内容分毫不变。"
                + "（官方原本有一模一样的读取方法，但它被压缩工具当成没人用而删掉了，只能自己复刻。）"
                + "④定位方式全面升级：从「按签名形状」细化到**按字段类型形状 + 方法签名形状**联合判定，"
                + "不写任何被改名的短名；并给「像不像正文对象」加了五中其四的标记阈值，"
                + "实测真正的正文对象命中五个、响应对象自己只有两个 ⇒ 不会误中。"
                + "⑤补了强诊断：这版会记录**每一条经过的响应**（网址、类型、体积），"
                + "所以哪怕还没修好，日志也能直接看出「到底有没有发请求」以及「请求长什么样」。"
                + "版本号保持 2.1.4、code 965→966）"
                + "==== BUILD 2.1.4 / code 965 （【2.1.4 第二版 —— 首版真机返工】"
                + "①首版（code 964）真机结果：网络钩子**挂上了但一次都没触发** —— 日志里既没有「命中字幕数据」"
                + "也没有「非字幕正文」，说明那处方法根本没被宿主调用。根因是宿主实际走的是另一个网络模块"
                + "（expo 那套 fetch 实现），它不读文本，而是把正文按块读进一个累加器对象再取字节。"
                + "现改为挂钩那个累加器的取字节方法，并补一条「按字节读」的兄弟路径；"
                + "读取时只拷贝不动原对象，宿主后续读取完全不受影响。"
                + "②设置页「拉不到底」根修：底栏原先浮在内容之上、靠代码量高让位，"
                + "这条链路依赖两个不肯定的前提，任一不成立最后一行就被压在栏下且滚不出来；"
                + "现改为底栏**真实占位**的兄弟节点，滚动区自动在它上方收口，不再依赖任何时序或高度测量。"
                + "③判据同步升级：网络通道的自证串改盯「累加器锚点就绪」与「首次命中字幕数据」。"
                + "版本号保持 2.1.4、code 964→965）"
                + "==== BUILD 2.1.4 / code 964 （【2.1.4 —— 宿主 2.20.2 适配：R8 混淆后字幕全断】"
                + "①症状：宿主升到 2.20.2 后，插件在播放页一直显示「无字幕」，而画面上明明有字幕。"
                + "真机日志证实是**数据源整体消失**，不是判定逻辑出错 —— 全程没有一条字幕载入记录，"
                + "字幕列表恒为空，于是按钮、悬浮窗、状态栏三处一起显示「无字幕」。"
                + "②根因：宿主改用 R8 混淆，网络库与媒体库、音频模块的类名方法名被整体改名，"
                + "本模块原先「按名字去找宿主对象」的三个采集点同时失效。"
                + "③修法一（字幕）：改从宿主网络模块取响应体。该模块因为被框架按名引用，"
                + "类名与方法名在混淆后仍然存活，而它的某个内部方法的**参数类型**就是响应体类 —— "
                + "于是从签名里直接取出该类，完全不需要知道它叫什么。"
                + "读正文的方式也改了：不再自己截取字节流，而是在宿主自己读完文本之后读同一个返回值，"
                + "零额外开销、零副作用。"
                + "④修法二（进度与播放态）：宿主播放器实现类已不存在，改为读取音频模块暴露的"
                + "**状态字典**（无参、返回值是字典的那个方法）。该字典里同时含播放位置、时长、"
                + "是否在播、播放状态、曲目序号，**一个方法顶替原来的三个采集点**。"
                + "字典的键是字符串常量，混淆不会改字符串池，所以这些键跨版本稳定。"
                + "⑤修法三（换轨）：换轨判据的实质是「曲目序号变没变」，而序号就在上述字典里，"
                + "已并入同一条消费路径；「序号归零视为列表重置」「进程重建后首次读数只种基线」"
                + "这两条历史教训的判据原样保留。"
                + "⑥补一处调用频率的坑：状态字典只在脚本侧主动索取时才构造，不是高频回调，"
                + "只靠挂钩会导致字幕长时间不换行 ⇒ 加了一路每秒一次、连续 15 秒无样本即自动停的兜底轮询。"
                + "⑦防回归的关键设计：**按签名形状定位，不写死混淆名** —— R8 每次宿主发版都会"
                + "重新生成映射表，写死短名下一版就又失效；同时保留旧名字通道，"
                + "所以同一个包在未混淆的老宿主上照样能用。"
                + "⑧真机自证口径：日志里出现「已从网络模块挂上响应体读取」与「首次命中字幕数据」，"
                + "即证明通道已通；出现「已挂上音频模块状态字典」即证明进度通道已通。"
                + "版本号 2.3.3→2.1.4、code 963→964）"
                + "==== BUILD 2.3.3 / code 963 （【2.3.3 —— 设置页闪退热修】"
                + "①设置页「一点就闪退、Activity 起不来」的根因修复 —— 上一版给「导出」按钮新加的那个"
                + "矢量图标，其路径数据里有一段**坐标个数不合法**（一段折线只写了半组坐标，"
                + "而折线命令必须给齐成组的坐标），这一拼写错误编译期检查不出来 —— 打包工具不校验"
                + "路径语法，它在资源表里就是一段普通字符串，只有真机在解析这个矢量图时才会报错，"
                + "于是崩在设置页「创建 → 建行 → 建备份区 → 造按钮 → 载图标」这条链上，"
                + "整页还没来得及显示就结束了；且同一段路径里另有一处「语法合法但画出来是斜线」的写法，"
                + "会把托盘右下角画歪。现改用官方原始路径数据（不再做「把横竖线段改写成斜线命令」这种"
                + "等价改写 —— 那正是漏参数的高发动作），两个图标一并统一。"
                + "②新增一层离线校验补齐盲区 —— 此前只有「字节码层」与「资源表层」两层验证，"
                + "两层都覆盖不到矢量路径的坐标合法性；现增加「路径命令元数自洽 + 子路径包围盒不越界」"
                + "两层离线校验，扫描资源目录下全部矢量图，并配自测样例确认它有区分力、不是永远绿灯。"
                + "本版**代码逻辑零改动**，只修资源层语法错 + 加校验，功能表现与上一版完全一致。"
                + "版本号 2.3.2→2.3.3、code 962→963）"
                + "==== BUILD 2.3.2 / code 962 （【2.3.2 —— code 961 测试结果返工】"
                + "①设置页四组小按钮的真根因修复 —— 此前把一套**样式资源**当成了「样式属性」传给按钮，"
                + "解析不到 ⇒ 浅色容器底与配套的深色文字都没落地，真机上表现为「深底压黑字」读不清、"
                + "分段按钮三段同色看不出选中（被当成「点选不可用」）；现改为显式写入底/字/图标三种色调，"
                + "并补上「禁用态半透明」档 —— 整页禁用时按钮整块消失即由此而来。"
                + "②语言胶囊改为自绘 —— 图标与语言短名是两个并列子视图，不再互相挤掉（此前只画出图标）。"
                + "③状态卡补左右外边距，不再贴屏幕两侧。"
                + "④卡片内选项之间加回细分隔线（一张卡内部按行分隔，与参考稿一致，此前只剩留白、看不出边界）。"
                + "⑤删掉备份区按钮下方那个没有任何文字的空占位，两处底距统一收到 6dp。"
                + "⑥所有选项行统一最小高度并垂直居中 ⇒ 卡片首行的**文字**顶边逐卡一致"
                + "（此前开关行被开关撑高、文字被推低，四张卡首行对不齐）。"
                + "⑦预览卡重做 —— 预览容器原先会顶掉自定义背景，那层「半透明面板」其实从未画出来过；"
                + "现改为三层叠加（不透明壁纸底 → 半透明面板 → 逐行字幕），"
                + "吸顶时下方不再透出滚动内容、左右边缘也不再显出另一种底色，并恒定留 5dp 间距。"
                + "⑧夜间配色上抬一档 —— 页面大底与卡片底色同时提亮，状态栏与导航栏跟着大底走。"
                + "⑨页头行与状态卡改为「只在滚回页面最顶部时出现」，不再随吸顶的预览窗一起回来，"
                + "同时消除与滚动容器争抢滚动量造成的顿挫。"
                + "⑩悬浮窗面板为彩色时，关闭按钮与缩放手柄转白 —— 真根因是配色补写在控件创建之前，"
                + "而运行期那条补色分支在视图重建时走不到，故一直是灰的。"
                + "版本号 2.3.1→2.3.2、code 961→962）"
                + "==== BUILD 2.3.1 / code 961 （【2.3.1 —— code 960 测试结果返工】"
                + "①状态卡判定根修（用例 2.1.1 / 2.6 / 2.8 同一根因）—— 此前 SystemUI 进程也挂了 Application 钩子、"
                + "并以宿主的身份标签抢答作用域探测，于是「只勾 SystemUI、没勾 DLsiteSound」被判成「宿主未运行」；"
                + "现改为只对目标包挂钩子，并在应答侧加「当前进程是不是宿主」的硬闸；"
                + "宿主存活判定改为读进程调度优先级，不再依赖「播放页可见 / 正在播放」这类会随暂停抖动的信号。"
                + "②去掉对远端配置的写入尝试（只读实现下每次都会抛异常刷日志）。"
                + "③关闭「状态栏字幕功能」不再弹提示（常驻小字已足够，用例 7.8）。"
                + "④文案「高亮」→「不透明度」（三语同步，用例 4.10）。"
                + "⑤设置页按钮全面 MD3 化（用例 1.1.1）—— 重置 / 对齐 / 导出 / 导入由描边改为浅色填充（tonal），"
                + "不再是一圈细线加文字；分段按钮同套语言。"
                + "⑥间距统一（用例 1.14 / 7.1.1）—— 卡片间距加大、卡片内上下留白以「字幕排版」卡为准。"
                + "⑦深色模式卡片底色（用例 1.17）—— 补齐容器层级兜底色，卡片比页面底亮一档。"
                + "⑧系统显示大小不再影响本页排版（用例 1.18~1.20）—— 配置覆盖改为在 Activity 自身资源上生效，"
                + "语言胶囊加最大宽度上限防溢出。"
                + "⑨预览区重做（用例 3.2 / 3.4 / 3.5）—— 逐行独立视图与真悬浮窗同构（字号/行距/阴影/模糊逐参数同源）。"
                + "⑩色板行重做（用例 4.1.1 / 4.3 / 6.8）—— 自定义入口改为与色点等大的彩虹色环，"
                + "半透明棋盘格只出现在色点圆内。"
                + "⑪悬浮窗面板为彩色时，关闭按钮与缩放手柄转白（用例 6.1.1，不透明度不变）。"
                + "⑫底栏（用例 8.1 / 8.2）—— 左侧按钮改浅重点色填充；删掉底栏上沿那条分隔线。"
                + "版本号 2.3.0→2.3.1、code 960→961）"
                + "==== BUILD 2.3.0 / code 960 （【2.3.0 —— 核心链路修复】依据真机日志返工：①配置跨进程持久化根修 —— LSPosed 2.2.0 里被注入进程对 getRemotePreferences 是只读实现（日志铁证 Read only implementation 与 remote prefs EMPTY），现改为广播生效后额外写一份本进程本地的 dlsitefloat_hook prefs，读取链新增 hook-local-prefs 一档，重启不再丢配置；②作用域探测 PONG 误判根修 —— 应答端只对宿主 tag 应答并带上「是否真的在用」标志，探测端校验 tag 后才认定宿主已授权，宿主没勾或已划掉时不再谎报已激活；③移除两项实测无效的选项（键名保留解析兼容）；④新增悬浮窗底色配置，面板渐变两档颜色改由配置换算（默认值与旧观感等价）；⑤换轨瞬间显示层清空但数据保留，切到真正无字幕的轨不再残留上一轨字幕；手上没缓存时仍 5 秒提前收窗，手上有缓存则等满裁决窗、字幕晚到照旧自动开回（不闪窗）；⑥保存后即时生效 —— 悬浮窗整屏重画、状态栏当前行立即按新样式重绘；⑦设置页 UI 全面 MD3 化（页头语言胶囊、预览卡真吸顶、悬浮窗底色配置项、状态栏字幕功能开关语义重做等，与本次并行）；版本号 2.2.0→2.3.0、code 959→960）==== BUILD 2.1.2 / code 954 （work_diag_82 第五件事【同版本号第三次打包】⑤【code 955】锁死胶囊大小与文字大小：Ari 2026-09-26 报「按钮大小随显示大小变化」。真机日志 15:34~15:36 铁证：宿主 App 的 density 读数在 OPPO 屏幕缩放档位间长期来回跳（2.9750001 -> 3.875 -> 2.9750001 -> 3.5 -> 2.9750001），而 code 939 的「持续 20s 不一致就重锁」会把每个档位都当新常态锁一遍，于是 `[几何4] capsuleH` 在 95/112/124px 之间反复变（宽、圆角、文字、距滑条偏移同吃一套 dip2px）。本版新增「胶囊度量锁定密度」：首次用到时取宿主稳定密度锁死一次（日志记 appStable 与 system 两个读数），此后 dip2px 一律走它 —— 无论 stableDensity 怎么重锁都不再跟随；胶囊文字同时改为**固定 px**（= 13sp 设计值 × 锁定密度，不吃 scaledDensity）并按「胶囊宽 − 左右各 4dp」用粗体量过当前语言全部文案，放不下就按 0.25dp 步长缩到放得下（下限 10sp 等效），配合 singleLine + 不省略号 + 去字体自带留白 ⇒ 「文字完全显示」是算出来的保证。诊断新增 `[code 955] capsule metrics density locked: …` 与 `[code 955] capsule text locked: …px (design=… avail=… lang=…)` 两行）==== BUILD 2.1.2 / code 954 （work_diag_81 四件事：④【同版本号第二次打包，Ari 2026-09-26 指令】单个胶囊宽由 **75dp 改为 85dp**（versionName/versionCode 均不变，装机前请用本行里的「85dp」确认拿到的是这一版；按钮组总宽随之 160dp→180dp，容器 wrap_content，子视图 CAPSULE_W_DP 是唯一真源）；①【多语言】Ari 2026-09-26 需求 —— 新增 util/I18n 统一对外文案：系统语言为简体中文/中文（简体）时文字不变；为繁體中文/中文（繁體）/繁體中文（中國香港）/繁體中文（中國台灣）/繁體中文（香港）/繁體中文（台灣）时按钮换成 狀態欄 開/關、懸浮窗 開/關、無字幕（悬浮窗占位同为 無字幕）；非中文时换成 Status ON/OFF、Popup ON/OFF、No Sub（悬浮窗占位 No Subtitles）。语言判定走 Resources.getSystem() 的系统配置（不看宿主 App 的 per-app 语言），繁体判定先看 BCP-47 的 script（Hant/Hans）再看地区（TW/HK/MO），进程内只判一次。文案写死在代码里而不用 res/values：注入视图拿的是**宿主** Context，取不到本模块资源。②【无字幕占位 5s】NO_SUBTITLE_EARLY_CLOSE_MS 由 4s 改为 5s（切到无字幕音轨后悬浮窗挂着「无字幕」占位的时长；数据侧裁决窗与「字幕晚到自动开回」逻辑未动）。③并入 code 952/953 两个按钮显隐修复：952 —— 新建按钮不再用「上次结论」预判可见性（一律先 GONE，配 sForceNextDetect 保证播放页上同一帧亮起），治「按钮偶尔在非播放页出现」；953 —— 「整棵树扫成空」的一趟扫描不再当作判隐藏的证据，改为 60ms 后强制补检，治「播放页上按钮每 ~700ms 闪一次」）==== BUILD 2.1.1 / code 951 （work_diag_80 Ari 2026-09-25 反馈「偶尔按键会到页面上不合理的位置（挡住音声的封面）」的根修：**跟手偏置被播放页入场转场位移污染**。日志铁证 09:33:41.155 `page follow bias calibrated: 431px (ref=play-button)`，而同一刻 `[几何4] sliderCy=2233`（稳态 1799，差 434px）—— 打开播放页那一次标定踩进了入场转场中途的 80ms 停顿窗，把「还没走完的 431px」当成宿主静态偏置记了下来；此后 `vis = ΣtranslationY − 431` 在页面真正静止时恒为 −431，被原样写进按钮 translationY ⇒ 两个胶囊整体抬高 431px，从「滑条上方」跑到标题/封面下缘（转场相位更早时抬得更高，正好压在封面上）。旧门「连续两次扫描读到同一个 y」拦不住 —— 转场把扫描踢到 80ms 一次；偏置又只标定一次、`captureFollowBaseline()` 被 `sFollowAppliedY != 0` 挡在门外 ⇒ 标错就整个进程都错，这才是「偶尔」的成因。修法：门①.5「页面最近没动过」接到**位移采样时间轴**（sLastPageMotionMs ≥ 200ms）；门②.5 候选位置连续稳住 400ms（BASE_SETTLE_MS，墙钟口径）；门⑤ 偏置量级闸 64dp（首次标定的 |ΣtranslationY| 超了就不采信为宿主偏置，继续走基线通道，照样跟手）；新增 `healStaleBiasIfAtRest()` 自愈（页面确在布局静止位却还挂着非零位移 → 重标偏置、位移归零，不会误伤「手指按住页面停在半路」）；诊断新增 `baseline deferred:` / `bias NOT adopted:` / `stale bias healed:` 三行。⚠️ 装本包后请**重启 SystemUI 或重启手机**（跨进程握手会带 versionCode，951 != 950 会打一行不一致警告））==== BUILD 2.1.0 / code 950 （work_diag_79 Ari 指令：applicationId 由 com.sena.dlsitesoundfloat 改为 io.github.ariinyume.dlsitesoundfloat，以满足 LSPosed 官方模块仓库对反向域名归你所有的要求，sena.com 不归本项目所有故必须换。Java 包目录树、入口类全名、AndroidManifest 的 package、namespace 与 applicationId 同步迁移；五条跨进程广播 action 串随包名同步改写，App 与 SystemUI 两端一致。本版相对上一版的功能代码零差异，只换包名与版本号。装本包等于装了一个全新 App，旧版可卸载，LSPosed 作用域需重新勾选，装完必须重启 SystemUI 或重启手机）==== BUILD 2.0.3 / code 949 （work_diag_79 Ari 指令：把 948/949 两个「按钮比播放页晚消失」的修复正式发版。948 给锚点面积归零单开 120 毫秒确认窗；949 再压到首次检测即收起并新增页面静止前置门。本版与上一版相比只改 versionName 与构建横幅，代码与此前 949 交付包完全一致）==== BUILD 2.0.2 / code 949 （work_diag_78 Ari 复测：比上一版快了但仍有延时。30fps 视频逐帧像素：页面消失到胶囊消失为 133 100 200 200 67 毫秒，与日志 120 毫秒档完全吻合，残余全部来自 120 毫秒探针周期。本版把归零档改为首次检测即收起，并新增页面静止前置门防惯性滚动误收）==== BUILD 2.0.2 / code 948 （work_diag_76 Ari 报「点击简介回到作品页面时，播放页直接消失而按钮延时了一会才消失」：视频 10fps 逐帧像素取证，播放页特征区在 t=1.05 秒归零而胶囊按钮区到 t=1.80 秒才归零，滞后约 0.75 秒，日志侧对应 anchor dead 那条 need=600ms 的防假死确认窗。本版新增三档确认窗，锚点面积归零时收到 120 毫秒，与播放页同步收起，默认 600 毫秒档保留给拖动场景，诊断日志新增 gone 字段）。==== BUILD 2.0.2 / code 947 （work_diag_74 Ari 报「状态栏字幕打开时徽标里看不到通知数量」：946 的 EVEN_ODD 洞在真机上没出现，实测圆盘 33×33 完全实心、内切 23×23 零个暗像素；根因是每帧调用的 Path.rewind() 会把 FillType 清回 WINDING（Android 的 reset() 专门存取 FillType，rewind() 没有这层保护），于是圆与字形同向取并集成实心圆。本版改用 reset() 并每帧显式再 setFillType 一次兜底，自证日志追加 fill= 与 glyph= 两项直接可验）。==== BUILD 2.0.1 / code 946 （work_diag_72 Ari 报「通知图标直接不显示了」：945 用 saveLayer+BlendMode.CLEAR 画的镂空徽标在这台机器上整块画不出来，实测截图像素证明圆底与数字一起消失；本版改成一条 Path+EVEN_ODD 挖洞（不用图层不用混合模式），并给 onMeasure 加尺寸兜底、给徽标加几何自证日志）。==== BUILD 2.0.1 / code 945 （work_diag_71 Ari 指令：点悬浮窗面板出现的 ✕ 关闭按钮，再点一次按钮外的其他悬浮窗区域立刻收回，并与 5 秒无操作自动隐藏并存；状态栏通知数徽标里的数字改为镂空数字，用 CLEAR 从圆底挖出，数字区域直接透出状态栏自己的底色，不再用近似色填充）。==== BUILD 2.0.1 / code 944 （work_diag_70 Ari 指令：切轨提前收窗阈值由 6 秒收到 4 秒；点悬浮窗面板出现的 ✕ 关闭按钮改为 5 秒内没人点就自动隐藏；未授权 com.android.systemui 作用域时不显示状态栏字幕开关按钮，该闸门本轮才真正打进交付包，并加 0.4 秒与 1.2 秒两拍快速补探）。==== BUILD 2.0.1 / code 943 （work_diag_69：全部 19 个 java 源文件补 GPL-3.0 文件头；提前收窗阈值 2 秒放宽到 6 秒）。==== BUILD 2.0.1 / code 942 （work_diag_68 Ari 指令：切到无字幕音轨时悬浮窗 2 秒内无字幕 JSON 即提前自动关闭，不再挂着无字幕占位等满 10 秒裁决窗；字幕晚到会自动开回）。【bug1 流体云不存在时字幕只显示半截 根修】旧判据只看子视图自身 getVisibility 等于 VISIBLE，容器被摘掉或 GONE 后其子视图仍报 VISIBLE，死容器照样给出 left 等于 454，宽度被压到 341 至 380 即半截；改为容器与子视图一律用 isShown 加上宽高大于零，宽限由 60s 收到 2s，并新增 seeding state 状态翻转日志自证。【bug2 按钮太低 根修】旧口径取简介底边与滑条视图顶边中点，中心 1747 底边 1794，比滑条顶 1772 还低 23px 故压进度条；改为按钮底边强制落在滑条顶边往上 25dp 即 1698，按钮恒定 32dp 不压缩，日志新增 descStable 与 sliderTop 两个诊断量。【bug2 打开播放界面按钮上下抖动 根修】实测转场瞬间 ctx 的密度读数会从常态 2.9688 跳到 3.5 或 3.875（即 476 与 560 与 620dpi，正是 OPPO 屏幕缩放档位表），而按钮底边与右距都直接吃它故按钮上下瞬移 19 至 32px 且左右同偏，斜着抖；改为密度一次性锁定，仅在像素屏幕尺寸变化或持续 20 秒以上不一致时才重新锁定，并打 density spike ignored 诊断行。【bug1 状态栏字幕显示区域被缩到超级短 根修】行左界实测值的采信门旧口径拿自己当锚，sLineLeftAcc 初值为负一故首采样无条件过门，而 showLine 在隐藏时钟通知图标之后一毫秒就采样，那次重排还没跑，读到含通知图标占位的旧值 251 并永久锁存，之后真值 110 因超出容差被永久拒收，字幕宽少 138px 即被压成超级短；改为首采样也以常量 38dp 即 113px 为锚加正负 20dp 容差，单帧失真值直接拒收，并新增连续三拍稳定偏离才重锁的自愈，同时治本，隐藏或还原时钟通知图标真的改了可见性时置布局脏，使紧随其后的采样被 onGlobalLayout 拦掉，日志新增 line left rejected 与 line left relocked 两行诊断。【新增 SystemUI 作用域授权探测】状态栏字幕开关按钮改为仅在 SystemUI 作用域已确认授权时显示，判据用跨进程握手，App 每三秒发一次 PING，被注入 SystemUI 的模块回 PONG，八秒内没收到即视为未授权并隐藏该按钮，PONG 携带 SystemUI 侧构建号，与 App 不一致时打警告提示重启 SystemUI。【承 936 gap 4dp / 935 左界实测 / 934 滚动迟滞与速度下限 / 933 等宽补起滚 / 925 稳定性移回消费端】 基于 2.0.1，含 1.21.1~1.21.16 全部内容） ====");
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

        // 【M1】宿主侧标准配置通道的安装已移到 hookApplicationAttach 的 afterVoid 里：
        //  这里（onPackageReady / initTargetApp）拿到的 systemCtx 是 system 的 Context，
        //  用它 registerReceiver 会抛 SecurityException（caller package "android" not running
        //  in process），必须在 Application#attach 之后用真实的宿主 Application Context 装。
        //  SystemUI 侧（StatusBarSubtitleHook#installConfigChannel）不受影响，保持不动。

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
                        // 【M1】宿主侧标准配置通道：必须在**这里**、用 attach 注入的真实宿主
                        // Application Context 安装应答器。initTargetApp（onPackageReady）里拿到的
                        // systemCtx 是 system 的 Context，用它 registerReceiver 会抛
                        // SecurityException（caller package "android" not running in process）。
                        // 此处 appCtx 是真正的宿主 Application，注册成功后才收得到设置页保存广播，
                        // 才能触发热重载（否则所有字幕外观设置恒走 defaults，全部失效）。
                        ConfigBus.installResponder(appCtx, "host");
                        RemoteConfig.logDiagnostics();
                        XposedCompat.log("[DLsiteSoundFloat] config channel ready (source="
                                + RemoteConfig.source() + "): " + RemoteConfig.get().summary());
                        // 【2.3.0 §2.1.1.4】先把 L1 功能级总闸从配置同步进来，再推初始状态：
                        // 冷启动时若用户已在设置页关掉该功能，宿主进程必须**当场**知道
                        // （否则要等下一次配置广播才生效，状态栏会先亮起来再灭）。
                        StatusBarSubtitleBridge.setFeatureEnabled(
                                RemoteConfig.get().statusbarSubtitleEnabled);
                        // 同步状态栏字幕开关（默认关闭），让 SystemUI 侧拿到初始状态
                        StatusBarSubtitleBridge.sendEnabled(appCtx,
                                StatusBarSubtitleBridge.effectiveEnabled());
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
