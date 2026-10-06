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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 【2.1.4 新增】**按签名形状**定位宿主类/方法 —— 抗 R8 混淆。
 *
 * <h3>为什么需要它</h3>
 * 宿主 DLsiteSound 2.20.2 起启用 R8 混淆（离线核实：全包 16052 个类里 {@code okhttp3.*}
 * 只剩 1 个 {@code PublicSuffixDatabase}，其余全改成 {@code Lvk/*} 之类短名）。
 * 于是本模块原先「按类名 + 方法名」硬编码的三个采集点同时失效：
 * <ul>
 *   <li>{@code okhttp3.Response$Builder} / {@code okhttp3.RealCall} —— 字幕 JSON 唯一来源</li>
 *   <li>{@code androidx.media3.exoplayer.ExoPlayerImpl} —— 播放进度</li>
 *   <li>{@code expo.modules.audio.AudioPlaylist.emitTrackChanged} 等 —— 换轨信号</li>
 * </ul>
 *
 * <p><b>硬编码混淆名同样不可靠</b>：R8 每次宿主发版都会重新生成映射表，
 * 写死 {@code Lvk/H;} 下一版就又失效。因此本类改为**按「方法的参数/返回类型」反查**——
 * 而签名里的类型（{@code int} / {@code String} / {@code ResponseBody}）在混淆前后
 * <b>结构不变</b>，只有名字变短。
 *
 * <h3>为什么锚点选「RN / expo」而不是「okhttp」</h3>
 * R8 会保留被<b>反射或 JS 侧按名引用</b>的类名。所以幸存的稳定锚点是那两个：
 * <ul>
 *   <li>{@code com.facebook.react.modules.network.NetworkingModule}
 *       —— 37 个方法名<b>全部保留</b>（RN 自己的 proguard 规则 protect 了它）。</li>
 *   <li>{@code expo.modules.audio.AudioPlayer} / {@code AudioPlaylist}
 *       —— expo 模块被 JS 按名 require，类名与<b>状态 Map 的键名</b>都保留。</li>
 * </ul>
 * 典型用法：{@code NetworkingModule.readWithProgress(int, String, X)} 的
 * <b>第 3 个参数类型 X 就是 ResponseBody</b>（混淆后是 {@code Lvk/H;}）——
 * 不需要知道它的名字，从签名里直接拿。
 *
 * <h3>用法要点</h3>
 * <ul>
 *   <li>所有查找都是<b>按形状</b>，名字只作为「首选尝试」，失败即自动回退形状匹配。</li>
 *   <li>形状匹配<b>带优先级</b>（见 {@link #matchMethod}）：先精确参数类型，
 *       再退到「参数个数 + 某一个参数的类型」。</li>
 *   <li>每个查找结果都<b>缓存</b>（按宿主类缓存），避免每次都全量反射扫描。</li>
 *   <li>所有方法<b>绝不抛异常</b>：宿主行为未知时返回 {@code null}，
 *       由调用方决定降级策略（钩子体绝不能把异常抛回宿主）。</li>
 * </ul>
 */
public final class Shape {
    private Shape() {
    }

    // ======================================================================
    //  方法查找
    // ======================================================================

    /**
     * 在 {@code cls} 及其父类里找一个<b>形状匹配</b>的方法。
     *
     * @param cls        宿主类
     * @param nameHint   方法名提示（混淆后可能失效，仅作为「先试同名」的首选）
     * @param paramTypes 期望的参数类型；<b>允许尾部为 {@code null}</b> 表示「该位不限定」
     * @param retType    期望的返回类型，{@code null} 表示不限定
     * @return 命中的方法；找不到返回 {@code null}
     */
    public static Method findMethod(Class<?> cls, String nameHint, Class<?>[] paramTypes, Class<?> retType) {
        if (cls == null) {
            return null;
        }
        // ① 先试「同名 + 精确参数类型」（未混淆的老宿主走这条最快路径）
        if (nameHint != null) {
            try {
                return cls.getDeclaredMethod(nameHint, paramTypes);
            } catch (Throwable ignored) {
            }
        }
        // ② 退到形状匹配
        return matchMethod(cls, paramTypes, retType);
    }

    /**
     * 形状匹配：参数「逐位比对，{@code null} 位不限定」+ 返回类型匹配。
     *
     * <p>注意 {@code cls.getMethods()} 只返回 public 方法；宿主的方法可能是包私有
     * （R8 会把非 public 的降级），所以这里同时扫 {@code getDeclaredMethods()}。
     */
    public static Method matchMethod(Class<?> cls, Class<?>[] paramTypes, Class<?> retType) {
        if (cls == null) {
            return null;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] all;
            try {
                all = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            Method best = null;
            int bestScore = -1;
            for (Method m : all) {
                if (!paramsMatch(m.getParameterTypes(), paramTypes)) {
                    continue;
                }
                if (retType != null && !retType.equals(m.getReturnType())) {
                    continue;
                }
                // 打分：静态方法与 public 方法优先（更可能是对外入口）
                int score = 0;
                if (Modifier.isPublic(m.getModifiers())) {
                    score += 2;
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    score += 1;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            if (best != null) {
                try {
                    best.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return best;
            }
        }
        return null;
    }

    /** 参数逐位比对；{@code want} 里的 {@code null} 位表示「不限定」。长度必须一致。 */
    private static boolean paramsMatch(Class<?>[] actual, Class<?>[] want) {
        if (want == null || actual.length != want.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (want[i] == null) {
                continue;
            }
            if (!want[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 找一个「无参、返回某类型」的方法（最常用的一种形状）。
     * 若宿主有多个同形状方法，返回第一个 public/静态优先的。
     */
    public static Method findNoArgReturning(Class<?> cls, String nameHint, Class<?> retType) {
        return findMethod(cls, nameHint, new Class<?>[0], retType);
    }

    /**
     * 找一个「无参、返回可赋值给 {@code want} 的值」的方法。
     *
     * <p><b>为什么要这一条</b>：混淆后返回值类型本身可能被 R8 换成<b>父类或接口</b>
     * （实测 expo 的状态 Map 声明就是精确的 {@code java.util.Map}，但 R8 也可能给成
     * {@code Object}）。此时按名字找不到、按精确类型也找不到，但
     * 「无参 + 返回类型是 Map 的子类型/父类型」这条形状依然成立。
     */
    public static Method findNoArgAssignableTo(Class<?> cls, String nameHint, Class<?> want) {
        if (cls == null || want == null) {
            return null;
        }
        // 先试精确
        Method exact = findNoArgReturning(cls, nameHint, want);
        if (exact != null) {
            return exact;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] all;
            try {
                all = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            Method best = null;
            int bestScore = -1;
            for (Method m : all) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                Class<?> r = m.getReturnType();
                if (r == null || r == void.class) {
                    continue;
                }
                // 返回类型必须和 want 有继承关系（任一方向），才算「同一个东西」
                if (!r.isAssignableFrom(want) && !want.isAssignableFrom(r)) {
                    continue;
                }
                int score = 0;
                if (Modifier.isPublic(m.getModifiers())) {
                    score += 2;
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    score += 1;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            if (best != null) {
                try {
                    best.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return best;
            }
        }
        return null;
    }

    /**
     * 找一个「无参、返回类型<b>不是</b>排除表里任何一种」的方法。
     *
     * <p><b>🔴 为什么必须有这一条（2.1.4 实测踩到）</b>：
     * 混淆后的响应体类上，无参方法有 7 个，返回类型分别是
     * {@code InputStream} / {@code byte[]} / {@code void} / {@code long} /
     * {@code MediaType} / {@code BufferedSource} / {@code String}。
     * 用「无参 + 返回任意对象」去找 contentType，会选中<b>第一个</b>返回引用的方法 ——
     * 也就是 {@code byteStream()}。<b>调用它会消费掉宿主的响应流</b>，
     * 后果是宿主自己读不到 body（图片/字幕加载失败），属于严重回归。
     * ⇒ 所以必须用<b>排除表</b>把「危险的那几个返回类型」显式排掉。
     *
     * @param excludedRetTypes 排除的返回类型全名（内部形态），按 {@code Class#getName}
     *                        与内部形态两种写法都比对
     */
    public static Method findNoArgExcluding(Class<?> cls, String nameHint,
                                            Class<?>[] excludedRetTypes) {
        if (cls == null) {
            return null;
        }
        java.util.Set<String> banned = new java.util.HashSet<>();
        for (Class<?> c : excludedRetTypes) {
            if (c == null) {
                continue;
            }
            banned.add(c.getName());
            banned.add(c.getCanonicalName());
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] all;
            try {
                all = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            Method best = null;
            int bestScore = -1;
            for (Method m : all) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                Class<?> r = m.getReturnType();
                if (r == null || r == void.class) {
                    continue;
                }
                // 🔴 只接受**引用类型**。contentType 返回的是一个对象（媒体类型），
                //    而 contentLength 返回 long 这个**基本类型** —— 不排掉的话，
                //    「无参 + 返回值不在排除表」会选中 contentLength（它排在前面）。
                if (r.isPrimitive()) {
                    continue;
                }
                if (banned.contains(r.getName()) || banned.contains(r.getCanonicalName())) {
                    continue;
                }
                int score = 0;
                if (Modifier.isPublic(m.getModifiers())) {
                    score += 2;
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    score += 1;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            if (best != null) {
                try {
                    best.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return best;
            }
        }
        return null;
    }

    // ======================================================================
    //  字段 / 值读取
    // ======================================================================

    /**
     * 列出一个类**自身声明**的字段类型（含私有），按声明顺序。
     *
     * <p>用途：宿主某类的「关键对象」藏在字段里（字段名被 R8 混淆、但<b>类型形状</b>没变）。
     * 典型：expo-fetch 的响应类里有个字段，其类型有一个 {@code (boolean)->ByteBuffer} 的
     * 方法（正文累加器）——字段名无从得知，但按「字段类型的形状」能反查出来。
     */
    public static Class<?>[] declaredFieldTypes(Class<?> cls) {
        if (cls == null) {
            return new Class<?>[0];
        }
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fs) {
                out.add(f.getType());
            }
        }
        return out.toArray(new Class<?>[0]);
    }

    /**
     * 读一个「无参、有返回值」的方法并转成 {@link Map} 之外的通用对象。
     * 纯工具：任何异常都返回 {@code null}。
     */
    public static Object callNoArg(Object target, Method m) {
        if (target == null || m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读一个 Map 里的值并做类型归一：{@code Integer}/{@code Long}/{@code Double}/{@code Float}
     * 统一转成 {@code double}；{@code Boolean} 单独取。
     *
     * @return 数值型返回 {@link Double}；布尔型返回 {@link Boolean}；否则 {@code null}
     */
    public static Object normalize(Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean || v instanceof String) {
            return v;
        }
        return null;
    }

    /**
     * 把字段值 {@code v} 当作「秒」转成毫秒；不是数值则返回 -1。
     *
     * <p>⚠️ 必须防 {@code NaN}/{@code 负值}/{@code 超大值}：宿主播放器在「未开始/已结束」
     * 状态下可能给出 {@code -1} 或 {@code NaN}，直接转 long 会得到一个巨大的正数
     * ⇒ 字幕索引跳到末尾。
     */
    public static long secondsToMillisOrMinus1(Object v) {
        if (!(v instanceof Number)) {
            return -1L;
        }
        double sec = ((Number) v).doubleValue();
        if (Double.isNaN(sec) || Double.isInfinite(sec) || sec < 0 || sec > 86400 * 7) {
            return -1L;
        }
        return (long) (sec * 1000.0);
    }

    // ======================================================================
    //  诊断
    // ======================================================================

    /**
     * 把某个类的公开形状打成一行短描述，进日志。
     *
     * <p>用途：真机若换了混淆布局，我们能直接从日志看出「当前形状长什么样」，
     * 而不必再抓一次宿主包离线分析。
     */
    public static String describe(Class<?> cls, int maxMethods) {
        if (cls == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getName()).append(" {");
        List<String> parts = new ArrayList<>();
        try {
            Method[] all = cls.getDeclaredMethods();
            int n = 0;
            for (Method m : all) {
                if (n++ >= maxMethods) {
                    parts.add("...");
                    break;
                }
                StringBuilder p = new StringBuilder();
                p.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        p.append(',');
                    }
                    p.append(ps[i].getSimpleName());
                }
                p.append(")->").append(m.getReturnType().getSimpleName());
                parts.add(p.toString());
            }
        } catch (Throwable ignored) {
            parts.add("(scan failed)");
        }
        sb.append(String.join(", ", parts));
        sb.append('}');
        return sb.toString();
    }

    /** 列出一个类里所有「无参且返回值类型唯一」的可疑采集点，进诊断日志。 */
    public static String describeNoArgReturnTypes(Class<?> cls, int maxMethods) {
        if (cls == null) {
            return "(null)";
        }
        List<String> parts = new ArrayList<>();
        try {
            Method[] all = cls.getDeclaredMethods();
            int n = 0;
            for (Method m : all) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                if (n++ >= maxMethods) {
                    parts.add("...");
                    break;
                }
                parts.add(m.getName() + "->" + m.getReturnType().getSimpleName());
            }
        } catch (Throwable ignored) {
            parts.add("(scan failed)");
        }
        return cls.getName() + " {no-arg: " + String.join(", ", parts) + "}";
    }

    /** 按名字读一个字段（含父类）；找不到返回 {@code null}。 */
    public static Object readField(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 找**第一个类型精确等于 {@code type}** 的字段（含父类），并设为可读。
     *
     * <p>R8 会把字段名混淆掉，但**字段类型不会变**（改类型会破坏 JVM 语义）。
     * 所以「按类型找字段」比「按名字找字段」稳得多 ——
     * 典型用途：{@code Response.body}（类型 = 响应体类）、
     * {@code Response.request}（类型 = 请求类）。
     */
    public static Field findFieldOfType(Class<?> cls, Class<?> type) {
        if (cls == null || type == null) {
            return null;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fs) {
                if (f.getType() != type) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return f;
            }
        }
        return null;
    }

    /** 读一个字段值；任何异常都返回 {@code null}（字段可能是 final / 静态）。 */
    public static Object readFieldValue(Field f, Object target) {
        if (f == null || target == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 有「无参且返回类型**精确等于** {@code ret}」的方法吗？（只判存在，不取方法）
     */
    public static boolean hasNoArgReturning(Class<?> cls, Class<?> ret) {
        return findNoArgReturningExact(cls, ret) != null;
    }

    /** 找「无参且返回类型精确等于 {@code ret}」的方法（含父类）。 */
    public static Method findNoArgReturningExact(Class<?> cls, Class<?> ret) {
        if (cls == null || ret == null) {
            return null;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] all;
            try {
                all = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : all) {
                if (m.getParameterCount() == 0 && m.getReturnType() == ret) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    return m;
                }
            }
        }
        return null;
    }

    /** 有没有 {@code close()} 这个方法？（用来区分「响应对象」和「构建器对象」） */
    public static boolean hasCloseMethod(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("close");
                return true;
            } catch (Throwable ignored) {
            }
            for (Class<?> itf : safeInterfaces(c)) {
                try {
                    itf.getDeclaredMethod("close");
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static Class<?>[] safeInterfaces(Class<?> c) {
        try {
            return c.getInterfaces();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    /** 列出实例字段（含父类，排除静态）。 */
    public static Field[] instanceFields(Class<?> cls) {
        if (cls == null) {
            return new Field[0];
        }
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                }
                out.add(f);
            }
        }
        return out.toArray(new Field[0]);
    }

    /**
     * 找一个「参数个数 = {@code n}、返回 boolean」的方法（含父类）。
     *
     * <p>用途：okio 的 {@code request(long): boolean}（把数据拉进内部缓冲区，<b>不消费</b>）。
     */
    public static Method findMethodByArityReturning(Class<?> cls, int n, Class<?> ret) {
        if (cls == null) {
            return null;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] all;
            try {
                all = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : all) {
                if (m.getParameterCount() == n && m.getReturnType() == ret) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    return m;
                }
            }
        }
        return null;
    }
}
