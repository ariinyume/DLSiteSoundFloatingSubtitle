# -*- coding: utf-8 -*-
"""code 993：从app/build.gradle 提取履历块 → CHANGELOG.md。

取证结论（这些数字是取证得来的，别再"顺手改"）：
  · 履历块内共**38** 条履历。37 条前缀为 '//    【…】'，另有 1 条前缀误写成 '// 【…】'
    （少一格空格），但它本身是一条真实且重要的履历（code 978 判据换根）。
    ⇒ 块尾定位必须放宽到「行首是【」，否则 code 978 那条会被切在块外丢失。
  · 踩过的坑：正则'^//\\s?' 只吃 1 个空白，剩 3 个缩进 → 行首匹配全失配、38 条塌成 1 条。
    必须用 '^//\\s*'（贪婪吃全部缩进）。

用法：python tools/extract_changelog.py   （在仓库根执行）
"""
import io, os, re, sys

GRADLE = os.path.join('app', 'build.gradle')
OUT    = 'CHANGELOG.md'
EXPECT_N = 38
HEAD_KEY  = '0921.1'
TAIL_KEY  = '2.2.14g'
MUST_HAVE = '1007.6'      # code 978：判据换根，漏了这条等于丢了最重要的历史

lines = io.open(GRADLE, encoding='utf-8').read().split('\n')

def is_lede(l):
    return l.startswith('//') and l.lstrip('/').lstrip().startswith('【')

start = next(i for i, l in enumerate(lines) if is_lede(l))
end   = max(i for i, l in enumerate(lines) if is_lede(l))
tail  = end + 1
while tail < len(lines):
    t = lines[tail].strip()
    if t and not t.startswith('//'):
        break
    tail += 1
sys.stderr.write('履历块 L%d..L%d\n' % (start + 1, tail))

items, cur = [], None
for l in lines[start:tail]:
    assert l.startswith('//'), repr(l)
    body = re.sub(r'^//\s*', '', l).rstrip()
    if not body.strip():
        continue
    m = re.match(r'^【([^】]*)】\s*(.*)$', body)
    if m:
        cur = {'key': m.group(1).strip(), 'title': m.group(2).strip(), 'body': []}
        items.append(cur)
    elif cur is not None:
        cur['body'].append(body)

assert len(items) == EXPECT_N, '期望 %d 条，实际 %d —— 停手' % (EXPECT_N, len(items))
assert items[0]['key'] == HEAD_KEY, items[0]['key']
assert items[-1]['key'].startswith(TAIL_KEY), items[-1]['key']
assert any(it['key'] == MUST_HAVE for it in items), 'code 978 那条丢了'
sys.stderr.write('断言全过：%d 条 / 首 %s / 尾 %s / 含 %s\n'
                 % (len(items), HEAD_KEY, TAIL_KEY, MUST_HAVE))

out = [
 '# DLsiteFloat 更新日志',
 '',
 '> 本文件由 `app/build.gradle` 的履历块**自动搬出**（code 993 架构重构）。',
 '> 条目正文一字未改，只重排版并清掉原注释前缀；详细根因取证见 `docs/修复说明/`。',
 '>',
 '> ⚠️ `app/build.gradle` 只保留最近 3 条摘要 + 指向本文件的指针，**不再承担历史档案**。',
 '> 重新生成：python tools/extract_changelog.py',
 '',
 '本项目采用 [语义化版本](https://semver.org/lang/zh-CN/)；',
 '`versionCode` 为**发版前最后修改日期的 MMDD**（同日多次发版递增末位）。',
 '',
 '---',
 '',
]
for it in items:
    head = it['title'] if it['title'].startswith(it['key']) else '%s %s' % (it['key'], it['title'])
    out.append('### %s' % head.strip())
    out.append('')
    out.extend([x for x in it['body'] if x.strip()])
    out.append('')

io.open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
sys.stderr.write('已写入 %s（%d 行）\n' % (OUT, len(out)))
