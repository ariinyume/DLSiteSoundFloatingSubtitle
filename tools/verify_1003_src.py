#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1003_src.py —— code 1003 的**源码层**验证。

code 1003 做了四件事，本脚本逐条把它写成断言：
  ① 设置页新增「液态玻璃效果调整」独立卡片（摆在「其他」之前，总开关关闭时下方全隐藏）；
  ② 四个滑条统一 0–100 / 步长 5 / 默认 50（模糊强度由 50–100 放开回 0–100）；
  ③ **删除**「背景亮度自动采样」+ 整套宿主窗口亮度采样功能，光圈固定为冷白色；
  ④ 明暗度分段点随默认值由 40 移到 50，**默认 50 仍严格 = 0.42**（零回归）。

⚠️ 为什么必须有源码层：
  · 「两个键都在 dex 里」字节码层能验；「默认配置算出来的值 == 历史值」是**纯运行时行为**，
    公式里挪一个小数点就会让所有用户的面板亮度跑偏 —— 只有把公式抽出来独立复算才拦得住。
  · 「卡片位置 / 显隐覆盖 / 判据是否覆盖消费键」这类**结构不变量**，编译绿灯、dex 全在，
    但用户拖着滑条毫无反应（code 1000 就栽在这）。

用法：
    python tools/verify_1003_src.py          （在仓库根执行，不接参数）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/'
GLASS = os.path.join(ROOT, J, 'view/LiquidGlassDrawable.java')
FV = os.path.join(ROOT, J, 'view/FloatingSubtitleView.java')
SA = os.path.join(ROOT, J, 'ui/SettingsActivity.java')
ST = os.path.join(ROOT, J, 'view/SubtitleStyle.java')
CFG = os.path.join(ROOT, J, 'config/SubtitleConfig.java')
LAYOUT = os.path.join(ROOT, 'app/src/main/res/layout/activity_settings.xml')

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
    for p in (GLASS, FV, SA, ST, CFG, LAYOUT):
        if not os.path.exists(p):
            print('找不到文件：%s（请在仓库根执行）' % p)
            return 2
    gl, fv, sa, st, cfg, lay = (read(GLASS), read(FV), read(SA), read(ST),
                                read(CFG), read(LAYOUT))
    glc, sac, fvc = strip_comments(gl), strip_comments(sa), strip_comments(fv)

    # ══════════ ① 独立卡片 ══════════
    check('layout 有 card_liquidglass', 'card_liquidglass' in lay)
    check('layout 有 group_liquidglass', 'group_liquidglass' in lay)
    check('layout 有 title_liquidglass', 'title_liquidglass' in lay)
    i_lg, i_misc = lay.index('card_liquidglass'), lay.index('card_misc')
    check('★ 卡片排在「其他」(card_misc) 之前', i_lg < i_misc,
          'card_liquidglass@%d vs card_misc@%d' % (i_lg, i_misc))
    check('Java 侧注册了 mGroupLiquidGlass', 'mGroupLiquidGlass = findViewById' in sa)
    check('Java 侧注册了 mTitleLiquidGlass', 'mTitleLiquidGlass = findViewById' in sa)
    check('卡片标题走三语文案 GROUP_LIQUID_GLASS',
          'Strings.GROUP_LIQUID_GLASS' in sa and 'GROUP_LIQUID_GLASS' in read(
              os.path.join(ROOT, J, 'ui/Strings.java')))
    check('refreshCardTints 给新卡上色', 'card_liquidglass' in sa)
    check('setControlsEnabled 纳入新卡', 'setGroupEnabled(mGroupLiquidGlass' in sa)

    # ══════════ ② 卡片内部结构与顺序 ══════════
    body = strip_comments(method_body(sa, 'private void buildLiquidGlassGroup(LinearLayout parent)'))
    order = ['mRowLiquidGlass = addSwitchRow',
             'mRowBackdropLum = addSliderRow',
             'mRowTransparency = addSliderRow',
             'mRowPanelLum = addSliderRow',
             'mRowLiquidGlassBlur = addSliderRow',
             'addResetRow(parent, Strings.RESET_LIQUID_GLASS']
    pos = [body.find(x) for x in order]
    check('卡片内六项的构建顺序齐全', all(p >= 0 for p in pos), str(pos))
    check('★ 顺序 = 总开关 → 环境背景亮度 → 通透度 → 明暗度 → 模糊强度 → 恢复默认',
          all(pos[i] >= 0 and pos[i] < pos[i + 1] for i in range(len(pos) - 1)))
    check('恢复默认小字用 HINT_LIQUID_GLASS（全部设置为 50%）',
          'Strings.HINT_LIQUID_GLASS' in body)
    check('环境背景亮度有说明小字', 'LIQUID_GLASS_BACKDROP_LUM_HINT' in body)
    check('通透度有说明小字', 'LIQUID_GLASS_TRANSPARENCY_HINT' in body)

    # ══════════ ③ 总开关关闭 ⇒ 下方全隐藏 ══════════
    vis = strip_comments(method_body(sa, 'private void applyLiquidGlassBlurVisibility()'))
    check('显隐判据取 mDraft.liquidGlass', 'mDraft.liquidGlass' in vis)
    # 【code 1003 修】显隐改为「遍历整卡子视图」统一收口：从 index 1 起全部按开关显隐。
    # ⚠️ 反向锚：不得再出现逐个点名的写法 —— 它会漏掉 makeRowSpacer() 的 9dp 占位，
    #    导致「液态玻璃关闭时卡底凭空多出 45dp 空位」（Ari 2026-10-10 截图实证）。
    check('显隐走「遍历整卡子视图」而非逐个点名',
          'getChildCount()' in vis and 'getChildAt(i)' in vis
          and 'for (int i = 1; i < parent.getChildCount(); i++)' in vis)
    check('遍历起点为 1（第 0 个恒为总开关行，必须保持可见）',
          'int i = 1' in vis)
    check('遍历体内对每个子视图 setVisibility(vis)',
          vis.count('setVisibility(vis)') == 1 and 'child.setVisibility(vis)' in vis)
    check('无 mResetRowRoot.setVisibility 这类逐个点名残留',
          'mResetRowRoot.setVisibility' not in vis
          and 'mHintLiquidGlassReset.setVisibility' not in vis
          and 'mRowPanelLum.root.setVisibility' not in vis)
    check('取卡片容器用 mGroupLiquidGlass 且带 null 守卫',
          'mGroupLiquidGlass' in vis and 'if (parent == null)' in vis)
    check('总开关回调里调用 applyLiquidGlassBlurVisibility',
          'applyLiquidGlassBlurVisibility();' in strip_comments(
              method_body(sa, 'private void onLiquidGlassToggled(boolean enabled)')))

    # ══════════ ④ 四个滑条统一 0–100 / 5 / 默认 50 ══════════
    for key, val in (('LIQUID_GLASS_BLUR_PCT_MIN', 0), ('LIQUID_GLASS_BLUR_PCT_MAX', 100),
                     ('LIQUID_GLASS_BLUR_PCT_STEP', 5), ('LIQUID_GLASS_BLUR_PCT_DEF', 50),
                     ('LIQUID_GLASS_PANEL_LUM_MIN', 0), ('LIQUID_GLASS_PANEL_LUM_MAX', 100),
                     ('LIQUID_GLASS_PANEL_LUM_STEP', 5), ('LIQUID_GLASS_PANEL_LUM_DEF', 50),
                     ('LIQUID_GLASS_TRANSPARENCY_MIN', 0), ('LIQUID_GLASS_TRANSPARENCY_MAX', 100),
                     ('LIQUID_GLASS_TRANSPARENCY_STEP', 5), ('LIQUID_GLASS_TRANSPARENCY_DEF', 50),
                     ('LIQUID_GLASS_BACKDROP_LUM_MIN', 0), ('LIQUID_GLASS_BACKDROP_LUM_MAX', 100),
                     ('LIQUID_GLASS_BACKDROP_LUM_STEP', 5), ('LIQUID_GLASS_BACKDROP_LUM_DEF', 50)):
        m = re.search(r'%s\s*=\s*(\d+)' % key, cfg)
        check('常量 %s == %d' % (key, val), m is not None and int(m.group(1)) == val,
              m.group(1) if m else 'N/A')

    # ══════════ ⑤ 明暗度分段映射（分段点 50，默认 50 → 0.42）══════════
    m = re.search(
        r'panelTargetLum\s*=\s*lp\s*<=\s*(\d+)\s*\?\s*\(([\d.]+)f\s*\+\s*lp\s*\*\s*([\d.]+)f\)'
        r'\s*:\s*\(([\d.]+)f\s*\+\s*\(lp\s*-\s*(\d+)\)\s*\*\s*([\d.]+)f\)', glc)
    check('明暗映射可解析（分段式）', m is not None)
    if m:
        thr, base, k1, idx, thr2, k2 = (int(m.group(1)), float(m.group(2)), float(m.group(3)),
                                        float(m.group(4)), int(m.group(5)), float(m.group(6)))
        check('★ 分段阈值两处一致 == 50', thr == 50 and thr2 == 50, '%s / %s' % (thr, thr2))
        check('分段点连续（pct=50 两支同值）',
              abs((base + 50 * k1) - idx) < 1e-6, '左 %.6f vs 右 %.6f' % (base + 50 * k1, idx))
        check('★ pct=50 → 0.42（零回归）', abs(base + 50 * k1 - 0.42) < 1e-6)
        check('pct=0 → 0.20（暗端）', abs(base - 0.20) < 1e-6)
        check('pct=100 → 0.60（亮端）', abs(idx + 50 * k2 - 0.60) < 1e-6)

    # ══════════ ⑥ 自动采样「删干净」══════════
    check('util/HostBackdrop.java 已删除',
          not os.path.exists(os.path.join(ROOT, J, 'util/HostBackdrop.java')))
    check('util/BackdropBaseline.java 已删除',
          not os.path.exists(os.path.join(ROOT, J, 'util/BackdropBaseline.java')))
    for bad, files in (('liquid_glass_backdrop_auto', [CFG, SA, FV, ST]),
                       ('liquidGlassBackdropAuto', [CFG, SA, FV, ST]),
                       ('K_LIQUID_GLASS_BACKDROP_AUTO', [CFG]),
                       ('LIQUID_GLASS_BACKDROP_AUTO', [CFG]),
                       ('HostBackdrop', [FV, SA, GLASS]),
                       ('BackdropBaseline', [FV, SA, GLASS]),
                       ('syncBackdropCapture', [FV]),
                       ('setBackdropStats', [FV, SA, GLASS]),
                       ('tintByEdge', [GLASS]),
                       ('EDGE_TINT', [GLASS]),
                       ('clearBackdropStats', [GLASS]),
                       ('seedFromPersistedBaseline', [FV]),
                       ('backdropLumFor', [FV]),
                       ('mRowBackdropAuto', [SA])):
        hits = [os.path.basename(f) for f in files if bad in read(f)]
        check('「%s」已删净（源码层）' % bad, not hits, ('残留于 %s' % hits) if hits else '')

    # ══════════ ⑦ 光圈固定冷白 ══════════
    for key, val in (('P_RIM_TOP', '0xB0EFF4FF'), ('P_RIM_BOTTOM', '0x6BEFF4FF'),
                     ('P_RIM_INNER_TOP', '0x0CEFF4FF'), ('P_RIM_INNER_BOTTOM', '0x10EFF4FF')):
        m = re.search(r'%s\s*=\s*(0x[0-9A-Fa-f]+)' % key, gl)
        check('%s == %s（冷白）' % (key, val), m is not None and m.group(1).upper() == val.upper(),
              m.group(1) if m else 'N/A')
    check('rimTop() 直接返回 P_RIM_TOP（不再染色）', 'return P_RIM_TOP;' in glc)
    check('rimBottom() 直接返回 P_RIM_BOTTOM（不再染色）', 'return P_RIM_BOTTOM;' in glc)

    # ══════════ ⑧ 亮度输入：唯一来源 = 用户滑条 ══════════
    check('LiquidGlassDrawable 提供 setBackdropLum', 'public void setBackdropLum(' in gl)
    check('applyPanelBackground 用 setBackdropLum',
          'setBackdropLum(' in strip_comments(method_body(fv, 'private void applyPanelBackground()')))
    check('设置页预览用 setBackdropLum',
          'setBackdropLum(' in strip_comments(method_body(sa, 'private void applyPreviewPanel()'))
          if 'private void applyPreviewPanel()' in sa else 'setBackdropLum' in sac)
    check('FloatingSubtitleView 不再持有采样状态（无 smoothedLum/backdropTarget）',
          'smoothedLum' not in fvc and 'backdropTarget' not in fvc)

    # ══════════ ⑨ 可达性不变量（code 1001 的通用不变量，本轮继续守）══════════
    apply_body = strip_comments(method_body(fv, 'private void applyPanelBackground()'))
    consumed = set(re.findall(r'\bst\.(\w+)', apply_body))
    check('能从 applyPanelBackground 抽出消费键', len(consumed) >= 3, str(sorted(consumed)))
    rs_body = strip_comments(method_body(fv, 'private void refreshStyle()'))
    guard_block = rs_body[rs_body.index('if (old != null && (old.panelColorTop'):
                         rs_body.index('LogGate.debug')]
    guarded = set(re.findall(r'old\.(\w+)\s*!=\s*s\.\1', guard_block))
    missing = sorted(consumed - guarded)
    check('★ 不变量：applyPanelBackground 消费的每个键都被判据覆盖', not missing,
          ('缺 %s ⇒ 拖这些键会毫无反应' % missing) if missing else '覆盖 %d 键' % len(guarded))
    same_as = strip_comments(method_body(st, 'public boolean sameAs(SubtitleStyle o)'))
    for k in ('liquidGlassPanelLum', 'liquidGlassTransparency', 'liquidGlassBackdropLum'):
        check('SubtitleStyle.sameAs 比到 %s' % k, k in same_as)

    # ══════════ 报告 ══════════
    print()
    print('=' * 84)
    print('code 1003 —— 源码层验证（独立卡片 + 四滑条范围 + 删采样 + 冷白光圈）')
    print('=' * 84)
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
