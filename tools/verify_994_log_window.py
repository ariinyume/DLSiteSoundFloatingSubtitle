#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
code 994 真机日志审计：判定「Button / [几何] 日志量下降」是回归还是采样效应。

背景
----
code 993 与 code 994 的两次真机日志窗口长度差 14.6 倍（291.3 min vs 20.0 min），
直接比绝对条数会得出「Button 从 1668 掉到 145 = 掉了 91%」的假回归结论。
本脚本把窗口长度算进去，并用**进程 pid 连续性**解释「structure event -> instant scan」
归零的真正原因。

三条独立证据
------------
1. 速率归一化：每分钟条数，Button 反而 1.26x 上升 ⇒ 绝对条数下降是窗口效应。
2. 配额上限：`MAX_POKE_LOGS = 40` 且 `sPokeLogged` **从不重置**；
   993 窗口内 #N 最大值恰好 = 40、且 0 条超过 40 ⇒ 配额在 13:45:36 已打满。
3. 进程未重启：993 与 994 的 App 进程 pid 均为 10433、SystemUI 均为 10174
   ⇒ 静态字段跨窗口保留，配额无法自然重置 ⇒ 994 该日志必然为 0。

用法：python tools/verify_994_log_window.py
"""

import collections
import datetime
import glob
import os
import re
import sys

TS = re.compile(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})')
PROC = re.compile(r'\((jp\.co\.eisys\.dlsitesound|com\.android\.systemui)\)')
PID = re.compile(r'\s(\d+):\s*\d+:\s*\d+\s+[VDIWEF]/')
POKE = re.compile(r'structure event -> instant scan.*#(\d+)')

# 源码常量（ActivityButtonHook.java:787）
MAX_POKE_LOGS = 40

# 速率比容差：低于下界或高于上界判为「需人工解释」
RATE_LO, RATE_HI = 0.4, 2.5

_fail = 0
_pass = 0


def check(ok, label, detail=''):
    global _fail, _pass
    if ok:
        _pass += 1
        print(f'    [PASS] {label}' + (f'  {detail}' if detail else ''))
    else:
        _fail += 1
        print(f'    [FAIL] {label}' + (f'  {detail}' if detail else ''))


def scan(root):
    """返回该日志根目录的统计结果。"""
    tag = collections.Counter()
    pids = collections.defaultdict(set)
    poke_seq = []
    first = last = None

    files = sorted(glob.glob(os.path.join(root, 'log', 'modules_*.log')))
    if not files:
        raise SystemExit(f'!! 在 {root} 下找不到 log/modules_*.log')

    for path in files:
        with open(path, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if 'DLsiteSoundFloat' not in line:
                    continue

                m = TS.search(line)
                stamp = m.group(1) if m else None
                if stamp:
                    if first is None or stamp < first:
                        first = stamp
                    if last is None or stamp > last:
                        last = stamp

                mp = PROC.search(line)
                mi = PID.search(line)
                if mp and mi:
                    pids[mp.group(1)].add(mi.group(1))

                mt = re.search(r'DLsiteSoundFloat:([A-Za-z0-9_]+)\]', line)
                tag[mt.group(1) if mt else '<none>'] += 1

                # instant scan 只在 App 进程（宿主 Activity）里产生
                if ('jp.co.eisys.dlsitesound' in line
                        and 'structure event -> instant scan' in line):
                    mnum = POKE.search(line)
                    if mnum:
                        poke_seq.append(int(mnum.group(1)))

    span = ((datetime.datetime.fromisoformat(last)
             - datetime.datetime.fromisoformat(first)).total_seconds()
            if first and last else 0.0)
    return {
        'tag': tag, 'pids': pids, 'poke': poke_seq,
        'first': first, 'last': last, 'span_min': span / 60.0,
        'total': sum(tag.values()),
    }


def main():
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ws = os.path.dirname(base)

    print('=' * 66)
    print('code 994 真机日志窗口审计（回归 vs 采样效应）')
    print('=' * 66)

    r993 = scan(os.path.join(ws, 'work_diag_993'))
    r994 = scan(os.path.join(ws, 'work_diag_994'))

    print(f'\n  code 993  窗口 {r993["first"][11:19]} ~ {r993["last"][11:19]}'
          f'  跨度 {r993["span_min"]:.1f} min  模块行 {r993["total"]}')
    print(f'  code 994  窗口 {r994["first"][11:19]} ~ {r994["last"][11:19]}'
          f'  跨度 {r994["span_min"]:.1f} min  模块行 {r994["total"]}')
    print(f'  窗口长度比 {r993["span_min"] / r994["span_min"]:.1f}x '
          f'⇒ 绝对条数不可直接比较')

    # ── 证据 1：速率归一化 ────────────────────────────────────────────
    print('\n【证据 1】按每分钟速率归一化（Button 是本轮唯一被质疑的 TAG）')
    check(r993['span_min'] > 0 and r994['span_min'] > 0,
          '两个窗口跨度均可计算',
          f'{r993["span_min"]:.1f} min / {r994["span_min"]:.1f} min')

    for key in ('Button', 'Pos', 'View', 'StatusBar'):
        a = r993['tag'].get(key, 0) / r993['span_min']
        b = r994['tag'].get(key, 0) / r994['span_min']
        ratio = b / a if a else float('inf')
        note = '' if RATE_LO <= ratio <= RATE_HI else '   <== 超容差，需解释'
        print(f'    {key:<12} 993={a:7.2f}/min  994={b:7.2f}/min  '
              f'速率比 {ratio:5.2f}x{note}')

    btn_a = r993['tag'].get('Button', 0) / r993['span_min']
    btn_b = r994['tag'].get('Button', 0) / r994['span_min']
    check(btn_b >= btn_a,
          'Button 速率未下降（绝对条数下降是窗口效应）',
          f'{btn_a:.2f}/min → {btn_b:.2f}/min  ({btn_b / btn_a:.2f}x)')

    # ── 证据 2：配额上限 ──────────────────────────────────────────────
    print('\n【证据 2】MAX_POKE_LOGS 配额效应'
          f'（源码 ActivityButtonHook.java:787 = {MAX_POKE_LOGS}，sPokeLogged 从不重置）')
    poke = r993['poke']
    check(bool(poke), 'code 993 窗口内采到 instant scan 序列', f'{len(poke)} 条')
    if poke:
        mx = max(poke)
        over = [n for n in poke if n > MAX_POKE_LOGS]
        check(mx == MAX_POKE_LOGS,
              f'#N 最大值恰好等于上限 {MAX_POKE_LOGS}',
              f'实测 max=#N {mx}')
        check(not over,
              f'无任何 #N 超过 {MAX_POKE_LOGS} ⇒ 配额确已打满',
              f'越界条数 {len(over)}')
        check(not r994['poke'],
              'code 994 窗口内 instant scan 为 0（配额已耗尽，符合预期）',
              f'实测 {len(r994["poke"])} 条')

    # ── 证据 3：进程连续性 ────────────────────────────────────────────
    print('\n【证据 3】进程 pid 跨窗口连续性（静态字段能否自然重置）')
    for proc in ('jp.co.eisys.dlsitesound', 'com.android.systemui'):
        a = r993['pids'].get(proc, set())
        b = r994['pids'].get(proc, set())
        same = bool(a) and a == b
        print(f'    {proc:<30} 993={sorted(a)}  994={sorted(b)}')
        check(same,
              f'{proc} 进程未重启 ⇒ 静态字段跨窗口保留',
              f'一致' if same else 'pid 变化，配额可能已重置')

    # ── 结论 ──────────────────────────────────────────────────────────
    print('\n' + '=' * 66)
    verdict = (_fail == 0)
    if verdict:
        print('结论：Button / [几何] 绝对条数下降 100% 是【日志窗口长度差异】'
              '叠加【sPokeLogged 配额打满】所致，')
        print('      不是 code 994 重构引入的回归。'
              '速率归一化后 Button 反而上升。')
    else:
        print(f'结论：{_fail} 项检查未通过，需人工复核。')
    print(f'\n全部通过：{_pass}/{_pass + _fail}')
    print('=' * 66)
    return 0 if verdict else 1


if __name__ == '__main__':
    sys.exit(main())
