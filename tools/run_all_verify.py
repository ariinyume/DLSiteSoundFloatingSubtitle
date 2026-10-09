#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键跑全部 verify_*.py，汇总 PASS/FAIL 表格。

设计约束（照项目铁律来，不自创）：
  · **退出码有意义**：任一脚本 FAIL ⇒ 本脚本退出码非 0 ⇒ CI/钩子能直接用。
  · **不吞错**：脚本不存在/崩溃/超时，都要单独报出来，不能混成「FAIL」了事。
  · **只读**：本脚本只调用子脚本，不改任何文件。

用法：
    python tools/run_all_verify.py            # 跑全部
    python tools/run_all_verify.py 995 protocol   # 只跑名字含这些关键字的
    python tools/run_all_verify.py --list     # 只列清单不执行

⚠️ 为什么要它：
    项目已有多个 verify_*.py，但每个都是「手工单跑一次」。
    改了码想全量回归时，得逐个敲命令、逐个看输出、逐个判断有没有 FAIL ——
    这是「验证链断裂」的经典形态：跑了，但没人保证全跑了。
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# 脚本 → (类别, 一句话说明它验什么)
# ⚠️ 说明必须与脚本内部判据一致；改判据时同步改这里，否则索引表本身就会骗人。
SCRIPTS = [
    ('verify_997.py', 'dex 字节码层',
     'code 997 亮度锁定 + 第5批重构：常量值变化与 PokeThrottle 调用（11 项，7 个有区分力锚）'),
    ('verify_996.py', 'dex 字节码层',
     'code 996 第4批重构：作用域探测是否真搬到 ScopeWatcher（27 项，20 个有区分力锚）'),
    ('verify_995.py', 'dex 字节码层',
     'code 995 六项改动是否真编进 APK（28 项，22 个有区分力锚）'),
    ('verify_protocol_src.py', '源码层',
     'Protocol 单点真源：action/extra 字面量仅存在于 Protocol，转发壳完好（10 项）'),
    ('verify_994.py', 'dex 字节码层',
     'code 994 的 PageFollowPolicy 是否真编进 APK（6 个区分力锚）'),
    ('verify_994_log_window.py', '日志层',
     '994 真机日志窗口归一化脚本（防止跨窗口比条数出假回归）'),
]

# 每个 dex 脚本期望的 code 号：(主验 code, 对照 code 或 None)
# ⚠️ 不能「按文件名取最新两个」—— 那样会把 995 当成 993 的对照，判据必假失败。
#   必须是脚本自己声明期望的 code，再精确匹配。
# ⚠️ 只有**真正解析 APK/dex** 的脚本才登记在这里。
#   不接包的脚本（如源码层校验、真机日志校验）不要登记，否则会被误判成「缺参数」。
NEEDS_APK = {
    'verify_997.py': (997, 996),   # <apk_997> <apk_996>
    'verify_996.py': (996, 995),   # <apk_996> <apk_995>
    'verify_995.py': (995, 994),   # <apk_995> <apk_994>
    'verify_994.py': (994, 993),   # <apk_994> <apk_993>
}

TIMEOUT = 300  # 秒；单个 dex dump 脚本实测 10~60s


def find_apks(repo):
    """扫出所有 `DLsiteFloat-*-codeNNN-debug.apk`，按 code 号建索引。

    ⚠️ 口径（照铁律 42）：搜索范围 = 仓库构建输出目录 + 工作区根 + releases/legacy；
    同code 号若多处存在，**取路径最短的那个**（构建目录优先，其次工作区根，最后归档），
    因为归档里的包可能已被后续轮次取代。
    """
    pat = re.compile(r'DLsiteFloat-.*-code(\d+)-debug\.apk$')
    cands = {}
    roots = [
        os.path.join(repo, 'app', 'build', 'outputs', 'apk', 'debug'),
        os.path.normpath(os.path.join(repo, '..')),   # 工作区根
        os.path.join(repo, 'releases', 'legacy'),
    ]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                m = pat.match(f)
                if not m:
                    continue
                code = int(m.group(1))
                p = os.path.join(dirpath, f)
                prev = cands.get(code)
                if prev is None or len(p) < len(prev):
                    cands[code] = p
    return cands


def build_argv(name, repo):
    """给需要 APK 的脚本按其期望 code 精确配包。

    返回 (args, missing)：
      · 不接包参数的脚本 → `([], [])`   **空列表，不是 None**
      · 缺主验包→ `([], [code])`
      · 缺对照包   → `([主验包], [对照code])`
    ⚠️ 必须用空列表当「不接参数」的信号。若返回 None，会被调用方当成「缺包」⇒ 误报 SKIP。
    """
    if name not in NEEDS_APK:
        return [], []
    want_main, want_old = NEEDS_APK[name]
    cands = find_apks(repo)
    missing = []
    args = []
    if want_main in cands:
        args.append(cands[want_main])
    else:
        missing.append(want_main)
    if want_old is not None:
        if want_old in cands:
            args.append(cands[want_old])
        else:
            missing.append(want_old)
    return args, missing


def discover():
    """兜底：把目录里 SCRIPTS 未登记的 verify_*.py 也捞出来，提醒补登记。"""
    on_disk = {f for f in os.listdir(HERE)
               if f.startswith('verify_') and f.endswith('.py')}
    registered = {s[0] for s in SCRIPTS}
    return sorted(on_disk - registered)


def run_one(name, repo):
    path = os.path.join(HERE, name)
    if not os.path.exists(path):
        return 'MISSING', 0, 0.0, '文件不存在'

    argv = [sys.executable, path]
    extra, missing = build_argv(name, repo)
    if not extra:
        # 没配到包。真需要包的脚本 ⇒ 缺包；不接包的脚本 ⇒ extra 为空但missing 也为空
        if missing:
            return 'SKIP', 0, 0.0, '未找到 code %s 的 APK' % missing
        out_extra = ''
    elif missing:
        # 缺对照包：主验仍可跑（对照类判据会被判为未命中）
        out_extra = '（缺 code %s 的对照包，部分判据将被判为未命中）' % missing
    else:
        out_extra = ''
    argv += extra

    t0 = time.time()
    try:
        # cwd 设成仓库根：脚本里普遍用相对路径读 APK / dex
        p = subprocess.run(argv, cwd=repo,
                           capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=TIMEOUT)
        dt = time.time() - t0
        out = (p.stdout or '') + (p.stderr or '') + ('\n' + out_extra if out_extra else '')
        if p.returncode == 0:
            return 'PASS', p.returncode, dt, out
        # exit 2 且是 usage 形态 ⇒ 参数问题，不算判据失败
        if p.returncode == 2 and 'usage' in out.lower():
            return 'SKIP', p.returncode, dt, out
        return 'FAIL', p.returncode, dt, out
    except subprocess.TimeoutExpired:
        return 'TIMEOUT', -9, time.time() - t0, '超过 %ds 未结束' % TIMEOUT
    except Exception as e:                      # noqa: BLE001
        return 'ERROR', -1, time.time() - t0, '%s: %s' % (type(e).__name__, e)


def verdict_line(out):
    """从脚本输出里抓它自己的结论行 —— 优先读脚本自报值，不自己重算。

    ⚠️ 各脚本结论格式不统一（实测），故多pattern 匹配：
      verify_995   → 「通过 28/28，有区分力锚 28 个」
      verify_994   → 「全部通过：20/20」
      protocol_src → 「PASS 10/10」
      log_window   → 「全部通过：8/8」
    """
    pats = [
        r'通过\s*(\d+)\s*/\s*(\d+)，有区分力锚\s*(\d+)',
        r'^\s*(?:全部通过|PASS)\s*[:：]?\s*(\d+)\s*/\s*(\d+)',
        r'^\s*失败\s*(\d+)\s*/\s*共\s*(\d+)',
        r'\bPASS\s+(\d+)\s*/\s*(\d+)',
    ]
    for p in pats:
        m = re.search(p, out, re.M)
        if m:
            if m.lastindex and m.lastindex >= 3:
                return '%s/%s（锚%s）' % (m.group(1), m.group(2), m.group(3))
            return '%s/%s' % (m.group(1), m.group(2))
    return ''


def main(argv):
    if '--list' in argv:
        print('登记的验证脚本（共 %d 个）' % len(SCRIPTS))
        for n, cat, desc in SCRIPTS:
            print('  %-26s [%-10s] %s' % (n, cat, desc))
        extra = discover()
        if extra:
            print('\n⚠️ 以下 verify_*.py 未登记到本脚本，建议补登记：')
            for n in extra:
                print('  %s' % n)
        return 0

    keys = [a for a in argv if not a.startswith('-')]
    targets = [s for s in SCRIPTS if not keys or any(k in s[0] for k in keys)]
    if not targets:
        print('没有匹配到脚本。关键字：%s' % (keys or '(空= 全部)'))
        return 2

    print('=' * 78)
    print('一键验证：跑 %d 个脚本' % len(targets))
    print('=' * 78)

    repo = os.path.dirname(HERE)
    rows = []
    for name, cat, desc in targets:
        print('\n>>> %s  [%s]  %s' % (name, cat, desc))
        print('-' * 78)
        status, rc, dt, out = run_one(name, repo)
        tail = out.strip().split('\n')
        print('\n'.join(tail[-14:] if len(tail) > 14 else tail))
        print('-' * 78)
        print('  ⇒ %s  (%.1fs, exit=%d) %s'
              % (status, dt, rc, ('[' + verdict_line(out) + ']') if verdict_line(out) else ''))
        rows.append((name, cat, status, rc, dt, verdict_line(out)))

    # ---- 汇总表 ----
    print()
    print('=' * 78)
    print('汇总')
    print('=' * 78)
    print('%-28s %-12s %-8s %-8s %s' % ('脚本', '类别', '结果', '用时', '判据'))
    for name, cat, status, rc, dt, v in rows:
        print('%-28s %-12s %-8s %-8s %s' % (name, cat, status, '%.1fs' % dt, v or '-'))

    bad = [r for r in rows if r[2] in ('FAIL', 'ERROR', 'TIMEOUT', 'MISSING')]
    skipped = [r for r in rows if r[2] == 'SKIP']
    print()
    if bad:
        print('❌ 未全绿：%d/%d 个脚本未通过' % (len(bad), len(rows)))
        for n, _, st, rc, _, _ in bad:
            print('   - %s  (%s, exit=%d)' % (n, st, rc))
        rc_ret = 1
    else:
        print('✅ 全绿：%d/%d' % (len(rows), len(rows)))
        rc_ret = 0
    if skipped:
        print('⚠️ 跳过 %d 个（参数/环境不足，不计入失败）：%s'
              % (len(skipped), ', '.join(r[0] for r in skipped)))

    extra = discover()
    if extra:
        print()
        print('⚠️ 以下 verify_*.py 未登记（本次没跑）：%s' % ', '.join(extra))
    return rc_ret


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))