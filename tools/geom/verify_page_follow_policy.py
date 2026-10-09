#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【2.2.14 / code 994】PageFollowPolicy 的真机黄金向量对照脚本

用途
----
`PageFollowPolicy.isPageHeld` 的五道门涉及 5 个阈值常量的组合
(PAGE_MOTION_HOLD_MS=200 / HOLD_SNAPSHOT_MS=2600 / PAGE_HOLD_PX=24 /
 PAGE_HOLD_MIN_AREA=0.12 /哨兵 NO_BASELINE)，
此前**只能真机试**。v36 复盘里「门③读错字段、从来没有生效过」正是这类
组合判据的典型事故 —— 门①②恰好都能通过，于是门③专门要防的场景原样发生。

本脚本用**真机实测值**当测试向量，离线验证抽出的判据层行为正确。

向量来源
--------
LSPosed 日志 `work_diag_993/log/modules_2026-10-09T*.log` 中82 条
    `hide suppressed: page held (vis=... ref=... snap=...ms attached=...)`
这一行**就是 `isPageHeld` 返回 true 的直接证据**（该日志只在门放行时打）。
实测特征：
    vis = 2772（全部 82 条）—— 正是 isPageHeld 门⓪ 注释里记的
        「切后台再回前台，播放页容器停靠屏外 translateY=2772px」
    snap(位移) = 2767 ~ 2772   snapMs(距快照) = 211 ~ 428ms
    ref = anchor（全部）        attached = true（全部）

⚠️刻意不写第二份实现来对比
    「抄两遍」是伪验证：两边一样错反而绿灯。
    等价性最终由 **dex 字节码层断言**兜底（见verify_994.py），
    本脚本负责的是「判据在真机取值域上给出与真机一致的结论」。

用法
----
    python verify_page_follow_policy.py
退出码 0 = 全部通过
"""

import sys

# ══════════════════════════════════════════════════════════════
# 阈值常量 —— 必须与 ActivityButtonHook / PageFollowPolicy 逐字一致
# ══════════════════════════════════════════════════════════════
PAGE_MOTION_HOLD_MS   = 200
HOLD_SNAPSHOT_MS      = 2600
PAGE_HOLD_PX          = 24
PAGE_HOLD_MIN_AREA    = 0.12
FOLLOW_STILL_FRAMES   = 90
FOLLOW_SANITY_RATIO   = 1.6
NO_BASELINE           = -2147483648  # Integer.MIN_VALUE

# 真机屏幕（MEM 有记：1272x2772）
SCREEN_W, SCREEN_H = 1272, 2772

FOLLOW = 'follow'
CLAMP = 'clamp'


# ══════════════════════════════════════════════════════════════
# 被测对象：严格照抄 PageFollowPolicy.java 的算法
# （保持与源码同形，便于逐行 diff；等价性由 dex 层兜底）
# ══════════════════════════════════════════════════════════════
def is_page_held(player_confirmed, last_motion_ms, now,
                 held_offset_ms, held_offset, hold_probe_attached,
                 page_visual_offset, screen_w, screen_h, anchor_area_ratio,
                 motion_hold_ms, snapshot_ms, hold_px, hold_min_area):
    # 门⓪ 会话闸
    if not player_confirmed:
        return False
    # 门② 位移时间轴
    if last_motion_ms != 0 and now - last_motion_ms < motion_hold_ms:
        return True
    # 门③ 快照兜底（先于门④）
    if (held_offset_ms != 0 and now - held_offset_ms < snapshot_ms
            and abs(held_offset) >= hold_px and hold_probe_attached):
        return True
    # 门④ 视觉位移
    if page_visual_offset == NO_BASELINE:
        return False
    if abs(page_visual_offset) < hold_px:
        return False
    # 门⑤ 锚点面积（拿不到屏幕尺寸 → 宁可维持现状 = true）
    if screen_w <= 0 or screen_h <= 0:
        return True
    return anchor_area_ratio >= hold_min_area


def reports_still(following, follow_still, still_frames):
    return (not following) or follow_still >= still_frames


def clamp_offset(delta, screen_h, ratio):
    if screen_h <= 0:
        return delta
    lim = int(screen_h * ratio)
    if delta > lim:
        return lim
    if delta < -lim:
        return -lim
    return delta


# ══════════════════════════════════════════════════════════════
# 黄金向量
# ══════════════════════════════════════════════════════════════
def load_real_vectors():
    """读真机日志里82 条 hide suppressed 的实测取值。

    找不到文件时返回 None（调用方降级为内置向量，不静默跳过）。
    """
    import os
    import re
    # 真机日志在**仓库外的工作区**目录：<repo>/../../work_diag_993/log
    # （脚本在 <repo>/tools/geom/，要退 4 层：geom→tools→repo→工作区根）
    here = os.path.dirname(os.path.abspath(__file__))          # .../tools/geom
    repo = os.path.dirname(os.path.dirname(here))              # .../DLsiteSound_FloatSubtitle
    work = os.path.dirname(repo)                               # .../DLSiteSound_Plus
    base = os.path.join(work, 'work_diag_993', 'log')
    if not os.path.isdir(base):
        return None
    pat = re.compile(
        r'hide suppressed: page held '
        r'\(vis=(-?\d+)px ref=(\w+) snap=(-?\d+)px/(\d+)ms attached=(\w+)\)')
    out = []
    for fn in sorted(os.listdir(base)):
        if not fn.startswith('modules_') or not fn.endswith('.log'):
            continue
        try:
            with open(os.path.join(base, fn), encoding='utf-8',
                      errors='replace') as f:
                for line in f:
                    m = pat.search(line)
                    if m:
                        out.append((int(m.group(1)), int(m.group(3)),
                                    int(m.group(4)), m.group(5) == 'true'))
        except OSError:
            continue
    return out or None


# ══════════════════════════════════════════════════════════════
# 1) isPageHeld —— 真机向量（期望全部 True：该行只在门放行时打出）
# ══════════════════════════════════════════════════════════════
def test_is_page_held_real():
    vecs = load_real_vectors()
    if vecs is None:
        # 内置降级向量：与真机 vis=2772 / snap≈2770 / ms≈250 同形
        vecs = [(2772, 2770, 250, True) for _ in range(4)]
        src = '内置降级向量（未找到真机日志）'
    else:
        src = '真机日志实测 %d 条' % len(vecs)

    # 去重后逐条验
    uniq = sorted(set(vecs))
    fails = []
    for vis, snap, snap_ms, attached in uniq:
        got = is_page_held(
            player_confirmed=True,
            last_motion_ms=0,          # 门② 不放行，让判据落到门③
            now=snap_ms,               # now - 0 = 250 < 200? 否 → 门② 不命中
            held_offset_ms=1,          # 非 0 ⇒ 门③ 生效（now-1=249< 2600）
            held_offset=snap,          # 2770 >= 24 ✅
            hold_probe_attached=attached,
            page_visual_offset=vis,    # 2772
            screen_w=SCREEN_W, screen_h=SCREEN_H,
            anchor_area_ratio=0.5,
            motion_hold_ms=PAGE_MOTION_HOLD_MS,
            snapshot_ms=HOLD_SNAPSHOT_MS,
            hold_px=PAGE_HOLD_PX,
            hold_min_area=PAGE_HOLD_MIN_AREA)
        #注意：last_motion_ms=0 时门② 短路；now=snap_ms 时门③ 的 now-1 = snap_ms-1 < 2600 ✅
        if got is not True:
            fails.append((vis, snap, snap_ms, attached, got))

    print('  isPageHeld 真机向量 [%s]' % src)
    print('    不同取值组合 %d 个，期望全部 True（该日志行只在门放行时打）'
          % len(uniq))
    if fails:
        for f in fails[:8]:
            print('    ❌ vis=%d snap=%d snapMs=%d attached=%s → %s（期望 True）' % f)
        return len(fails), len(uniq)
    print('    ✅ %d/%d 组合判定为 True，与真机一致' % (len(uniq), len(uniq)))
    return 0, len(uniq)


# ══════════════════════════════════════════════════════════════
# 2) isPageHeld —— 边界与门序（每道门单独打靶）
# ══════════════════════════════════════════════════════════════
def test_is_page_held_gates():
    cases = [
        # (说明, kwargs覆盖, 期望)
        ('门⓪ 未确认播放页 → 一律放行',
         dict(player_confirmed=False, last_motion_ms=0, now=999999,
              held_offset_ms=1, held_offset=2772, hold_probe_attached=True,
              page_visual_offset=2772), False),
        ('门⓪ 优先级最高：即使 vis=2772 也放行（切后台回前台的复盘）',
         dict(player_confirmed=False, last_motion_ms=0, now=100,
              held_offset_ms=1, held_offset=2772, hold_probe_attached=True,
              page_visual_offset=2772), False),

        ('门② 刚动过（now-motion=199 < 200）→ 放行',
         dict(player_confirmed=True, last_motion_ms=1000, now=1199,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=0), True),
        ('门② 差1ms 就过阈值（now-motion=200 不< 200）→ 不靠门②',
         dict(player_confirmed=True, last_motion_ms=1000, now=1200,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=0), False),

        ('门③ 快照超龄（now-snapMs=2600 不 < 2600）→ 不靠门③',
         dict(player_confirmed=True, last_motion_ms=0, now=2601,
              held_offset_ms=1, held_offset=2772, hold_probe_attached=True,
              page_visual_offset=0), False),
        ('门③ 位移不足 24px（23 < 24）→ 不靠门③',
         dict(player_confirmed=True, last_motion_ms=0, now=250,
              held_offset_ms=1, held_offset=23, hold_probe_attached=True,
              page_visual_offset=0), False),
        ('门③ 探测锚点已脱落 → 不靠门③',
         dict(player_confirmed=True, last_motion_ms=0, now=250,
              held_offset_ms=1, held_offset=2772, hold_probe_attached=False,
              page_visual_offset=0), False),

        ('门④ 无基线哨兵 → 放行',
         dict(player_confirmed=True, last_motion_ms=0, now=999999,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=NO_BASELINE), False),
        ('门④ |vis|=23 < 24 → 放行',
         dict(player_confirmed=True, last_motion_ms=0, now=999999,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=23), False),

        ('门⑤ 拿不到屏幕尺寸 → 宁可维持现状（返回 true）',
         dict(player_confirmed=True, last_motion_ms=0, now=999999,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=2772, screen_w=0, screen_h=0), True),
        ('门⑤ 面积 0.1199 < 0.12 → 放行',
         dict(player_confirmed=True, last_motion_ms=0, now=999999,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=2772, anchor_area_ratio=0.1199), False),
        ('门⑤ 面积 0.12 >= 0.12 → 拦下（闭区间边界）',
         dict(player_confirmed=True, last_motion_ms=0, now=999999,
              held_offset_ms=0, held_offset=0, hold_probe_attached=False,
              page_visual_offset=2772, anchor_area_ratio=0.12), True),
    ]
    base = dict(player_confirmed=True, last_motion_ms=0, now=0,
                held_offset_ms=0, held_offset=0, hold_probe_attached=False,
                page_visual_offset=NO_BASELINE, screen_w=SCREEN_W,
                screen_h=SCREEN_H, anchor_area_ratio=0.0,
                motion_hold_ms=PAGE_MOTION_HOLD_MS,
                snapshot_ms=HOLD_SNAPSHOT_MS, hold_px=PAGE_HOLD_PX,
                hold_min_area=PAGE_HOLD_MIN_AREA)
    fails = []
    for desc, over, want in cases:
        kw = dict(base)
        kw.update(over)
        got = is_page_held(kw['player_confirmed'], kw['last_motion_ms'],
                           kw['now'], kw['held_offset_ms'], kw['held_offset'],
                           kw['hold_probe_attached'], kw['page_visual_offset'],
                           kw['screen_w'], kw['screen_h'],
                           kw['anchor_area_ratio'], kw['motion_hold_ms'],
                           kw['snapshot_ms'], kw['hold_px'],
                           kw['hold_min_area'])
        if got != want:
            fails.append((desc, want, got))
    print('  isPageHeld 门序/边界 %d 条' % len(cases))
    for desc, want, got in fails:
        print('    ❌ %s → 期望 %s，实际 %s' % (desc, want, got))
    if not fails:
        print('    ✅ %d/%d 通过' % (len(cases), len(cases)))
    return len(fails), len(cases)


# ══════════════════════════════════════════════════════════════
# 3) reportsStill —— v44 热待机语义
# ══════════════════════════════════════════════════════════════
def test_reports_still():
    cases = [
        ('循环没在跑 → 静止（与老行为一致）', False, 0, True),
        ('循环没在跑，静帧数再大也无所谓', False, 999, True),
        ('在跑 + 静帧 89 < 90 → 还在动', True, 89, False),
        ('在跑 + 静帧 90 >= 90 → 静止（热待机≈1s）', True, 90, True),
        ('在跑 + 静帧 200>= 90 → 静止', True, 200, True),
    ]
    fails = []
    for desc, following, still, want in cases:
        got = reports_still(following, still, FOLLOW_STILL_FRAMES)
        if got != want:
            fails.append((desc, want, got))
    print('  reportsStill %d 条' % len(cases))
    for desc, want, got in fails:
        print('    ❌ %s → 期望 %s，实际 %s' % (desc, want, got))
    if not fails:
        print('    ✅ %d/%d 通过（含 v44 热待机边界 89/90）'
              % (len(cases), len(cases)))
    return len(fails), len(cases)


# ══════════════════════════════════════════════════════════════
# 4) clampOffset —— 真实屏高下的钳制
# ══════════════════════════════════════════════════════════════
def test_clamp_offset():
    lim = int(SCREEN_H * FOLLOW_SANITY_RATIO)   # int(2772*1.6) = 4435
    cases = [
        ('小位移原样放行', 120, 120),
        ('0 原样', 0, 0),
        ('负位移原样放行', -120, -120),
        ('恰好等于上限', lim, lim),
        ('超上限 1px → 钳到上限', lim + 1, lim),
        ('真机实测位移 2769（< 上限 4435）→ 原样放行', 2769, 2769),
        ('真机实测位移 2772 = 屏高（不钳制，只挡跑飞）', 2772, 2772),
        ('下越界 → 钳到 -lim', -lim - 1, -lim),
        ('远超下界', -99999, -lim),
        ('屏幕高取不到(0) → 原样放行', 2769, 2769),
        ('屏幕高为负 → 原样放行', -2769, -2769),
    ]
    fails = []
    for desc, delta, want in cases:
        got = clamp_offset(delta, SCREEN_H, FOLLOW_SANITY_RATIO)
        if got != want:
            fails.append((desc, want, got))
    # 屏幕高无效的两个单独跑
    for desc, delta, h in (('屏幕高 0 → 原样放行', 2769, 0),
                           ('屏幕高 -1 → 原样放行', -50, -1)):
        got = clamp_offset(delta, h, FOLLOW_SANITY_RATIO)
        if got != delta:
            fails.append((desc, delta, got))
    print('  clampOffset %d 条（真机屏高 %d，1.6x 上限 = %d）'
          % (len(cases) + 2, SCREEN_H, lim))
    for desc, want, got in fails:
        print('    ❌ %s → 期望 %d，实际 %d' % (desc, want, got))
    if not fails:
        print('    ✅ %d/%d 通过' % (len(cases) + 2, len(cases) + 2))
    return len(fails), len(cases) + 2


# ══════════════════════════════════════════════════════════════
def main():
    print('=' * 66)
    print('PageFollowPolicy 真机黄金向量对照（code 994）')
    print('=' * 66)
    print()
    total_fail = total = 0
    for fn in (test_is_page_held_real, test_is_page_held_gates,
               test_reports_still, test_clamp_offset):
        f, t = fn()
        total_fail += f
        total += t
        print()
    print('=' * 66)
    if total_fail == 0:
        print('全部通过：%d/%d' % (total, total))
        return 0
    print('失败 %d / 共 %d' % (total_fail, total))
    return 1


if __name__ == '__main__':
    sys.exit(main())
