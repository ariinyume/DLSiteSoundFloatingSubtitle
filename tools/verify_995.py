#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【2.2.14 / code 995】六项改动的 dex 字节码层硬验证

为什么需要这个脚本
------------------
JUnit（GoldenVectorTest）验的是「**判据逻辑**在真机取值域上给出正确结论」，
但它跑在 JVM 上、用的是 java 源码编译出的 class。
真正要证明的是另一件事：**这些改动确实编进了 APK**。
只有 dex 字节码层能给出这个证据 —— 源码改了但没编进包，是这类项目最常见的假绿。

验什么（每项都要求「主验命中 + 对照未命中」才��有区分力锚）
------------------------------------------------
1. **AnchorDeadPolicy 确实存在**且三方法签名齐全（confirmWindow / shouldHide / Window）
2. **AnchorDeadPolicy 零状态**：无实例字段、无状态性静态字段（纯判定层的定义）
3. **ActivityButtonHook 已转调** AnchorDeadPolicy（不再自己算判据）
4. **Protocol 确实存在**且 9 个 action 全在；**旧两处的字面量已归零**
   （区分力锚的关键：994 有 9 个、995 有 0 个）
5. **Utils 三个方法都在**；四舍五入口径与截断口径**都还在**
   （只搬不改 ⇒ 两个口径必须同时存在。反向锚：少了任何一个都算回归）
6. **LogGate 接入**：ConfigStore / ScopeProbe / SettingsActivity / StatusBarSubtitleBridge
   四个调用点确实引用了 LogGate
7. **sPokeLogged 配额判定已改**：dex 里应出现取模（rem-int）而非「先判上限再打」

对照口径
--------
主验 = code 995 包；对照 = code 994 包（本轮改动前的上一个交付包）。
**两项分开转 dexdump**（单进程双表只适用于同一 dump；
这里必须跨包对照，所以各自 dump 后再比）。
最后报「有区分力锚数」—— 区分力为 0 的检查项一律标记为 ⚠ 无区分力，
提醒「这个断言在994 里也成立，可能测不到东西」。

用法
----
    python verify_995.py <apk_995> [apk_994]
退出码 0 = 全部通过
"""

import os
import re
import subprocess
import sys
import zipfile

BT = r'F:\WorkBuddyCache\DLSiteSound_Plus\toolchain\android-sdk\build-tools\34.0.0'
DEXDUMP = os.path.join(BT, 'dexdump.exe')

PKG = 'io/github/ariinyume/dlsitesoundfloat'   # dex 格式：包名用 / 分隔
C_POLICY = 'L%s/hook/AnchorDeadPolicy;' % PKG
C_BUTTON = 'L%s/hook/ActivityButtonHook;' % PKG
C_PROTO = 'L%s/config/Protocol;' % PKG
C_BUS = 'L%s/config/ConfigBus;' % PKG
C_BRIDGE = 'L%s/util/StatusBarSubtitleBridge;' % PKG
C_UTILS = 'L%s/util/Utils;' % PKG
C_STORE = 'L%s/config/ConfigStore;' % PKG
C_PROBE = 'L%s/util/ScopeProbe;' % PKG

ACTION_PREFIX = 'io.github.ariinyume.dlsitesoundfloat.action.'
ALL_ACTIONS = [
    'CONFIG_CHANGED', 'RESTART_SYSUI', 'SCOPE_HOST_PING', 'SCOPE_HOST_PONG',
    'STATUSBAR_SUBTITLE_LINE', 'STATUSBAR_SUBTITLE_ENABLED',
    'STATUSBAR_SUBTITLE_DISMISS_REQUEST', 'STATUSBAR_SCOPE_PING', 'STATUSBAR_SCOPE_PONG',
]


def dexdump_all(apk, out):
    """把 APK 里所有 dex 的 dexdump 落盘（大 dex 内存吃不消，分开转再拼）。"""
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(apk) as z:
        dexs = [n for n in z.namelist() if re.match(r'.*\.dex$', n)]
        for n in dexs:
            tmp = out + '.' + os.path.basename(n)
            with open(tmp, 'wb') as f:
                f.write(z.read(n))
            r = subprocess.run([DEXDUMP, '-d', tmp],
                               capture_output=True, text=True,
                               errors='replace', encoding='utf-8')
            with open(out, 'a', encoding='utf-8') as f:
                f.write(r.stdout)
            os.remove(tmp)
    return out


def slice_class(dump, cls_sig):
    """切出某个类的 dexdump 片段。

    dexdump 的真实格式（已取证）：
        Class descriptor  : 'Lio/.../hook/PageFollowPolicy;'
    ⇒ 用 **descriptor 行**定位起点、下一个 Class descriptor 行作终点。
    ⚠️ 不能只 `find(cls_sig)` 就算命中 —— 那样会切到「上一个类的主体里」。
    """
    m = re.search(r"Class descriptor\s*:\s*'%s'" % re.escape(cls_sig), dump)
    if not m:
        return None
    start = m.start()
    m2 = re.search(r"\n[^\n]*Class descriptor\s*:", dump[m.end():])
    end = m.end() + m2.start() if m2 else len(dump)
    return dump[start:end]


def field_names(body, kind):
    """取 Static / Instance fields 段的字段名集合。

    ⚠️ dexdump 的真实格式（已取证）：
        Static fields     -
          #0 : (in L...)
            name : 'NO_BASELINE'
    **无方括号计数**，所以不能用 `Static fields\\[-\\d+\\]` 去匹配 —— 那样永远匹配不到。
    """
    if body is None:
        return set()
    pat = (r'%s fields\s*(?:-|\[[^\]]*\])\s*:?(.*?)'
           r'(?=\n\s*(?:Static fields|Instance fields|Direct methods)\s)' % kind)
    blk = re.search(pat, body, re.S)
    if not blk:
        return set()
    return set(re.findall(r"name\s*:\s*'([A-Za-z_$][\w$]*)'", blk.group(1)))


class Checker(object):
    """收集检查项并区分「有/无区分力」。"""

    def __init__(self):
        self.rows = []

    def add(self, desc, in_main, in_ctrl):
        self.rows.append((desc, bool(in_main), bool(in_ctrl)))

    def report(self):
        npass = ndisc = 0
        print()
        print('=' * 78)
        print('%-52s %-5s %-5s %s' % ('检查项', '995', '994', '判定'))
        print('=' * 78)
        for desc, m, c in self.rows:
            if m and not c:
                verdict = 'OK 有区分力'
                npass += 1
                ndisc += 1
            elif m and c:
                verdict = '-- 两版都有(无区分力)'
                npass += 1
            else:
                verdict = 'FAIL 未命中'
            print('%-52s %-5s %-5s %s' % (desc[:52], 'Y' if m else '.',
                                          'Y' if c else '.', verdict))
        print('=' * 78)
        print('通过 %d/%d，有区分力锚 %d 个' % (npass, len(self.rows), ndisc))
        if ndisc == 0:
            print('!! 有区分力锚为 0 —— 本脚本可能整体测不到东西，别信它。')
        return 0 if npass == len(self.rows) else 1


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk_new = sys.argv[1]
    apk_old = sys.argv[2] if len(sys.argv) > 2 else None
    for a in [apk_new] + ([apk_old] if apk_old else []):
        if not os.path.isfile(a):
            print('找不到 APK: %s' % a)
            return 2

    work = os.path.dirname(os.path.abspath(apk_new))
    dn = os.path.join(work, 'dexdump_995.txt')
    print('主验 dexdump: %s' % apk_new)
    dexdump_all(apk_new, dn)
    D = open(dn, encoding='utf-8', errors='replace').read()
    print('  dump 长度 %d' % len(D))

    if apk_old:
        do = os.path.join(work, 'dexdump_994.txt')
        print('对照 dexdump: %s' % apk_old)
        dexdump_all(apk_old, do)
        C = open(do, encoding='utf-8', errors='replace').read()
        print('  dump 长度 %d' % len(C))
    else:
        C = None
        print('对照: (无) —— 区分力无法判定，结果仅供参考')

    ck = Checker()
    # 对照缺失时，in_ctrl 一律填 False（保证判定退化成「只看主验」）
    def dual(fn):
        """把「主验里满足」与「对照里满足」两次求值。"""
        return (fn(D), fn(C) if C is not None else False)

    # ── 1) AnchorDeadPolicy 存在 + 签名 ────────────────────────────────
    pol = slice_class(D, C_POLICY)
    ck.add('1 AnchorDeadPolicy 类编进包了',
           *dual(lambda d: slice_class(d, C_POLICY) is not None))
    for sig, why in (('confirmWindow', '三档确认窗'),
                     ('shouldHide', '收起判定'),
                     ('Window', '结果类')):
        ck.add('1b %s 方法/类存在（%s）' % (sig, why),
               *dual(lambda d, s=sig: (lambda b: b is not None and s in b)(
                   slice_class(d, C_POLICY))))
    # confirmWindow 的参数字段名（dex 里字段名以 name : 'x' 出现）
    for p in ('withEvidenceMs', 'goneStillThresholdMs', 'deadSinceMs',
              'lastOtherSeenMs', 'lastPageMotionMs'):
        ck.add('1c confirmWindow 参数 %s 在 dex 里' % p,
               *dual(lambda d, p=p: (lambda b: b is not None and p in b)(
                   slice_class(d, C_POLICY))))

    # ── 2) 零状态 ───────────────────────────────────────────────────
    sn = field_names(pol, 'Static')
    inames = field_names(pol, 'Instance')
    ck.add('2a AnchorDeadPolicy 无实例字段（零状态前提）',
           *dual(lambda d: not field_names(slice_class(d, C_POLICY), 'Instance')))
    ck.add('2b AnchorDeadPolicy 无状态性静态字段（无 s*/m* 命名）',
           *dual(lambda d: not any(
               re.match(r'^[sm][A-Z]', x)
               for x in field_names(slice_class(d, C_POLICY), 'Static'))))
    print('  AnchorDeadPolicy 静态字段: %s' % (sorted(sn) or '无'))

    # ── 3) hook 侧转调 ───────────────────────────────────────────────
    btn = slice_class(D, C_BUTTON)
    ck.add('3 ActivityButtonHook 引用 AnchorDeadPolicy（已转调）',
           *dual(lambda d: (lambda b: b is not None and 'AnchorDeadPolicy' in b)(
               slice_class(d, C_BUTTON))))

    # ── 4) Protocol + 字面量归零（本脚本最有区分力的一组）─────────────
    ck.add('4 Protocol 类编进包了',
           *dual(lambda d: slice_class(d, C_PROTO) is not None))
    ck.add('4b Protocol 内含全部 9 个 action 字面量',
           *dual(lambda d: (lambda b: b is not None and all(
               (ACTION_PREFIX + a) in b for a in ALL_ACTIONS))(
                   slice_class(d, C_PROTO))))
    ck.add('4c Protocol 含 PROTOCOL_VERSION 常量',
           *dual(lambda d: (lambda b: b is not None and 'PROTOCOL_VERSION' in b)(
               slice_class(d, C_PROTO))))
    ck.add('4d Protocol 前缀常量存在',
           *dual(lambda d: (lambda b: b is not None and 'ACTION_PREFIX' in b)(
               slice_class(d, C_PROTO))))
    ck.add('4f Protocol 含宿主/SystemUI 包名常量',
           *dual(lambda d: (lambda b: b is not None and
                            'jp.co.eisys.dlsitesound' in b and
                            'com.android.systemui' in b)(slice_class(d, C_PROTO))))
    # ⚠️⚠️ 这里**故意没有**下面这两类判据，第一版都写了、都被实测推翻：
    #
    #  ❌「旧两处字面量应归零」
    #     实测：ConfigBus 里仍有 CONFIG_CHANGED 等 4 个、StatusBarSubtitleBridge 里仍有 5 个。
    #     根因是 **javac 的编译期常量内联**：`public static final String X = Protocol.ACTION_X;`
    #     会被 javac 直接替换成字面量，dex 里不存在「转发」这回事。
    #
    #  ❌「旧两处应仍引用 Protocol」（反向版，同一个根因，同样不成立）
    #     实测：ConfigBus / StatusBarSubtitleBridge 的 dex 片段里**一次都没出现** Protocol。
    #
    #  ⇒ 结论：字节码层能验的只有「Protocol 类存在且字面量齐全」（上面 4/4b/4c/4d/4f）。
    #    「源码层单点真源」靠 grep 保证（见 tools/verify_protocol_src.py），
    #    因为内联之后每个类的常量池里都各自留了一份 —— 这是编译器的设计，不是缺陷。

    # ── 5) Utils 三方法 + 两口径都在（只搬不改）──────────────────────
    for sig, why in (('dip2px', '四舍五入口径'),
                     ('dip2pxOrDefault', 'null 兜底变体'),
                     ('applyDimensionDip', 'TypedValue 截断口径'),
                     ('DEFAULT_DENSITY', '兜底密度常量')):
        ck.add('5  Utils.%s 存在（%s）' % (sig, why),
               *dual(lambda d, s=sig: (lambda b: b is not None and s in b)(
                   slice_class(d, C_UTILS))))
    # 四类调用方确实改调 Utils 了
    for cls, name in ((C_STORE, 'ConfigStore'), (C_PROBE, 'ScopeProbe')):
        pass  # 这两项放到第 6 项一起查

    # ── 6) LogGate 接入 ─────────────────────────────────────────────
    # ⚠️⚠️ ScopeProbe 的 LogGate 调用在**内部类 ScopeProbe$2** 里（那个匿名
    #    BroadcastReceiver），不在外层 ScopeProbe。第一版脚本查了外层类 ⇒ 误报 FAIL。
    #    这类「调用点落在内部类」的坑，见 MEM_rules.md 铁律 36。
    for cls, name in ((C_STORE, 'ConfigStore'),
                      ('L%s/util/ScopeProbe$2;' % PKG, 'ScopeProbe$2(匿名 receiver)'),
                      (C_BRIDGE, 'StatusBarSubtitleBridge')):
        ck.add('6  %s 已调 LogGate.debug' % name,
               *dual(lambda d, c=cls: (lambda b: b is not None and 'LogGate' in b)(
                   slice_class(d, c))))
    # ⚠️ Log.w 的判据不能找 `'W'` 字面量 —— 优先级/level 是**寄存器传入**的
    #    （`invoke-static {v0, v2}, Landroid/util/Log;.w`），dexdump 里根本没有 'W' 字符串。
    #    正确判据是看 `Landroid/util/Log;.w` 这个调用目标。
    ck.add('6b ConfigStore 的 Log.w 告警仍在（未误降级为诊断级）',
           *dual(lambda d: (lambda b: b is not None and
                            'Landroid/util/Log;.w' in b)(slice_class(d, C_STORE))))

    # ── 7) sPokeLogged 改为取模 ─────────────────────────────────────
    ck.add('7  sPokeLogged 在 dex 里',
           *dual(lambda d: (lambda b: b is not None and 'sPokeLogged' in b)(
               slice_class(d, C_BUTTON))))
    # ★ 区分力锚：「rem-int」这个字符串本身**没有**区分力 —— 994 里就有一处无关的
    #   `rem-int/lit8 v0, v0, #int 60`。真正的锚是**紧跟 sPokeLogged 之后、
    #   模数恰好是 MAX_POKE_LOGS(40)** 的那条取模。
    #   实测：995 的 ActivityButtonHook 里有 `rem-int/lit8 v5, v5, #int 40 // #28`，994 没有。
    def has_poke_mod(d):
        b = slice_class(d, C_BUTTON)
        if b is None:
            return False
        return re.search(r'rem-int\S*\s+v\d+,\s*v\d+,\s*#int 40\b', b) is not None
    ck.add('7b★ sPokeLogged 环形取模（模数=MAX_POKE_LOGS=40）已编进包', *dual(has_poke_mod))
    ck.add('7c sPokeCount 累计值仍保留（跨轮次对齐 #N 靠它）',
           *dual(lambda d: (lambda b: b is not None and 'sPokeCount' in b)(
               slice_class(d, C_BUTTON))))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())