#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1001.py —— code 1001 的 dex 字节码层验证（滑条可达性修复 + 明暗分段）。

验两项：
  A. **滑条可达性修复**：新增的原地重设路径 `retunePanelGlass` 是否真的编进包，
     并且**真的挂在 `refreshStyle` 的分支里**（这是本轮 bug 的核心 ——
     上一版缺的不是方法，而是"它会不会被调用"）。
  B. **明暗分段**：`setPanelTuningFromPct` 里的浮点常量由单段
     `{0.003, 0.3, 0.42}` 变为分段 `{0.0055, 0.2, 0.003, 0.42}`
     （暗端 0.30 → 0.20）。

判据分四型（见 tools/README.md）：正向 `add()` / 负向 `add_negative()` /
保持 `add_keep()` / 值变化 `add_diff()`。

⚠️ 本脚本用**两层**：
  · 字符串层（快，扫 dex 字节）
  · **方法体指令层**（dexdump -d 的 `invoke-*` / `const ... #float` 行）——
    「某方法里调了谁」「某方法里用了哪个浮点常量」**只有这一层**能验准。

用法：
    python tools/verify_1001.py <apk_1001> <apk_1000>
主验 = code 1001 包；对照 = code 1000 包（修复前）
"""
import os
import re
import subprocess
import sys
import zipfile

DEXDUMP = os.environ.get(
    'DEXDUMP',
    r'F:\WorkBuddyCache\DLSiteSound_Plus\toolchain\android-sdk\build-tools\34.0.0\dexdump.exe'
)

C_VIEW = 'Lio/github/ariinyume/dlsitesoundfloat/view/FloatingSubtitleView;'
C_GLASS = 'Lio/github/ariinyume/dlsitesoundfloat/view/LiquidGlassDrawable;'


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


def dump_candidates(apk, cls, tmp):
    """把**含该类名字符串**的 dex 逐个 dexdump 后拼接（定义类的那份自然在内）。"""
    os.makedirs(tmp, exist_ok=True)
    tag = os.path.basename(apk)
    bodies = []
    for idx, name, blob in dex_blobs(apk):
        if cls.encode() not in blob:
            continue
        p = os.path.join(tmp, '%s.%s' % (tag, name))
        with open(p, 'wb') as f:
            f.write(blob)
        dp = p + '.dump'
        subprocess.run([DEXDUMP, '-d', p],
                       stdout=open(dp, 'w', encoding='utf-8', errors='replace'),
                       check=False)
        bodies.append(open(dp, encoding='utf-8', errors='replace').read())
    return '\n'.join(bodies)


def slice_class(dump, cls):
    """抽类切片。⚠️ dexdump 的 descriptor **带单引号**（996 首版栽过）。"""
    for pat in ("Class descriptor  : '%s'" % cls, 'Class descriptor  : %s' % cls):
        if pat in dump:
            i = dump.index(pat)
            m = re.search(r'\n\s*Class #\d+\s', dump[i + len(pat):])
            j = i + len(pat) + (m.start() if m else len(dump))
            return dump[i:j]
    return None


def method_body(class_seg, mname):
    """抽某方法体（到下一个 `name :` 行为止）。"""
    if class_seg is None:
        return None
    m = re.search(r"name\s+:\s+'%s'" % re.escape(mname), class_seg)
    if not m:
        return None
    nxt = re.search(r"\n\s*name\s+:\s+'", class_seg[m.start() + 10:])
    return class_seg[m.start():m.start() + 10 + (nxt.start() if nxt else 6000)]


def floats_in(body):
    """抽方法体里出现的 `#float x` 常量（指令层口径）。"""
    return set(re.findall(r'#float ([\d.eE+-]+)', body or ''))


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

    def add_negative(self, label, hit_new, hit_old):
        if (not hit_new) and hit_old:
            v = 'OK 有区分力(已绝迹)'
        elif hit_new and hit_old:
            v = '!! FAIL 未改(主验仍命中)'
            self.fails += 1
        else:
            v = '!! 无效(对照也没有,基准选错)'
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_diff(self, label, ok_new, ok_old, new_val, old_val):
        changed = str(new_val) != str(old_val)
        if ok_new and ok_old and changed:
            v = 'OK 有区分力(值已变)'
        elif ok_new and ok_old and not changed:
            v = '!! 无效(新旧期望值相同)'
            self.fails += 1
        else:
            v = 'FAIL 值不符'
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def add_keep(self, label, ok_new, ok_old):
        if ok_new:
            v = 'OK 保持'
        else:
            v = 'FAIL 功能丢失'
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def report(self):
        print()
        print('=' * 82)
        print('code 1001 —— dex 验证（主验 1001 / 对照 1000）')
        print('=' * 82)
        print('%-5s %-5s %s' % ('1001', '1000', '判据'))
        print('-' * 82)
        for label, a, b, v in self.rows:
            print('%-5s %-5s %s' % ('Y' if a else '.',
                                    'Y' if b else ('.' if b is not None else '-'),
                                    label))
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
    tmp = os.environ.get('TMP', '.')
    print('主验 =', os.path.basename(apk_new))
    print('对照 =', os.path.basename(apk_old))

    tn, to = all_dex_text(apk_new), all_dex_text(apk_old)
    dn = dump_candidates(apk_new, C_VIEW, tmp) + dump_candidates(apk_new, C_GLASS, tmp)
    do = dump_candidates(apk_old, C_VIEW, tmp) + dump_candidates(apk_old, C_GLASS, tmp)

    def has(txt, s):
        return s.encode() in txt

    ck = Checker()

    # ══════ A. 滑条可达性：新方法 + 它真的被调用 ══════
    ck.add('1★ retunePanelGlass 方法已编进包',
           has(tn, 'retunePanelGlass'), has(to, 'retunePanelGlass'))

    rn = method_body(slice_class(dn, C_VIEW), 'refreshStyle')
    ro = method_body(slice_class(do, C_VIEW), 'refreshStyle')
    ck.add('2★ refreshStyle 里真的**调用**了 retunePanelGlass（本 bug 的核心）',
           bool(rn) and 'retunePanelGlass' in rn,
           bool(ro) and 'retunePanelGlass' in ro)
    ck.add_keep('3  refreshStyle 仍调用 applyPanelBackground（重建路径不能丢）',
                bool(rn) and 'applyPanelBackground' in rn,
                bool(ro) and 'applyPanelBackground' in ro)

    gz = method_body(slice_class(dn, C_VIEW), 'retunePanelGlass')
    ck.add_keep('4  retunePanelGlass 内真的调 LiquidGlassDrawable.setPanelTuningFromPct',
                bool(gz) and 'LiquidGlassDrawable;.setPanelTuningFromPct' in gz, True)
    ck.add_keep('5  retunePanelGlass 有 instance-of LiquidGlassDrawable 守卫',
                bool(gz) and 'instance-of' in gz and 'LiquidGlassDrawable;' in gz, True)

    # ══════ B. 明暗分段：方法体浮点常量层 ══════
    pt_n = method_body(slice_class(dn, C_GLASS), 'setPanelTuningFromPct')
    pt_o = method_body(slice_class(do, C_GLASS), 'setPanelTuningFromPct')
    fn, fo = floats_in(pt_n), floats_in(pt_o)
    ck.add_keep('6  setPanelTuningFromPct 方法体仍可抽到', bool(pt_n), bool(pt_o))

    ck.add('7★ 暗端斜率 0.0055 已新增（#float 指令层）',
           '0.0055' in fn, '0.0055' in fo)
    ck.add('8★ 暗端基准 0.2 已新增（#float 指令层）', '0.2' in fn, '0.2' in fo)
    ck.add_negative('9★ 旧单段基准 0.3 已绝迹', '0.3' in fn, '0.3' in fo)
    ck.add_keep('10 亮端斜率 0.003 保持', '0.003' in fn, '0.003' in fo)
    ck.add_keep('11 默认目标 0.42 保持（零回归）', '0.42' in fn, '0.42' in fo)
    ck.add_keep('12 通透度段参数保持（0.55 / 0.22 / -0.16 / -0.33）',
                all(x in fn for x in ('0.55', '0.22', '-0.16', '-0.33')),
                all(x in fo for x in ('0.55', '0.22', '-0.16', '-0.33')))

    # ══════ C. 保持型：不许把前几轮的东西弄丢 ══════
    ck.add_keep('13 setPanelTuningFromPct 仍在 LiquidGlassDrawable',
                has(tn, 'setPanelTuningFromPct'), has(to, 'setPanelTuningFromPct'))
    ck.add_keep('14 两个配置键名仍在', has(tn, 'liquid_glass_panel_lum')
                and has(tn, 'liquid_glass_transparency'),
                has(to, 'liquid_glass_panel_lum') and has(to, 'liquid_glass_transparency'))
    ck.add_keep('15 PokeThrottle 仍在', has(tn, 'PokeThrottle'), has(to, 'PokeThrottle'))
    ck.add_keep('16 ScopeWatcher 仍在', has(tn, 'ScopeWatcher'), has(to, 'ScopeWatcher'))
    ck.add_keep('17 sCtxWasMissing 仍在（启动竞态修复）',
                has(tn, 'sCtxWasMissing'), has(to, 'sCtxWasMissing'))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
