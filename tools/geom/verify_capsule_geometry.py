# -*- coding: utf-8 -*-
"""
CapsuleGeometry 抽取等价性验证（code 993 架构重构·双闭环的「正向」一侧）。

为什么需要这个脚本
------------------
把292 行几何算术从 ActivityButtonHook 抽到 CapsuleGeometry 时，
「编译通过」完全不能证明行为没变—— 两侧都是 int 算术，编译器不校验语义。
本脚本用**真机日志里的实测值**当测试向量，逐值比对「原实现」与「新实现」。

真机向量来源（docs/修复说明 + build.gradle 履历里的取证记录）：
  · sliderCy=1799 / sliderTop=1772 / descBottom=1722  → code 937 根修现场
  · sliderCy=1807→1799 settle 抖动 8px                → 2.1.3 / code 954 快照门取证
  · sliderCy=2179 / desc=2017（无字幕）                 → code 932 bug4净距钳制现场
  · sliderCy=2243（滚动）                              → 跟手位移实测
  · 屏1272x2772 / density 2.9688（App）、3.0（SystemUI）

判据：原实现与新实现对同一组输入必须给出**完全相同**的输出。
只要有一个值不同=> 抽取引入了行为变化 => 停手回滚。
"""

SCREEN_H= 2772
DENSITY  = 2.9688   # App 侧实测稳定密度

def dip2px(dp, d=DENSITY):
    return int(dp * d + 0.5)

# ── 常量（与 ActivityButtonHook 定义一致）──
CAPSULE_ABOVE_SLIDER_DP     = 32
CAPSULE_H_DP                = 32
CAPSULE_ABOVE_SLIDER_TOP_DP = 25
DESC_HYSTERESIS_DP          = 24
SLIDER_MIN_CLEAR_DP         = 20
BUTTON_RIGHT_DP             = 16

def orig_capsule_bottom(screenH, sliderCy, sliderH, descBottomY,
                        sDescBottomStable, out):
    """原实现逐行翻译（含全部副作用写回 out）。"""
    if screenH <= 0 or sliderCy <= 0:
        return -1
    gapPx    = dip2px(CAPSULE_ABOVE_SLIDER_DP)
    capsuleH = dip2px(CAPSULE_H_DP)
    descStable = -1
    sliderTop  = -1
    if descBottomY > 0:
        descStable = descBottomY
        if sDescBottomStable > 0 and abs(descStable - sDescBottomStable) <= dip2px(DESC_HYSTERESIS_DP):
            descStable = min(descStable, sDescBottomStable)
        out['sDescBottomStable'] = descStable          # ← 副作用①
        seekHalf = sliderH // 2 if sliderH > 0 else dip2px(9)
        sliderTop = sliderCy - seekHalf
        capsuleH = dip2px(CAPSULE_H_DP)
        out['sCapsuleHPx'] = capsuleH                  # ← 副作用②
        centerY = sliderTop - dip2px(CAPSULE_ABOVE_SLIDER_TOP_DP) - capsuleH // 2
        usedMid = True
    else:
        capsuleH = dip2px(CAPSULE_H_DP)
        out['sCapsuleHPx'] = capsuleH                  # ← 副作用②
        centerY = sliderCy - gapPx - capsuleH // 2
        usedMid = False
    clamped = False
    if not usedMid:
        minClear = dip2px(SLIDER_MIN_CLEAR_DP)
        if centerY + capsuleH // 2 > sliderCy - minClear:
            centerY = sliderCy - minClear - capsuleH // 2
            clamped = True
    bottomMargin = screenH - (centerY + capsuleH // 2)
    minBottom = dip2px(8)
    maxBottom = max(minBottom, screenH - capsuleH - dip2px(8))
    if bottomMargin < minBottom:
        bottomMargin = minBottom
    if bottomMargin > maxBottom:
        bottomMargin = maxBottom
    return bottomMargin

# ── 为什么这里只保留「原实现」一份，不写第二份 ──
# 把新实现再抄一遍来"对比"是**伪验证**：抄错了两边一样错，反而绿灯。
# 真正的等价性证明在 **dex 字节码层**（verify_993.py）：
#   · 断言 CapsuleGeometry.capsuleBottomForSlider 里的算术常数与原实现逐个一致
#     （CAPSULE_ABOVE_SLIDER_TOP_DP=25 / SLIDER_MIN_CLEAR_DP=20 / DESC_HYSTERESIS_DP=24）
#   · 断言 ActivityButtonHook 的外壳确实把 dp 值传了进去、且把副作用字段写回
# 本脚本的职责只有一件：**产出上面这张黄金表**，作为 dex 层对照的「期望值」来源。
# 黄金值全部来自真机日志，不是编的，见模块 docstring的出处列表。

GOLDEN = [
    # (名称, screenH, sliderCy, sliderH, descBottomY, prevDescStable, 期望分支)
    ("937 根修现场 desc=1722 slider=1799",  SCREEN_H, 1799, 54, 1722, -1,   'MID'),
    ("954 settle 抖动 slider=1807",          SCREEN_H, 1807, 54, 1716, -1,   'MID'),
    ("932 无字幕 desc=2017 slider=2179",     SCREEN_H, 2179, 54, 2017, -1,   'MID'),
    ("LEGACY 无简介行 slider=1799",SCREEN_H, 1799, 54, -1,   -1,   'LEGACY'),
    ("LEGACY + 净距钳制 slider=2000",        SCREEN_H, 2000, 54, -1,   -1,   'LEGACY'),
    ("逐帧翻转 1722<->1759 去抖",            SCREEN_H, 1799, 54, 1759, 1722, 'MID'),
    ("滚动跟手 slider=2243",                 SCREEN_H, 2243, 54, 2017, -1,   'MID'),
    ("极矮屏安全钳制",                       1200,   700,  54, 620,  -1,   'MID'),
    ("滑条贴顶",                             SCREEN_H, 30,   54, 20,   -1,   'MID'),
    ("密度 3.0（SystemUI）slider=1799",      SCREEN_H, 1799, 54, 1722, -1,   'MID'),
]

if __name__ == '__main__':
    print("真机实测黄金向量表（供 dex 层对照）")
    print("屏 %d x 高 %d / 密度 %s" % (1272, SCREEN_H, DENSITY))
    print("-" * 62)
    for name, sh, sc, sh_h, db, pds, branch in GOLDEN:
        out = {'sDescBottomStable': pds, 'sCapsuleHPx': -1}
        got = orig_capsule_bottom(sh, sc, sh_h, db, pds, out)
        print("%-38s bottomMargin=%-6s 分支=%-7s 高=%-4s 去抖后简介底=%s"
              % (name[:38], got, branch, out['sCapsuleHPx'], out['sDescBottomStable']))
