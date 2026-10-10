#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1005.py —— code 1005 的 **dex 字节码层**验证。

code 1005 修的是：**换作品 / 换章节时同样会短暂「无字幕」**（Ari 2026-10-10 21:42 截屏）。
真机时间线（LSPosed_20261010_214410）：
    21:41:53.139  currentIndex=0 trackCount=0 duration=0.0 idle   ← 旧列表已销毁
    21:41:53.505  新轨 idx=2 的字幕 JSON 到达（仅隔 366ms）
    21:41:58.241  currentIndex=2 trackCount=8 duration=0.0        ← 新列表首次可读
    21:41:59.654  >>> track changed 6→8 [index-gate] | lastJson=6135ms ago -> SUSPEND + 973 隔离

验五件事：
  A. **新增物**是否真编进包（正向锚，1005 有 / 1004 无）。
  B. **常量层**（`dexdump -d` 字段定义的 `value :`）—— 新增 15000；既有七个时限常量
     一个都不许被动过（这是本项目的**数值真源**，字符串层查不到值）。
  C. **调用链**（方法体里的指令序列 + **指令偏移**）：
     · 数据层四枚印章的写入顺序；
     · 认领查询真的把三字段喂给纯判定层，且内联常量就是 15000；
     · 采集入口真的按 trackCount 分了两路（`if-gtz` + `sget/if-lez`）；
     · 去抖闸门真的问了三处 `ownsCuesFor*`；
     · **推送点仍在权威门之前**（按指令偏移，不是字符串位置）。
  D. **负向锚**：1004 包里这些新物必须**都不存在**（证明区分力）。
  E. **保持型**：980 / 973 / 960 与 code 1004 那几轮的机器一件都不许丢。

⚠️ 写本脚本时踩过的三个坑（都当场假 FAIL 过，别再犯）：
   ① dex 字符串池里存的是 Java 字符串的**内容**，不含源码里的引号 ——
      判据要写 `[code 1005]` 而不是 `"[code 1005]"`；
   ② dexdump 的指令行里寄存器号会随编译变化，判据只钉**助记符 + 字段/方法名**，
      不要钉 `v3`/`v9` 这种寄存器号；
   ③ 「两个符号之间 ≤N 字符」在 dexdump 上必假 FAIL（指令行长度不定）⇒ 一律用
      `method_body` 切片 + `insn_offset` 比偏移。

用法：
    python tools/verify_1005.py <apk_1005> <apk_1004>
主验 = code 1005 包；对照 = code 1004 包（唯一有区分力的基准）
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

C_KEY = 'Lio/github/ariinyume/dlsitesoundfloat/data/PlaylistKey;'
C_REPO = 'Lio/github/ariinyume/dlsitesoundfloat/data/SubtitleRepository;'
C_SRC = 'Lio/github/ariinyume/dlsitesoundfloat/hook/PlayerSourceHook;'
C_POS = 'Lio/github/ariinyume/dlsitesoundfloat/hook/PlayerPositionHook;'


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


def field_value(class_seg, fname):
    """从类的 field 段里取 `value :`（静态常量的**数值真源**）。"""
    if class_seg is None:
        return None
    m = re.search(r"name\s+:\s+'%s'\s*\n\s*type\s+:\s*'[^']*'\s*\n\s*access\s+:\s*[^\n]*\n"
                  r"\s*value\s+:\s*(-?\d+)" % re.escape(fname), class_seg)
    return int(m.group(1)) if m else None


def methods_of(class_seg):
    """把类里所有方法的 name -> [type...] 抽出来（同名重载会拿到多个签名）。"""
    out = {}
    if not class_seg:
        return out
    for m in re.finditer(r"name\s+:\s+'([^']+)'\s*\n\s*type\s+:\s*'([^']*)'", class_seg):
        out.setdefault(m.group(1), []).append(m.group(2))
    return out


def method_body(class_seg, name):
    """按 dexdump 的方法头切出**单个方法**的完整代码块（切到下一个方法头/类尾）。"""
    if not class_seg:
        return None
    m = re.search(r"name\s+:\s+'%s'\s*\n" % re.escape(name), class_seg)
    if not m:
        return None
    nxt = re.search(r'\n\s+#\d+\s+: \(in ', class_seg[m.end():])
    return class_seg[m.start():m.end() + (nxt.start() if nxt else len(class_seg))]


def insn_offset(class_seg, needle):
    """取某个调用点在 dex 里的**指令偏移**（指令行 `|00fe:` 里的十六进制值）；-1 = 没找到。"""
    if not class_seg:
        return -1
    i = class_seg.find(needle)
    if i < 0:
        return -1
    line = class_seg[class_seg.rfind('\n', 0, i) + 1:class_seg.find('\n', i)]
    m = re.search(r'\|([0-9a-fA-F]{4}):', line)
    return int(m.group(1), 16) if m else -1


class Checker:
    def __init__(self):
        self.rows = []
        self.fails = 0

    def add(self, label, hit_new, hit_old):
        v = 'OK 有区分力' if (hit_new and not hit_old) else (
            '!! 无区分力(两版都成立)' if hit_new else 'FAIL 未命中（新包没有）')
        if v != 'OK 有区分力':
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_keep(self, label, ok_new, ok_old=None):
        v = 'OK 保持' if ok_new else 'FAIL 功能丢失（新包没有）'
        if not ok_new:
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def add_diff(self, label, ok_new, ok_old):
        """方法体类断言专用：要求「新包成立 **且** 对照包不成立」才算有区分力。

        ⚠️ 用 add_manual 写这类断言会漏掉「假绿」——表达式在旧包上也成立时，
        add_manual 照样给 OK，读者无从判断它到底有没有区分力。"""
        if ok_new and not ok_old:
            v = 'OK 有区分力'
        elif ok_new and ok_old:
            v = '!! 无区分力(两版都成立)'
        else:
            v = 'FAIL 未命中（新包没有）'
        if v != 'OK 有区分力':
            self.fails += 1
        self.rows.append((label, ok_new, ok_old, v))

    def add_const(self, label, exp, new_v, old_v):
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

    def add_manual(self, label, ok, detail=''):
        v = 'OK' if ok else 'FAIL ' + detail
        if not ok:
            self.fails += 1
        self.rows.append((label, None, None, v))

    def report(self):
        print()
        print('=' * 96)
        print('code 1005 —— dex 验证（主验 1005 / 对照 1004）')
        print('=' * 96)
        for label, a, b, v in self.rows:
            fa = 'Y' if a is True else (a if isinstance(a, int) else '.' if a is False else '-')
            fb = 'Y' if b is True else (b if isinstance(b, int) else '.' if b is False else '-')
            print('%-7s %-7s %s' % (fa, fb, label))
        print()
        anchors = sum(1 for r in self.rows if '有区分力' in r[3])
        print('  断言 %d 项，其中有区分力锚 %d 个' % (len(self.rows), anchors))
        if self.fails:
            print('FAIL %d/%d' % (self.fails, len(self.rows)))
            for label, a, b, v in self.rows:
                if v.startswith('!!') or v.startswith('FAIL'):
                    print('   x %s  [%s]' % (label, v))
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

    def new(s):
        return s.encode() in tn

    def old(s):
        return s.encode() in to

    kn, ko = slice_class(dn, C_KEY), slice_class(do, C_KEY)
    rn, ro = slice_class(dn, C_REPO), slice_class(do, C_REPO)
    sn, so = slice_class(dn, C_SRC), slice_class(do, C_SRC)
    pn, po = slice_class(dn, C_POS), slice_class(do, C_POS)
    ck = Checker()

    # ══════════════ A. 新增物（正向锚：1005 有 / 1004 无）══════════════
    ck.add('1★ PlaylistKey.cuesBelongToRebuiltPlaylist:(ZJJJJJ)Z（六参纯判定）',
           'cuesBelongToRebuiltPlaylist:(ZJJJJJ)Z' in (kn or ''),
           'cuesBelongToRebuiltPlaylist' in (ko or ''))
    ck.add('2★ SubtitleRepository.ownsCuesForRebuiltPlaylist:(JJ)Z',
           'ownsCuesForRebuiltPlaylist:(JJ)Z' in (rn or ''),
           'ownsCuesForRebuiltPlaylist' in (ro or ''))
    ck.add('3★ SubtitleRepository.notePlaylistRebuilt:(Ljava/lang/String;)V',
           'notePlaylistRebuilt:(Ljava/lang/String;)V' in (rn or ''),
           'notePlaylistRebuilt' in (ro or ''))
    ck.add('4★ 字段 SubtitleRepository.playlistRebuiltAtMs:J（窗口起点）',
           '.playlistRebuiltAtMs:J' in (rn or ''),
           '.playlistRebuiltAtMs:J' in (ro or ''))
    ck.add('5★ 字段 SubtitleRepository.cuesArrivedDuringRebuild:Z（窗口内到达旗标）',
           '.cuesArrivedDuringRebuild:Z' in (rn or ''),
           '.cuesArrivedDuringRebuild:Z' in (ro or ''))
    ck.add('6★ 字段 SubtitleRepository.cuesLoadedAtMs:J（到达时刻）',
           '.cuesLoadedAtMs:J' in (rn or ''),
           '.cuesLoadedAtMs:J' in (ro or ''))
    ck.add('7★ 字段 PlayerSourceHook.sLastNonZeroTrackCount:I（非零→0 跳变判据）',
           '.sLastNonZeroTrackCount:I' in (sn or ''),
           '.sLastNonZeroTrackCount:I' in (so or ''))
    ck.add('8★ 常量字段 REBUILT_PLAYLIST_CLAIM_MS（认领时限）',
           'REBUILT_PLAYLIST_CLAIM_MS' in (rn or ''),
           'REBUILT_PLAYLIST_CLAIM_MS' in (ro or ''))
    ck.add('9★ 里程碑日志串 [code 1005]',
           new('[code 1005]'), old('[code 1005]'))
    ck.add('10★ 开窗日志串 rebuild window OPEN',
           new('rebuild window OPEN'), old('rebuild window OPEN'))
    ck.add('11★ 开窗日志串 playlist destroyed (trackCount=0)',
           new('playlist destroyed (trackCount=0)'), old('playlist destroyed (trackCount=0)'))
    ck.add('12★ 认领日志串 so it belongs to the NEW playlist',
           new('so it belongs to the NEW playlist'), old('so it belongs to the NEW playlist'))
    # 本轮按 Ari 指令 versionName 由 2.3.0 升到 **2.3.1**（code 仍是 1005）
    ck.add('13★ 版本横幅 ==== BUILD 2.3.1 / code 1005',
           new('==== BUILD 2.3.1 / code 1005'), old('==== BUILD 2.3.1 / code 1005'))

    # ══════════════ B. 常量层（dexdump 字段 value = 数值真源）══════════════
    ck.add_const('14★ REBUILT_PLAYLIST_CLAIM_MS = 15000（真机早到 6135ms，留足余量）',
                 15000, field_value(rn, 'REBUILT_PLAYLIST_CLAIM_MS'),
                 field_value(ro, 'REBUILT_PLAYLIST_CLAIM_MS'))
    for lbl, owner, name, exp in (
        ('16★', 'rn', 'PRELOAD_TOLERANCE_MS', 3500),
        ('17★', 'rn', 'NO_SUBTITLE_GRACE_MS', 3000),
        ('18★', 'rn', 'NO_SUBTITLE_GRACE_MS_CACHED', 15000),
        ('19★', 'rn', 'NO_SUBTITLE_EARLY_CLOSE_MS', 5000),
        ('20★', 'kn', 'SELECTION_TC_SCALE', 100000),
        ('21★', 'kn', 'IDENTITY_TC_SCALE', 1000000000000),
        ('22★', 'kn', 'IDENTITY_IDX_SCALE', 1000),
    ):
        seg = {'rn': rn, 'kn': kn}[owner]
        seg_o = {'rn': ro, 'kn': ko}[owner]
        ck.add_const_keep('%s 既有常量 %s 一字未动' % (lbl, name), exp,
                          field_value(seg, name), field_value(seg_o, name))

    # ══════════════ C. 调用链（方法体内的指令；每条都带对照包读数）══════════════
    # 每条的形态：(标签, 类, 方法名, 判据 fn)。fn 同时喂给新包与对照包的方法体，
    # 只有「新包成立 且 1004 不成立」才算有区分力（add_diff）。
    SEGS = {'kn': (kn, ko), 'rn': (rn, ro), 'sn': (sn, so)}

    def bd(owner, name, side):
        return method_body(SEGS[owner][side], name) or ''

    probes = [
        ('15★ 内联到认领查询里的那个 15000 也在（防常量折叠后被优化掉）',
         'rn', 'ownsCuesForRebuiltPlaylist', lambda s: '#int 15000' in s),
        ('23★ 认领查询把三字段喂给纯判定层：旗标 → 盖章身份 → 到达时刻（顺序即源码顺序）',
         'rn', 'ownsCuesForRebuiltPlaylist', lambda s: (
             s.find('cuesArrivedDuringRebuild:Z') >= 0
             and s.find('cuesOwnerIdentity:J') > s.find('cuesArrivedDuringRebuild:Z') >= 0
             and s.find('cuesLoadedAtMs:J') > s.find('cuesOwnerIdentity:J'))),
        ('24★ 认领查询真的调用纯判定层并直接返回（invoke-static/range → move-result → return）',
         'rn', 'ownsCuesForRebuiltPlaylist', lambda s: (
             'cuesBelongToRebuiltPlaylist:(ZJJJJJ)Z' in s
             and 'move-result' in s and 'return' in s)),
        ('25★ 开窗只开一次：读窗口起点 → 与 0 比较 → 非 0 就直接 return-void',
         'rn', 'notePlaylistRebuilt', lambda s: (
             'playlistRebuiltAtMs:J' in s
             and re.search(r'cmp-long[\s\S]*?if-eqz[\s\S]*?return-void', s) is not None)),
        ('26★ 开窗时刻来自 SystemClock.uptimeMillis()（与全仓同一时钟源）',
         'rn', 'notePlaylistRebuilt',
         lambda s: 'Landroid/os/SystemClock;.uptimeMillis:()J' in s),
        ('27★ 开窗只写一个字段（零状态副作用）',
         'rn', 'notePlaylistRebuilt',
         lambda s: len(re.findall(r'\biput(?:-wide|-boolean|-object)?\b', s)) == 1),
        ('28★ 采集入口按 trackCount 分流：if-gtz（≤0）先问 sLastNonZeroTrackCount 再开窗',
         'sn', 'onPlaylistSelectionFromStatusMap', lambda s: (
             'if-gtz' in s and s.find('sLastNonZeroTrackCount:I') < s.find('notePlaylistRebuilt'))),
        ('29★ 分流两支都不缺：≤0 → notePlaylistRebuilt；>0 → makeSelection 后推选择键',
         'sn', 'onPlaylistSelectionFromStatusMap', lambda s: (
             'notePlaylistRebuilt:(Ljava/lang/String;)V' in s
             and 'PlaylistKey;.makeSelection:(II)J' in s
             and 'noteObservedPlaylistSelection:(J)V' in s)),
        ('30★ 非零分支把 trackCount 记进 sLastNonZeroTrackCount（供下次「非零→0」判别）',
         'sn', 'onPlaylistSelectionFromStatusMap',
         lambda s: re.search(r'\bsput\b[^\n]*sLastNonZeroTrackCount:I', s) is not None),
        ('31★ 认领路径升到三条：ownsCuesForSelection 与 ownsCuesForRebuiltPlaylist 都在 tryResumeFromCache 里',
         'rn', 'tryResumeFromCache', lambda s: (
             'ownsCuesForSelection:(J)Z' in s and 'ownsCuesForRebuiltPlaylist:(JJ)Z' in s)),
        ('32★ 三条路径的日志串齐备（[code 1005] 是新加的那条）',
         'rn', 'tryResumeFromCache',
         lambda s: '[code 1005]' in s and '[code 1004]' in s),
        ('33★ 认领是一次性的：方法体内出现把「窗口内到达」旗标落下的写指令',
         'rn', 'tryResumeFromCache',
         lambda s: re.search(r'\biput-boolean\b[^\n]*cuesArrivedDuringRebuild:Z', s) is not None),
        ('34★ 装载盖章四连且顺序正确：选择键 → 是否跑在前面 → 到达时刻 → 是否窗口内到达',
         'rn', 'loadFromJsonArrayInternal', lambda s: (
             -1 < s.find('cuesOwnerSelection:J') < s.find('cuesLoadedAheadOfIdentity:Z')
             < s.find('cuesLoadedAtMs:J') < s.find('cuesArrivedDuringRebuild:Z'))),
        ('35★ settleChange 的静默例外问满三处（顺序即源码 || 链）',
         'sn', 'settleChange', lambda s: (
             s.find('ownsCuesFor:(J)Z') >= 0
             and s.find('ownsCuesForSelection:(J)Z') > s.find('ownsCuesFor:(J)Z')
             and s.find('ownsCuesForRebuiltPlaylist:(JJ)Z') > s.find('ownsCuesForSelection:(J)Z'))),
    ]
    for label, owner, meth, fn in probes:
        ck.add_diff(label, fn(bd(owner, meth, 0)), fn(bd(owner, meth, 1)))

    # 36★：这条是**保持型**而不是新增 —— 「推送早于权威门调用」在 1004 里就已经成立，
    #      本轮改了采集条件（去掉 trackCount>0）**不能**把这个顺序弄反。
    a_new = insn_offset(pn, 'PlayerSourceHook;.onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V')
    a_gate = insn_offset(pn, 'PlayerSourceHook;.onPlaylistStateFromStatusMap:(IIDLjava/lang/String;)Z')
    a_new_o = insn_offset(po, 'PlayerSourceHook;.onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V')
    a_gate_o = insn_offset(po, 'PlayerSourceHook;.onPlaylistStateFromStatusMap:(IIDLjava/lang/String;)Z')
    ck.add_keep('36★ 保持型：推送点仍在权威门之前（按**指令偏移**：新包 |%04x: < |%04x:，'
                '对照 |%04x: < |%04x:）'
                % (max(a_new, 0), max(a_gate, 0), max(a_new_o, 0), max(a_gate_o, 0)),
                a_new >= 0 and a_gate >= 0 and a_new < a_gate,
                a_new_o >= 0 and a_gate_o >= 0 and a_new_o < a_gate_o)

    # ══════════════ D. 保持型（老机器一件都不许丢）══════════════
    for lbl, owner, sym in (
        ('37★', 'kn', 'makeSelection:(II)J'),
        ('38★', 'kn', 'selectionAheadOfIdentity:(JJ)Z'),
        ('39★', 'kn', 'cuesBelongToTarget:(ZJJ)Z'),
        ('40★', 'rn', 'ownsCuesFor:(J)Z'),
        ('41★', 'rn', 'ownsCuesForSelection:(J)Z'),
        ('42★', 'rn', 'noteObservedPlaylistSelection:(J)V'),
        ('43★', 'rn', 'tryResumeFromCache:(Ljava/lang/String;JJ)Z'),
        ('44★', 'sn', 'settleChange:(JJJJ)Z'),
        ('45★', 'sn', 'notifyTrackChanged'),
    ):
        seg = {'kn': kn, 'rn': rn, 'sn': sn}[owner] or ''
        ck.add_keep('%s 保持型：%s 仍在' % (lbl, sym), sym in seg)
    ck.add_keep('46★ 保持型：980 的里程碑日志 [code 980] 仍在', new('[code 980]'))
    ck.add_keep('47★ 保持型：973 的隔离日志串仍在', new('previous-track cues quarantined'))
    ck.add_keep('48★ 保持型：code 1004 的认领日志串仍在', new('cues were already the NEW track'))
    ck.add_keep('49★ 保持型：code 1004 的履历段仍在（追加式，不覆盖）',
                new('==== BUILD 2.3.0 / code 1004 （'))
    ck.add_keep('50★ 保持型：PlayerPositionHook 仍在（改了它的采集条件，别把它整个弄丢）',
                po is not None and pn is not None)

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
