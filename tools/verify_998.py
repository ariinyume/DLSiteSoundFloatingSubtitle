#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_998.py —— code 998 的 dex 字节码层验证（**液态玻璃样式回退到 996**）。

本轮改动只有一件事：把 `view/LiquidGlassDrawable.java` 恢复成 996 内容
（本质是撤销 997 对该文件的全部改动）。所以判据是 **997 那套的反向**：

  A. **样式已回退**（4 个有区分力锚）
     · `ADAPTIVE_BASE_BLACK`  0xBB → 0x14000000
     · `ADAPTIVE_VEIL_ALPHA`  0.44 → 0.22
     · `LOCKED_VEIL_LUM`      字段与字符串**双双绝迹**
     · `hasStats()` / `lum(int)` **已恢复**（997 删除过）

  B. **G7 第 5 批重构仍在**（保持型，证明「只回退了样式」）
     · `PokeThrottle` / `POKE_WINDOW_MS` / `ScopeWatcher` 均在
     · `pokeStructureChanged` 仍调 `PokeThrottle.decide`

判据分型（详见 tools/README.md）：
  · 正向 `add()`           —— 主验命中 / 对照未命中
  · 负向 `add_negative()`  —— 主验**不**命中 / 对照命中
  · 保持 `add_keep()`      —— 两版都命中
  · 值变化 `add_diff()`    —— 主验==新值 且 对照==旧值 且 新值≠旧值

⚠️ 数值改动**只有「dex 静态字段常量层」能验准**（javac 把 static final 内联进指令，
   字符串层查不到值）⇒ 必须读 dexdump 的 `value :` 行。

用法：
    python verify_998.py <apk_998> <apk_997>
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

C_GLASS = 'Lio/github/ariinyume/dlsitesoundfloat/view/LiquidGlassDrawable;'
C_BUTTON = 'Lio/github/ariinyume/dlsitesoundfloat/hook/ActivityButtonHook;'

# 期望的 dex 静态字段值（主验 998 回退后 / 对照 997 锁定版）
# ⚠️ 十进制是 dexdump 的输出口径，注释给出十六进制便于核对
EXPECT_I32 = {
    # name: (998 值, 997 值)
    'ADAPTIVE_BASE_BLACK': (335544320, -1157627904),   # 0x14000000 / 0xBB000000
}
EXPECT_F32 = {
    'ADAPTIVE_VEIL_ALPHA': ('0.22', '0.44'),
    'LOCKED_VEIL_LUM': (None, '0.72'),                 # 998 无此字段 ⇒ 负向锚
    'TARGET_LUM': ('0.42', '0.42'),                    # 两版一致 ⇒ 保持型
}


def dex_blobs(apk):
    """返回 [(序号, 名字, bytes)]，按 dex 序号排序。"""
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
    """把含 cls 的 dex 逐个 dexdump，返回拼接后的反汇编文本。"""
    os.makedirs(tmp, exist_ok=True)
    tag = os.path.basename(apk)
    bodies = []
    for idx, name, blob in dex_blobs(apk):
        if cls.encode() not in blob:
            continue          # 连引用都没有 ⇒ 不可能有定义，跳过（省 dexdump 时间）
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
    """从类切片里抽某个方法体（到下一个 `name :` 行为止）。找不到返回 None。"""
    if class_seg is None:
        return None
    m = re.search(r"name\s+:\s+'%s'" % re.escape(mname), class_seg)
    if not m:
        return None
    nxt = re.search(r"\n\s*name\s+:\s+'", class_seg[m.start() + 10:])
    return class_seg[m.start():m.start() + 10 + (nxt.start() if nxt else 6000)]


def static_field_value(class_seg, field):
    """从类切片里读 `value : xxx` 行。返回字符串，找不到返回 None。"""
    if class_seg is None:
        return None
    j = class_seg.find("'%s'" % field)
    if j < 0:
        return None
    vm = re.search(r'value\s*:\s*(\S+)', class_seg[j:j + 400])
    return vm.group(1) if vm else None


class Checker:
    """四型判据收集器（正向 / 负向 / 保持 / 值变化），详见 tools/README.md。"""

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
            v = '!! FAIL 未回退(主验仍命中)'
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
            v = 'FAIL 值不符(998=%s / 997=%s)' % (ok_new, ok_old)
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
        print('=' * 80)
        print('code 998 —— dex 验证（主验 998 回退版 / 对照 997 锁定版）')
        print('=' * 80)
        print('%-4s %-5s %s' % ('998', '997', '判据'))
        print('-' * 80)
        for label, a, b, v in self.rows:
            print('%-4s %-5s %s' % ('Y' if a else '.',
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
    dn = dump_candidates(apk_new, C_BUTTON, tmp) + dump_candidates(apk_new, C_GLASS, tmp)
    do = dump_candidates(apk_old, C_BUTTON, tmp) + dump_candidates(apk_old, C_GLASS, tmp)

    def has(txt, s):
        return s.encode() in txt

    ck = Checker()
    gn = slice_class(dn, C_GLASS)
    go = slice_class(do, C_GLASS)

    # ── A. 样式回退：dex 静态字段常量层（数值改动只有这一层能验准）──
    for name, (v_new, v_old) in EXPECT_I32.items():
        an = static_field_value(gn, name)
        ao = static_field_value(go, name)
        ck.add_diff('1★ %s：%s → %s（已回退）' % (name, v_old, v_new),
                    an == str(v_new), ao == str(v_old), v_new, v_old)
    for name, (v_new, v_old) in EXPECT_F32.items():
        an = static_field_value(gn, name)
        ao = static_field_value(go, name)
        if v_new is None:
            # 998 无此字段 ⇒ 负向锚（字段已随回退移除）
            ck.add_negative('3★ %s 已随回退移除（997 曾有 = %s）' % (name, v_old),
                            an is not None, ao == v_old)
        elif v_new == v_old:
            ck.add_keep('7  %s 保持 = %s' % (name, v_new), an == v_new, ao == v_old)
        else:
            ck.add_diff('2★ %s：%s → %s（已回退）' % (name, v_old, v_new),
                        an == v_new, ao == v_old, v_new, v_old)

    # 字符串层双保险：LOCKED_VEIL_LUM 这个**新哨兵名**已绝迹
    ck.add_negative('4★ LOCKED_VEIL_LUM 字符串层已绝迹',
                    has(tn, 'LOCKED_VEIL_LUM'), has(to, 'LOCKED_VEIL_LUM'))

    # 恢复型：997 删掉的两个成员又回来了
    ck.add('5★ hasStats() 方法已恢复（997 删除）',
           method_body(gn, 'hasStats') is not None,
           method_body(go, 'hasStats') is not None)
    ck.add('6★ lum(int) 方法已恢复（997 删除）',
           method_body(gn, 'lum') is not None,
           method_body(go, 'lum') is not None)

    # ── B. 保持型：G7 重构不能因样式回退而丢 ──
    bn = method_body(slice_class(dn, C_BUTTON), 'pokeStructureChanged')
    bo = method_body(slice_class(do, C_BUTTON), 'pokeStructureChanged')
    ck.add_keep('8  PokeThrottle 类仍在（G7 重构）',
                has(tn, 'PokeThrottle'), has(to, 'PokeThrottle'))
    ck.add_keep('9  POKE_WINDOW_MS 常量仍在',
                has(tn, 'POKE_WINDOW_MS'), has(to, 'POKE_WINDOW_MS'))
    ck.add_keep('10 pokeStructureChanged 仍调 PokeThrottle.decide',
                bool(bn) and 'PokeThrottle;.decide' in bn,
                bool(bo) and 'PokeThrottle;.decide' in bo)
    ck.add_keep('11 ScopeWatcher 类仍在（第 4 批重构）',
                has(tn, 'ScopeWatcher'), has(to, 'ScopeWatcher'))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
