#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1001_src.py —— code 1001 的**源码层**验证：调参链路可达性（不变量）。

【为什么必须有这一层】
code 1000 交付后 Ari 报「加了两个滑条，拖着看不出变化」。
根因不是公式错、也不是键没接上，而是 **`refreshStyle()` 里那个决定
「要不要重设面板背景」的判据漏了这两个键** —— 于是：

    样式对象更新了 ✓   style refreshed 日志照打 ✓   画面一动不动 ✗

字节码层能验出「类里有 retunePanelGlass 这个方法」，但**验不出「它会不会被调用」**；
编译也全绿（漏的是运行时判据，不是语法）。⇒ 只有把这条不变量写成断言才拦得住。

【本脚本验的不变量（通用形式，不只针对这两个键）】
    `applyPanelBackground()` **消费**的每一个配置键，
    都必须出现在 `refreshStyle()` 的「重建判据」或「重设判据」里。
    否则：用户拖那个键 ⇒ 判据全 false ⇒ 什么都不发生。

顺带验：
  · `retunePanelGlass` 存在、且真的把参数喂给玻璃（调 setPanelTuningFromPct）
  · 预览侧（SettingsActivity）同样调用
  · `SubtitleStyle.sameAs` 比到这两个字段（否则 refreshStyle 根本不会进）
  · 明暗映射的分段形态与两端值（与 verify_1000_src 互补：那里验默认值，这里验形态）

用法：
    python tools/verify_1001_src.py          （在仓库根执行，不接参数）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/'
FV = os.path.join(ROOT, J, 'view/FloatingSubtitleView.java')
SA = os.path.join(ROOT, J, 'ui/SettingsActivity.java')
ST = os.path.join(ROOT, J, 'view/SubtitleStyle.java')
GLASS = os.path.join(ROOT, J, 'view/LiquidGlassDrawable.java')

rows = []
fails = 0


def check(label, ok, detail=''):
    global fails
    rows.append((label, ok, detail))
    if not ok:
        fails += 1


def read(p):
    return io.open(p, encoding='utf-8', newline='').read()


def strip_comments(src):
    """去掉 `//` 行注释与 `/* */` 块注释。

    ⚠️ **必须先剥注释再抽符号**：code 1002 实测踩到 —— 给这一层写了句说明文字
    「这里刻意写成 `st.字段` 而不是调 helper」，结果正则把注释里的 `st.字段`
    当成"applyPanelBackground 消费的键" ⇒ **假 FAIL**（报缺 xxx / 字段 两个键）。
    ⇒ 凡是「从源码里抽符号/数字」的判据，都要先剥注释，否则文档写得越细越容易自伤。
    """
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


def main():
    for p in (FV, SA, ST, GLASS):
        if not os.path.exists(p):
            print('找不到源码，请在仓库根执行')
            return 2
    fv, sa, st, gl = read(FV), read(SA), read(ST), read(GLASS)

    # ══════════ 1. 核心不变量：消费 ⊆ 判据（模拟"只改这一个键"）══════════
    apply_body = strip_comments(method_body(fv, 'private void applyPanelBackground()'))
    consumed = set(re.findall(r'\bst\.(\w+)', apply_body))
    check('能从 applyPanelBackground 抽出它消费的配置键', len(consumed) >= 3,
          '抽到 %s' % sorted(consumed))

    rs_body = strip_comments(method_body(fv, 'private void refreshStyle()'))
    guard_block = rs_body[rs_body.index('if (old != null && (old.panelColorTop'):
                         rs_body.index('LogGate.debug')]
    guarded = set(re.findall(r'old\.(\w+)\s*!=\s*s\.\1', guard_block))
    check('能从重建/重设判据抽出被覆盖的键', len(guarded) >= 5, '抽到 %s' % sorted(guarded))

    missing = sorted(consumed - guarded)
    check('★ 不变量：applyPanelBackground 消费的每个键都被判据覆盖',
          not missing, ('缺 %s ⇒ 拖这些键会毫无反应' % missing) if missing else
          '覆盖 %d 个键' % len(guarded))

    # 逐键模拟：只改这一个键时，是否至少有一条分支会触发"重设/重建"
    sim_bad = []
    for k in sorted(consumed):
        if k not in guarded:
            sim_bad.append(k)
    check('★ 逐键模拟：%d 个键单独变化都能触发重设/重建' % len(consumed),
          not sim_bad, ('这些键单独变 = 无动作：%s' % sim_bad) if sim_bad else
          '${%s} 全覆盖' % ', '.join(sorted(consumed)))

    # 两个新键必须落在 **重设分支**（不必重建，但要重设）
    check('两个新键出现在判据里',
          'old.liquidGlassPanelLum != s.liquidGlassPanelLum' in guard_block
          and 'old.liquidGlassTransparency != s.liquidGlassTransparency' in guard_block)
    check('新键走的是 else-if 原地重设分支（不重建 Drawable）',
          'else if (old != null && (old.liquidGlassPanelLum' in guard_block)
    check('重设分支调用 retunePanelGlass',
          'retunePanelGlass(s);' in guard_block)

    # ══════════ 2. retunePanelGlass 真的把参数喂到玻璃 ══════════
    rt = strip_comments(method_body(fv, 'private void retunePanelGlass(SubtitleStyle s)'))
    check('retunePanelGlass 调 setPanelTuningFromPct', 'setPanelTuningFromPct(' in rt)
    check('retunePanelGlass 只对液态玻璃档生效（非玻璃档不吃这两参数）',
          's.liquidGlass' in rt and 'instanceof LiquidGlassDrawable' in rt)
    check('retunePanelGlass 不重建背景（不许出现 setBackground）', 'setBackground' not in rt)
    check('applyPanelBackground 仍调用 setPanelTuningFromPct（新建路径不能漏）',
          'setPanelTuningFromPct(' in apply_body)

    # ══════════ 3. 上游链路：样式变化必须真能进 refreshStyle ══════════
    same_as = strip_comments(method_body(st, 'public boolean sameAs(SubtitleStyle o)'))
    check('SubtitleStyle.sameAs 比到 面板明暗',
          'liquidGlassPanelLum' in same_as)
    check('SubtitleStyle.sameAs 比到 通透度',
          'liquidGlassTransparency' in same_as)
    check('自愈判据（refreshStyleIfStale）含两个新键',
          'RemoteConfig.get().liquidGlassPanelLum != cur.liquidGlassPanelLum' in fv
          and 'RemoteConfig.get().liquidGlassTransparency != cur.liquidGlassTransparency' in fv)

    # ══════════ 4. 预览侧同源 ══════════
    check('设置页预览调用 setPanelTuningFromPct', 'setPanelTuningFromPct(' in sa)

    # ══════════ 5. 明暗映射形态（分段 + 两端）══════════
    m = re.search(r'panelTargetLum\s*=\s*lp\s*<=\s*(\d+)\s*\?\s*\(([\d.]+)f\s*\+\s*lp\s*\*\s*([\d.]+)f\)'
                  r'\s*:\s*\(([\d.]+)f\s*\+\s*\(lp\s*-\s*(\d+)\)\s*\*\s*([\d.]+)f\)',
                  strip_comments(gl))
    check('明暗映射已改成分段形态', m is not None)
    if m:
        thr, base, k1, idx, thr2, k2 = (int(m.group(1)), float(m.group(2)), float(m.group(3)),
                                        float(m.group(4)), int(m.group(5)), float(m.group(6)))
        check('两处阈值一致（50）', thr == 50 and thr2 == 50, '%s / %s' % (thr, thr2))
        check('分段点连续（pct=50 无跳变）',
              abs((base + thr * k1) - idx) < 1e-6,
              '左 %.6f vs 右 %.6f' % (base + thr * k1, idx))
        check('pct=50 → 0.42（零回归）', abs(base + thr * k1 - 0.42) < 1e-6)
        check('pct=0 → 0.20（暗端，code 1001 加深）', abs(base - 0.20) < 1e-6)
        check('pct=100 → 0.60（亮端不变）', abs(idx + (100 - thr) * k2 - 0.60) < 1e-6)

    # ══════════ 报告 ══════════
    print()
    print('=' * 78)
    print('code 1001 —— 源码层验证（调参链路可达性 + 明暗分段形态）')
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
