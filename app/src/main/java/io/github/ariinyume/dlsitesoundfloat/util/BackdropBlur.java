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

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 【2.2.12】「真·背后模糊」—— 直接让**合成器**把本图层背后的内容糊掉。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 这是整个液态玻璃系列**最关键的突破**，真机已验收：模糊出来了，而且可调。
 *
 * 它的原理与前面所有失败尝试的区别只有一句话：
 *   **不碰窗口标志（FLAG_BLUR_BEHIND），自己把模糊设到自己的图层上。**
 *
 * 为什么这样就成立、而窗口标志那条不行（真机取证结论，见
 * {@code ColorOS17_液态玻璃_悬浮窗可行性分析.md} §7）：
 *   · 带 {@code FLAG_BLUR_BEHIND} 时，模糊由**系统窗口管理器**发起 —— 这台机上它会
 *     交给厂商的模糊服务按**整屏画面**处理，于是整屏糊（含桌面与其他 App）；
 *   · 而且 App 侧就算再把模糊区域显式设成窗口矩形（{@code setBlurRegions}）也没用：
 *     真机实测「调用成功、apply 成功、现象零变化」⇒ **区域不是病根，发起者才是**。
 *   · 本类是另一条：{@code SurfaceControl.Transaction} 上的
 *       {@code setBackgroundBlurRadius(图层, 半径)} + {@code setBlurRegions(图层, {{l,t,r,b,圆角}})}
 *     直接作用在**我们自己那个尺寸就等于面板的图层**上，完全不经过窗口管理器。
 *     真机结果：面板背后出现真实模糊，其他区域不受影响，**且半径可随滑条实时变化**。
 *
 * 这条路的收益是决定性的（全部白拿）：
 *   · **天生全局** —— 底下是桌面、别的 App 都糊得到（不再受"只能取宿主窗口画面"限制）；
 *   · **零权限** —— 不需要录屏授权、没有常驻通知、不进别的进程；
 *   · **零额外功耗** —— 模糊由合成器（GPU/DPU）做，没有回读、没有额外合成；
 *   · **零延迟** —— 不是"每 250ms 采一帧"，而是合成时就地糊，跟手。
 *
 * ⚠️ 边界与兜底：
 *   · 全部走反射，任何一步失败都只返回 false（面板退回静态玻璃，绝不影响窗口）；
 *   · {@code setBlurRegions} 的第 5 个元素是**圆角半径** —— 这正是"面板是圆角、
 *     糊出来的背景却是直角"那个问题的解：把面板圆角一并交给它，模糊区域才是圆的；
 *   · 尺寸/圆角变化（缩放窗口、改配置）后必须**重设一次**，见 {@code FloatingSubtitleView}。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class BackdropBlur {

    private static final String TAG = "[DLsiteSoundFloat:BackdropBlur]";

    /** 记一次"已上报过的状态"，避免每次尺寸变化都刷日志（只在**变化**时打）。 */
    private static String sLastLogged = "";
    /** 隐藏 API 清单只探一次（诊断与实现都靠它，见 {@link #probeOnce()}）。 */
    private static boolean sProbed = false;

    private BackdropBlur() {
    }

    /**
     * 把「玻璃背后」的模糊交给合成器。
     *
     * @param v              面板视图（取其所属 ViewRootImpl 的 SurfaceControl）
     * @param offX/offY      玻璃矩形在**窗口图层内**的偏移（px）。【2.2.13】窗口比玻璃大一圈
     *                       阴影环（见 FloatingSubtitleView#GLASS_SHADOW_INSET_DP），
     *                       模糊区域必须跟着玻璃走，否则阴影环也会被糊进去。
     * @param w/h            玻璃尺寸（px，图层局部坐标）
     * @param radiusPx       模糊半径（px），0 = 关闭模糊
     * @param cornerRadiusPx 玻璃圆角（px）—— 让模糊区域与玻璃的圆角对齐，消除四个直角
     * @return true = 已成功下发（≠ 一定肉眼可见，但真机已验证可见）
     */
    public static boolean apply(View v, int offX, int offY, int w, int h,
                                int radiusPx, int cornerRadiusPx) {
        if (v == null || w <= 0 || h <= 0) {
            return false;
        }
        final Object sc = findSurfaceControl(findViewRootImpl(v));
        if (sc == null) {
            logOnce("no-sc", "拿不到本窗口的 SurfaceControl，无法申请背景模糊");
            return false;
        }
        probeOnce();
        final int radius = Math.max(0, radiusPx);
        // 半径 0 也要下发：用户把滑条拉到 0 时应当真的不糊（而不是保留上一次的半径）。
        boolean okRadius = txCall("setBackgroundBlurRadius", new Class<?>[]{int.class},
                new Object[]{radius}, sc);
        boolean okRegion = txCall("setBlurRegions", new Class<?>[]{float[][].class},
                new Object[]{new float[][]{{(float) offX, (float) offY,
                        (float) (offX + w), (float) (offY + h),
                        Math.max(0f, (float) cornerRadiusPx)}}}, sc);
        // 【2.2.12b】圆角：真机证明 **region 里那个圆角元素被本 ROM 忽略了**
        //   （下发了 83px 圆角，四角照样是直角模糊）。所以再走一条路：
        //   **在图层上直接设圆角** —— SF 的图层圆角会把"该图层的一切合成结果"（含它背后的
        //   背景模糊）一起裁成圆角，正是我们要的效果。取不到就当无事发生（不会更差）。
        boolean okCorner = cornerRadiusPx > 0
                && txCall("setCornerRadius", new Class<?>[]{float.class},
                new Object[]{(float) cornerRadiusPx}, sc);
        logOnce("st" + radius + "/" + w + "x" + h + "/r" + cornerRadiusPx
                        + "/" + okRadius + okRegion + okCorner,
                "背景模糊下发: 半径=" + radius + "px 区域=" + w + "x" + h
                        + " 圆角=" + cornerRadiusPx + "px"
                        + " (radius=" + okRadius + ", region=" + okRegion
                        + ", corner=" + okCorner + ")");
        return okRadius || okRegion;
    }

    /**
     * 【2.2.12b】一次性把本机 {@code SurfaceControl$Transaction} 上**与模糊/圆角相关**的
     * 隐藏方法名全部打出来。
     *
     * 为什么要做：本轮的圆角问题就是"以为 region 的圆角元素会生效、结果被 ROM 忽略"。
     * 与其反复猜方法名与语义，不如一次把可用的 API 列全 —— 日志里看到清单，
     * 下次就能直接挑对的那一个，不必再靠试错。
     */
    private static void probeOnce() {
        if (sProbed) {
            return;
        }
        sProbed = true;
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Method m : txCls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                String name = m.getName().toLowerCase();
                if (name.contains("blur") || name.contains("corner") || name.contains("round")) {
                    if (n++ > 0) {
                        sb.append(" | ");
                    }
                    sb.append(m.getName()).append('/').append(m.getParameterCount());
                }
            }
            LogGate.debug(TAG, " Transaction 可用(模糊/圆角相关): "
                    + (sb.length() == 0 ? "(无)" : sb));
        } catch (Throwable t) {
            XposedCompat.log(TAG + " probe failed: " + t);
        }
    }

    // ==================================================================
    // 反射基础件
    // ==================================================================

    /** 找本视图所属的 {@code ViewRootImpl}（@hide：{@code View#getViewRootImpl()}，失败则读 mAttachInfo）。 */
    private static Object findViewRootImpl(View v) {
        try {
            Method m = View.class.getDeclaredMethod("getViewRootImpl");
            m.setAccessible(true);
            Object r = m.invoke(v);
            if (r != null) {
                return r;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field ai = View.class.getDeclaredField("mAttachInfo");
            ai.setAccessible(true);
            Object attach = ai.get(v);
            if (attach != null) {
                Field vri = attach.getClass().getDeclaredField("mViewRootImpl");
                vri.setAccessible(true);
                return vri.get(attach);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 拿窗口的 SurfaceControl（{@code ViewRootImpl.mSurfaceControl}）。 */
    private static Object findSurfaceControl(Object viewRootImpl) {
        if (viewRootImpl == null) {
            return null;
        }
        for (String n : new String[]{"mSurfaceControl", "mSurface", "mBlastSurfaceControl"}) {
            try {
                Field f = viewRootImpl.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object sc = f.get(viewRootImpl);
                if (sc != null && sc.getClass().getName().contains("SurfaceControl")) {
                    return sc;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 在 {@code SurfaceControl$Transaction} 上按「名字 + 参数类型」精确调用一次并 apply。
     *
     * 每次都新建一个 Transaction 并立即 apply：模糊是**当前状态**而不是一次性动作，
     * 但沿用同一套下发口径最简单也最稳（真机已验证连续下发不会累积副作用）。
     */
    private static boolean txCall(String name, Class<?>[] argTypes, Object[] args, Object sc) {
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?>[] full = new Class<?>[argTypes.length + 1];
            full[0] = scCls;
            System.arraycopy(argTypes, 0, full, 1, argTypes.length);
            Method m = txCls.getDeclaredMethod(name, full);
            m.setAccessible(true);
            Object tx = txCls.getDeclaredConstructor().newInstance();
            Object[] fullArgs = new Object[args.length + 1];
            fullArgs[0] = sc;
            System.arraycopy(args, 0, fullArgs, 1, args.length);
            m.invoke(tx, fullArgs);
            Method apply = txCls.getDeclaredMethod("apply");
            apply.setAccessible(true);
            apply.invoke(tx);
            return true;
        } catch (Throwable t) {
            logOnce("fail-" + name + "-" + t.getClass().getSimpleName(),
                    name + " 下发失败: " + t.getClass().getSimpleName() + "/" + t.getMessage());
            return false;
        }
    }

    /** 同一条状态只打一次日志（尺寸每次缩放都在变，全打会刷屏）。 */
    private static void logOnce(String key, String msg) {
        if (key.equals(sLastLogged)) {
            return;
        }
        sLastLogged = key;
        LogGate.debug(TAG, msg);
    }
}
