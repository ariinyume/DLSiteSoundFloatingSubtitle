#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1000_src.py —— 液态玻璃「用户调参」的**源码层**验证：默认值是否精确落回历史观感。

为什么必须有这个脚本：
  code 1000 把三个标定常量（TARGET_LUM / ADAPTIVE_VEIL_ALPHA / ADAPTIVE_BASE_BLACK）
  从 `static final` 改成**实例字段 + 换算函数**。字节码层只能验「东西编进去了」，
  而「**默认配置下换算出来的值 == 改动前的历史值**」是**纯运行时行为** ——
  公式里挪一个小数点（0.003 → 0.004）就会让所有用户的面板亮度整体跑偏，
  字节码层完全看不出来。

做法：从源码里**提取公式里的每个数字**，在 Python 里**独立复算**，
      再与历史标定值逐一对比。⇒ 任何人改公式，本脚本立刻拦住。

⚠️ code 1001 起：明暗映射由单段改为**分段**
      （【code 1003】分段点随默认值移到 50：pct ≤ 50 ⇒ 0.20 + pct×0.0044；
       pct > 50 ⇒ 0.42 + (pct−50)×0.0036），
      暗端因此由 0.30 加深到 0.20，而默认 50 处**仍严格 = 0.42**（零回归）。
      本脚本已同步复算「分段点连续」与「暗端降幅 ≥ 40%」两条。

⚠️ 本脚本曾被自己写坏过一次：`def lum_of()` 插在 `main()` 体内 ⇒ 把 main 从中间截断
   ⇒ **一行不打印却 exit=0**（`sys.exit(None)`）。⇒ 现在把 lum_of 提到 main 之前，
   并给一键入口加了「PASS 但零输出 = 可疑」的兜底网。

用法：
    python tools/verify_1000_src.py          （在仓库根执行，不接参数）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLASS = os.path.join(ROOT, 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/view/LiquidGlassDrawable.java')
CONFIG = os.path.join(ROOT, 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/config/SubtitleConfig.java')

fails = 0
rows = []

# ── 明暗映射：从源码抽出来的分段参数（main 里赋值，供 lum_of 复算）──
LUM = {'thr': None, 'base': None, 'k1': None, 'idx': None, 'thr2': None, 'k2': None}


def check(label, ok, detail=''):
    global fails
    rows.append((label, ok, detail))
    if not ok:
        fails += 1


def read(p):
    return io.open(p, encoding='utf-8', newline='').read()


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def method_body(src, sig):
    """按花括号配平抽一个方法的函数体（缩进正则不可靠，见铁律 46）。"""
    i = src.index(sig)
    j = src.index('{', i)
    depth = 0
    k = j
    while k < len(src):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    raise AssertionError('花括号未配平：%s' % sig)


def lum_of(pct):
    """复算「面板明暗 pct → TARGET_LUM」，口径照源码分段式。"""
    if LUM['thr'] is None:
        return None
    if pct <= LUM['thr']:
        return LUM['base'] + pct * LUM['k1']
    return LUM['idx'] + (pct - LUM['thr']) * LUM['k2']


def main():
    if not os.path.exists(GLASS) or not os.path.exists(CONFIG):
        print('找不到源码，请在仓库根执行')
        return 2
    g = read(GLASS)
    c = read(CONFIG)

    # ── 1. 提取历史标定常量（它们必须还在，且值不变）──
    m = re.search(r'private static final float TARGET_LUM\s*=\s*([\d.]+)f', g)
    check('TARGET_LUM 常量仍在且可解析', m is not None)
    hist_target = float(m.group(1)) if m else None

    m = re.search(r'private static final float ADAPTIVE_VEIL_ALPHA\s*=\s*([\d.]+)f', g)
    check('ADAPTIVE_VEIL_ALPHA 常量仍在且可解析', m is not None)
    hist_alpha = float(m.group(1)) if m else None

    m = re.search(r'private static final int ADAPTIVE_BASE_BLACK\s*=\s*0x([0-9A-Fa-f]+)', g)
    check('ADAPTIVE_BASE_BLACK 常量仍在且可解析', m is not None)
    hist_black_a = int(m.group(1), 16) >> 24 if m else None   # 0x14000000 → 0x14

    check('历史 TARGET_LUM == 0.42', hist_target == 0.42, str(hist_target))
    check('历史 ADAPTIVE_VEIL_ALPHA == 0.22', hist_alpha == 0.22, str(hist_alpha))
    check('历史 ADAPTIVE_BASE_BLACK 的 α == 0x14', hist_black_a == 0x14, hex(hist_black_a or 0))

    # ── 2. 提取换算公式里的每个数字 ──
    # 【code 1001】明暗映射是**分段线性**：pct≤40 ⇒ base+pct×k1；pct>40 ⇒ idx+(pct−40)×k2
    m = re.search(
        r'panelTargetLum\s*=\s*lp\s*<=\s*(\d+)\s*\?\s*\(([\d.]+)f\s*\+\s*lp\s*\*\s*([\d.]+)f\)'
        r'\s*:\s*\(([\d.]+)f\s*\+\s*\(lp\s*-\s*(\d+)\)\s*\*\s*([\d.]+)f\)', g)
    check('明暗映射式可解析（分段：pct≤T ⇒ base+pct×k1；pct>T ⇒ idx+(pct−T)×k2）', m is not None)
    if m:
        LUM['thr'] = int(m.group(1))
        LUM['base'] = float(m.group(2))
        LUM['k1'] = float(m.group(3))
        LUM['idx'] = float(m.group(4))
        LUM['thr2'] = int(m.group(5))
        LUM['k2'] = float(m.group(6))

    m = re.search(r'panelVeilAlpha\s*=\s*([\d.]+)f\s*\+\s*t\s*\*\s*\(ADAPTIVE_VEIL_ALPHA\s*-\s*([\d.]+)f\)', g)
    check('通透度「实」段的 α 插值式可解析', m is not None)
    solid_alpha = float(m.group(1)) if m else None    # 0.55

    m = re.search(r'blackAlpha\s*=\s*Math\.round\(0x([0-9A-Fa-f]+)\s*\+\s*t\s*\*\s*\(0x([0-9A-Fa-f]+)\s*-\s*0x([0-9A-Fa-f]+)\)', g)
    check('通透度「实」段的黑底插值式可解析', m is not None)
    solid_black, mid_black = (int(m.group(1), 16), int(m.group(2), 16)) if m else (None, None)

    m = re.search(r'panelVeilAlpha\s*=\s*ADAPTIVE_VEIL_ALPHA\s*\+\s*t\s*\*\s*\(([\d.]+)f\s*-\s*ADAPTIVE_VEIL_ALPHA\)', g)
    check('通透度「透」段的 α 插值式可解析', m is not None)
    clear_alpha = float(m.group(1)) if m else None      # 0.06

    # ── 3. 复算：默认配置（明暗 40 / 通透度 50）必须等于历史值 ──
    m = re.search(r'LIQUID_GLASS_PANEL_LUM_DEF\s*=\s*(\d+)', c)
    check('配置默认 明暗 DEF 可解析', m is not None)
    def_lum = int(m.group(1)) if m else None

    m = re.search(r'LIQUID_GLASS_TRANSPARENCY_DEF\s*=\s*(\d+)', c)
    check('配置默认 通透度 DEF 可解析', m is not None)
    def_tr = int(m.group(1)) if m else None

    check('明暗默认 == 50', def_lum == 50, str(def_lum))
    check('通透度默认 == 50', def_tr == 50, str(def_tr))

    if all(v is not None for v in (LUM['thr'], LUM['base'], LUM['k1'], LUM['idx'], LUM['thr2'], LUM['k2'])):
        check('分段阈值两处一致 == 50', LUM['thr'] == 50 and LUM['thr2'] == 50,
              '%s / %s' % (LUM['thr'], LUM['thr2']))
        check('分段点连续（pct=thr 两支同值，无跳变）',
              abs((LUM['base'] + LUM['thr'] * LUM['k1']) - (LUM['idx'] + 0 * LUM['k2'])) < 1e-6,
              '左支 %.6f vs 右支 %.6f' % (LUM['base'] + LUM['thr'] * LUM['k1'], LUM['idx']))
        got = lum_of(def_lum)
        check('★ 默认明暗 50 → TARGET_LUM == 历史 0.42',
              abs(got - hist_target) < 1e-6, '复算 %.6f vs 历史 %.6f' % (got, hist_target))

    if all(v is not None for v in (solid_alpha, hist_alpha, def_tr)):
        t = def_tr / 50.0                     # tp == 50 ⇒ t == 1.0 ⇒ 落在「实」段末
        got_a = solid_alpha + t * (hist_alpha - solid_alpha)
        check('★ 默认通透度 50 → 霜面 α == 历史 0.22',
              abs(got_a - hist_alpha) < 1e-6, '复算 %.6f vs 历史 %.6f' % (got_a, hist_alpha))
        if solid_black is not None:
            got_b = round(solid_black + t * (mid_black - solid_black))
            check('★ 默认通透度 50 → 薄黑底 α == 历史 0x14',
                  got_b == hist_black_a, '复算 0x%02X vs 历史 0x%02X' % (got_b, hist_black_a))

    # ── 4. 两端的边界值（写进注释里的承诺，不能被改飞）──
    if LUM['base'] is not None:
        check('明暗 0 → 0.20（最暗端，code 1001 加深）', abs(lum_of(0) - 0.20) < 1e-6,
              '复算 %.6f' % lum_of(0))
        check('明暗 100 → 0.60（最亮端）', abs(lum_of(100) - 0.60) < 1e-6,
              '复算 %.6f' % lum_of(100))
        if hist_target:
            drop = (hist_target - lum_of(0)) / hist_target
            check('暗端相对默认的降幅 >= 40%（与亮端量级相称）', drop >= 0.40,
                  '实际 %.1f%%' % (drop * 100))
    if solid_black is not None:
        check('通透度 0 → 薄黑底 0x60（最实端）', solid_black == 0x60)
    if clear_alpha is not None:
        check('通透度 100 → 霜面 α 0.06（最透端）', abs(clear_alpha - 0.06) < 1e-6)

    # ── 5. 接线完整：三个消费者都得调到 ──
    fv = read(os.path.join(ROOT, 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/view/FloatingSubtitleView.java'))
    sa = read(os.path.join(ROOT, 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/ui/SettingsActivity.java'))
    st = read(os.path.join(ROOT, 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/view/SubtitleStyle.java'))
    check('悬浮窗接线 setPanelTuningFromPct', 'setPanelTuningFromPct' in fv)
    check('设置页预览接线 setPanelTuningFromPct', 'setPanelTuningFromPct' in sa)
    check('SubtitleStyle 已承载两个新字段', 'liquidGlassPanelLum' in st and 'liquidGlassTransparency' in st)
    check('悬浮窗自愈判据含新两键',
          'liquidGlassPanelLum != cur.liquidGlassPanelLum' in fv
          and 'liquidGlassTransparency != cur.liquidGlassTransparency' in fv)
    check('设置页两个滑条已创建', 'mRowPanelLum' in sa and 'mRowTransparency' in sa)
    # 【code 1003 修】判据从「字面出现 mRowPanelLum.root.setVisibility」改为
    #「两滑条都建在液态玻璃卡内 + 共用同一个门控方法」。原来那句字面断言在
    # code 1003 把显隐改成「遍历整卡子视图」之后就失效了（不是功能坏了，
    # 是判据钉在了实现细节上）。铁律：判据要钉**意图**（同显隐），
    # 别钉写法；否则重构必假 FAIL。
    check('设置页两滑条同显隐（建卡内 + 共用门控）',
          'mRowPanelLum' in sa and 'mRowTransparency' in sa
          and 'mDraft.liquidGlass' in strip_comments(method_body(sa,
              'private void applyLiquidGlassBlurVisibility()'))
          and 'buildLiquidGlassGroup' in sa)

    # ── 报告 ──
    print()
    print('=' * 78)
    print('液态玻璃调参 —— 源码层验证（默认值映射 + 接线完整性）')
    print('=' * 78)
    for label, ok, detail in rows:
        print('  %s %s%s' % ('✓' if ok else '✗', label, ('   → ' + detail) if detail else ''))
    print()
    print('  断言 %d 项' % len(rows))
    if fails:
        print('FAIL %d/%d' % (fails, len(rows)))
        return 1
    print('PASS %d/%d' % (len(rows), len(rows)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
