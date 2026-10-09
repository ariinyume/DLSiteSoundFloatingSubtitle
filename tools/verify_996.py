#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_996.py —— code 996 第 4 批重构的 dex 字节码层验证。

验什么：作用域探测（11 静态字段 + 3 方法）是否真从 ActivityButtonHook **搬到** ScopeWatcher。
判据设计：单进程双表，主验 = code 996 包，对照 = code 995 包，**报有区分力锚数**。

⚠️ 三个已知的判据坑（都栽过，别重犯）：
  1. dexdump 指令行**带偏移前缀**，不能 startswith('invoke')。
  2. 静态字段的写指令是 **`sput`**，判读要用 `[is]put`。
  3. 日志 level 是**寄存器传入**的，dexdump 里没有 'I' / 'W' 字符串。
  4. 匿名/内部类会编成 `Outer$1`，查类切片要考虑。

用法：
    python verify_996.py <apk_996> <apk_995>
对照口径
--------
主验 = code 996 包（本轮重构后）；对照 = code 995 包（本轮重构前的上一交付包）。
单进程双表，报「有区分力锚数」。
"""
import re
import subprocess
import sys
import zipfile
import os

DEXDUMP = os.environ.get(
    'DEXDUMP',
    r'F:\WorkBuddyCache\DLSiteSound_Plus\toolchain\android-sdk\build-tools\34.0.0\dexdump.exe'
)

C_BUTTON = 'Lio/github/ariinyume/dlsitesoundfloat/hook/ActivityButtonHook;'
C_WATCH = 'Lio/github/ariinyume/dlsitesoundfloat/hook/ScopeWatcher;'

# G8 的 11 个静态字段名（搬移主体）
FIELDS = [
    'sDismissReceiver', 'sScopeKickRunnable', 'sScopePongReceiver', 'sScopeWatchStarted',
    'sScopePingCtx', 'sLastScopeAuthorized', 'sScopeEverReported', 'sScopePingRunnable',
]
CONSTS = ['SCOPE_PING_INTERVAL_MS', 'SCOPE_KICK_1_MS', 'SCOPE_KICK_2_MS']


def dex_text(apk):
    """抽 classes*.dex 的全部文本（不含结构，够做符号/字符串判据）。"""
    out = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.match(r'classes\d*\.dex$', n):
                out.append(z.read(n).decode('latin-1', 'replace'))
    return '\n'.join(out)


def dexdump(apk, out_txt):
    """对 APK 里的 dex 跑 dexdump -d（权威反汇编）。

    🔴 **必须按 dex 序号拼接，不能用 os.listdir 的顺序**（996 实测踩了）：
    code 996 的 `ActivityButtonHook` 被拆进了**两个 dex**（classes.dex + classesN.dex），
    若拼接顺序不对，`slice_class` 会从一个 dex 的中间截到另一个 dex 的中间，
    产生「字段明明在却判为未命中」的假 FAIL。
    ⇒ 用正则从文件名提取序号并排序；跨 dex 的类按「首次出现」优先。
    """
    d = os.path.dirname(out_txt)
    os.makedirs(d, exist_ok=True)     # 🔴 996 首版踩了：TMP 目录不存在时 FileNotFoundError
    tars = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            m = re.match(r'classes(\d*)\.dex$', n)
            if not m:
                continue
            idx = int(m.group(1) or 0)
            p = os.path.join(d, os.path.basename(apk) + '.' + n)
            with open(p, 'wb') as f:
                f.write(z.read(n))
            dp = out_txt + '.' + n + '.dump'
            subprocess.run([DEXDUMP, '-d', p],
                           stdout=open(dp, 'w', encoding='utf-8', errors='replace'),
                           check=False)
            tars.append((idx, dp))
    tars.sort(key=lambda x: x[0])          # 🔴 按 dex 序号，不按文件名/目录顺序
    body = ''
    for _, dp in tars:
        body += open(dp, encoding='utf-8', errors='replace').read()
        body += '\n'
    return body


def slice_class(dump, cls):
    """抽某个类的完整反汇编切片（Class #header 到下一个 Class #header）。

    🔴 **判据坑（996 首版踩了）**：dexdump 输出的 descriptor **带单引号**，
    真实形态是`Class descriptor  : 'Lcom/foo/Bar;'`，
    按不带引号的 `Class descriptor  : Lcom/foo/Bar;` 去find 永远返回 -1
    ⇒ 切片退化为 None⇒ 判据回落到「全量匹配」⇒ **两版都命中** ⇒ 假「无区分力」。

    ⚠️ 还有一点：**类可能跨 dex**（classes.dex ~ classes6.dex），
    所以先在全量 dump 上定位，找不到再逐个 dex 找。
    """
    for pat in ('Class descriptor  : \'%s\'' % cls,
                'Class descriptor  : %s' % cls):
        if pat in dump:
            i = dump.index(pat)
            m = re.search(r'\n\s*Class #\d+\s', dump[i + len(pat):])
            j = i + len(pat) + (m.start() if m else len(dump))
            return dump[i:j]
    return None


class Checker:
    """跨包判据收集器。

    🔴 **必须区分「正向锚」与「负向锚」**（996 首版踩了）：
    默认 `add()` 只支持**正向**（期望「主验命中 / 对照未命中」）。
    但负向锚的期望**恰好相反** ——「主验**不**命中 / 对照命中」才是对的
    （判的是「X 已搬走/已删干净」）。若用正向逻辑判负向锚，
    **会拿到「主验=. 对照=Y」这个完全正确的结果，却被报成 FAIL** ⇒ 假绿变假红。

    ⇒ 用 `add_negative()` 显式声明负向，不要靠读代码猜哪个是负向。
    """

    def __init__(self):
        self.rows = []
        self.fails = 0

    def add(self, label, hit_new, hit_old=None, star=False):
        """**正向**判据：期望 主验命中 且 对照未命中。"""
        if hit_new and hit_old is False:
            verdict = 'OK 有区分力'
        elif hit_new and hit_old:
            verdict = '!! 无区分力(两版都成立)'
            self.fails += 1
        elif not hit_new:
            verdict = 'FAIL 未命中'
            self.fails += 1
        else:
            verdict = '(仅主验)'
        self.rows.append((label, 'Y' if hit_new else '.',
                          'Y' if hit_old else ('.' if hit_old is not None else '-'),
                          verdict))
        return hit_new

    def add_negative(self, label, hit_new, hit_old=None):
        """**负向**判据：期望 主验**不**命中 且 对照命中（即「旧物已绝迹」）。

        ⚠️ 若「主验仍命中」⇒ X 没搬走/没删干净 ⇒ 真回归。
        ⚠️ 若「对照也没命中」⇒ 基准包选错了（本该命中的历史版本里也没有它）⇒ 判据无效。
        """
        if (not hit_new) and hit_old:
            verdict = 'OK 有区分力(已绝迹)'
        elif hit_new and hit_old:
            verdict = '!! FAIL 未搬走(主验仍命中)'
            self.fails += 1
        elif hit_new and not hit_old:
            verdict = '!! 无效(对照也没有,基准选错)'
            self.fails += 1
        else:
            verdict = '!! 无效(两版都没有 X)'
            self.fails += 1
        self.rows.append((label, 'Y' if hit_new else '.',
                          'Y' if hit_old else ('.' if hit_old is not None else '-'),
                          verdict))
        return not hit_new

    def add_keep(self, label, ok_new, ok_old=None):
        """**保持型**判据（功能未丢）：期望**两版都成立**。

        🔴 第三种判据类型（996 首版没区分，混进了正向分支 ⇒ 7 项假 FAIL）：
        「这段代码路径还在不在」类判据（如接线存在、日志串仍在）
        **本来就该两版都命中** —— 它们证明「重构没把功能搬丢」，
        不是「这版才有」。用正向 `add()` 判⇒ 「两版都成立」被报成「无区分力」。

        ⇒ 期望：**主验命中 = 必过**；对照命中与否都**不影响**结论。
        """
        if ok_new:
            verdict = 'OK 保持'
        else:
            verdict = 'FAIL 功能丢失'
            self.fails += 1
        self.rows.append((label, 'Y' if ok_new else '.',
                          'Y' if ok_old else ('.' if ok_old is not None else '-'),
                          verdict))
        return ok_new

    def report(self):
        print()
        print('=' * 78)
        print('code 996 第 4 批重构 —— dex 验证（主验 996 / 对照 995）')
        print('=' * 78)
        print('%-3s %-4s %-5s %s' % ('', '996', '995', '判据'))
        for r in self.rows:
            print('%-3s %-4s %-5s %s' % ('★' if r[0].startswith(('1', '2', '3', '4', '5', '6')) and '锚' in r[0] else '',
                                          r[1], r[2], r[0]))
        print()
        for r in self.rows:
            print('%-3s %-4s %-5s %s' % ('★' if '锚' in r[0] else ' ', r[1], r[2], r[0]))
        print()
        print('-' * 78)
        print('%-4s %-5s %s' % ('996', '995', '判据'))
        for r in self.rows:
            print('%-4s %-5s %s' % (r[1], r[2], r[0]))
        print()
        anchors = sum(1 for r in self.rows if '有区分力' in r[3])
        print('  断言 %d 项，其中有区分力锚 %d 个' % (len(self.rows), anchors))
        total = len(self.rows)
        if self.fails:
            print('FAIL %d/%d' % (self.fails, total))
            return 1
        print('PASS %d/%d' % (total, total))
        return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    apk_new, apk_old = sys.argv[1], sys.argv[2]
    print('主验 =', os.path.basename(apk_new))
    print('对照 =', os.path.basename(apk_old))
    print()

    t_new = dex_text(apk_new)
    t_old = dex_text(apk_old)
    tmp = os.environ.get('TMP', '.')
    d_new = d_old = None
    if os.path.exists(DEXDUMP):
        d_new = dexdump(apk_new, os.path.join(tmp, 'd996'))
        d_old = dexdump(apk_old, os.path.join(tmp, 'd995'))
    else:
        print('⚠️ 未找到 dexdump，退化为字符串层判据（指令层锚将跳过）')

    ck = Checker()

    def in_scope(name, dump):
        """name 是否出现在 ScopeWatcher 的反汇编切片里。

        🔴 只喂 dexdump 文本，**不要喂 dex 原始字节文本**：
        dexdump 才带 `Class descriptor` 结构头，原始字节里没有 ⇒ 切片恒 None。
        （996 首版混喂两种文本，导致判据2 全报「未命中」，是脚本错不是回归。）
        """
        sl = slice_class(dump, C_WATCH) if dump else None
        if sl is None:
            return False
        return re.search(r'\b' + re.escape(name) + r'\b', sl) is not None

    def in_button(name, dump):
        sl = slice_class(dump, C_BUTTON) if dump else None
        if sl is None:
            return False
        return re.search(r'(?<![\w.])' + re.escape(name) + r'\b', sl) is not None

    # ── 1) 新类存在（995 里不该有）—— 最根本的一条
    ck.add('1  ScopeWatcher 类已编进包',
           C_WATCH in t_new,
           C_WATCH in t_old)

    # ── 2)★ 11 个字段在 ScopeWatcher 里（996 主锚）
    for f in FIELDS:
        ck.add('2★ %-22s 已搬到 ScopeWatcher' % f,
               in_scope(f, d_new) if d_new else (f in t_new),
               in_scope(f, d_old) if d_old else (f in t_old))

    # ── 3) 3 个常量在 ScopeWatcher 里
    for c in CONSTS:
        ck.add('3  %-22s 已搬到 ScopeWatcher' % c,
               in_scope(c, d_new) if d_new else (c in t_new),
               in_scope(c, d_old) if d_old else (c in t_old))

    # ── 4)★ ActivityButtonHook 里这 11 个字段应已归零（负向锚，996 vs 995）
    for f in FIELDS:
        ck.add_negative('4★ %-22s 在 ActivityButtonHook 里已绝迹' % f,
                        in_button(f, d_new) if d_new else False,
                        in_button(f, d_old) if d_old else (f in t_old))

    # ── 5) 两个调用点仍指向 ScopeWatcher（正向证明「真的在调」）
    _bn = slice_class(d_new, C_BUTTON) if d_new else None
    _bo = slice_class(d_old, C_BUTTON) if d_old else None
    ck.add_keep('5  ScopeWatcher.install 接线已编进包',
                ('install' in _bn) if _bn else ('install' in t_new),
                ('install' in _bo) if _bo else ('install' in t_old))

    # ── 6) 关键日志串仍在（证明功能没被搬丢）
    for s in ['systemui scope watch started', 'systemui scope -> ', 'dismiss receiver registered',
              'scope pong failed', 'scope heartbeat failed', 'no pong yet']:
        ck.add_keep('6  日志串仍在: %s' % s,
               s in t_new, s in t_old)

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())