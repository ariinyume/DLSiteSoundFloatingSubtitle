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
package io.github.ariinyume.dlsitesoundfloat.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.InputStream;

import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * 【M1】hook 侧（被注入进程）的**只读**配置访问点 —— PRD §5.3 方案 A1 的读取端。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 读取链（按可靠性排序，命中即返回）
 *
 *  ① {@code XposedInterface#getRemotePreferences(SubtitleConfig.PREFS_NAME)}
 *     —— libxposed API 102 提供的跨进程 SharedPreferences，等价传统 Xposed 的
 *        XSharedPreferences。**首选**：设置页保存时同步 commit 的就是这一份。
 *     前置：框架要声明 {@code PROP_CAP_REMOTE} 能力（见 {@link #read()}）。
 *
 *  ② **本进程本地 prefs**（{@code Context#getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE)}）
 *     —— 【2.3.0 方案 K】跨进程持久化的**真落点**。
 *        背景（真机铁证，2026-10）：LSPosed 2.2.0 里被注入进程拿到的
 *        {@code getRemotePreferences} 是**只读**实现，写它必抛
 *        {@code UnsupportedOperationException: Read only implementation}；
 *        而设置页进程没有 XposedInterface，也写不了那一份 ⇒ remote prefs 恒为空
 *        （日志 {@code remote prefs getAll(): EMPTY}、{@code remote files: []}）。
 *        于是「保存」只能靠广播送到 hook 侧内存，进程一重启就丢。
 *        修法：hook 侧把广播来的配置**写进自己进程的私有 prefs**，下次冷启动直接读它。
 *
 *  ③ {@code XposedInterface#openRemoteFile(SubtitleConfig.FILE_NAME)}
 *     —— 兜底读标准配置 JSON（设置页原子写的那一份）。
 *        个别框架仍能走通这条路时，它也是可靠来源。
 *
 *  ④ 默认值 —— 三条路都不可用时（框架不支持 / 配置还没写过 / 读取异常）：
 *     按 PRD §FR-09 规则 3「读取失败整体回退默认值」，**绝不半套生效**。
 *
 * ⚠️ 缓存策略：进程内缓存一次，只有收到 {@link ConfigBus#ACTION_CONFIG_CHANGED}
 *    才重读（见 {@link ConfigBus#installResponder}）。所以 {@link #get()} 在渲染
 *    热路径上调用是零成本的 —— 它只做一次 volatile 读。
 *
 * ⚠️ 日志一律走 {@link XposedCompat#log}：本类只在**被注入进程**里跑，
 *    而 {@code android.util.Log} 不进 LSPosed 日志包（等于排查时看不见，见 XposedCompat 类头）。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class RemoteConfig {

    private static final String TAG = "[DLsiteSoundFloat:RemoteConfig]";

    /**
     * 【2.3.0 方案 K】hook 侧**本地**持久化用的 SharedPreferences 名。
     *
     * 与 {@link SubtitleConfig#PREFS_NAME}（{@code dlsitefloat_config}）刻意区分开：
     * 后者是设置页写的、给 remote prefs 用的镜像；这一份只由被注入进程自己写自己读。
     */
    public static final String LOCAL_PREFS_NAME = "dlsitefloat_hook";

    private static final Object LOCK = new Object();

    /** 进程内缓存；null = 尚未读取。 */
    private static volatile SubtitleConfig sCache = null;
    /** 配置代数：每次 reload 自增。渲染缓存键里带上它即可实现「配置一变就重绘」。 */
    private static volatile int sRevision = 0;
    /** 最近一次读取的来源标识（日志/排查）。 */
    private static volatile String sSource = "unread";

    /**
     * 【2.3.0 方案 K】本进程的 Application Context（由 {@link ConfigBus#installResponder} 注入）。
     *
     * 唯一用途：给 {@link #persistToLocalPrefs} 提供写本地 prefs 的 Context。
     * 拿不到（= 还没注入）时本地持久化静默跳过 —— 内存生效不受影响。
     */
    private static volatile Context sAppCtx = null;

    private RemoteConfig() {
    }

    /** 由被注入进程在拿到真实 Context 的第一时刻调用（传 applicationContext）。 */
    public static void attachContext(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Context app = ctx.getApplicationContext();
            sAppCtx = app != null ? app : ctx;
        } catch (Throwable t) {
            sAppCtx = ctx;
        }
    }

    /** 当前生效配置（恒非 null）。 */
    public static SubtitleConfig get() {
        SubtitleConfig c = sCache;
        if (c != null) {
            return c;
        }
        synchronized (LOCK) {
            if (sCache == null) {
                sCache = read();
                sRevision++;
            }
            return sCache;
        }
    }

    /** 配置代数（每次成功重载 +1）。供渲染缓存键使用。 */
    public static int revision() {
        get();
        return sRevision;
    }

    /** 最近一次读取来源（"remote-prefs" / "hook-local-prefs" / "remote-file" / "defaults" / "broadcast"）。 */
    public static String source() {
        get();
        return sSource;
    }

    /**
     * 【2.2.7 / code 979】诊断日志是否开启（供被注入进程的高频日志做闸门）。
     *
     * <p>语义 = 「用户是否在设置页打开了『调试日志』」。关闭（默认）时，
     * 状态 Map 全量转储、轮询自证等**诊断级**日志一律不输出，只留里程碑与 WARN。
     *
     * <p>⚠️ 热路径友好：{@link #get()} 走的是 volatile 读（进程内缓存），
     * 首次调用才会真正读一次配置，之后零成本 —— 可以放心在每次轮询/每次采样里调用。
     */
    public static boolean debugLog() {
        try {
            return get().debugLog;
        } catch (Throwable t) {
            // 读配置本身出异常时宁可少打日志，绝不让日志闸门把主流程拖下水
            return false;
        }
    }

    /** 强制重读（收到配置变更广播时调用）。 */
    public static void reload() {
        synchronized (LOCK) {
            sCache = read();
            sRevision++;
        }
    }

    /**
     * 从广播负载直接应用配置（M1 真机返工的根修通道）。
     *
     * 背景：设置页写的是模块自己进程的 {@code MODE_PRIVATE} prefs，而本进程
     * {@code getRemotePreferences} 读的是 LSPosed 框架托管的另一份存储 —— 两边不是
     * 同一份文件，真机日志 {@code remote prefs getAll(): EMPTY} 是铁证。因此「保存」
     * 的实际传输通道只能是广播：设置页把完整配置 JSON 塞进
     * {@link ConfigBus#ACTION_CONFIG_CHANGED}，本方法负责
     * ① 解析（失败返回 false，调用方退回 {@link #reload()}）；
     * ② 内存生效（cache + revision++，渲染端自动重绘）；
     * ③ 持久化：写本进程本地 prefs（{@link #persistToLocalPrefs}）—— 方案 K 的真落点，
     *    下次进程冷启动 {@link #read()} 第 ② 步就能读到配置。
     *    ⚠️ 【2.3.1】不再尝试写 remote prefs（必抛只读异常，见方法体注释，用例 10.5）。
     *
     * @return 是否成功解析并应用
     */
    public static boolean applyFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        SubtitleConfig c;
        try {
            c = SubtitleConfig.fromJson(new JSONObject(json));
        } catch (Throwable t) {
            logWarn("applyFromJson parse failed: " + t);
            return false;
        }
        synchronized (LOCK) {
            sCache = c;
            sRevision++;
            sSource = "broadcast";
        }
        log("config applied from broadcast: " + c.summary());
        // 【2.3.1 · 用例 10.5】不再尝试写 remote prefs。
        //
        // 真机现状（LSPosed 2.2.0(7854)）：框架**声明**了 PROP_CAP_REMOTE，能力检查会通过，
        // 但被注入进程拿到的 getRemotePreferences 是**只读实现** —— 每次 edit().commit()
        // 都必抛 UnsupportedOperationException: Read only implementation，日志里刷一条
        // `persistToRemotePrefs failed: …`（LSPosed_20261005_204342 可复现）。
        // 这个写入**从来没有成功过**，也**没有任何用处**（remote prefs 在本进程恒为空，
        // 读取链第 ① 步永远读不到），纯粹是每次保存都留一条吓人的 ERROR/WARN。
        // 用例 10.5 的判据就是「日志里不得再出现这两串」，所以直接删掉这次尝试。
        // 持久化的唯一真落点 = 下面这句本地 prefs（方案 K），保持不变。
        persistToLocalPrefs(c);
        return true;
    }

    /**
     * 【2.3.1 方案 K】把配置写进**本进程**的私有 prefs —— 跨进程持久化的真落点。
     *
     * 为什么必须自己存一份：remote prefs 在被注入进程里是只读的（见类头），
     * 而广播只在进程活着时才送得到；进程一重启，配置就只剩默认值。
     * 本地 prefs 与被注入进程同生命周期，读写都是普通 App 权限，一定能落盘。
     *
     * 失败（Context 还没注入 / 磁盘异常）只打 WARN：内存已生效，本次会话不受影响。
     */
    private static void persistToLocalPrefs(SubtitleConfig c) {
        Context ctx = sAppCtx;
        if (ctx == null) {
            log("local prefs persistence skipped (no application context attached yet)");
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            c.writeTo(ed);
            boolean ok = ed.commit();
            log("config persisted to hook-local prefs (commit=" + ok + ")");
        } catch (Throwable t) {
            logWarn("persistToLocalPrefs failed: " + t);
        }
    }

    // ==================================================================
    // 实际读取
    // ==================================================================

    private static SubtitleConfig read() {
        XposedInterface api = XposedCompat.api();
        if (api == null) {
            sSource = "defaults (no XposedInterface)";
            log("no XposedInterface, using defaults");
            return SubtitleConfig.defaults();
        }

        // ① 跨进程 SharedPreferences（首选）
        try {
            long caps = api.getFrameworkProperties();
            if ((caps & XposedInterface.PROP_CAP_REMOTE) != 0) {
                SharedPreferences sp = api.getRemotePreferences(SubtitleConfig.PREFS_NAME);
                if (sp != null && sp.contains(SubtitleConfig.K_SCHEMA_VERSION)) {
                    SubtitleConfig c = SubtitleConfig.fromPrefs(sp);
                    sSource = "remote-prefs";
                    log("config loaded via remote prefs: " + c.summary());
                    return c;
                }
                log("remote prefs empty (settings page never saved?)");
                // 防御性诊断：把「没写」与「写了但 group 名/文件不对」区分开，
                // 下次排查一眼就能定位（是 prefs 根本没落盘，还是读错了份）。
                logDiagnostics();
                logPrefsContents(sp);
            } else {
                log("framework has no PROP_CAP_REMOTE, trying remote file");
            }
        } catch (Throwable t) {
            logWarn("getRemotePreferences failed: " + t);
        }

        // ② 【2.3.0 方案 K】本进程本地 prefs（上次广播时自己存的那份）
        try {
            Context ctx = sAppCtx;
            if (ctx != null) {
                SharedPreferences local = ctx.getSharedPreferences(LOCAL_PREFS_NAME,
                        Context.MODE_PRIVATE);
                if (local.contains(SubtitleConfig.K_SCHEMA_VERSION)) {
                    SubtitleConfig c = SubtitleConfig.fromPrefs(local);
                    sSource = "hook-local-prefs";
                    log("config loaded via hook-local prefs: " + c.summary());
                    return c;
                }
                log("hook-local prefs empty (no broadcast persisted yet)");
            } else {
                log("hook-local prefs skipped (no application context attached yet)");
            }
        } catch (Throwable t) {
            logWarn("read hook-local prefs failed: " + t);
        }

        // ③ 兜底：直接读标准配置 JSON
        try {
            ParcelFileDescriptor pfd = api.openRemoteFile(SubtitleConfig.FILE_NAME);
            if (pfd != null) {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                    JSONObject o = new JSONObject(ConfigStore.readText(in));
                    SubtitleConfig c = SubtitleConfig.fromJson(o);
                    sSource = "remote-file";
                    log("config loaded via remote file: " + c.summary());
                    return c;
                }
            }
        } catch (Throwable t) {
            logWarn("openRemoteFile(" + SubtitleConfig.FILE_NAME + ") failed: " + t);
        }

        // ④ 全部失败 → 默认值（PRD §FR-09 规则 3）
        sSource = "defaults";
        log("config unavailable, using defaults");
        return SubtitleConfig.defaults();
    }

    /** 仅供诊断：列出框架可见的模块文件（排查 ① ② 都不通时用）。 */
    public static void logDiagnostics() {
        XposedInterface api = XposedCompat.api();
        if (api == null) {
            return;
        }
        try {
            String[] files = api.listRemoteFiles();
            StringBuilder sb = new StringBuilder();
            if (files != null) {
                for (String f : files) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(f);
                }
            }
            log("remote files: [" + sb + "]");
        } catch (Throwable t) {
            logWarn("listRemoteFiles failed: " + t);
        }
    }

    /**
     * 仅供诊断：打印 {@code getRemotePreferences} 拿到的 sp 的实际内容 key 列表。
     *
     * 用来区分两种「remote prefs empty」：
     *   · getAll() 完全为空 → 设置页**从未成功保存**（prefs 没落盘）；
     *   · getAll() 有键但缺 {@link SubtitleConfig#K_SCHEMA_VERSION} →
     *     读错了文件 / group 名不一致 / 键名漂移。
     */
    private static void logPrefsContents(SharedPreferences sp) {
        if (sp == null) {
            log("remote prefs handle is null (getRemotePreferences returned null)");
            return;
        }
        try {
            java.util.Map<String, ?> all = sp.getAll();
            if (all == null || all.isEmpty()) {
                log("remote prefs getAll(): EMPTY (no keys -> settings page never wrote)");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String k : all.keySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(k);
            }
            log("remote prefs getAll(): [" + sb + "] (hasSchema="
                    + sp.contains(SubtitleConfig.K_SCHEMA_VERSION) + ", count=" + all.size() + ")");
        } catch (Throwable t) {
            logWarn("remote prefs getAll() failed: " + t);
        }
    }

    private static void log(String msg) {
        XposedCompat.log(TAG + " " + msg);
    }

    private static void logWarn(String msg) {
        XposedCompat.log(TAG + " WARN " + msg);
    }
}
