package com.sena.dlsitesoundfloat.util;

import android.content.Context;
import android.os.Environment;

import com.sena.dlsitesoundfloat.data.SubtitleRepository;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把关键网络诊断写到文件，便于二次定位真实请求路径。
 *
 * v5 修复「文件根本不存在」的原因：本类原先在 NetworkHook.hook() 时 init(ctx)，
 * 但那一刻 Application.attach 还没跑，repo.getAppContext() 仍是 null →
 * 退回到 /sdcard/Download（Android 11+ 无权限静默失败）→ FILE 是个写不进去的路径，
 * 且之后再也不会重新初始化，于是整个会话一条都没落盘。
 *
 * 现在改成【首次写日志时惰性初始化】，此时 App 上下文一定已就绪；
 * 优先写 getExternalFilesDir（Android/data/<pkg>/files/，无需任何权限），
 * 拿不到再退回内部 getFilesDir（有 root/LSPosed 时同样可读）。
 */
public class NetLogFile {
    private static File FILE;
    private static final int MAX = 200;
    private static int count = 0;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 早期调用（可能拿不到 context）；拿不到就什么都不做，留给 log() 惰性重试。 */
    public static synchronized void init(Context ctx) {
        if (FILE != null) {
            return;
        }
        ensureFile(ctx);
    }

    private static void ensureFile(Context ctx) {
        if (ctx == null) {
            ctx = SubtitleRepository.getInstance().getAppContext();
        }
        if (ctx == null) {
            return; // 还没有上下文，下次再试
        }
        File dir = null;
        try {
            dir = ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (dir == null) {
            try {
                dir = ctx.getFilesDir();
            } catch (Throwable ignored) {
            }
        }
        if (dir == null) {
            try {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            } catch (Throwable ignored) {
            }
        }
        if (dir == null) {
            return;
        }
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        FILE = new File(dir, "dlsitefloat_net.log");
        count = 0;
        try {
            FileWriter fw = new FileWriter(FILE, false);
            fw.write(SDF.format(new Date()) + " === dlsitefloat net log start ===\n");
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void log(String line) {
        if (FILE == null) {
            ensureFile(null); // 惰性初始化
        }
        if (FILE == null || count >= MAX) {
            return;
        }
        count++;
        try {
            FileWriter fw = new FileWriter(FILE, true);
            fw.write(SDF.format(new Date()) + " " + line + "\n");
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {
        }
    }

    /** 诊断用：当前落盘路径。 */
    public static synchronized String getPath() {
        return FILE == null ? "(null)" : FILE.getAbsolutePath();
    }
}
