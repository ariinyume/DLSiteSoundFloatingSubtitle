#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1005_src.py —— code 1005 的**源码层**验证。

code 1005 修的是：**换作品 / 换章节时同样会短暂「无字幕」**（Ari 2026-10-10 21:42 截屏）。
真机时间线（LSPosed_20261010_214410）：

    21:41:53.139  statusMap  currentIndex=0 trackCount=0 duration=0.0 idle   ← 旧列表已销毁
    21:41:53.505  optimized/c0d71242….json 到达（新轨 idx=2 的字幕，仅隔 366ms）
    21:41:58.241  statusMap  currentIndex=2 trackCount=8 duration=0.0        ← 新列表首次可读
    21:41:59.654  >>> track changed  tc=6 dur=835560 idx=5 -> tc=8 dur=1286040 idx=2
                  | lastJson=6135ms ago -> SUSPEND … [973 previous-track cues quarantined]
    21:42:04.657  no subtitle json yet -> auto-closed floating window early
    21:42:14.654  [code 960] soft verdict

code 1004 补的「选择键」由 (曲目数, 序号) 构成，而列表重建期 `trackCount == 0`
⇒ makeSelection 只能返回 0 ⇒ 那条路**没有证据可用**；新旧列表曲目数又不同（6→8）
⇒ 防跨作品冒充的安全阀必然不放行。本轮补上「列表重建窗口」这条判据。

⚠️ 为什么字节码层不够、必须再有源码层：见 verify_1004_src.py 的同一段说明 ——
   dex 层证得了「指令编进去了」，证不了「判据条件写得对、盖章时机对、开关窗互补」。

本脚本的每一条都只在**剥掉注释**后的源码上匹配（铁律 53）。

用法：
    python tools/verify_1005_src.py          （在仓库根执行，不接参数）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = 'app/src/main/java/io/github/ariinyume/dlsitesoundfloat/'
KEY = os.path.join(ROOT, J, 'data/PlaylistKey.java')
REPO = os.path.join(ROOT, J, 'data/SubtitleRepository.java')
POS = os.path.join(ROOT, J, 'hook/PlayerPositionHook.java')
SRC = os.path.join(ROOT, J, 'hook/PlayerSourceHook.java')
MOD = os.path.join(ROOT, J, 'DlsiteSoundSubtitleModule.java')
GRADLE = os.path.join(ROOT, 'app/build.gradle')

rows = []
fails = 0


def check(label, ok, detail=''):
    global fails
    rows.append((label, bool(ok), detail))
    if not ok:
        fails += 1


def read(p):
    """读源码，并**把行尾统一成 LF** —— 判据不能被磁盘上的 CRLF/LF 风格左右（铁律 53）。

    ⚠️ 本仓库 `core.autocrlf=true`：只要做过一次 `git rebase` / `checkout`，工作区文件
       就会从 LF 变成 CRLF（仓库内仍是 LF，`git diff` 看不出来）。任何写死 `\\n` 的跨行
       判据都会在那一刻崩掉或假 FAIL。所以判据一律先归一化。
    """
    return io.open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')


def strip_comments(src):
    """剥掉块注释与行注释 —— 判据必须在「只有代码」的文本上匹配（铁律 53）。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def norm(src):
    """折空白：判据的「字符级形态」不能被换行/缩进变化左右（铁律 53 的另一半）。"""
    return re.sub(r'\s+', ' ', src)


def method_body(src, sig):
    """按花括号配平抽一个方法的完整函数体（缩进正则不可靠）。"""
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


def split_args(argstr):
    """按顶层逗号切实参（先剥字符串字面量与 `->`，否则深度会掉到负）。"""
    argstr = re.sub(r'"(\\.|[^"\\])*"', '""', argstr)
    argstr = argstr.replace('->', '-')
    out, depth, cur = [], 0, ''
    for ch in argstr:
        if ch in '(<':
            depth += 1
        elif ch in ')>':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def arg_arities(src, name):
    """取 `name(...)` 的**所有调用点**实参个数（配平括号扫描，排除定义处）。

    ⚠️ 前视里**不能**排除 `.`：本项目里被断言的这些方法几乎都写成
       `SubtitleRepository.getInstance().ownsCuesForXxx(...)` ——
       写成 `(?<![\\w.])` 会把它们**全部**漏掉，读数变成 `[]` 而断言当场假 FAIL
       （本脚本第一次运行就是这么红的）。
    """
    out = []
    for m in re.finditer(r'(?<![\w])%s\(' % re.escape(name), src):
        prefix = src[max(0, m.start() - 30):m.start()]
        if re.search(r'\b(?:boolean|void|int|long|double|float|String|Object)\s+$', prefix):
            continue
        i, depth = m.end(), 1
        while i < len(src) and depth:
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
            i += 1
        out.append(len(split_args(src[m.end():i - 1])))
    return out


def main():
    for p in (KEY, REPO, POS, SRC, MOD, GRADLE):
        if not os.path.exists(p):
            print('找不到文件：%s（请在仓库根执行）' % p)
            return 2

    key = read(KEY)
    repo = read(REPO)
    pos = read(POS)
    src = read(SRC)
    mod = read(MOD)
    gra = read(GRADLE)
    keyc, repoc, posc, srcc = (strip_comments(x) for x in (key, repo, pos, src))

    # ══════════════ ① 纯判定层：PlaylistKey.cuesBelongToRebuiltPlaylist ══════════════
    sig = 'public static boolean cuesBelongToRebuiltPlaylist('
    check('① PlaylistKey 新增 cuesBelongToRebuiltPlaylist（列表重建窗口的纯判定）', sig in keyc)
    body = norm(method_body(keyc, sig))
    check('① 六参签名：窗口旗标 / 目标身份 / 盖章身份 / 到达时刻 / 当前时刻 / 时限',
          norm(keyc).find(
              'cuesBelongToRebuiltPlaylist(boolean arrivedDuringRebuild, long newIdentity, '
              'long cuesOwnerIdentity, long arrivedAtMs, long nowMs, long toleranceMs)') >= 0)
    check('① 首个早退：没观察到窗口 / 目标身份未知 ⇒ 一律不认领',
          'if (!arrivedDuringRebuild || newIdentity == 0L) { return false; }' in body)
    check('① 第二道保险：必须**换了列表** —— 新曲目数与盖章时不同（相同交给 code 1004）',
          'int newTc = identityTrackCount(newIdentity);' in body
          and 'newTc <= 0 || newTc == identityTrackCount(cuesOwnerIdentity)' in body)
    check('① 第三道保险：到达时刻未知 / 时钟回退 ⇒ 不认领',
          'if (arrivedAtMs <= 0L || nowMs < arrivedAtMs) { return false; }' in body)
    check('① 时限：nowMs - arrivedAtMs <= toleranceMs（闭区间，超时放弃）',
          'return nowMs - arrivedAtMs <= toleranceMs;' in body)
    check('① 纯判定：方法体里不出现任何字段赋值 / Android 依赖',
          not re.search(r'\b(?:SystemClock|this\.)\w*', body)
          and 'nowMs' in body)
    check('① 复用身份键分量解析（不另造一套位运算）',
          'identityTrackCount(' in keyc
          and keyc.count('public static int identityTrackCount') == 1)

    # ══════════════ ② 数据层：三个字段 + 常量 ══════════════
    check('② 字段 playlistRebuiltAtMs 是 volatile long（窗口起点，0 = 不在窗口内）',
          re.search(r'private volatile long playlistRebuiltAtMs = 0L;', repoc) is not None)
    check('② 字段 cuesArrivedDuringRebuild 是 volatile boolean（这份 cues 是否窗口内到达）',
          re.search(r'private volatile boolean cuesArrivedDuringRebuild = false;', repoc) is not None)
    check('② 字段 cuesLoadedAtMs 是 volatile long（到达时刻，供时限判定）',
          re.search(r'private volatile long cuesLoadedAtMs = 0L;', repoc) is not None)
    check('② 认领时限常量 = 15000ms（真机实测早到 6135ms，留足余量）',
          'private static final long REBUILT_PLAYLIST_CLAIM_MS = 15000L;' in repoc)

    # ══════════════ ③ 开窗 / 关窗 / 盖章 三者互补 ══════════════
    reb = norm(method_body(repoc, 'public void notePlaylistRebuilt('))
    check('③ notePlaylistRebuilt 存在且是「只开一次、不刷新首次时刻」',
          'if (playlistRebuiltAtMs != 0L) { return; }' in reb
          and 'playlistRebuiltAtMs = SystemClock.uptimeMillis();' in reb)
    check('③ 开窗是零状态副作用：只写一个字段 + 一条日志，不改任何 UI/裁决状态',
          reb.count('playlistRebuiltAtMs =') == 1
          and 'softNoSubtitles' not in reb and 'pendingTrackDecision' not in reb)
    sel = norm(method_body(repoc, 'public void noteObservedPlaylistSelection('))
    check('③ noteObservedPlaylistSelection 对 0 值早退（不让未知选择键冲掉已知的）',
          'if (selection <= 0L) { return; }' in sel)
    check('③ 新列表出现即关窗（playlistRebuiltAtMs 归零）',
          'observedPlaylistSelection = selection;' in sel
          and 'playlistRebuiltAtMs = 0L;' in sel)
    check('③ 关窗**不**动已盖在 cues 上的旗标（那份证据要留到换轨通知兑现）',
          'cuesArrivedDuringRebuild' not in sel)
    load = norm(repoc)
    check('③ 装载时同步盖两枚新印章：到达时刻 + 「窗口是否还开着」',
          'cuesLoadedAtMs = SystemClock.uptimeMillis(); cuesArrivedDuringRebuild = playlistRebuiltAtMs != 0L;'
          in load)
    check('③ 两枚新印章紧跟在 code 1004 的印章之后（同一次加载、同一把锁内）',
          load.find('cuesLoadedAheadOfIdentity = PlaylistKey.selectionAheadOfIdentity(')
          < load.find('cuesArrivedDuringRebuild = playlistRebuiltAtMs != 0L;'))

    # ══════════════ ④ 认领：第三条路径 + 一次性 + 与数据同生共死 ══════════════
    own = norm(method_body(repoc, 'public boolean ownsCuesForRebuiltPlaylist('))
    check('④ ownsCuesForRebuiltPlaylist 把六个参数原样转给纯判定层',
          norm(own).find('PlaylistKey.cuesBelongToRebuiltPlaylist( cuesArrivedDuringRebuild, '
                         'newIdentity, cuesOwnerIdentity, cuesLoadedAtMs, nowMs, '
                         'REBUILT_PLAYLIST_CLAIM_MS);') >= 0)
    check('④ 认领查询是无锁读（不拿 lock，去抖闸门会高频调它）',
          'synchronized' not in own)
    res = norm(method_body(repoc, 'private boolean tryResumeFromCache('))
    check('④ tryResumeFromCache 现在有三条认领路径：身份（980）/ 选择键（1004）/ 重建窗口（1005）',
          'boolean byIdentity' in res and 'boolean bySelection' in res and 'boolean byRebuild' in res)
    check('④ 第三条路径真的接了查询方法（不是写了变量没用）',
          'boolean byRebuild = ownsCuesForRebuiltPlaylist(newIdentity, SystemClock.uptimeMillis());' in res)
    check('④ 三条路径缺失时一律 return false（任一成立才继续）',
          'if (!byIdentity && !bySelection && !byRebuild) { return false; }' in res)
    check('④ 日志 code 三分支：980 / 1004 / 1005',
          'byIdentity ? "[code 980]" : bySelection ? "[code 1004]" : "[code 1005]"' in res)
    check('④ code 1005 的 why 说清「列表重建窗口内到达 ⇒ 属于新列表」',
          'the playlist was rebuilt (trackCount went to 0) and this json arrived' in res
          and 'so it belongs to the NEW playlist' in res)
    check('④ 认领是**一次性**的：兑现后把旗标落下（否则同一份印章会被后来的无关换轨再兑现）',
          'cuesArrivedDuringRebuild = false;' in res)
    check('④ 旗标落下发生在 synchronized(lock) 块内（与撤销挂起/隔离同一临界区）',
          res.index('synchronized (lock)') < res.index('cuesArrivedDuringRebuild = false;'))
    hard = norm(method_body(repoc, 'private void resolvePendingTrack('))
    check('④ 硬裁决销毁数据时同步清掉重建印章（与主人印章同生共死）',
          'cuesArrivedDuringRebuild = false;' in hard and 'cuesLoadedAtMs = 0L;' in hard)
    check('④ 硬裁决的清理与既有印章清理挨在一起（不散落）',
          hard.find('cuesOwnerIdentity = 0L;') < hard.find('cuesArrivedDuringRebuild = false;'))

    # ══════════════ ⑤ Hook 层：采集点与分流 ══════════════
    # ⚠️ 这里**不能**用 method_body(posc, 'PlayerSourceHook.onPlaylistSelectionFromStatusMap(')：
    #    该串在 PlayerPositionHook 里第一次出现就是**调用点**（后面跟 `;` 不跟 `{`），
    #    method_body 会一路找到下一个 `{` ⇒ 抽出的是错位片段（真踩过，两条断言一真一假）。
    posn = norm(posc)
    check('⑤ PlayerPositionHook 的推送条件**不再**要求 trackCount > 0（重建期报 0 必须收得到）',
          re.search(r'if \(isPlaylistMap && !halted && curIdx >= 0\) \{ '
                    r'PlayerSourceHook\.onPlaylistSelectionFromStatusMap\(', posn) is not None)
    check('⑤ 旧写法 `trackCount > 0 && !halted && curIdx >= 0` 在 PlayerPositionHook 里彻底消失',
          'trackCount > 0 && !halted && curIdx >= 0' not in posc)
    check('⑤ 仍要求 !halted 与 curIdx >= 0（作废列表 / 读不到序号一律不推）',
          '!halted && curIdx >= 0' in posn)
    check('⑤ 推送点仍在权威门之前（按源码位置：推送 < `if (!authoritative)` 早退）',
          posn.find('PlayerSourceHook.onPlaylistSelectionFromStatusMap')
          < posn.find('if (!authoritative)'))
    entry = norm(method_body(srcc, 'public static void onPlaylistSelectionFromStatusMap('))
    check('⑤ PlayerSourceHook 按 trackCount 分流：0 ⇒ 开窗口，> 0 ⇒ 推选择键',
          'if (trackCount <= 0) {' in entry
          and 'repo.notePlaylistRebuilt(where);' in entry
          and 'repo.noteObservedPlaylistSelection(PlaylistKey.makeSelection(trackCount, idx));' in entry)
    check('⑤ 分流里 trackCount<=0 那条分支以 return 结束（不会顺手把 0 当选择键推下去）',
          re.search(r'if \(trackCount <= 0\) \{[\s\S]*?return;[\s\S]*?\}', entry) is not None)
    check('⑤ 只在「非零 → 0」的跳变上开窗（冷启动空列表不算，防判据退化）',
          'if (sLastNonZeroTrackCount > 0) {' in entry
          and 'sLastNonZeroTrackCount = trackCount;' in entry)
    check('⑤ 「最近一次非零曲目数」是静态 volatile（跨 statusMap 调用保留）',
          'private static volatile int sLastNonZeroTrackCount = 0;' in srcc)

    # ══════════════ ⑥ 去抖闸门：静默例外扩到第三条 ══════════════
    settle = norm(method_body(srcc, 'private static boolean settleChange('))
    check('⑥ settleChange 的静默例外现在问三处： ownsCuesFor / ownsCuesForSelection / ownsCuesForRebuiltPlaylist',
          'ownsCuesFor(to)' in settle
          and 'ownsCuesForSelection(toSelection)' in settle
          and 'ownsCuesForRebuiltPlaylist(to, now)' in settle)
    check('⑥ 三处用 || 连成同一个 ours 判据（任一条成立即放行）',
          re.search(r'boolean ours = [^;]*ownsCuesFor[^;]*ownsCuesForSelection[^;]*ownsCuesForRebuiltPlaylist[^;]*;',
                    settle) is not None)
    check('⑥ 放行后仍让静默窗失效（sNotifiedMs = 0L）—— 铁律 51 的既有收口没被改掉',
          'sNotifiedMs = 0L;' in settle)

    # ══════════════ ⑦ 不变量：调用点实参个数（漏传新键的新调用点会被抓住） ══════════════
    # 口径说明：这里断言的是**语义集合**而不是「按顺序列出每个读数」——
    #   后者会因为我在别处多一个/少一个调用点就变红，而那是无关改动（铁律 42 的同族坑）。
    #   真正要抓的是「有没有哪个调用点少传了新键」，那等价于「出现了既非 4 又非 2/5 的实参个数」。
    a_settle = arg_arities(srcc, 'settleChange')
    check('⑦ 不变量：settleChange 的每个调用点都恰好 4 个实参（新键一个都不能漏传）',
          len(a_settle) >= 2 and set(a_settle) == {4}, '实参个数 %s' % a_settle)
    a_notify = arg_arities(srcc, 'notifyTrackChanged')
    check('⑦ 不变量：notifyTrackChanged 的调用点只有 5 参（带选择键）与 2 参（老路径补 0L）两种',
          len(a_notify) >= 4 and set(a_notify) <= {2, 5}, '实参个数 %s' % a_notify)
    check('⑦ 不变量：5 参那条真的在用（至少 2 处：索引门 + 2 参重载的转发）',
          a_notify.count(5) >= 2, '5 参处数 %d' % a_notify.count(5))
    a_own = arg_arities(srcc, 'ownsCuesForRebuiltPlaylist')
    check('⑦ 不变量：ownsCuesForRebuiltPlaylist 的调用点恰好 2 参（身份 + 当前时刻）',
          a_own == [2], '实参个数 %s' % a_own)

    # ══════════════ ⑧ 版本号四处 + 横幅纪律 ══════════════
    check('⑧ build.gradle: appVersionCode = 1005', 'def appVersionCode = 1005' in gra)
    # 【随轮次推进】versionName 本轮按 Ari 指令由 2.3.0 升到 **2.3.1**，因此**不钉死**它，
    #   改为守「三处同值」：gradle 的 name / tag + 常开横幅（横幅是装机核对的唯一锚）。
    m_name = re.search(r"def appVersionName = '([\d.]+)'", gra)
    m_tag = re.search(r"def appVersionTag = '([\d.]+)'", gra)
    vname = m_name.group(1) if m_name else '?'
    check('⑧ 版本自洽：appVersionName == appVersionTag（APK 文件名与 versionName 同源）',
          m_name is not None and m_tag is not None and m_name.group(1) == m_tag.group(1),
          'name=%s tag=%s' % (vname, m_tag.group(1) if m_tag else '?'))
    keep = 'XposedCompat.log("[DLsiteSoundFloat] ==== BUILD %s / code 1005");' % vname
    check('⑧ 常开横幅那一行是 code 1005、且 versionName 与 build.gradle 一致（装机前核这一行）',
          keep in mod, 'expected: %s' % keep)
    check('⑧ 常开横幅只有一行：旧的 code 1004 常开行已被替换（不是两行并存）',
          not re.search(r'XposedCompat\.log\("\[DLsiteSoundFloat\] '
                        r'==== BUILD [\d.]+ / code 1004"\);', mod))
    check('⑧ 常开横幅里不写具体文件名/被删标识符（横幅纪律，防负向锚自伤）',
          all(ch not in keep for ch in
              ('AudioPlaylist', 'ExoPlayerImpl', 'Duration', 'cuesBelongToRebuiltPlaylist')))
    seg = re.search(r'==== BUILD %s / code 1005 （([\s\S]*?)"\);' % re.escape(vname), mod)
    check('⑧ 调试段里的 code 1005 履历含四段（现象/根因/修法/连带）+ 尾部写明「版本号 2.3.0→2.3.1」',
          seg is not None and all(k in seg.group(1) for k in ('①', '②', '③', '④'))
          and '版本号 2.3.0→2.3.1' in seg.group(1))
    check('⑧ code 1004 的历史段仍在（履历是追加式、不覆盖；它的横幅保持当时的 2.3.0）',
          '==== BUILD 2.3.0 / code 1004 （' in mod)

    # ══════════════ 报告 ══════════════
    print()
    print('=' * 88)
    print('code 1005 —— 源码层验证（列表重建窗口 + 第三条认领路径 + 分流采集 + 静默例外）')
    print('=' * 88)
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
