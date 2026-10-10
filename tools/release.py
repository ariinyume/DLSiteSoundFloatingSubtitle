#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DLsiteFloat 双仓发版脚本（源仓库 + LSPosed 上架仓库）

固化 2026-10-10 血泪教训：
  GitHub Releases 页侧栏按「tag 指向的 commit 的 committer date」排序，
  不是 published_at、也不是 created_at。所以复用旧 commit 打 tag ⇒ 新版会被
甩到旧版下面（2.3.0 曾因此排在 2.2.0 之下）。
  正解：给每个 tag 造一个「日期正确」的空提交，再 force 重打 tag。

用法
  # 侦察（不写任何东西）
  python tools/release.py --code 1003 --version 2.3.0 --dry-run

  # 真发（会force 重打 tag，必须显式 --yes）
  python tools/release.py --code 1003 --version 2.3.0 \
      --apk "F:/Downloads/DLsiteFloat-2.3.0-code1003-debug.apk" \
      --notes _rel234/notes-2.3.0.md --yes

铁律（务必保持）
  1. 上架仓库 owner 是 Xposed-Modules-Repo，不是 ariinyume。
  2. 源仓库 tag = v<version>；上架仓库 tag = <code>-<version>（十进制 code，不是 label）。
  3. git 走 -c http.proxy（Clash 7897）；gh 走 API 直连，不要给 gh 带代理。
  4. push 后必须 fetch 验证，push 退出码不可信。
  5. force 重打 tag 前先fetch 侦察，且必须 --yes。
  6. 发布后两侧 + 本地三处 sha256 逐字节核对。
"""

import argparse
import datetime as dt
import hashlib
import os
import re
import shutil
import subprocess
import sys

SRC_REPO = "ariinyume/DLSiteSoundFloatingSubtitle"
XP_REPO = "Xposed-Modules-Repo/io.github.ariinyume.dlsitesoundfloat"

# Clash Verge 默认混合端口；MEM_release.md 记载「端口会变，先扫再用」
PROXY_PORTS = [7897, 7890, 10809, 1080, 59219]

GH = r"C:\Program Files\GitHub CLI\gh.exe"
if not os.path.exists(GH):
    _g = shutil.which("gh")
    GH = _g or "gh"

OK, BAD, WARN, INFO = "[OK]", "[!!]", "[--]", "[..]"


def say(tag, msg):
    print(f"{tag} {msg}", flush=True)


def run(cmd, cwd=None, env=None, check=True, capture=True):
    """执行命令。check=False 时返回 (code, stdout, stderr) 不抛异常。"""
    p = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        shell=isinstance(cmd, str),
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and p.returncode != 0:
        out = (p.stdout or "") + (p.stderr or "")
        raise RuntimeError(f"命令失败 ({p.returncode}): {cmd}\n{out}")
    return p


def git_proxy_prefix():
    """探测可用代理端口，返回 git 命令行级代理参数列表。"""
    for port in PROXY_PORTS:
        try:
            sock = __import__("socket").create_connection(("127.0.0.1", port), timeout=0.4)
            sock.close()
            return [
                "-c", f"http.proxy=http://127.0.0.1:{port}",
                "-c", f"https.proxy=http://127.0.0.1:{port}",
            ]
        except OSError:
            continue
    return []


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def api(repo, endpoint):
    """调 gh api 并返回解析后的 JSON。gh 直连，不带代理。"""
    p = run([GH, "api", f"repos/{repo}/{endpoint}"])
    import json
    return json.loads(p.stdout)


def check_tag_free(repo, tag):
    """铁律：发某版本号前先查该号是否已被占用。"""
    try:
        p = run([GH, "api", f"repos/{repo}/git/ref/tags/{tag}"], check=False)
        if p.returncode == 0:
            sha = ""
            m = re.search(r'"sha":\s*"([0-9a-f]+)"', p.stdout or "")
            if m:
                sha = m.group(1)[:8]
            return False, f"已存在 -> {sha}"
    except Exception:
        pass
    return True, "未被占用"


def main():
    ap = argparse.ArgumentParser(description="DLsiteFloat 双仓发版")
    ap.add_argument("--code", required=True, type=int, help="versionCode，十进制整数")
    ap.add_argument("--version", required=True, help="versionName，如 2.3.0")
    ap.add_argument("--apk", help="APK 路径")
    ap.add_argument("--notes", help="release notes 文件")
    ap.add_argument("--title-src", help="源仓库 release 标题（默认 v<version>（code <code>））")
    ap.add_argument("--title-xp", help="上架仓库 release 标题（默认 DLsiteFloat v<version>）")
    ap.add_argument("--date", help="空提交日期 YYYY-MM-DDTHH:MM:SSZ（默认取当前 UTC）")
    ap.add_argument("--work", default="_rel_release", help="上架仓库本地工作目录")
    ap.add_argument("--dry-run", action="store_true", help="只侦察，不做任何写操作")
    ap.add_argument("--yes", action="store_true", help="确认执行 force 重打 tag")
    args = ap.parse_args()

    src_tag = f"v{args.version}"
    xp_tag = f"{args.code}-{args.version}"

    print("=" * 68)
    say(INFO, f"DLsiteFloat 发版  versionName={args.version}  versionCode={args.code}")
    print("=" * 68)

    # ---------- 1. 前置侦察 ----------
    say(INFO, "[1/6] 前置侦察：查版本号是否被占用")
    occupied = []
    for repo, tag in ((SRC_REPO, src_tag), (XP_REPO, xp_tag)):
        free, detail = check_tag_free(repo, tag)
        if free:
            say(OK, f"{repo} :: {tag} {detail}")
        else:
            occupied.append((repo, tag))
            say(WARN, f"{repo} :: {tag} {detail}")
    if occupied:
        say(WARN, f"tag 已存在{len(occupied)} 处 ⇒ 本次将走「重打tag + edit release」路径"
                  "（幂等修复，非首次发布）。")
        if not args.dry_run and not args.yes:
            say(BAD, "重打已有 tag 属force 操作，必须显式加 --yes 才执行。")
            return 1

    # ---------- 2. 校验 APK ----------
    say(INFO, "[2/6] 校验 APK")
    if not args.apk:
        say(WARN, "未提供 --apk，跳过资产校验（仅调整 tag/日期时可用）")
        local_sha, local_size = None, None
    else:
        if not os.path.exists(args.apk):
            say(BAD, f"APK 不存在: {args.apk}")
            return 1
        local_sha, local_size = sha256(args.apk), os.path.getsize(args.apk)
        say(OK, f"{os.path.basename(args.apk)}  {local_size} B")
        say(OK, f"sha256 {local_sha}")

    # ---------- 3. 准备上架仓库工作副本 ----------
    say(INFO, "[3/6] 准备上架仓库工作副本")
    proxy = git_proxy_prefix()
    say(INFO, f"git 代理参数: {proxy or '（无/ 直连）'}")
    work = os.path.abspath(args.work)
    if not os.path.isdir(os.path.join(work, ".git")):
        if args.dry_run:
            say(WARN, f"[dry-run] 需克隆 {XP_REPO} -> {work}")
        else:
            if os.path.isdir(work):
                shutil.rmtree(work)
            run([GH, "repo", "clone", XP_REPO, work])
            say(OK, f"已克隆 -> {work}")
            # git 身份：复用本机配置，缺失则用 GitHub noreply
            if not run(["git", "config", "user.email"], cwd=work,
                       check=False).stdout.strip():
                run(["git", "config", "user.name", "ariinyume"], cwd=work)
                run(["git", "config", "user.email",
                     "ariinyume@users.noreply.github.com"], cwd=work)

    # ---------- 4. 造空提交 + force 重打 tag ----------
    say(INFO, "[4/6] 造日期正确的空提交并重打 tag")
    when = args.date or dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    say(INFO, f"空提交时间 = {when}")

    if args.dry_run:
        say(WARN, "[dry-run] 跳过：空提交 / push main / force tag / release edit")
        new_sha = "<dry-run>"
    else:
        # 基线 commit = 当前 origin/main；tree 沿用，保证是纯空提交
        run(["git"] + proxy + ["fetch", "origin", "--tags", "--force"], cwd=work)
        base = run(["git"] + proxy + ["rev-parse", "origin/main"], cwd=work).stdout.strip()
        tree = run(["git"] + proxy + ["rev-parse", f"{base}^{{tree}}"], cwd=work).stdout.strip()

        env = dict(os.environ)
        env["GIT_AUTHOR_DATE"] = env["GIT_COMMITTER_DATE"] = when
        new_sha = run(
            ["git"] + proxy + ["commit-tree", tree, "-p", base, "-m", xp_tag],
            cwd=work, env=env,
        ).stdout.strip()
        say(OK, f"空提交 {new_sha[:8]}  parent={base[:8]}  tree不变（纯空提交）")

        # 顺序铁律：先 push main，再改 tag（否则 tag 指向不存在的 commit）
        if _on_main(work, proxy, new_sha):
            say(WARN, "该提交已在 main 上，直接重打 tag")
        else:
            say(INFO, "推送空提交到 main…")
            run(["git"] + proxy + ["push", "origin", f"{new_sha}:main"], cwd=work)
            # 铁律：push 退出码不可信，fetch 验证
            run(["git"] + proxy + ["fetch", "origin"], cwd=work)
            got = run(["git"] + proxy + ["rev-parse", "origin/main"], cwd=work).stdout.strip()
            if got != new_sha:
                say(BAD, f"push 未生效！origin/main={got[:8]} != {new_sha[:8]}")
                return 1
            say(OK, f"origin/main 已验证 = {got[:8]}")

        run(["git"] + proxy + ["tag", "-f", xp_tag, new_sha], cwd=work)
        say(INFO, "force 推送 tag…")
        run(["git"] + proxy + ["push", "--force", "origin", f"refs/tags/{xp_tag}"], cwd=work)
        # 再验证
        run(["git"] + proxy + ["fetch", "origin", "--tags", "--force"], cwd=work)
        remote_sha = api(XP_REPO, f"git/ref/tags/{xp_tag}")["object"]["sha"]
        if remote_sha != new_sha:
            say(BAD, f"远端 tag 未生效！{remote_sha[:8]} != {new_sha[:8]}")
            return 1
        say(OK, f"远端 tag {xp_tag} 已验证 -> {remote_sha[:8]}")

    # ---------- 5. 建/改 release ----------
    say(INFO, "[5/6] 创建/更新两侧 release")
    t_src = args.title_src or f"v{args.version}（code {args.code}）"
    t_xp = args.title_xp or f"DLsiteFloat v{args.version}"

    jobs = [
        (SRC_REPO, src_tag, t_src),
        (XP_REPO, xp_tag, t_xp),
    ]
    for repo, tag, title in jobs:
        exists = not check_tag_free(repo, tag)[0]
        verb = "edit" if exists else "create"
        if args.dry_run:
            say(WARN, f"[dry-run] gh release {verb} {tag} --repo {repo} "
                      f"--title \"{title}\" --latest")
            continue
        cmd = [GH, "release", verb, tag,
               "--repo", repo, "--title", title, "--latest"]
        if args.notes and os.path.exists(args.notes):
            cmd += ["--notes-file", args.notes]
        # 🔴 gh release edit 不接受位置资产参数（edit 只能改元数据），
        #    换资产必须 delete + create 或单独 upload。
        if args.apk and verb == "create":
            cmd.append(args.apk)
        if verb == "edit" and args.apk:
            say(WARN, f"  （edit 不支持替换资产；如需换APK 请手动 delete + create）")
        if repo == XP_REPO:
            cmd += ["--target", new_sha]
        say(INFO, f"{verb} {tag} @ {repo}")
        p = run(cmd, check=False)
        if p.returncode != 0:
            say(BAD, f"失败：{(p.stdout or '') + (p.stderr or '')}")
            return 1
        say(OK, "ok")

    # ---------- 6. 发布后核对 ----------
    say(INFO, "[6/6] 发布后核对")
    if args.dry_run:
        say(WARN, "[dry-run] 跳过核对")
        print()
        return 0

    all_ok = True
    for repo, tag in ((SRC_REPO, src_tag), (XP_REPO, xp_tag)):
        try:
            rel = api(repo, f"releases/tags/{tag}")
        except Exception as e:
            say(BAD, f"{repo}::{tag} 查询失败 {e}")
            all_ok = False
            continue
        if rel.get("draft"):
            say(BAD, f"{repo}::{tag} 是 draft（LSPosed bot 校验失败会静默设 draft！）")
            all_ok = False
        for a in rel.get("assets", []):
            say(OK, f"{repo}::{tag} 资产 {a['name']}  {a['size']}B  {a['state']}")
            if local_sha and a["name"].endswith(".apk"):
                remote_sha = api(repo, f"releases/tags/{tag}")["assets"][0].get("digest")
                if remote_sha and remote_sha.startswith("sha256:"):
                    r = remote_sha.split(":", 1)[1]
                    same = r == local_sha
                    say(OK if same else BAD,
                        f"  sha256 {'一致' if same else '不一致!'} 本地={local_sha[:16]}…远端={r[:16]}…")
                    all_ok = all_ok and same
    latest = api(XP_REPO, "releases/latest")["tag_name"]
    say(OK if latest == xp_tag else WARN, f"上架仓库 Latest = {latest}（期望 {xp_tag}）")

    print("=" * 68)
    say(OK if all_ok else BAD, "全部通过" if all_ok else "有问题，见上面[!!]")
    print("=" * 68)
    return 0 if all_ok else 1


def _on_main(work, proxy, sha):
    p = run(["git"] + proxy + ["branch", "-r", "--contains", sha], cwd=work, check=False)
    return "origin/main" in (p.stdout or "")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as e:
        say(BAD, str(e))
        sys.exit(1)
    except KeyboardInterrupt:
        say(WARN, "中断")
        sys.exit(130)