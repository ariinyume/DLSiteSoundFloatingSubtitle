#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1006.py —— code 1006 的 **dex 字节码层**验证。

code 1006 修的是：**换作品时插件仍会短暂「无字幕」**（上一轮 code 1005 的同类问题第三例，
Ari 2026-10-10 22:53 截屏）。真机时间线（LSPosed_20261010_225318）：

    22:51:02.563  Loaded 73 cues（作品 RJ01126292，用户刚点新作品）
    22:51:09.847  >>> track changed 7→5 [index-gate] | lastJson=7284ms ago -> SUSPEND（空窗 17.9s）
    22:52:11.455  Loaded 343 cues（作品 RJ01536846）
    22:52:13.009  statusMap tc=4 dur=0.0（新列表第一拍：非零 tc、时长仍 0）
    22:52:17.875  >>> track changed 5→4 | lastJson=4627ms ago -> SUSPEND（空窗 29.7s）

本轮两条修法：
  ① 新增「作品键」正证据：`PlaylistKey.workKeyOf(url)` 从字幕 JSON 的请求 URL 推出
     `…/doujin/RJ01127000/RJ01126292/optimized`；`cuesBelongToChangedWork` 判定
     「手上 cues 的作品键 == 刚换到的作品键」⇒ 直接认领（第四条认领路径）。
  ② 重建窗口口径修正：起点改成**按实例 `id` 记账的真边沿**（`sLastTcByListId`），
     终点改由**带时长的权威读数**（`notePlaylistLoaded`）负责。

验五件事：
  A. **新增物**是否真编进包（正向锚，1006 有 / 1005 无）。
  B. **常量层**（`dexdump -d` 字段定义的 `value :`）—— 新增 15000（WORK_CHANGE_CLAIM_MS）
     与 8（MAX_TRACKED_LIST_IDS）；既有八个时限/尺度常量一个都不许被动过
     （这是本项目的**数值真源**，字符串层查不到值）。
  C. **调用链**（方法体里的指令序列）：
     · workKeyOf 真的用 indexOf/lastIndexOf/substring 做两步纯字符串处理；
     · 认领查询真的把两把钥匙 + 两个时刻喂给纯判定层，且内联常量就是 15000；
     · 装载盖章真的写进 workChangeKey/workChangeAtMs/cuesWorkKey/lastSeenWorkKey；
     · 采集入口真的用 LinkedHashMap.remove 判「同一个实例自己的真边沿」；
     · 关窗真的改由带时长的权威读数（onPlaylistStateFromStatusMap）负责；
     · 去抖闸门真的问了四处 `ownsCuesFor*`。
  D. **负向锚**：1005 包里这些新物必须**都不存在**（证明区分力）；
     且旧的三参 `onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V` 在 1006 包里消失。
  E. **保持型**：980 / 973 / 960 / code 1004 / code 1005 那几轮的机器一件都不许丢。

⚠️ 写本脚本时踩过的三个坑（都当场假 FAIL 过，别再犯）：
   ① dex 字符串池里存的是 Java 字符串的**内容**，不含源码里的引号 ——
      判据要写 `[code 1006]` 而不是 `"[code 1006]"`；
   ② dexdump 的指令行里寄存器号会随编译变化，判据只钉**助记符 + 字段/方法名**，
      不要钉 `v3`/`v9` 这种寄存器号；
   ③ 「两个符号之间 ≤N 字符」在 dexdump 上必假 FAIL（指令行长度不定）⇒ 一律用
      `method_body` 切片 + 顺序比较。

用法：
    python tools/verify_1006.py <apk_1006> <apk_1005>
主验 = code 1006 包；对照 = code 1005 包（唯一有区分力的基准）
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
C_NET = 'Lio/github/ariinyume/dlsitesoundfloat/hook/NetworkHook;'


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


def method_body(class_seg, name):
    """按 dexdump 的方法头切出**单个方法**的完整代码块（切到下一个方法头/类尾）。"""
    if not class_seg:
        return None
    m = re.search(r"name\s+:\s+'%s'\s*\n" % re.escape(name), class_seg)
    if not m:
        return None
    nxt = re.search(r'\n\s+#\d+\s+: \(in ', class_seg[m.end():])
    return class_seg[m.start():m.end() + (nxt.start() if nxt else len(class_seg))]


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
        elif old_v is None:
            v = 'OK 有区分力(旧包无此字段 → %s)' % new_v
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
        print('code 1006 —— dex 验证（主验 1006 / 对照 1005）')
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
    nn, no = slice_class(dn, C_NET), slice_class(do, C_NET)
    ck = Checker()

    # ══════════════ A. 新增物（正向锚：1006 有 / 1005 无）══════════════
    ck.add('1★ PlaylistKey.workKeyOf:(Ljava/lang/String;)Ljava/lang/String;（URL→作品键）',
           'workKeyOf:(Ljava/lang/String;)Ljava/lang/String;' in (kn or ''),
           'workKeyOf' in (ko or ''))
    ck.add('2★ PlaylistKey.cuesBelongToChangedWork:(Ljava/lang/String;Ljava/lang/String;JJJ)Z（五参纯判定）',
           'cuesBelongToChangedWork:(Ljava/lang/String;Ljava/lang/String;JJJ)Z' in (kn or ''),
           'cuesBelongToChangedWork' in (ko or ''))
    ck.add('3★ SubtitleRepository.ownsCuesForChangedWork:(J)Z（第四条认领查询）',
           'ownsCuesForChangedWork:(J)Z' in (rn or ''),
           'ownsCuesForChangedWork' in (ro or ''))
    ck.add('4★ SubtitleRepository.notePlaylistLoaded:()V（关窗新家＝带时长的权威读数）',
           'notePlaylistLoaded:()V' in (rn or ''),
           'notePlaylistLoaded' in (ro or ''))
    ck.add('5★ SubtitleRepository.loadFromJson:(Ljava/lang/String;Ljava/lang/String;)V（带作品键的新入口）',
           'loadFromJson:(Ljava/lang/String;Ljava/lang/String;)V' in (rn or ''),
           'loadFromJson:(Ljava/lang/String;Ljava/lang/String;)V' in (ro or ''))
    ck.add('6★ SubtitleRepository.loadFromJsonArray:(Lorg/json/JSONArray;Ljava/lang/String;)V',
           'loadFromJsonArray:(Lorg/json/JSONArray;Ljava/lang/String;)V' in (rn or ''),
           'loadFromJsonArray:(Lorg/json/JSONArray;Ljava/lang/String;)V' in (ro or ''))
    ck.add('7★ 字段 SubtitleRepository.cuesWorkKey:Ljava/lang/String;（这份 cues 的作品键）',
           '.cuesWorkKey:Ljava/lang/String;' in (rn or ''),
           '.cuesWorkKey:Ljava/lang/String;' in (ro or ''))
    ck.add('8★ 字段 SubtitleRepository.lastSeenWorkKey:Ljava/lang/String;（历史基线，硬裁决不清）',
           '.lastSeenWorkKey:Ljava/lang/String;' in (rn or ''),
           '.lastSeenWorkKey:Ljava/lang/String;' in (ro or ''))
    ck.add('9★ 字段 SubtitleRepository.workChangeKey:Ljava/lang/String;（待兑现的换作品）',
           '.workChangeKey:Ljava/lang/String;' in (rn or ''),
           '.workChangeKey:Ljava/lang/String;' in (ro or ''))
    ck.add('10★ 字段 SubtitleRepository.workChangeAtMs:J（换作品时刻）',
           '.workChangeAtMs:J' in (rn or ''),
           '.workChangeAtMs:J' in (ro or ''))
    ck.add('11★ 常量字段 WORK_CHANGE_CLAIM_MS（换作品认领时限）',
           'WORK_CHANGE_CLAIM_MS' in (rn or ''),
           'WORK_CHANGE_CLAIM_MS' in (ro or ''))
    ck.add('12★ 字段 PlayerSourceHook.sLastTcByListId:Ljava/util/LinkedHashMap;（按实例记账）',
           '.sLastTcByListId:Ljava/util/LinkedHashMap;' in (sn or ''),
           '.sLastTcByListId:Ljava/util/LinkedHashMap;' in (so or ''))
    # ⚠️ 判据形态：dexdump 的**字段段**是 name / type 分行，没有 `名字:类型` 连写
    #   （连写只出现在指令行的 iget/sget 操作数里）。所以这里必须按字段段匹配。
    ck.add('13★ 常量字段 MAX_TRACKED_LIST_IDS（实例表容量上限，int）',
           re.search(r"name\s+:\s+'MAX_TRACKED_LIST_IDS'\s*\n\s*type\s+:\s*'I'",
                     sn or '') is not None,
           'MAX_TRACKED_LIST_IDS' in (so or ''))
    ck.add('14★ 字段 PlayerSourceHook.sLoggedMissingListId:Z（一次性告警）',
           '.sLoggedMissingListId:Z' in (sn or ''),
           '.sLoggedMissingListId:Z' in (so or ''))
    # ⚠️ 对照侧的判据必须是「**旧包里也没有**新形态」，不能写成「旧包里有旧形态」
    #   （那恒为真 ⇒ add() 判成「无区分力」；1006_src 首跑就这么红了）。
    ck.add('15★ 采集入口签名升为四参 onPlaylistSelectionFromStatusMap:(IILjava/lang/String;Ljava/lang/String;)V',
           'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;Ljava/lang/String;)V' in (sn or ''),
           'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;Ljava/lang/String;)V' in (so or ''))
    ck.add('16★ NetworkHook.submit 升为四参（多带作品键）',
           'submit:(Ljava/lang/String;Lio/github/ariinyume/dlsitesoundfloat/data/SubtitleRepository;'
           'Ljava/lang/String;Ljava/lang/String;)V' in (nn or ''),
           'submit:(Ljava/lang/String;Lio/github/ariinyume/dlsitesoundfloat/data/SubtitleRepository;'
           'Ljava/lang/String;Ljava/lang/String;)V' in (no or ''))
    ck.add('17★ 里程碑日志串 [code 1006]', new('[code 1006]'), old('[code 1006]'))
    ck.add('18★ 盖章日志串 work change pending',
           new('work change pending'), old('work change pending'))
    ck.add('19★ 采集期一次性告警串 status map has no `id` field',
           new('status map has no `id` field'), old('status map has no `id` field'))
    ck.add('20★ 认领日志串 came from a DIFFERENT work',
           new('came from a DIFFERENT work'), old('came from a DIFFERENT work'))
    ck.add('21★ 认领日志串 json was fetched for another work at click time',
           new('json was fetched for another work at click time'),
           old('json was fetched for another work at click time'))
    ck.add('22★ 版本横幅 ==== BUILD 2.3.1 / code 1006（versionName 本轮不动）',
           new('==== BUILD 2.3.1 / code 1006'), old('==== BUILD 2.3.1 / code 1006'))

    # ══════════════ B. 常量层（dexdump 字段 value = 数值真源）══════════════
    ck.add_const('23★ WORK_CHANGE_CLAIM_MS = 15000（真机提前量 4.6~7.3s，留足余量）',
                 15000, field_value(rn, 'WORK_CHANGE_CLAIM_MS'),
                 field_value(ro, 'WORK_CHANGE_CLAIM_MS'))
    ck.add_const('24★ PlayerSourceHook.MAX_TRACKED_LIST_IDS = 8（宿主同时活着的实例是个位数）',
                 8, field_value(sn, 'MAX_TRACKED_LIST_IDS'),
                 field_value(so, 'MAX_TRACKED_LIST_IDS'))
    for lbl, owner, name, exp in (
        ('25★', 'rn', 'REBUILT_PLAYLIST_CLAIM_MS', 15000),
        ('26★', 'rn', 'PRELOAD_TOLERANCE_MS', 3500),
        ('27★', 'rn', 'NO_SUBTITLE_GRACE_MS', 3000),
        ('28★', 'rn', 'NO_SUBTITLE_GRACE_MS_CACHED', 15000),
        ('29★', 'rn', 'NO_SUBTITLE_EARLY_CLOSE_MS', 5000),
        ('30★', 'kn', 'SELECTION_TC_SCALE', 100000),
        ('31★', 'kn', 'IDENTITY_TC_SCALE', 1000000000000),
        ('32★', 'kn', 'IDENTITY_IDX_SCALE', 1000),
    ):
        seg = {'rn': rn, 'kn': kn}[owner]
        seg_o = {'rn': ro, 'kn': ko}[owner]
        ck.add_const_keep('%s 既有常量 %s 一字未动' % (lbl, name), exp,
                          field_value(seg, name), field_value(seg_o, name))

    # ══════════════ C. 调用链（方法体内指令；每条都带对照包读数）══════════════
    SEGS = {'kn': (kn, ko), 'rn': (rn, ro), 'sn': (sn, so),
            'pn': (pn, po), 'nn': (nn, no)}

    def bd(owner, name, side):
        return method_body(SEGS[owner][side], name) or ''

    probes = [
        ("33★ workKeyOf 用 indexOf('?') 砍查询串（String.indexOf:(I)I）",
         'kn', 'workKeyOf', lambda s: 'Ljava/lang/String;.indexOf:(I)I' in s),
        ("34★ workKeyOf 用 lastIndexOf('/') 砍哈希文件名（String.lastIndexOf:(I)I）",
         'kn', 'workKeyOf', lambda s: 'Ljava/lang/String;.lastIndexOf:(I)I' in s),
        # ⚠️ 两步 substring 都是**两参**重载 `substring:(II)`（javac 把 `0` 也生成出来），
        #   写成单参 `substring:(I)` 会当场假 FAIL（首跑就是这么红的）。
        ('35★ workKeyOf 两步都用 substring + isEmpty 收尾（纯字符串处理，无任何 Android 依赖）',
         'kn', 'workKeyOf', lambda s: (
             'Ljava/lang/String;.substring:(II)Ljava/lang/String;' in s
             and 'Ljava/lang/String;.isEmpty:()Z' in s
             and 'SystemClock' not in s)),
        ('36★ cuesBelongToChangedWork 用 String.equals 判「完全相等」的安全阀',
         'kn', 'cuesBelongToChangedWork',
         lambda s: 'Ljava/lang/String;.equals:(Ljava/lang/Object;)Z' in s),
        ('37★ cuesBelongToChangedWork 真的有 null 早退（含三条 return 分支的 if-eqz/if-nez）',
         'kn', 'cuesBelongToChangedWork',
         lambda s: (s.count('return') >= 4
                    and re.search(r'if-(?:eqz|nez)', s) is not None
                    and 'SystemClock' not in s)),
        ('38★ 认领查询真的调用纯判定层并直接返回（invoke → move-result → return）',
         'rn', 'ownsCuesForChangedWork', lambda s: (
             'cuesBelongToChangedWork:(Ljava/lang/String;Ljava/lang/String;JJJ)Z' in s
             and 'move-result' in s and 'return' in s)),
        ('39★ 认领查询把两把钥匙 + 两个时刻按序喂进纯判定层',
         'rn', 'ownsCuesForChangedWork', lambda s: (
             s.find('cuesWorkKey:Ljava/lang/String;') >= 0
             and s.find('workChangeKey:Ljava/lang/String;') > s.find('cuesWorkKey:Ljava/lang/String;')
             and s.find('workChangeAtMs:J') > s.find('workChangeKey:Ljava/lang/String;'))),
        ('40★ 内联到认领查询里的那个 15000 也在（防常量折叠后被优化掉）',
         'rn', 'ownsCuesForChangedWork', lambda s: '#int 15000' in s),
        ('41★ 盖章三连写进装载路径：workChangeKey → workChangeAtMs → cuesWorkKey（顺序即源码）',
         'rn', 'loadFromJsonArrayInternal', lambda s: (
             -1 < s.find('workChangeKey:Ljava/lang/String;')
             < s.find('workChangeAtMs:J')
             < s.find('cuesWorkKey:Ljava/lang/String;'))),
        ('42★ 盖章用 iput-object（字段写入而非只读）',
         'rn', 'loadFromJsonArrayInternal',
         lambda s: re.search(r'\biput-object\b[^\n]*workChangeKey:Ljava/lang/String;', s) is not None),
        ('43★ 历史基线 lastSeenWorkKey 在同一段里被写（先判后写）',
         'rn', 'loadFromJsonArrayInternal', lambda s: (
             re.search(r'\biput-object\b[^\n]*lastSeenWorkKey:Ljava/lang/String;', s) is not None)),
        ('44★ 认领路径升到四条：ownsCuesForChangedWork 也进了 tryResumeFromCache',
         'rn', 'tryResumeFromCache',
         lambda s: ('ownsCuesForChangedWork:(J)Z' in s
                    and 'ownsCuesForRebuiltPlaylist:(JJ)Z' in s)),
        ('45★ 四条路径的日志串齐备（[code 1006] 是新加的那条）',
         'rn', 'tryResumeFromCache',
         lambda s: '[code 1006]' in s and '[code 1005]' in s),
        ('46★ 认领一次性：方法体内出现把 workChangeKey 落下的写指令（iput-object 空值）',
         'rn', 'tryResumeFromCache',
         lambda s: re.search(r'\biput-object\b[^\n]*workChangeKey:Ljava/lang/String;', s) is not None),
        ('47★ 真实边沿判据：LinkedHashMap.remove(id) 的结果决定是否开窗（invoke → move-result → if-eqz）',
         'sn', 'onPlaylistSelectionFromStatusMap', lambda s: (
             'Ljava/util/LinkedHashMap;.remove:(Ljava/lang/Object;)Ljava/lang/Object;' in s
             and re.search(r'move-result-object[\s\S]{0,200}?if-eqz', s) is not None)),
        # ⚠️ 容量上限是编译期常量 ⇒ dex 里是**内联立即数** `const/16 v3, #int 8 // #8`，
        #   字段名不会出现在指令行里（首跑写 `MAX_TRACKED_LIST_IDS:I` 当场假 FAIL）。
        #   字段本身仍在（13★/24★ 用字段段与 value 验它）。
        ('48★ 非零分支按 LRU 上限记账：size() 与内联上限 8 比较后 put',
         'sn', 'onPlaylistSelectionFromStatusMap', lambda s: (
             'Ljava/util/LinkedHashMap;.size:()I' in s
             and re.search(r'const/16 v\d+, #int 8 // #8', s) is not None
             and 'Ljava/util/LinkedHashMap;.put:(Ljava/lang/Object;Ljava/lang/Object;)'
             'Ljava/lang/Object;' in s)),
        ('49★ PlayerPositionHook 从状态 Map 取 `id` 并传下去（Map.get → 四参调用）',
         'pn', 'consume', lambda s: (
             'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;Ljava/lang/String;)V' in s
             and 'Ljava/util/Map;.get:(Ljava/lang/Object;)Ljava/lang/Object;' in s)),
        ('50★ 去抖闸门的静默例外问满四处（顺序即源码 || 链，1006 排在最后）',
         'sn', 'settleChange', lambda s: (
             s.find('ownsCuesFor:(J)Z') >= 0
             and s.find('ownsCuesForSelection:(J)Z') > s.find('ownsCuesFor:(J)Z')
             and s.find('ownsCuesForRebuiltPlaylist:(JJ)Z') > s.find('ownsCuesForSelection:(J)Z')
             and s.find('ownsCuesForChangedWork:(J)Z') > s.find('ownsCuesForRebuiltPlaylist:(JJ)Z'))),
        ('51★ NetworkHook 只对 .json 请求推作品键（String.contains + workKeyOf）',
         'nn', 'tapResponseBody', lambda s: (
             'Ljava/lang/String;.contains:(Ljava/lang/CharSequence;)Z' in s
             and 'PlaylistKey;.workKeyOf:(Ljava/lang/String;)Ljava/lang/String;' in s)),
        ('52★ NetworkHook 把作品键转给 loadFromJson(text, workKey)',
         'nn', 'submit',
         lambda s: 'loadFromJson:(Ljava/lang/String;Ljava/lang/String;)V' in s),
    ]
    for label, owner, meth, fn in probes:
        ck.add_diff(label, fn(bd(owner, meth, 0)), fn(bd(owner, meth, 1)))

    # 53★：关窗职责搬家 —— onPlaylistStateFromStatusMap（带时长的权威读数）里必须出现
    #      notePlaylistLoaded 调用；1005 包里没有这个方法，故有区分力。
    state_fn = lambda s: 'notePlaylistLoaded:()V' in s
    ck.add_diff('53★ 关窗职责搬到带时长的权威读数（onPlaylistStateFromStatusMap 调 notePlaylistLoaded）',
                state_fn(bd('sn', 'onPlaylistStateFromStatusMap', 0)),
                state_fn(bd('sn', 'onPlaylistStateFromStatusMap', 1)))

    # ══════════════ D. 负向锚（旧世界必须彻底消失）══════════════
    ck.add_manual('54★ 负向：旧的三参采集入口 (IILjava/lang/String;)V 在 1006 包里消失',
                  'onPlaylistSelectionFromStatusMap:(IILjava/lang/String;)V' not in (sn or ''),
                  '旧签名仍在')
    ck.add_manual('55★ 负向：noteObservedPlaylistSelection 的类里不再有重建窗口字段写入',
                  re.search(r'\biput-wide\b[^\n]*playlistRebuiltAtMs:J',
                            method_body(rn, 'noteObservedPlaylistSelection') or '') is None,
                  '仍在写 playlistRebuiltAtMs')
    ck.add_keep('56★ 保持型：1005 的开窗日志形态仍在（`… via ` + where，本轮只在其后追加实例 id）',
                new('playlist destroyed (trackCount=0) via '))

    # ══════════════ E. 保持型（老机器一件都不许丢）══════════════
    for lbl, owner, sym in (
        ('57★', 'kn', 'cuesBelongToRebuiltPlaylist:(ZJJJJJ)Z'),
        ('58★', 'kn', 'makeSelection:(II)J'),
        ('59★', 'kn', 'selectionAheadOfIdentity:(JJ)Z'),
        ('60★', 'rn', 'ownsCuesForRebuiltPlaylist:(JJ)Z'),
        ('61★', 'rn', 'notePlaylistRebuilt:(Ljava/lang/String;)V'),
        ('62★', 'rn', 'ownsCuesFor:(J)Z'),
        ('63★', 'rn', 'ownsCuesForSelection:(J)Z'),
        ('64★', 'rn', 'tryResumeFromCache:(Ljava/lang/String;JJ)Z'),
        ('65★', 'sn', 'settleChange:(JJJJ)Z'),
        ('66★', 'sn', 'notifyTrackChanged'),
    ):
        seg = {'kn': kn, 'rn': rn, 'sn': sn}[owner] or ''
        ck.add_keep('%s 保持型：%s 仍在' % (lbl, sym), sym in seg)
    for lbl, owner, name, exp in (
        ('67★', 'rn', 'playlistRebuiltAtMs:J', None),
        ('68★', 'rn', 'cuesArrivedDuringRebuild:Z', None),
        ('69★', 'rn', 'cuesLoadedAtMs:J', None),
        ('70★', 'sn', 'sLastNonZeroTrackCount:I', None),
    ):
        seg = {'rn': rn, 'sn': sn}[owner] or ''
        ck.add_keep('%s 保持型：字段 %s 仍在（1005 窗口机制的基础件）' % (lbl, name), name in seg)
    ck.add_keep('71★ 保持型：code 1005 的里程碑日志 [code 1005] 仍在', new('[code 1005]'))
    ck.add_keep('72★ 保持型：code 1005 的开窗日志串仍在',
                new('rebuild window OPEN'))
    ck.add_keep('73★ 保持型：980 的里程碑日志 [code 980] 仍在', new('[code 980]'))
    ck.add_keep('74★ 保持型：973 的隔离日志串仍在', new('previous-track cues quarantined'))
    ck.add_keep('75★ 保持型：code 1004 的认领日志串仍在', new('cues were already the NEW track'))
    ck.add_keep('76★ 保持型：code 1004 的履历段仍在（追加式，不覆盖）',
                new('==== BUILD 2.3.0 / code 1004 （'))
    ck.add_keep('77★ 保持型：code 1005 的履历段仍在（追加式，不覆盖）',
                new('==== BUILD 2.3.1 / code 1005 （'))
    ck.add_keep('78★ 保持型：NetworkHook 仍在（本轮改了它，别把它整个弄丢）',
                nn is not None and no is not None)
    ck.add_keep('79★ 保持型：PlayerPositionHook 仍在（本轮改了它的采集点）',
                pn is not None and po is not None)

    return ck.report()


if __name__ == '__main__':
    sys.exit(main())
