# 状态栏字幕（StatusBar Subtitle）

> 从 1.21.4 起，本模块在 SystemUI 里注入一条常驻状态栏的字幕。
> 机制源头是 `F:\Desktop\base.apk`（`com.rikumi.colorosmod` 1.0.36）—— 逆向报告见
> 仓库外 `work_baseapk/StatusBarLyric_逆向分析.md`。本文只记**本模块自己的实现与踩坑**。

## 0. 一句话

App 进程（`jp.co.eisys.dlsitesound`）拿到字幕行 → **跨进程广播**送到 SystemUI 进程 →
`StatusBarSubtitleHook` 把它塞进状态栏起始区的一个 `FrameLayout`，
时钟/通知图标让位、通知数换成一个徽标、字幕按 cue 时长单程左移。

```
App: SubtitleRepository ──▶ StatusBarSubtitleBridge.sendLine()
                                  │  广播 ACTION_LINE {line, enabled, durationMs, playing}
                                  ▼
SystemUI: StatusBarSubtitleHook  ──▶ showLine() ──▶ 注入的 FrameLayout(sBadge + sLineView)
```

相关文件：

| 文件 | 职责 |
|---|---|
| `hook/StatusBarSubtitleHook.java` | SystemUI 侧全部逻辑（注入 / 遮挡 / 宽度 / 滚动） |
| `util/StatusBarSubtitleBridge.java` | 广播协议常量 + 发送端 |
| `DlsiteSoundSubtitleModule.java` | 按包名分流：`jp.co.eisys.dlsitesound` 走原逻辑，`com.android.systemui` 走状态栏 |
| `hook/ActivityButtonHook.java` | 悬浮窗按钮长按 1s 开关（app 启动默认关） |
| `app/src/main/assets/xposed_scope` | 自动勾选作用域（含 `com.android.systemui`） |

---

## 1. 实机状态栏关键视图表（ColorOS 16 / 1272×2772 / density 3.0）

一次 `attach` 时的 BFS dump（日志 `---- status bar view tree ----`）得到的**权威结果**：

| 资源名 | 类名 | 用途 |
|---|---|---|
| `status_bar` | `PhoneStatusBarView` | 钩 `onAttachedToWindow` 拿到的根 |
| `seeding_card_container` | **`CapsulePluginContainer`** | ⭐ **流体云 / 实时活动胶囊的容器** |
| `status_bar_start_side_container` | `FrameLayout` | 起始区容器（注入锚点的父） |
| `status_bar_start_side_content_for_fake` | `LinearLayout` | 实际注入锚点（本模块 host） |
| `cutout_space_view` | `Space` | 挖孔占位 |
| `clock` | `StatClock` | 字体/颜色同步源 + 遮挡目标 |
| `notification_icon_area` | `AlphaOptimizedFrameLayout` | 通知图标区，遮挡目标 + 通知数来源 |
| `statusIcons` | `StatusIconContainer` | 状态图标（电池/WiFi…），不遮 |
| `ongoing_activity_chip_primary` / `_secondary` | `FrameLayout` / `SecondaryOngoingActivityChip` | 胶囊的另一种形态 |

> ⚠️ 本模块早期的 `FLUID_IDS` 里那 15 个猜名（`status_bar_fluid_cloud` / `fluidset` / `capsule_view`…）
> **一个都不存在**。真正的名字是 `seeding_card_container` —— ColorOS 的「流体云」在框架内部叫
> **seeding card / PluginSeedling**（系统日志里也是 `PluginSeedling--Origin: LiveAlertInteractor`）。
> 别猜，去 dump。

---

## 2. 显示宽度：照搬 base.apk 的 `updateLyricWidth`

**这是整块功能里最讲究的部分**，直接复刻 `com.rikumi.colorosmod.hooks.StatusBarLyricHooks.updateLyricWidth`
（反汇编还原见下），因为它的行为在真机上被验证过。

### 2.1 算法（原版 + 本模块落点）

设 `left` = 字幕视图左边界、`right` = 右界，都换算到**同一个屏幕坐标系**：

```
right = 宿主容器(host) 右边界                      ① 上限
      = min(right, status_bar_start_side_container 右边界)
limit = min(
  cutout_space_view 左边界                        ② 挖孔（要求 VISIBLE 且宽>0）
  seeding_card_container 内最左可见子视图左界 - 2dp  ③ 流体云（递归 + alpha 过滤）
  [非 ColorOS 兜底] FLUID_IDS / 类名特征 / 几何探测到的胶囊左界 - 4dp
)
if (limit > left && limit < right) right = limit
avail = right - left
if (avail < MIN_LINE_W_DP) → 视为假目标，放弃收缩、保持整宽
if (avail == sAppliedLineWidth) → 直接返回，**不重排**
else → lp.width = avail; setLayoutParams(); syncScrollToAvailableWidth()（按新宽度续滚）
```

三个「精髓」：

1. **递归找最左可见子视图**，而不是直接拿容器左边界：
   胶囊容器自身宽度可能是整行/为 0，真正占位的是它内部那些子 View。
2. **alpha > 0.01 过滤**。胶囊收起/切换时子视图**还留在树里，只是 alpha=0**。
   不排除它们，`limit` 会被压到屏幕最左，字幕宽度直接算成负数（表现为字幕只剩几个字）。
3. **结果缓存 + 变了才重排**。每帧 `setLayoutParams` 会触发布局、和动画打架。

### 2.2 base.apk 的 `updateLyricWidth` 反汇编（供对照）

```
  v1  = host.getLocationOnScreen()[0]              // 基准 X（host 屏幕 x）
  v2  = leftOf(startSide, v1)                      // startSide 相对 host 的 left
  if (startSide instanceof ViewGroup)
      for (child : startSide.children) {
          if (child == lyricView) { v2 += lp.leftMargin; break; }     // 自己：只加 margin
          if (child == null || child.visibility == GONE) continue;    // 隐藏：跳过
          v2 += child.width + lp.leftMargin + lp.rightMargin;         // 前导兄弟占位
      }
  v3  = host.getWidth()                            // 右界上限
  if ((s = findById("status_bar_start_side_container")) != null && s.width > 0)
      v3 = leftOf(s, v1) + s.width
  if ((c = findById("cutout_space_view")) != null && c.visibility == VISIBLE && c.width > 0) {
      x = leftOf(c, v1);  if (x > v2 && x < v3) v3 = x;
  }
  if ((l = findLeftmostVisibleChildLeft(findById("seeding_card_container"), v1, v2)) != MAX) {
      l -= round(dpToPx(2f));  if (l > v2 && l < v3) v3 = l;
  }
  w = v3 - v2
  if (w <= 0) return
  if (w == sLyricMaxWidthPx) return                // ← 缓存，不变不动
  sLyricMaxWidthPx = w;  lyricView.lp.width = w; lyricView.lp.height = MATCH_PARENT; setLayoutParams()
  syncScrollToAvailableWidth()
```

`findLeftmostVisibleChildLeft(group, rootLeft, minValid)`：

```
if (!(group instanceof ViewGroup)) return MAX
best = MAX
for (child : group.children) {
    if (child == null || child.visibility != VISIBLE) continue
    if (child.alpha <= 0.01f) continue                       // ← 关键过滤
    left = leftOf(child, rootLeft)
    if (child.width > 0 && left > minValid && left < best) best = left
    deeper = findLeftmostVisibleChildLeft(child, rootLeft, minValid)
    if (deeper < best) best = deeper                          // ← 递归
}
return best
```

### 2.3 本模块的对应实现

`applyFluidBoundary(boolean force)` + `leftmostVisibleChildLeft()` + `screenX()` + `findById(String[])`；
改动前（1.21.8）是「拿探测到的胶囊左界 − 4dp」，探测靠 15 个猜名 + 类名 + 几何兜底，
**猜名全落空 → 兜底几何挑错目标 → 字幕宽 451px 时钻进胶囊下方约 119px**。

---

## 3. 遮挡时钟 / 通知图标 + 通知数徽标

- 显示时把 `clock`、`notification_icon_area` 三个目标 `setVisibility(GONE)`，
  原值存进 `sHiddenPrev`（`IdentityHashMap`），关闭时还原。
- 三保险防「露出来」：① `hook View.setVisibility` 事件级压制（id 命中且非 GONE 就压回）、
  ② `OnGlobalLayoutListener` 每次布局复检、③ 600ms 心跳 `enforceSuppression()`。
- 徽标 = 实心圆 `GradientDrawable(OVAL)` + 反色数字，颜色跟随时钟色；
  数字来源优先「通知图标容器里可见子视图数」，兜底 `NotificationManager.getActiveNotifications()`。
- 尺寸（1.21.13 起）：直径 `BADGE_SIZE_DP` = **11.05dp**、数字 `BADGE_TEXT_SP` = **7.65sp**
  （原 13dp / 9sp，整体 ×0.85 = 缩小 15%；两个值必须同比缩，否则数字会顶出圆外）。
  `BADGE_GAP_DP`(2dp) **不属于「图标尺寸」**、保持不变；`applyBadgeInset()` 的让位宽度由
  `BADGE_SIZE_DP` 推导，自动从 16dp 收窄到 **1 + 11.05 + 2 = 14.05dp**。
  ⚠️ `static final float` 会被 javac 内联到使用点，所以 dex 里查不到字段名，只能断言浮点常量
  （`buildViews` 里 `const 0x4130cccd # 11.05f`、`applyBadgeStyle` 里 `const 0x40f4cccd # 7.65f`）。

### ⚠️ 坑：徽标比字幕亮一截 —— 别把时钟色的 alpha 补成不透明（1.21.12 修复）

```java
int fill = clockArgb | 0xFF000000;   // ❌ 1.21.11 及以前：强制不透明
```

字幕文字用的是**原样的**时钟色（`onClockColorChanged` → `sLineView.setTextColor(color)`），
而 ColorOS 在深色状态栏上给的是「纯白 **+ alpha**」。那个 alpha 被日志里的
`color & 0x00FFFFFF` 抹掉了 → **从日志上完全看不出来**（记到的是 `#ffffff`）。

Ari 截图（6096px 宽）取亮像素峰值的硬数字 —— 两行对照正好把根因锁死：

| 时钟色 | 徽标填充 | 字幕字形 | 结论 |
| --- | --- | --- | --- |
| `#cccccc`（不透明） | 203.7 | 201.5 | **本来就一致** → 说明取色公式对，只是 alpha 被吃了 |
| `#ffffff`（alpha ≈0.8） | **248.5**（max 255） | 197.5 | 徽标亮一大截 ← Ari：「通知图标太亮了」 |

改法：连 alpha 一并继承；`alpha == 0`（多数 ROM 直接给不透明色）才补 `0xFF`。
`isLight()` 只看 RGB，所以黑白字判定不受影响。

### ⚠️ 坑：还原动作会被自己的压制 hook 吃掉（1.21.9 修复）

```java
// ❌ 错的顺序（1.21.6~1.21.8）
for (e : sHiddenPrev) v.setVisibility(e.getValue());   // 此时 sSuppressed 仍是 true
sHiddenPrev.clear();
sSuppressed = false;
```

`setVisibility(VISIBLE)` 会走 `afterHookedMethod` → 判据 `if (!sSuppressed) return;` 此时为 `true`
→ id 命中 → **立刻改回 GONE**。循环结束再落 `sSuppressed=false` 已经晚了，
何况 `clear()` 把快照也丢了 → **时钟永远回不来**。症状：暂停音频字幕后，状态栏时间一直空着。

正确顺序 + 一道门：

```java
sSuppressed = false;      // 1. 先落旗标
sRestoring = true;        // 2. 开「正在还原」门（hook 里最先判它）
try { /* 逐个 setVisibility(want)，并统计实际 restored 数 */ }
finally { sRestoring = false; }
sHiddenPrev.clear();      // 3. 最后才丢快照
```

---

## 4. 字体 / 颜色 / 阴影

| 项 | 来源 | 更新时机 |
|---|---|---|
| typeface / textSize / letterSpacing / fontFeatureSettings | `StatClock` | 首次显示（`sFontSource` 换实例才重来） |
| **shadow**（radius/dx/dy/color） | `StatClock`，**时钟无阴影就不加** | 同上 |
| textColor | `StatClock.getCurrentTextColor()` | **每次显示 / 心跳 / 布局**都重读 + `hook TextView.setTextColor` 事件镜像 |

- 浅色状态栏（深色图标）下系统会把时钟改成深色 → 字幕必须跟着变黑，否则白字直接隐身。
  `onClockColorChanged(color)` 是统一入口（心跳、镜像 hook、布局三路都打到它）。
- **不要硬加阴影**：1.21.8 及以前硬写 `setShadowLayer(1.5f, 0f, 1f, 0xCC000000)`，
  浅色底上字形会糊出一圈灰边。1.21.9 起改为跟随时钟。
- **颜色日志要节流**：ColorOS 时钟换色带 alpha 渐变动画，`getCurrentTextColor()` 每次都不一样，
  逐帧记日志实测一轮刷 25 行（还把状态栏拖卡）。按 `color & 0xFFFFFF` 去重后再记。

---

### 4.1 字幕左内边距：只在有通知时才给徽标让位（1.21.11 修复）

参考图（Ari 提供的 ColorOS Mod 实机截图，1920px 宽 / 屏宽 1272 -> 1.509x）量出来的硬数字：

| 行 | 最左前景列（图内 x） | 换算屏坐标 |
| --- | --- | --- |
| 「We're a constellation」（**有**通知徽标） | 104（徽标左缘） | 68.9px |
| 「I'll be everywhere you go」（**无**徽标） | 100 | 66.2px |
| 我们那行「真拿你没办法」 | 166 | **110.0px** |

结论：**参考插件在没有通知时，歌词是顶到状态栏最左的**；我们却无条件留出了
`BADGE_SIZE(13dp) + BADGE_GAP(2dp) = 15dp`，正好右移 44px（110 − 66 ≈ 44px = 14.6dp，对得上）。

改法：

- `buildViews()` 里 `sLineView` 的 `marginStart` 从 `LINE_LEFT_PAD_DP + BADGE_SIZE_DP + BADGE_GAP_DP`
  改成只有 `LINE_LEFT_PAD_DP`（= 3px @ density 3.0）；
- 新增 `applyBadgeInset(boolean)`，由 `updateBadge()` 按「徽标是否真的显示」决定要不要补这 15dp；
- 门控：`marginStart` 值没变就直接返回 —— 否则 600ms 心跳会把它变成周期性重排。

正向副作用：`marginStart` 变化会改变可用宽度 -> `OnGlobalLayout` -> `applyFluidBoundary(false)`
->（150ms 去抖）-> `retargetScrollForNewWidth`，字幕自动按新宽度重算目标且**速度不变**（见 §5.2）。

## 5. 滚动

- 不用系统跑马灯（`setSingleLine + ellipsize=MARQUEE` 会**到头弹回起点**），
  改 `ValueAnimator.ofInt` 驱动 `setScrollX`，**单程** 0 → (文本宽 − 可视宽)，
  停到末尾不回弹。
- 时长优先取「该字幕行自身的播放时长」（App 侧按 cue 的 start/end 算出，随广播 `EXTRA_DURATION_MS` 带来），
  夹在 `[SCROLL_MIN_MS, SCROLL_MAX_MS]`，并受 `SCROLL_MAX_SPEED_PX_PER_S` 约束。
- **新行出现后先静置再起滚**：`SCROLL_START_DELAY_MS`（1.21.8 = 100ms → **1.21.9 = 200ms**），
  起滚前校验「这一行还是不是当初那一行」，换行就放弃、由新行自己排。
- 宽度变化（流体云伸缩）→ `restartScrollForNewWidth()`：**从当前 scrollX 按新目标续滚**，
  不回退到行首。

---

### 5.1 ⚠️ 坑：宽度每帧变 → 速度被逐次放大 + 字幕抖一下（1.21.10 修复）

**现象**：流体云（胶囊）从长变短、再到消失的那一两秒里，字幕滚动会**突然加快**；
另外偶尔会在字幕开头**前后抖一下**。

**实机日志（1.21.9，`work_diag_33`）**——640ms 内宽度变了 5 次：

```
22:46:13.094 subtitle width: max 333px (by seeding_card_container(CapsulePluginContainer) left=454)
22:46:13.710 subtitle width: max 329px (by seeding_card_container(CapsulePluginContainer) left=450)
22:46:13.715 subtitle width: max 331px (by seeding_card_container(CapsulePluginContainer) left=452)
22:46:13.726 subtitle width: max 332px (by seeding_card_container(CapsulePluginContainer) left=453)
22:46:13.733 subtitle width: max 333px (by seeding_card_container(CapsulePluginContainer) left=454)
```

胶囊伸缩是**逐帧动画**，宽度每帧都在变 1~2px，`OnGlobalLayout` 每帧触发
`applyFluidBoundary()` → 旧实现的缓存是「宽度**完全相等**才跳过」，1px 也要重排 → 每帧
`setLayoutParams` + 每帧 `restartScrollForNewWidth()`。

**根因 ①｜速度被放大（问题 1）** —— 旧代码这样算续滚的剩余时间：

```java
long total = sScrollDurMs;                                   // 上一轮的「剩余」时间
int  oldTarget = sScrollTarget;                              // 却是「全程」距离
long remain = (long)(total * (target - cur) / (double) oldTarget);
```

分子用剩余时间、分母用全程距离 → 反推出的速度比实际**大**。更糟的是下一次 restart 把
`sScrollDurMs` 换成刚算出的 `remain`（又变小了），而 `sScrollTarget` 依旧是全程距离 ——
**每 restart 一次速度放大一档**，连打 5 次后字幕就飙起来。

> 教训：任何「速率」都只能从**一次**基准算出来并**存下来**，不能在重入路径上用
> 「当前剩余量」反复反推。剩余时间 = 剩余距离 ÷ 存下来的速度。

**根因 ②｜抖动（问题 2）** —— 同一个每帧重排的副产品：每帧 `cancelScroll()` +
`animateScrollTo()`，视觉上就是抖。另外旧代码在「变宽后 `target<=0`」和「`cur>=target`」
两个分支里会 `setScrollX(target)` —— 那是**瞬移**（跳回行首 / 倒退），抖得更明显。

**根因 ③｜`left` 基线带滚动偏移** —— 旧代码 `left = screenX(sLineView) - base`，
而 `sLineView` 正在滚动，同一右界算出了两个宽度：

```
22:45:27.170 subtitle width: max 307px (by cutout_space_view left=585)
22:45:27.179 subtitle width: max 463px (by cutout_space_view left=585)   <- 差 156px
```

**修复（1.21.10）**

| 项 | 做法 |
| --- | --- |
| 绝对速度 | `startScroll()` 时定死 `sScrollSpeedPxPerMs = target / dur`；续滚只做 `remain = (target - cur) / speed`，再夹速度上限与 200ms 下限 |
| 目标迟滞 | `\|newTarget - sScrollTarget\| < 8px` → 只更新记录，**不重启动画** |
| 宽度迟滞 | `\|newWidth - sAppliedLineWidth\| < 6px` → 直接 return，不重排 |
| 去抖 | 非 `force` 的宽度变化延迟 150ms 合并（`WIDTH_TASK`），动画期间只落地最后一次 |
| 不瞬移 | 变宽后 `target<=0` 或 `cur>=target` → **停在当前位置**，`cancelScroll()` 即可，绝不跳回行首/倒退 |
| left 基线 | 改用 `screenX(sContainer) + sLineView.getLeft()`（布局坐标，不含 `scrollX`） |
| 目标宽度 | 宽度刚写进 LayoutParams 还没 layout，`getWidth()` 是旧值 → `computeScrollTarget(newWidthPx)` 显式传入 |

新日志：

```
subtitle width: max 333px (by seeding_card_container(...) left=454) (prev=463px)
scroll retarget: 120->408px remain=3712ms speed=78px/s
```

看 `speed=` 那一行：同一行字幕从开始到结束，这个值应当**基本不变**；如果一次滚动里
它逐次变大，说明又退回了「反推速度」。

### 5.2 宽度变化不再重启动画：动态目标 + 同速续滚（1.21.11 修复）

1.21.10 把速度公式改对了（不再反推），但**动作仍然是「cancel + 重启动画」**：宽度每变一次就取消
当前动画、按 `remain=(target-cur)/speed` 重新起一段。两个问题：

1. `remain` 被 `200ms` 下限与 `420px/s` 上限夹过之后，**实际速度已经不等于原速度**；
2. 流体云伸缩是逐帧动画（640ms 内能变 5 次），一次伸缩就反复重启好几次 ->「速度变了 / 顿一下」。

Ari 的需求口径（对齐 ColorOS Mod 观感）：

> 胶囊从无到出现时，字幕最右端缩到胶囊最左边，**但是字幕滚动速度不变**，继续按照原速度往前滚动；
> 当新字幕行出现时，即使字幕未播放完也无所谓。

改法：把「目标」变成**每帧可读的动态量** `sLiveTargetPx`，宽度变化只更新它（外加裁剪宽度）：

```
宽度变化 -> retargetScrollForNewWidth(width)
             |- 只写 sLiveTargetPx / sScrollTarget，**不 cancel、不重算速度、不 setScrollX 瞬移**
             |- 目标变小（胶囊收缩 / 消失）-> animateScrollTo 的 update 每帧比对：
             |    cur >= sLiveTargetPx 就停在当前位置（绝不倒退），并 sScrollGen++ 标记「主动停」
             `- 目标变大（胶囊出现把右界推窄）-> 本段动画**自然跑完**后，onAnimationEnd 里
                  按**原速度**续滚：remain = (sLiveTargetPx - cur) / sScrollSpeedPxPerMs
```

配套要点：

| 点 | 内容 |
| --- | --- |
| 速度只定一次 | `startScroll()` 里 `speed = target/dur` 定死；续滚只做「距离 ÷ 速度 = 时间」 |
| 绝不追赶 | 不做「剩余时间不够就提速」——需求明确说没播完也无所谓 |
| `sScrollArmed` | 本行是否已起滚。false 期间（新行还停在 `postScrollWhenLaidOut`）的宽度变化直接忽略，交给 `startScroll` 用新宽度自己重算，避免在没 layout 时起滚 |
| 新行作废旧速度 | `showLine` 换行时 `sLiveTargetPx=0` / `sScrollSpeedPxPerMs=0` / `sScrollArmed=false` |
| `sScrollGen` 代次 | `cancelScroll()` 与「到线自停」都自增；`onAnimationEnd` 里 `gen != sScrollGen` 就不续滚 |
| ⚠️ `Animator` 没有 `isCancelled()` | 想区分「自然跑完（该续滚）」和「被我们取消（不续滚）」**不能靠它**：它只住在 `ValueAnimator` 上，而 `onAnimationEnd` 的入参是 `Animator`，写 `anim.isCancelled()` **编译期直接报找不到符号**（1.21.11 第一次构建就撞上了）。改用上面的代次 |

实机日志（同一行字幕内，`speed=` 应保持不变）：

```
scroll: target=408px dur=5200ms (viewW=530)
scroll retarget: 408->333px cur=120 speed=78px/s     <- 胶囊出现，目标变小
scroll retarget: 333->408px cur=333 speed=78px/s     <- 胶囊消失，目标变大
scroll continue: 333->408px in 962ms speed=78px/s    <- 跑完后按原速续滚
```

## 6. 广播协议

由 `util/StatusBarSubtitleBridge` 定义：

| 常量 | 说明 |
|---|---|
| `ACTION_STATUSBAR_SUBTITLE_LINE` | 送一行字幕；额外携带 `EXTRA_ENABLED` / `EXTRA_DURATION_MS` / `EXTRA_PLAYING`（SystemUI 重启后首条即可恢复，不必等开关广播） |
| `ACTION_STATUSBAR_SUBTITLE_ENABLED` | 只切开关；关掉时立刻隐藏并还原 |

> ⚠️ **Android 13+（API 33+）必须带可见性标志**：App 进程与 SystemUI 进程 UID 不同，
> 收端注册必须用 `Context.RECEIVER_EXPORTED`，否则 `registerReceiver` 直接抛 `SecurityException`、
> 一个字都收不到。1.21.5 曾因此完全不显示，而修复又因「同文件多次 Edit 互相覆盖」丢失过一次。

### 6.1 ⚠️ 坑：切到无字幕音轨后按钮底色留在绿色 —— 口径必须**一起判等**（1.21.13 修复）

悬浮窗按钮的绿色底色（= 状态栏字幕开）直接读 `StatusBarSubtitleBridge.sAppEnabled`，
而这个开关有**两条**改动路径，其中只有一条会顺带重画按钮：

| 路径 | 谁调用 | 有没有重画按钮底色 |
|---|---|---|
| 长按 1s 翻转 | `ActivityButtonHook` 的长按任务（`updateButtonDrawable` 紧跟其后） | ✅ |
| 「无字幕」强制作废 | `SubtitleRepository` 的仓库观察者（`DlsiteSoundSubtitleModule`） | ❌ 调用方**根本不知道按钮存在** |

更深一层：`applyButtonText()` 的「没变化就不折腾」原先只比 `text` 与 `alpha`
（`text.equals(sLastBtnText) && sButton.getAlpha() == alpha`），**底色的变化被漏掉**；
而按钮自己的仓库观察者注册得比模块观察者**早**（`ActivityButtonHook.hook()` 在第 2004 行，
模块的在 `DlsiteSoundSubtitleModule` 第 62 行），先跑的那个必然早退 → 之后再没人重画。

实机现场（Ari 2026-09-17 16:09）：

```
16:09:24.662 [Core] track changed via ...setMediaSources | lastJson=272052ms ago | cues=134 -> SUSPEND, wait 3000ms
16:09:25.1xx        （500ms 延时路径落笔「无字幕」；此刻 sAppEnabled 仍为 true -> 底色刷成绿）※1.21.14 起这里改成普通色
16:09:27.664 [Core] track decision: NO subtitles for this track (cues were 134)
16:09:27.667 [Core] status bar subtitle force-off (no subtitles)   ← sAppEnabled 已变 false，但没人重画
16:09:40            截图：按钮文字「无字幕」、底色仍是绿
```

**像素级铁证**：截图里按钮填充 `rgb(24,119,97)`，而
`0.6 × BUTTON_BG_STATUSBAR(30,185,128) + 0.4 × 背景(15,19,48) = (24.0, 118.6, 96.0)` —— 分毫不差
（0.6 是「无字幕」态 `sButton.setAlpha(0.6f)` 的结果；背景取按钮同水平带的实测均值）。
对照 `BUTTON_BG_ACTIVE`(58,57,104) 的合成值是 (40.8, 41.8, 81.6)，偏蓝紫、明显不符。

修法两条腿走路（只改 `ActivityButtonHook` + `StatusBarSubtitleBridge`）：

1. **底色收敛到唯一口径**：`buttonBgMode(repo, noSub)` → `0 普通 / 1 活跃 / 2 状态栏绿`，
   `createButtonDrawable(int)` 按 mode 查色；`applyButtonText()` 把它**一起纳入判等**
   （新增字段 `sLastBtnBgMode`），`updateButtonDrawable()` 也走同一函数 —— 谁都不可能再算歪。
   （旧签名 `createButtonDrawable(boolean)` 随之删除。）
2. **给 Bridge 加开关变化监听** `setEnabledListener(Runnable)`：`toggleAppEnabled()` 与
   `forceDisabledWhenNoSubtitles()` 翻转后都回调一次，按钮侧
   `uiHandler.post(() -> updateButtonDrawable(repo))`。这条链路与观察者**互相独立**，不依赖谁先跑。

> **通用教训：同一个状态、多个 UI 读取方 → 口径必须一起判等，不能只比其中两项。**
> 「没变化就不折腾」这类去重优化，一旦判等字段少于实际输入字段，就会漏掉真实变化 ——
> 1.21.12 漏的是 `isSuspended()`（文字口径），1.21.13 漏的是 `sAppEnabled`（底色口径），
> **同一个坑的第二次**。反过来，凡是「底色/文字」这类由多个来源拼出来的 UI 状态，
> 就该有一个**唯一计算函数**，而不是每处各算一份。

### 6.2 ⚠️ 坑：判等都对了，但两个属性用了**不同粒度的口径**（1.21.14 修复）

1.21.13 把「谁会重画」修好了，可绿色**什么时候**消失还是挂在开关真源上 ——
文字与底色分别绑在两个**更新节奏差 2.5 秒**的量上：

| 按钮属性 | 口径 | 变化时刻 |
|---|---|---|
| 文字 `无字幕` / `悬浮开` | `noSub = !hasSubtitles() \|\| isSuspended()` | 换轨后 **~0.5s**（`BUTTON_NO_SUB_DELAY_MS` 预压缩） |
| 底色 绿 → 普通 | `StatusBarSubtitleBridge.sAppEnabled` | 换轨后 **3000ms**（等 `NO_SUBTITLE_GRACE_MS` 裁决完、`forceDisabledWhenNoSubtitles()` 才把它翻成 false） |

实测时间差：`16:09:24.662` 换轨 SUSPEND → `16:09:27.664` 裁决 = **3002ms**。
用户看到的就是「字很及时、色慢三秒」——同一块按钮上两条口径不同步。

**修法（一行条件，但想清楚才敢改）**：绿色额外要求「当下确实有字幕可显示」

```java
private static int buttonBgMode(SubtitleRepository repo, boolean noSub) {
    if (!noSub && StatusBarSubtitleBridge.sAppEnabled) {   // ← 1.21.14 加的 !noSub
        return BTN_BG_MODE_STATUSBAR;
    }
    return (!noSub && repo.isFloatingWindowOpen()) ? BTN_BG_MODE_ACTIVE : BTN_BG_MODE_NORMAL;
}
```

- 显示口径与文字**同出一个 `noSub`**、同一时间点落笔（~0.5s）；
- `sAppEnabled` 的**持久状态**照旧等 3000ms 数据裁决 —— **绝不能把 force-off 提前**：
  新音轨的字幕 JSON 最晚几百毫秒才到，提前作废会把「其实有字幕」的音轨的状态栏字幕**误关**，
  用户得再长按一次才能恢复；
- 顺带语义也更对：无字幕时 `canToggle()` 本来就是 false（长按无效），绿色会是个谎。

配套：`applyButtonText()` 落笔时若 `bgMode` 变了就打一行
`[Button] button bg: normal (noSub=true, statusBarOn=true)`，出问题时直接和 `track changed … SUSPEND`
两行对时间差，不必再靠截图。

> **通用教训：一个界面元素的全部视觉属性必须收敛到**同一个量**。
> 1.21.13 的教训是「判等字段要覆盖全部输入」，1.21.14 是它的下一层 ——
> 字段都在判等里了，但**两个字段本身刻画的是不同粒度的事实**（一个是「此刻有没有内容」，
> 一个是「用户有没有把这个功能打开」），照样会错位。
> 排查顺序：先问「这两个属性分别由谁在什么时刻写」，再问「它们判等了没有」。

### 6.3 ⚠️ 坑：切轨时状态栏字幕「自己关闭」—— 拿推断去改写用户意图 + 假阴性裁决（1.21.15 修复）

Ari 2026-09-17 18:20 报「有时切换新音轨时，状态栏字幕会自己关闭」。表现是按钮绿色消失、
状态栏字幕不再出现，**而且不会自己回来**，必须重新长按。日志里四次
`status bar subtitle force-off (no subtitles)`：`17:22:05` / `17:35:05` / `18:00:34` / `18:20:28`。

两个根因不在同一层，得分开治。

#### 根因 A：拿「数据推断」去改写「用户意图」

`forceDisabledWhenNoSubtitles()`（1.21.11 引入）在仓库判定「本音轨无字幕」时，
把 `sAppEnabled` 强制翻成 false。问题在于**同一个事实被两个 UI 读到，却只有一边能自愈**：

| 被自动关掉的东西 | 自动恢复路径 |
|---|---|
| 悬浮窗（`autoClosedForNoSubtitle`） | ✅ 字幕晚到 → `loadFromJsonArrayInternal()` 自动重开 |
| 状态栏字幕开关（`sAppEnabled`） | ❌ **一条都没有**，只能用户再长按一次 |

最刺眼的一次（`18:00:33.391` 长按打开，**961ms 后**就被自己关掉）：

```
18:00:30.202 [Core]   track changed via ...getCurrentTrackIndex 1->2 | cues=152 -> SUSPEND, wait 3000ms
18:00:31.348 [Core]   track changed via ...getCurrentTrackIndex 2->1 | cues=152 -> SUSPEND, wait 3000ms
18:00:33.391 [Button] status bar subtitle ON (long-press 1s)        ← 用户刚长按打开
18:00:34.349 [Core]   track decision: NO subtitles for this track (cues were 152)
18:00:34.352 [Core]   status bar subtitle force-off (no subtitles)  ← 961ms 后就被自己关掉
```

而这条强制作废**本来就是多余的** —— 它想达到的两个效果都已经有别的东西在管：

- 「无字幕时状态栏不挂字幕」→ `sendCurrentFromRepo()` 算出 `line=""`，SystemUI 侧
  `showLine()` 走 `HIDE_TASK` → `hideSubtitleNow()` 把整个 `sContainer` 设为 `GONE`
  （通知数徽标 `sBadge` 是 `sContainer` 的子视图，一起消失）并 `applySuppression(false)` 还原时钟；
- 「按钮底色回归普通色」→ `buttonBgMode()` 里的 `!noSub` 已经覆盖。

**修法**：删掉 `forceDisabledWhenNoSubtitles()` 及两个调用点（模块观察者、按钮侧），
让 `sAppEnabled` 回到类注释里原本写的语义 ——「唯一真源在 App 进程，默认关闭，**只由长按翻转**」。
数据侧此后只决定「当下有没有内容可显示」，不再有权改写用户意图。

#### 根因 B：「3s 内没等到 JSON ⇒ 该音轨无字幕」是假阴性

字幕 JSON 的到达延迟实测跨度极大：缓存命中时 ~0.2s，长时间空闲后首次切轨的冷请求要 5~15.6s；
而裁决窗固定 3000ms。于是「其实有字幕」的音轨被判无字幕。三条都能和后面的 `Loaded` 对上：

| 判「无字幕」 | 同一音轨的 JSON 到达 | 延迟 |
|---|---|---|
| `18:20:25.879`（cues were 55） | `18:20:31.355` Loaded **105** cues | 5.5s |
| `17:35:02.521`（cues were 81） | `17:35:07.812` Loaded **152** cues | 5.3s |
| `17:22:02.745`（cues were 81） | `17:22:16.824` Loaded **81** cues | 11.1s |

**修法**：裁决窗**分级** —— 进入待确认时若已有缓存 cues（说明这部作品是有字幕的），
窗口给 `NO_SUBTITLE_GRACE_MS_CACHED` = 10000ms；cues 本来就是空（用户一直待在无字幕音轨上）
则维持 3000ms 结案，零代价：

```
track changed via ... 1->2 | cues=152 -> SUSPEND subtitles, wait 10000ms (had cues)
track changed via ... 1->2 | cues=0   -> SUSPEND subtitles, wait 3000ms
```

⚠️ 为什么能用「已有缓存 cues」当判据：待确认期间 `getCues()/getCurrentSubtitles()` 一律返回空
（`pendingTrackDecision` 门控），所以**窗口期内 UI 早就按「无字幕」显示了**，
把窗口拉长不改变用户看到的任何东西，只影响「JSON 始终不来时什么时候下结论」。

配套取证：判无字幕时记 `lastNoSubtitleVerdictMs`；若 60s 内字幕 JSON 又到了，打一行

```
[Core] track decision was a FALSE NEGATIVE: subtitle json arrived 5533ms after the "no subtitles" verdict -> cues=105 (this track does have subtitles)
```

—— 下次这类问题不用再人肉对时间线，日志直接点名。

> **通用教训（两层）**
>
> 1. **「用户意图」和「数据推断」必须分开存放。** 推断会错、而且会反复错；拿一个可能出错的推断
>    去改写用户**显式表达过**的状态，一旦错了就是「功能自己关了」，用户还找不到原因（他没有关过）。
>    正确姿势：推断只影响**显示**（有内容就显示、没内容就空），用户开的开关只由用户关。
> 2. **「超时」不是「不存在」的证据。** 把「N 秒内没等到」当成「就是没有」，等于对延迟分布做了
>    一个假设（这里假设 ≤3s，实测最长 15.6s）。写这类窗口前先量一下真实分布；量不到就把窗口
>    调到明显大于观测最大值，或者**只降级显示、不销毁数据**。

---

---

## 7. 排障手册

### 换 ROM / 全新系统：先看 compat self-check

1.21.10 起 `attach()` 会打两行自检（`work_diag_33/patch_compat_selfcheck.py` 引入）：

```
compat self-check: root=PhoneStatusBarView host=LinearLayout anchor=id:status_bar_start_side_content_for_fake clock=id:clock(x1) notif=id:notification_icon_area(x1) seeding=id:seeding_card_container(CapsulePluginContainer) side=id:status_bar_start_side_container cutout=id:cutout_space_view
compat OK: 四关全命中（仅指 id/类名探测成功，未做实机视觉验证）
```

每个字段的含义：

| 字段 | 正常值 | 该怎么读 |
| --- | --- | --- |
| `root` / `host` | 状态栏根类名 / 注入到的容器类名 | `MISSING` = 锚点都没找到，整体不生效 |
| `anchor` | `id:xxx` | `fallback:parentOf(...)` = 资源名没命中、走了「时钟的父容器」兜底；`MISSING` = 两路都失败 |
| `clock` | `id:clock(x1)` | `MISSING` = 字体/颜色同步与时钟遮挡都失效 |
| `notif` | `id:notification_icon_area(x1)` | `MISSING` = 遮不住通知图标、徽标数字也不准 |
| `seeding` | `id:seeding_card_container(...)` | `none` = 不是 ColorOS，胶囊避让只能靠几何兜底 |
| `side` / `cutout` | 各自的资源名 | `none` 不影响出字幕，只是右界少一层约束 |

有缺失时会多打一行：

```
compat MISSING: anchor,seeding, -> 本 ROM 需补 id：把上面 tree[N] 里的 id=xxx 发回来即可
```

**补 id 的流程**（不用重新逆向）：翻同一份日志里 `---- status bar view tree ----` 的
`tree[N] 类名 id=xxx`，找到对应视图，把名字填进 `ANCHOR_IDS` / `CLOCK_IDS` /
`NOTIF_AREA_IDS` / `SEEDING_IDS` / `SIDE_CONTAINER_IDS` / `CUTOUT_IDS` 就行。
1.21.9 就是这么一次性挖出 `seeding_card_container` 的（在那之前猜的 15 个候选名全错）。

> `describe()` 在 id 不命中候选名单时会输出 `类名#0x十六进制id`，例如
> `StatClock#0x7f0a0123` —— 拿这个十六进制 id 去对 tree dump 里的 `id=xxx` 也能定位。

**跨系统可行性**（只在 ColorOS 16 实测过，其余为代码兜底链推断）：

| 系统 | 判断 | 卡点 |
| --- | --- | --- |
| ColorOS / realmeUI / 一加 | 可用（实测） | — |
| 安卓原生 / 类原生 | 希望最大 | AOSP 标准 id 基本齐全；胶囊避让待验证（有 `ongoing_activity_chip_primary` + 几何兜底） |
| 小米澎湃 OS | 有机会 | **瓶颈不在代码在 root**：BL 解锁门槛高；另外后台管控狠，App 进程被杀就没字幕 |
| vivo OriginOS | 基本不可用 | BL 长期不解锁 → 装不了 LSPosed → 模块进不去 SystemUI |
| 鸿蒙 | 不适用 | 非 Android SystemUI |

---

「一个字都不显示」按顺序查：

1. LSPosed 里模块作用域**勾了 `com.android.systemui`** 没有（manifest 的 `xposed_scope` 只是省事，不一定生效）。
2. `hook() enter, module build=X.Y.Z` —— 版本对不上 = 装了旧包。
3. `hooked com.android.systemui.statusbar.phone.PhoneStatusBarView` 有没有。
4. `receiver registered (RECEIVER_EXPORTED)` 有没有（没有 → 广播通道断）。
5. `attached to status bar (host=…, clock=…, notifArea=…)` 有没有（没有 → 锚点没找到）。
6. 播一段有字幕的音，看 `subtitle color <- clock:` / `scroll: target=` / `subtitle width:`。

「字幕被胶囊压住」→ 看 `subtitle width:` 那行里的 `by xxx`：
- `by seeding_card_container(CapsulePluginContainer)` = 正常避让；
- `side container only` = 只用了宿主右界，**没探测到胶囊**（胶囊当时可能没在显示）；
- `candidate too close (Npx) -> keep full width` = 探测到假目标被放弃（把候选贴进日志再校）。

「暂停后时钟不回来」→ 看 `system parts restored (clock + notification icons), restored=N`：
`restored` 明显小于 `system parts hidden` 时的目标数，说明还原又被打回去了（见 §3 的坑）。

「字幕滚动速度忽快忽慢 / 伸缩胶囊时窜一下」→ 看 `scroll retarget:` 与 `scroll continue:` 里的
`speed=Npx/s`：**同一行字幕内 N 必须恒定**。N 一路变大 = 又回到「反推速度」了；N 变小 = 又被 200ms 下限夹了。
宽度变化只应改变 `target=`，不该改变 `speed=`（见 `docs/statusbar-subtitle.md` §5.2）。

「字幕比参考插件右移一个图标位」→ 看 `line left inset -> Npx (badge shown|hidden)`：
没有通知时应该是 `LINE_LEFT_PAD_DP`（density 3.0 下 = 3px）；如果还是 48px，说明 `applyBadgeInset(false)`
没被执行（或 `updateBadge()` 的 `count == sBadgeCount` 门把首次调用挡了）。

「按钮显示『无字幕』但状态栏还在显示字幕」→ 应当能在 App 日志里看到
`status bar subtitle force-off (no subtitles)`；长按应当只打印 `long-press ignored: no subtitles` 且按钮不变色。

