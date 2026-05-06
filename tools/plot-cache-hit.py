#!/usr/bin/env python3
"""
Generate two comparison plots from WriteCacheHitBenchmark JMH output:

  1. Wall-clock (us/op) — cacheHit vs noCache, log-y, grouped by body size.
  2. Allocation rate (B/op) — same grouping, log-y.

Reads `load-tests/results/0.8.0/jmh-cache-hit.json` and writes:

  load-tests/results/_plots/cache-hit-time.png
  load-tests/results/_plots/cache-hit-alloc.png

Usage:
    python tools/plot-cache-hit.py
"""
import json
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = REPO_ROOT / "load-tests" / "results" / "0.8.0" / "jmh-cache-hit.json"
DEFAULT_OUT = REPO_ROOT / "load-tests" / "results" / "_plots"

# Body-size groups in the order we want to display them.
SIZES = [
    ("1 KB", "small"),
    ("12 KB", "medium"),
    ("1 MB", "large"),
]
# File type variants to plot side-by-side per size group.
VARIANTS = [
    ("marker (.md)",     "marker_cacheHit",    "marker_noCache"),
    ("non-marker (.cursorrules)", "nonMarker_cacheHit", "nonMarker_noCache"),
]


def load(path: Path) -> dict[str, dict]:
    """Return {benchmark-short-name: {score, error, alloc_norm}}."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, dict] = {}
    for entry in raw:
        name = entry["benchmark"].rsplit(".", 1)[-1]
        score = entry["primaryMetric"]["score"]
        err = entry["primaryMetric"]["scoreError"]
        secondary = entry.get("secondaryMetrics", {}) or {}
        alloc = None
        alloc_key = next((k for k in secondary if k.endswith("alloc.rate.norm")), None)
        if alloc_key:
            alloc = secondary[alloc_key]["score"]
        out[name] = {"score": score, "err": err, "alloc": alloc}
    return out


def plot_grouped_bars(
    data: dict[str, dict],
    out_path: Path,
    metric: str,
    ylabel: str,
    title: str,
    log_y: bool,
) -> None:
    """One group per size-x-variant, two bars (cacheHit / noCache)."""
    fig, ax = plt.subplots(figsize=(11, 6))

    labels = [f"{size_label}\n{var_label}" for size_label, _ in SIZES for var_label, _, _ in VARIANTS]
    cache_vals: list[float] = []
    nocache_vals: list[float] = []
    cache_err: list[float] = []
    nocache_err: list[float] = []

    for _, size_key in SIZES:
        for _, hit_suffix, miss_suffix in VARIANTS:
            hit_name = f"{size_key}_{hit_suffix}"
            miss_name = f"{size_key}_{miss_suffix}"
            cache_vals.append(data[hit_name][metric] if metric in data[hit_name] else data[hit_name]["score"])
            nocache_vals.append(data[miss_name][metric] if metric in data[miss_name] else data[miss_name]["score"])
            if metric == "score":
                cache_err.append(data[hit_name]["err"])
                nocache_err.append(data[miss_name]["err"])
            else:
                cache_err.append(0.0)
                nocache_err.append(0.0)

    x = np.arange(len(labels))
    width = 0.36
    bars_cache = ax.bar(x - width / 2, cache_vals, width, yerr=cache_err if metric == "score" else None,
                        capsize=4, label="cache hit", color="#2ca02c")
    bars_no    = ax.bar(x + width / 2, nocache_vals, width, yerr=nocache_err if metric == "score" else None,
                        capsize=4, label="no cache",  color="#d62728")

    if log_y:
        ax.set_yscale("log")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=9)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(True, axis="y", which="both", linestyle="--", alpha=0.4)
    ax.legend()

    # Annotate ratio (noCache / cacheHit) above each pair
    for i, (c, n) in enumerate(zip(cache_vals, nocache_vals)):
        if c > 0 and n > 0:
            ratio = n / c
            ymax = max(c, n)
            label = f"{ratio:.0f}×" if ratio >= 10 else f"{ratio:.1f}×"
            ax.annotate(label, xy=(i, ymax), xytext=(0, 6), textcoords="offset points",
                        ha="center", fontsize=9, fontweight="bold", color="#1f1f1f")

    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def main() -> None:
    data = load(DEFAULT_INPUT)
    DEFAULT_OUT.mkdir(parents=True, exist_ok=True)

    plot_grouped_bars(
        data,
        DEFAULT_OUT / "cache-hit-time.png",
        metric="score",
        ylabel="avgt µs/op (log scale, lower is better)",
        title="WriteCacheHitBenchmark — wall-clock per writeFileIfChanged call",
        log_y=True,
    )

    plot_grouped_bars(
        data,
        DEFAULT_OUT / "cache-hit-alloc.png",
        metric="alloc",
        ylabel="bytes allocated per op (log scale, lower is better)",
        title="WriteCacheHitBenchmark — allocation per writeFileIfChanged call",
        log_y=True,
    )

    print(f"Wrote plots to {DEFAULT_OUT}")
    for name in sorted(data):
        if "_cacheHit" in name or "_noCache" in name:
            d = data[name]
            print(f"  {name}: {d['score']:.1f} µs/op  alloc {d['alloc']:.0f} B/op")


if __name__ == "__main__":
    main()
