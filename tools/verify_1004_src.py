#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_1004_src.py —— code 1004 的**源码层**验证。

code 1004 修的是：**点击未缓存音频，App 显示有字幕，但插件显示「无字幕」**。
根因是「两个键的可见时机不同」—— 曲目序号在切轨瞬间就翻，带时长的身份键要等新音源
装载完才有值，而新轨的字幕 JSON 恰恰落在中间。本脚本把修法逐条写成断言。

⚠️ 为什么字节码层不够，必须再有源码层：
  · dex 层能证「选择键那几条指令编进包了」，**证不了**「它算得对」——
    换一个运算符（`+` 写 `-`、`*` 写 `/`）编译照样绿灯、字节码照样在，
    但真机上选择键永远撞不上 ⇒ 修了个寂寞。
  · dex 层能证「入口在权威门之前」（按指令偏移），**证不了**「入口的调用条件是
    当初设计的那个」—— 条件写宽（漏 `!halted`）会把幽灵列表的序号也推下去。
  · 「纯判定层零状态」「盖章顺序」「转发链一条不缺」这类**结构不变量**，
    编译绿灯、dex 全在，但行为可能整个反了。

本脚本的每一条都只在**剥掉注释**后的源码上匹配（铁律 53：涉及源码文本的判据
先归一化再匹配 —— `PlaylistKey.java` 的类注释里就写着 `duration`，
不剥注释的话「选择键与时长无关」这条会当场假 FAIL）。

用法：
    python tools/verify_1004_src.py          （在仓库根执行，不接参数）
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
    return io.open(p, encoding='utf-8', newline='').read()


def strip_comments(src):
    """剥掉块注释与行注释 —— 判据必须在「只有代码」的文本上匹配（铁律 53）。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def method_body(src, sig):
    """按花括号配平抽一个方法的完整函数体（缩进正则不可靠）。

    sig 用「在源码里唯一」的片段即可（例如 'private void foo('）。
    """
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
    """按顶层逗号切实参（跳过泛型/嵌套括号里的逗号）。

    ⚠️ 字符串字面量必须先剥掉：日志文本里带着 `->`（如
       `where + " playlist " + from + " -> " + desc`），
       其中的 `>` 会被当成泛型右尖括号 ⇒ 深度掉到 -1 ⇒ 后面所有逗号都不再算分隔符
       ⇒ 一个 5 实参的调用被数成 2 实参（真踩过，读数 [2,5,…] 变 [2,2,…]）。
    """
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
    """取 `name(...)` 的**所有调用点**实参个数（**不含**方法定义处）。

    ⚠️ 三个坑（前两个都真踩过）：
      ① 非贪婪 `[^;]*?` 会从定义处的 `) {` 一路跨到下一条语句的 `)`，
         于是「定义」被当成一个「6 实参的调用」⇒ 必须配平括号，别用长度糊。
      ② 以 `);` 结尾会把 `if (!name(...)) {` 这类**双右括号**的调用点整条漏掉
         （真机上就是这条把 settleChange 的两个调用点全漏了，读数 `[]`）。
      ③ 定义处前面紧邻返回类型（`boolean` / `void` …），据此显式排除。
    """
    out = []
    for m in re.finditer(r'(?<![\w.])%s\(' % re.escape(name), src):
        prefix = src[max(0, m.start() - 30):m.start()]
        if re.search(r'\b(?:boolean|void|int|long|double|float|String|Object)\s+$', prefix):
            continue           # 这是方法定义，不是调用
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
    gradle = read(GRADLE)

    keyc = strip_comments(key)
    repoc = strip_comments(repo)
    posc = strip_comments(pos)
    srcc = strip_comments(src)

    # ══════════════ ① PlaylistKey —— 零状态纯判定层 ══════════════
    check('新类 PlaylistKey 存在且为 final 工具类', 'public final class PlaylistKey' in keyc)
    check('私有构造 ⇒ 不可实例化', 'private PlaylistKey()' in keyc)
    check('★ 零 Android 依赖（无 import android / no android.* 引用）',
          'android' not in keyc)
    # 零状态：除了三个 private static final 常量，不得有任何其它字段
    fields = re.findall(r'\b(?:private|public|protected|static|final|volatile|\s)+[\w<>\[\]]+\s+(\w+)\s*(?:=|;)',
                        keyc)
    consts = re.findall(r'private static final long (\w+)\s*=', keyc)
    check('★ 零状态：类内字段恰为三个 private static final long 常量',
          sorted(consts) == ['IDENTITY_IDX_SCALE', 'IDENTITY_TC_SCALE', 'SELECTION_TC_SCALE']
          and len(fields) == len(consts), '字段=%s 常量=%s' % (fields, consts))
    check('选择键与**时长无关**（剥注释后不出现 duration）', 'duration' not in keyc)

    for name, val, why in (
        ('SELECTION_TC_SCALE', '100000L', '选择键里 trackCount 的权重'),
        ('IDENTITY_IDX_SCALE', '1000L', '身份键里 idx 的低位掩码（须与 makeIdentity 对齐）'),
        ('IDENTITY_TC_SCALE', '1000000000000L', '身份键里 trackCount 的权重'),
    ):
        check('%s = %s（%s）' % (name, val, why),
              re.search(r'private static final long %s\s*=\s*%s\s*;' % (name, val), keyc)
              is not None)
    # 常量隔离：选择键权重 ×2 ≤ 身份键 idx 掩码？不必；但选择键必须**不重叠**：
    check('选择键权重(1e5) 与身份键权重(1e12) 不同 ⇒ 两种键不可互相冒充',
          '100000L' in keyc and '1000000000000L' in keyc)

    body = strip_comments(method_body(key, 'public static long makeSelection('))
    check('makeSelection：非法入参（trackCount<=0 或 idx<0）返回 0',
          'trackCount <= 0 || idx < 0' in body and 'return 0L' in body)
    check('★ makeSelection 算术 = (long) trackCount * SELECTION_TC_SCALE + idx',
          '(long) trackCount * SELECTION_TC_SCALE + idx' in body)
    check('makeSelection 先做 long 提升（避免 int 溢出）', '(long) trackCount' in body)

    body = strip_comments(method_body(key, 'public static boolean selectionAheadOfIdentity('))
    check('selectionAheadOfIdentity：两侧任一未知 ⇒ false',
          'selection <= 0L || identity <= 0L' in body and 'return false' in body)
    check('★ selectionAheadOfIdentity：trackCount 不同 ⇒ false（跨作品不算「跑在前面」）',
          'selectionTrackCount(selection) != identityTrackCount(identity)' in body)
    check('★ selectionAheadOfIdentity：同列表内序号不同才算 true',
          'selectionIndex(selection) != identityIndex(identity)' in body)

    body = strip_comments(method_body(key, 'public static boolean cuesBelongToTarget('))
    check('cuesBelongToTarget：三项合取（跑在前面 + 目标非未知 + 选择键相等）',
          'loadedAheadOfIdentity' in body and 'targetSelection > 0L' in body
          and 'targetSelection == cuesOwnerSelection' in body
          and body.count('&&') >= 2)

    # ══════════════ ② 数据层 —— 印章、认领、转发链 ══════════════
    for name, init in (('observedPlaylistSelection', '0L'),
                       ('cuesOwnerSelection', '0L'),
                       ('cuesLoadedAheadOfIdentity', 'false')):
        check('新字段 volatile %s = %s（无锁读，闸门会高频问它）' % (name, init),
              re.search(r'private\s+volatile\s+\w+\s+%s\s*=\s*%s\s*;' % (name, re.escape(init)),
                        repoc) is not None)

    body = strip_comments(method_body(repo, 'public void noteObservedPlaylistSelection('))
    stmts = [s.strip() for s in body[body.index('{') + 1:body.rindex('}')].split(';')
             if s.strip()]
    check('★ noteObservedPlaylistSelection 是**纯写入**（体内只有一句赋值，无任何副作用）',
          len(stmts) == 1 and stmts[0] == 'observedPlaylistSelection = selection',
          '体内语句 %d 条：%s' % (len(stmts), stmts))
    check('noteObservedPlaylistSelection 是 public', 'public void noteObservedPlaylistSelection' in repoc)

    body = strip_comments(method_body(repo, 'public boolean ownsCuesForSelection('))
    check('ownsCuesForSelection 委托 PlaylistKey.cuesBelongToTarget 且**无锁**',
          'PlaylistKey.cuesBelongToTarget(' in body and 'synchronized' not in body)
    check('ownsCuesForSelection 传的是 cuesLoadedAheadOfIdentity + cuesOwnerSelection',
          'cuesLoadedAheadOfIdentity' in body and 'cuesOwnerSelection' in body)

    # 盖章顺序（加载 JSON 那一刻）：主人印章 → 选择键印章 → 「选择跑在身份前面」旗标
    load = strip_comments(method_body(repo, 'private void loadFromJsonArrayInternal('))
    i_id = load.find('cuesOwnerIdentity = observedPlaylistIdentity')
    i_sel = load.find('cuesOwnerSelection = observedPlaylistSelection')
    i_ahead = load.find('cuesLoadedAheadOfIdentity = PlaylistKey.selectionAheadOfIdentity(')
    check('★ 盖章顺序：cuesOwnerIdentity → cuesOwnerSelection → cuesLoadedAheadOfIdentity',
          i_id >= 0 and i_sel > i_id and i_ahead > i_sel,
          'id@%d sel@%d ahead@%d' % (i_id, i_sel, i_ahead))
    check('★ 旗标由 selectionAheadOfIdentity(观测选择, 观测身份) 算出（不是常量）',
          'selectionAheadOfIdentity(\n                    observedPlaylistSelection, observedPlaylistIdentity)'
          in load or 'selectionAheadOfIdentity(' in load
          and 'observedPlaylistSelection, observedPlaylistIdentity' in load)
    check('旗标在加载时与 cues 内容同生命周期（loadFromJsonArrayInternal 内）', i_ahead > 0)

    safe = strip_comments(method_body(repo, 'private boolean tryResumeFromCache('))
    check('tryResumeFromCache 签名升为三参 (where, newIdentity, newSelection)',
          'tryResumeFromCache(String where, long newIdentity, long newSelection)' in repoc)
    check('★ tryResumeFromCache 保留 byIdentity（code 980 反向切轨，一字未改）',
          'newIdentity != 0L && newIdentity == cuesOwnerIdentity' in safe)
    check('★ tryResumeFromCache 新增 bySelection（code 1004 认领）',
          'boolean bySelection = ownsCuesForSelection(newSelection);' in safe)
    check('两条路径都认不出时立即返回 false（不进挂起）',
          'if (!byIdentity && !bySelection)' in safe and 'return false;' in safe)
    check('★ 日志码按路径区分：byIdentity ⇒ [code 980]，否则 ⇒ [code 1004]',
          'byIdentity ? "[code 980]" : "[code 1004]"' in safe)
    check('认领日志含真机可核对的证据字段（cues / pos / RESUME from cache）',
          'RESUME from cache' in safe and '" | cues="' in safe and '" | pos="' in safe)
    check('★ 无 cues 时不认领（印章与数据同生同灭）',
          'if (cueCount == 0)' in safe and 'return false' in safe)
    check('认领时撤销挂起/软裁决/上一轨隔离并把静默窗作废',
          'pendingTrackDecision = false' in safe and 'softNoSubtitles = false' in safe
          and 'previousTrackCuesHidden = false' in safe and 'pendingToken++' in safe)

    four = strip_comments(method_body(
        repo, 'public void onTrackChanged(String where, boolean indexVerified, long newIdentity,'))
    check('★ 四参版第一件事就是 tryResumeFromCache（认领优先于挂起）',
          four.index('tryResumeFromCache(where, newIdentity, newSelection)')
          < four.index('SystemClock.uptimeMillis()'))
    for sig, arg in (('public void onTrackChanged(String where) {', 'onTrackChanged(where, false, 0L);'),
                     ('public void onTrackChanged(String where, boolean indexVerified) {',
                      'onTrackChanged(where, indexVerified, 0L);'),
                     ('public void onTrackChanged(String where, boolean indexVerified, long newIdentity) {',
                      'onTrackChanged(where, indexVerified, newIdentity, 0L);')):
        check('转发链：%s ⇒ %s' % (sig.split('(')[0].strip(), arg),
              sig in repoc and arg in repoc)
    check('★ 老调用点行为一字未变：不传身份/选择键时一律补 0L（0 = 不认识）',
          all(s in repoc for s in ('onTrackChanged(where, false, 0L);',
                                   'onTrackChanged(where, indexVerified, 0L);',
                                   'onTrackChanged(where, indexVerified, newIdentity, 0L);')))
    # ★ 结构不变量：**新键必须加进所有门控判据**（漏一层 = 该路径上认领永远不发生）。
    #   做法 = 逐个调用点数实参，而不是「源码里出现过某个字符串」——
    #   后者在「新加了一个调用点却忘了传选择键」时照样绿（code 1003 的教训）。
    a_settle = arg_arities(srcc, 'settleChange')
    check('★ 不变量：settleChange 的每个调用点都恰好 4 个实参（新键一个都不能漏传）',
          a_settle == [4, 4], '实参个数 %s' % a_settle)
    # 5 个调用点：老信号路径 3 处（2 参，如实传不出选择键）+ 权威列表路径 1 处（5 参）
    # + 两参转发壳自己的方法体 1 处（5 参，见 notifyTrackChanged 的定义）。
    a_notify = arg_arities(srcc, 'notifyTrackChanged')
    check('★ 不变量：notifyTrackChanged 的每个调用点要么 5 参（带选择键）要么 2 参（老路径补 0L）',
          a_notify == [2, 5, 2, 2, 5] and all(a in (2, 5) for a in a_notify),
          '实参个数 %s' % a_notify)

    hard = strip_comments(method_body(
        repo, 'private void resolvePendingTrackDecisionLocked(')) \
        if 'private void resolvePendingTrackDecisionLocked(' in repoc else repoc
    check('★ 硬裁决销毁 cues 时同步清掉两枚新印章（cuesOwnerSelection / cuesLoadedAheadOfIdentity）',
          'cuesOwnerIdentity = 0L;' in hard and 'cuesOwnerSelection = 0L;' in hard
          and 'cuesLoadedAheadOfIdentity = false;' in hard)

    # ══════════════ ③ 位置钩子 —— 推送必须在权威门之前 ══════════════
    cons = strip_comments(method_body(pos, 'private static void consume(')) \
        if 'private static void consume(' in posc else posc
    i_push = cons.find('PlayerSourceHook.onPlaylistSelectionFromStatusMap(')
    i_gate = cons.find('if (!authoritative)')
    check('★ 源码层：选择键推送写在权威门 `if (!authoritative)` **之前**',
          i_push >= 0 and i_gate >= 0 and i_push < i_gate,
          'push@%d gate@%d' % (i_push, i_gate))
    check('推送条件 = 是列表 + 有曲目 + 未作废 + 序号可读（4 个条件一个不少）',
          'isPlaylistMap && trackCount > 0 && !halted && curIdx >= 0' in cons)
    check('★ curIdx 只读一次并被后面的 ④ 段复用（避免同一帧两次取值不一致）',
          pos.count('m.get("currentIndex")') == 1 and cons.count('curIdx') >= 3,
          'm.get 次数=%d curIdx 用点=%d' % (pos.count('m.get("currentIndex")'), cons.count('curIdx')))
    # ★ 负向锚：④ 段进入换轨判据的**门**必须与旧写法逐字等价。
    #   起草时曾顺手把它收成 `curIdx >= 0`（更「整洁」），但那是本轮**没被要求的行为改动**：
    #   负序号会被旧写法当成 idx=0 参与判据、进而可能触发一次换轨通知 —— 与本轮要修的正是
    #   「别拿脏读数当换轨」直接相关，所以更不能顺手改。⇒ 还原为 `idxObj instanceof Number`。
    #   （已取证：23 个会话 / 240,183 条 currentIndex 读数里负序号 0 例；但**不靠这个前提**保安全。）
    check('★ 负向：④ 段仍按旧写法判 `idxObj instanceof Number`（换轨判据零行为改动）',
          'if (idxObj instanceof Number)' in cons
          and 'onPlaylistStateFromStatusMap(curIdx, trackCount, durSec, where);' in cons)
    check('★ 负向：④ 段没有被收紧成 `curIdx >= 0`（那是未要求的行为改动）',
          cons.count('curIdx >= 0') == 1,
          '`curIdx >= 0` 出现 %d 次（应为 1 次：只在 ④-前 的推送守卫里）' % cons.count('curIdx >= 0'))
    check('halted 在权威门判定处已声明（供 ④-前 复用，作用域上提）',
          'boolean halted = false;' in cons)

    # ══════════════ ④ 源钩子 —— 新入口 + 静默例外 + 传参 ══════════════
    check('PlayerSourceHook 已 import PlaylistKey',
          'import io.github.ariinyume.dlsitesoundfloat.data.PlaylistKey;' in src)
    push = strip_comments(method_body(
        src, 'public static void onPlaylistSelectionFromStatusMap('))
    check('★ 新入口只推不判（体内不出现 notifyTrackChanged / settleChange）',
          'notifyTrackChanged' not in push and 'settleChange' not in push)
    check('新入口把 makeSelection(trackCount, idx) 交给 noteObservedPlaylistSelection',
          'noteObservedPlaylistSelection(' in push
          and 'PlaylistKey.makeSelection(trackCount, idx)' in push)
    check('★ 新入口无 where 也不发日志（高频调用点，不得刷屏）',
          'XposedCompat.log' not in push and 'dbg(' not in push)

    check('settleChange 签名升为四参（带上 toSelection）',
          'private static boolean settleChange(long from, long to, long toSelection, long now)'
          in srcc)
    st = strip_comments(method_body(
        src, 'private static boolean settleChange(long from, long to, long toSelection, long now)'))
    check('★ 静默例外同时问 ownsCuesFor 与 ownsCuesForSelection（两代认领都放行）',
          'ownsCuesFor(to)' in st and 'ownsCuesForSelection(toSelection)' in st
          and re.search(r'boolean ours = [\s\S]{0,200}?\|\|[\s\S]{0,200}?;', st) is not None)
    check('放行后让静默窗失效（否则紧接的反向同对会被再静默 20s）',
          'sNotifiedMs = 0L;' in st)
    check('★ 未认领时仍然照旧静默（return false，行为一字未变）',
          'suppressed (same pair within' in st and 'return false;' in st)

    state = strip_comments(method_body(
        src, 'public static boolean onPlaylistStateFromStatusMap('))
    check('★ 权威状态 Map 路径算出了 selection 并交给 notifyTrackChanged（不是补 0L）',
          'long selection = PlaylistKey.makeSelection(trackCount, idx);' in state
          and re.search(r'notifyTrackChanged\(null,[^;]*selection\);', state) is not None)
    check('★ settleChange 调用点也带上了 selection（闸门才能认出「新轨的字幕」）',
          'settleChange(sLastIdentity, identity, selection, now)' in srcc)
    check('老信号路径（无 trackCount）如实传 0L = 不认识',
          'settleChange(last, idx, 0L, now)' in srcc)

    check('notifyTrackChanged 两参转发壳仍在 ⇒ 参数链完整',
          'notifyTrackChanged(repo, where, false, 0L, 0L);' in srcc)
    nt = strip_comments(method_body(
        src, 'private static void notifyTrackChanged(SubtitleRepository repo, String where,\n'
             '                                           boolean indexVerified, long identity,'))
    check('★ notifyTrackChanged 把 selection 一路透传给数据层四参版',
          'target.onTrackChanged(where, indexVerified, identity, selection);' in nt)
    check('★ 负向：旧的 (repo, where, boolean, long) 四参重载已删净',
          not re.search(r'notifyTrackChanged\(SubtitleRepository repo, String where,\s*'
                        r'boolean indexVerified, long identity\)', srcc))

    # ══════════════ ⑤ 版本四处 + 横幅纪律 ══════════════
    check('appVersionCode = 1004', re.search(r"def appVersionCode = 1004\b", gradle) is not None)
    check("appVersionCodeLabel = '1008.25'",
          re.search(r"def appVersionCodeLabel = '1008\.25'", gradle) is not None)
    check("appVersionName = '2.3.0'",
          re.search(r"def appVersionName = '2\.3\.0'", gradle) is not None)
    check("appVersionTag = '2.3.0'",
          re.search(r"def appVersionTag = '2\.3\.0'", gradle) is not None)
    check('常开横幅 = BUILD 2.3.0 / code 1004（版本核验锚点）',
          'XposedCompat.log("[DLsiteSoundFloat] ==== BUILD 2.3.0 / code 1004");' in mod)
    banners = re.findall(r'XposedCompat\.log\("\[DLsiteSoundFloat\] ==== BUILD ([^"]*)"',
                         mod)
    check('★ 常开横幅只有一行（历史段全部归入调试开关）', len(banners) == 1, str(banners))
    check('常开横幅里不写具体文件名/被删标识符（横幅纪律，防负向锚自伤）',
          all(ch not in banners[0] for ch in ('AudioPlaylist', 'ExoPlayerImpl', 'Duration')))
    seg1004 = re.search(r'==== BUILD 2\.3\.0 / code 1004 （([\s\S]*?)"\);', mod)
    check('调试段里的 code 1004 履历含四段（现象/根因/修法/连带）',
          seg1004 is not None
          and all(k in seg1004.group(1) for k in ('①', '②', '③', '④')))

    # ══════════════ 报告 ══════════════
    print()
    print('=' * 88)
    print('code 1004 —— 源码层验证（选择键 + 认领路径 + 权威门之前推送 + 静默例外）')
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
