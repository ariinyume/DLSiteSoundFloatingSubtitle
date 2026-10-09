# `tools/` 索引

> 全部脚本**只读**，不改任何文件。
> 汇总入口：`python tools/run_all_verify.py`（任一 FAIL ⇒ 退出码非 0）

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
| **① 单元测试** | 纯函数内核的黄金向量（纯 JVM，不碰 Android） | `app/src/test/` · `GoldenVectorTest` | **22/22** |
| **② 源码层** | 单点真源、字面量归零、转发壳完好 | `verify_protocol_src.py` | **10/10** |
| **③ dex 字节码层** | 改动**确实编进了 APK** | `verify_995.py` · `verify_994.py` | **28/28（锚 22）** · **18/18** |
| **④ 资源层** | aapt2 读编译后资源（float 有精度损失 ⇒ 必须容差） | `verify_pathdata.py`（矢量图） | 按需 |
| **⑤ 真机日志** | 真实行为是否与判据一致 | `verify_994_log_window.py` | **8/8** |

---

## 脚本明细

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