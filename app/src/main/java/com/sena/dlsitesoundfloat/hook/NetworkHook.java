package com.sena.dlsitesoundfloat.hook;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.util.NetLogFile;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 拦截 DLsiteSound 的网络响应，提前读取字幕文件。
 *
 * ── v33（1.20.9）：第一次尝试修「音频缓存缺开头」──
 * 给 peek 加了白名单，判据之一是「URL 含 {@code /optimized/}`」。
 *
 * ── v34（1.20.10）：**发现 v33 的白名单根本没拦住音频** ──
 *
 * 症状：v33 装到真机后，缓存音频**仍然缺开头**，只是从 **44 秒缩到 10 秒**。
 *
 * 根因（Ari 2026-09-14 21:26 日志铁证）：**音频文件本身就放在 `/optimized/` 目录下** ——
 *
 *   …/content/work/doujin/RJ01696000/RJ01695219/optimized/bf225a84341ad6c28a693d10842a91d2.mp3
 *   …/content/work/doujin/RJ01696000/RJ01695219/optimized/79d82fcb92f012c58a24dbe653c8e1dd.json
 *
 * 而 v33 的白名单第 ① 条是「URL 含 {@code /optimized/} → 直接判定为字幕候选」，
 * 且这条**排在所有拒绝条件之前**。于是每一个 mp3（以及它的每一个 206 分片）
 * 照样走 {@link #peekBodyText}。唯一的区别只是 {@link #PEEK_LIMIT} 从 1MB 收到 256KB ——
 * 而缺口的长度正好随 peek 大小线性变化：**44s × (256KB/1MB) ≈ 11s ≈ 实测的 10s**。
 * 这个比例关系就是「peek 就是元凶」最硬的证据。
 *
 * 修法（v34）：
 *   1. **只认 {@code .json}`**：只有 URL 里出现 {@code .json}、或 content-type 含 {@code json} 才 peek。
 *      去掉 {@code /optimized/} 这条 —— 它是错误的强判据，音频与字幕共用这个目录。
 *   2. **先拒绝、后放行**：状态码 ≠ 200（206 分片）、体积超限、content-type 是媒体、
 *      URL 后缀是媒体扩展名 —— 任一条命中立刻 0 开销返回（**连 NetLogFile 都不写**）。
 *   3. **删掉 {@code java.net.URL.toString()} 这条兜底日志钩子**：它是早期用来找字幕接口的，
 *      现在字幕已经稳定从 {@code Response$Builder.build()} 拿到，它只剩开销 ——
 *      而且它会为**每一个音频 URL** 打一行日志（见日志里成串的 `URL captured: …mp3`）。
 *   4. {@link #PEEK_LIMIT} 256KB → **128KB**（实测字幕 JSON 只有几 KB，243 cues 也远小于它）。
 */
public class NetworkHook {
    private static final String TAG = "[DLsiteSoundFloat:Network]";

    /**
     * 允许 peek 的最大 body 长度。
     *
     * v34：256KB → **128KB**。字幕 JSON 实测只有几 KB（最多见过 243 cues），
     * 128KB 已经宽松几十倍；再大的一律是音频 / 封面图，绝不该碰。
     */
    private static final long PEEK_LIMIT = 128 * 1024;

    /**
     * 疑似音频 / 视频 / 图片 / 二进制流的 content-type 关键词 —— 命中就**完全不碰**这个响应。
     */
    private static final String[] MEDIA_CT_HINTS = {
            "audio/", "video/", "image/", "octet-stream",
            "mpeg", "mp4", "zip", "font", "pdf", "event-stream"};

    /**
     * 媒体扩展名 —— URL 里出现就**完全不碰**。
     *
     * ⚠️ 这条是 v34 的关键防线：音频与字幕 JSON 在同一个 {@code /optimized/} 目录下，
     * 只能靠**扩展名**区分，不能靠目录。
     */
    private static final String[] MEDIA_EXT_HINTS = {
            ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".opus", ".flac", ".wma",
            ".mp4", ".m4v", ".webm", ".mkv", ".ts", ".m3u8",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg"};

    /** 第一次略过媒体响应时，往 logcat 打一行完整证据（只打一次），便于确认白名单真的生效。 */
    private static boolean sFirstSkipLogged = false;

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        NetLogFile.init(repo.getAppContext());
        hookOkHttpResponseBuilder(cl, repo);
        hookOkHttpRealCall(cl, repo);
    }

    private static void hookOkHttpResponseBuilder(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.Response$Builder", cl);
            Method buildMethod = XposedHelpers.findMethodExact(builderClass, "build");
            XposedBridge.hookMethod(buildMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object response = param.getResult();
                    if (response == null) {
                        return;
                    }
                    captureResponse(response, repo);
                }
            });
            XposedBridge.log(TAG + " hooked okhttp3.Response$Builder.build() [v34 json-only peek]");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " okhttp3.Response$Builder hook failed: " + e.getMessage());
        }
    }

    private static void hookOkHttpRealCall(ClassLoader cl, SubtitleRepository repo) {
        // 仅做兜底尝试；DLsiteSound 的 okhttp3 常重打包导致 RealCall 找不到
        try {
            Class<?> realCallClass = XposedHelpers.findClass("okhttp3.RealCall", cl);
            Method executeMethod = XposedHelpers.findMethodExact(realCallClass, "execute");
            XposedBridge.hookMethod(executeMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object response = param.getResult();
                    if (response == null) {
                        return;
                    }
                    captureResponse(response, repo);
                }
            });
            XposedBridge.log(TAG + " hooked okhttp3.RealCall.execute()");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " okhttp3.RealCall hook failed: " + e.getMessage());
        }
    }

    private static void captureResponse(Object response, SubtitleRepository repo) {
        String url = getUrlFromResponse(response);

        // v34：不是字幕候选就**立刻返回** —— 不 peek、不落盘、不写日志。
        // 音频缓存期间这一条最关键：媒体响应从此与本模块零接触。
        if (!isSubtitleCandidate(response, url)) {
            if (!sFirstSkipLogged) {
                sFirstSkipLogged = true;
                XposedBridge.log(TAG + " skip non-subtitle response (no peek):"
                        + " ctype=" + contentTypeOf(response)
                        + " code=" + codeOf(response)
                        + " url=" + (url == null ? "(null)" : url));
            }
            return;
        }

        NetLogFile.log("RESP url=" + (url == null ? "(null)" : url));

        String text = peekBodyText(response);
        boolean hasWebvtt = text != null
                && (text.contains("\"webvtt\"") || text.contains("\"subtitles\""));
        NetLogFile.log("  bodyLen=" + (text == null ? -1 : text.length()) + " hasSubtitleJson=" + hasWebvtt);

        if (!hasWebvtt) {
            return;
        }
        try {
            // 优先按完整结构解析（{data:{webvtt:[...]}}）
            repo.loadFromJson(text);
            int n = repo.getCues().size();
            XposedBridge.log(TAG + " >> parsed subtitle JSON from " + (url == null ? "(unknown url)" : url)
                    + " cues=" + n);
            NetLogFile.log("  >> PARSED cues=" + n);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " parse error: " + e.getMessage());
            NetLogFile.log("  parseErr=" + e.getMessage());
        }
    }

    /**
     * 这个响应值不值得 peek？—— v34 的**严格白名单**。
     *
     * 判定顺序（先便宜后昂贵，任一条命中就立刻定论）：
     *   ① 状态码不是 200（典型是 206 分片 / 3xx / 4xx）→ **否**（媒体分片绝不碰）；
     *   ② URL 含媒体扩展名（{@link #MEDIA_EXT_HINTS}）→ **否**
     *      —— ⚠️ 音频就在 {@code /optimized/} 里，这条是唯一能把它挡住的判据；
     *   ③ content-length 超过 {@link #PEEK_LIMIT} → **否**（大文件不可能是字幕 JSON）；
     *   ④ content-type 命中 {@link #MEDIA_CT_HINTS} → **否**（音频 / 视频 / 图片 / 二进制）；
     *   ⑤ 放行条件（两个都要走到底）：URL 含 {@code .json}，或 content-type 含 {@code json}。
     * 其余一律**不放行** —— 宁可漏掉一个不认识的字幕接口，也不能再碰音频流。
     */
    private static boolean isSubtitleCandidate(Object response, String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);

        // ① 只处理完整响应；206 分片是流媒体的标志，一律不碰。
        Integer code = codeOf(response);
        if (code == null || code != 200) {
            return false;
        }

        // ② URL 扩展名是媒体 → 立刻否。音频与字幕同在 /optimized/，只能靠扩展名区分。
        for (String ext : MEDIA_EXT_HINTS) {
            if (u.contains(ext)) {
                return false;
            }
        }

        String ct = contentTypeOf(response);

        // ③ 体积门。
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) {
                return false;
            }
            Object lenObj = XposedHelpers.callMethod(body, "contentLength");
            if (lenObj instanceof Long && (Long) lenObj > PEEK_LIMIT) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        // ④ content-type 是媒体 → 否。
        for (String hint : MEDIA_CT_HINTS) {
            if (ct.contains(hint)) {
                return false;
            }
        }

        // ⑤ 放行：只认 json。
        if (u.contains(".json") || ct.contains("json")) {
            return true;
        }
        if (u.contains("subtitle") || u.contains("webvtt")) {
            return true;
        }
        return false;
    }

    private static String contentTypeOf(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) {
                return "";
            }
            Object ct = XposedHelpers.callMethod(body, "contentType");
            return ct == null ? "" : ct.toString().toLowerCase(Locale.US);
        } catch (Throwable e) {
            return "";
        }
    }

    private static Integer codeOf(Object response) {
        try {
            Object c = XposedHelpers.callMethod(response, "code");
            return c instanceof Integer ? (Integer) c : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String peekBodyText(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "peekBody", PEEK_LIMIT);
            if (body == null) {
                return null;
            }
            return (String) XposedHelpers.callMethod(body, "string");
        } catch (Throwable e) {
            NetLogFile.log("  peekErr=" + e.getMessage());
            return null;
        }
    }

    private static String getUrlFromResponse(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) {
                return null;
            }
            Object url = XposedHelpers.callMethod(request, "url");
            if (url == null) {
                return null;
            }
            // HttpUrl.toString() 或 String
            Object s = XposedHelpers.callMethod(url, "toString");
            return s == null ? null : s.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
