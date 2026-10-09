#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【2.2.14 / code 995】Protocol 的**源码层**单点真源验证

为什么要有这个脚本
------------------
`verify_995.py`（dex 层）能证明「Protocol 类编进包了、9 个 action 字面量齐全」，
但**证明不了**「旧两处已不再各自写一份字面量」——
因为 `public static final String X = Protocol.ACTION_X;` 会被 **javac 编译期常量内联**，
dex 里每个类的常量池都各自留了一份，字节码层看不到转发关系（实测两版都查不到 Protocol 引用）。

于是分工明确：
  · dex 层（verify_995.py）      —— 证「编进包了」；
  · 源码层（本脚本）            —— 证「只有Protocol 一处定义字面量」。

这是编译器设计，不是缺陷。内联之后源码改Protocol 的值，javac 仍会把新的字面量
内联到每个转发点，所以**运行期语义仍然正确**，只是常量池里有多份副本。

验什么
------
1. 全仓（除 Protocol.java）**不再出现**任何 `io.github.ariinyume.dlsitesoundfloat.action.` 字面量
2. 全仓（除 Protocol.java）**不再出现**宿主/SystemUI 包名字面量
3. Protocol.java 里 9 个 action **齐全**且都在（防「搬走时漏了一个」）
4. Protocol.java 里 extra key 与两个 tag 值齐全
5. ConfigBus / StatusBarSubtitleBridge 的转发壳**必须仍存在**（删了就断调用点）

用法
----
    python verify_protocol_src.py [src_root]
退出码 0 = 全部通过
"""

import os
import re
import sys

SRC_DEFAULT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'app', 'src', 'main', 'java')

ACTION_PREFIX = 'io.github.ariinyume.dlsitesoundfloat.action.'
ALL_ACTIONS = [
    'CONFIG_CHANGED', 'RESTART_SYSUI', 'SCOPE_HOST_PING', 'SCOPE_HOST_PONG',
    'STATUSBAR_SUBTITLE_LINE', 'STATUSBAR_SUBTITLE_ENABLED',
    'STATUSBAR_SUBTITLE_DISMISS_REQUEST', 'STATUSBAR_SCOPE_PING', 'STATUSBAR_SCOPE_PONG',
]
ALL_EXTRAS = ['build', 'tag', 'host_alive', 'config_json', 'reason',
              'line', 'enabled', 'duration_ms', 'playing', 'pong_build']
PKG_LITERALS = ['jp.co.eisys.dlsitesound', 'com.android.systemui']

PROTOCOL_REL = os.path.join('config', 'Protocol.java')


def strip_javadoc_and_comments(text):
    """去掉块注释与行注释，**保留字符串字面量内容**。

    ⚠️ 关键：不能像处理一般代码那样把字符串字面量整体替换掉 ——
    本脚本要查的**就是**字符串字面量（action 名/ extra key / 包名），
    剥掉它们等于把要找的东西删了。

    为什么仍要剥注释：Protocol 的 Javadoc 里**故意**写了那些字面量作示例
    （如「code 950 换包名时这串前缀跟着改过」），不剥会把注释当成第二份定义
    —— 那正是铁律 33 的同类误报。
    """
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'//[^\n]*', '', text)
    return text


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else SRC_DEFAULT
    if not os.path.isdir(root):
        print('源码目录不存在: %s' % root)
        return 2

    files = []
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if fn.endswith('.java'):
                files.append(os.path.join(dp, fn))
    files.sort()

    proto_path = None
    others = []
    for p in files:
        # ⚠️ relpath 含完整包路径前缀（io\github\...\config\Protocol.java），
        #    所以按**尾部**匹配，不要拿整条 relpath 去比。
        rel = os.path.relpath(p, root)
        if rel.endswith(PROTOCOL_REL):
            proto_path = p
        else:
            others.append((rel, p))   # (相对路径, 绝对路径) —— 两个都要用
    if proto_path is None:
        print('找不到 %s' % PROTOCOL_REL)
        return 2

    proto_raw = open(proto_path, encoding='utf-8').read()
    proto = strip_javadoc_and_comments(proto_raw)
    # action 在 Protocol 里是拼接形式： ACTION_PREFIX + "CONFIG_CHANGED"
    # ⇒ 判据要查 "CONFIG_CHANGED" 这个**短名**，不是全串。
    proto_actions = proto

    total = 0
    fails = []

    def check(desc, ok, extra=''):
        nonlocal total
        total += 1
        print('  %s %s%s' % ('OK ' if ok else 'FAIL', desc,
                             ('  ' + extra) if extra else ''))
        if not ok:
            fails.append(desc)

    print('源码根: %s' % root)
    print('java 文件数: %d' % len(files))
    print()

    print('=== 1. 字面量单点真源：除 Protocol 外不应再出现 ===')
    dup_action = []
    dup_pkg = []
    for rel, p in others:
        body = strip_javadoc_and_comments(open(p, encoding='utf-8').read())
        for a in ALL_ACTIONS:
            if (ACTION_PREFIX + a) in body:
                dup_action.append('%s: %s' % (rel, a))
        for pk in PKG_LITERALS:
            if '"%s"' % pk in body:
                dup_pkg.append('%s: %s' % (rel, pk))
    check('action 字面量仅存在于 Protocol（他处 0 处）',
          not dup_action, '重复: %s' % (dup_action or '无'))
    check('包名字面量仅存在于 Protocol（他处 0 处）',
          not dup_pkg, '重复: %s' % (dup_pkg or '无'))

    print()
    print('=== 2. Protocol 定义完整性（防搬漏）===')
    miss_a = [a for a in ALL_ACTIONS if '"%s"' % a not in proto_actions]
    check('9 个 action 全在 Protocol 定义',
          not miss_a, '缺: %s' % (miss_a or '无'))
    miss_e = [e for e in ALL_EXTRAS if '"%s"' % e not in proto]
    check('10 个 extra key 全在', not miss_e, '缺: %s' % (miss_e or '无'))
    check('PROTOCOL_VERSION 已定义', 'PROTOCOL_VERSION' in proto)
    check('ACTION_PREFIX 已定义', 'ACTION_PREFIX' in proto)

    print()
    print('=== 3. 转发壳必须仍在（删了就断 105 处调用点）===')
    for rel, cls in ((os.path.join('config', 'ConfigBus.java'), 'ConfigBus'),
                     (os.path.join('util', 'StatusBarSubtitleBridge.java'),
                      'StatusBarSubtitleBridge')):
        hit = [(r, ap) for r, ap in others if r.endswith(rel)]
        ok = bool(hit)
        check('%s 文件存在' % cls, ok, rel)
        if ok:
            body = strip_javadoc_and_comments(open(hit[0][1], encoding='utf-8').read())
            n_fwd = len(re.findall(r'=\s*Protocol\.[A-Z_]+', body))
            check('%s 有 ≥6 个常量转发自 Protocol' % cls, n_fwd >= 6,
                  '实际 %d 个' % n_fwd)

    print()
    print('=' * 70)
    if fails:
        print('FAIL %d/%d' % (len(fails), total))
        for f in fails:
            print('  - %s' % f)
        return 1
    print('PASS %d/%d' % (total, total))
    return 0


if __name__ == '__main__':
    sys.exit(main())