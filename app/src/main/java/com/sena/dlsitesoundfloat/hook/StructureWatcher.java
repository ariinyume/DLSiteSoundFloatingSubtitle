package com.sena.dlsitesoundfloat.hook;

import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 宿主视图树「结构事件」监听（v34 新增）—— 让开关按钮的显隐做到准零延迟。
 *
 * ── 为什么要这个东西 ──
 * 按钮延迟的真正来源是**检测节奏**，不是位置（见类头 v30/v31 段）：
 * 稳定态心跳 600ms，而 RN 的页面转场由 translate / opacity 驱动，
 * **根本不触发布局回调**（{@code setTranslationX()} 不会 requestLayout），
 * 于是「页面已经上来了」这件事最长要等 600ms 才被发现。
 *
 * 心跳是「问」；这里改成「等宿主告诉我们」—— RN 挂载 / 卸载页面必然会动视图树，
 * 只要钩住那几个**结构变更入口**，就能在第一帧立刻触发一次扫描。
 *
 * ── 钩了哪些（都是 ViewGroup / View 自身声明的方法，稳定、无混淆风险）──
 *   · {@code ViewGroup.addView(View, int)}        —— 页面（子树）挂载
 *   · {@code ViewGroup.removeView(View)}          —— 页面卸载
 *   · {@code ViewGroup.removeViewAt(int)}         —— 页面卸载（按下标）
 *   · {@code ViewGroup.removeAllViews()}          —— 整页清空
 *   · {@code View.setVisibility(int)}             —— RN 的 display:none / 复用页面时的显隐切换
 *   · {@code View.setTranslationX/Y(float)}       —— RN 转场的位移驱动（← 最关键的一条）
 *   · {@code View.setAlpha(float)}                —— RN 转场的淡入淡出驱动
 *
 * ── 为什么不直接把按钮「注入」到宿主控件行里 ──
 * 试过、也 dump 过真实视图树（1.20.9），结论是**不可行**：
 * RN 的 {@code ReactViewGroup} 在 {@code onInterceptTouchEvent()} 里**无条件拦截**触摸
 * （它自己用 JSTouchDispatcher 重新做命中测试，只认带 React tag 的视图）。
 * 任何我们塞进去的原生 View 都**收不到点击**，按钮会变成纯装饰。
 * 所以按钮继续留在 DecorView（可点击；拖动归悬浮窗），只把「什么时候显示」交给宿主事件驱动。
 *
 * ── 成本控制 ──
 * 每个钩子体只有一次方法调用（{@link ActivityButtonHook#pokeStructureChanged}），
 * 里面先做 {@code sActivity == null} 与「距上次触发不足 50ms」两道早退，
 * 真正昂贵的整树扫描被合并、限流（见 {@code POKE_MIN_INTERVAL_MS}）。
 *
 * ── v35：同一个入口还负责「按钮跟手」──
 * {@code pokeStructureChanged} 里在合并闸之后会顺手读**一次**参考点坐标
 * （见 {@code maybeStartPageFollow()}）：页面确实在动就拉起一个每帧同步的
 * Choreographer 循环，让按钮跟着播放页一起上下走。所以钩住 {@code setTranslationY}
 * 不只是为了显隐提速，也是「跟手」的启动信号。
 */
public class StructureWatcher {
    private static final String TAG = "[DLsiteSoundFloat:Watcher]";

    private static int sHookedCount = 0;

    public static void hook(ClassLoader cl) {
        // 页面挂载 / 卸载
        hookViewGroup("addView", View.class, int.class);
        hookViewGroup("removeView", View.class);
        hookViewGroup("removeViewAt", int.class);
        hookViewGroup("removeAllViews");

        // 显隐 / 转场驱动
        hookView("setVisibility", int.class);
        hookView("setTranslationX", float.class);
        hookView("setTranslationY", float.class);
        hookView("setAlpha", float.class);

        XposedBridge.log(TAG + " structure watcher ready (" + sHookedCount + " hooks)"
                + " -> instant scan on host view-tree changes");
    }

    private static void hookViewGroup(String name, Class<?>... params) {
        hookOn(ViewGroup.class, name, name, params);
    }

    private static void hookView(String name, Class<?>... params) {
        hookOn(View.class, name, name, params);
    }

    private static void hookOn(Class<?> owner, final String method, final String reason,
                               Class<?>... params) {
        try {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        ActivityButtonHook.pokeStructureChanged(reason);
                    } catch (Throwable ignored) {
                        // 钩子体绝不能把异常抛回宿主
                    }
                }
            };
            // findAndHookMethod 的 varargs 约定：参数类型… + **回调放最后**。
            Object[] typesAndCallback = new Object[params.length + 1];
            System.arraycopy(params, 0, typesAndCallback, 0, params.length);
            typesAndCallback[params.length] = callback;
            XposedHelpers.findAndHookMethod(owner, method, typesAndCallback);
            sHookedCount++;
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook " + owner.getSimpleName() + "." + method
                    + " failed: " + e.getMessage());
        }
    }
}
