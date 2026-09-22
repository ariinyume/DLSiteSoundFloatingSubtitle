#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
版本号 code 生成器 —— Ari 的规则（2026-09-20 起）

规则：版本 code = **发版前（以 push 到 git 为准）最后修改日期的 MMDD**。
      例：9 月 20 日发版 → 0920；10 月 3 日 → 1003。

为什么 code 用「裸整数 920」而不是写 `0920`：
  app/build.gradle 里 `def appVersionCode = 0920` 会被 Groovy 按**前导零八进制**
  或类型歧义解析（0920 里 9 非法八进制 → 编译报错/意外值）。
  所以 build.gradle 写 `920`，可读语义（0920）另放 appVersionCodeLabel。

用法：
  # 看当前 HEAD 的日期会算出什么 code（不写入）
  python version_code.py --show

  # 按「最后一次 commit 日期」写入 build.gradle
  python version_code.py --apply

  # 手动指定日期（如你打算明天发版）
  python version_code.py --apply --date 2026-10-03

  # 指定仓库路径
  python version_code.py --show --repo F:/path/to/repo
"""

import argparse
import datetime
import io
import os
import re
import subprocess
import sys

GIT = r"C:/Users/ly/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin/git.exe"
DEFAULT_REPO = r"F:/WorkBuddyCache/DLSiteSound_Plus/DLsiteSound_FloatSubtitle"


def last_commit_date(repo):
    """取最后一次 commit 的**作者日期**（本地时区），返回 date。"""
    env = dict(os.environ)
    for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
        env.pop(k, None)
    out = subprocess.run(
        [GIT, "-C", repo, "log", "-1", "--format=%ad", "--date=format:%Y-%m-%d"],
        capture_output=True, text=True, env=env,
    )
    if out.returncode != 0:
        # 退路：直接用工作区最新文件 mtime（git 不可用时）
        newest = 0.0
        for root, _dirs, files in os.walk(repo):
            if ".git" in root or "build" in root.split(os.sep):
                continue
            for f in files:
                if f.endswith((".java", ".gradle", ".xml", ".list", ".prop")):
                    newest = max(newest, os.path.getmtime(os.path.join(root, f)))
        if newest:
            return datetime.date.fromtimestamp(newest)
        raise SystemExit("git 与文件 mtime 都拿不到日期：%s" % out.stderr.strip())
    return datetime.date.fromisoformat(out.stdout.strip())


def code_from_date(d):
    """2026-09-20 → (920, '0920')。"""
    mmdd = d.strftime("%m%d")
    return int(mmdd), mmdd      # int('0920') == 920


def read_current(repo):
    p = os.path.join(repo, "app", "build.gradle")
    src = io.open(p, encoding="utf-8").read()
    m = re.search(r"def\s+appVersionCode\s*=\s*(\d+)", src)
    return p, src, (m.group(1) if m else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--date", help="手动指定 YYYY-MM-DD（默认取最后 commit 日期）")
    ap.add_argument("--apply", action="store_true", help="写入 app/build.gradle")
    ap.add_argument("--show", action="store_true", help="只显示，不写入（默认）")
    a = ap.parse_args()

    d = datetime.date.fromisoformat(a.date) if a.date else last_commit_date(a.repo)
    code, label = code_from_date(d)
    path, src, cur = read_current(a.repo)

    print("仓库        : %s" % a.repo)
    print("源日期      : %s" % d.isoformat())
    print("目标 code   : %s  (数值 %d)" % (label, code))
    print("当前 code   : %s" % cur)

    if not a.apply:
        print("\n（--show 模式，未写入。加 --apply 落地）")
        return 0

    new = src
    new = re.sub(r"(def\s+appVersionCode\s*=\s*)\d+",
                 r"\g<1>%d" % code, new, count=1)
    if "appVersionCodeLabel" in new:
        new = re.sub(r"(def\s+appVersionCodeLabel\s*=\s*')[^']*(')",
                     r"\g<1>%s\g<2>" % label, new, count=1)
    else:
        new = re.sub(r"(def\s+appVersionCode\s*=\s*\d+\n)",
                     r"\g<1>def appVersionCodeLabel = '%s'\n" % label,
                     new, count=1)
    if new == src:
        print("无需改动（已一致）")
        return 0
    io.open(path, "w", encoding="utf-8", newline="\n").write(new)
    print("\n✅ 已写入 %s → code=%s" % (path, label))
    return 0


if __name__ == "__main__":
    sys.exit(main())
