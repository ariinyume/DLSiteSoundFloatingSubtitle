#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键跑全部 verify_*.py，汇总 PASS/FAIL 表格。

设计约束（照项目铁律来，不自创）：
  · **退出码有意义**：任一脚本 FAIL ⇒ 本脚本退出码非 0 ⇒ CI/钩子能直接用。
  · **不吞错**：脚本不存在/崩溃/超时，都要单独报出来，不能混成「FAIL」了事。
  · **只读**：本脚本只调用子脚本，不改任何文件。

用法：
    python tools/run_all_verify.py            # 跑全部
    python tools/run_all_verify.py 995 protocol   # 只跑名字含这些关键字的
    python tools/run_all_verify.py --list     # 只列清单不执行

⚠️ 为什么要它：
    项目已有多个 verify_*.py，但每个都是「手工单跑一次」。
    改了码想全量回归时，得逐个敲命令、逐个看输出、逐个判断有没有 FAIL ——
    这是「验证链断裂」的经典形态：跑了，但没人保证全跑了。
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# 脚本 → (类别, 一句话说明它验什么)
# ⚠️ 说明必须与脚本内部判据一致；改判据时同步改这里，否则索引表本身就会骗人。
SCRIPTS = [
    ('verify_1006_src.py', '源码层',
     'code 1006 作品键正证据（workKeyOf 两步纯解析 + cuesBelongToChangedWork 五参判定）+ '
     '第四条认领路径 + 按实例 id 记账的真边沿 + 权威读数关窗 + 静默例外扩到四处 + '
     '实参个数不变量（87 项）'),
    ('verify_1006.py', 'dex 字节码层',
     'code 1006：workKeyOf/五参判定/四字段/两新签名是否编进包、常量 15000 与 8、'
     'LinkedHashMap 真边沿、四处静默、负向锚（旧三参入口消失）（79 项 / 45 有区分力锚）'),
    ('verify_1005_src.py', '源码层',
     'code 1005 列表重建窗口（开窗/关窗/盖章互补）+ 第三条认领路径 + 采集点分流 + '
     '静默例外扩到四处 + 实参个数不变量（54 项）'),
    ('verify_1005.py', 'dex 字节码层',
     'code 1005：六参纯判定/三字段/两方法是否编进包、常量 15000、四枚印章写入顺序、'
     '采集入口 if-gtz 分流、静默三处、按**指令偏移**证推送早于权威门（50 项 / 28 有区分力锚）'),
    ('verify_1004_src.py', '源码层',
     'code 1004 选择键纯判定层 + 认领路径（四条）+ 权威门之前推送 + 静默例外（四处）+ '
     '实参个数不变量 + 版本自洽（72 项）'),
    ('verify_1004.py', 'dex 字节码层',
     'code 1004：PlaylistKey/三字段/三方法是否编进包、签名升级、按**指令偏移**证推送早于权威门（49 项）'),
    ('verify_1003_src.py', '源码层',
     'code 1003 独立卡片 + 四滑条范围/默认 + 删净采样 + 冷白光圈 + 可达性不变量（74 项）'),
    ('verify_1003.py', 'dex 字节码层',
     'code 1003：新增卡片/滑条文案、删净采样链路（反向锚）、常量层与冷白光圈（50 项）'),
    ('verify_1001_src.py', '源码层',
     'code 1001 调参可达性不变量：applyPanelBackground 消费的键必须都被判据覆盖（21 项）'),
    ('verify_1001.py', 'dex 字节码层',
     'code 1001 滑条可达性修复：retunePanelGlass 是否真被 refreshStyle 调用（17 项，5 锚）'),
    ('verify_1000_src.py', '源码层',
     '默认值映射：复算公式验证「默认配置 == 历史观感」+ 接线完整性（30 项）'),
    ('verify_1000.py', 'dex 字节码层',
     'code 1000 明暗可调：两个新键/新字段/新setter 是否编进包（16 项，9 个有区分力锚）'),
    ('verify_999.py', 'dex 字节码层',
     'code 999 启动竞态修复 + 样式回退：RemoteConfig 重读与常量值（14 项，8 个有区分力锚）'),
    ('verify_998.py', 'dex 字节码层',
     'code 998 液态玻璃样式回退：常量值回到 996 与 G7 保留（11 项，6 个有区分力锚）'),
    ('verify_997.py', 'dex 字节码层',
     'code 997 亮度锁定 + 第5批重构：常量值变化与 PokeThrottle 调用（11 项，7 个有区分力锚）'),
    ('verify_996.py', 'dex 字节码层',
     'code 996 第4批重构：作用域探测是否真搬到 ScopeWatcher（27 项，20 个有区分力锚）'),
    ('verify_995.py', 'dex 字节码层',
     'code 995 六项改动是否真编进 APK（28 项，22 个有区分力锚）'),
    ('verify_protocol_src.py', '源码层',
     'Protocol 单点真源：action/extra 字面量仅存在于 Protocol，转发壳完好（10 项）'),
    ('verify_994.py', 'dex 字节码层',
     'code 994 的 PageFollowPolicy 是否真编进 APK（6 个区分力锚）'),
    ('verify_994_log_window.py', '日志层',
     '994 真机日志窗口归一化脚本（防止跨窗口比条数出假回归）'),
]

# 每个 dex 脚本期望的 code 号：(主验 code, 对照 code 或 None)
# ⚠️ 不能「按文件名取最新两个」—— 那样会把 995 当成 993 的对照，判据必假失败。
#   必须是脚本自己声明期望的 code，再精确匹配。
# ⚠️ 只有**真正解析 APK/dex** 的脚本才登记在这里。
#   不接包的脚本（如源码层校验、真机日志校验）不要登记，否则会被误判成「缺参数」。
NEEDS_APK = {
    'verify_1006.py': (1006, 1005),   # <apk_1006> <apk_1005>
    'verify_1005.py': (1005, 1004),   # <apk_1005> <apk_1004>
    'verify_1004.py': (1004, 1003),   # <apk_1004> <apk_1003>
    'verify_1003.py': (1003, 1002),   # <apk_1003> <apk_1002>
    'verify_1001.py': (1001, 1000),   # <apk_1001> <apk_1000>
    'verify_1000.py': (1000, 999),   # <apk_1000> <apk_999>
    'verify_999.py': (999, 997),   # <apk_999> <apk_997>
    'verify_998.py': (998, 997),   # <apk_998> <apk_997>
    'verify_997.py': (997, 996),   # <apk_997> <apk_996>
    'verify_996.py': (996, 995),   # <apk_996> <apk_995>
    'verify_995.py': (995, 994),   # <apk_995> <apk_994>
    'verify_994.py': (994, 993),   # <apk_994> <apk_993>
}

TIMEOUT = 300  # 秒；单个 dex dump 脚本实测 10~60s

# 【code 1001】环境侧「批量删除保护」会**从外部中断**命令（Python 捕不到）。
#   实测：同一轮里累计删除到 50 个文件后，`verify_994.py` / `verify_995.py`
#   这类**跑完会清临时 dump** 的脚本会在清理处被整条命令掐断 ⇒ exit=1。
#   ⚠️ 这不是判据失败 —— 脚本自己的 `FAIL n/m` 汇总行**根本没来得及打印**。
#   ⇒ 只有「带该标记 **且** 没有判据汇总行」才判 ENVBLOCK（环境受限，不计入失败）。
ENV_BLOCK_MARKER = 'SAFE_DELETE_BULK_CONFIRM_REQUIRED' 


def find_apks(repo):
    """扫出所有 `DLsiteFloat-*-codeNNN-debug.apk`，按 code 号建索引。

    ⚠️ 口径（照铁律 42）：搜索范围 = 仓库构建输出目录 + 工作区根 + releases/legacy；
    同code 号若多处存在，**取路径最短的那个**（构建目录优先，其次工作区根，最后归档），
    因为归档里的包可能已被后续轮次取代。
    """
    pat = re.compile(r'DLsiteFloat-.*-code(\d+)-debug\.apk$')
    cands = {}
    roots = [
        os.path.join(repo, 'app', 'build', 'outputs', 'apk', 'debug'),
        os.path.normpath(os.path.join(repo, '..')),   # 工作区根
        os.path.join(repo, 'releases', 'legacy'),
    ]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                m = pat.match(f)
                if not m:
                    continue
                code = int(m.group(1))
                p = os.path.join(dirpath, f)
                prev = cands.get(code)
                if prev is None or len(p) < len(prev):
                    cands[code] = p
    return cands


def build_argv(name, repo):
    """给需要 APK 的脚本按其期望 code 精确配包。

    返回 (args, missing)：
      · 不接包参数的脚本 → `([], [])`   **空列表，不是 None**
      · 缺主验包→ `([], [code])`
      · 缺对照包   → `([主验包], [对照code])`
    ⚠️ 必须用空列表当「不接参数」的信号。若返回 None，会被调用方当成「缺包」⇒ 误报 SKIP。
    """
    if name not in NEEDS_APK:
        return [], []
    want_main, want_old = NEEDS_APK[name]
    cands = find_apks(repo)
    missing = []
    args = []
    if want_main not in cands:
        # 【踩坑】主验包都没有时**必须整体跳过** —— 绝不能把「只凑到的对照包」单独
        #   传进去：脚本会因参数不足打印用法并 exit 2，被误判成判据 FAIL。
        #   （实测：code 998 的包被后续 clean 构建清掉后，verify_998 就这样假 FAIL。）
        return [], [want_main]
    args.append(cands[want_main])
    if want_old is not None:
        if want_old in cands:
            args.append(cands[want_old])
        else:
            missing.append(want_old)
    return args, missing


def discover():
    """兜底：把目录里 SCRIPTS 未登记的 verify_*.py 也捞出来，提醒补登记。"""
    on_disk = {f for f in os.listdir(HERE)
               if f.startswith('verify_') and f.endswith('.py')}
    registered = {s[0] for s in SCRIPTS}
    return sorted(on_disk - registered)


def run_one(name, repo):
    path = os.path.join(HERE, name)
    if not os.path.exists(path):
        return 'MISSING', 0, 0.0, '文件不存在'

    argv = [sys.executable, path]
    extra, missing = build_argv(name, repo)
    if not extra:
        # 没配到包。真需要包的脚本 ⇒ 缺包；不接包的脚本 ⇒ extra 为空但missing 也为空
        if missing:
            return 'SKIP', 0, 0.0, '未找到 code %s 的 APK' % missing
        out_extra = ''
    elif missing:
        # 缺对照包：主验仍可跑（对照类判据会被判为未命中）
        out_extra = '（缺 code %s 的对照包，部分判据将被判为未命中）' % missing
    else:
        out_extra = ''
    argv += extra

    t0 = time.time()
    try:
        # cwd 设成仓库根：脚本里普遍用相对路径读 APK / dex
        p = subprocess.run(argv, cwd=repo,
                           capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=TIMEOUT)
        dt = time.time() - t0
        out = (p.stdout or '') + (p.stderr or '') + ('\n' + out_extra if out_extra else '')
        if p.returncode == 0:
            # ⚠️ 兜底网（code 1001 加的）：**退出码 0 但一点输出都没有 ⇒ 可疑**。
            #   实测踩过 —— 把 `def lum_of()` 插进了 `main()` 体内，等于把 main 从中间
            #   截断，剩下的语句全变成不可达 ⇒ `main()` 返回 None ⇒ `sys.exit(None)` = 0，
            #   而**一行都不打印**。这种「静默通过」比 FAIL 更危险（看着全绿、其实没验）。
            #   ⇒ 只靠退出码是不够的：**没有输出 = 没有断言**。
            if not out.strip():
                return 'SUSPECT', p.returncode, dt, '退出码 0 但零输出（脚本可能没真正执行）'
            return 'PASS', p.returncode, dt, out
        # exit 2 且是 usage 形态 ⇒ 参数问题，不算判据失败
        if p.returncode == 2 and 'usage' in out.lower():
            return 'SKIP', p.returncode, dt, out
        # 环境批量删除保护把命令掐断 ⇒ 判据没跑完，不算判据失败
        if ENV_BLOCK_MARKER in out and not re.search(r'^\s*FAIL \d+/', out, re.M):
            return 'ENVBLOCK', p.returncode, dt, \
                out + '\n[环境拦截：临时文件清理被批量删除保护拦下，判据未跑完]'
        return 'FAIL', p.returncode, dt, out
    except subprocess.TimeoutExpired:
        return 'TIMEOUT', -9, time.time() - t0, '超过 %ds 未结束' % TIMEOUT
    except Exception as e:                      # noqa: BLE001
        return 'ERROR', -1, time.time() - t0, '%s: %s' % (type(e).__name__, e)


def verdict_line(out):
    """从脚本输出里抓它自己的结论行 —— 优先读脚本自报值，不自己重算。

    ⚠️ 各脚本结论格式不统一（实测），故多pattern 匹配：
      verify_995   → 「通过 28/28，有区分力锚 28 个」
      verify_994   → 「全部通过：20/20」
      protocol_src → 「PASS 10/10」
      log_window   → 「全部通过：8/8」
    """
    pats = [
        r'通过\s*(\d+)\s*/\s*(\d+)，有区分力锚\s*(\d+)',
        r'^\s*(?:全部通过|PASS)\s*[:：]?\s*(\d+)\s*/\s*(\d+)',
        r'^\s*失败\s*(\d+)\s*/\s*共\s*(\d+)',
        r'\bPASS\s+(\d+)\s*/\s*(\d+)',
    ]
    for p in pats:
        m = re.search(p, out, re.M)
        if m:
            if m.lastindex and m.lastindex >= 3:
                return '%s/%s（锚%s）' % (m.group(1), m.group(2), m.group(3))
            return '%s/%s' % (m.group(1), m.group(2))
    return ''


def main(argv):
    if '--list' in argv:
        print('登记的验证脚本（共 %d 个）' % len(SCRIPTS))
        for n, cat, desc in SCRIPTS:
            print('  %-26s [%-10s] %s' % (n, cat, desc))
        extra = discover()
        if extra:
            print('\n⚠️ 以下 verify_*.py 未登记到本脚本，建议补登记：')
            for n in extra:
                print('  %s' % n)
        return 0

    keys = [a for a in argv if not a.startswith('-')]
    targets = [s for s in SCRIPTS if not keys or any(k in s[0] for k in keys)]
    if not targets:
        print('没有匹配到脚本。关键字：%s' % (keys or '(空= 全部)'))
        return 2

    print('=' * 78)
    print('一键验证：跑 %d 个脚本' % len(targets))
    print('=' * 78)

    repo = os.path.dirname(HERE)
    rows = []
    for name, cat, desc in targets:
        print('\n>>> %s  [%s]  %s' % (name, cat, desc))
        print('-' * 78)
        status, rc, dt, out = run_one(name, repo)
        tail = out.strip().split('\n')
        print('\n'.join(tail[-14:] if len(tail) > 14 else tail))
        print('-' * 78)
        print('  ⇒ %s  (%.1fs, exit=%d) %s'
              % (status, dt, rc, ('[' + verdict_line(out) + ']') if verdict_line(out) else ''))
        rows.append((name, cat, status, rc, dt, verdict_line(out)))

    # ---- 汇总表 ----
    print()
    print('=' * 78)
    print('汇总')
    print('=' * 78)
    print('%-28s %-12s %-8s %-8s %s' % ('脚本', '类别', '结果', '用时', '判据'))
    for name, cat, status, rc, dt, v in rows:
        print('%-28s %-12s %-8s %-8s %s' % (name, cat, status, '%.1fs' % dt, v or '-'))

    # ⚠️ 失败名单是**白名单**式（FAIL/ERROR/TIMEOUT/MISSING）⇒ 新增的 ENVBLOCK
    #    天然不会计入失败；但必须在"全绿"行里显眼列出来，不能悄悄吞掉。
    bad = [r for r in rows if r[2] in ('FAIL', 'ERROR', 'TIMEOUT', 'MISSING')]
    skipped = [r for r in rows if r[2] == 'SKIP']
    envblocked = [r for r in rows if r[2] == 'ENVBLOCK']
    print()
    if bad:
        print('❌ 未全绿：%d/%d 个脚本未通过' % (len(bad), len(rows)))
        for n, _, st, rc, _, _ in bad:
            print('   - %s  (%s, exit=%d)' % (n, st, rc))
        rc_ret = 1
    else:
        ran = len(rows) - len(skipped) - len(envblocked)
        bits = []
        if skipped:
            bits.append('%d 个跳过：%s' % (len(skipped), ', '.join(r[0] for r in skipped)))
        if envblocked:
            bits.append('%d 个被环境拦截：%s'
                        % (len(envblocked), ', '.join(r[0] for r in envblocked)))
        print('✅ 全绿：%d/%d%s' % (ran, ran, ('（另有 ' + '；'.join(bits) + '）') if bits else ''))
        rc_ret = 0
    if skipped:
        print('⚠️ 跳过 %d 个（参数/环境不足，不计入失败）：%s'
              % (len(skipped), ', '.join(r[0] for r in skipped)))
    if envblocked:
        print('⚠️ 被环境拦截 %d 个（临时文件清理撞上批量删除保护 ⇒ 判据未跑完，'
              '**不是判据失败**；同一轮里别把本脚本连跑超过两次）：%s'
              % (len(envblocked), ', '.join(r[0] for r in envblocked)))

    extra = discover()
    if extra:
        print()
        print('⚠️ 以下 verify_*.py 未登记（本次没跑）：%s' % ', '.join(extra))
    return rc_ret


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))