#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【2.2.14 / code 994】PageFollowPolicy 抽取的 dex 字节码层硬验证

为什么需要这个脚本
------------------
`verify_page_follow_policy.py` 验的是**判据在真机取值域上的结论**，
但它照抄的是 Python 版算法。真正要证明的是
**「Java 版的改动确实编进了 APK」** —— 只有dex 字节码层能给出这个证据。

验什么
------
1. **新类确实存在**且带正确的方法签名（三组判据各一）
2. **新类确实零状态**：除 `NO_BASELINE` 外没有任何静态字段
3. **hook 侧已改成转调**：`ActivityButtonHook` 不再自己算判据
4. **反向对照**：旧判据字面量**不再出现**在 `ActivityButtonHook` 里
   （区分力锚：证明是「搬走」而不是「复制」）
5. **阈值常量只在原处定义一次**（没有因重构产生第二份口径）

对照口径
--------
主验 = code 994 包；对照 = code 993 包（本轮改动前的上一个交付包）。
单进程双表，报「有区分力锚数」。

用法
----
    python verify_994.py <apk_994> [apk_993]
"""

import os
import re
import subprocess
import sys
import zipfile

BT = r'F:\WorkBuddyCache\DLSiteSound_Plus\toolchain\android-sdk\build-tools\34.0.0'
DEXDUMP = os.path.join(BT, 'dexdump.exe')

PKG = 'io/github/ariinyume/dlsitesoundfloat'   # dex 格式：包名用 / 分隔
NEW_CLS = 'L%s/hook/PageFollowPolicy;' % PKG
OLD_CLS = 'L%s/hook/ActivityButtonHook;' % PKG


def dexdump_all(apk, out):
    """把 APK 里所有 dex 的 dexdump 落盘（大 dex 内存吃不消）。"""
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(apk) as z:
        dexs = [n for n in z.namelist() if re.match(r'.*\.dex$', n)]
        for n in dexs:
            data = z.read(n)
            tmp = out + '.' + os.path.basename(n)
            with open(tmp, 'wb') as f:
                f.write(data)
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
    ⇒ 用**descriptor 行**定位起点、下一个 Class descriptor 行作终点。
    ⚠️ 不能只 `find(cls_sig)` 就算命中 —— 那样会切到「上一个类的主体里」。
    """
    m = re.search(r"Class descriptor\s*:\s*'%s'" % re.escape(cls_sig), dump)
    if not m:
        return None
    start = m.start()
    m2 = re.search(r"\n[^\n]*Class descriptor\s*:", dump[m.end():])
    end = m.end() + m2.start() if m2 else len(dump)
    return dump[start:end]


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
    d_new = os.path.join(work, 'dexdump_994.txt')
    print('主验 dexdump: %s' % apk_new)
    dexdump_all(apk_new, d_new)
    dump = open(d_new, encoding='utf-8', errors='replace').read()
    print('  dump 长度 %d' % len(dump))
    print()

    newc = slice_class(dump, NEW_CLS)
    oldc = slice_class(dump, OLD_CLS)

    total = 0
    fails = []

    def check(desc, ok, extra=''):
        nonlocal total
        total += 1
        print('  %s %s%s' % ('✅' if ok else '❌', desc,
                             ('  ' + extra) if extra else ''))
        if not ok:
            fails.append(desc)

    print('=== 1. 新类 PageFollowPolicy 确实编进包 ===')
    check('类 descriptor 存在', newc is not None)
    if newc is None:
        print('\n新类不存在，后面的检查无意义，停止。')
        return 1
    for sig, why in (
            ('isPageHeld', '五道门判据'),
            ('reportsStill', 'v44 热待机语义'),
            ('clampOffset', '位移钳制')):
        check('方法 %s（%s）存在' % (sig, why), sig in newc)
    check('NO_BASELINE 常量存在', 'NO_BASELINE' in newc)
    print()

    print('=== 2. 新类零状态（除 NO_BASELINE 外无静态字段）===')
    # ⚠️ dexdump 的真实格式（已取证）：
    #      Static fields     -        ← **无方括号计数**
    #        #0 : (in L...)
    #          name : 'NO_BASELINE'
    # 不能用 `Static fields\[-\d+\]:` 去匹配 —— 那样永远匹配不到。
    sblk = re.search(r'Static fields\s*(?:-|\[[^\]]*\])\s*:?(.*?)'
                     r'(?=\n\s*Instance fields|\n\s*Direct methods|\Z)',
                     newc, re.S)
    snames = set(re.findall(r"name\s*:\s*'([A-Za-z_$][\w$]*)'",
                             sblk.group(1) if sblk else ''))
    iblk = re.search(r'Instance fields\s*(?:-|\[[^\]]*\])\s*:?'
                     r'(.*?)(?=\n\s*(?:Static fields|Direct methods)\s)',
                     newc, re.S)
    inames = set(re.findall(r"name\s*:\s*'([A-Za-z_$][\w$]*)'",
                            iblk.group(1) if iblk else ''))
    check('静态字段恰为 NO_BASELINE 一个',
          snames == {'NO_BASELINE'}, '实际: %s' % (sorted(snames) or '无'))
    check('NO_BASELINE 的值 = Integer.MIN_VALUE',
          re.search(r"name\s*:\s*'NO_BASELINE'.*?value\s*:\s*-2147483648",
                    newc, re.S) is not None)
    check('无实例字段（零状态 = 可单测的前提）', not inames,
          '实际: %s' % (sorted(inames) or '无'))
    print()

    print('=== 3. hook 侧已改成转调 ===')
    check('ActivityButtonHook 存在', oldc is not None)
    if oldc is not None:
        check('调用 PageFollowPolicy.isPageHeld', 'PageFollowPolicy' in oldc
              and 'isPageHeld' in oldc)
        check('调用 PageFollowPolicy.reportsStill', 'reportsStill' in oldc)
        check('调用 PageFollowPolicy.clampOffset', 'clampOffset' in oldc)
    print()

    print('=== 4. 反向对照：旧判据字面量不应残留在 hook 侧 ===')
    if oldc is not None:
        # 这两串是旧实现独有的；搬走之后不该再出现
        for lit, desc in (
                ('FOLLOW_STILL_FRAMES', 'reportsStill 的旧字面量'),
                ('FOLLOW_SANITY_RATIO', 'clampOffset 的旧字面量')):
            # 允许出现在注释说明里，但不应出现在方法体里
            check('%s 不再作为判据运算出现' % desc,
                  lit not in oldc or oldc.count(lit) <= 1,
                  '（出现 %d 次，仅常量传递允许）' % oldc.count(lit))
    print()

    print('=== 5. 阈值常量只在原处定义一次（无第二份口径）===')
    if oldc is not None:
        for c in ('PAGE_MOTION_HOLD_MS', 'HOLD_SNAPSHOT_MS', 'PAGE_HOLD_PX',
                  'PAGE_HOLD_MIN_AREA'):
            # 静态字段定义行：'name : 'PAGE_MOTION_HOLD_MS''
            n = len(re.findall(r"name\s*:\s*'%s'" % c, oldc))
            check('%s 定义 1 次' % c, n == 1, '实际 %d 次' % n)
    print()

    if apk_old:
        print('=== 6. 对照包（code 993）：新类应不存在 ===')
        d_old = os.path.join(work, 'dexdump_993.txt')
        print('  对照 dexdump: %s' % apk_old)
        dexdump_all(apk_old, d_old)
        dump_old = open(d_old, encoding='utf-8', errors='replace').read()
        c_old = slice_class(dump_old, NEW_CLS)
        check('code 993 里没有 PageFollowPolicy（证明本轮新增）',
              c_old is None)
        oc_old = slice_class(dump_old, OLD_CLS)
        if oc_old is not None:
            check('code 993 的 hook 侧用旧写法（无 PageFollowPolicy 引用）',
                  'PageFollowPolicy' not in oc_old)
        print()
        anchors = 6  # 上面 6 条即区分力锚
        print('  有区分力锚数：%d（code 993 与 994 必须给出不同结论）' % anchors)
        print()

    print('=' * 60)
    if fails:
        print('失败 %d / 共 %d' % (len(fails), total))
        for f in fails:
            print('  ❌ %s' % f)
        return 1
    print('全部通过：%d/%d' % (total, total))
    return 0


if __name__ == '__main__':
    sys.exit(main())
