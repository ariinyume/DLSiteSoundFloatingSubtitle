#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1000.py —— code 1000 的 dex 字节码层验证（**悬浮窗明暗改为用户可调**）。

本轮改动：新增两个配置项 + 绘制层参数化 + 设置页两个滑条。

判据分型（详见 tools/README.md）：
  · 正向 `add()`           —— 主验命中 / 对照未命中
  · 负向 `add_negative()`  —— 主验**不**命中 / 对照命中
  · 保持 `add_keep()`      —— 两版都命中
  · 值变化 `add_diff()`    —— 主验==新值 且 对照==旧值 且 新值≠旧值

⚠️ 本轮的核心风险是「**参数化之后默认值跑偏**」—— 那是纯运行时行为，
   字节码层只能验「东西编进去了」，所以额外用**源码层**核对默认值映射
   （tools/verify_1000_src.py）。本脚本负责前者。

用法：
    python verify_1000.py <apk_1000> <apk_999>
"""
import os
import re
import sys
import zipfile

C_GLASS = 'Lio/github/ariinyume/dlsitesoundfloat/view/LiquidGlassDrawable;'
C_BUTTON = 'Lio/github/ariinyume/dlsitesoundfloat/hook/ActivityButtonHook;'
C_CFG = 'Lio/github/ariinyume/dlsitesoundfloat/config/SubtitleConfig;'

# 本轮新增/改动的符号（主验 1000 有、对照 999 无）
NEW_SYMBOLS = [
    ('1★ 配置键 liquid_glass_panel_lum 已新增', 'liquid_glass_panel_lum'),
    ('2★ 配置键 liquid_glass_transparency 已新增', 'liquid_glass_transparency'),
    ('3★ 换算入口 setPanelTuningFromPct 已新增', 'setPanelTuningFromPct'),
    ('4★ 实例字段 panelTargetLum 已新增', 'panelTargetLum'),
    ('5★ 实例字段 panelVeilAlpha 已新增', 'panelVeilAlpha'),
    ('6★ 实例字段 panelBaseBlack 已新增', 'panelBaseBlack'),
    ('7★ 诊断用 transparency() 已新增', 'maxCompensableLum'),
]


def dex_blobs(apk):
    out = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            m = re.match(r'classes(\d*)\.dex$', n)
            if m:
                out.append((int(m.group(1) or 0), n, z.read(n)))
    out.sort(key=lambda x: x[0])
    return out


def all_dex_text(apk):
    return b'\n'.join(b for _, _, b in dex_blobs(apk))


class Checker:
    def __init__(self):
        self.rows = []
        self.fails = 0

    def add(self, label, hit_new, hit_old):
        if hit_new and not hit_old:
            v = 'OK 有区分力'
        elif hit_new and hit_old:
            v = '!! 无区分力(两版都成立)'
            self.fails += 1
        else:
            v = 'FAIL 未命中'
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_keep(self, label, ok_new, ok_old):
        if ok_new:
            v = 'OK 保持'
        else:
            v = 'FAIL 功能丢失'
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def report(self):
        print()
        print('=' * 80)
        print('code 1000 —— dex 验证（主验 1000 可调版 / 对照 999 写死版）')
        print('=' * 80)
        print('%-4s %-5s %s' % ('1000', '999', '判据'))
        print('-' * 80)
        for label, a, b, v in self.rows:
            print('%-4s %-5s %s' % ('Y' if a else '.',
                                    'Y' if b else ('.' if b is not None else '-'), label))
        print()
        anchors = sum(1 for r in self.rows if '有区分力' in r[3])
        print('  断言 %d 项，其中有区分力锚 %d 个' % (len(self.rows), anchors))
        if self.fails:
            print('FAIL %d/%d' % (self.fails, len(self.rows)))
            for label, a, b, v in self.rows:
                if v.startswith('!!') or v.startswith('FAIL'):
                    print('   ❌ %s  [%s]' % (label, v))
            return 1
        print('PASS %d/%d' % (len(self.rows), len(self.rows)))
        return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    apk_new, apk_old = sys.argv[1], sys.argv[2]
    print('主验 =', os.path.basename(apk_new))
    print('对照 =', os.path.basename(apk_old))

    tn, to = all_dex_text(apk_new), all_dex_text(apk_old)

    def has(txt, s):
        return s.encode() in txt

    ck = Checker()

    # ── A. 正向：本轮新增的键 / 方法 / 字段 ──
    for label, sym in NEW_SYMBOLS:
        ck.add(label, has(tn, sym), has(to, sym))

    # ── B. 正向：设置页滑条文案（三语各查中文）──
    ck.add('8★ 设置页文案「面板明暗」已加', has(tn, '面板明暗'), has(to, '面板明暗'))
    ck.add('9★ 设置页文案「通透度」已加', has(tn, '通透度'), has(to, '通透度'))

    # ── C. 保持型：原标定常量仍作为「默认值」存在（不能被删）──
    ck.add_keep('10 TARGET_LUM 仍作为默认值保留',
                has(tn, 'TARGET_LUM'), has(to, 'TARGET_LUM'))
    ck.add_keep('11 ADAPTIVE_VEIL_ALPHA 仍作为默认值保留',
                has(tn, 'ADAPTIVE_VEIL_ALPHA'), has(to, 'ADAPTIVE_VEIL_ALPHA'))
    ck.add_keep('12 ADAPTIVE_BASE_BLACK 仍作为默认值保留',
                has(tn, 'ADAPTIVE_BASE_BLACK'), has(to, 'ADAPTIVE_BASE_BLACK'))

    # ── D. 保持型：前几轮的修复与重构不能被误伤 ──
    ck.add_keep('13 sCtxWasMissing（999 竞态修复）仍在',
                has(tn, 'sCtxWasMissing'), has(to, 'sCtxWasMissing'))
    ck.add_keep('14 PokeThrottle（G7 重构）仍在',
                has(tn, 'PokeThrottle'), has(to, 'PokeThrottle'))
    ck.add_keep('15 ScopeWatcher（第 4 批重构）仍在',
                has(tn, 'ScopeWatcher'), has(to, 'ScopeWatcher'))
    ck.add_keep('16 liquid_glass_blur_pct（既有键）仍在',
                has(tn, 'liquid_glass_blur_pct'), has(to, 'liquid_glass_blur_pct'))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
