#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""github.com 直连 push 被断时的 API 兜底提交工具(按 commit 粒度复刻)。

用法:
  python scripts/api_push.py commit --branch master --message "..." \
      --set path=localfile [--set path=localfile ...] [--delete path ...]
  python scripts/api_push.py put-file --branch dl --path carmusic-release.apk --file app.apk --message "..."

token 取自 git credential store(git credential fill)。
blob 走 git data API(base64),树基于远端父提交 tree,PATCH ref 非强制(防覆盖他人提交)。
"""
import argparse
import base64
import json
import subprocess
import sys
import urllib.request

REPO = "soloman7/carmusic"


def gh_token() -> str:
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise RuntimeError("credential store 里没有 github token")


class GH:
    def __init__(self, repo: str):
        self.repo = repo
        self.headers = {
            "Authorization": f"token {gh_token()}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "carmusic-api-push/1.0",
        }

    def req(self, method: str, path: str, body=None, timeout: int = 300):
        url = f"https://api.github.com/repos/{self.repo}{path}"
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(url, data=data, headers=self.headers, method=method)
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            payload = resp.read().decode()
            return json.loads(payload) if payload else {}

    def blob(self, content: bytes) -> str:
        return self.req(
            "POST", "/git/blobs",
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )["sha"]


def cmd_commit(args):
    gh = GH(REPO)
    ref = gh.req("GET", f"/git/ref/heads/{args.branch}")
    parent = ref["object"]["sha"]
    base_tree = gh.req("GET", f"/git/commits/{parent}")["tree"]["sha"]

    entries = []
    for item in args.set or []:
        path, _, local = item.partition("=")
        with open(local, "rb") as f:
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": gh.blob(f.read())})
        print(f"blob ok: {path} <- {local}")
    for path in args.delete or []:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        print(f"delete: {path}")

    tree = gh.req("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})["sha"]
    commit = gh.req(
        "POST", "/git/commits",
        {"message": args.message, "tree": tree, "parents": [parent]},
    )["sha"]
    gh.req("PATCH", f"/git/refs/heads/{args.branch}", {"sha": commit, "force": False})
    print(f"committed on {args.branch}: {commit}")


def cmd_put_file(args):
    gh = GH(REPO)
    with open(args.file, "rb") as f:
        content = base64.b64encode(f.read()).decode()
    res = gh.req(
        "PUT", f"/contents/{args.path}",
        {"message": args.message, "content": content, "branch": args.branch},
        timeout=600,
    )
    print(f"put {args.path} on {args.branch}: commit {res['commit']['sha']}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("commit")
    c.add_argument("--branch", required=True)
    c.add_argument("--message", required=True)
    c.add_argument("--set", action="append", help="path=localfile,可多次")
    c.add_argument("--delete", action="append")
    p = sub.add_parser("put-file")
    p.add_argument("--branch", required=True)
    p.add_argument("--path", required=True)
    p.add_argument("--file", required=True)
    p.add_argument("--message", required=True)
    args = ap.parse_args()
    {"commit": cmd_commit, "put-file": cmd_put_file}[args.cmd](args)


if __name__ == "__main__":
    main()
