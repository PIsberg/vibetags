#!/usr/bin/env python3
"""
Render comparison plots for VibeTags load-test baselines.

Walks `load-tests/results/<version>/` and emits PNGs into
`load-tests/results/_plots/`.

Usage:
    python tools/plot-results.py
    python tools/plot-results.py --results load-tests/results --out load-tests/results/_plots
"""
import argparse
import json
import re
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RESULTS = REPO_ROOT / "load-tests" / "results"

# Release directories, pre-release suffix included. The suffix used to be excluded, which meant
# every 1.0.0-RCn baseline was captured, committed, and then silently left out of every plot — the
# folder was there, the line was not, and nothing said so.
VERSION_DIR_PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d*))?$")
STRESS_ROW = re.compile(
    r"^\s*(\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$"
)


def release_order(name: str) -> tuple:
    """
    Sort key placing 0.9.7 < 1.0.0-RC1 < 1.0.0-RC9 < 1.0.0.

    A pre-release precedes its own final release, so the suffix flag is 0 for RCs and 1 for a plain
    version. Plain lexicographic sorting put "1.0.0-RC9" after "1.0.0" and "1.0.0-RC10" before
    "-RC9", which would draw the release history in the wrong order on every chart.
    """
    m = VERSION_DIR_PATTERN.match(name)
    if not m:
        return (0, 0, 0, 0, 0)
    major, minor, patch, tag, tag_num = m.groups()
    return (int(major), int(minor), int(patch), 0 if tag else 1, int(tag_num or 0))


def discover_versions(results_dir: Path) -> list[str]:
    versions = sorted(
        (p.name for p in results_dir.iterdir()
         if p.is_dir() and VERSION_DIR_PATTERN.match(p.name)),
        key=release_order,
    )
    if not versions:
        sys.exit(f"No version directories found under {results_dir}")
    return versions


def load_stress(version_dir: Path) -> list[tuple[int, int, int, int, int]]:
    return _load_table(version_dir / "stress.txt")


def load_memory(version_dir: Path) -> list[tuple[int, int, int, int, int]]:
    """Reads memory.txt rows: (N, ProcessorAlloc_KB, BaselineAlloc_KB, OverheadAlloc_KB, PeakHeap_MB)."""
    path = version_dir / "memory.txt"
    if not path.exists():
        return []
    return _load_table(path)


def _load_table(path: Path) -> list[tuple[int, int, int, int, int]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = STRESS_ROW.match(line)
        if m:
            rows.append(tuple(int(x) for x in m.groups()))
    return rows


def load_jmh(version_dir: Path) -> dict[str, tuple[float, float]]:
    # Not every baseline has one: 1.0.0-RC1 captured allocation only. Returning empty keeps that
    # release on the stress and memory charts instead of aborting the whole run.
    path = version_dir / "jmh.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    out = {}
    for entry in data:
        name = entry["benchmark"].rsplit(".", 1)[-1]
        score = entry["primaryMetric"]["score"]
        err = entry["primaryMetric"]["scoreError"]
        if isinstance(err, str) and err.upper() == "NAN":
            err = 0.0
        if err is None:
            err = 0.0
        out[name] = (float(score), float(err))
    return out


def plot_overhead_vs_n(versions: list[str], stress: dict[str, list], out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(8, 5))
    for version in versions:
        rows = stress[version]
        ns = [r[0] for r in rows]
        overhead = [r[3] for r in rows]
        ax.plot(ns, overhead, marker="o", label=f"v{version}")
    ax.set_xscale("log")
    ax.set_xlabel("Annotated classes (N, log scale)")
    ax.set_ylabel("Processor overhead (ms)")
    ax.set_title("AnnotationVolumeStressTest — overhead = processorTime − baseline")
    ax.grid(True, which="both", linestyle="--", alpha=0.4)
    ax.axhline(0, color="black", linewidth=0.5)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_memory_overhead_vs_n(versions: list[str], memory: dict[str, list], out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(8, 5))
    for version in versions:
        rows = memory.get(version, [])
        if not rows:
            continue
        ns = [r[0] for r in rows]
        # OverheadAlloc(KB) → MB for readability; column index 3
        overhead_mb = [r[3] / 1024 for r in rows]
        ax.plot(ns, overhead_mb, marker="o", label=f"v{version}")
    ax.set_xscale("log")
    ax.set_xlabel("Annotated classes (N, log scale)")
    ax.set_ylabel("Processor allocation overhead (MB)")
    ax.set_title("MemoryVolumeStressTest — overhead = processorAlloc − baselineAlloc")
    ax.grid(True, which="both", linestyle="--", alpha=0.4)
    ax.axhline(0, color="black", linewidth=0.5)
    ax.legend()
    # Each line was captured on the day its release was cut, on whatever the machine was doing
    # then. The baseline column alone moved 9 % between the 1.0.0-RC1 and 1.0.0-RC9 captures, which
    # is larger than most release-to-release differences on this chart. Read it as a trend, and use
    # a same-session comparison (tools/plot-release-comparison.py) for any actual claim.
    fig.text(0.5, 0.015,
             "Lines come from separate capture sessions months apart — differences under ~10 % "
             "are environment, not code.",
             ha="center", fontsize=8, style="italic", color="#555555")
    fig.tight_layout(rect=(0, 0.045, 1, 1))
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_peak_heap_vs_n(versions: list[str], memory: dict[str, list], out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(8, 5))
    for version in versions:
        rows = memory.get(version, [])
        if not rows:
            continue
        ns = [r[0] for r in rows]
        # PeakHeap(MB); column index 4
        peak = [r[4] for r in rows]
        ax.plot(ns, peak, marker="s", linestyle="--", label=f"v{version}")
    ax.set_xscale("log")
    ax.set_xlabel("Annotated classes (N, log scale)")
    ax.set_ylabel("Peak heap during processor run (MB)")
    ax.set_title("MemoryVolumeStressTest — peak heap (noisy: GC-timing dependent)")
    ax.grid(True, which="both", linestyle="--", alpha=0.4)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_hotpath(versions: list[str], jmh: dict[str, dict], out_path: Path,
                 benchmarks: list[str], title: str, log_y: bool) -> None:
    fig, ax = plt.subplots(figsize=(11, 6))
    n_versions = len(versions)
    n_bench = len(benchmarks)
    x = np.arange(n_bench)
    width = 0.8 / n_versions

    for i, version in enumerate(versions):
        scores = [jmh[version].get(b, (np.nan, 0))[0] for b in benchmarks]
        errors = [jmh[version].get(b, (np.nan, 0))[1] for b in benchmarks]
        offset = (i - (n_versions - 1) / 2) * width
        ax.bar(x + offset, scores, width, yerr=errors, capsize=4, label=f"v{version}")

    ax.set_xticks(x)
    ax.set_xticklabels(benchmarks, rotation=20, ha="right")
    ax.set_ylabel("avgt µs/op (lower is better)")
    if log_y:
        ax.set_yscale("log")
    ax.set_title(title)
    ax.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax.legend()
    # Wall-clock, and therefore hostage to whatever else the capture machine was doing. Re-running
    # 0.9.7 on a different day reproduced its own recorded numbers only to within 1.4x-3.1x.
    fig.text(0.5, 0.015,
             "Wall-clock across separate capture sessions: the same build re-measured later has "
             "landed 1.4x–3.1x from its own baseline. Bars are indicative, not a ranking.",
             ha="center", fontsize=8, style="italic", color="#555555")
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--out", type=Path,
                        default=DEFAULT_RESULTS / "_plots")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    versions = discover_versions(args.results)

    stress = {v: load_stress(args.results / v) for v in versions}
    jmh = {v: load_jmh(args.results / v) for v in versions}
    memory = {v: load_memory(args.results / v) for v in versions}

    all_benchmarks = sorted({b for v in versions for b in jmh[v]})
    write_benchmarks = [b for b in all_benchmarks if b.startswith("writeFileIfChanged")]

    versions_with_stress = [v for v in versions if stress[v]]
    plot_overhead_vs_n(versions_with_stress, stress, args.out / "overhead-vs-n.png")

    # Only releases that actually captured JMH output get a bar; an empty series would otherwise
    # claim a legend entry and draw nothing, which reads as "measured, and it was zero".
    versions_with_jmh = [v for v in versions if jmh[v]]
    if versions_with_jmh:
        plot_hotpath(versions_with_jmh, jmh, args.out / "hotpath-by-release.png",
                     all_benchmarks, "JMH ProcessorHotPathBenchmark — by release", log_y=True)
        plot_hotpath(versions_with_jmh, jmh, args.out / "writeFileIfChanged-detail.png",
                     write_benchmarks, "writeFileIfChanged variants (linear)", log_y=False)

    versions_with_memory = [v for v in versions if memory[v]]
    if versions_with_memory:
        plot_memory_overhead_vs_n(versions_with_memory, memory, args.out / "memory-overhead-vs-n.png")
        plot_peak_heap_vs_n(versions_with_memory, memory, args.out / "memory-peak-heap-vs-n.png")

    print(f"Wrote plots to {args.out}")
    for v in versions:
        print(f"  v{v}: {len(stress[v])} stress rows, "
              f"{len(jmh[v])} JMH benchmarks, "
              f"{len(memory[v])} memory rows")


if __name__ == "__main__":
    main()
