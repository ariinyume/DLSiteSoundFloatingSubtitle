package com.sena.dlsitesoundfloat.window;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.view.FloatingSubtitleView;

import de.robv.android.xposed.XposedBridge;

/**
 * 进程内悬浮窗管理器（单例）。
 *
 * 关键设计：本管理器与目标 App（DLsiteSound）运行在【同一个进程】中，
 * 因此与各个 Hook 共享同一个 SubtitleRepository 实例，字幕数据天然互通。
 *
 * 悬浮窗的权限归属（重要）：窗口是在 DLsiteSound 的进程里、用它的 Context 挂的，
 * 所以类型 2038 (TYPE_APPLICATION_OVERLAY) 首先需要【DLsiteSound 自己】的
 * SYSTEM_ALERT_WINDOW（显示在其他应用上层 / 悬浮窗）权限 —— 这是必需项。
 * ⚠️ 实测（ColorOS）**模块 App 侧也建议一并授予**该权限：系统会在模块侧再拦一道，
 * 两者都开最稳妥。详见 docs/build.md 的「运行环境」。
 *
 * 交互（v5）：
 *   - 面板任意处按住拖动 → 移动窗口
 *   - 右下角手柄区按住拖动 → 缩放窗口（宽高）
 *
 * v12：系统级「背后实时模糊」默认关闭（{@link #ENABLE_SYSTEM_BLUR_BEHIND}）。
 *   原因见该常量注释 —— ColorOS 上 FLAG_BLUR_BEHIND 会糊掉整个屏幕，且 App 侧无法限制模糊区域。
 */
public class FloatingWindowManager {
    private static final String TAG = "[DLsiteSoundFloat:Window]";
    private static final long MIN_RETRY_INTERVAL_MS = 800L;
    private static final int MIN_W_DP = 140;
    private static final int MIN_H_DP = 72;
    private static final int DEFAULT_W_RATIO = 85; // 屏宽百分比
    private static final int DEFAULT_H_DP = 200;
    /**
     * v20：窗口纵向**最长** = 屏幕高度的该百分比（50 = 一半）。
     * Ari 要求"上下拉长长度不得超过屏幕的一半"，缩放 clamp 的上限即取自本值。
     */
    private static final int MAX_H_SCREEN_RATIO = 50;
    /** 面板背后实时模糊半径（dp）。仅 Android 12+ 且系统开启跨窗模糊时生效，否则自动降级。 */
    private static final int BLUR_RADIUS_DP = 26;
    /**
     * 是否启用系统级「背后实时模糊」（FLAG_BLUR_BEHIND + setBlurBehindRadius）。
     *
     * ⚠️ 默认 false —— 实测在 OPPO/ColorOS 上，TYPE_APPLICATION_OVERLAY 窗口一旦置上
     * FLAG_BLUR_BEHIND，模糊会铺满【整个屏幕】而不是只模糊面板身后那一小块区域：
     * 只开一个 85%×200dp 的小窗，结果整个桌面/页面全糊（Ari 机器实测已复现）。
     *
     * 原因：模糊区域由系统合成器（SurfaceFlinger 的 blur region）决定，对 overlay 窗口
     * 就是整屏；App 侧没有任何公开 API 能把它裁到窗口范围内。于是只能整体关闭，
     * 面板的质感改由 GlassPanelDrawable 的**半透明渐变底 + 提高底色不透明度**实现
     * （v13 起已移除顶部高光/外辉光/轮廓边缘光等装饰层）。
     *
     * 若要重新试错（例如换到 AOSP 机型）：把这里改成 true 即可，其余逻辑无需改动。
     */
    private static final boolean ENABLE_SYSTEM_BLUR_BEHIND = false;

    private static FloatingWindowManager sInstance;

    // ---- v29：窗口几何记忆（同一进程内生效）----
    /**
     * 最近一次的窗口尺寸 / 位置。0 或 {@link Integer#MIN_VALUE} 表示「还没设定过」。
     *
     * 背景：换轨时会「自动关窗（判无字幕后）→ 字幕到达再开窗」，窗口被整个重建；
     * 旧实现每次重建都套用默认尺寸（85% 屏宽 × 200dp）与默认位置，
     * 于是 Ari 拖好的大小/位置会被重置回默认 —— 这就是「短时间换轨后悬浮窗变回默认尺寸」。
     * 这里把几何记在静态字段里，重建时优先恢复（并按当前屏幕重新夹取，防旋转/分辨率变化后跑出屏外）。
     */
    private static int sLastW = 0;
    private static int sLastH = 0;
    private static int sLastX = Integer.MIN_VALUE;
    private static int sLastY = Integer.MIN_VALUE;

    private Context appContext;
    private WindowManager wm;
    private FloatingSubtitleView view;
    private WindowManager.LayoutParams params;
    private boolean showing = false;
    private boolean permissionPromptPending = false; // 守卫：失败只自动提示一次权限页
    /**
     * 上一次「挂载失败」的时刻。
     * v17：只在失败时记录 —— 原先无论成败都记录，导致「隐藏后立刻再显示」（例如切音轨时
     * 自动关窗又被字幕到达重新打开）会被这条节流挡掉，窗口迟迟不出现。
     */
    private long lastFailMs = 0L;
    private final Object lock = new Object();

    public static synchronized FloatingWindowManager getInstance() {
        if (sInstance == null) {
            sInstance = new FloatingWindowManager();
        }
        return sInstance;
    }

    /**
     * 由 SubtitleRepository 的观察者驱动：根据 repo 的开关状态与实际显示状态对齐窗口。
     */
    public void sync(Context ctx) {
        if (ctx != null) {
            this.appContext = ctx;
        }
        SubtitleRepository repo = SubtitleRepository.getInstance();
        boolean wantOpen = repo.isFloatingWindowOpen();
        synchronized (lock) {
            if (wantOpen && !showing) {
                show();
            } else if (!wantOpen && showing) {
                hide();
            }
            if (showing && view != null) {
                view.updateFromRepository();
            }
        }
    }

    private void show() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        // 节流：只在「上一次真的失败」时才限制重试频率（成功不节流，
        // 否则 hide→show 的快速切换会被挡掉）。
        long now = SystemClock.uptimeMillis();
        if (now - lastFailMs < MIN_RETRY_INTERVAL_MS) {
            return;
        }

        // 不用 Settings.canDrawOverlays() 做前置门控（OPPO 上会误报 false），
        // 直接尝试 addView，用真实结果判断权限是否生效。
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            view = new FloatingSubtitleView(ctx);
            view.setOnTouchListener(new GestureListener());

            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            int minW = dp(ctx, MIN_W_DP);
            int minH = dp(ctx, MIN_H_DP);
            int maxH = screenH * MAX_H_SCREEN_RATIO / 100;

            // v29：优先恢复上次的尺寸 / 位置；从未设定过才用默认值。
            // 恢复值一律按当前屏幕重新夹取（旋转、分辨率变化、字体缩放后仍保证窗口可见）。
            int defW = Math.max(minW, screenW * DEFAULT_W_RATIO / 100);
            int defH = dp(ctx, DEFAULT_H_DP);
            int w = clampInt(sLastW > 0 ? sLastW : defW, minW, screenW);
            int h = clampInt(sLastH > 0 ? sLastH : defH, minH, maxH);
            int x = clampInt(sLastX != Integer.MIN_VALUE ? sLastX : (int) (screenW * 0.075),
                    0, Math.max(0, screenW - w));
            int y = clampInt(sLastY != Integer.MIN_VALUE ? sLastY : (int) (screenH * 0.32),
                    0, Math.max(0, screenH - h));
            boolean restored = sLastW > 0 || sLastX != Integer.MIN_VALUE;

            params = new WindowManager.LayoutParams(
                    w, h,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = x;
            params.y = y;

            boolean blurOn = applyBlurBehind(ctx);
            wm.addView(view, params);
            showing = true;
            permissionPromptPending = false; // 成功挂载即重置提示守卫
            view.setBlurBehindActive(blurOn); // 玻璃底按模糊是否生效自适应通透度
            view.updateFromRepository();
            XposedBridge.log(TAG + " floating window shown (pkg=" + ctx.getPackageName()
                    + ", " + params.width + "x" + params.height
                    + " at " + params.x + "," + params.y
                    + (restored ? " [geometry restored]" : " [default geometry]") + ")");
        } catch (Throwable e) {
            lastFailMs = SystemClock.uptimeMillis(); // 只有失败才参与节流
            boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(ctx);
            XposedBridge.log(TAG + " addView FAILED: " + e.getMessage()
                    + " | canDrawOverlays=" + canDraw
                    + " | windowOwnerPkg=" + ctx.getPackageName()
                    + " (需给该 App 打开「显示在其他应用上层/悬浮窗」)");
            showing = false;
            view = null;
            // 真正没权限：回退开关，让按钮显示「悬浮关」，并只自动提示一次权限页。
            SubtitleRepository.getInstance().setFloatingWindowOpen(false);
            if (!permissionPromptPending) {
                permissionPromptPending = true;
                promptOverlayPermission(ctx);
            }
        }
    }

    /**
     * 为悬浮窗开启「背后实时模糊」——即让面板像磨砂玻璃一样把身后画面真正模糊掉。
     *
     * 依赖 Android 12+ 的跨窗模糊能力：给 LayoutParams 设置模糊半径并置 FLAG_BLUR_BEHIND。
     * 该能力受系统/厂商开关控制（部分机器默认关闭跨窗模糊），因此：
     *   - 先探测 {@link WindowManager#isCrossWindowBlurEnabled()}；
     *   - 全程 try/catch，任何异常都不影响窗口正常显示。
     * 返回是否真正开启，供视图层据此调整玻璃底的通透度（无模糊时更不透明以保证可读性）。
     *
     * 必须在 addView 之前调用（模糊是窗口创建时的属性）。
     */
    private boolean applyBlurBehind(Context ctx) {
        if (!ENABLE_SYSTEM_BLUR_BEHIND) {
            // ColorOS 上会糊掉整个屏幕，已全局关闭（详见常量注释）。
            XposedBridge.log(TAG + " blurBehind: DISABLED by config (would blur whole screen on ColorOS)");
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || params == null) {
            XposedBridge.log(TAG + " blurBehind skipped: SDK_INT=" + Build.VERSION.SDK_INT);
            return false;
        }
        try {
            boolean enabled = true;
            if (wm != null) {
                try {
                    enabled = wm.isCrossWindowBlurEnabled();
                } catch (Throwable ignored) {
                }
            }
            if (enabled) {
                params.setBlurBehindRadius(dp(ctx, BLUR_RADIUS_DP));
                params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            }
            XposedBridge.log(TAG + " blurBehind: crossWindowBlurEnabled=" + enabled
                    + ", radiusDp=" + BLUR_RADIUS_DP);
            return enabled;
        } catch (Throwable e) {
            XposedBridge.log(TAG + " blurBehind unavailable: " + e.getMessage());
            return false;
        }
    }

    private void promptOverlayPermission(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            XposedBridge.log(TAG + " prompted overlay permission for " + ctx.getPackageName());
        } catch (Throwable e) {
            XposedBridge.log(TAG + " promptOverlayPermission failed: " + e.getMessage());
        }
    }

    private void hide() {
        saveGeometry(); // v29：关窗前固化几何，下次开窗直接恢复
        try {
            if (wm != null && view != null) {
                wm.removeView(view);
            }
        } catch (Throwable ignored) {
        }
        view = null;
        showing = false;
        XposedBridge.log(TAG + " floating window hidden");
    }

    public boolean isShowing() {
        return showing;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** v29：把当前窗口几何写进静态记忆（尺寸 + 位置）。 */
    private void saveGeometry() {
        WindowManager.LayoutParams p = params;
        if (p == null) {
            return;
        }
        sLastW = p.width;
        sLastH = p.height;
        sLastX = p.x;
        sLastY = p.y;
    }

    /**
     * 移动 + 缩放（右下角手柄）。窗口根的 OnTouchListener 先于子 View 的 onTouchEvent 生效，
     * 且内部 ScrollView 已被改成不拦截触摸，所以这里能稳定拿到全部事件。
     */
    private class GestureListener implements View.OnTouchListener {
        private boolean resizing;
        private boolean moved;
        private float downRawX;
        private float downRawY;
        private int startW;
        private int startH;
        private int startX;
        private int startY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            synchronized (lock) {
                if (params == null || view == null || wm == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startW = params.width;
                        startH = params.height;
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        // 缩放热区：以右下角**三角形自身为中心**、边长 GRIP_HIT_DP(30dp) 的正方形
                        // （v16 之前是「距窗口右下角 44dp 见方」—— 既过大，又与三角形错位）。
                        resizing = view.hitResizeArea(event.getX(), event.getY());
                        // 诊断：确认触摸是否真的送达悬浮窗视图（若「点了没反应」，先看有没有这行）
                        XposedBridge.log(TAG + " touch DOWN x=" + (int) event.getX()
                                + " y=" + (int) event.getY()
                                + " viewW=" + view.getWidth() + " viewH=" + view.getHeight()
                                + " resizing=" + resizing);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float rawDx = event.getRawX() - downRawX;
                        float rawDy = event.getRawY() - downRawY;
                        float slop = 8f * view.getContext().getResources().getDisplayMetrics().density;
                        // 超过阈值才判定为拖动，轻触不移动 → 视为「点击」。
                        if (!moved && (Math.abs(rawDx) > slop || Math.abs(rawDy) > slop)) {
                            moved = true;
                        }
                        if (!moved) {
                            return true;
                        }
                        int dx = (int) rawDx;
                        int dy = (int) rawDy;
                        if (resizing) {
                            int maxW = view.getContext().getResources().getDisplayMetrics().widthPixels;
                            // v20：纵向最长不超过屏幕高度的一半（MAX_H_SCREEN_RATIO = 50%）。
                            int maxH = view.getContext().getResources().getDisplayMetrics().heightPixels
                                    * MAX_H_SCREEN_RATIO / 100;
                            // 缩放下界：当前字幕行完整显示所需的最小尺寸（动态）。
                            int dynMinW = view.getMinWidthPx();
                            int dynMinH = view.getMinHeightPx();
                            int loW = dynMinW > 0 ? dynMinW : dp(view.getContext(), MIN_W_DP);
                            int loH = dynMinH > 0 ? dynMinH : dp(view.getContext(), MIN_H_DP);
                            params.width = clamp(startW + dx, loW, maxW);
                            params.height = clamp(startH + dy, loH, maxH);
                            view.recenterCurrent(); // 缩放时保持当前字幕行垂直居中
                        } else {
                            params.x = startX + dx;
                            params.y = startY + dy;
                        }
                        try {
                            wm.updateViewLayout(view, params);
                        } catch (Throwable ignored) {
                        }
                        saveGeometry(); // v29：随时记住尺寸/位置，供「关窗 → 重开」时恢复
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        // 未拖动、且不是按在右下角缩放手柄上 → 视为「点击面板任意处」：
                        // 切换右上角关闭按钮（✕）的显隐。
                        if (!moved && !resizing) {
                            view.toggleCloseButton();
                        } else {
                            XposedBridge.log(TAG + " touch UP moved=" + moved
                                    + " resizing=" + resizing + " -> no tap toggle");
                        }
                        saveGeometry(); // v29：抬手时再固化一次
                        resizing = false;
                        moved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        resizing = false;
                        moved = false;
                        return true;
                    default:
                        return false;
                }
            }
        }

        private int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }
}
