#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1004.py —— code 1004 的 **dex 字节码层**验证。

code 1004 修的是：**点击未缓存音频，App 显示有字幕，但插件显示「无字幕」**。

验五件事：
  A. **新增物**是否真编进包（正向锚，1004 有 / 1003 无）：
     新类 PlaylistKey 及其全部方法、数据层三个新字段与三个新方法、
     PlayerSourceHook 的新入口、四条新日志串、版本横幅。
  B. **签名升级**是否真落地（新签名在 / 旧签名不在）：
     tryResumeFromCache 由 (String,long) 升为 (String,long,long)；
     settleChange 由 (long,long,long) 升为 (long,long,long,long)；
     notifyTrackChanged 新增 (...,ZJJ) 版（旧的 2 参转发壳必须还在）。
  C. **常量层**（`dexdump -d` 字段定义的 `value :`）：
     PlaylistKey 三个打包常量；以及前几轮的时限常量**一个都不许被动过**
     （PRELOAD_TOLERANCE_MS=3500 / NO_SUBTITLE_GRACE_MS=3000 /
       NO_SUBTITLE_GRACE_MS_CACHED=15000 / NO_SUBTITLE_EARLY_CLOSE_MS=5000）。
  D. **调用链**：PlayerPositionHook 真的调了新入口，且调用点在权威门**之前**；
     数据层真的把选择键盖章写进 cuesOwnerSelection / cuesLoadedAheadOfIdentity。
  E. **保持型**：980 / 973 / 975 / 960 那几轮的机器一件都不许丢。

用法：
    python tools/verify_1004.py <apk_1004> <apk_1003>
主验 = code 1004 包；对照 = code 1003 包（唯一有区分力的基准）
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
    """按 dexdump 的方法头切出**单个方法**的完整代码块。

    切到下一个方法头（`#N : (in L...;)`）为止；若是类内最后一个方法则切到类尾。
    ⚠️ 不要用「两个符号之间 ≤N 字符」这种脆断言：dexdump 的指令行长度不定，
       N 稍小就假 FAIL、稍大就跨方法命中。切片才是稳的。
    """
    if not class_seg:
        return None
    m = re.search(r"name\s+:\s+'%s'\s*\n" % re.escape(name), class_seg)
    if not m:
        return None
    nxt = re.search(r'\n\s+#\d+\s+: \(in ', class_seg[m.end():])
    return class_seg[m.start():m.end() + (nxt.start() if nxt else len(class_seg))]


def insn_offset(class_seg, needle):
    """取某个调用点在 dex 里的**指令偏移**（指令行 `|00fe:` 里的那个十六进制值）。

    返回 -1 表示没找到。与「字符位置」相比，指令偏移才是「谁先执行」的真权威：
    同一方法内字符串位置会被注释长短干扰，指令偏移不会。
    """
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

    def add_neg(self, label, hit_new, hit_old):
        v = 'OK 有区分力(已删净)' if (hit_old and not hit_new) else (
            '!! 无区分力(旧包也没有)' if not hit_old else 'FAIL 未删净（新包仍存在）')
        if v != 'OK 有区分力(已删净)':
            self.fails += 1
        self.rows.append((label, hit_new, hit_old, v))

    def add_keep(self, label, ok_new, ok_old):
        v = 'OK 保持' if ok_new else 'FAIL 功能丢失（新包没有）'
        if not ok_new:
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
        print('=' * 92)
        print('code 1004 —— dex 验证（主验 1004 / 对照 1003）')
        print('=' * 92)
        for label, a, b, v in self.rows:
            fa = 'Y' if a is True else (a if isinstance(a, int) else '.' if a is False else '-')
            fb = 'Y' if b is True else (b if isinstance(b, int) else '.' if b is False else '-')
            print('%-6s %-6s %s' % (fa, fb, label))
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

    def in_new(s):
        return s.encode() in tn

    def in_old(s):
        return s.encode() in to

    ck = Checker()

    # ══════ A. 新增物（正向锚）══════
    for label, sym in (
        ('1★ 新类 PlaylistKey', 'data/PlaylistKey;'),
        ('2★ 方法 makeSelection', 'makeSelection'),
        ('3★ 方法 selectionAheadOfIdentity', 'selectionAheadOfIdentity'),
        ('4★ 方法 cuesBelongToTarget', 'cuesBelongToTarget'),
        ('5★ 方法 ownsCuesForSelection', 'ownsCuesForSelection'),
        ('6★ 方法 noteObservedPlaylistSelection', 'noteObservedPlaylistSelection'),
        ('7★ 方法 onPlaylistSelectionFromStatusMap', 'onPlaylistSelectionFromStatusMap'),
        ('8★ 字段 observedPlaylistSelection', 'observedPlaylistSelection'),
        ('9★ 字段 cuesOwnerSelection', 'cuesOwnerSelection'),
        ('10★ 字段 cuesLoadedAheadOfIdentity', 'cuesLoadedAheadOfIdentity'),
        ('11★ 日志 tag [code 1004]', '[code 1004]'),
        ('12★ 日志串 cues were already the NEW track', "cues were already the NEW track's"),
        ('13★ 日志串 json arrived before the switch was reported',
         'json arrived before the switch was reported'),
        ('14★ 日志串 settleChange 例外说明', 'or cues already the new'),
        ('15★ 版本横幅 code 1004', '==== BUILD 2.3.0 / code 1004'),
    ):
        ck.add(label, in_new(sym), in_old(sym))

    # ══════ B. 签名升级（新签名在 / 旧签名不在）══════
    key_n, key_o = slice_class(dn, C_KEY), slice_class(do, C_KEY)
    repo_n, repo_o = slice_class(dn, C_REPO), slice_class(do, C_REPO)
    src_n, src_o = slice_class(dn, C_SRC), slice_class(do, C_SRC)
    pos_n, pos_o = slice_class(dn, C_POS), slice_class(do, C_POS)

    mn, mo = methods_of(repo_n), methods_of(repo_o)
    ck.add_neg('16★ 旧签名 tryResumeFromCache(String,long) 已消失',
               '(Ljava/lang/String;J)Z' in mn.get('tryResumeFromCache', []),
               '(Ljava/lang/String;J)Z' in mo.get('tryResumeFromCache', []))
    ck.add_keep('17★ 新签名 tryResumeFromCache(String,long,long) 已在',
                '(Ljava/lang/String;JJ)Z' in mn.get('tryResumeFromCache', []), None)
    ck.add_keep('18★ 新签名 onTrackChanged(String,boolean,long,long) 已在',
                '(Ljava/lang/String;ZJJ)V' in mn.get('onTrackChanged', []), None)
    ck.add_keep('19 旧签名 onTrackChanged(String,boolean,long) 转发壳仍在',
                '(Ljava/lang/String;ZJ)V' in mn.get('onTrackChanged', []), None)
    sn, so = methods_of(src_n), methods_of(src_o)
    ck.add_neg('20★ 旧签名 settleChange(long,long,long) 已消失',
               '(JJJ)Z' in sn.get('settleChange', []), '(JJJ)Z' in so.get('settleChange', []))
    ck.add_keep('21★ 新签名 settleChange(long,long,long,long) 已在',
                '(JJJJ)Z' in sn.get('settleChange', []), None)
    ck.add_keep('22★ notifyTrackChanged 新增 (…,String,boolean,long,long) 版',
                '(Lio/github/ariinyume/dlsitesoundfloat/data/SubtitleRepository;'
                'Ljava/lang/String;ZJJ)V' in sn.get('notifyTrackChanged', []), None)
    ck.add_keep('23 旧 2 参 notifyTrackChanged 转发壳仍在',
                '(Lio/github/ariinyume/dlsitesoundfloat/data/SubtitleRepository;'
                'Ljava/lang/String;)V' in sn.get('notifyTrackChanged', []), None)

    # ══════ C. 常量层 ══════
    for label, f, exp in (
        ('24★ PlaylistKey.IDENTITY_IDX_SCALE（低位权重）', 'IDENTITY_IDX_SCALE', 1000),
        ('25★ PlaylistKey.SELECTION_TC_SCALE', 'SELECTION_TC_SCALE', 100000),
        ('26★ PlaylistKey.IDENTITY_TC_SCALE', 'IDENTITY_TC_SCALE', 1000000000000),
    ):
        ck.add_const(label, exp, field_value(key_n, f), field_value(key_o, f))
    # 前几轮的时限常量：一个都不许被动（本轮零改动）
    for label, f, exp in (
        ('27 PRELOAD_TOLERANCE_MS 保持 3500', 'PRELOAD_TOLERANCE_MS', 3500),
        ('28 NO_SUBTITLE_GRACE_MS 保持 3000', 'NO_SUBTITLE_GRACE_MS', 3000),
        ('29 NO_SUBTITLE_GRACE_MS_CACHED 保持 15000', 'NO_SUBTITLE_GRACE_MS_CACHED', 15000),
        ('30 NO_SUBTITLE_EARLY_CLOSE_MS 保持 5000', 'NO_SUBTITLE_EARLY_CLOSE_MS', 5000),
        ('31 POSITION_RESET_TOLERANCE_MS 保持 1000', 'POSITION_RESET_TOLERANCE_MS', 1000),
    ):
        ck.add_const_keep(label, exp, field_value(repo_n, f), field_value(repo_o, f))

    # ══════ D. 调用链 ══════
    ck.add_keep('32★ 数据层盖章：loadFromJsonArrayInternal 写 cuesLoadedAheadOfIdentity',
                bool(repo_n) and re.search(
                    r'loadFromJsonArrayInternal[\s\S]{0,40000}?'
                    r'selectionAheadOfIdentity:\(JJ\)Z', repo_n) is not None, None)
    ck.add_keep('33★ 数据层盖章：紧接着写 cuesOwnerSelection',
                bool(repo_n) and re.search(
                    r'selectionAheadOfIdentity:\(JJ\)Z[\s\S]{0,400}?'
                    r'iput-boolean[\s\S]{0,200}?cuesLoadedAheadOfIdentity:Z', repo_n) is not None,
                None)
    ck.add_keep('34★ PlayerPositionHook 调新入口（IILjava/lang/String;）',
                bool(pos_n) and 'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V' in pos_n,
                None)
    _ENTRY = 'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V'
    _GATE = 'onPlaylistStateFromStatusMap:(IIDLjava/lang/String;)Z'
    _o_entry = insn_offset(pos_n, _ENTRY)
    _o_gate = insn_offset(pos_n, _GATE)
    ck.add_keep('35★ 新入口在权威门之前（按 dex 指令偏移比较）',
                _o_entry >= 0 and _o_gate >= 0 and _o_entry < _o_gate, None)
    ck.add_manual('35b 指令偏移读数（新入口 |%04x: < 权威门 |%04x:）'
                  % (max(_o_entry, 0), max(_o_gate, 0)), _o_entry < _o_gate,
                  '未取到偏移 entry=%d gate=%d' % (_o_entry, _o_gate))
    ck.add_keep('36★ PlayerSourceHook 内 lay... makeSelection 调用点存在（两处：推送 + 通知）',
                bool(src_n) and src_n.count('PlaylistKey;.makeSelection:(II)J') >= 2, None)
    _sb = method_body(src_n, 'settleChange')
    _i_own = _sb.find('ownsCuesFor:(J)Z') if _sb else -1
    _i_sel = _sb.find('ownsCuesForSelection:(J)Z') if _sb else -1
    ck.add_keep('37★ settleChange **方法体内**同时问 ownsCuesFor 与 ownsCuesForSelection',
                bool(_sb) and _i_own >= 0 and _i_sel >= 0 and _i_own < _i_sel
                and (_i_sel - _i_own) <= 1200
                and _sb.count('SubtitleRepository;.ownsCuesFor') >= 2, None)
    ck.add_keep('38★ 硬裁决销毁 cues 时同步清掉选择键印章',
                bool(repo_n) and re.search(
                    r'cuesOwnerIdentity:J[\s\S]{0,900}?cuesOwnerSelection:J', repo_n) is not None,
                None)

    # ══════ E. 保持型（980 / 973 / 975 / 960 的机器）══════
    for label, sym in (
        ('39 cuesOwnerIdentity 仍在（code 980 反向切轨）', 'cuesOwnerIdentity'),
        ('40 previousTrackCuesHidden 仍在（code 973 隔离）', 'previousTrackCuesHidden'),
        ('41 pendingSoftSuspend 仍在（2.1.3 软挂起）', 'pendingSoftSuspend'),
        ('42 softNoSubtitles 仍在（1.21.16 软裁决）', 'softNoSubtitles'),
        ('43 provisionalEarlyClose 仍在（code 942 提前收窗）', 'provisionalEarlyClose'),
        ('44 resolvePendingTrack 仍在（裁决窗）', 'resolvePendingTrack'),
        ('45 ownsCuesFor 仍在（code 980 闸门例外）', 'ownsCuesFor'),
        ('46 sIdentitySeen 仍在（code 978 首观测只种基线）', 'sIdentitySeen'),
        ('47 PlayerPositionHook 权威门字段仍在', 'authoritative'),
        ('48 LogGate 仍在（日志分级）', 'LogGate'),
    ):
        ck.add_keep(label, in_new(sym), in_old(sym))

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
