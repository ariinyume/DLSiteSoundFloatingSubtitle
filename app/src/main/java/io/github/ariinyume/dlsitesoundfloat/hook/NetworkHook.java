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
package io.github.ariinyume.dlsitesoundfloat.hook;

import io.github.ariinyume.dlsitesoundfloat.data.SubtitleRepository;
import io.github.ariinyume.dlsitesoundfloat.util.NetLogFile;
import io.github.ariinyume.dlsitesoundfloat.util.Shape;
import io.github.ariinyume.dlsitesoundfloat.util.XposedCompat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.ariinyume.dlsitesoundfloat.util.LogGate;

/**
 * 拦截宿主网络响应，读取字幕 JSON。
 *
 * <h3>沿革</h3>
 * <ul>
 *   <li><b>v34（1.20.10）</b>：从 {@code okhttp3.Response$Builder.build()} 里 peek 响应体，
 *       靠 URL 后缀与 content-type 白名单过滤。<b>这条路在未混淆宿主上一直有效</b>。</li>
 *   <li><b>code 964</b>：改挂「宿主读文本响应」的那个方法 —— 真机<b>零命中</b>。</li>
 *   <li><b>code 965</b>：改挂 expo-fetch 的「正文累加器」 —— 真机<b>仍然零命中</b>。</li>
 *   <li><b>code 966（本版）</b>：回到 v34 的那一步（{@code Response.Builder.build()}）
 *       —— 它是<b>所有路径都必须经过</b>的，并用「写时复制快照」复刻官方已删掉的非消费读取。</li>
 * </ul>
 *
 * <h3>🔴🔴 为什么必须重写：宿主 2.20.2 上了 R8 混淆</h3>
 * 真机日志（宿主 2.20.2 + 本模块 2.1.3/code 958）里，两条老通道<b>全部</b>失败：
 * <pre>
 * [DLsiteSoundFloat:Network] okhttp3.Response$Builder hook failed: okhttp3.Response$Builder
 * [DLsiteSoundFloat:Network] okhttp3.RealCall hook failed: okhttp3.RealCall
 * </pre>
 * 离线核实（dex 层）：全包 16052 个类里 {@code okhttp3.*} <b>只剩 1 个</b>
 * （{@code PublicSuffixDatabase}），其余被 R8 改名成 {@code Lvk/*} 之类短名。
 * ⇒ 老实现<b>一个字幕 JSON 都抓不到</b> ⇒ {@code hasCues=false} ⇒ 三处 UI 一起显示「无字幕」。
 *
 * <p><b>并且不能改成硬编码混淆名</b>：R8 每次宿主发版都重新生成映射表
 * （本次实测 ResponseBody = {@code Lvk/H;}，下一版就未必是）。
 *
 * <h3>🔴 两次失败的教训（964 / 965）：别猜「宿主用哪个方法读正文」</h3>
 * 两版都在猜某个<b>读取方法</b>会被调用，结果真机上钩子挂得好好的、<b>一次都没触发</b>。
 * 同一个 App 里可能同时装好几套网络栈（RN 一套、expo 一套、图片库一套），
 * 「类名存活 ⇒ 能挂上」<b>只说明能挂，不说明那条路会被走</b>。
 * ⇒ 采集点必须选<b>所有路径的公共必经点</b>：{@code Response.Builder.build()}。
 * 离线实测它在宿主全包被调用 <b>20 处</b>（网络 / 缓存 / 各拦截器包装都要经过），
 * 一旦返回，正文对象就挂在响应对象的<b>字段</b>上 —— 方法会被 R8 改名或 inline，
 * <b>字段不会</b>。
 *
 * <h3>✅ 本版方案：构建点 + 写时复制 peek + 全形状定位</h3>
 * <ol>
 *   <li><b>怎么读正文不消费</b>：复刻 okhttp 自己的 {@code peekBody(byteCount)}
 *       （R8 把它当无人调用的方法删了）——
 *       {@code source()} → {@code request(N)}（把字节拉进响应体自带的缓冲区，不消费）
 *       → {@code buffer()} → <b>{@code clone()}（okio 的写时复制快照）</b>
 *       → 只从<b>副本</b>里 {@code readByteArray()}。
 *       ⚠️ {@code clone()} 走的是 okio 的 {@code Buffer.copy()}（反汇编实证：新建段表 + 逐段拷贝 +
 *       标记 SHARED），<b>不是</b> {@code Object.clone()} 的浅拷贝 ⇒ 读副本不会移动原缓冲区的读指针。</li>
 *   <li><b>怎么找类</b>：从存活类名锚点（{@code expo.modules.fetch.NativeResponse} /
 *       RN 网络模块）的<b>方法签名</b>解出响应类；再从响应类的<b>字段类型</b>解出正文类
 *       （五中其四的「响应体特征」阈值）；再从响应类的无参方法解出构建器与 {@code build()}；
 *       okio 链路（{@code request(long):boolean} / {@code buffer()} / {@code clone()} /
 *       {@code readByteArray()}）全部按形状解析。<b>零硬编码混淆名</b>。</li>
 *   <li><b>怎么防误中</b>：真正正文对象命中 5/5 个特征，响应对象自己只有 2 个、
 *       其余字段 ≤ 2 个 ⇒ 阈值 4 有充分区分力（离线判据 G2.4/G2.5 钉死）。</li>
 * </ol>
 *
 * <h3>过滤顺序（先闸门后解析，媒体一律 0 开销）</h3>
 * <ol>
 *   <li>{@code contentLength()} &gt; 512KB ⇒ 直接返回，<b>连字符串检查都不做</b>。</li>
 *   <li>content-type 命中媒体关键词 ⇒ 直接返回。</li>
 *   <li>URL 后缀是媒体扩展名 ⇒ 直接返回。</li>
 *   <li>必须「体积已知且小」或「像 JSON」或「URL 是 .json」才敢 peek
 *       —— {@code request(N)} 会阻塞到 N 字节到齐，绝不能对音视频流调用。</li>
 *   <li>正文里必须同时有「容器键」与「cue 时间键」⇒ 判为字幕。</li>
 * </ol>
 * 字幕 JSON 实测几 KB 级（最多见过 324 cues，约 60–80KB）；512KB 是「gzip 后拿不到长度时」的拉取上限。
 *
 * <h3>URL 从哪来</h3>
 * R8 把 {@code Response.request()} / {@code Request.url()} 都 inline 掉了，但<b>字段还在</b>：
 * 顺着「响应 → 请求 → 网址对象」的字段链反射读出来（网址对象的形状是有 {@code toUri()}）。
 * 取不到也不影响抓取 —— 只是少一条日志与一条判据。
 */
public class NetworkHook {
    private static final String TAG = "[DLsiteSoundFloat:Network]";

    /** 宿主网络模块类名（R8 未混淆 —— 被 RN proguard 规则 protect）。 */
    private static final String RN_NETWORKING = "com.facebook.react.modules.network.NetworkingModule";

    /**
     * expo-fetch 的响应类名（R8 未混淆 —— 被 JS 按名引用）。
     *
     * <p>2.20.2 上宿主实际用的是这个网络模块，不是 RN 自带的那个。
     */
    private static final String EXPO_FETCH_RESPONSE = "expo.modules.fetch.NativeResponse";

    /**
     * 允许读正文的最大体积。
     *
     * <p>⚠️ 这个值同时决定「{@code request(N)} 一次拉多少」—— 而走 gzip 的响应在解压后
     * 被 okhttp 去掉了 {@code Content-Length}，此时拿不到真实长度、只能按本值拉
     * ⇒ <b>本值就是字幕 JSON 的体积上限</b>。实测字幕 JSON 是几 KB 级
     * （最多见过 324 条 cue，约 60–80KB），取 512KB 留足一个数量级余量；
     * 再大的一定是音频 / 封面图，而那些在更早的 content-type / 扩展名闸门就已被挡掉。
     */
    private static final long PEEK_LIMIT = 512 * 1024;

    /** 疑似音视频 / 图片 / 二进制流的 content-type 关键词 —— 命中就完全不碰。 */
    private static final String[] MEDIA_CT_HINTS = {
            "audio/", "video/", "image/", "octet-stream",
            "mpeg", "mp4", "zip", "font", "pdf", "event-stream"};

    /** 媒体扩展名（老通道用；新通道拿不到 URL）。 */
    private static final String[] MEDIA_EXT_HINTS = {
            ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".opus", ".flac", ".wma",
            ".mp4", ".m4v", ".webm", ".mkv", ".ts", ".m3u8",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg"};

    /**
     * 找 contentType 时必须**排除**的返回类型。
     *
     * <p>🔴 {@code InputStream}（= byteStream）排在第一位且**绝对不能碰**：
     * 调用它会把响应流消费掉，宿主自己随后就读不到 body 了。
     * {@code byte[]}（= bytes）同理。
     */
    private static final Class<?>[] EXCLUDED_RETVALS = {
            InputStream.class,   // byteStream —— 调用即消费宿主流
            byte[].class,         // bytes —— 同上
            Object.class,
            CharSequence.class,
    };

    /** 已成功解析的响应数（只用于诊断，证明通道真的通了）。 */
    private static volatile int sParsedCount = 0;
    /** 首次读到「像字幕的响应体」时打一行自证日志。 */
    private static volatile boolean sFirstHitLogged = false;
    /** 首次「读到 body 但没找到字幕结构」时打一行（只打一次，避免刷屏）。 */
    private static volatile boolean sFirstMissLogged = false;
    /** 最近一次提交解析的正文指纹（多通道可能各命中一次同一正文 ⇒ 去重）。 */
    private static volatile int sLastBodyHash = 0;

    // ---- 通道 0（Response.Builder.build + 非消费 peek）的解析结果 ----

    /** 响应体上「取 source」的那个无参方法（混淆后名字丢失，按形状解析）。 */
    private static volatile Method sSrcM;
    /** BufferedSource.request(long): boolean —— 把字节拉进内部缓冲区，**不消费**。 */
    private static volatile Method sRequestM;
    /** BufferedSource.buffer(): Buffer —— 取那个共享缓冲区。 */
    private static volatile Method sBufferM;
    /** Buffer.clone() —— okio 的写时复制快照（读副本不影响原件）。 */
    private static volatile Method sCloneM;
    /** Buffer.readByteArray(): byte[] —— 只从**副本**里读。 */
    private static volatile Method sReadArrM;
    /** 响应体的 contentLength(): long（通道 0 与通道 1 共用）。 */
    private static volatile Method sBodyLenM;
    /** 响应体的 contentType(): MediaType（通道 0 与通道 1 共用）。 */
    private static volatile Method sBodyCtM;

    /** 已经处理过的响应体（按**对象身份**去重：同一个响应可能被多次 build）。 */
    private static final java.util.Set<Object> sSeenBodies =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());

    /** 见过的响应总数 / 被闸门挡掉数 / 真正 peek 过数 —— 全部只用于诊断。 */
    private static volatile int sBuildCalls = 0;
    private static volatile int sSkipped = 0;
    private static volatile int sPeeked = 0;
    private static volatile boolean sPeekErrLogged = false;

    public static void hook(ClassLoader cl, SubtitleRepository repo) {
        NetLogFile.init(repo.getAppContext());
        // 通道 0 最先装：它是 2.20.2 上唯一确定会走到的采集点
        hookViaResponseBuild(cl, repo);
        // 通道 1：老宿主 / RN 文本路径（也是 contentLength/contentType 的解析来源）
        hookViaReactNetworking(cl, repo);
        // 通道 2：完全未混淆的老宿主
        hookViaOkHttpDirect(cl, repo);
    }

    // ======================================================================
    //  通道 1（主）：RN 网络模块 —— 抗混淆
    // ======================================================================

    /**
     * 主通道：从 {@code NetworkingModule.readWithProgress} 的<b>参数类型</b>里取出
     * ResponseBody 的类，再 hook 它的 {@code string()}。
     *
     * <p>为什么这条是主通道：{@code NetworkingModule} 的方法名全部存活，
     * 而 {@code readWithProgress} 的签名形状（int, String, ResponseBody）唯一，
     * 混淆前后结构不变 ⇒ 下一版宿主再改名也不影响。
     */
    private static void hookViaReactNetworking(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> nm = XposedCompat.findClass(RN_NETWORKING, cl);
            // 锚点在，但形状可能变（RN 升级会改签名）—— 这时打形状出来再降级
            Method probe = Shape.matchMethod(nm, new Class<?>[]{int.class, String.class, null}, null);
            if (probe == null) {
                LogGate.debug(TAG, " RN anchor present but readWithProgress shape not found -> "
                        + Shape.describeNoArgReturnTypes(nm, 8));
                return;
            }
            Class<?> bodyCls = probe.getParameterTypes()[2];
            LogGate.debug(TAG, " RN anchor ok: " + probe.getName() + "("
                    + probe.getParameterTypes()[0].getSimpleName() + ","
                    + probe.getParameterTypes()[1].getSimpleName() + ","
                    + bodyCls.getName() + ")  [body class resolved BY SIGNATURE, not by name]");

            // ResponseBody 上的无参 string()（混淆后叫 l()/a()/…，按返回类型 String 认）
            Method stringM = Shape.findNoArgReturning(bodyCls, "string", String.class);
            if (stringM == null) {
                LogGate.debug(TAG, " ResponseBody.string() not found -> "
                        + Shape.describeNoArgReturnTypes(bodyCls, 10));
                return;
            }
            // contentLength()：先看体积再决定要不要读
            Method lenM = Shape.findNoArgReturning(bodyCls, "contentLength", long.class);
            // contentType()：媒体闸。
            // 🔴 必须用**排除表**找，不能用「无参 + 返回任意对象」—— 那样会选中
            //    第一个返回引用的方法，也就是 byteStream()；调用它会**消费掉宿主的流**，
            //    导致宿主自己读不到 body（图片/字幕加载失败）＝ 严重回归。
            //    2.20.2 实测：响应体类无参方法返回类型依次是
            //    InputStream / byte[] / void / long / MediaType / BufferedSource / String。
            Method ctM = Shape.findNoArgExcluding(bodyCls, "contentType", EXCLUDED_RETVALS);
            // 通道 0（build + peek）也要用这两个 —— 这里解析完就共享出去
            sBodyLenM = lenM;
            sBodyCtM = ctM;
            LogGate.debug(TAG, " ResponseBody shape: string=" + stringM.getName()
                    + " contentLength=" + (lenM == null ? "(none)" : lenM.getName())
                    + " contentType=" + (ctM == null ? "(none)" : ctM.getName())
                    + "  [" + bodyCls.getName() + "]");

            final Method fLen = lenM;
            final Method fCt = ctM;
            XposedCompat.hookMethod(stringM, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    try {
                        if (result instanceof String) {
                            handleBodyString((String) result, chain.getThisObject(), fLen, fCt, repo,
                                    "rn.string");
                        }
                    } catch (Throwable ignored) {
                        // 钩子体绝不能把异常抛回宿主
                    }
                    return result; // 字幕文本原样放行
                }
            });
            XposedCompat.log(TAG + " hooked ResponseBody.string() via RN anchor [PRIMARY channel]");

            // 【2.1.4 补】还有一条兄弟路径：bytes()（无参 → byte[]）。
            //    真机日志证明 string() 在 2.20.2 上**一次都没被调用** ——
            //    因为宿主走的是 expo-fetch（见下个通道），而不是 RN 的文本路径。
            //    但 RN 侧若把 responseType 取成二进制，就会走 bytes()，
            //    这条留着兜底；读的是**已经生成好的 byte[]**，零副作用。
            Method bytesM = Shape.findNoArgReturning(bodyCls, "bytes", byte[].class);
            if (bytesM != null) {
                final Method fLen2 = lenM;
                final Method fCt2 = ctM;
                XposedCompat.hookMethod(bytesM, new XposedCompat.SimpleHook() {
                    @Override
                    protected Object after(XposedInterface.Chain chain, Object result) {
                        try {
                            if (result instanceof byte[]) {
                                handleBodyBytes((byte[]) result, chain.getThisObject(), fLen2, fCt2,
                                        repo, "rn.bytes");
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                });
                XposedCompat.log(TAG + " also hooked ResponseBody." + bytesM.getName()
                        + "() [bytes fallback]");
            }
        } catch (Throwable e) {
            XposedCompat.log(TAG + " RN-anchor channel failed: " + e.getMessage());
        }
    }

    // ======================================================================
    //  通道 0（主·2.20.2 起）：Response.Builder.build() + 非消费 peek
    // ======================================================================

    /**
     * <b>2.20.2 上唯一确定会被走到的采集点</b>。
     *
     * <h3>为什么前两版都失败（真机两次「挂上了但零命中」）</h3>
     * <ul>
     *   <li>v1 挂在 {@code ResponseBody.string()} 上 —— 宿主走的是 expo-fetch，
     *       它按块读字节、**从不调用 string()**。</li>
     *   <li>v2 改挂 expo-fetch 的「正文累加器」 —— 那次解析到的字段类型其实不是
     *       正文累加器，同样零命中。</li>
     * </ul>
     * 两次的共性：<b>都在猜「宿主用哪个方法读正文」</b>。而 okhttp 的
     * {@code Response.Builder.build()} 是<b>所有</b>路径（网络 / 缓存 / 各拦截器包装）
     * 都必须经过的一步 —— 离线实测全包被调用 <b>20 处</b>，
     * 而且一旦构建出响应，正文对象就挂在 {@code Response} 的
     * <b>body 字段</b>上（方法会被 R8 inline，<b>字段不会</b>）。
     *
     * <h3>怎么在不消费的前提下读正文</h3>
     * 复刻 okhttp 自己的 {@code peekBody(byteCount)}（R8 已把该方法删掉）：
     * <pre>
     *   src  = body.source()          // BufferedSource（不读就不动）
     *   src.request(N)                // 把 N 字节拉进**它自己的缓冲区**（不消费）
     *   buf  = src.buffer()           // 就是这个共享缓冲区
     *   copy = buf.clone()            // okio 的**写时复制**快照（独立段表）
     *   data = copy.readByteArray()   // 只消费**副本** ⇒ 宿主读到的内容分毫不变
     * </pre>
     * ⚠️ 关键：{@code clone()} 走的是 okio 的 {@code Buffer.copy()}（反汇编实证：
     * 新建段表 + 逐段拷贝 + 标记 SHARED），<b>不是</b> {@code Object.clone()} 的浅拷贝 ——
     * 读副本不会移动原 buffer 的读指针，所以宿主随后照常读到完整正文。
     *
     * <h3>怎么在不写混淆名的前提下找到这些类</h3>
     * <ol>
     *   <li><b>Response 类</b>：从存活类名 {@code expo.modules.fetch.NativeResponse}
     *       的方法签名里找「参数类型既有 {@code close()} 又有一个『响应体形状』的字段」
     *       的那个 —— 2.20.2 上即 {@code l(Call, Response)} 的第 2 参。</li>
     *   <li><b>ResponseBody 类</b>：Response 的字段里，类型同时具备
     *       {@code bytes()[B} + {@code contentLength()J} + {@code string()String}
     *       + {@code byteStream()InputStream} 的那个（≥4 个标记）。</li>
     *   <li><b>Response.Builder 类</b>：Response 的无参方法返回的类 B
     *       —— B 有 ResponseBody 类型的字段，且有无参方法返回 Response。
     *       那个方法就是 {@code build()}。</li>
     *   <li><b>okio 链路</b>：全部按形状（{@code (long)→boolean} 的 request、
     *       返回「有 clone + readByteArray 的类」的 buffer 等）。</li>
     * </ol>
     * ⇒ 全流程<b>零硬编码混淆名</b>，宿主下一次重新生成映射表也不受影响。
     */
    private static void hookViaResponseBuild(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> respCls = resolveResponseClass(cl);
            if (respCls == null) {
                LogGate.debug(TAG, " response class NOT resolved (no anchor matched)"
                        + " -> build channel unavailable");
                return;
            }
            Field bodyField = findBodyField(respCls);
            if (bodyField == null) {
                LogGate.debug(TAG, " no ResponseBody-shaped field on " + respCls.getName()
                        + " -> build channel unavailable");
                return;
            }
            Class<?> bodyCls = bodyField.getType();
            // contentLength / contentType 若通道 1 还没解析出来，这里先补上
            if (sBodyLenM == null) {
                sBodyLenM = Shape.findNoArgReturning(bodyCls, "contentLength", long.class);
            }
            if (sBodyCtM == null) {
                sBodyCtM = Shape.findNoArgExcluding(bodyCls, "contentType", EXCLUDED_RETVALS);
            }
            boolean okio = resolveOkioChain(bodyCls);

            int hooked = 0;
            for (Method nb : respCls.getDeclaredMethods()) {
                if (nb.getParameterCount() != 0) {
                    continue;
                }
                Class<?> bc = nb.getReturnType();
                if (bc == null || bc.isPrimitive() || bc.isArray() || bc == respCls) {
                    continue;
                }
                if (Shape.findFieldOfType(bc, bodyCls) == null) {
                    continue;
                }
                Method buildM = Shape.findNoArgReturningExact(bc, respCls);
                if (buildM == null) {
                    continue;
                }
                final Field fBody = bodyField;
                final Class<?> fResp = respCls;
                XposedCompat.hookMethod(buildM, new XposedCompat.SimpleHook() {
                    @Override
                    protected Object after(XposedInterface.Chain chain, Object result) {
                        try {
                            if (result != null && fResp.isInstance(result)) {
                                Object body = Shape.readFieldValue(fBody, result);
                                if (body != null) {
                                    tapResponseBody(body, result, repo);
                                }
                            }
                        } catch (Throwable ignored) {
                            // 钩子体绝不能把异常抛回宿主
                        }
                        return result; // 响应原样放行
                    }
                });
                hooked++;
                XposedCompat.log(TAG + " hooked " + bc.getName() + "." + buildM.getName()
                        + "() -> Response  [PRIMARY channel on 2.20.2]");
            }
            if (hooked > 0) {
                XposedCompat.log(TAG + " build channel ready: resp=" + respCls.getName()
                        + " body=" + bodyCls.getName()
                        + " peek=" + (okio ? "YES" : "NO(被动通道兜底)")
                        + "  [every class resolved BY SHAPE, zero hardcoded obfuscated names]");
            } else {
                XposedCompat.log(TAG + " build channel: no builder found on " + respCls.getName()
                        + " -> " + Shape.describeNoArgReturnTypes(respCls, 8));
            }
        } catch (Throwable e) {
            XposedCompat.log(TAG + " build channel setup failed: " + e);
        }
    }

    /** 存活类名锚点（被 JS / RN proguard 规则按名引用 ⇒ R8 不动它们）。 */
    private static final String[] RESP_ANCHORS = {
            "expo.modules.fetch.NativeResponse",
            "com.facebook.react.modules.network.NetworkingModule",
    };

    /**
     * 解析「响应类」——从存活锚点的方法签名里反查。
     *
     * <p>判据：某方法的参数类型同时满足 ① 有 {@code close()}；② 有一个「响应体形状」的字段。
     * 2.20.2 实测命中 {@code expo.modules.fetch.NativeResponse.l(Call, Response)} 的第 2 参
     * （另一个参数 {@code Call} 没有响应体字段，被 ② 挡掉）。
     */
    private static Class<?> resolveResponseClass(ClassLoader cl) {
        for (String name : RESP_ANCHORS) {
            Class<?> a;
            try {
                a = XposedCompat.findClass(name, cl);
            } catch (Throwable ignored) {
                continue;
            }
            if (a == null) {
                continue;
            }
            Method[] ms;
            try {
                ms = a.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : ms) {
                Class<?>[] ps;
                try {
                    ps = m.getParameterTypes();
                } catch (Throwable ignored) {
                    continue;
                }
                for (Class<?> p : ps) {
                    if (p == null || p.isPrimitive() || p.isArray()) {
                        continue;
                    }
                    if (p.getName().startsWith("java.") || p.getName().startsWith("android.")) {
                        continue;
                    }
                    if (!Shape.hasCloseMethod(p)) {
                        continue;
                    }
                    if (findBodyField(p) == null) {
                        continue;
                    }
                    return p;
                }
            }
        }
        return null;
    }

    /** 在响应类的字段里找「响应体」——类型具备 ≥4 个响应体特征即认。 */
    private static Field findBodyField(Class<?> respCls) {
        for (Field f : Shape.instanceFields(respCls)) {
            if (looksLikeResponseBody(f.getType())) {
                return f;
            }
        }
        return null;
    }

    /**
     * 这个类型像不像 okhttp 的 {@code ResponseBody}？
     *
     * <p>要求同时具备 ≥4 个特征（bytes / contentLength / string / byteStream / close）。
     * 实测 {@code Response} 自身的其它字段（Headers / Protocol / Request / 缓存响应）
     * 最多只命中 1 个 ⇒ 不会误中。
     */
    private static boolean looksLikeResponseBody(Class<?> t) {
        if (t == null || t.isPrimitive() || t.isArray() || t.isInterface()) {
            return false;
        }
        int markers = 0;
        if (Shape.hasNoArgReturning(t, byte[].class)) {
            markers++;   // bytes()
        }
        if (Shape.hasNoArgReturning(t, long.class)) {
            markers++;   // contentLength()
        }
        if (Shape.hasNoArgReturning(t, String.class)) {
            markers++;   // string()
        }
        if (Shape.hasNoArgReturning(t, InputStream.class)) {
            markers++;   // byteStream()
        }
        if (Shape.hasCloseMethod(t)) {
            markers++;   // close()
        }
        return markers >= 4;
    }

    /**
     * 解析 okio 链路（全部按形状，不写名字）。
     *
     * <p>⚠️ 判据里<b>必须排除 JDK 类型</b>：用「有参且返回 boolean 的方法」这种宽判据
     * 去筛 {@code source()} 的返回类型时，{@code String.equals(Object):boolean}
     * 也会命中 ⇒ 会把 {@code body.string()} 误当成 {@code source()}。
     * 所以这里要求参数**精确是 {@code long}**（okio 的 {@code request(long)}）。
     */
    private static boolean resolveOkioChain(Class<?> bodyCls) {
        if (sSrcM != null) {
            return true; // 已解析过
        }
        try {
            for (Class<?> c = bodyCls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) {
                        continue;
                    }
                    Class<?> r = m.getReturnType();
                    if (r.isPrimitive() || r.isArray()) {
                        continue;
                    }
                    if (r.getName().startsWith("java.") || r.getName().startsWith("android.")) {
                        continue;
                    }
                    // BufferedSource.request(long): boolean
                    Method req = Shape.matchMethod(r, new Class<?>[]{long.class}, boolean.class);
                    if (req == null) {
                        continue;
                    }
                    // BufferedSource.buffer(): Buffer（Buffer 有 clone() 与 readByteArray()）
                    Method bufM = null;
                    Class<?> bufCls = null;
                    for (Method m2 : r.getDeclaredMethods()) {
                        if (m2.getParameterCount() != 0) {
                            continue;
                        }
                        Class<?> r2 = m2.getReturnType();
                        if (r2.isPrimitive() || r2.isArray() || r2 == r) {
                            continue;
                        }
                        if (r2.getName().startsWith("java.")) {
                            continue;
                        }
                        if (Shape.findNoArgReturning(r2, "clone", Object.class) == null) {
                            continue;
                        }
                        if (!Shape.hasNoArgReturning(r2, byte[].class)) {
                            continue;
                        }
                        bufM = m2;
                        bufCls = r2;
                        break;
                    }
                    if (bufM == null) {
                        continue;
                    }
                    Method cl = Shape.findNoArgReturning(bufCls, "clone", Object.class);
                    Method rd = Shape.findNoArgReturning(bufCls, null, byte[].class);
                    if (cl == null || rd == null) {
                        continue;
                    }
                    sSrcM = m;
                    sRequestM = req;
                    sBufferM = bufM;
                    sCloneM = cl;
                    sReadArrM = rd;
                    LogGate.debug(TAG, " okio chain resolved BY SHAPE: source=" + m.getName()
                            + " request=" + req.getName()
                            + " buffer=" + bufM.getName()
                            + " clone=" + cl.getName()
                            + " readByteArray=" + rd.getName());
                    return true;
                }
            }
        } catch (Throwable e) {
            XposedCompat.log(TAG + " okio chain resolve error: " + e);
        }
        return false;
    }

    /**
     * 拿到一个「已经构建出来的响应体」时做的事：闸门 → 非消费 peek → 解析。
     *
     * <p>闸门顺序（先便宜后昂贵，媒体一律 0 开销）：
     * ① 体积已知且超限 ⇒ 返回；② content-type 命中媒体词 ⇒ 返回；
     * ③ URL 后缀是媒体 ⇒ 返回；④ 必须「体积已知且小」或「像 JSON」或「URL 是 .json」才敢读。
     */
    private static void tapResponseBody(Object body, Object resp, SubtitleRepository repo) {
        sBuildCalls++;
        Object lenObj = Shape.callNoArg(body, sBodyLenM);
        long len = (lenObj instanceof Long) ? (Long) lenObj : -1L;
        Object ctObj = Shape.callNoArg(body, sBodyCtM);
        String cts = ctObj == null ? "" : String.valueOf(ctObj).toLowerCase(Locale.US);
        String url = extractUrl(resp);
        String urlLc = url == null ? "" : url.toLowerCase(Locale.US);

        if (sBuildCalls <= 5) {
            LogGate.debug(TAG, " response #" + sBuildCalls + ": url=" + url
                    + " ctype=" + (cts.isEmpty() ? "(none)" : cts) + " len=" + len);
        }

        if (len > PEEK_LIMIT) {
            if (++sSkipped <= 3) {
                LogGate.debug(TAG, " skipped #" + sSkipped + " (large): url=" + url
                        + " ctype=" + cts + " len=" + len);
            }
            return;
        }
        for (String hint : MEDIA_CT_HINTS) {
            if (cts.contains(hint)) {
                if (++sSkipped <= 3) {
                    LogGate.debug(TAG, " skipped #" + sSkipped + " (media ctype): url=" + url
                            + " ctype=" + cts + " len=" + len);
                }
                return;
            }
        }
        for (String hint : MEDIA_EXT_HINTS) {
            if (urlLc.contains(hint)) {
                if (++sSkipped <= 3) {
                    LogGate.debug(TAG, " skipped #" + sSkipped + " (media ext): url=" + url
                            + " ctype=" + cts + " len=" + len);
                }
                return;
            }
        }
        boolean small = len >= 0 && len <= PEEK_LIMIT;
        boolean jsonish = cts.contains("json") || cts.contains("text/") || cts.contains("xml");
        boolean urlJson = urlLc.contains(".json");
        if (!small && !jsonish && !urlJson) {
            if (++sSkipped <= 3) {
                LogGate.debug(TAG, " skipped #" + sSkipped + " (unknown type): url=" + url
                        + " ctype=" + (cts.isEmpty() ? "(none)" : cts) + " len=" + len);
            }
            return;
        }
        if (sSrcM == null) {
            // okio 链路没解析出来 ⇒ 只能靠被动通道，不能主动读
            return;
        }
        // ⚠️ 去重表**只登记准备 peek 的**（都是小 JSON）。若在闸门前就登记，
        //    会把这个进程里所有音频/图片的响应体对象长期持有住 ⇒ 内存压力。
        synchronized (sSeenBodies) {
            if (sSeenBodies.size() > 64) {
                sSeenBodies.clear();
            }
            if (!sSeenBodies.add(body)) {
                return; // 同一个响应体对象只处理一次
            }
        }
        sPeeked++;
        if (sPeeked == 1) {
            XposedCompat.log(TAG + " >>> first response passed gates (peek starts): url=" + url
                    + " ctype=" + (cts.isEmpty() ? "(none)" : cts) + " len=" + len);
        }
        String text = peekText(body, len);
        if (text == null) {
            return;
        }
        // 截断告警：peek 只读了前 N 字节，若正文比 N 大就会读不全。
        // 字幕 JSON 是「{...}」结构 ⇒ 结尾不是 } 或 ] 且体积不小 ⇒ 很可能被截断。
        String tail = text.trim();
        if (tail.length() > 4096 && !tail.endsWith("}") && !tail.endsWith("]")) {
            XposedCompat.log(TAG + " !! peek may be TRUNCATED (len=" + text.length()
                    + ", limit=" + PEEK_LIMIT + ") url=" + url);
        }
        NetLogFile.log("RESP url=" + url + " ctype=" + cts + " len=" + len
                + " peeked=" + text.length());
        submit(text, repo, "build.peek");
    }

    /**
     * 非消费读取正文前 N 字节。
     *
     * <p>⚠️ 只对**已经过闸门**的响应调用；{@code request(N)} 会阻塞到「N 字节到齐或流结束」，
     * 所以绝对不能对音频/视频这类大流调用。
     */
    private static String peekText(Object body, long knownLen) {
        try {
            Object src = Shape.callNoArg(body, sSrcM);
            if (src == null) {
                return null;
            }
            long want = (knownLen >= 0 && knownLen <= PEEK_LIMIT) ? knownLen : PEEK_LIMIT;
            if (want <= 0) {
                want = PEEK_LIMIT;
            }
            try {
                sRequestM.invoke(src, Long.valueOf(want));
            } catch (Throwable ignored) {
                // request 失败也继续：缓冲区里可能已经有数据
            }
            Object buf = Shape.callNoArg(src, sBufferM);
            if (buf == null) {
                return null;
            }
            Object copy = Shape.callNoArg(buf, sCloneM);
            if (copy == null) {
                return null;
            }
            Object arr = Shape.callNoArg(copy, sReadArrM);
            if (!(arr instanceof byte[])) {
                return null;
            }
            byte[] data = (byte[]) arr;
            if (data.length == 0 || data.length > PEEK_LIMIT) {
                return null;
            }
            return decodeUtf8(data, 0, data.length);
        } catch (Throwable e) {
            if (!sPeekErrLogged) {
                sPeekErrLogged = true;
                XposedCompat.log(TAG + " peek failed (once): " + e);
            }
            return null;
        }
    }

    /**
     * 从响应对象里 best-effort 取 URL。
     *
     * <p>R8 把 {@code Response.request()} / {@code Request.url()} 都 inline 掉了，
     * 但<b>字段还在</b>：Response 有个类型为「请求类」的字段，请求类里有个类型为
     * 「HttpUrl」的字段。HttpUrl 的形状很好认：有 {@code toUri(): java.net.URI}。
     * 取不到就返回 {@code null}（只是少了日志与一条判据，不影响抓取）。
     */
    private static String extractUrl(Object resp) {
        try {
            for (Field f : Shape.instanceFields(resp.getClass())) {
                Object v = Shape.readFieldValue(f, resp);
                String u = urlLike(v);
                if (u != null) {
                    return u;
                }
                if (v == null) {
                    continue;
                }
                for (Field f2 : Shape.instanceFields(v.getClass())) {
                    String u2 = urlLike(Shape.readFieldValue(f2, v));
                    if (u2 != null) {
                        return u2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 值是 HttpUrl 吗？——「有 {@code toUri()} 且 toString 以 http 开头」。 */
    private static String urlLike(Object v) {
        if (v == null) {
            return null;
        }
        if (!Shape.hasNoArgReturning(v.getClass(), java.net.URI.class)) {
            return null;
        }
        String s = String.valueOf(v);
        return s.startsWith("http") ? s : null;
    }

    // ======================================================================
    //  通道 2（备）：未混淆的老宿主仍走老路
    // ======================================================================

    /**
     * 备用通道：宿主<b>没有</b>开 R8 时（≤2.20.1）仍按原类名 hook okhttp3。
     *
     * <p>保留它是为了「同一个包在老宿主上也能用」——本模块不能只支持新版本。
     * 两条通道可能同时命中（{@link #handleBodyString} 内按内容去重），谁先到用谁。
     */
    private static void hookViaOkHttpDirect(ClassLoader cl, SubtitleRepository repo) {
        int hit = 0;
        hit += hookOkHttpBuilder(cl, repo);
        hit += hookOkHttpRealCall(cl, repo);
        if (hit > 0) {
            LogGate.debug(TAG, " legacy okhttp channel active (" + hit
                    + " hooks) -> host is NOT obfuscated");
        } else {
            LogGate.debug(TAG, " legacy okhttp channel absent (expected on obfuscated host)");
        }
    }

    private static int hookOkHttpBuilder(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> builderClass = XposedCompat.findClass("okhttp3.Response$Builder", cl);
            Method buildMethod = XposedCompat.findMethodExact(builderClass, "build");
            XposedCompat.hookMethod(buildMethod, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    try {
                        if (result != null) {
                            captureResponse(result, repo);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            XposedCompat.log(TAG + " hooked okhttp3.Response$Builder.build() [legacy]");
            return 1;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static int hookOkHttpRealCall(ClassLoader cl, SubtitleRepository repo) {
        try {
            Class<?> realCallClass = XposedCompat.findClass("okhttp3.RealCall", cl);
            Method executeMethod = XposedCompat.findMethodExact(realCallClass, "execute");
            XposedCompat.hookMethod(executeMethod, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    try {
                        if (result != null) {
                            captureResponse(result, repo);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            XposedCompat.log(TAG + " hooked okhttp3.RealCall.execute() [legacy]");
            return 1;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ======================================================================
    //  正文处理（两条通道共用）
    // ======================================================================

    /**
     * 处理一段「已经拿到手的响应体文本」。
     *
     * <p>这是 2.1.4 的核心：<b>先按体积 / 类型闸门零开销挡掉媒体</b>，再检查正文结构。
     * 判「是不是字幕」不再依赖 URL（混淆后拿不到），改看正文里有没有 webvtt 结构。
     */
    private static void handleBodyString(String text, Object bodyObj, Method lenM, Method ctM,
                                         SubtitleRepository repo, String via) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // ① 体积闸（拿得到长度就先用它挡掉音频 —— 音频正文动辄几百 KB）
        if (lenM != null) {
            Object len = Shape.callNoArg(bodyObj, lenM);
            if (len instanceof Long && (Long) len > PEEK_LIMIT) {
                return; // 超大 ⇒ 绝不是字幕 JSON
            }
        }
        // ② content-type 闸
        if (ctM != null) {
            Object ct = Shape.callNoArg(bodyObj, ctM);
            if (ct != null) {
                String s = String.valueOf(ct).toLowerCase(Locale.US);
                for (String hint : MEDIA_CT_HINTS) {
                    if (s.contains(hint)) {
                        return; // 媒体响应 ⇒ 完全不碰
                    }
                }
            }
        }
        // ③ 长度兜底（contentLength 拿不到时）
        if (text.length() > PEEK_LIMIT) {
            return;
        }
        // ④ 结构判据 + 解析
        submit(text, repo, via);
    }

    /** 字节流入口（RN 的 bytes() / expo-fetch 的累加器字节）。 */
    private static void handleBodyBytes(byte[] data, Object bodyObj, Method lenM, Method ctM,
                                        SubtitleRepository repo, String via) {
        if (data == null || data.length == 0 || data.length > PEEK_LIMIT) {
            return;
        }
        if (lenM != null) {
            Object len = Shape.callNoArg(bodyObj, lenM);
            if (len instanceof Long && (Long) len > PEEK_LIMIT) {
                return;
            }
        }
        if (ctM != null) {
            Object ct = Shape.callNoArg(bodyObj, ctM);
            if (ct != null) {
                String s = String.valueOf(ct).toLowerCase(Locale.US);
                for (String hint : MEDIA_CT_HINTS) {
                    if (s.contains(hint)) {
                        return;
                    }
                }
            }
        }
        submit(decodeUtf8(data, 0, data.length), repo, via);
    }

    /**
     * {@link ByteBuffer} 入口（expo-fetch 的正文累加器）。
     *
     * <p>🔴 <b>只读不取</b>：用 {@code duplicate()} 拷一份再按 {@code remaining()} 读字节，
     * <b>绝不改动原 buffer 的 position / limit</b> —— 宿主随后还要用它来构造响应文本，
     * 一旦我们挪了 position，宿主就只能读到半截（典型症状：字幕/图片加载不出来）。
     */
    private static void handleBodyBuffer(ByteBuffer buf, SubtitleRepository repo, String via) {
        if (buf == null) {
            return;
        }
        int remain;
        try {
            remain = buf.remaining();
        } catch (Throwable e) {
            return;
        }
        // 体积闸：媒体（音频）也走这条路，累加器里可能是几十 MB
        if (remain <= 0 || remain > PEEK_LIMIT) {
            return;
        }
        byte[] data = new byte[remain];
        try {
            ByteBuffer copy = buf.duplicate();   // 拷贝，不动原 buffer 的位置
            copy.get(data);
        } catch (Throwable e) {
            return;
        }
        // 快速结构预判：先看有没有 webvtt 关键字，避免大量无谓解码
        if (!containsAscii(data, "webvtt") && !containsAscii(data, "subtitles")
                && !containsAscii(data, "start_time")) {
            return;
        }
        submit(decodeUtf8(data, 0, data.length), repo, via);
    }

    /**
     * 统一出口：结构判据 → 解析 → 日志。
     *
     * <p>{@code cueCount} 去重：同一条正文可能从多个通道各来一次
     * （例如 RN 与 expo-fetch 同时命中），靠「刚解析过同样的内容」避免重复刷日志。
     */
    private static void submit(String text, SubtitleRepository repo, String via) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 结构判据：必须是字幕 JSON
        if (!looksLikeSubtitleJson(text)) {
            if (!sFirstMissLogged) {
                sFirstMissLogged = true;
                LogGate.debug(TAG, " first non-subtitle body (len=" + text.length()
                        + ") passed gates but no webvtt structure -> skipped");
            }
            return;
        }
        if (sLastBodyHash == text.hashCode()) {
            return; // 同一条正文重复到达（多通道命中），不重复解析
        }
        sLastBodyHash = text.hashCode();
        if (!sFirstHitLogged) {
            sFirstHitLogged = true;
            XposedCompat.log(TAG + " >>> first subtitle JSON hit via=" + via
                    + " len=" + text.length() + " (subtitle channel is ALIVE)");
        }
        NetLogFile.log("RESP body via=" + via + " len=" + text.length()
                + " hasSubtitleJson=true");
        try {
            repo.loadFromJson(text);
            int n = repo.getCues().size();
            sParsedCount++;
            XposedCompat.log(TAG + " >> parsed subtitle JSON via=" + via + " cues=" + n
                    + " (total=" + sParsedCount + ")");
            NetLogFile.log("  >> PARSED cues=" + n);
        } catch (Throwable e) {
            XposedCompat.log(TAG + " parse error via=" + via + ": " + e.getMessage());
        }
    }

    /**
     * 正文像不像字幕 JSON。
     *
     * <p>判据：必须同时出现「容器键」与「cue 时间键」，否则不认。
     * ⚠️ 不能只看 {@code "webvtt"} 一个串 —— 播放页元数据里也可能带这个字段。
     */
    private static boolean looksLikeSubtitleJson(String text) {
        boolean hasContainer = text.contains("\"webvtt\"")
                || text.contains("\"subtitles\"")
                || text.contains("\"cues\"");
        if (!hasContainer) {
            return false;
        }
        return text.contains("start_time") || text.contains("end_time")
                || text.contains("startTime") || text.contains("endTime");
    }

    /** UTF-8 解码（宿主 expo-fetch 的文本路径用的就是 UTF-8）。 */
    private static String decodeUtf8(byte[] data, int off, int len) {
        try {
            return new String(data, off, len, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 字节里有没有某个 ASCII 子串（廉价预筛，避免给大型二进制做整段解码）。
     *
     * <p>朴素匹配即可：只用于「几十 KB 以内、且只是想快速排除」的场景。
     */
    private static boolean containsAscii(byte[] data, String needle) {
        if (data == null || needle == null || needle.isEmpty()) {
            return false;
        }
        int n = needle.length();
        if (data.length < n) {
            return false;
        }
        char c0 = needle.charAt(0);
        for (int i = 0; i + n <= data.length; i++) {
            if (data[i] != (byte) c0) {
                continue;
            }
            boolean ok = true;
            for (int j = 1; j < n; j++) {
                if (data[i + j] != (byte) needle.charAt(j)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    //  老通道（未混淆宿主）专用的响应对象处理
    // ======================================================================

    /** 老通道：直接从 {@code okhttp3.Response} 对象 peek（v34 的白名单逻辑）。 */
    private static void captureResponse(Object response, SubtitleRepository repo) {
        String url = getUrlFromResponse(response);
        if (!isSubtitleCandidate(response, url)) {
            return;
        }
        NetLogFile.log("RESP url=" + (url == null ? "(null)" : url));
        String text = peekBodyText(response);
        handleBodyString(text, response, null, null, repo, "okhttp.peek");
    }

    /** v34 白名单：先拒绝后放行，只认 json。 */
    private static boolean isSubtitleCandidate(Object response, String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        Integer code = codeOf(response);
        if (code == null || code != 200) {
            return false; // 206 分片 / 3xx / 4xx 一律不碰
        }
        for (String ext : MEDIA_EXT_HINTS) {
            if (u.contains(ext)) {
                return false;
            }
        }
        String ct = contentTypeOf(response);
        for (String hint : MEDIA_CT_HINTS) {
            if (ct.contains(hint)) {
                return false;
            }
        }
        try {
            Object body = XposedCompat.callMethod(response, "body");
            if (body == null) {
                return false;
            }
            Object lenObj = XposedCompat.callMethod(body, "contentLength");
            if (lenObj instanceof Long && (Long) lenObj > PEEK_LIMIT) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return u.contains(".json") || ct.contains("json")
                || u.contains("subtitle") || u.contains("webvtt");
    }

    private static String contentTypeOf(Object response) {
        try {
            Object body = XposedCompat.callMethod(response, "body");
            if (body == null) {
                return "";
            }
            Object ct = XposedCompat.callMethod(body, "contentType");
            return ct == null ? "" : ct.toString().toLowerCase(Locale.US);
        } catch (Throwable e) {
            return "";
        }
    }

    private static Integer codeOf(Object response) {
        try {
            Object c = XposedCompat.callMethod(response, "code");
            return c instanceof Integer ? (Integer) c : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String peekBodyText(Object response) {
        try {
            Object body = XposedCompat.callMethod(response, "body");
            if (body == null) {
                return null;
            }
            Object peeked = XposedCompat.callMethod(body, "peekBody", PEEK_LIMIT);
            if (peeked == null) {
                return null;
            }
            return (String) XposedCompat.callMethod(peeked, "string");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String getUrlFromResponse(Object response) {
        try {
            Object request = XposedCompat.callMethod(response, "request");
            if (request == null) {
                return null;
            }
            Object url = XposedCompat.callMethod(request, "url");
            if (url == null) {
                return null;
            }
            Object s = XposedCompat.callMethod(url, "toString");
            return s == null ? null : s.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 已成功解析的字幕响应数（诊断用：真机日志里它 &gt; 0 即证明通道通了）。 */
    public static int parsedCount() {
        return sParsedCount;
    }
}
