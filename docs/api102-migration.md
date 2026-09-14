# 迁移到 libxposed API 102 的改造清单（预备）

> 📦 本文档是**预备清单**，不是当前工作计划 —— 仅在决定迁移时按此执行。
> 📌 评估结论：**当前不必迁移**。建议等 v28（1.20.5）的换轨修复验证收敛、功能稳定后，
> 作为**独立一版**（如 `1.21.0`）单独实施，不要与功能修复混在一起。

---

## 0. 决策摘要

| 问题 | 结论 |
| --- | --- |
| 设备端框架支持吗？ | ✅ 支持。LSPosed **v2.2.0 (7854)** 已实现 libxposed API 102 |
| 会被框架点名 / 警告吗？ | ❌ 不会。本模块没用 `XSharedPreferences`，不在 2.3.0 清理范围内 |
| 是「改个数字」吗？ | ❌ 不是。是**换一套框架**，legacy API 全禁、**不可混用** |
| 迁移工程量 | 9 个文件 / 4270 行；**103 处** Xposed API 引用（另有 17 行 import） |
| 值得做吗？ | 唯一实收益 = **热重载**；其余卖点对本项目几乎无用 |
| 什么时候做？ | bug 收敛后作为独立版本；迁移是**单向门**，回滚 = 整个模块回滚 |

---

## 1. 设备端前提（已满足，无需处理）

从 LSPosed 日志 dump 里可直接读到：

```
log/modules/zygisk_lsposed/module.prop
→ version=v2.2.0 (7854)
```

LSPosed 自 **v2.1.0-7769** 起即标注「Implemented libxposed API 102」，所以**框架门槛早已过了**，
不需要为了迁移去动 root 环境或更换框架。

另外已确认：本模块**不使用 `XSharedPreferences`**（manifest 无 `xposedsharedprefs`、代码零引用），
因此 LSPosed 2.3.0 计划移除新 XSharedPreferences 的那波清理**与本模块无关**。

---

## 2. 为什么这是「换框架」而不是「升版本」

| | Legacy | Modern |
| --- | --- | --- |
| 坐标 | `de.robv.android.xposed:api:82` | `io.github.libxposed:api:102.0.0` |
| 状态 | rovo89 时代，**已停更（止于 82）** | LSPosed 团队维护，**当前活跃** |
| 入口接口 | `IXposedHookLoadPackage` | `XposedModule`（抽象类） |
| Hook 回调 | `XC_MethodHook` + `before/afterHookedMethod` | `Hooker.intercept(Chain)`，OkHttp 式拦截链 |
| 反射工具 | `XposedHelpers` | **已移除**，需自建（官方另出 `libxposed/helper`） |
| 入口声明 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 元数据 | Manifest `<meta-data>` | `META-INF/xposed/module.prop` + `scope.list` |

🔴 **决定性规则**：`targetApiVersion ≥ 102` 的模块，**在 classloader 层被禁止访问 legacy 包**。
也就是说 `XposedHelpers.*` / `XposedBridge.*` / `XC_MethodHook` 全部**不可用，且不可混用** ——
没有「先改一半试试」的选项。（官方原文：*Libxposed modules can not call legacy de.robv.android.xposed APIs.*）

---

## 3. 迁移面盘点（基于当前代码实测）

统计口径：`grep -rn "XposedHelpers\.\|XposedBridge\.\|XC_MethodHook\|XC_LoadPackage\|IXposedHookLoadPackage\|de\.robv" app/src/main/java`（**排除 `import` 行**）

| Legacy API | 处数 | 迁移后替代 |
| --- | --- | --- |
| `XposedBridge.log` | **73** | `log(priority, tag, msg)`（**实例方法**，见步骤 4） |
| `new XC_MethodHook`（hook 体） | **10** | `hook(m).intercept(chain -> …)` |
| `XposedHelpers.findClass` | **8** | `Class.forName(name, false, cl)` |
| `XposedHelpers.callMethod` | **7** | `getInvoker(m)` 或纯反射 |
| `XposedHelpers.findAndHookMethod` | **6** | `cl.getDeclaredMethod(…)` + `hook(m)` |
| `XposedHelpers.findMethodExact` | **2** | `cl.getDeclaredMethod(name, types)` |
| `XposedBridge.hookMethod` | **2** | `hook(executable).intercept(…)` |
| `XposedBridge.hookAllMethods` | **2** | 遍历 `getDeclaredMethods()` 逐个 `hook()` |
| `XposedHelpers.callStaticMethod` | **1** | 纯反射 / `getInvoker(m)` |
| `IXposedHookLoadPackage` | **1** | `extends XposedModule` |
| `XC_LoadPackage` | **1** | `PackageLoadedParam` / `PackageReadyParam` |
| **合计** | **103** | ＋17 行 import，共 120 处文本命中 |

按文件的分布：

| 文件 | 处数 | 其中 `log` | 剩余（要理解的） |
| --- | --- | --- | --- |
| `hook/ActivityButtonHook.java` | 21 | 18 | 3 |
| `hook/NetworkHook.java` | 21 | 9 | 12 |
| `hook/PlayerSourceHook.java` | 15 | 10 | 5 |
| `DlsiteSoundSubtitleModule.java` | 13 | 7 | 6 |
| `window/FloatingWindowManager.java` | 11 | 11 | 0 |
| `data/SubtitleRepository.java` | 9 | 9 | 0 |
| `hook/PlayerPositionHook.java` | 8 | 4 | 4 |
| `view/FloatingSubtitleView.java` | 4 | 4 | 0 |
| `hook/SubtitleViewHook.java` | 1 | 1 | 0 |
| **合计** | **103** | **73** | **30** |

> 📎 好消息：**73/103 是 `log`**，属于机械替换。
> 真正需要理解语义的只有 **10 个 hook 体** 和 **18 处反射调用**。

---

## 4. 改造步骤

### 步骤 1：构建配置

**依赖**（`app/build.gradle`）：

```groovy
dependencies {
    // 旧：compileOnly 'de.robv.android.xposed:api:82'
    compileOnly 'io.github.libxposed:api:102.0.0'
}
```

⚠️ **离线环境注意**：`io.github.libxposed:api:102.0.0` 发布在 **Maven Central**，本机 Gradle 缓存里
**没有任何 xposed / libxposed 依赖**，属于全新拉取。迁移前必须先联网拉一次，
或手动把 aar 预置到 `libs/` / `mavenLocal`，否则 `--offline` 构建直接失败。

**入口声明改为资源文件**：删掉 `app/src/main/assets/xposed_init`，
新建 `app/src/main/resources/META-INF/xposed/`（Gradle 会自动打进 APK）：

```
app/src/main/resources/META-INF/xposed/
├── java_init.list      # 每行一个入口类全名
├── module.prop         # 模块 API 门限
└── scope.list          # 每行一个作用域包名
```

`java_init.list`
```
com.sena.dlsitesoundfloat.DlsiteSoundSubtitleModule
```

`module.prop`（Java Properties 格式）
```properties
minApiVersion=102
targetApiVersion=102
# 可选：staticScope=true → 不允许用户勾选作用域外的应用
# 可选（102 新增）：autoHotReload=true → 模块 App 更新时自动触发热重载
```

`scope.list`
```
jp.co.eisys.dlsitesound
```

**若开启 R8 / 混淆**（本项目当前 `minifyEnabled false`，**暂不涉及**；迁移时若顺手开混淆需加）：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

### 步骤 2：Manifest 清理

新版**不再使用 `<meta-data>` 元数据**。删掉这 4 条，改用标准 Android 资源：

```xml
<!-- 全部删除 -->
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="…" />
<meta-data android:name="xposedminversion" android:value="93" />
<meta-data android:name="xposedscope" android:value="jp.co.eisys.dlsitesound" />
```

改为 `android:label`（模块名）+ `android:description`（描述，需新增 `@string` 资源）：

```xml
<application
    android:label="@string/app_name"
    android:description="@string/app_desc"
    android:icon="@mipmap/ic_launcher"
    android:allowBackup="false">
```

> ✅ `_debug` 后缀仍放在 `app_name` 里即可 —— LSPosed 列表读的就是 `android:label`，行为不变。

### 步骤 3：入口类重写

**改前**（现状）：

```java
public class DlsiteSoundSubtitleModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        …
        repo.init(systemCtx, lpparam.classLoader);
        NetworkHook.hook(lpparam.classLoader, repo);
        …
    }
}
```

**改后**：

```java
public class DlsiteSoundSubtitleModule extends XposedModule {
    private static final String TAG = "DLsiteSoundFloat";
    private static final String TARGET_PKG = "jp.co.eisys.dlsitesound";

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        ClassLoader cl = param.getDefaultClassLoader();   // ≈ 旧的 lpparam.classLoader
        log(Log.INFO, TAG, "==== BUILD 1.21.0 (libxposed API 102) ====");
        … // 其余注册逻辑照搬
    }
}
```

要点：

- **构造器必须是无参 `public`**；框架会自动 `attachFramework(...)`，**不要自己调**。
- **不要在 `onModuleLoaded()` 之前做初始化**（构造器里什么都别干）。
- 回调选择：`onPackageLoaded`（默认 classloader 就绪，≈ 旧 `handleLoadPackage`）
  / `onPackageReady`（app classloader 就绪，用 `param.getClassLoader()`，还能拿 `getAppComponentFactory()`）。
  本项目目标类（expo / okhttp / media3）都是后加载的，**用哪个都能 hook 到**，建议 `onPackageLoaded`。
- 不再有 zygote 阶段，只在作用域进程内加载。

### 步骤 4：日志替换（73 处）＋ 「实例方法」这道坎

⚠️ **这一步有个容易漏的架构影响**：新版 `log()` 和 `hook()` **都是 `XposedInterface` 的实例方法**，
不再是 legacy 那种静态工具。而我们的 5 个 hook 类全是 `static hook(ClassLoader, repo)` 工具类，
**拿不到那个实例**。所以必须先把实例传下去（或做成静态桥）。

推荐做法 —— **在 `DlsiteSoundSubtitleModule` 里注册一个静态桥**，然后：

```java
// 1) 新增 util/Logs.java
public final class Logs {
    private static XposedInterface sXp;
    public static void install(XposedInterface xp) { sXp = xp; }
    public static void d(String msg) {
        if (sXp != null) sXp.log(Log.INFO, "DLsiteSoundFloat", msg);
    }
}

// 2) 入口类 onPackageLoaded 开头
Logs.install(this);

// 3) 73 处调用点批量替换
//    旧：XposedBridge.log("[DLsiteSoundFloat] Module loaded for " + pkg);
//    新：Logs.d("Module loaded for " + pkg);
```

同样地，各 hook 类的方法签名要带上实例：

```java
// 旧
public static void hook(ClassLoader cl, SubtitleRepository repo)
// 新
public static void hook(XposedInterface xp, ClassLoader cl, SubtitleRepository repo)
```

> ⚠️ 日志文本格式变了（多了 priority / tag 两个参数），
> `docs/troubleshooting.md` 的日志对照表需同步更新；**抓取方式不变**（仍进 LSPosed 日志）。

### 步骤 5：反射工具替代（`XposedHelpers` 已移除，18 处）

官方**不再提供 `XposedHelpers`**（另出了 `libxposed/helper` 作为可选开发套件）。两条路：

**A. 自建极薄 shim**（推荐：可控、零额外依赖）

```java
final class Xp {
    static Class<?> findClass(String name, ClassLoader cl) {
        try { return Class.forName(name, false, cl); }
        catch (Throwable t) { return null; }
    }
    static Object call(Object obj, String name, Object... args) {
        // 遍历 declaredMethods，按名字 + 参数类型匹配 → setAccessible → invoke
    }
}
```

**B. 用官方 `libxposed/helper`** —— 更省事，但该库当时仍在开发中、API 可能变动；
且它本身也是**要新增的依赖**，离线环境需一并拉取。

对照表：

| 旧 | 新 |
| --- | --- |
| `XposedHelpers.findClass(name, cl)` | `Class.forName(name, false, cl)` |
| `XposedHelpers.findMethodExact(cl, "build")` | `cl.getDeclaredMethod("build")` |
| `XposedHelpers.callMethod(obj, "m")` | `getInvoker(m).invoke(obj)` 或纯反射 |
| `XposedHelpers.callStaticMethod(cl, "m")` | 同上（`thisObject` 传 `null`） |

> ⚠️ 本项目 `callMethod` 多用于**目标 App 的内部方法**（`AudioPlaylist` / `ExoPlayerImpl` / `ActivityThread`）。
> 优先用 **`getInvoker(Method)`** —— 它**绕过访问检查**，等价于旧 `XposedHelpers.callMethod` 的宽容度；
> 纯反射 `setAccessible(true)` 在部分隐藏 API 上会被拦。

### 步骤 6：Hook 回调模型（10 处 hook 体）—— 最花心思的一步

从「before / after 两段回调」改成「**OkHttp 式拦截链**」：只有一个 `intercept(Chain)`，
**返回值就是该方法的返回值**（新版**没有** `param.setResult`）。

**改前**（`PlayerPositionHook` 读 `getCurrentPosition` 返回值）：

```java
XposedHelpers.findAndHookMethod(cls, "getCurrentPosition", new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Object r = param.getResult();
        if (r instanceof Long) repo.onPosition((Long) r);
    }
});
```

**改后**：

```java
hook(cls.getDeclaredMethod("getCurrentPosition"))
    .setPriority(PRIORITY_DEFAULT)
    .setExceptionMode(ExceptionMode.PROTECTIVE)
    .intercept(chain -> {
        Object r = chain.proceed();          // ≈ afterHookedMethod：先执行原方法
        if (r instanceof Long) repo.onPosition((Long) r);
        return r;                            // ⚠️ 必须原样返回，否则篡改返回值
    });
```

**改前**（`NetworkHook` 在 after 里读结果解析字幕 JSON）：

```java
XposedBridge.hookMethod(executeMethod, new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Object response = param.getResult();
        … // 解析字幕 JSON
    }
});
```

**改后**：

```java
hook(executeMethod).intercept(chain -> {
    Object response = chain.proceed();
    … // 解析字幕 JSON
    return response;
});
```

**只做副作用、不关心结果**的 hook（如 `ActivityButtonHook` 观察 Activity 生命周期）：

```java
hook(attachMethod).intercept(chain -> {
    Object ret = chain.proceed();
    Context appCtx = (Context) chain.getArg(0);   // ≈ param.args[0]
    repo.setAppContext(appCtx);
    return ret;                                    // void 方法返回 null 即可
});
```

**关键 API 对照**：

| 旧（`MethodHookParam`） | 新（`Chain`） |
| --- | --- |
| `param.args[i]` | `chain.getArg(i)` |
| `param.args`（可写） | `chain.getArgs()`（**不可变**；要改参用 `proceed(newArgs)`） |
| `param.thisObject` | `chain.getThisObject()` |
| `param.getResult()` | `chain.proceed()` 的返回值 |
| `param.setResult(x)` | **`return x;`** |
| `param.getThrowable()` | 用 `try / catch` 包住 `chain.proceed()` |
| `beforeHookedMethod`，不改行为 | 先 `proceed()`，再做副作用 |
| `beforeHookedMethod`，要改参 | `chain.proceed(新参数组)` |
| 短路（不执行原方法） | **不调 `chain.proceed()`，直接 `return` 自造结果** |

> ✅ 本项目**没有** `param.setResult()` / `setObjectExtra` 的用法（不是「改结果」型 hook），
> 全部是「**观察型**」（读结果、记状态、回调 repo），语义迁移很直接：
> `afterHookedMethod` 体 → `Object r = chain.proceed(); …; return r;`
>
> ⚠️ `Chain` **不可跨线程共享、`intercept` 返回后即失效**，不要把它存进静态字段。

### 步骤 7：`hookAllMethods` → 手工遍历（2 处）

新版没有 `hookAllMethods`，改为遍历 + 过滤：

```java
// 旧：hooked += XposedBridge.hookAllMethods(cls, name, new XC_MethodHook(){…});
// 新：
int hooked = 0;
for (Method m : cls.getDeclaredMethods()) {
    if (!m.getName().equals(name)) continue;
    hook(m).intercept(chain -> { …; return chain.proceed(); });
    hooked++;
}
```

> 📌 这两处（`PlayerSourceHook` 的换轨信号方法与 `getCurrentTrackIndex`）是**换轨判定的命门**。
> 迁移时必须保留「hook 数量 = 实际命中数」的可观测性（现有
> `hooked expo.modules.audio.AudioPlaylist [..] (N methods)` 日志），
> 否则会重演 `[unconditional + seed] (0 methods)` 那种**静默 0 命中**。

### 步骤 8：上下文获取（1 处）

入口类里拿 system context 用的是 `ActivityThread.currentActivityThread()` + `getSystemContext()`
—— 这**不是 legacy Xposed API**，只是普通反射。把 `XposedHelpers.callStaticMethod` / `callMethod`
换成 shim（或用 `getInvoker`）即可，**逻辑不动**。

模块自身信息改用新 API：`getModuleApplicationInfo()`。

### 步骤 9（可选）：接入 102 新增能力

| 能力 | 用法 | 对本项目的价值 |
| --- | --- | --- |
| ⭐ **热重载** | 覆写 `onHotReloading()` / `onHotReloaded()`；`module.prop` 可设 `autoHotReload=true` | **唯一实收益**，详见 §8 |
| 停止回调 | `detach()` | 作用域外进程不再被打扰，可省开销 |
| 原子替换 hook | `HookHandle.replace()` / 按 id 替换 | 调试期热更单个 hook 用得上 |
| Remote Preferences / Files | `getRemotePreferences(group)` / `openRemoteFile(name)` | 「模块 App 侧配参数、目标进程读」，当前无此需求 |
| `deoptimize(Executable)` | 强制反内联 | 我们 hook 的不是被内联的热点，**用不上** |
| 资源 hook | — | **官方已移除**（本模块本来也没用） |

---

## 5. 逐文件工作量表

| 文件 | 改动性质 | 预估 |
| --- | --- | --- |
| `DlsiteSoundSubtitleModule.java` | 入口重写 + 静态桥注册 + 上下文反射 shim + 1 hook + 7 log | 中 |
| `hook/PlayerSourceHook.java` | 2 hook 体 + 2 处 `hookAllMethods` 遍历 + 反射 + 10 log | **高（命门）** |
| `hook/NetworkHook.java` | 3 hook 体 + 2 `findMethodExact` + 2 `hookMethod` + 9 log | 中 |
| `hook/ActivityButtonHook.java` | 2 hook 体 + `findClass` + 18 log | 中 |
| `hook/PlayerPositionHook.java` | 2 hook 体 + 2 `findClass` + 4 log | 中 |
| `hook/SubtitleViewHook.java` | 1 log | 低 |
| `data/SubtitleRepository.java` | 9 log | 低 |
| `window/FloatingWindowManager.java` | 11 log | 低 |
| `view/FloatingSubtitleView.java` | 4 log | 低 |
| 新增 `util/Logs.java` | 静态日志桥（把实例方法转成静态调用） | 低 |
| `app/build.gradle` | 依赖 + `resources` 目录 | 低 |
| `AndroidManifest.xml` | 删 4 条 meta-data，加 `android:description` | 低 |
| 新增 `META-INF/xposed/*` | 3 个小文件 | 低 |
| `docs/troubleshooting.md` | 日志对照表同步 | 低 |
| `README.md` | 依赖 / 构建 / 日志说明同步 | 低 |

---

## 6. 风险与回滚

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 🔴 **单向门** | `targetApiVersion ≥ 102` 后 legacy API 全禁，**不能混用、不能渐进** | 在独立分支做，整包回滚 |
| 🔴 **离线拉不到依赖** | `io.github.libxposed:api:102.0.0` 在 Maven Central，本地缓存没有 | 迁移前先联网拉一次，或预置到 `libs/` + `mavenLocal` |
| 🟠 **静态桥被漏传** | `log()` / `hook()` 是实例方法，5 个 hook 类都要改签名 | 编译期就会报错，不会静默失败；但改动面广，逐个核对 |
| 🟠 **静默 0 命中** | `hookAllMethods` 改手工遍历后，名字 / 参数过滤写错 → 一个都没 hook 上（本项目踩过） | 保留并核对 `hooked … (N methods)` 日志 |
| 🟠 **反射宽容度** | 纯反射 `setAccessible` 可能被隐藏 API 拦 | 优先 `getInvoker(Method)` |
| 🟠 **返回值被篡改** | `intercept` 里忘记 `return chain.proceed()` 的结果 → 目标方法返回 null | 逐个 hook 检查返回到位 |
| 🟡 **调试期叠加** | 迁移与功能修复同时做 → 出问题无法归因 | **务必独立成版**，见 §0 |

---

## 7. 迁移后验证清单

- [ ] LSPosed 日志出现新 BUILD 串，且**无 legacy API 相关报错**
- [ ] 模块在 LSPosed 列表中正常显示（名称含 `_debug`）+ 作用域正确
- [ ] 悬浮字幕窗在播放页正常显示 / 非播放页正常隐藏（页面三态判定未退化）
- [ ] 字幕随播放进度同步（`getCurrentPosition` hook 生效 = 返回值未被篡改）
- [ ] **真换轨字幕跟着切**（`PlayerSourceHook` 序号门有效）
- [ ] 假换轨（第一轨按「上一首」）不清字幕、不关窗
- [ ] 切到无字幕音轨 → 3 秒后清空并关窗
- [ ] 字幕 JSON 抓取正常（`NetworkHook` 的 after 语义迁移正确）
- [ ] 按钮在播放页正常出现 / 消失
- [ ] 日志落盘路径与抓取方式无变化

---

## 8. 收益结算（诚实版）

| 卖点 | 对本项目的实际价值 |
| --- | --- |
| ⭐ **热重载** | **唯一真收益**。改完模块**不用杀进程**即可生效。本项目的调试循环恰恰是「退到后台再回来」「切轨」这类**与进程生命周期强相关**的 bug，而「强制停止 DLsiteSound」本身就会破坏复现条件 —— 热重载更贴近真实场景。<br>⚠️ 前提：**恰好一个 Java 入口类**（本项目正好只有 `DlsiteSoundSubtitleModule`，✅ 满足） |
| 类型安全 | **几乎为零**。做的是逆向，`expo.modules.audio.AudioPlaylist` 这类类名**只能是运行时字符串**，编译期无法校验 |
| 性能 | **无体感**。hook 调用开销降低，理论上对 `getCurrentPosition` 高频 hook 有意义，但从未观察到本项目卡顿 |
| 官方支持 | 中期收益。legacy 线已停更，新特性只在 modern 线加 |

**结论**：将来若做，理由应该是「**为了热重载带来的调试效率**」，
而不是「为了跟上版本」—— 后者在这个项目里换不来任何可感知的变化。

---

## 附 A. API 速查对照表

| Legacy（82） | Modern（102） |
| --- | --- |
| `de.robv.android.xposed:api:82` | `io.github.libxposed:api:102.0.0` |
| `IXposedHookLoadPackage` | `extends XposedModule` |
| `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| Manifest `<meta-data>` | `META-INF/xposed/module.prop` + `scope.list` + `android:label/description` |
| `handleLoadPackage(lpparam)` | `onPackageLoaded` / `onPackageReady(param)` |
| `lpparam.classLoader` | `param.getDefaultClassLoader()` / `param.getClassLoader()` |
| `XC_MethodHook` + before/after | `Hooker.intercept(Chain)` |
| `param.getResult()` | `chain.proceed()` 的返回值 |
| `param.setResult(x)` | `return x` |
| `param.args[i]` | `chain.getArg(i)` |
| `XposedBridge.log(msg)` | `log(priority, tag, msg)`（**实例方法**） |
| `XposedHelpers.*` | 自建 shim / `libxposed/helper` |
| `XposedBridge.hookAllMethods` | 遍历 `getDeclaredMethods()` |
| — | `onHotReloading` / `onHotReloaded`（102 新增） |
| — | `detach()` / `HookHandle.replace()`（102 新增） |

## 附 B. 参考资料

- libxposed API Javadoc —— <https://libxposed.github.io/api/>
- LSPosed Wiki：*Develop Xposed Modules Using Modern Xposed API*
- `libxposed/api` 仓库（依赖坐标 / ProGuard 规则）
- `libxposed/helper`（可选开发套件）
- 框架门槛：LSPosed 自 v2.1.0-7769 起实现 API 102
