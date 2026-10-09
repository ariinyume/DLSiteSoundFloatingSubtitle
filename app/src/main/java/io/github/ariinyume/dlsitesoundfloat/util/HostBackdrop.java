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

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 【2.2.12】宿主画面**统计**服务 —— 只回答两个问题：面板背后**多亮**、**什么颜色**。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ⚠️ 本类的定位在这一版发生了**根本变化**，先说清楚，否则很容易误读：
 *
 * 2.2.11 时它是"自己把背景采回来并模糊"（因为系统模糊走不通）；
 * 2.2.12 起**真实背景模糊已经由合成器完成**（见 {@code util/BackdropBlur}：直接对本图层
 * 设模糊半径与区域，零权限、零功耗、天生全局）。于是"自己采一帧再糊一遍"这件事**整个不再需要**，
 * 本类随之收窄成两个**统计量**的唯一来源：
 *
 *   ① **均值亮度** —— 面板的自适应霜面靠它把面板亮度压到"白字一定读得清"的区间；
 *      没有它，亮背景上白字会糊掉（这就是当初要做自适应霜面的原因）。
 *   ② **上/下缘平均色** —— 「边缘光圈的颜色随实时背景走」靠它（见
 *      {@code LiquidGlassDrawable#tintByEdge}）。
 *
 * 因此本轮把**模糊整段删掉**（省掉三遍盒式模糊与两块像素缓冲），周期也从 250ms 放宽到
 * {@link #PERIOD_MS} —— 统计量变化很慢，不需要更高的采样率。
 *
 * ── 为什么仍然只能"在目标 App 内"有效 ──────────────────────────────
 * 取像源是**宿主 Activity 自己的窗口**（同一进程、不需要权限）。浮窗移到桌面/别的 App 上时
 * 取不到画面 ⇒ 统计量未知 ⇒ 面板退回**静态填充**（自己给出足够底色保证可读）。
 * 注意：**模糊本身仍然照常工作**（那是合成器按图层做的，与取像无关），
 * 所以"全局"这件事不受本类影响。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class HostBackdrop {

    /** 采集目标：由窗口层提供「面板当前的屏幕矩形」。返回 false = 此刻不该采。 */
    public interface Target {
        boolean getScreenRect(Rect out);
    }

    /**
     * 统计结果回调（**主线程**）。
     *
     * @param meanLum    该区域均值亮度（0..1）
     * @param edgeTop    上缘平均色（0xFFRRGGBB）
     * @param edgeBottom 下缘平均色（0xFFRRGGBB）
     */
    public interface Listener {
        void onStats(float meanLum, int edgeTop, int edgeBottom);
    }

    private static final String TAG = "[DLsiteSoundFloat:Backdrop]";

    /**
     * 采样周期（ms）。只驱动"自适应霜面 + 光圈染色"两个慢变量，不需要高频：
     * 采得越勤只会更耗电，画面上看不出区别。
     */
    private static final int PERIOD_MS = 400;
    /**
     * 【989】「留底」模式的采样周期（ms）—— **液态玻璃没开时**也低频采一点。
     *
     * 动因（Ari 2026-10-09 提议，原话「即使用户未保存使用，也保存少量采样数据留底用于
     * 下次开启功能，这样做会不会开销小一点」）：普通悬浮窗 → 液态玻璃的切换时刻，
     * 用户一定停在设置页里（宿主在后台），采样被软跳过 —— 于是新玻璃"没有任何统计"，
     * 只能退回不透明深色底（Ari 看到的"先变成黑色"）。要消除它，必须有一份**当前进程
     * 里采到过的**基线；而基线只能在"宿主可见"时采。
     *
     * 代价口径：留底只在**悬浮窗显示期间**跑，且周期是正常档的 1/5（400ms → 2000ms），
     * 采的还是同一个 1/10 缩小的小块 ⇒ 相对上一版（关掉开关=零采样）多出的开销约为
     * "液态玻璃开启时"的 1/5，与"每次开启都要黑一下/要拖一下"相比是划算的。
     * 关掉悬浮窗即刻 {@link #stop()}，不留常驻。
     */
    private static final int PASSIVE_PERIOD_MS = 2000;
    /** 【989】当前采样周期（默认 = 正常档；"留底"时切到 {@link #PASSIVE_PERIOD_MS}）。 */
    private volatile int periodMs = PERIOD_MS;
    /** 采集缩小倍数（只做统计，不需要细节）。 */
    private static final int DOWNSCALE = 10;
    /** 边缘取样的带宽（缩放后像素）。 */
    private static final int EDGE_BAND = 3;
    private static final int MAX_CONSECUTIVE_FAILS = 3;
    private static final long LOG_THROTTLE_MS = 4000L;

    private static final HostBackdrop INSTANCE = new HostBackdrop();

    public static HostBackdrop get() {
        return INSTANCE;
    }

    /** 主线程 Handler：只跑 tick 与回调。 */
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 工作线程：PixelCopy 回调与统计都在这里跑（不占主线程）。 */
    private HandlerThread worker;
    private Handler workerHandler;

    private WeakReference<Activity> activityRef;
    private Target target;
    private Listener listener;

    private boolean running;
    private boolean paused;
    private boolean inFlight;
    private boolean gaveUp;
    private int failCount;
    private int successCount;
    private long lastLogMs;
    private long inFlightSince;

    /** 复用的目标位图与像素缓冲（只在工作线程触碰）。 */
    private Bitmap dest;
    private int[] px;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!paused) {
                try {
                    captureOnce();
                } catch (Throwable t) {
                    noteFail("tick threw: " + t);
                }
            }
            if (running) {
                main.postDelayed(this, periodMs);
            }
        }
    };

    private HostBackdrop() {
    }

    // ==================================================================
    // 外部控制
    // ==================================================================

    /** 由 Activity 生命周期钩子喂进来（见 ActivityButtonHook）。传 null = 宿主已离开。 */
    public void setActivity(Activity activity) {
        activityRef = activity == null ? null : new WeakReference<>(activity);
        if (activity != null) {
            gaveUp = false;
            failCount = 0;
        }
    }

    /** 拖动/缩放期间暂停采样：那会儿用户要的是跟手，而背后画面本来就在位移。 */
    public void setPaused(boolean p) {
        if (paused == p) {
            return;
        }
        paused = p;
        if (!paused) {
            requestNow();
        }
    }

    /** 立即补一次采样（重排 tick）。 */
    public void requestNow() {
        if (!running) {
            return;
        }
        main.removeCallbacks(tick);
        main.post(tick);
    }

    /** 开始采样（幂等）。 */
    public void start(Target t, Listener l) {
        target = t;
        listener = l;
        if (running) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            gaveUp = true;
            XposedCompat.log(TAG + " disabled: SDK_INT=" + Build.VERSION.SDK_INT + " < 26");
            return;
        }
        running = true;
        failCount = 0;
        ensureWorker();
        main.removeCallbacks(tick);
        main.post(tick);
        XposedCompat.log(TAG + " stats sampling started (period=" + periodMs
                + "ms downscale=1/" + DOWNSCALE + ")");
    }

    /**
     * 【989】切「留底」模式 —— 液态玻璃**没开**时也在悬浮窗显示期间低频采一点，
     * 给"下次开启"留一份可直接打底的基线（设计缘由见 {@link #PASSIVE_PERIOD_MS}）。
     *
     * 幂等；切档时把排队中的那拍重排，立刻按新周期走（避免切档后还等 2s 才生效）。
     */
    public void setPassive(boolean passive) {
        final int want = passive ? PASSIVE_PERIOD_MS : PERIOD_MS;
        if (periodMs == want) {
            return;
        }
        periodMs = want;
        LogGate.debug(TAG, " sampling period -> " + want + "ms"
                + (passive ? " (留底基线：液态玻璃未开)" : " (液态玻璃已开)"));
        if (running) {
            main.removeCallbacks(tick);
            main.post(tick);
        }
    }

    /** 停止采样（窗口隐藏 / 视图摘除 / 关掉液态玻璃）。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        inFlight = false;
        main.removeCallbacks(tick);
        XposedCompat.log(TAG + " stats sampling stopped (ok=" + successCount + " fail=" + failCount + ")");
    }

    /** 工作线程按需创建、不主动退出（空闲时不耗 CPU）。 */
    private void ensureWorker() {
        if (workerHandler != null) {
            return;
        }
        worker = new HandlerThread("DLsiteFloat-backdrop");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
    }

    // ==================================================================
    // 采样（主线程：只读几何 + 派发）
    // ==================================================================

    private void captureOnce() {
        if (gaveUp) {
            return;
        }
        if (inFlight) {
            // 看门狗：回调理论上一定会来；万一被吞掉，采集会**静默停摆**且无日志。
            if (SystemClock.uptimeMillis() - inFlightSince > 1500L) {
                inFlight = false;
                LogGate.debug(TAG, " in-flight watchdog fired (>1.5s) -> retry");
            } else {
                return;
            }
        }

        // 这些「当前没有可采的目标」都是正常时序（宿主退后台、页面转场、窗口没显示），
        // **不算失败** —— 计进失败数会把一次临时缺目标永久升级成"放弃"。
        Activity activity = activityRef == null ? null : activityRef.get();
        if (activity == null || activity.isFinishing()) {
            skipSoft("no host activity");
            return;
        }
        Window win = activity.getWindow();
        if (win == null) {
            skipSoft("no window");
            return;
        }
        View decor = win.getDecorView();
        if (decor == null) {
            skipSoft("no decor view");
            return;
        }
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0 || !decor.isShown()) {
            return;
        }

        Rect want = new Rect();
        if (target == null || !target.getScreenRect(want)
                || want.width() <= 0 || want.height() <= 0) {
            return;
        }

        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        Rect src = new Rect(want);
        src.offset(-loc[0], -loc[1]);
        if (!src.intersect(0, 0, decor.getWidth(), decor.getHeight()) || src.isEmpty()) {
            return;
        }

        final int dw = Math.max(1, src.width() / DOWNSCALE);
        final int dh = Math.max(1, src.height() / DOWNSCALE);

        ensureWorker();
        if (workerHandler == null) {
            skipSoft("no worker");
            return;
        }

        inFlight = true;
        inFlightSince = SystemClock.uptimeMillis();
        workerHandler.post(() -> doCopy(win, src, dw, dh));
    }

    private void doCopy(Window win, Rect src, int dw, int dh) {
        Bitmap bmp;
        try {
            bmp = ensureDest(dw, dh);
        } catch (Throwable t) {
            finishOnMain(null, 0f, 0, 0, "alloc dest failed: " + t);
            return;
        }
        try {
            PixelCopy.request(win, src, bmp, result -> {
                if (result != PixelCopy.SUCCESS) {
                    finishOnMain(null, 0f, 0, 0, "PixelCopy result=" + result);
                    return;
                }
                try {
                    finishStats(bmp, dw, dh);
                } catch (Throwable t) {
                    finishOnMain(null, 0f, 0, 0, "stats failed: " + t);
                }
            }, workerHandler);
        } catch (Throwable t) {
            finishOnMain(null, 0f, 0, 0, "PixelCopy.request threw: " + t);
        }
    }

    /** 唯一允许重建位图的地方（只在工作线程调用）。 */
    private Bitmap ensureDest(int w, int h) {
        Bitmap b = dest;
        if (b != null && b.getWidth() == w && b.getHeight() == h && !b.isRecycled()) {
            return b;
        }
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        dest = b;
        px = new int[w * h];
        return b;
    }

    /** 工作线程：读像素 → 均值亮度 + 上下缘平均色 → 回主线程。 */
    private void finishStats(Bitmap bmp, int w, int h) {
        int[] a = px;
        if (a == null || a.length < w * h) {
            finishOnMain(null, 0f, 0, 0, "pixel buffer mismatch");
            return;
        }
        bmp.getPixels(a, 0, w, 0, 0, w, h);

        long lumSum = 0;
        long topR = 0, topG = 0, topB = 0, botR = 0, botG = 0, botB = 0;
        int topN = 0, botN = 0;
        final int band = Math.max(1, Math.min(EDGE_BAND, h / 2));
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            final boolean isTop = y < band;
            final boolean isBottom = y >= h - band;
            for (int x = 0; x < w; x++) {
                final int p = a[row + x];
                final int r = (p >> 16) & 0xFF;
                final int g = (p >> 8) & 0xFF;
                final int b = p & 0xFF;
                lumSum += (77 * r + 150 * g + 29 * b) >> 8;
                if (isTop) {
                    topR += r; topG += g; topB += b; topN++;
                } else if (isBottom) {
                    botR += r; botG += g; botB += b; botN++;
                }
            }
        }
        final int n = w * h;
        final float meanLum = n == 0 ? 0.5f : (lumSum / (float) n) / 255f;
        final int edgeTop = topN == 0 ? 0
                : 0xFF000000 | ((int) (topR / topN) << 16) | ((int) (topG / topN) << 8)
                | (int) (topB / topN);
        final int edgeBottom = botN == 0 ? 0
                : 0xFF000000 | ((int) (botR / botN) << 16) | ((int) (botG / botN) << 8)
                | (int) (botB / botN);
        finishOnMain(null, meanLum, edgeTop, edgeBottom, "ok " + w + "x" + h);
    }

    /** 统一切回主线程收尾（成功与失败都走这里，保证 inFlight 一定被清）。 */
    private void finishOnMain(Bitmap bmp, float meanLum, int edgeTop, int edgeBottom, String note) {
        main.post(() -> {
            inFlight = false;
            if (note.startsWith("alloc") || note.startsWith("PixelCopy") || note.startsWith("stats")
                    || note.startsWith("pixel")) {
                noteFail(note);
                return;
            }
            successCount++;
            failCount = 0;
            if (successCount == 1 || successCount % 60 == 0) {
                XposedCompat.log(TAG + " stats " + note + " meanLum=" + round2(meanLum)
                        + " edgeTop=" + hex(edgeTop) + " edgeBottom=" + hex(edgeBottom));
            }
            if (listener != null) {
                listener.onStats(meanLum, edgeTop, edgeBottom);
            }
        });
    }

    private static String hex(int c) {
        return c == 0 ? "?" : String.format("#%06X", c & 0x00FFFFFF);
    }

    private static String round2(float v) {
        return String.valueOf(Math.round(v * 100) / 100f);
    }

    // ==================================================================
    // 失败处理
    // ==================================================================

    /** 软跳过：当前没有可采的目标（正常时序），不计失败、只按节流打日志。 */
    private void skipSoft(String why) {
        long now = SystemClock.uptimeMillis();
        if (successCount == 0 && now - lastLogMs > LOG_THROTTLE_MS) {
            lastLogMs = now;
            XposedCompat.log(TAG + " sampling skipped: " + why);
        }
    }

    /**
     * 真失败：PixelCopy 报错 / 分配失败 / 抛异常。
     *
     * ⚠️ 放弃之后**不停循环**（tick 会立刻返回，代价可忽略），等宿主换新 Activity 自动恢复。
     */
    private void noteFail(String why) {
        failCount++;
        long now = SystemClock.uptimeMillis();
        if (failCount >= MAX_CONSECUTIVE_FAILS) {
            gaveUp = true;
            XposedCompat.log(TAG + " giving up after " + failCount + " consecutive failures ("
                    + why + ") -> 面板退回静态填充（模糊不受影响）");
            return;
        }
        if (now - lastLogMs > LOG_THROTTLE_MS) {
            lastLogMs = now;
            XposedCompat.log(TAG + " sampling failed (" + failCount + "/"
                    + MAX_CONSECUTIVE_FAILS + "): " + why);
        }
    }
}
