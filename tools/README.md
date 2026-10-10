# `tools/` 索引

> 全部脚本**只读**，不改任何文件。
> 汇总入口：`python tools/run_all_verify.py`（任一 FAIL ⇒ 退出码非 0）
> 🔴 **脚本清单与项数的单点真源 = `python tools/run_all_verify.py --list`**；
>   下面 §脚本明细 是人类可读的补充说明、可能滞后于最新几轮，**不要当清单副本引用**。

---

## 一键跑全部

```bash
python tools/run_all_verify.py# 全部
python tools/run_all_verify.py 995 protocol   # 只跑名字含这些关键字的
python tools/run_all_verify.py --list         # 只列清单不执行
```

脚本会**自动找出 APK 并按每个 dex 脚本期望的 code 精确配包**，
不需要手动传路径（漏配对照包会在输出里显式提示，且**不算失败**）。

---

## 三层验证与脚本对照

项目的验证链分五层，每层对应的脚本如下：

| 层 | 验什么 | 脚本 | 当前结果 |
| --- | --- | --- | --- |
| **① 单元测试** | 纯函数内核的黄金向量（纯 JVM，不碰 Android） | `app/src/test/` · `GoldenVectorTest` | **57/57** |
| **② 源码层** | 单点真源、字面量归零、转发壳完好 | `verify_protocol_src.py` | **10/10** |
| **③ dex 字节码层** | 改动**确实编进了 APK** | `verify_995.py` · `verify_994.py` | **28/28（锚 22）** · **18/18** |
| **④ 资源层** | aapt2 读编译后资源（float 有精度损失 ⇒ 必须容差） | `verify_pathdata.py`（矢量图） | 按需 |
| **⑤ 真机日志** | 真实行为是否与判据一致 | `verify_994_log_window.py` | **8/8** |

---

## 脚本明细

### `verify_1002_src.py` — **源码层**（code 1002 背景亮度改为用户可设定）

```
用法:  python tools/verify_1002_src.py     （在仓库根执行，不接参数）
```

**27 项**，四类：

| 类 | 验什么 | 为什么值钱 |
| --- | --- | --- |
| ① **配置项读写点齐全** | 一个键必须出现在 **9~10 个**地方：K 常量 / 字段 / copy / equals / clamp / json 写读 / prefs 写读 / summary | 少一处就是「某个通道上丢配置」（设置页改了、重启后变回去），而**编译全绿、日志也看不出** |
| ② **映射口径** | `L = pct / 100` 的**除数**逐处断言必须是 `100f` | 写成 255 / 1000 会整体偏亮偏暗，代码照样编译、照样"看起来在工作" |
| ③ **可达性** | 两个新键必须同时出现在 `refreshStyle()` 的重设判据与 `refreshStyleIfStale()` 的自愈判据里 | 铁律 51 —— 1001 就是这么漏的 |
| ④ **两档行为** | 自动档仍走 `smoothedLum`（零回归）· 手动档在 `applyPanelBackground` 里**覆盖**继承来的采样亮度 | 不覆盖 ⇒ 重建一次玻璃就"变回自动" |

---

### `verify_1002.py` — dex 字节码层（code 1002 背景亮度改为用户可设定）

```
用法:  python tools/verify_1002.py <apk_1002> <apk_1001>
```

**21 项 / 13 个有区分力锚**（本轮是纯新增，锚全是「1002 有 / 1001 无」）。

最有价值的四条是**指令层**的（字符串层验不出「挂在哪儿」）：

| 判据 | 证据 |
| --- | --- |
| `applyPanelBackground` 体内读 `SubtitleStyle.liquidGlassBackdropAuto` | `iget-boolean v6, v0, L…/SubtitleStyle;.liquidGlassBackdropAuto:Z` |
| 同体内读 `liquidGlassBackdropLum` **且随后** `setBackdropStats` | `iget …LiquidGlassBackdropLum:I` → `invoke-virtual …LiquidGlassDrawable;.setBackdropStats` |
| `syncBackdropCapture` 体内读 `backdropAuto` | 手动档降采样频率 |
| `refreshStyle` 判据体内读两个新字段 | 判据覆盖（铁律 51）|

---

### 🔴 两条「写验证脚本」的硬教训（code 1002 实测）

**① 抽符号前必须剥注释**（`verify_1001_src.py` 栽过）

`verify_1001_src.py` 从 `applyPanelBackground` 抽 `st.xxx` 组成"消费集"。
我在这段代码旁边写了句说明：「这里刻意写成 `st.字段` 而不是调 helper」——
**注释里的 `st.字段` 被正则当成了"消费的键"** ⇒ 报「缺 xxx / 字段 两个键」⇒ **假 FAIL**。

⇒ 已加 `strip_comments()`：**凡"从源码抽符号/数字"的判据，都要先剥注释**。
⚠️ 反直觉的地方在于：**这条注释写得越详细越容易自伤** —— 它本来是为了防止别人改错。

**② 片段匹配不抗换行**（`verify_1002_src.py` 栽过）

「配置项读写点齐全」用片段匹配，而 `optBoolean(K_…,` 被 IDE 换行成两行 ⇒
一个**已经写好的**读写点被报成「缺 json 读 / prefs 读」⇒ 假 FAIL。
⇒ 已加 `norm()`（连续空白折成单个空格）。凡"某片段是否存在"的判据都要过一遍它。

⚠️ 两条共同的形状：**判据的"字符级形态"比"语义"脆弱** ——
换行、注释、引号风格一变就可能假红/假绿。⇒ 涉及源码文本的判据，先归一化再匹配。

---

### `verify_1002_src.py` — **源码层**（code 1002 背景亮度改为用户可设定）

```
用法:  python tools/verify_1002_src.py     （在仓库根执行，不接参数）
```

**27 项**，四类：

| 类 | 验什么 | 为什么值钱 |
| --- | --- | --- |
| ① **配置项读写点齐全** | 一个键必须出现在 **9~10 个**地方：K 常量 / 字段 / copy / equals / clamp / json 写读 / prefs 写读 / summary | 少一处就是「某个通道上丢配置」（设置页改了、重启后变回去），而**编译全绿、日志也看不出** |
| ② **映射口径** | `L = pct / 100` 的**除数**逐处断言必须是 `100f` | 写成 255 / 1000 会整体偏亮偏暗，代码照样编译、照样"看起来在工作" |
| ③ **可达性** | 两个新键必须同时出现在 `refreshStyle()` 的重设判据与 `refreshStyleIfStale()` 的自愈判据里 | 铁律 51 —— 1001 就是这么漏的 |
| ④ **两档行为** | 自动档仍走 `smoothedLum`（零回归）· 手动档在 `applyPanelBackground` 里**覆盖**继承来的采样亮度 | 不覆盖 ⇒ 重建一次玻璃就"变回自动" |

---

### `verify_1002.py` — dex 字节码层（code 1002 背景亮度改为用户可设定）

```
用法:  python tools/verify_1002.py <apk_1002> <apk_1001>
```

**21 项 / 13 个有区分力锚**（本轮是纯新增，锚全是「1002 有 / 1001 无」）。

最有价值的四条是**指令层**的（字符串层验不出「挂在哪儿」）：

| 判据 | 证据 |
| --- | --- |
| `applyPanelBackground` 体内读 `SubtitleStyle.liquidGlassBackdropAuto` | `iget-boolean v6, v0, L…/SubtitleStyle;.liquidGlassBackdropAuto:Z` |
| 同体内读 `liquidGlassBackdropLum` **且随后** `setBackdropStats` | `iget …LiquidGlassBackdropLum:I` → `invoke-virtual …LiquidGlassDrawable;.setBackdropStats` |
| `syncBackdropCapture` 体内读 `backdropAuto` | 手动档降采样频率 |
| `refreshStyle` 判据体内读两个新字段 | 判据覆盖（铁律 51）|

---

### 🔴 两条「写验证脚本」的硬教训（code 1002 实测）

**① 抽符号前必须剥注释**（`verify_1001_src.py` 栽过）

`verify_1001_src.py` 从 `applyPanelBackground` 抽 `st.xxx` 组成"消费集"。
我在这段代码旁边写了句说明：「这里刻意写成 `st.字段` 而不是调 helper」——
**注释里的 `st.字段` 被正则当成了"消费的键"** ⇒ 报「缺 xxx / 字段 两个键」⇒ **假 FAIL**。

⇒ 已加 `strip_comments()`：**凡"从源码抽符号/数字"的判据，都要先剥注释**。
⚠️ 反直觉的地方在于：**这条注释写得越详细越容易自伤** —— 它本来是为了防止别人改错。

**② 片段匹配不抗换行**（`verify_1002_src.py` 栽过）

「配置项读写点齐全」用片段匹配，而 `optBoolean(K_…,` 被 IDE 换行成两行 ⇒
一个**已经写好的**读写点被报成「缺 json 读 / prefs 读」⇒ 假 FAIL。
⇒ 已加 `norm()`（连续空白折成单个空格）。凡"某片段是否存在"的判据都要过一遍它。

⚠️ 两条共同的形状：**判据的"字符级形态"比"语义"脆弱** ——
换行、注释、引号风格一变就可能假红/假绿。⇒ 涉及源码文本的判据，先归一化再匹配。

---

### `verify_1001_src.py` — **源码层**（code 1001 调参链路可达性不变量）

```
用法:  python tools/verify_1001_src.py     （在仓库根执行，不接参数）
```

🔴 **这个脚本验的是一条通用不变量，不只针对某两个键**：

> `applyPanelBackground()` **消费**的每一个配置键，
> 都必须出现在 `refreshStyle()` 的「重建判据」或「重设判据」里。

否则：用户拖那个键 ⇒ 判据全 false ⇒ **什么都不发生**，而编译全绿、
`style refreshed` 日志照打（因为外层判据命中了）—— 这正是 code 1000 交付后的真实现象。

做法：
1. 从 `applyPanelBackground()` 抽 `st.xxx` ⇒ **消费集**
2. 从判据块抽 `old.X != s.X` ⇒ **覆盖集**
3. 断言 **消费 ⊆ 覆盖**（21 项），并对每个键做「只改这一个键」的**逐键模拟**

⚠️ **反向对照已做**：把 `else-if` 那段删掉后，脚本正好报
`missing=['liquidGlassPanelLum','liquidGlassTransparency']` ⇒ 判据有区分力。

✅ 顺带一条经验：**「验运行时不变量」的脚本比「验代码存在」的脚本值钱得多** ——
本轮这个 bug 里有方法、有调用、编译也过，唯独运行时判据漏了一个键。

---

### `verify_1001.py` — dex 字节码层（code 1001 滑条可达性修复）

```
用法:  python tools/verify_1001.py <apk_1001> <apk_1000>
```

**17 项 / 5 个有区分力锚**：

| 类型 | 判据 |
| --- | --- |
| 正向 `add` | `retunePanelGlass` 方法已编进包 · **`refreshStyle` 里真的 invoke 了它**（本 bug 核心）· 暗端斜率 `#float 0.0055` · 暗端基准 `#float 0.2` |
| 负向 `add_negative` | 旧单段基准 `#float 0.3` 已绝迹 |
| 保持 `add_keep` | `applyPanelBackground` 仍被调用 · `retunePanelGlass` 内仍调 `setPanelTuningFromPct` + `instance-of` 守卫 · 亮端 `0.003` · 默认 `0.42` · 通透度段参数 · 两个键名 · `PokeThrottle` / `ScopeWatcher` / `sCtxWasMissing` |

⚠️ 两条「只有某一层能验准」的口径：
- **「某方法里调了谁」只有方法体指令层能验**
  （`refreshStyle` 方法体里出现 `invoke-direct …retunePanelGlass`）；
- **「某方法用了哪个浮点常量」只有指令层的 `const … #float x` 能验**
  （`0.42` 在 1000 里位于**字段初始化区**、不在方法体，所以它在本脚本里表现为
  「1001 有、1000 无」的正向形态）。

---

---

### `verify_1000_src.py` — **源码层**（code 1000 默认值映射 + 接线完整性）

```
用法:  python tools/verify_1000_src.py     （在仓库根执行，不接参数）
```

🔴 **为什么需要这个脚本（它抓到了一个真事故）**：
本轮把三个标定常量改成「实例字段 + 换算函数」，「**默认配置下换算出来的值
是否等于改动前的历史值**」是**纯运行时行为** —— 字节码层只能验「东西编进去了」，
公式里挪一个小数点（0.003 → 0.004）就会让所有用户的面板亮度整体跑偏，**看不出来**。

做法：**从源码里提取公式的每个数字，在 Python 里独立复算**，再与历史值对比：

```
明暗 40      → 0.20 + 40×0.0055 = 0.420000  == 历史 TARGET_LUM 0.42   ✅（code 1001 起分段）
明暗 0       → 0.200000        （暗端，−52%）
明暗 100     → 0.600000        （亮端）
分段点连续   → pct=40 两支同值  （不会出现跳变）
通透度 50    → α = 0.55 + 1.0×(0.22−0.55) = 0.220000 == 历史 0.22       ✅
             → 黑底 = round(0x60 + 1.0×(0x14−0x60)) = 0x14 == 历史 0x14  ✅
```

另外验 6 条**接线完整性**（悬浮窗 / 设置页预览 / SubtitleStyle 承载 /
自愈判据 / 两个滑条创建与显隐）。

⚠️ **实战价值**：就是这 6 条接线断言抓出了「闭包 `nonlocal` 导致改动静默丢失」的
事故（4 个文件的改动全没落盘，脚本却报「✅ 已改」）—— 否则会交付一个
「设置页有滑条但没接线」的包。⇒ **改完必须回读验证**，不能信「写成功」的打印。

### `verify_1000.py` — dex 字节码层（code 1000 明暗可调）

```
用法:  python tools/verify_1000.py <apk_1000> <apk_999>
```

**16 项 / 9 个有区分力锚**：
- 正向 9 个：两个新键名字符串 · `setPanelTuningFromPct` · 三个实例字段名 ·
  `maxCompensableLum` · 两条设置页文案
- 保持型 7 个：三个标定常量仍作为**默认值**存在 · `sCtxWasMissing` ·
  `PokeThrottle` · `ScopeWatcher` · `liquid_glass_blur_pct`

---

### `verify_999.py` — dex 字节码层（code 999 启动竞态修复 + 样式回退）

```
用法:  python tools/verify_999.py <apk_999> <apk_997>
主验 = code 999 包；对照 = **code 997** 包
```

⚠️ 对照刻意选 **997**（而不是 998）：本轮要同时验**两组**改动，
997 是「锁定版 + 无竞态修复」⇒ 与本轮差异最全，一次覆盖两件事。

**14 项 / 8 个有区分力锚**：

| 组 | 判据 |
| --- | --- |
| **竞态修复**（正向） | `sCtxWasMissing` 字段 + 「`application context attached after a ctx-less read`」日志串 |
| **样式回退**（值变化） | `ADAPTIVE_BASE_BLACK` / `ADAPTIVE_VEIL_ALPHA` |
| 样式回退（负向） | `LOCKED_VEIL_LUM` 绝迹（字段层 + 字符串层双锚） |
| 样式回退（正向恢复） | `hasStats()` / `lum(int)` 回归 |
| 保持型 | `TARGET_LUM` · `PokeThrottle` · `POKE_WINDOW_MS` · `PokeThrottle.decide` 调用 · `ScopeWatcher` · `attachContext` |

---

### `verify_998.py` — dex 字节码层（code 998 液态玻璃样式回退）

```
用法:  python tools/verify_998.py <apk_998> <apk_997>
主验 = code 998 包（回退版）；对照 = code 997 包（锁定版）
```

本轮改动是**撤销 997 对一个文件的全部改动** ⇒ 判据是 997 那套的**反向**。
**11 项 / 6 个有区分力锚**：

| 类型 | 判据 |
| --- | --- |
| 值变化 `add_diff` | `ADAPTIVE_BASE_BLACK` 0xBB → 0x14 · `ADAPTIVE_VEIL_ALPHA` 0.44 → 0.22 |
| 负向 `add_negative` | `LOCKED_VEIL_LUM` 绝迹（**字段层 + 字符串层双锚**） |
| 正向 `add` | `hasStats()` / `lum(int)` **恢复**（997 删过） |
| 保持 `add_keep` | `TARGET_LUM` = 0.42 · `PokeThrottle` · `POKE_WINDOW_MS` · `pokeStructureChanged` 仍调 `PokeThrottle.decide` · `ScopeWatcher` |

⚠️ **保持型那 4 条是本轮的重点**：它们证明「只回退了样式，G7 重构没被误伤」。
没有它们，一个「把整个 commit 都 revert 掉」的错误做法也能骗过验证。

⚠️ 恢复型判据（`hasStats` / `lum`）**必须用 `method_body()` 而非裸字符串匹配** ——
`lum` 是短名，而 `backdropMeanLum` 等字段**包含** `lum` 子串，
裸 `in` 匹配会让两版都命中 ⇒ 假「无区分力」。

---

### `verify_997.py` — dex 字节码层（code 997 亮度锁定 + 第 5 批重构）

```
用法:  python tools/verify_997.py <apk_997> <apk_996>
主验 = code 997 包；对照 = code 996 包
```

验两项，共 **11 项 / 7 个有区分力锚**：

**A. 亮度锁定（LiquidGlassDrawable 填充常量）** —— ⚠️ **数值改动只有「dex 静态字段
常量层」能验准**：javac 把 `static final` 基本类型常量内联进指令，字符串层查不到值。
本脚本读 dexdump 的 `value :` 行：

| 字段 | 996 | 997 |
| --- | --- | --- |
| `ADAPTIVE_BASE_BLACK` | `335544320`（0x14000000） | `-1157627904`（0xBB000000） |
| `ADAPTIVE_VEIL_ALPHA` | `0.22` | `0.44` |
| `LOCKED_VEIL_LUM` | （无此字段） | `0.72` |
| `TARGET_LUM` | `0.42` | `0.42`（保持型） |

**B. 第 5 批重构（PokeThrottle）**
- `PokeThrottle` 类名 / `POKE_WINDOW_MS` 常量：997 有、996 无
- **指令层锚**：`pokeStructureChanged` 的**方法体**里含 `PokeThrottle;.decide`
  （dexdump 会把方法引用展开成完整字符串，这是最强的「真的在调」证据）
- **负向锚**：`hasStats` 已绝迹（锁定后不再需要该条件）

⚠️ 定位类切片前要先确定**类定义在哪个 dex** —— 类名在多个 dex 里作为「引用」
出现，只有一个是定义。本脚本对候选 dex 逐个 dump 再找 `Class descriptor`。

---

### `verify_996.py` — dex 字节码层（code 996 第4 批重构）

验作用域探测是否**真从 `ActivityButtonHook` 搬到 `ScopeWatcher`**。

```
用法:  python tools/verify_996.py <apk_996> <apk_995>
主验 = code 996 包；对照 = code 995 包（重构前的上一交付包）
```

🔴 **本脚本贡献了一条新铁律：判据分三种类型，不能混用一个 `add()`**：

| 类型 | 期望 | 用哪个方法 | 典型用途 |
| --- | --- | --- | --- |
| **正向锚** | 主验命中 / 对照未命中 | `ck.add()` | 「新代码已编进包」 |
| **负向锚** | 主验**不**命中 / 对照命中 | `ck.add_negative()` | 「旧物已绝迹」 |
| **保持型** | **两版都命中** | `ck.add_keep()` | 「功能没被搬丢」（日志串仍在） |
| **区分型「值变化」** | 主验==新值 **且** 对照==旧值 **且** 新值≠旧值 | `ck.add_diff()` | 「常量值改了」（996 只归纳出三型，997 补上第四型） |

996 首版只有 `add()`，结果：负向锚的**正确结果**（「主验=· 对照=Y」）
被报成 FAIL，保持型被报「无区分力」⇒ **15 个假 FAIL**。

⚠️ **第四型（997 补）为什么必须单列**：改**常量值**（如 `ADAPTIVE_BASE_BLACK`
0x14→0xBB）时，两版**都有**这个字段 ⇒ 用 `add()` 判「存在性」会报
「无区分力(两版都成立)」—— 可实际上「值变了」正是我们要的区分力。
⇒ 判「值变化」要用 `add_diff()`，它额外要求新旧期望值**本身不同**。

⚠️ 另两个坑：
- **descriptor 带单引号**：dexdump 输出是 `Class descriptor  : 'Lfoo/Bar;'`。
  按不带引号去 find 永远返回 -1 ⇒ 切片 None ⇒ 回落到全量匹配 ⇒ **两版都命中**的假象。
- **类会跨 dex**：996 里 `ActivityButtonHook` 被拆进两个 dex，
  按 `os.listdir` 顺序拼接会从一个 dex 中间截到另一个中间 ⇒ 必须按 dex 序号排序。

---

### `verify_995.py` — dex 字节码层（code 995）

验 code 995 六项改动是否**真的编进了 APK**，而不是只改了源码。

```
用法:  python tools/verify_995.py <apk_995> [apk_994]
主验 = code 995 包；对照 = code 994 包（改动前的上一版）
```

**关键设计：单进程双表 + 报「有区分力锚数」。**

| 判据组 | 内容 |
| --- | --- |
| 1~4 | `Protocol` 转发壳 · `AnchorDeadPolicy` 抽出 · `Utils.dip2px` 三个入口 |
| 5 | `Utils.dip2pxOrDefault` / `applyDimensionDip` / `DEFAULT_DENSITY` |
| 6 | `ConfigStore` / `ScopeProbe$2` / `StatusBarSubtitleBridge` 已调 `LogGate` |
| 6b | `ConfigStore` 的 `Log.w` 告警**仍在**（验证没被误降级为诊断级） |
| 7 | `sPokeLogged` 环形取模（模数 = `MAX_POKE_LOGS` = 40）· `sPokeCount` 仍保留 |

⚠️ **三个已被实测推翻的判据**（写在脚本注释里，别重犯）：

| 曾经的判据 | 为什么不行 |
| --- | --- |
| 在 `ConfigBus` / `Bridge` 里查 action 字面量是否归零 | **`javac` 编译期常量内联**：`static final String X = Protocol.X` 被替换成字面量，dex 里旧类仍带字面量，且**完全看不到 `Protocol` 引用** ⇒ 字节码层两个方向都验不出 |
| 在 `ScopeProbe` 外层类查 `LogGate` 调用 | 调用在**匿名内部类 `ScopeProbe$2`**（`BroadcastReceiver`）里 |
| 判`Log.w` 找 `'W'` 字符串 | level是**寄存器传入**的，dexdump 里只有 `invoke-static {..}, Landroid/util/Log;.w` |

⚠️ **`rem-int` 单独做判据没有区分力**：994 里本就有 1 处无关的 `rem-int/lit8 v0, v0, #int 60`。
真正的锚是**紧跟 `sPokeLogged`、模数恰为 40** 的那条（995 有 `rem-int/lit8 v5, v5, #int 40`，994 没有）。

---

### `verify_protocol_src.py` — 源码层（code 995）

验`config/Protocol.java` 的**单点真源**是否真的单点。

```
用法:  python tools/verify_protocol_src.py [repo_root]
```

**为什么需要它**：字节码层验不出这件事（见上表第一条）。
所以「action/extra 字面量只在 Protocol 里」这件事**只能在源码层验**。

判据（10 项）：
1. action 字面量仅存在于 `Protocol.java`
2. 包名字面量仅存在于 `Protocol.java`
3. 9 个 action 齐全 · 10 个 extra 齐全
4. `ConfigBus` / `StatusBarSubtitleBridge` 的转发壳完好（各≥ 6 个转发）
5. `PROTOCOL_VERSION` 存在

技术点：`strip_javadoc_and_comments()` **只剥注释、保留字符串字面量**。
⚠️ 第一版把它写成了「连字符串一起剥」⇒ 恰好把要找的东西删掉了，已修正。

---

### `verify_994.py` — dex 字节码层（code 994）

验 `PageFollowPolicy` 抽出是否真编进包（6 个区分力锚）。

```
用法:  python tools/verify_994.py <apk_994> [apk_993]
```

🔴 **诚实的收益说明**：这批锚**预期会随轮次失效**。
它判的是「994 引入了 `PageFollowPolicy`、993 没有」，而 995 的对照包仍是 994 ⇒
**主验 995 与对照 994 的差异本就该判为 FAIL**。
所以现在跑它得到 `18/18`（对比包缺 993，对照类判据自动跳过），
**全绿只说明「994 包里`PageFollowPolicy` 在」，不代表别的**。

⇒ **想用它验 995，得给一个 995 的对照包**；否则请以 `verify_995.py` 为准。
（这不是脚本 bug，是「单版本差异判据」的固有局限。）

---

### `verify_994_log_window.py` — 真机日志层

验「跨日志窗口比条数前必须归一化」这条铁律，以及 `sPokeLogged` 配额的代价。

```
用法:  python tools/verify_994_log_window.py
```

⚠️ **硬编码依赖**：它读工作区的 `work_diag_993/log/` 与 `work_diag_994/log/`。
若那两卷已移动，脚本会打印找不到的提示并退出。

**它证明了什么**：
1. `Button` / `[几何]` 绝对条数下降 100%，**根因是日志窗口长度差异 + `sPokeLogged` 配额打满**，不是重构回归
2. 速率归一化后 `Button` 反而**上升**
3. 两个宿主进程 pid 跨窗口**未重启** ⇒ 静态字段跨窗口保留

⇒ 这条脚本是**铁律 31 / 32 的活证据**。code 995 修好配额后，
`verify_995.py` 的第 7 组锚就是它的反向验证。

---

## 其他工具（非 `verify_`）

| 脚本 | 作用 |
| --- | --- |
| `extract_changelog.py` | 🔴 **已退役**（code 995 起）。它是 code 993 的一次性迁移工具：把 build.gradle 里 38 条履历搬进 CHANGELOG。迁移完成后 build.gradle 只留 5 条摘要 ⇒ 它的 `EXPECT_N=38` 断言**必然失败**。**现在加履历请直接编辑 `CHANGELOG.md`**，它是唯一真源 |
| `version_code.py` | 版本号四处（`appVersionCode` / `appVersionName` / `appVersionCodeLabel` / `LogGate` 类头）的**一致性检查** |
| `geom/` | 几何计算的辅助脚本目录 |

> 🔴 **踩过的坑**：code 995 第一次跑 `extract_changelog.py` 时断言挂了
> 「期望 38 条，实际 5 —— 停手」。
> 取证后确认：**这不是数据损坏，是脚本使命已完成**。
> ⚠️ 若当时「顺手把EXPECT_N 改成 5」—— 那才是真事故：重跑会把 858 行的 CHANGELOG 覆盖成 5 条摘要。
> **脚本自曝了「已退役」，这比默默改数字安全。**

---

## ⚠️ 判据纪律（三条，别绕过）

1. **有区分力才算判据**。真锚 = 主验命中**且**对照未命中。
   「两版都成立」= 没测到东西。`run_all_verify.py` 会把锚数打在汇总表里。
2. **优先读脚本自报值**。日志自带 `OK` / `OFF=n` / `PASS n/n` 时，别自己拿原始字段重算 ——
   字段名与语义没有对应关系时，算出来的数可能是假绿。
3. **判 FAIL 前先确认不是脚本判据错**。995 的 dex 层首版报了 3 个 FAIL，
   取证后确认**全是脚本判据错、零真回归**。这个方向要敢怀疑。

---

**本目录为纯工具集，代码零改动。**

### `verify_1000_src.py` — **源码层**（默认值映射，常驻不变量）

⚠️ code 1001 起明暗映射改为**分段线性**，本脚本已同步：

```
明暗 0 → 0.20（最暗，−52%）· 40 → 0.42（默认，零回归）· 100 → 0.60（最亮）
```

新增两条断言：**分段点连续**（pct=40 两支同值，不会跳变）、**暗端降幅 ≥ 40%**。

🔴 **本脚本曾被自己写坏过一次，值得记住**：改公式时把 `def lum_of()` 插进了
`main()` 体内 ⇒ 等于把 `main()` **从中间截断**，剩下的语句全变成不可达
⇒ `main()` 返回 `None` ⇒ `sys.exit(None)` = **退出码 0**，而**一行都不打印**。
一件"全绿"的假象。⇒ 已给 `run_all_verify.py` 加兜底网：
**退出码 0 但零输出 ⇒ `SUSPECT`（计入失败）**。

---

---

### ⚠️ `ENVBLOCK`：环境拦截 ≠ 判据失败

`run_all_verify.py` 有个 `ENVBLOCK` 状态。触发条件：
脚本输出里带 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` **且** 没有自己的 `FAIL n/m` 汇总行。

**背景**：本环境有「**单轮**删除 ≥ 50 个文件需确认」的保护。
`verify_994.py` / `verify_995.py` 跑完会清临时 dump ⇒ **同一轮里把一键验证连跑两次**，
第二次的清理就会被拦，命令被**从外部中断**（Python `try/except` 捕不到）
⇒ 脚本在自己的判据汇总行**之前**就被掐断 ⇒ 看起来像 FAIL，其实什么都没验出来。

⇒ **规矩**：同一轮里一键验证最多跑**两次**；真要连跑，先等下一轮。
（两个脚本的清理也改成了**非致命**：`_rm()` 吞异常，且首块用 `'w'` 覆盖写 ——
避免"删不掉时把上一轮的 dump 追加进来"这种更隐蔽的错。）
