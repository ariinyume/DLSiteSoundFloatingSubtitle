package com.sena.dlsitesoundfloat.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sena.dlsitesoundfloat.data.SubtitleCue;
import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 悬浮窗内的字幕视图。
 *
 * 重要：本类运行在被 hook 的目标进程（DLsiteSound）中，目标进程的 Resources
 * 并不包含本模块的编译资源，因此【禁止】使用 R.layout / R.id / R.drawable 等
 * 模块资源 ID（会触发 ResourceNotFound）。所有子 View 一律用代码构建。
 *
 * v5：只渲染当前 cue 前后各 WINDOW_RADIUS 条；内层 ScrollView 不拦截触摸；右下角缩放手柄。
 * v6：当前行缩放 1.3→1.1；窗口最小尺寸受当前行限制；当前行保持垂直居中。
 * v7：横向宽度不再受文字自然长度限制，当前高亮行强制换行 ≤3 行。
 * v8：关闭滚动条；右上角 ✕ 关闭按钮（点击面板切换显隐）。
 * v9：玻璃拟态质感 —— GlassPanelDrawable、圆角 20dp、文字投影、当前行加粗、
 *     setBlurBehindActive() 自适应通透度。
 *
 * v11：左右内边距 14 → 22dp；✕ 全链路诊断日志；✕ 30 → 32dp、边距 6 → 8dp。
 *
 * v12：关闭系统级 FLAG_BLUR_BEHIND —— 实测（ColorOS）它会把【整个屏幕】糊掉，而不是只模糊
 *   面板身后那一小块。改为提高玻璃底色不透明度（fillScale 1.9）来维持质感与字幕可读性。
 *
 * v13：
 *   1) 面板去掉【顶部高光 / 外辉光 / 轮廓边缘光】三层装饰，只留半透明渐变底 —— 更干净。
 *   2) 左右内边距 22 → 16dp（{@link #PANEL_PAD_H_DP}），最小宽度随之自动重算。
 *   3) 当前字幕行缩放 1.1 → 1.0 倍（{@link #CURRENT_SCALE}），仍靠"加粗 + 全白"区分。
 *   4) 右下角缩放手柄 "◢" 字符 → 自绘 {@link GripIndicatorView}（三个角全部倒圆）。
 *
 * v14：两个小按钮的"体量与位置"重排
 *   1) **根视图不再设内边距** —— 原先 padding 加在 FrameLayout 自己身上，导致所有子 View
 *      （含 ✕ 与缩放手柄）都被向内推 16/13dp，"贴不到窗口边缘"。
 *      现在内边距下移到 {@link #container}（内容层），装饰性按钮得以贴角。
 *   2) 缩放手柄：填充改「灰 + 40% 不透明」（见 {@link GripIndicatorView}），
 *      距窗口右下角 3dp。
 *   3) ✕ 按钮：TextView 字符 → 自绘 {@link CloseButtonView}（灰底 + 镂空透明 ✕），
 *      直径 32 → 18dp，距窗口上/右边缘各 3dp，与手柄成同一套视觉语言。
 *
 * v15：两个装饰件的体量与边距再放大
 *   1) ✕ 按钮 18 → **40dp**（{@link #CLOSE_BTN_DP}）—— 显示圆盘与**点击区域同时放大**，
 *      解决"按钮太小、手指点不中"的问题；
 *   2) ✕ 外边距 3 → **10dp**（{@link #CLOSE_BTN_MARGIN_DP}）；
 *   3) 缩放手柄外边距 3 → **10dp**（{@link GripIndicatorView#INSET_DP}），与 ✕ 对齐。
 *
 * v16：缩放热区收窄并与三角形对齐
 *   1) 热区边长 44 → **30dp**（{@link #GRIP_HIT_DP}）；
 *   2) 热区不再"贴窗口右下角"，而是**以手柄三角形为中心**（{@link #hitResizeArea}）——
 *      原先落在窗口角落 44dp 见方内就判定为缩放，会吃掉面板右下角一大片可点击区；
 *   3) 手柄视图容器尺寸常量由 GRIP_SIZE_DP 更名为 {@link #GRIP_VIEW_DP}（值仍 44dp，仅作容器）。
 *
 * v17：**彻底关掉滚动条**。原先调了 setScrollbarFadingEnabled(false)，其副作用是把
 *   ScrollabilityCache 状态置为 ON（常显），于是每次 scrollTo（典型场景：拖右下角手柄
 *   缩放时 recenterCurrent 让当前行重新居中）都会让滚动条闪现一下。
 *   现在：不调 setScrollbarFadingEnabled(false)、setScrollBarSize(0)，
 *   并覆写 awakenScrollBars() 返回 false —— 滚动条状态永远停在 OFF。
 *
 * v18：✕ 按钮收到 30dp。显示圆盘与点击区域同步缩小（{@link #CLOSE_BTN_DP} 40 → 30），
 *   距窗口上 / 右边缘仍为 {@link #CLOSE_BTN_MARGIN_DP}（10dp）不变。
 *
 * v20：本轮四条观感/手感优化（Ari 提出）
 *   1) 【窗口纵向上限】最长不得超过屏幕高度的一半 —— 实现在
 *      {@code FloatingWindowManager.GestureListener} 的缩放 clamp（本类只配合）。
 *   2) 【当前行恒定居中】此前当前行是首行 / 尾行时会贴顶 / 贴底：因为 ScrollView 的滚动量
 *      被夹在 [0, 内容高-视口高] 内，没有多余空间把首尾行推到中间。
 *      现在给 {@link #container} 上下各留 **半个视口高度**的空白内边距
 *      （{@link #applyCenterPadding()}，随窗口尺寸自动重算），首行/尾行同样能滚到正中。
 *   3) 【最小留白 15dp】{@link #PANEL_PAD_H_DP} 16 → 13dp、{@link #PANEL_PAD_V_DP} 13 → 10dp，
 *      叠加每行文字自身的 2dp / 5dp 内边距后，窗口缩到最小时字幕四周**恰好**留 15dp；
 *      {@link #MIN_TEXT_PAD_DP}(15dp) 只是兜底下限，且作用在"有效留白"上（不叠加）。
 *   4) 【平滑上滚】换行时不再瞬间跳位，改用 {@link ValueAnimator} 在
 *      {@link #SCROLL_ANIM_MS} 毫秒内减速滚动到新位置（视觉上字幕向上滚动）。
 *      仅在"播放推进导致当前行变化"时动画；窗口缩放 / 首次定位仍为立即跳转。
 */
public class FloatingSubtitleView extends FrameLayout {
    private static final String TAG = "[DLsiteSoundFloat:View]";

    /** 缩放手柄视图的布局尺寸（dp）—— 仅作容器，图形按 {@link GripIndicatorView} 的参数绘制。 */
    private static final int GRIP_VIEW_DP = 44;
    /**
     * 右下角缩放热区边长（dp）。命中判定见 {@link #hitResizeArea(float, float)}：
     * 热区是**以三角形为中心**、边长为本值的正方形（不再贴窗口右下角）。
     * v16：44 → 30dp（原先 44dp 见方既过大，又与三角形错位）。
     */
    public static final int GRIP_HIT_DP = 30;
    /** 当前行前后各渲染多少条 cue。 */
    private static final int WINDOW_RADIUS = 8;
    /** 当前字幕行的基准字号（sp），放大倍率作用于此。 */
    private static final float BASE_TEXT_SP = 17f;
    /** 实时播放中的字幕行缩放倍率（v13：1.1 → 1.0，与上下文行同字号）。 */
    private static final float CURRENT_SCALE = 1.0f;
    /** 当前高亮行允许的最大换行行数。 */
    private static final int MAX_LINES = 3;

    // —— 玻璃拟态外观参数 ——
    /** 面板圆角（dp）。 */
    private static final int CORNER_RADIUS_DP = 20;
    /**
     * 面板左右内边距（dp）。想再宽/再窄改这一个数就行（最小宽度会自动跟着重算）。
     * v20：16 → 13dp —— 叠加每行文字自身的 {@link #LINE_PAD_H_DP}(2dp) 后，
     * 窗口缩到最小时字幕左右**恰好**留 15dp。
     */
    private static final int PANEL_PAD_H_DP = 13;
    /**
     * 面板上下内边距（dp）。
     * v20：13 → 10dp —— 叠加每行文字自身的 {@link #LINE_PAD_V_DP}(5dp) 后，
     * 窗口缩到最小时字幕上下**恰好**留 15dp。
     * （v20 中途曾改成 15dp，Ari 要求与左右统一成"有效留白 15dp"，故再下调。）
     */
    private static final int PANEL_PAD_V_DP = 10;
    /** 每行字幕 TextView 自身的左右内边距（dp）—— 与 {@link #PANEL_PAD_H_DP} 相加 = 实际留白。 */
    private static final int LINE_PAD_H_DP = 2;
    /** 每行字幕 TextView 自身的上下内边距（dp）—— 与 {@link #PANEL_PAD_V_DP} 相加 = 实际留白。 */
    private static final int LINE_PAD_V_DP = 5;
    /**
     * v20：当前字幕行与窗口边缘的**最小留白**（dp）。
     *
     * ⚠️ 比较的是「面板内边距 + 文字自身内边距」这个**有效值**，不是单独的面板内边距 ——
     * 否则会算成 {@code max(13, 15) + 2 = 17dp}，比要求的 15dp 更宽。
     * 正常配置下（横向 13+2、纵向 10+5）有效值正好 15dp；本常量只是
     * "以后有人把 PAD 调得更小"时的兜底下限。
     */
    private static final int MIN_TEXT_PAD_DP = 15;
    /** v20：字幕换行时的平滑滚动时长（ms）。 */
    private static final int SCROLL_ANIM_MS = 360;
    /** 是否使用浅色（奶白）玻璃。想换风格把这里改成 true 即可。 */
    private static final boolean LIGHT_GLASS = false;
    /** 字幕投影：让文字在模糊/明亮背景上仍清晰。 */
    private static final float TEXT_SHADOW_RADIUS = 7f;
    private static final int TEXT_SHADOW_COLOR = 0xCC000000;
    /** 右上角 ✕ 按钮尺寸（dp）：**显示圆盘与点击区域同为该值**。v15：18 → 40dp；v18：→ 30dp。 */
    private static final int CLOSE_BTN_DP = 30;
    /** ✕ 按钮距窗口上/右边缘的距离（dp）。v15：3 → 10dp。 */
    private static final int CLOSE_BTN_MARGIN_DP = 10;

    private NonInterceptScrollView scrollView;
    private LinearLayout container;
    private TextView hint;
    private CloseButtonView closeBtn;

    // 渲染缓存：内容没变就跳过重建
    private String lastRenderKey = null;
    private boolean lastHintVisible = false;

    // 当前字幕行在 container 中的局部索引（用于居中滚动）
    private int currentLocalIndex = -1;

    /**
     * v20：上一次已经"居中过"的**全局** cue 索引。
     * 用于区分「播放推进导致当前行换了」（→ 平滑上滚）与「首次定位 / 缩放后重排」（→ 立即跳转）。
     */
    private int lastCenteredCueIndex = -1;

    /** v20：平滑滚动动画（同一时刻只允许一个，新的会取消旧的）。 */
    private ValueAnimator scrollAnim;

    // 当前字幕行完整显示所需的最小尺寸（像素）。0 表示尚未测量 / 无当前行。
    private int minWidthPx = 0;
    private int minHeightPx = 0;

    // 系统级跨窗模糊是否生效（影响玻璃的可透度）
    private boolean blurActive = false;

    public FloatingSubtitleView(Context context) {
        super(context);
        init();
    }

    public int getMinWidthPx() {
        return minWidthPx;
    }

    public int getMinHeightPx() {
        return minHeightPx;
    }

    /** 由窗口层告知系统跨窗模糊是否真正生效，用于自适应玻璃通透度。 */
    public void setBlurBehindActive(boolean active) {
        if (this.blurActive == active) {
            return;
        }
        this.blurActive = active;
        applyPanelBackground();
    }

    /** 重算并把当前字幕行滚动到悬浮窗垂直居中（缩放窗口后调用；v20：不带动画，避免拖拽时抖动）。 */
    public void recenterCurrent() {
        if (currentLocalIndex >= 0) {
            scrollToCurrent(currentLocalIndex, false);
        }
    }

    /**
     * 切换右上角关闭按钮的显示/隐藏（由窗口层的「点击面板」手势调用）。
     * 带日志：下次若「点了没反应」，从日志就能判断是「点击没送到视图」还是「送到了但没生效」。
     */
    public void toggleCloseButton() {
        if (closeBtn == null) {
            XposedBridge.log(TAG + " toggleCloseButton: closeBtn == null (view not init?)");
            return;
        }
        boolean show = closeBtn.getVisibility() != VISIBLE;
        closeBtn.setVisibility(show ? VISIBLE : GONE);
        XposedBridge.log(TAG + " panel tapped -> close button " + (show ? "VISIBLE" : "GONE"));
    }

    /**
     * 生成玻璃面板背景。
     * 有系统模糊时可更通透；无模糊（当前默认档：ColorOS 上系统模糊会糊掉整个屏幕，
     * 已被 {@code ENABLE_SYSTEM_BLUR_BEHIND=false} 关闭）时提高底色不透明度，
     * 让面板呈现"磨砂卡"质感，并保证白色字幕在任何背景上都清晰可读。
     */
    private void applyPanelBackground() {
        // v13：GlassPanelDrawable 已精简为「只有半透明渐变底」，不再需要传描边宽度。
        GlassPanelDrawable panel = new GlassPanelDrawable(dp(CORNER_RADIUS_DP), LIGHT_GLASS);
        panel.setAlpha(blurActive ? 242 : 255);
        panel.setFillScale(blurActive ? 1f : 1.9f);
        setBackground(panel);
    }

    private void init() {
        applyPanelBackground();
        // v14：根视图**不设**内边距 —— ✕ 与缩放手柄需要贴到窗口边缘（3dp）。
        // 内容内边距下移到 container；measureCurrentCue 里的 padW/padH 数值含义不变。

        scrollView = new NonInterceptScrollView(getContext());
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(PANEL_PAD_H_DP), dp(PANEL_PAD_V_DP),
                dp(PANEL_PAD_H_DP), dp(PANEL_PAD_V_DP));

        scrollView.addView(container);

        hint = new TextView(getContext());
        hint.setText("无字幕");
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(TEXT_SHADOW_RADIUS, 0f, dp(1), TEXT_SHADOW_COLOR);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.CENTER;
        hint.setLayoutParams(hintLp);

        // 右下角缩放手柄提示（v13：自绘「三角倒圆」图形；v14：灰底 40%；
        // v15：距窗口右下角 10dp，与 ✕ 的外边距对齐）
        GripIndicatorView grip = new GripIndicatorView(getContext());
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                dp(GRIP_VIEW_DP), dp(GRIP_VIEW_DP));
        gripLp.gravity = Gravity.BOTTOM | Gravity.END;
        grip.setLayoutParams(gripLp);

        // 右上角关闭按钮（✕），默认隐藏，点击面板任意处切换显隐。
        // v15：自绘「灰底圆 + 镂空 ✕」，直径 / 点击区域同为 40dp，距窗口上/右边缘各 10dp。
        // v18：直径 / 点击区域收到 30dp。
        closeBtn = new CloseButtonView(getContext());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dp(CLOSE_BTN_DP), dp(CLOSE_BTN_DP));
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp(CLOSE_BTN_MARGIN_DP);
        closeLp.rightMargin = dp(CLOSE_BTN_MARGIN_DP);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setVisibility(GONE);
        closeBtn.setOnClickListener(v -> {
            XposedBridge.log(TAG + " close button CLICKED -> setFloatingWindowOpen(false)");
            // 关闭悬浮窗：置 false 后，repo 观察者会驱动 FloatingWindowManager 隐藏窗口，
            // 同时播放页按钮状态同步更新为「悬浮关」。
            SubtitleRepository.getInstance().setFloatingWindowOpen(false);
        });

        addView(scrollView);
        addView(hint);
        addView(grip);
        addView(closeBtn); // 最后添加，保证在最上层、可点击
        XposedBridge.log(TAG + " view built: padH=" + PANEL_PAD_H_DP + "dp padV=" + PANEL_PAD_V_DP
                + "dp minTextPad=" + MIN_TEXT_PAD_DP + "dp corner=" + CORNER_RADIUS_DP
                + "dp lightGlass=" + LIGHT_GLASS
                + " currentScale=" + CURRENT_SCALE + " closeBtn=" + CLOSE_BTN_DP
                + "dp margin=" + CLOSE_BTN_MARGIN_DP + "dp gripInset="
                + GripIndicatorView.INSET_DP + "dp gripView=" + GRIP_VIEW_DP
                + "dp gripHit=" + GRIP_HIT_DP + "dp scrollAnim=" + SCROLL_ANIM_MS + "ms");
    }

    /**
     * 右下角「缩放热区」命中判定。
     *
     * 热区是**以手柄三角形自身为中心**、边长 {@link #GRIP_HIT_DP} 的正方形 ——
     * 三角形绘制在窗口右下角 {@code INSET_DP} ~ {@code INSET_DP + LEG_DP} 之间，
     * 故其中心距窗口右下角 = {@code INSET_DP + LEG_DP / 2}。
     * 手指落在三角形上（或紧邻四周）即命中，而不是"贴着窗口角落"才算。
     *
     * @param x 触摸点相对本视图左上角的 x
     * @param y 触摸点相对本视图左上角的 y
     */
    public boolean hitResizeArea(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        float centerOff = (GripIndicatorView.INSET_DP + GripIndicatorView.LEG_DP / 2f) * density;
        float half = GRIP_HIT_DP * density / 2f;
        return Math.abs(x - (getWidth() - centerOff)) <= half
                && Math.abs(y - (getHeight() - centerOff)) <= half;
    }

    public void updateFromRepository() {
        SubtitleRepository repo = SubtitleRepository.getInstance();
        List<SubtitleCue> cues = repo.getCues();
        int currentIdx = repo.findCurrentCueIndex();

        // 1) 有完整 cue 列表 → 渲染当前行附近，当前行放大、其余模糊
        if (!cues.isEmpty()) {
            int from = Math.max(0, currentIdx - WINDOW_RADIUS);
            int to = Math.min(cues.size() - 1, (currentIdx < 0 ? 0 : currentIdx) + WINDOW_RADIUS);
            String key = "cues:" + cues.size() + ":" + currentIdx + ":" + from + ":" + to;
            if (key.equals(lastRenderKey) && !lastHintVisible) {
                return;
            }
            hint.setVisibility(GONE);
            scrollView.setVisibility(VISIBLE);
            lastHintVisible = false;
            renderCues(cues, currentIdx, from, to);
            lastRenderKey = key;
            // v20：只有「播放推进 → 当前行真的换了」才做平滑上滚；首次定位直接到位。
            boolean animate = currentIdx >= 0 && lastCenteredCueIndex >= 0
                    && lastCenteredCueIndex != currentIdx;
            scrollToCurrent(currentLocalIndex, animate);
            lastCenteredCueIndex = currentIdx >= 0 ? currentIdx : -1;
            return;
        }

        // 2) 降级：只有屏上镜像的当前行（播放进度不可用且未抓到完整列表）
        List<String> current = repo.getCurrentSubtitles();
        if (!current.isEmpty()) {
            String key = "mirror:" + String.join("\u0001", current);
            if (key.equals(lastRenderKey) && !lastHintVisible) {
                return;
            }
            hint.setVisibility(GONE);
            scrollView.setVisibility(VISIBLE);
            lastHintVisible = false;
            renderMirrored(current);
            lastRenderKey = key;
            lastCenteredCueIndex = -1;
            return;
        }

        // 3) 真的没有字幕
        if (!lastHintVisible) {
            minWidthPx = 0;
            minHeightPx = 0;
            currentLocalIndex = -1;
            lastCenteredCueIndex = -1;
            cancelScrollAnim();
            hint.setVisibility(VISIBLE);
            scrollView.setVisibility(GONE);
            container.removeAllViews();
            lastHintVisible = true;
            lastRenderKey = null;
        }
    }

    private void renderMirrored(List<String> lines) {
        container.removeAllViews();
        currentLocalIndex = -1;
        minWidthPx = 0;
        minHeightPx = 0;
        for (String line : lines) {
            TextView tv = new TextView(getContext());
            tv.setText(line);
            styleLine(tv, 18f, 0xFFFFFFFF, 1.0f, false);
            clearBlur(tv);
            container.addView(tv);
        }
    }

    private void renderCues(List<SubtitleCue> cues, int currentIdx, int from, int to) {
        container.removeAllViews();
        currentLocalIndex = -1;
        int childIndex = 0;
        for (int i = from; i <= to; i++) {
            SubtitleCue cue = cues.get(i);
            boolean isCurrent = (i == currentIdx);
            if (isCurrent) {
                // 当前高亮行：合并为单个可自动换行的文本段落（宽度不足时强制换行，≤3 行）。
                if (currentLocalIndex < 0) {
                    currentLocalIndex = childIndex;
                }
                TextView tv = new TextView(getContext());
                tv.setText(joinSubtitles(cue));
                // 焦点行：加粗 + 全白，与上下模糊的上下文行拉开层次
                styleLine(tv, BASE_TEXT_SP * CURRENT_SCALE, 0xFFFFFFFF, 1.0f, true);
                tv.setMaxLines(MAX_LINES);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                clearBlur(tv);
                container.addView(tv);
                childIndex++;
            } else {
                for (String line : cue.subtitles) {
                    TextView tv = new TextView(getContext());
                    tv.setText(line);
                    styleLine(tv, 15f, 0x99FFFFFF, 0.35f, false);
                    applyBlur(tv);
                    container.addView(tv);
                    childIndex++;
                }
            }
        }
        measureCurrentCue(cues, currentIdx);
    }

    /** 统一的字幕行样式：居中 + 内边距 + 字号 + 颜色 + 不透明度 + 投影。 */
    private void styleLine(TextView tv, float sizeSp, int color, float alpha, boolean bold) {
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(LINE_PAD_H_DP), dp(LINE_PAD_V_DP), dp(LINE_PAD_H_DP), dp(LINE_PAD_V_DP));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setAlpha(alpha);
        tv.setShadowLayer(TEXT_SHADOW_RADIUS, 0f, dp(1), TEXT_SHADOW_COLOR);
        if (bold) {
            tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
    }

    /** 把当前 cue 的多条字幕合并成一段文本（以空格连接），用于「强制换行 ≤3 行」的度量与渲染。 */
    private static String joinSubtitles(SubtitleCue cue) {
        StringBuilder sb = new StringBuilder();
        if (cue.subtitles != null) {
            for (String s : cue.subtitles) {
                if (s == null) {
                    continue;
                }
                String t = s.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * 测量「当前高亮文本行」的最小宽高：
     *  - 最小宽度 = 使整段文本恰好排成 ≤ MAX_LINES(3) 行所需的最小宽度（二分求得）。
     *    因此窗口宽度可以一直缩到文字开始折成第 3 行；再窄就要第 4 行，于是锁死。
     *  - 最小高度 = 在该最小宽度下换行后的实际文本高度（含 padding）。
     *
     * v20：每边内边距取 {@code max(面板内边距, }{@link #MIN_TEXT_PAD_DP}{@code ) + TextView 自身内边距}，
     * 硬性保证窗口缩到最小时字幕四周仍 ≥15dp 留白。
     */
    private void measureCurrentCue(List<SubtitleCue> cues, int currentIdx) {
        minWidthPx = 0;
        minHeightPx = 0;
        if (currentIdx < 0 || currentIdx >= cues.size()) {
            return;
        }
        SubtitleCue cue = cues.get(currentIdx);
        String text = joinSubtitles(cue);
        if (text.isEmpty()) {
            return;
        }
        float sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                BASE_TEXT_SP * CURRENT_SCALE, getContext().getResources().getDisplayMetrics());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sizePx);
        // 必须与渲染用的字体一致（当前行加粗），否则测量宽度会偏小、导致多出一行。
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;

        // 每边**有效**留白 = 面板内边距 + 文字自身内边距，且不低于 MIN_TEXT_PAD_DP。
        // ⚠️ max 必须作用在"有效值"上：max(13,15)+2 = 17dp 是错的，应是 max(13+2,15) = 15dp。
        int padHSide = Math.max(PANEL_PAD_H_DP + LINE_PAD_H_DP, MIN_TEXT_PAD_DP);
        int padVSide = Math.max(PANEL_PAD_V_DP + LINE_PAD_V_DP, MIN_TEXT_PAD_DP);
        int padW = dp(padHSide) * 2;
        int padH = dp(padVSide) * 2;

        int naturalW = (int) Math.ceil(paint.measureText(text)); // 单行不换行宽度（行数=1，必 ≤3）
        int screenW = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;

        // 二分：找最小的「内容宽度」使行数 ≤ MAX_LINES
        int lo = 1;
        int hi = Math.max(1, naturalW);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lineCount(paint, text, mid) <= MAX_LINES) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        int contentW = lo;

        minWidthPx = Math.min(contentW + padW + 6, screenW); // +6 余量，防字体舍入导致多出一行

        int linesAtMin = lineCount(paint, text, contentW);
        int textH = (int) Math.ceil(linesAtMin * lineH);
        minHeightPx = Math.min(textH + padH, screenH);
    }

    /** 用 Paint.breakText 静态模拟：文本在给定宽度下会折成多少行。 */
    private static int lineCount(Paint paint, String text, int width) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        if (width <= 0) {
            return text.length();
        }
        int n = text.length();
        int i = 0;
        int lines = 0;
        while (i < n) {
            int count = paint.breakText(text, i, n, true, width, null);
            if (count <= 0) {
                count = 1; // 宽度太窄连一个字符都放不下，按 1 字符推进，避免死循环
            }
            i += count;
            lines++;
            if (lines > MAX_LINES + 1) {
                break; // 已超过上限，无需继续
            }
        }
        return Math.max(1, lines);
    }

    /**
     * v20：把 {@link #container} 的上下内边距设为「半个视口高度 + 面板内边距」。
     *
     * 只有内容上下各多出半个视口的空白，ScrollView 才可能把**第一行 / 最后一行**
     * 也滚到窗口正中（否则滚动量被夹在 0 ~ 内容高-视口高，首尾行只能贴边）。
     *
     * @return 本次是否真的改了内边距（改了就需要等一次布局再算滚动位置）
     */
    private boolean applyCenterPadding() {
        if (container == null || scrollView == null) {
            return false;
        }
        int half = scrollView.getHeight() / 2;
        int wantH = dp(PANEL_PAD_H_DP);
        int wantV = half + dp(PANEL_PAD_V_DP);
        if (container.getPaddingTop() == wantV && container.getPaddingBottom() == wantV
                && container.getPaddingLeft() == wantH && container.getPaddingRight() == wantH) {
            return false;
        }
        container.setPadding(wantH, wantV, wantH, wantV);
        return true;
    }

    /** 视口尺寸变化（拖动缩放窗口）→ 刷新居中内边距并立即重新居中。 */
    private void onViewportSizeChanged() {
        applyCenterPadding();
        if (currentLocalIndex >= 0) {
            post(() -> scrollNow(currentLocalIndex, false));
        }
    }

    /**
     * 把「当前高亮行」滚到视口正中。
     *
     * @param localIndex 当前行在 container 中的子 View 下标
     * @param animate    true = 平滑滚动（播放推进时字幕向上滚）；false = 立即跳转（缩放 / 首次定位）
     */
    private void scrollToCurrent(int localIndex, boolean animate) {
        if (localIndex < 0) {
            return;
        }
        post(() -> {
            if (applyCenterPadding()) {
                // 内边距刚变 → 等这次布局把新高度量完，否则滚动目标算不准
                post(() -> scrollNow(localIndex, animate));
            } else {
                scrollNow(localIndex, animate);
            }
        });
    }

    private void scrollNow(int localIndex, boolean animate) {
        int count = container.getChildCount();
        if (count == 0) {
            return;
        }
        View target = container.getChildAt(Math.min(localIndex, count - 1));
        if (target == null) {
            return;
        }
        int viewport = scrollView.getHeight();
        if (viewport <= 0) {
            return;
        }
        int maxScroll = Math.max(0, container.getHeight() - viewport);
        int y = target.getTop() - viewport / 2 + target.getHeight() / 2;
        y = Math.max(0, Math.min(y, maxScroll));
        if (animate) {
            animateScrollTo(y);
        } else {
            cancelScrollAnim();
            scrollView.scrollTo(0, y);
        }
    }

    /** v20：在 {@link #SCROLL_ANIM_MS} 毫秒内减速滚到目标位置（视觉上字幕向上滚动）。 */
    private void animateScrollTo(int targetY) {
        int from = scrollView.getScrollY();
        cancelScrollAnim();
        if (from == targetY) {
            scrollView.scrollTo(0, targetY);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofInt(from, targetY);
        anim.setDuration(SCROLL_ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> scrollView.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnim = anim;
        anim.start();
    }

    private void cancelScrollAnim() {
        if (scrollAnim != null) {
            scrollAnim.cancel();
            scrollAnim = null;
        }
    }

    private void applyBlur(TextView tv) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                tv.setRenderEffect(RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                tv.setAlpha(0.3f);
            }
        }
    }

    private void clearBlur(TextView tv) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tv.setRenderEffect(null);
        }
    }

    private int dp(float v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 永不拦截触摸的 ScrollView：滚动只允许程序调用，触摸一律交回面板做拖动/缩放。
     * 同时关闭滚动条与边缘辉光，避免播放时闪现。
     */
    private class NonInterceptScrollView extends ScrollView {
        NonInterceptScrollView(Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
            setHorizontalScrollBarEnabled(false);
            // v17：不能再调 setScrollbarFadingEnabled(false)！
            // 它的副作用是把 ScrollabilityCache.state 置成 ON（= 常显），
            // 于是每次 scrollTo（比如缩放手柄拖动时 recenterCurrent）都会把滚动条画出来一下。
            // 现在保持默认（可淡出），并用下面的 awakenScrollBars 覆写彻底掐断唤醒路径。
            setScrollBarSize(0);                 // 双保险：即使被画出也是 0 宽，不可见
            setOverScrollMode(OVER_SCROLL_NEVER); // 关掉边缘辉光
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                // v20：窗口尺寸变了 → 半个视口的居中内边距要跟着重算
                FloatingSubtitleView.this.onViewportSizeChanged();
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }

        /**
         * v17：View.scrollTo() 内部会调 awakenScrollBars()，ScrollView 自动滚动/回弹也会。
         * 直接覆写成 false，滚动条状态永远停在 OFF，任何情况下都不会闪现。
         */
        @Override
        protected boolean awakenScrollBars() {
            return false;
        }

        @Override
        protected boolean awakenScrollBars(int startDelay) {
            return false;
        }

        @Override
        protected boolean awakenScrollBars(int startDelay, boolean invalidate) {
            return false;
        }
    }
}
