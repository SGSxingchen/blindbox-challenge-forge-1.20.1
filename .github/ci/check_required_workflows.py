#!/usr/bin/env python3
"""Wait for and verify all required workflow runs for one commit SHA."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REQUIRED = [
    "质量与构建",
    "专用服务器启动",
    "生命周期强杀恢复",
    "真实单客户端启动",
    "真实双客户端联机",
]
TERMINAL = {"completed"}
SUCCESS = {"success"}


def api_get(url: str, token: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "blindboxchallenge-regression-gate",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def latest_required_runs(repo: str, sha: str, token: str) -> dict[str, dict]:
    query = urllib.parse.urlencode({"head_sha": sha, "per_page": 100})
    payload = api_get(f"https://api.github.com/repos/{repo}/actions/runs?{query}", token)
    latest: dict[str, dict] = {}
    for run in payload.get("workflow_runs", []):
        name = run.get("name")
        # API 的 head_sha 查询是必要但不能成为唯一信任来源：报告前再次逐条绑定目标 SHA，
        # 防止延迟/分页/平台异常时把另一提交的同名 workflow 当成本次门禁。
        if name not in REQUIRED or run.get("head_sha") != sha:
            continue
        previous = latest.get(name)
        if previous is None or int(run.get("run_number", 0)) > int(previous.get("run_number", 0)):
            latest[name] = run
    return latest


def canonical_report(repo: str, sha: str, runs: dict[str, dict]) -> dict:
    entries = []
    for name in REQUIRED:
        run = runs.get(name)
        entries.append(
            {
                "name": name,
                "present": run is not None,
                "status": None if run is None else run.get("status"),
                "conclusion": None if run is None else run.get("conclusion"),
                "run_id": None if run is None else run.get("id"),
                "run_number": None if run is None else run.get("run_number"),
                "url": None if run is None else run.get("html_url"),
                "head_sha": None if run is None else run.get("head_sha"),
                "head_branch": None if run is None else run.get("head_branch"),
                "event": None if run is None else run.get("event"),
            }
        )
    return {"schema": 1, "repository": repo, "head_sha": sha, "required_workflows": entries}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=2700)
    parser.add_argument("--poll-seconds", type=int, default=20)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("GITHUB_TOKEN is required", file=sys.stderr)
        return 2

    deadline = time.monotonic() + args.timeout_seconds
    runs: dict[str, dict] = {}
    while True:
        runs = latest_required_runs(args.repo, args.sha, token)
        missing = [name for name in REQUIRED if name not in runs]
        pending = [
            name
            for name, run in runs.items()
            if run.get("status") not in TERMINAL
        ]
        print(f"required={len(REQUIRED)} present={len(runs)} missing={missing} pending={pending}", flush=True)
        if not missing and not pending:
            break
        if time.monotonic() >= deadline:
            report = canonical_report(args.repo, args.sha, runs)
            Path(args.output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("Timed out waiting for required workflows", file=sys.stderr)
            return 1
        time.sleep(args.poll_seconds)

    report = canonical_report(args.repo, args.sha, runs)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    failed = [
        entry
        for entry in report["required_workflows"]
        if not entry["present"] or entry["status"] != "completed" or entry["conclusion"] not in SUCCESS
    ]
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        print("Required workflow gate failed: " + ", ".join(entry["name"] for entry in failed), file=sys.stderr)
        return 1
    print("All required workflow runs succeeded for " + args.sha)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
