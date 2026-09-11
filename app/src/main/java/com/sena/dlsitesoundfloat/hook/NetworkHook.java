package com.sena.dlsitesoundfloat.hook;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;
import com.sena.dlsitesoundfloat.util.NetLogFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 拦截 DLsiteSound 网络响应，提前读取字幕文件。
 *
 * 关键修正（基于真实日志）：
 * 之前靠「URL 关键词」门控，而 getUrlFromResponse 在混淆/特殊 okhttp3 构建下每次都抛异常、
 * 被 catch 吞掉，导致 build() 钩子虽然挂上了却全程静默、零解析。
 *
 * 新策略：
 * 1. 不再依赖 URL —— 对每个 okhttp3 响应直接 peekBody 扫 body 文本，
 *    只要含 "webvtt" / "subtitles" 就当字幕 JSON 解析。
 * 2. 把前 150 个响应的 url / body 长度 / 是否含 webvtt 落盘到
 *    /sdcard/Download/dlsitefloat_net.log，便于二次定位真实网络路径。
 * 3. URL 仅用于日志展示（best-effort），不参与门控。
 */
public class NetworkHook {
    private static final String TAG = "[DLsiteSoundFloat:Network]";
    private static final long PEEK_LIMIT = 1024 * 1024; // 1MB，字幕 JSON 很小

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        NetLogFile.init(repo.getAppContext());
        hookOkHttpResponseBuilder(cl, repo);
        hookOkHttpRealCall(cl, repo);
        hookJavaNetUrl(cl, repo);
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
            XposedBridge.log(TAG + " hooked okhttp3.Response$Builder.build()");
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

    private static void hookJavaNetUrl(ClassLoader cl, SubtitleRepository repo) {
        try {
            XposedHelpers.findAndHookMethod("java.net.URL", cl, "toString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String url = (String) param.getResult();
                    if (url != null && (url.contains("optimized")
                            || url.toLowerCase().contains("subtitle")
                            || url.toLowerCase().contains("webvtt"))) {
                        XposedBridge.log(TAG + " URL captured: " + url);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked java.net.URL.toString() as fallback logger");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " java.net.URL hook failed: " + e.getMessage());
        }
    }

    private static void captureResponse(Object response, SubtitleRepository repo) {
        String url = getUrlFromResponse(response);
        // 落盘诊断：前 150 个响应
        NetLogFile.log("RESP url=" + (url == null ? "(null)" : url));

        String text = peekBodyText(response);
        boolean hasWebvtt = text != null && (text.contains("\"webvtt\"") || text.contains("\"subtitles\""));
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
