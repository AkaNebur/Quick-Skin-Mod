#!/usr/bin/env python3
"""
Quick Skin E2E multi-version orchestrator (Phase 3).

Drives the per-combo bash orchestrator (run-e2e.sh) across a matrix of Minecraft versions, loaders,
and scenarios, then aggregates the per-client report.json results into a single summary (printed
table + e2e-out/summary.json). Each (version, loader, scenario) combo is run SEQUENTIALLY — never
overlapping — because every server binds port 25565 and stacking runs both clashes the port and
overloads the machine (which flakes the second-client login handshake).

run-e2e.sh already handles one combo robustly (build harness -> pre-seed a fresh day-pinned server
world -> boot server -> launch auto-connecting client(s) -> poll done.marker -> collect artifacts ->
teardown). This script is the matrix layer on top: which versions, which loaders per version, which
scenarios, plus result aggregation. It does not re-implement the launch logic.

Loader rules (locked): 1.20.1 -> fabric + forge; 1.21.x / 26.x -> fabric + neoforge (Forge is
1.20.1-only). The selector is overridden per combo via run-e2e.sh's first arg (-> -Pminecraft_version),
so gradle.properties is never edited.

Usage:
  python3 e2e/orchestrator.py                          # full validated matrix
  python3 e2e/orchestrator.py --versions 1.21.1,26.2   # subset of versions
  python3 e2e/orchestrator.py --loaders fabric         # restrict loaders
  python3 e2e/orchestrator.py --scenarios full,propagation-live
  python3 e2e/orchestrator.py --list                   # print the matrix and exit

Exit code is non-zero if any combo failed, so it doubles as a CI gate.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RUN_SH = REPO / "e2e" / "run-e2e.sh"
OUT_ROOT = REPO / "e2e-out"

# Loaders available per Minecraft version. 1.20.1 is the only Forge target; everything 1.21+/26.x is
# NeoForge. Fabric is always present. Extend this as more versions are validated — the shared e2e
# source set must compile against every enabled version (see fabric/neoforge build scripts).
VERSION_LOADERS: dict[str, list[str]] = {
    "1.20.1": ["fabric", "forge"],
    "1.21.1": ["fabric", "neoforge"],
    "26.2": ["fabric", "neoforge"],
}

# Default scenario sweep, cheapest/most-robust first so a broken combo fails fast on the simple case.
DEFAULT_SCENARIOS = ["phase0-smoke", "propagation", "propagation-live", "full"]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Quick Skin E2E multi-version orchestrator")
    p.add_argument("--versions", help="comma-separated MC versions (default: all known)")
    p.add_argument("--loaders", help="comma-separated loaders to restrict to (e.g. fabric)")
    p.add_argument("--scenarios", help=f"comma-separated scenarios (default: {','.join(DEFAULT_SCENARIOS)})")
    p.add_argument("--list", action="store_true", help="print the resolved matrix and exit")
    return p.parse_args()


def resolve_matrix(args: argparse.Namespace) -> list[tuple[str, str, str]]:
    versions = args.versions.split(",") if args.versions else list(VERSION_LOADERS)
    loader_filter = set(args.loaders.split(",")) if args.loaders else None
    scenarios = args.scenarios.split(",") if args.scenarios else list(DEFAULT_SCENARIOS)

    combos: list[tuple[str, str, str]] = []
    for v in versions:
        v = v.strip()
        if v not in VERSION_LOADERS:
            sys.exit(f"unknown version {v!r}; known: {', '.join(VERSION_LOADERS)}")
        for loader in VERSION_LOADERS[v]:
            if loader_filter and loader not in loader_filter:
                continue
            for scen in scenarios:
                combos.append((v, loader, scen.strip()))
    return combos


def read_reports(version: str, loader: str) -> dict:
    """Collect the per-role report.json statuses for a finished combo."""
    base = OUT_ROOT / version / loader
    roles: dict[str, dict] = {}
    for role_dir in ("client_a", "client_b"):
        report = base / role_dir / "report.json"
        if report.exists():
            try:
                data = json.loads(report.read_text())
                steps = data.get("steps", [])
                roles[role_dir] = {
                    "status": data.get("status"),
                    "scenario": data.get("scenario"),
                    "steps_total": len(steps),
                    "steps_pass": sum(1 for s in steps if s.get("status") == "pass"),
                    "failures": [s["name"] for s in steps if s.get("status") != "pass"],
                }
            except (json.JSONDecodeError, OSError) as e:
                roles[role_dir] = {"status": "unreadable", "error": str(e)}
    return roles


def run_combo(version: str, loader: str, scenario: str) -> dict:
    """Run one (version, loader, scenario) via run-e2e.sh; return a result record."""
    print(f"\n{'=' * 72}\n>>> {version} / {loader} / {scenario}\n{'=' * 72}", flush=True)
    # Belt-and-suspenders teardown: kill any stragglers from a prior combo before binding the port.
    subprocess.run(["pkill", "-f", "quickskin.e2e"], check=False)
    time.sleep(2)
    # Clear stale per-role reports so the aggregated summary reflects only THIS combo (a 1-client
    # scenario must not inherit a prior 2-client run's client_b report from the shared out dir).
    for role_dir in ("client_a", "client_b"):
        rp = OUT_ROOT / version / loader / role_dir / "report.json"
        if rp.exists():
            rp.unlink()

    start = time.time()
    proc = subprocess.run(
        ["bash", str(RUN_SH), version, loader, scenario],
        cwd=str(REPO),
    )
    elapsed = round(time.time() - start, 1)

    # run-e2e.sh exits 0 only when every required client passed; cross-check the reports too.
    reports = read_reports(version, loader)
    rec = {
        "version": version,
        "loader": loader,
        "scenario": scenario,
        "exit_code": proc.returncode,
        "passed": proc.returncode == 0,
        "elapsed_s": elapsed,
        "reports": reports,
    }
    print(f"<<< {version}/{loader}/{scenario}: "
          f"{'PASS' if rec['passed'] else 'FAIL'} ({elapsed}s)", flush=True)
    return rec


def print_summary(results: list[dict]) -> None:
    print(f"\n{'=' * 72}\nSUMMARY\n{'=' * 72}")
    width = max((len(f"{r['version']}/{r['loader']}/{r['scenario']}") for r in results), default=10)
    for r in results:
        combo = f"{r['version']}/{r['loader']}/{r['scenario']}"
        roles = r["reports"]
        detail = " ".join(
            f"{role.replace('client_', '').upper()}={info.get('status')}"
            f"({info.get('steps_pass')}/{info.get('steps_total')})"
            for role, info in sorted(roles.items())
        ) or "(no report)"
        status = "PASS" if r["passed"] else "FAIL"
        print(f"  {combo:<{width}}  {status:<4}  {r['elapsed_s']:>6}s  {detail}")
    passed = sum(1 for r in results if r["passed"])
    print(f"\n  {passed}/{len(results)} combos passed")


def main() -> int:
    args = parse_args()
    combos = resolve_matrix(args)

    if args.list:
        print("Resolved matrix (run sequentially):")
        for v, loader, scen in combos:
            print(f"  {v} / {loader} / {scen}")
        print(f"\n{len(combos)} combos")
        return 0

    if not RUN_SH.exists():
        sys.exit(f"run-e2e.sh not found at {RUN_SH}")

    print(f"Quick Skin E2E orchestrator: {len(combos)} combos (sequential)")
    results = [run_combo(v, loader, scen) for v, loader, scen in combos]

    print_summary(results)

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    summary_path = OUT_ROOT / "summary.json"
    # Note: timestamp intentionally omitted (kept deterministic + dependency-free); the file mtime
    # records when it was written.
    summary_path.write_text(json.dumps({"results": results}, indent=2))
    print(f"\nWrote {summary_path}")

    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
