package com.sena.dlsitesoundfloat;

import android.content.Context;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.hook.ActivityButtonHook;
import com.sena.dlsitesoundfloat.window.FloatingWindowManager;
import com.sena.dlsitesoundfloat.hook.NetworkHook;
import com.sena.dlsitesoundfloat.hook.PlayerPositionHook;
import com.sena.dlsitesoundfloat.hook.PlayerSourceHook;
import com.sena.dlsitesoundfloat.hook.SubtitleViewHook;
import com.sena.dlsitesoundfloat.hook.StructureWatcher;
import com.sena.dlsitesoundfloat.util.NetLogFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DlsiteSoundSubtitleModule implements IXposedHookLoadPackage {
    private static final String TARGET_PKG = "jp.co.eisys.dlsitesound";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DLsiteSoundFloat] Module loaded for " + lpparam.packageName);
        // 版本标识：每次排查「功能怎么没生效」时，先看这行确认装的是不是最新 APK。
        // ⚠️ 保留版本号、只改括号描述会产生「同日同名包」，装机前务必核这一行。
        XposedBridge.log("[DLsiteSoundFloat] ==== BUILD 1.21.0 (launcher icon refined: r=300 rounded corners, smooth edges (no grey rim), outer shadow offset down+right so it sits clearly BELOW the icon [card-on-table drop shadow]; regenerated at mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi with proportional scaling; version stays 1.21.0 / code 12100) ====");
        XposedBridge.log("[DLsiteSoundFloat] build applicationId=" + BuildConfig.APPLICATION_ID
                + " versionName=" + BuildConfig.VERSION_NAME);

        Context systemCtx = null;
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentActivityThread");
            systemCtx = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Throwable t) {
            XposedBridge.log("[DLsiteSoundFloat] getSystemContext failed: " + t.getMessage());
        }

        SubtitleRepository repo = SubtitleRepository.getInstance();
        repo.init(systemCtx, lpparam.classLoader);

        NetworkHook.hook(lpparam.classLoader, repo);
        PlayerPositionHook.hook(lpparam.classLoader, repo);
        PlayerSourceHook.hook(lpparam.classLoader, repo);
        SubtitleViewHook.hook(lpparam.classLoader, repo);
        ActivityButtonHook.hook(lpparam.classLoader, repo);
        // v34：钩住宿主视图树的结构事件（挂载/卸载/显隐/转场），把按钮显隐从「600ms 轮询」
        // 改成「宿主一动就扫」。最后一个装 —— 它依赖 ActivityButtonHook 的静态状态。
        StructureWatcher.hook(lpparam.classLoader);

        // 悬浮窗与目标 App 同进程，注册观察者后由 FloatingWindowManager 自动对齐显隐与内容
        repo.addObserver(() ->
                FloatingWindowManager.getInstance().sync(SubtitleRepository.getInstance().getAppContext()));

        hookApplicationAttach(lpparam.classLoader, repo);
    }

    private void hookApplicationAttach(ClassLoader cl, SubtitleRepository repo) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context appCtx = (Context) param.args[0];
                    repo.setAppContext(appCtx);
                    // 此刻上下文才真正可用：初始化网络诊断日志并打印落盘路径
                    NetLogFile.init(appCtx);
                    XposedBridge.log("[DLsiteSoundFloat] Application attached, context ready");
                    XposedBridge.log("[DLsiteSoundFloat] net log path -> " + NetLogFile.getPath());
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[DLsiteSoundFloat] hookApplicationAttach failed: " + t.getMessage());
        }
    }
}
