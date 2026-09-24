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
package io.github.ariinyume.dlsitesoundfloat.util;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 旧 XposedBridge / XposedHelpers → libxposed API 102 的**兼容层**。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么需要这一层（2.0.0 迁移说明）
 *
 * 传统 API（{@code de.robv.android.xposed:api:82}）提供三样东西：
 *   ① 静态日志 {@code XposedBridge.log(String)}；
 *   ② 反射助手 {@code XposedHelpers.findClass / findMethodExact / callMethod …}；
 *   ③ 回调基类 {@code XC_MethodHook}（before/after 一对）与 {@code XposedBridge.hookMethod}。
 *
 * libxposed 现代 API（{@code io.github.libxposed:api:102.0.0}）**一个都不再提供**：
 *   · 日志 → {@link XposedInterface#log(int, String, String)}（实例方法，无静态入口）；
 *   · 反射助手 → 官方口径是「用 JDK 反射 / libxposed/helper」，但 helper 是可选依赖，
 *     本模块离线构建，所以这里**自带**一份纯反射实现；
 *   · 回调 → {@link XposedInterface.Hooker}，before/after 合并成一条拦截链
 *     （{@code chain.proceed()} 之前 = before，之后 = after；改写返回值靠 {@code return}）。
 *
 * 本类的存在意义就是**把这三样东西按原名/原语义补回来**，让 11 个业务文件的改动
 * 收敛成「换 import + 换基类」，而不是把每个钩子都重写成拦截链 —— 后者极易在
 * 「before/after 语义」「返回值改写」「异常吞不吞」这三处引入回归。
 *
 * ⚠️ 与旧 API 的**语义差异**（改代码前必读）：
 *   1. {@code afterHookedMethod(param)} 在旧 API 里可以调 {@code param.setResult(v)} 改写结果；
 *      新模型不允许篡改，只能**用 return 覆盖**。所以本层的 {@link SimpleHook} 提供的是
 *      {@code after(Chain, Object result)} —— 想改写就 {@code return 新值}，
 *      默认实现在返回前把原结果原样返回（见 {@link #hookMethod}）。
 *      本模块全部钩子**没有任何一处 setResult**（迁移前已 grep 确认），所以这个差异
 *      在本项目里是零风险。
 *   2. 新 API 里 hooker 抛异常会被框架按 ExceptionMode 处理。本模块所有钩子体都自带
 *      try/catch（「钩子体绝不能把异常抛回宿主」），语义保持不变。
 *   3. {@link #hookAllMethods} 在旧 API 里返回 {@code Set<XC_MethodHook.Unhook>}
 *      （代码里只用了 {@code .size()}）；这里直接返回**成功挂钩的数量**。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class XposedCompat {

    /** logcat tag：与原 XposedBridge.log 的输出保持一致，便于沿用既有排查手法。 */
    private static final String LOGCAT_TAG = "DLsiteSoundFloat";

    /** 本模块的 XposedInterface 实例；由入口类在 onModuleLoaded 注入（= module 自身）。 */
    private static volatile XposedInterface sApi;

    /**
     * 全局钩子注册表 —— 供 {@link #deoptimizeAll()} 回退用。
     *
     * ─────────────────────────────────────────────────────────────────────
     * ⚠️ 2.0.0 的重大认知修正（第一版这里写错了，导致真机「完全没日志」）
     *
     * 第一版注释里我断言「XposedModule 不实现 XposedInterface，所以不能当 API 用」——
     * **这是错的**。javap 实测 102.0.0 的真实继承链是：
     *
     *   XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface
     *   XposedInterfaceWrapper implements XposedInterface
     *
     * 即 **XposedModule IS-A XposedInterface**（log / hook / deoptimize 全部由
     * XposedInterfaceWrapper 以 final 方法提供）。框架在调用 onModuleLoaded **之前**
     * 已经自己调过 attachFramework(...)，所以从 onModuleLoaded 起，**this 就是一个
     * 可用的 XposedInterface** —— 这正是 libxposed 的设计意图（旧 API 的静态
     * XposedBridge.log 变成了「模块自己的实例方法」）。
     *
     * 第一版因为不信这件事，把 API 实例搞成「从挂载点类反射探测」+ sApi 长期为 null，
     * 于是所有日志走 android.util.Log.println 兜底 —— **而 Log.println 不会进
     * LSPosed 的日志包**（旧 API 的 XposedBridge.log 会）。结果是：模块可能跑得好好的，
     * 排查时却「一个字都没有」，等于亲手毁掉取证手段（见 xposed-ui-timing-debug 铁律）。
     *
     * 所以现在的口径是：**module 就是 API，直接用它**；{@link #findXposedInterface(Class)}
     * 仅作为万一 sApi 为空时的兜底探测保留。
     * ─────────────────────────────────────────────────────────────────────
     */
    private static final List<Object[]> sHookRegistry = new CopyOnWriteArrayList<>();

    private XposedCompat() {
    }

    /**
     * 记录一条本模块的钩子（executable + 提供 API 的类），供 deoptimize 回退时使用。
     */
    private static void remember(Executable exe, XposedInterface api) {
        if (exe == null || api == null) {
            return;
        }
        for (Object[] e : sHookRegistry) {
            if (e[0] == exe) {
                return; // 同一个 executable 只记一次
            }
        }
        sHookRegistry.add(new Object[]{exe, api});
    }

    /**
     * 把一个已挂钩的方法**撤销优化**（还原成解释执行）。
     *
     * 为什么需要它：本模块有若干「读一次就返回」的高频 getter 钩子
     * （ExoPlayerImpl.getCurrentPosition 每帧被调用），现代 API 的 hook 会内联/编译这些方法。
     * 当宿主在运行中替换了实现（RN 热更新 / 播放器重建），旧钩子可能落到已失效的 ArtMethod 上。
     * 保留这个能力是迁移时的**安全阀**：一旦出现「装了模块后宿主行为诡异」，可以一键回退。
     */
    public static void deoptimizeAll() {
        int ok = 0;
        for (Object[] e : sHookRegistry) {
            try {
                if (((XposedInterface) e[1]).deoptimize((Executable) e[0])) {
                    ok++;
                }
            } catch (Throwable ignored) {
            }
        }
        log("[DLsiteSoundFloat] deoptimizeAll: " + ok + "/" + sHookRegistry.size() + " methods");
    }

    /** 已登记的钩子数量（仅供日志/调试）。 */
    public static int hookCount() {
        return sHookRegistry.size();
    }

    /**
     * 取本模块的 {@link XposedInterface}。
     *
     * 正常情况下 {@code sApi} 在 onModuleLoaded 就由 {@link #attach} 填好了，直接返回。
     * 保留一点点兜底探测（万一 sApi 为空）：从**挂载点类**的接口里找
     * {@code io.github.libxposed} 前缀的接口再 asSubclass —— 但这属于「不该发生」的路径，
     * 真走到了会有 FALLBACK 日志自曝。
     *
     * @param loadedClass 钩子所在的宿主类（其 ClassLoader 属框架可见范围）
     */
    private static XposedInterface findXposedInterface(Class<?> loadedClass) {
        XposedInterface cached = sApi;
        if (cached != null) {
            return cached;
        }
        if (loadedClass == null) {
            return null;
        }
        try {
            for (Class<?> itf : loadedClass.getInterfaces()) {
                if (!itf.getName().startsWith("io.github.libxposed")) {
                    continue;
                }
                try {
                    return itf.asSubclass(XposedInterface.class).cast(loadedClass);
                } catch (Throwable ignored) {
                    // 这个接口与公开接口无关，试下一个
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 注入模块实例 —— **这就是本模块的 XposedInterface**（见 {@link #sHookRegistry} 的认知修正）。
     *
     * 调用点在 {@code onModuleLoaded}：框架在此之前已自行调过
     * {@code attachFramework(...)}，所以此时的 module 一定能用。
     * 之后所有 {@code XposedCompat.log/hook} 都走它，日志才会进 LSPosed 的日志包。
     */
    public static void attach(XposedModule module) {
        if (module != null) {
            sApi = module;   // XposedModule IS-A XposedInterface（javap 实测）
        }
        // 迁移期把「兼容层已就绪 + 真的拿到 API」写进日志：排查时一眼能看出走的是新 API 路径、
        // 而不是 Log.println 兜底（后者不进 LSPosed 日志包，等于看不见）。
        String apiInfo;
        try {
            apiInfo = "framework=" + sApi.getFrameworkName() + " " + sApi.getFrameworkVersion()
                    + " apiVersion=" + sApi.getApiVersion();
        } catch (Throwable t) {
            apiInfo = "api unavailable: " + t;
        }
        log("[DLsiteSoundFloat] XposedCompat attached (libxposed API 102 port of DLsiteFloat)"
                + (module == null ? " [null module]" : " [module " + module.getClass().getName() + "]")
                + " " + apiInfo);
    }

    /** 当前可用的框架 API 实例；未 attach 时为 null。 */
    public static XposedInterface api() {
        return sApi;
    }

    // ======================================================================
    // 日志（替代 XposedBridge.log）
    // ======================================================================

    /**
     * 与旧 {@code XposedBridge.log(String)} 等价。
     *
     * ⚠️ 用 {@link Log#INFO} 而不是 DEBUG：旧 XposedBridge.log 默认也会进 logcat（无优先级过滤），
     * 而本模块的排查流程（抽帧对时间戳 / 抓 LSPosed 日志包）依赖**所有**这些行，
     * 改成 DEBUG 会在部分 ROM 上被过滤掉 —— 那等于把取证手段弄没了。
     */
    public static void log(String msg) {
        log(Log.INFO, LOGCAT_TAG, msg);
    }

    /**
     * 带「模块内部 tag（形如 [DLsiteSoundFloat:Network]）」的日志。
     *
     * ─────────────────────────────────────────────────────────────────────
     * ⚠️⚠️ 2.0.0 铁律：**首选 api.log()，绝不能让 Log.println 成为常规路径**
     *
     * 旧 API 的 {@code XposedBridge.log()} 由框架接管，**必然进 LSPosed 的日志包**
     * （历史包里有 7000+ 行本模块日志可证）。而 {@code android.util.Log.println} 只在
     * 系统 logcat 里，**不进 LSPosed 日志包** —— 一旦日志都走兜底，
     * 排查时就是「模块看起来完全没跑，实际可能跑得好好的」，取证手段被无声销毁。
     *
     * 所以：
     *   · api 可用 → api.log(...)，与旧版形态逐字节一致（tag=DLsiteSoundFloat，
     *     模块内部前缀留在 msg 里，既有正则/脚本不用改）；
     *   · api 不可用（仅在框架注入前的极早期）→ 兜底 Log.println，**并且额外打一条
     *     「FALLBACK」自曝行**，让「日志为什么不在包里」永远有迹可循。
     * ─────────────────────────────────────────────────────────────────────
     */
    public static void log(int priority, String tag, String msg) {
        String text = (msg == null ? "null" : msg);
        XposedInterface api = sApi;
        if (api != null) {
            try {
                api.log(priority, LOGCAT_TAG, text);
                return;
            } catch (Throwable t) {
                // 框架拒绝（理论上只在 detach 后）→ 落到下面自曝
                try {
                    Log.println(priority, tag, "[FALLBACK api.log failed: " + t + "] " + text);
                } catch (Throwable ignored) {
                }
                return;
            }
        }
        // 兜底：仅在框架注入前。自曝一行，绝不静默。
        try {
            Log.println(priority, tag,
                    "[FALLBACK no XposedInterface yet — this line will NOT appear in the "
                            + "LSPosed log bundle] " + text);
        } catch (Throwable ignored) {
        }
    }

    // ======================================================================
    // 反射助手（替代 XposedHelpers）
    // ======================================================================

    /**
     * 按类名在给定 ClassLoader 里加载类（替代 {@code XposedHelpers.findClass}）。
     *
     * 与旧实现的差异：旧实现内部会依次尝试宿主 classloader / 自身 classloader；
     * 这里只需要 {@code cl}（调用方传的都是 {@code param.getClassLoader()} 或 lpparam 的），
     * 加一个「cl 为 null 时用本模块的 classloader」兜底。
     */
    public static Class<?> findClass(String className, ClassLoader cl) throws ClassNotFoundException {
        if (cl == null) {
            cl = XposedCompat.class.getClassLoader();
        }
        if (className == null) {
            throw new ClassNotFoundException("class name is null");
        }
        // 数组类型（如 "[B"）走 Class.forName；普通类走 cl.loadClass。
        return Class.forName(className, false, cl);
    }

    /** 找**无参**方法（替代 {@code XposedHelpers.findMethodExact(cls, name)}）。 */
    public static Method findMethodExact(Class<?> cls, String name) throws NoSuchMethodException {
        return findMethodExact(cls, name, (Class<?>[]) null);
    }

    /**
     * 找精确签名的方法（替代 {@code XposedHelpers.findMethodExact(cls, name, ...)}）。
     *
     * 与旧实现的差异：旧实现会沿父类链向上找；这里只在**声明类及其父类链**上找
     * （{@code getDeclaredMethod} 逐级上溯），语义等价，且比 {@code getMethod} 宽松
     * （非 public 也能拿到，与旧行为一致）。
     *
     * ⚠️ 注意与 {@link #findMethodExactArray} 的分工：Java 不允许同时声明
     * {@code Class<?>...} 与 {@code Class<?>[]} 两个同位置参数的重载
     * （编译期「名称冲突」，本文件就是这么被卡过一次），所以给「拿数组调用」的场景
     * 单独留了一个名字。调用点一般写 varargs 形式即可。
     */
    public static Method findMethodExact(Class<?> cls, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return findMethodExactArray(cls, name, parameterTypes);
    }

    /** {@link #findMethodExact} 的数组入参版本（内部用；也供「类型是动态数组」的调用点使用）。 */
    public static Method findMethodExactArray(Class<?> cls, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        Class<?>[] types = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        if (cls == null) {
            throw new NoSuchMethodException("class is null for " + name);
        }
        Class<?> c = cls;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name + paramDesc(types));
    }

    /**
     * 按名字**第一个**匹配的方法（替代 {@code XposedHelpers.findMethodExact} 的模糊用法）。
     *
     * 旧 API 里 {@code findMethodExact(cls, name)} 要求无参；而 {@code findAndHookMethod(cls, name)}
     * 在**无参类型**时钩的也是无参方法。本项目用到模糊查找的地方（{@code getCurrentPosition} /
     * {@code getPlaybackState} / {@code getCurrentTime} / {@code setVisibility} 等）都是无参或
     * 参数唯一，无需真正的模糊匹配 —— 这里仍提供该方法供「同名重载第一个」的场景使用。
     */
    public static Method findMethodByName(Class<?> cls, String name) throws NoSuchMethodException {
        if (cls == null) {
            throw new NoSuchMethodException("class is null for " + name);
        }
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    /** 列出某类（含父类链）上所有同名方法 —— 替代 {@code XposedBridge.hookAllMethods} 的枚举部分。 */
    public static List<Method> findMethodsByName(Class<?> cls, String name) {
        List<Method> out = new ArrayList<>();
        if (cls == null) {
            return out;
        }
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    out.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** 找字段（替代 {@code XposedHelpers.findField}）。 */
    public static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldException((cls == null ? "null" : cls.getName()) + "#" + name);
    }

    /** 取静态 Context（替代本项目里 {@code callStaticMethod(ActivityThread, "currentActivityThread")} 链路）。 */
    public static Object callStaticMethod(Class<?> cls, String name, Object... args) throws Throwable {
        return callMethod0(cls, null, name, args);
    }

    public static Object callMethod(Object obj, String name, Object... args) throws Throwable {
        if (obj == null) {
            throw new NullPointerException("callMethod: target is null for " + name);
        }
        return callMethod0(obj.getClass(), obj, name, args);
    }

    /**
     * 真正干活的那一层：按**参数个数**挑同名重载，再按「声明类型可接受」筛一遍。
     *
     * 为什么不直接用精确签名：这套反射助手要服务的是**宿主 App 的混淆后类**
     * （okhttp 在 DLsiteSound 里被重打包、RN 的类名也可能变），调用点写不出精确类型；
     * 旧 {@code XposedHelpers.callMethod(obj, name, args...)} 用的也是同样的宽松策略
     * （按参数个数 + 类型兼容性挑）。这里保持同等宽松度，避免「换个宿主版本就挂不上」。
     */
    private static Object callMethod0(Class<?> cls, Object receiver, String name, Object[] args)
            throws Throwable {
        if (args == null) {
            args = new Object[0];
        }
        Throwable last = null;
        for (Method m : findMethodsByName(cls, name)) {
            if (m.getParameterCount() != args.length) {
                continue;
            }
            if (receiver == null && !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (receiver != null && Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                return m.invoke(receiver, args);
            } catch (InvocationTargetException e) {
                // 宿主方法内部抛的异常：原样上抛**真实原因**（旧 API 也是这么做的），
                // 否则调用点只能看到一个无信息量的 InvocationTargetException。
                last = e.getCause() == null ? e : e.getCause();
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new NoSuchMethodException((cls == null ? "null" : cls.getName()) + "#" + name
                + paramDescOfArgs(args));
    }

    /**
     * 构造实例（替代 {@code XposedHelpers.newInstance}）。
     * 按参数个数 + 类型兼容性挑构造器。
     */
    public static Object newInstance(Class<?> cls, Object... args) throws Throwable {
        if (cls == null) {
            throw new NullPointerException("newInstance: class is null");
        }
        if (args == null) {
            args = new Object[0];
        }
        Throwable last = null;
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != args.length) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            } catch (InvocationTargetException e) {
                last = e.getCause() == null ? e : e.getCause();
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new NoSuchMethodException(cls.getName() + "<init>" + paramDescOfArgs(args));
    }

    private static String paramDesc(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(types[i] == null ? "?" : types[i].getSimpleName());
            }
        }
        return sb.append(")").toString();
    }

    private static String paramDescOfArgs(Object[] args) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append(")").toString();
    }

    // ======================================================================
    // 钩子回调（替代 XC_MethodHook）
    // ======================================================================

    /**
     * 单方法钩子的便利基类 —— 语义上是旧 {@code XC_MethodHook} 的「只关心 after」简化版。
     *
     * 旧代码里 **全部 23 处钩子都是 afterHookedMethod**（迁移前已 grep 确认
     * {@code beforeHookedMethod} 命中 0 次），所以这里只保留 after 语义，
     * 不引入 before 会让「是不是顺手改成了 before」这种回归变得不可能发生。
     *
     * 用法（对应旧写法）：
     * <pre>
     *   // 旧
     *   XposedHelpers.findAndHookMethod(cls, "getCurrentPosition", new XC_MethodHook() {
     *       {@literal @}Override protected void afterHookedMethod(MethodHookParam p) {
     *           Object r = p.getResult();
     *           ...
     *       }
     *   });
     *   // 新
     *   XposedCompat.hookMethod(XposedCompat.findMethodByName(cls, "getCurrentPosition"),
     *       new SimpleHook() {
     *           {@literal @}Override protected void after(XposedInterface.Chain chain, Object result) {
     *               ...           // result 就是旧 p.getResult()
     *           }
     *       });
     * </pre>
     */
    public abstract static class SimpleHook implements XposedInterface.Hooker {
        /**
         * 原方法执行完之后调用。
         *
         * @param chain  拦截链：{@code getThisObject()} 等价旧 {@code param.thisObject}；
         *               {@code getArgs()} / {@code getArg(i)} 等价旧 {@code param.args}。
         * @param result 原方法的返回值（void 方法为 null）—— 等价旧 {@code param.getResult()}。
         * @return 最终对外返回的结果：想**改写**就返回新值，不想改就返回 {@code result}。
         *         ⚠️ 新模型没有 setResult，返回值就是唯一出口；忘 return 等于把返回值改成 null。
         */
        protected abstract Object after(XposedInterface.Chain chain, Object result) throws Throwable;

        @Override
        public final Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            return after(chain, result);
        }
    }

    /**
     * 把「原方法无返回值、after 也不改写结果」的钩子写得更省事：
     * 只需实现 {@code afterVoid(chain)}，不用每次记得 {@code return result}。
     */
    public abstract static class VoidHook implements XposedInterface.Hooker {
        protected abstract void afterVoid(XposedInterface.Chain chain) throws Throwable;

        @Override
        public final Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            afterVoid(chain);
            return result;
        }
    }

    // ======================================================================
    // 挂钩入口（替代 XposedBridge.hookMethod / hookAllMethods）
    // ======================================================================

    /**
     * 钩一个已解析的 {@link Executable}（替代 {@code XposedBridge.hookMethod}）。
     *
     * 实现要点：**不使用全局 api 实例**，而是从 {@code method.getDeclaringClass()}
     * 解析框架的 XposedInterface（见 {@link #findXposedInterface(Class)}）。
     * 理由见 {@link #sHookRegistry} 的长注释 —— XposedModule 本身不能当 API 用。
     *
     * @param hooker 一般传 {@link SimpleHook} / {@link VoidHook} 的匿名子类
     * @return 钩子句柄；失败时抛异常（调用方按旧代码习惯 try/catch 记日志）
     */
    public static XposedInterface.HookHandle hookMethod(Executable method, XposedInterface.Hooker hooker)
            throws Throwable {
        if (method == null) {
            throw new IllegalArgumentException("hookMethod: method is null");
        }
        XposedInterface api = findXposedInterface(method.getDeclaringClass());
        if (api == null) {
            throw new IllegalStateException("cannot resolve XposedInterface from "
                    + method.getDeclaringClass().getName() + " (framework not attached?)");
        }
        XposedInterface.HookHandle handle = api.hook(method).intercept(hooker);
        remember(method, api);
        return handle;
    }

    /**
     * 挂钩**所有同名重载**（替代 {@code XposedBridge.hookAllMethods} 的批量语义）。
     *
     * ⚠️ 与旧 API 的差异：旧实现返回 {@code Set<Unhook>}（代码里只取 {@code .size()}）；
     * 这里直接返回**成功挂钩的数量**，调用点原文是 {@code XposedBridge.hookAllMethods(...).size()}
     * → 迁移后为 {@code XposedCompat.hookAllMethods(...)}，特意保留数字语义以便日志里的
     * 「(N methods)」保持原样可比对。
     */
    public static int hookAllMethods(Class<?> cls, String name, XposedInterface.Hooker hooker) {
        int hooked = 0;
        for (Method m : findMethodsByName(cls, name)) {
            try {
                hookMethod(m, hooker);
                hooked++;
            } catch (Throwable t) {
                // 单个重载钩不上不影响其它重载（与旧 API 行为一致）
                log("[DLsiteSoundFloat] hookAllMethods " + cls.getName() + "." + name
                        + paramDesc(m.getParameterTypes()) + " failed: " + t);
            }
        }
        if (hooked == 0) {
            log("[DLsiteSoundFloat] hookAllMethods found no method: " + cls.getName() + "." + name);
        }
        return hooked;
    }

    /** 钩一个「名字 + 参数类型」确定的方法，失败返回 false 并记日志（替代 findAndHookMethod 的常用形态）。 */
    public static boolean hookMethod(Class<?> cls, String name, Class<?>[] parameterTypes,
                                    XposedInterface.Hooker hooker, String tag) {
        try {
            Method m = findMethodExact(cls, name, parameterTypes);
            hookMethod(m, hooker);
            return true;
        } catch (Throwable t) {
            log(tag + " hook " + (cls == null ? "null" : cls.getSimpleName()) + "." + name
                    + " failed: " + t.getMessage());
            return false;
        }
    }
}
