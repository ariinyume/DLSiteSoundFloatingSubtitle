#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1003.py —— code 1003 的 **dex 字节码层**验证。

验四件事：
  A. **新增物**是否真编进包（正向锚，1003 有 / 1002 无）：
     卡片标题与四个滑条的三语文案、四个新方法名、新字段。
  B. **删除物**是否真删干净（反向锚，1002 有 / 1003 无）：
     自动采样开关与键、采样链路的方法与类。⚠️ 基准 = 引入过它们的 **1002 包**。
  C. **常量层**（`dexdump -d` 的字段定义 `value :`）：
     四个滑条的默认值/范围、四个光圈色常量 —— 数值改动只有这一层验得准（javac 会内联）。
  D. **方法体层**：
     卡片构建/显隐方法体内引用到新字段；`applyPanelBackground` 体内**只**调 `setBackdropLum`
     而**不再**调 `setBackdropStats`。

用法：
    python tools/verify_1003.py <apk_1003> <apk_1002>
主验 = code 1003 包；对照 = code 1002 包
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DEXDUMP = os.environ.get(
    'DEXDUMP',
    r'F:\WorkBuddyCache\DLSiteSound_Plus\toolchain\android-sdk\build-tools\34.0.0\dexdump.exe'
)

C_CFG = 'Lio/github/ariinyume/dlsitesoundfloat/config/SubtitleConfig;'
C_GLASS = 'Lio/github/ariinyume/dlsitesoundfloat/view/LiquidGlassDrawable;'
C_FV = 'Lio/github/ariinyume/dlsitesoundfloat/view/FloatingSubtitleView;'
C_SA = 'Lio/github/ariinyume/dlsitesoundfloat/ui/SettingsActivity;'


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


def dump_all(apk, tmp):
    os.makedirs(tmp, exist_ok=True)
    tag = os.path.basename(apk)
    parts = []
    for _, name, blob in dex_blobs(apk):
        p = os.path.join(tmp, '%s.%s' % (tag, name))
        with open(p, 'wb') as f:
            f.write(blob)
        dp = p + '.dump'
        subprocess.run([DEXDUMP, '-d', p],
                       stdout=open(dp, 'w', encoding='utf-8', errors='replace'),
                       check=False)
        parts.append(open(dp, encoding='utf-8', errors='replace').read())
    return '\n'.join(parts)


def slice_class(dump, cls):
    for pat in ("Class descriptor  : '%s'" % cls, 'Class descriptor  : %s' % cls):
        if pat in dump:
            i = dump.index(pat)
            m = re.search(r'\n\s*Class #\d+\s', dump[i + len(pat):])
            j = i + len(pat) + (m.start() if m else len(dump))
            return dump[i:j]
    return None


def method_body(class_seg, mname):
    if class_seg is None:
        return None
    m = re.search(r"name\s+:\s+'%s'" % re.escape(mname), class_seg)
    if not m:
        return None
    nxt = re.search(r"\n\s*name\s+:\s+'", class_seg[m.start() + 10:])
    return class_seg[m.start():m.start() + 10 + (nxt.start() if nxt else 8000)]


def field_value(class_seg, fname):
    """从类的 field 段里取 `value :`（静态常量的**数值真源**）。"""
    if class_seg is None:
        return None
    m = re.search(r"name\s+:\s+'%s'\s*\n\s*type\s+:\s*'[^']*'\s*\n\s*access\s+:\s*[^\n]*\n"
                  r"\s*value\s+:\s*(-?\d+)" % re.escape(fname), class_seg)
    return int(m.group(1)) if m else None


def i32(h):
    v = int(h, 16)
    return v - (1 << 32) if v >= (1 << 31) else v


class Checker:
    def __init__(self):
        self.rows = []
        self.fails = 0

    def _verdict(self, mode, ok):
        if ok:
            return 'OK 有区分力'
        self.fails += 1
        return {'new': 'FAIL 未命中（新包没有）',
                'neg': 'FAIL 未删净（新包仍存在）',
                'keep': 'FAIL 功能丢失（新包没有）'}[mode]

    def add(self, label, hit_new, hit_old):
        v = 'OK 有区分力' if (hit_new and not hit_old) else (
            '!! 无区分力(两版都成立)' if hit_new else 'FAIL 未命中（新包没有）')
        if v != 'OK 有区分力':
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_neg(self, label, hit_new, hit_old):
        """负向锚：旧包有、新包必须没有。基准必须是引入过它的那一版。"""
        v = 'OK 有区分力(已删净)' if (hit_old and not hit_new) else (
            '!! 无区分力(旧包也没有)' if not hit_old else 'FAIL 未删净（新包仍存在）')
        if v == 'FAIL 未删净（新包仍存在）' or v.startswith('!!'):
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_keep(self, label, ok_new, ok_old):
        v = 'OK 保持' if ok_new else 'FAIL 功能丢失（新包没有）'
        if not ok_new:
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def add_const(self, label, exp, new_v, old_v):
        """常量层：新包必须 == exp；旧包 != exp 才算有区分力。"""
        ok = (new_v == exp)
        if not ok:
            v = 'FAIL 值不对（新包 %s，期望 %s）' % (new_v, exp)
            self.fails += 1
        elif old_v == exp:
            v = '!! 无区分力(旧包同值)'
            self.fails += 1
        else:
            v = 'OK 有区分力(%s → %s)' % (old_v, new_v)
        self.rows.append((label, new_v, old_v, v))

    def add_const_keep(self, label, exp, new_v, old_v):
        ok = (new_v == exp)
        v = 'OK 保持(%s)' % new_v if ok else 'FAIL 值不对（新包 %s，期望 %s）' % (new_v, exp)
        if not ok:
            self.fails += 1
        self.rows.append((label, new_v, old_v, v))

    def report(self):
        print()
        print('=' * 92)
        print('code 1003 —— dex 验证（主验 1003 / 对照 1002）')
        print('=' * 92)
        print('%-8s %-8s %s' % ('1003', '1002', '判据'))
        print('-' * 92)
        for label, a, b, v in self.rows:
            fa = 'Y' if a is True else (a if isinstance(a, int) else '.' if a is False else '-')
            fb = 'Y' if b is True else (b if isinstance(b, int) else '.' if b is False else '-')
            print('%-8s %-8s %s' % (fa, fb, label))
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
    tmp = os.environ.get('TMP', tempfile.gettempdir())
    print('主验 =', os.path.basename(apk_new))
    print('对照 =', os.path.basename(apk_old))

    tn, to = all_dex_text(apk_new), all_dex_text(apk_old)
    dn, do = dump_all(apk_new, tmp), dump_all(apk_old, tmp)

    def has(txt, s):
        return s.encode() if isinstance(s, str) else s

    def in_new(s):
        return has(tn, s) in tn

    def in_old(s):
        return has(to, s) in to

    ck = Checker()

    # ══════ A. 新增物（正向锚）══════
    for label, sym in (
        ('1★ 卡片标题三语文案 液态玻璃效果调整', '液态玻璃效果调整'),
        ('2★ 滑条文案 环境背景亮度', '环境背景亮度'),
        ('3★ 滑条文案 明暗度', '明暗度'),
        ('4★ 重置按钮 恢复液态玻璃默认设置', '恢复液态玻璃默认设置'),
        ('5★ 重置小字 全部设置为 50%', '全部设置为 50%'),
        ('6★ 方法 buildLiquidGlassGroup', 'buildLiquidGlassGroup'),
        ('7★ 方法 makeHintView', 'makeHintView'),
        ('8★ 方法 resetLiquidGlassGroup', 'resetLiquidGlassGroup'),
        ('9★ 字段 mResetRowRoot', 'mResetRowRoot'),
        ('10★ 方法 setBackdropLum', 'setBackdropLum'),
    ):
        ck.add(label, in_new(sym), in_old(sym))

    # ══════ B. 删除物（反向锚，基准 = 1002）══════
    for label, sym in (
        ('11★ 开关文案 背景亮度自动采样', '背景亮度自动采样'),
        ('12★ 配置键 liquid_glass_backdrop_auto', 'liquid_glass_backdrop_auto'),
        ('13★ 常量 LIQUID_GLASS_BACKDROP_AUTO', 'LIQUID_GLASS_BACKDROP_AUTO'),
        ('14★ 字段 liquidGlassBackdropAuto', 'liquidGlassBackdropAuto'),
        ('15★ 设置页字段 mRowBackdropAuto', 'mRowBackdropAuto'),
        ('16★ 方法 backdropLumFor', 'backdropLumFor'),
        ('17★ 类 HostBackdrop', 'HostBackdrop'),
        ('18★ 类 BackdropBaseline', 'BackdropBaseline'),
        ('19★ 方法 syncBackdropCapture', 'syncBackdropCapture'),
        ('20★ 方法 setBackdropStats', 'setBackdropStats'),
        ('21★ 方法 seedFromPersistedBaseline', 'seedFromPersistedBaseline'),
        ('22★ 方法 tintByEdge（背景染色）', 'tintByEdge'),
        ('23★ 常量 EDGE_TINT', 'EDGE_TINT'),
        ('24★ 旧文案 仅「自动采样」关闭时生效', '仅「自动采样」关闭时生效'),
    ):
        ck.add_neg(label, in_new(sym), in_old(sym))

    # ══════ C. 常量层（javac 内联 ⇒ 只有这一层验得准）══════
    cfg_n, cfg_o = slice_class(dn, C_CFG), slice_class(do, C_CFG)
    gl_n, gl_o = slice_class(dn, C_GLASS), slice_class(do, C_GLASS)
    for label, f, exp in (
        ('25★ LIQUID_GLASS_BACKDROP_LUM_DEF', 'LIQUID_GLASS_BACKDROP_LUM_DEF', 50),
        ('27★ LIQUID_GLASS_PANEL_LUM_DEF', 'LIQUID_GLASS_PANEL_LUM_DEF', 50),
        ('28★ LIQUID_GLASS_BLUR_PCT_DEF', 'LIQUID_GLASS_BLUR_PCT_DEF', 50),
        ('29★ LIQUID_GLASS_BLUR_PCT_MIN', 'LIQUID_GLASS_BLUR_PCT_MIN', 0),
    ):
        ck.add_const(label, exp, field_value(cfg_n, f), field_value(cfg_o, f))
    for label, f, exp in (
        ('26 LIQUID_GLASS_TRANSPARENCY_DEF（默认不变，保持 50）',
         'LIQUID_GLASS_TRANSPARENCY_DEF', 50),
        ('30 LIQUID_GLASS_BLUR_PCT_MAX（保持 100）', 'LIQUID_GLASS_BLUR_PCT_MAX', 100),
        ('31 LIQUID_GLASS_PANEL_LUM_MAX（保持 100）', 'LIQUID_GLASS_PANEL_LUM_MAX', 100),
        ('32 LIQUID_GLASS_BACKDROP_LUM_STEP（保持 5）', 'LIQUID_GLASS_BACKDROP_LUM_STEP', 5),
    ):
        ck.add_const_keep(label, exp, field_value(cfg_n, f), field_value(cfg_o, f))
    for label, f, hexv in (
        ('33★ P_RIM_TOP（冷白）', 'P_RIM_TOP', '0xB0EFF4FF'),
        ('34★ P_RIM_BOTTOM（冷白）', 'P_RIM_BOTTOM', '0x6BEFF4FF'),
        ('35★ P_RIM_INNER_TOP（冷白）', 'P_RIM_INNER_TOP', '0x0CEFF4FF'),
        ('36★ P_RIM_INNER_BOTTOM（冷白）', 'P_RIM_INNER_BOTTOM', '0x10EFF4FF'),
    ):
        ck.add_const(label, i32(hexv), field_value(gl_n, f), field_value(gl_o, f))

    # ══════ D. 方法体层 ══════
    sa_n, fv_n, fv_o = slice_class(dn, C_SA), slice_class(dn, C_FV), slice_class(do, C_FV)
    bg = method_body(sa_n, 'buildLiquidGlassGroup')
    ck.add_keep('37 buildLiquidGlassGroup 体内建了四个滑条行',
                bool(bg) and all(x in bg for x in ('mRowBackdropLum', 'mRowTransparency',
                                                   'mRowPanelLum', 'mRowLiquidGlassBlur')), None)
    ck.add_keep('38 体内调用 addResetRow + HINT_LIQUID_GLASS',
                bool(bg) and 'addResetRow' in bg and 'HINT_LIQUID_GLASS' in bg, None)
    vis = method_body(sa_n, 'applyLiquidGlassBlurVisibility')
    ck.add_keep('39 applyLiquidGlassBlurVisibility 走遍历式收口（含 getChildAt）',
                bool(vis) and 'getChildAt' in vis and 'getChildCount' in vis
                and 'mDraft' in vis and 'liquidGlass' in vis, None)

    ap_n, ap_o = method_body(fv_n, 'applyPanelBackground'), method_body(fv_o, 'applyPanelBackground')
    ck.add('40★ applyPanelBackground 体内改调 setBackdropLum',
           bool(ap_n) and 'LiquidGlassDrawable;.setBackdropLum' in ap_n,
           bool(ap_o) and 'LiquidGlassDrawable;.setBackdropLum' in ap_o)
    ck.add_neg('41★ applyPanelBackground 体内不再调 setBackdropStats',
               bool(ap_n) and 'setBackdropStats' in ap_n,
               bool(ap_o) and 'setBackdropStats' in ap_o)

    # ══════ E. 保持型：不许把前几轮弄丢 ══════
    for label, sym in (
        ('42 setPanelTuningFromPct 仍在', 'setPanelTuningFromPct'),
        ('43 liquid_glass_panel_lum 键仍在', 'liquid_glass_panel_lum'),
        ('44 liquid_glass_transparency 键仍在', 'liquid_glass_transparency'),
        ('45 liquid_glass_backdrop_lum 键仍在', 'liquid_glass_backdrop_lum'),
        ('46 liquid_glass_blur_pct 键仍在', 'liquid_glass_blur_pct'),
        ('47 PokeThrottle 仍在', 'PokeThrottle'),
        ('48 ScopeWatcher 仍在', 'ScopeWatcher'),
        ('49 sCtxWasMissing 仍在（启动竞态修复）', 'sCtxWasMissing'),
        ('50 BackdropBlur 仍在（合成器模糊没丢）', 'BackdropBlur'),
    ):
        ck.add_keep(label, in_new(sym), in_old(sym))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
