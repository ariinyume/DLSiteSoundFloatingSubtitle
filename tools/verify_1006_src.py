#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1006_src.py —— code 1006 的**源码层**验证。

code 1006 修的是：**换作品时插件仍会短暂「无字幕」**（Ari 2026-10-10 22:53 截屏，
上一轮 code 1005 的同类问题第三例）。真机时间线（LSPosed_20261010_225318）：

    22:51:02.563  Loaded 73 cues —— 作品 RJ01126292（用户刚点下新作品）
    22:51:09.847  >>> track changed tc=7 dur=827472 idx=3 -> tc=5 dur=745032 idx=0
                  | lastJson=7284ms ago -> SUSPEND        → 空窗 17.9s
    22:52:11.455  Loaded 343 cues —— 作品 RJ01536846（同一作品三条音轨三个 json）
    22:52:13.009  statusMap tc=4 dur=0.0（新列表第一拍：非零 tc、时长仍为 0）
    22:52:17.875  >>> track changed tc=5 dur=1025040 idx=2 -> tc=4 dur=2371608 idx=2
                  | lastJson=4627ms ago -> SUSPEND        → 空窗 29.7s

两条旧判据各自的命门：
  · code 1004 的选择键 —— 换作品时列表整个重建，重建期取不到有效选择键；
  · code 1005 的重建窗口 —— ① 起点不是边沿：宿主同时轮询多个 `playbackState=ended`
    的幽灵实例，它们**永远**报 `trackCount=0`（本会话 22:50:46~22:53:23 共 222 条
    「playlist destroyed」，一秒 1~3 次）⇒ 窗口被反复误开、永不闭合；② 终点关得太早：
    新列表**第一拍**就报非零 `trackCount`（时长仍 0），而新作品 JSON 还在它之后 238ms。

本轮改用**作品键**这条正证据：字幕 JSON 的请求 URL 里带着作品路径
（`…/doujin/RJ01127000/RJ01126292/optimized/<hash>.json?Key-Pair-Id=…`），
同一部作品的每条音轨共用同一个路径、换作品必变。同时把重建窗口的口径改为
「起点=按实例记账的真边沿，终点=带时长的权威读数」。

⚠️ 为什么字节码层不够、必须再有源码层：见 verify_1004_src.py 的同一段说明 ——
   dex 层证得了「指令编进去了」，证不了「判据条件写得对、盖章时机对、开关窗互补」。

本脚本的每一条都只在**剥掉注释**后的源码上匹配（铁律 53）。

用法：
    python tools/verify_1006_src.py          （在仓库根执行，不接参数）
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
NET = os.path.join(ROOT, J, 'hook/NetworkHook.java')
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
    """读源码，并**把行尾统一成 LF** —— 判据不能被磁盘上的 CRLF/LF 风格左右（铁律 53/58）。"""
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
       `SubtitleRepository.getInstance().ownsCuesForXxx(...)` —— 写成 `(?<![\\w.])`
       会把它们**全部**漏掉，读数变成 `[]` 而断言当场假 FAIL（1005_src 第一次跑就是这么红的）。
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
    for p in (KEY, REPO, POS, SRC, NET, MOD, GRADLE):
        if not os.path.exists(p):
            print('找不到文件：%s（请在仓库根执行）' % p)
            return 2

    key = read(KEY)
    repo = read(REPO)
    pos = read(POS)
    src = read(SRC)
    net = read(NET)
    mod = read(MOD)
    gra = read(GRADLE)
    keyc, repoc, posc, srcc, netc = (strip_comments(x) for x in (key, repo, pos, src, net))

    # ══════════════ ① 纯解析层：PlaylistKey.workKeyOf ══════════════
    sig = 'public static String workKeyOf('
    check('① PlaylistKey 新增 workKeyOf（从 URL 取作品键）', sig in keyc)
    wk = norm(method_body(keyc, sig))
    check('① 单参签名（只吃 URL 字符串，无任何注入/上下文依赖）',
          norm(keyc).find('public static String workKeyOf(String url)') >= 0)
    check('① null 早退（调用方一律当「无证据」）',
          'if (url == null) { return null; }' in wk)
    check('① 第一步：砍掉 `?` 起的查询串（每次请求签名都不同，留着会把同一份 JSON 当成两部作品）',
          "int q = url.indexOf('?');" in wk
          and 'String s = q >= 0 ? url.substring(0, q) : url;' in wk)
    check('① 第二步：砍掉最后一个 `/` 之后的哈希文件名（同一作品的每条音轨文件名都不同）',
          "int slash = s.lastIndexOf('/');" in wk
          and 'String key = s.substring(0, slash);' in wk)
    check('① 退化输入判据：slash <= 0 ⇒ null；key 为空 ⇒ null（宁可「无证据」，绝不凭坏数据认领）',
          'if (slash <= 0) { return null; }' in wk
          and 'return key.isEmpty() ? null : key;' in wk)
    check('① 纯解析：方法体里不出现任何字段赋值 / Android 依赖（可在单测里直跑）',
          not re.search(r'\b(?:SystemClock|this\.|XposedCompat)\w*', wk)
          and 'url' in wk)

    # ══════════════ ② 纯判定层：PlaylistKey.cuesBelongToChangedWork ══════════════
    sig = 'public static boolean cuesBelongToChangedWork('
    check('② PlaylistKey 新增 cuesBelongToChangedWork（换作品的纯判定）', sig in keyc)
    body = norm(method_body(keyc, sig))
    check('② 五参签名：手上 cues 的作品键 / 待兑现的作品键 / 待兑现时刻 / 当前时刻 / 时限',
          norm(keyc).find(
              'cuesBelongToChangedWork(String cuesWorkKey, String workChangeKey, '
              'long workChangeAtMs, long nowMs, long toleranceMs)') >= 0)
    check('② 首个早退：手上这份 cues 没拿到 URL（null / 空）⇒ 不认领',
          'if (cuesWorkKey == null || cuesWorkKey.isEmpty()) { return false; }' in body)
    check('② 安全阀：必须**完全相等** —— 待兑现的作品键不是手上这份 cues 的 ⇒ 一律不认',
          'if (workChangeKey == null || !workChangeKey.equals(cuesWorkKey)) { return false; }' in body)
    check('② 第三道保险：待兑现时刻未知 / 时钟回退 ⇒ 不认领',
          'if (workChangeAtMs <= 0L || nowMs < workChangeAtMs) { return false; }' in body)
    check('② 时限：nowMs - workChangeAtMs <= toleranceMs（闭区间，超时放弃）',
          'return nowMs - workChangeAtMs <= toleranceMs;' in body)
    check('② 纯判定：方法体里不出现任何字段赋值 / Android 依赖',
          not re.search(r'\b(?:SystemClock|this\.)\w*', body) and 'nowMs' in body)
    check('② 两条新方法挨在一起（同一个 code 1006 段，便于审计）',
          keyc.index('public static String workKeyOf(')
          < keyc.index('public static boolean cuesBelongToChangedWork('))

    # ══════════════ ③ 数据层：四个字段 + 一个常量 ══════════════
    for name, init, kind in (('cuesWorkKey', 'null', 'String'),
                             ('lastSeenWorkKey', 'null', 'String'),
                             ('workChangeKey', 'null', 'String'),
                             ('workChangeAtMs', '0L', 'long')):
        check('③ 字段 volatile %s %s = %s（无锁读，去抖闸门会高频问它）' % (kind, name, init),
              re.search(r'private\s+volatile\s+%s\s+%s\s*=\s*%s\s*;' % (kind, name, re.escape(init)),
                        repoc) is not None)
    check('③ 认领时限常量 WORK_CHANGE_CLAIM_MS = 15000L（真机实测提前量 4.6~7.3s，留足余量）',
          'private static final long WORK_CHANGE_CLAIM_MS = 15000L;' in repoc)
    check('③ 历史基线 lastSeenWorkKey 的注释写明「硬裁决**不**清」（它与 cuesWorkKey 语义不同）',
          'private volatile String lastSeenWorkKey = null;' in repoc
          and '不随硬裁决清空' in repo)

    # ══════════════ ④ 盖章：workKey 从 Hook 一路贯通到 loadFromJsonArrayInternal ══════════════
    check('④ 旧入口 loadFromJson(String) 保留并转发为 workKey = null（老调用点零改动）',
          re.search(r'public void loadFromJson\(String json\) \{ loadFromJson\(json, null\); \}',
                    norm(repoc)) is not None)
    check('④ 新入口 loadFromJson(String, String) 存在（带作品键）',
          'public void loadFromJson(String json, String workKey)' in repoc)
    check('④ loadFromJsonArray 同样有两参/三参两个入口',
          re.search(r'public void loadFromJsonArray\(JSONArray webvtt\) \{ '
                    r'loadFromJsonArray\(webvtt, null\); \}', norm(repoc)) is not None
          and 'public void loadFromJsonArray(JSONArray webvtt, String workKey)' in repoc)
    check('④ 新入口把 workKey 传进内部实现（不是收了参数不用）',
          'loadFromJsonArrayInternal(webvtt, workKey);' in norm(repoc))
    check('④ 内部实现的签名带第二参 workKey',
          'private void loadFromJsonArrayInternal(JSONArray webvtt, String workKey)' in repoc)
    stamp = norm(repoc)
    check('④ 盖章条件：只有拿到非空 workKey 才动这三个字段（拿不到 URL 的路径不冲历史基线）',
          'if (workKey != null && !workKey.isEmpty()) {' in stamp)
    check('④ 换作品的判据：本次作品键 != 历史基线 ⇒ 记下待兑现（这是「取字幕动作跨了作品」的唯一解释）',
          'if (lastSeenWorkKey != null && !lastSeenWorkKey.equals(workKey)) {' in stamp
          and 'workChangeKey = workKey;' in stamp
          and 'workChangeAtMs = SystemClock.uptimeMillis();' in stamp)
    check('④ 顺序正确：先判「换没换」（用旧基线），再更新基线，最后盖 cues 印章',
          stamp.find('!lastSeenWorkKey.equals(workKey)')
          < stamp.find('lastSeenWorkKey = workKey;')
          < stamp.find('cuesWorkKey = workKey;'))
    check('④ 三枚印章都在同一次加锁加载里：1004 选择键印章 → 1006 作品键印章',
          stamp.find('cuesLoadedAheadOfIdentity = PlaylistKey.selectionAheadOfIdentity(')
          < stamp.find('workChangeKey = workKey;'))
    check('④ 盖章带一条可取证日志（含 code 1006 标记）',
          '[code 1006] subtitle json came from ANOTHER' in repoc)

    # ══════════════ ⑤ 一次性：三处清空 ══════════════
    res = norm(method_body(repoc, 'private boolean tryResumeFromCache('))
    check('⑤ 认领成功 ⇒ 落下「待兑现的换作品」（一次性，免得被后面的无关换轨重复兑现）',
          'workChangeKey = null;' in res and 'workChangeAtMs = 0L;' in res)
    check('⑤ 落下发生在 synchronized(lock) 块内（与撤销挂起/隔离同一临界区）',
          res.index('synchronized (lock)') < res.index('workChangeKey = null;'))
    track = norm(method_body(
        repoc, 'public void onTrackChanged(String where, boolean indexVerified, long newIdentity,'))
    check('⑤ 「刚加载过 JSON ⇒ 保留」这条平静路径同样落下它（那份 cues 显然已是新轨的）',
          re.search(r'if \(ago <= PRELOAD_TOLERANCE_MS\) \{[\s\S]*?workChangeKey = null;'
                    r'[\s\S]*?\}', track) is not None)
    hard = norm(method_body(repoc, 'private void resolvePendingTrack('))
    check('⑤ 硬裁决销毁数据时同步清掉作品键三件套（与主人印章同生共死）',
          'cuesWorkKey = null;' in hard and 'workChangeKey = null;' in hard
          and 'workChangeAtMs = 0L;' in hard)
    check('⑤ 硬裁决的清理与既有印章清理挨在一起（不散落）',
          hard.find('cuesArrivedDuringRebuild = false;') < hard.find('cuesWorkKey = null;'))
    check('⑤ 历史基线 lastSeenWorkKey 在硬裁决里**不**被清（清了三处里没有它）',
          'lastSeenWorkKey = null;' not in hard)

    # ══════════════ ⑥ 查询方法：ownsCuesForChangedWork ══════════════
    own = norm(method_body(repoc, 'public boolean ownsCuesForChangedWork('))
    check('⑥ ownsCuesForChangedWork 把五个参数原样转给纯判定层',
          own.find('PlaylistKey.cuesBelongToChangedWork( cuesWorkKey, workChangeKey, '
                   'workChangeAtMs, nowMs, WORK_CHANGE_CLAIM_MS);') >= 0)
    check('⑥ 认领查询是无锁读（不拿 lock，去抖闸门会高频调它）',
          'synchronized' not in own)
    check('⑥ 注释写清与另外三条的分工（四条互不替代）',
          '四条互不替代' in repo)

    # ══════════════ ⑦ tryResumeFromCache：第四条认领路径 ══════════════
    check('⑦ 第四条路径声明且真的接了查询方法（不是写了变量没用）',
          'boolean byWorkChange = ownsCuesForChangedWork(SystemClock.uptimeMillis());' in res)
    check('⑦ 四条路径缺失时一律 return false（任一成立才继续）',
          'if (!byIdentity && !bySelection && !byRebuild && !byWorkChange) { return false; }' in res)
    check('⑦ 日志 code 四分支：980 / 1004 / 1005 / 1006',
          'byIdentity ? "[code 980]" : bySelection ? "[code 1004]"' in res
          and ': byRebuild ? "[code 1005]" : "[code 1006]"' in res)
    check('⑦ code 1006 的 why 说清「这份 json 来自与上一份不同的作品 ⇒ 它就是新选中作品的」',
          'came from a DIFFERENT work than the previous json' in res
          and 'so it is the newly selected work' in res)
    check('⑦ code 1006 的 how 说清「点击那一刻就已为新作品取回，无需再等」',
          'json was fetched for another work at click time, no need to wait' in res)

    # ══════════════ ⑧ Hook 层：真边沿 + 权威读数关窗 ══════════════
    check('⑧ PlayerSourceHook 新增按实例记账的表 + 容量上限 + 一次性告警旗标',
          'private static final java.util.LinkedHashMap<String, Integer> sLastTcByListId =' in srcc
          and 'private static final int MAX_TRACKED_LIST_IDS = 8;' in srcc
          and 'private static volatile boolean sLoggedMissingListId = false;' in srcc)
    check('⑧ 新入口签名升为四参（多带实例 id）',
          'public static void onPlaylistSelectionFromStatusMap(int trackCount, int idx, String listId,' in srcc)
    entry = norm(method_body(srcc, 'public static void onPlaylistSelectionFromStatusMap('))
    check('⑧ 真边沿判据：只有**同一个实例自己**从表里被摘掉时才算「列表被销毁」',
          'realEdge = listId != null && sLastTcByListId.remove(listId) != null;' in entry)
    check('⑧ 真边沿用 synchronized 保护（static 表 + 多线程 statusMap 回调）',
          'synchronized (sLastTcByListId)' in entry)
    check('⑧ 开窗只在真边沿上发生（全局 sLastNonZeroTrackCount 仍作「本会话见过活跃列表」兜底）',
          'if (sLastNonZeroTrackCount > 0) {' in entry and 'if (realEdge) {' in entry)
    check('⑧ 开窗日志带上实例 id（取证时能分辨是哪一份列表被销毁）',
          re.search(r'repo\.notePlaylistRebuilt\(where \+ " id=" \+ listId\);', entry) is not None)
    check('⑧ 宿主没给 `id` 时只提示一次、且**不开窗**（退回 1005 的保守行为）',
          'else if (listId == null && !sLoggedMissingListId) {' in entry
          and 'sLoggedMissingListId = true;' in entry)
    check('⑧ 非零读数时按 LRU 上限记账，再推选择键（顺序：记账在前、推送在后）',
          entry.find('sLastTcByListId.put(listId, trackCount);')
          < entry.find('repo.noteObservedPlaylistSelection(PlaylistKey.makeSelection(trackCount, idx));')
          and 'sLastTcByListId.size() >= MAX_TRACKED_LIST_IDS' in entry)
    check('⑧ 关窗职责落在**带时长的权威读数**上（onPlaylistStateFromStatusMap 里调 notePlaylistLoaded）',
          'SubtitleRepository.getInstance().notePlaylistLoaded();' in srcc
          and srcc.index('noteObservedPlaylistIdentity(identity)')
          < srcc.index('notePlaylistLoaded()'))
    check('⑧ 关窗调用在 onPlaylistStateFromStatusMap 之内（不是别处）',
          'notePlaylistLoaded();'
          in norm(method_body(srcc, 'public static boolean onPlaylistStateFromStatusMap(')))

    # ══════════════ ⑨ 采集端：PlayerPositionHook 把实例 id 传下去 ══════════════
    posn = norm(posc)
    check('⑨ PlayerPositionHook 从状态 Map 取 `id`（宿主给每个 AudioPlaylist 的 UUID）',
          'Object idObj = m.get("id");' in posn
          and 'String listId = idObj instanceof String ? (String) idObj : null;' in posn)
    check('⑨ 取 id 后按四参调用（旧的三参调用已彻底消失）',
          'PlayerSourceHook.onPlaylistSelectionFromStatusMap(trackCount, curIdx, listId, where);' in posn
          and 'onPlaylistSelectionFromStatusMap(trackCount, curIdx, where)' not in posc)
    check('⑨ 推送条件仍是 `isPlaylistMap && !halted && curIdx >= 0`（本轮未改判定本身）',
          re.search(r'if \(isPlaylistMap && !halted && curIdx >= 0\) \{', posn) is not None)
    check('⑨ 推送点仍在权威门之前（按源码位置：推送 < `if (!authoritative)` 早退）',
          posn.find('onPlaylistSelectionFromStatusMap')
          < posn.find('if (!authoritative)'))

    # ══════════════ ⑩ 网络层：把 URL 里的作品键带进数据层 ══════════════
    check('⑩ NetworkHook 已 import PlaylistKey',
          'import io.github.ariinyume.dlsitesoundfloat.data.PlaylistKey;' in net)
    check('⑩ 只有 `.json` 请求才推作品键（音频/图片流没有作品语义）',
          'String workKey = urlLc.contains(".json") ? PlaylistKey.workKeyOf(url) : null;' in net)
    check('⑩ build.peek 主通道把 workKey 传下去',
          'submit(text, repo, "build.peek", workKey);' in net)
    check('⑩ submit 签名升为四参并转给 loadFromJson(text, workKey)',
          'private static void submit(String text, SubtitleRepository repo, String via, String workKey)' in net
          and 'repo.loadFromJson(text, workKey);' in net)
    check('⑩ 其余三条通道显式传 null（拿不到 URL ⇒ 明确「无证据」，不是忘记传）',
          len(re.findall(r'submit\((?:text|decodeUtf8\(data, 0, data\.length\)), repo, via, null\);',
                         netc)) >= 3)

    # ══════════════ ⑪ 去抖闸门：静默例外扩到第四条 ══════════════
    settle = norm(method_body(srcc, 'private static boolean settleChange('))
    check('⑪ 例外问满四处：owners（980）/ selection（1004）/ rebuilt（1005）/ changedWork（1006）',
          'ownsCuesFor(to)' in settle
          and 'ownsCuesForSelection(toSelection)' in settle
          and 'ownsCuesForRebuiltPlaylist(to, now)' in settle
          and 'ownsCuesForChangedWork(now)' in settle)
    check('⑪ 四处用 || 连成同一个 ours 判据（任一条成立即放行）',
          re.search(r'boolean ours = [^;]*ownsCuesFor[^;]*ownsCuesForSelection[^;]*'
                    r'ownsCuesForRebuiltPlaylist[^;]*ownsCuesForChangedWork[^;]*;',
                    settle) is not None)
    check('⑪ 放行后仍让静默窗失效（sNotifiedMs = 0L）—— 铁律 51 的既有收口没被改掉',
          'sNotifiedMs = 0L;' in settle)

    # ══════════════ ⑫ 不变量：调用点实参个数 ══════════════
    a_push = arg_arities(posc, 'onPlaylistSelectionFromStatusMap')
    check('⑫ 不变量：onPlaylistSelectionFromStatusMap 的调用点恰好 4 参（实例 id 一个都不能漏传）',
          a_push == [4], '实参个数 %s' % a_push)
    a_load = arg_arities(netc, 'loadFromJson')
    check('⑫ 不变量：NetworkHook 里 loadFromJson 只有带作品键的那一个调用点（不存在漏传的老路）',
          a_load == [2], '实参个数 %s' % a_load)
    a_sub = arg_arities(netc, 'submit')
    check('⑫ 不变量：submit 的调用点全部 4 参（新旧通道口径一致）',
          len(a_sub) >= 4 and set(a_sub) == {4}, '实参个数 %s' % a_sub)
    a_own = arg_arities(srcc, 'ownsCuesForChangedWork')
    check('⑫ 不变量：ownsCuesForChangedWork 的调用点恰好 1 参（只吃当前时刻）',
          a_own == [1], '实参个数 %s' % a_own)
    a_settle = arg_arities(srcc, 'settleChange')
    check('⑫ 不变量：settleChange 的每个调用点都恰好 4 个实参（既有口径没被本轮改坏）',
          len(a_settle) >= 2 and set(a_settle) == {4}, '实参个数 %s' % a_settle)

    # ══════════════ ⑬ 负向锚：旧世界的东西必须彻底消失 ══════════════
    check('⑬ 负向：旧的三参调用 onPlaylistSelectionFromStatusMap(tc, idx, where) 全仓消失',
          'onPlaylistSelectionFromStatusMap(trackCount, curIdx, where)' not in (posc + srcc))
    check('⑬ 负向：三参定义 onPlaylistSelectionFromStatusMap(int, int, String) 已不存在',
          not re.search(r'onPlaylistSelectionFromStatusMap\(int trackCount, int idx, '
                        r'String where\)', srcc + posc))
    check('⑬ 负向：不再有「任何一次 trackCount 归零都开窗」的旧写法（全局判据已降级为兜底）',
          'if (sLastNonZeroTrackCount > 0) { repo.notePlaylistRebuilt(' not in norm(srcc))
    check('⑬ 负向：无 where 版本的开窗调用 `notePlaylistRebuilt(where)` 已消失',
          'notePlaylistRebuilt(where)' not in srcc)
    check('⑬ 负向：noteObservedPlaylistSelection 不再碰重建窗口（关窗职责已移交）',
          'playlistRebuiltAtMs' not in norm(
              method_body(repoc, 'public void noteObservedPlaylistSelection(')))

    # ══════════════ ⑭ 版本号 + 横幅 + 履历 ══════════════
    m_code = re.search(r'def appVersionCode = (\d+)', gra)
    m_name = re.search(r"def appVersionName = '([\d.]+)'", gra)
    m_tag = re.search(r"def appVersionTag = '([\d.]+)'", gra)
    vcode = m_code.group(1) if m_code else '?'
    vname = m_name.group(1) if m_name else '?'
    check('⑭ build.gradle: appVersionCode = 1006（Ari 本轮：只升 code、不升版本号）',
          m_code is not None and vcode == '1006', 'code=%s' % vcode)
    check('⑭ versionName / versionTag 仍是 2.3.1（本轮刻意不动）',
          m_name is not None and m_tag is not None
          and m_name.group(1) == '2.3.1' and m_tag.group(1) == '2.3.1',
          'name=%s tag=%s' % (vname, m_tag.group(1) if m_tag else '?'))
    check('⑭ appVersionCodeLabel 保持 1008.25 不动',
          "def appVersionCodeLabel = '1008.25'" in gra)
    keep = 'XposedCompat.log("[DLsiteSoundFloat] ==== BUILD %s / code 1006");' % vname
    check('⑭ 常开横幅那一行是 code 1006、且 versionName 与 build.gradle 一致（装机前核这一行）',
          keep in mod, 'expected: %s' % keep)
    check('⑭ 常开横幅只有一行：code 1005 的常开行已被替换（不是两行并存）',
          not re.search(r'XposedCompat\.log\("\[DLsiteSoundFloat\] '
                        r'==== BUILD [\d.]+ / code 1005"\);', mod))
    check('⑭ 常开横幅里不写具体文件名/被删标识符（横幅纪律，防负向锚自伤）',
          all(ch not in keep for ch in
              ('AudioPlaylist', 'ExoPlayerImpl', 'Duration', 'workKeyOf',
               'cuesBelongToChangedWork', 'sLastTcByListId')))
    seg = re.search(r'==== BUILD %s / code 1006 （([\s\S]*?)"\);' % re.escape(vname), mod)
    check('⑭ 调试段里的 code 1006 履历含四段（现象/根因/修法/连带）',
          seg is not None and all(k in seg.group(1) for k in ('①', '②', '③', '④')))
    check('⑭ 履历尾部写明「版本号 2.3.1 不变、code 1005 升 1006」',
          seg is not None and '版本号 2.3.1 不变、code 1005 升 1006' in seg.group(1))
    check('⑭ 履历里的真机数字与日志一致（提前量 7284 / 4627ms、误开窗 222 条、空窗 17.9 / 29.7s）',
          seg is not None and all(k in seg.group(1)
                                  for k in ('7284', '4627', '222', '17.9', '29.7')))
    check('⑭ code 1005 的历史段仍在（履历追加式、不覆盖；它的横幅保持 2.3.1）',
          '==== BUILD 2.3.1 / code 1005 （' in mod)

    # ══════════════ 报告 ══════════════
    print()
    print('=' * 88)
    print('code 1006 —— 源码层验证（作品键正证据 + 按实例记账的真边沿 + 权威读数关窗）')
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
