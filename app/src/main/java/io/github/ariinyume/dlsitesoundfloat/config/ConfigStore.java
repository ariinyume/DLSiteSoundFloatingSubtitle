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
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 【M1】标准配置的**读写仓库**（设置页侧，跑在模块自己的进程里）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 落盘策略：两个通道同时写，各自负责不同消费方
 *
 *  ① 标准配置 JSON —— {@code filesDir/dlsitefloat_config.json}
 *      · PRD §FR-09 的「标准配置」本体：带 {@code schema_version} + {@code saved_at}；
 *      · 写入用「临时文件 + 原子替换」（见 {@link #writeJsonAtomic}），防写坏；
 *      · 是 M3 备份导出 / 恢复导入的唯一数据源。
 *
 *  ② SharedPreferences 平铺键 —— {@code dlsitefloat_config.xml}
 *      · PRD §5.3 方案 A1 的**跨进程通道**：hook 侧（DLsiteSound / SystemUI 进程）
 *        用 {@code XposedInterface#getRemotePreferences("dlsitefloat_config")} 直读；
 *      · 必须**同步 commit()**：commit 返回后文件已落盘，紧接着发的
 *        {@code ACTION_CONFIG_CHANGED} 广播才可能让 hook 读到新值
 *        （apply() 是异步的，会让「保存后立即重载」偶发读到旧值）。
 *
 * 写失败（磁盘满 / 权限异常，PRD §12 E-06）→ 返回 false 并带上可读原因，
 * 由调用方保留草稿 + Toast 报错；**绝不静默成功**。
 * ─────────────────────────────────────────────────────────────────────
 */
public final class ConfigStore {

    private static final String TAG = "[DLsiteSoundFloat:Config]";

    /**
     * 【2.3.0】界面语言的**独立**存储（设置页进程自己的 MODE_PRIVATE prefs）。
     *
     * 为什么要单独一份：页内切语言是**即时生效**、且**不该**触碰其它参数的动作 ——
     * 走 {@link #save} 会连带重写标准配置 JSON、刷新 saved_at、并给被注入进程发广播
     * （而语言根本不是渲染参数，发过去毫无意义）。独立存储后：
     *   · 切语言不写 JSON、不发广播、不动其它参数；
     *   · {@link #load()} 优先读这一份，于是下次进设置页仍是上次选的语言。
     */
    public static final String UI_PREFS_NAME = "dlsitefloat_ui";

    private static volatile ConfigStore sInstance;

    private final Context appCtx;
    private final SharedPreferences prefs;

    /** 最近一次加载是否发现配置损坏并已回退默认（PRD §12 E-03：设置页要提示「配置已重置」）。 */
    private volatile boolean loadResetHappened = false;
    /** 最近一次加载的说明（供日志与提示）。 */
    private volatile String loadNote = "";

    private ConfigStore(Context ctx) {
        this.appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.prefs = this.appCtx.getSharedPreferences(SubtitleConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static ConfigStore get(Context ctx) {
        ConfigStore s = sInstance;
        if (s == null) {
            synchronized (ConfigStore.class) {
                s = sInstance;
                if (s == null) {
                    s = new ConfigStore(ctx);
                    sInstance = s;
                }
            }
        }
        return s;
    }

    /** 标准配置文件的绝对路径（导入/导出、日志用）。 */
    public File configFile() {
        return new File(appCtx.getFilesDir(), SubtitleConfig.FILE_NAME);
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 读取当前已保存配置。
     *
     * 口径：**SharedPreferences 是主通道**（与 hook 侧读的是同一份，最不容易出现
     * 「设置页看到的」与「悬浮窗生效的」不一致）；JSON 文件缺失或损坏时回落默认值并记录
     * （PRD §FR-09 规则 3：禁止半套参数生效）。两条通道都没有过 → 全新安装，用默认值。
     */
    public SubtitleConfig load() {
        loadResetHappened = false;
        loadNote = "";
        SubtitleConfig result;
        try {
            if (prefs.contains(SubtitleConfig.K_SCHEMA_VERSION)) {
                result = SubtitleConfig.fromPrefs(prefs);
                loadNote = "from prefs";
            } else {
                File f = configFile();
                if (f.exists()) {
                    try {
                        result = SubtitleConfig.fromJson(new JSONObject(readText(f)));
                        loadNote = "from json";
                    } catch (Throwable t) {
                        // 配置损坏（PRD §12 E-03）：整体回退默认值并标记，让设置页提示「配置已重置」
                        loadResetHappened = true;
                        loadNote = "json corrupted, defaults used: " + t;
                        Log.w(TAG, loadNote);
                        result = SubtitleConfig.defaults();
                    }
                } else {
                    loadNote = "no config yet, defaults used";
                    result = SubtitleConfig.defaults();
                }
            }
        } catch (Throwable t) {
            loadNote = "prefs read failed: " + t;
            Log.w(TAG, loadNote);
            result = SubtitleConfig.defaults();
        }
        // 【2.3.0】界面语言以独立存储为准（页内切语言只写这一份，见 saveUiLanguageOnly）
        String uiLang = readUiLanguageOnly();
        if (uiLang != null) {
            result.uiLanguage = uiLang;
        }
        return result;
    }

    /**
     * 【2.3.0】只把界面语言存进设置页自己的 prefs（不写 JSON、不发广播、不影响其它参数）。
     *
     * 页内切语言是「即时生效的界面偏好」，不是渲染参数：写 JSON 会白白改 saved_at、
     * 发广播会让被注入进程做一次无意义的重载。所以这里只落一个键。
     *
     * @return null = 成功；非 null = 失败原因
     */
    public String saveUiLanguageOnly(String uiLanguage) {
        String lang = uiLanguage == null ? SubtitleConfig.LANG_SYSTEM : uiLanguage;
        try {
            SharedPreferences ui = appCtx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE);
            boolean ok = ui.edit().putString(SubtitleConfig.K_UI_LANGUAGE, lang).commit();
            Log.i(TAG, "ui language saved only (" + (ok ? "ok" : "commit false") + "): " + lang);
            return ok ? null : "ui language commit failed";
        } catch (Throwable t) {
            Log.w(TAG, "saveUiLanguageOnly failed: " + t);
            return "save ui language failed: " + t;
        }
    }

    /** 读独立存储里的界面语言；从未写过返回 null（此时沿用配置里的值）。 */
    public String readUiLanguageOnly() {
        try {
            SharedPreferences ui = appCtx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE);
            return ui.getString(SubtitleConfig.K_UI_LANGUAGE, null);
        } catch (Throwable t) {
            Log.w(TAG, "readUiLanguageOnly failed: " + t);
            return null;
        }
    }

    public boolean loadResetHappened() {
        return loadResetHappened;
    }

    public String loadNote() {
        return loadNote;
    }

    /** 是否有过任何一份已保存配置（决定首次进入设置页要不要提示「配置已重置」）。 */
    public boolean hasSavedConfig() {
        try {
            if (prefs.contains(SubtitleConfig.K_SCHEMA_VERSION)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return configFile().exists();
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 全量保存（PRD §FR-08 规则 4：保存采用全量保存，不分组保存）。
     *
     * @return null = 成功；非 null = 失败原因（可直接展示给用户）。
     */
    public String save(SubtitleConfig cfg) {
        if (cfg == null) {
            return "config is null";
        }
        SubtitleConfig c = cfg.copy();
        c.clamp();
        c.schemaVersion = SubtitleConfig.SCHEMA_VERSION;
        c.savedAt = SubtitleConfig.nowIso8601();

        // ① 标准配置 JSON（原子写）
        String jsonErr = writeJsonAtomic(c);
        // ② 跨进程通道（同步 commit）
        boolean prefsOk = false;
        try {
            SharedPreferences.Editor ed = prefs.edit();
            c.writeTo(ed);
            prefsOk = ed.commit();
        } catch (Throwable t) {
            Log.w(TAG, "prefs commit failed: " + t);
        }
        tryMakeWorldReadable();

        if (jsonErr != null && !prefsOk) {
            return jsonErr;
        }
        Log.i(TAG, "saved (" + (jsonErr == null ? "json ok" : "json failed: " + jsonErr)
                + ", prefs " + (prefsOk ? "ok" : "failed") + ") " + c.summary());
        // 保存成功 → 把**完整配置 JSON** 广播给被注入的进程（PRD §9.2 的热重载路径）。
        // ⚠️ 广播必须带负载（M1 真机返工根修）：设置页写的 MODE_PRIVATE prefs 与
        // hook 侧 getRemotePreferences 是两份存储（真机日志 remote prefs EMPTY 实锤），
        // 只发空通知让 hook「自己去读」永远读不到；hook 收到负载后解析生效并
        // 持久化进 remote prefs（见 RemoteConfig#applyFromJson）。
        // 旧 hotReload 开关不再拦截传输（关掉它只会让配置永远到不了 hook，反而丢配置）；
        // 「收不到广播」天然退化为 A2：下次冷启动由 hook 侧 remote prefs 兜底。
        ConfigBus.sendConfigChanged(appCtx, c.toJson().toString());
        return null;
    }

    /**
     * 自愈补推：把**已保存**的配置再广播一次（不带草稿）。
     *
     * 场景：用户在 DLsiteSound 未运行时保存过配置 —— 那次广播没有接收者，
     * remote prefs 里也就没有这份配置；等宿主进程跑起来（探测到 PONG）后，
     * 设置页打开/回到前台时补推一次，宿主就能把配置持久化进 remote prefs。
     */
    public void pushSavedConfigToHooks() {
        try {
            if (!hasSavedConfig()) {
                return;
            }
            SubtitleConfig c = load();
            ConfigBus.sendConfigChanged(appCtx, c.toJson().toString());
            Log.i(TAG, "saved config pushed to hooks: " + c.summary());
        } catch (Throwable t) {
            Log.w(TAG, "pushSavedConfigToHooks failed: " + t);
        }
    }

    /**
     * 临时文件 + 原子替换写入（PRD §FR-09 规则 2）。
     *
     * 顺序：写 {@code xxx.json.tmp} → fsync → rename 覆盖。
     * rename 在同一目录内是原子的，任何时刻读到的都是「完整的旧配置」或「完整的新配置」，
     * 不会出现半个文件。
     *
     * @return null = 成功；非 null = 失败原因
     */
    private String writeJsonAtomic(SubtitleConfig c) {
        File target = configFile();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return "cannot create config dir: " + parent;
            }
            byte[] bytes = c.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                    // 某些 ROM 的 FUSE 上 fsync 会抛；不影响 rename 的原子性
                }
            }
            if (!tmp.renameTo(target)) {
                // 个别 ROM 上跨已有文件 rename 会失败：删掉目标再试一次
                if (!target.delete() || !tmp.renameTo(target)) {
                    tmp.delete();
                    return "rename failed: " + tmp + " -> " + target;
                }
            }
            return null;
        } catch (Throwable t) {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
            return "write failed: " + t;
        }
    }

    /**
     * 尽力把配置文件 / SharedPreferences 置为「其他进程可读」。
     *
     * PRD §5.3 A1 写的是 world-readable，但 Android 7+（API 24）起
     * {@code MODE_WORLD_READABLE} 直接抛 {@code SecurityException}。
     * 实际跨进程通道走的是 LSPosed 的 {@code getRemotePreferences}（由框架以高权限读模块
     * 数据目录），**不依赖文件权限**；这里只是 best-effort 兜底，失败完全不影响功能。
     */
    private void tryMakeWorldReadable() {
        try {
            File f = configFile();
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    // 文本读取（导入 / 兜底路径共用）
    // ==================================================================

    public static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readText(in);
        }
    }

    public static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
