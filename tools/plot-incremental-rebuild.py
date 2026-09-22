#!/usr/bin/env python3
"""
Render what the fingerprint short-circuit actually saves on a rebuild of unchanged sources.

Reads `load-tests/results/<version>/incremental-rebuild.txt`, the table
`IncrementalRebuildStressTest` writes. Every other chart in this repo is a cold build: the volume
sweeps give each N a fresh `@TempDir`, so the cache and the fingerprint they would hit are always
empty. This is the other build — the one a developer runs twenty times an afternoon.

The figure is deliberately not flattering. The short-circuit fires (the test asserts on the
processor's own note, not on the timing), and it still leaves almost the whole cost standing,
because the collector has walked every annotated element before a fingerprint can be computed.

Emits `load-tests/results/_plots/incremental-rebuild-<version>.png`.

Usage:
    python tools/plot-incremental-rebuild.py --version 1.3.7-SNAPSHOT
"""
import argparse
import re
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "load-tests" / "results"

ROW = re.compile(r"^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s*$")

COLD_COLOUR = "#8fa3b8"
WARM_COLOUR = "#1f7a4d"
SAVED_COLOUR = "#27ae60"


def parse(path: Path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            rows.append((int(m.group(1)), int(m.group(2)), int(m.group(3)),
                         int(m.group(4)), int(m.group(5)), float(m.group(6))))
    if not rows:
        sys.exit(f"could not parse any rows from {path}")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    source = RESULTS / args.version / "incremental-rebuild.txt"
    if not source.exists():
        sys.exit(f"no incremental-rebuild table for {args.version}: {source}")
    rows = parse(source)

    ns = [r[0] for r in rows]
    cold = [r[1] / 1024 for r in rows]
    warm = [r[2] / 1024 for r in rows]
    saved = [r[5] for r in rows]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.6))
    fig.suptitle(
        f"VibeTags {args.version} — what a rebuild of unchanged sources costs\n"
        "IncrementalRebuildStressTest: the same sources compiled twice into one project root, "
        "with the fingerprint short-circuit confirmed to fire on the second round",
        fontsize=10,
    )

    x = np.arange(len(ns))
    width = 0.38
    b1 = ax1.bar(x - width / 2, cold, width, color=COLD_COLOUR, edgecolor="white",
                 linewidth=0.6, label="first build (cold)", zorder=3)
    b2 = ax1.bar(x + width / 2, warm, width, color=WARM_COLOUR, edgecolor="white",
                 linewidth=0.6, label="rebuild, nothing changed", zorder=3)
    ax1.bar_label(b1, fmt="%.0f", fontsize=8, padding=2)
    ax1.bar_label(b2, fmt="%.0f", fontsize=8, padding=2)
    ax1.set_xticks(x)
    ax1.set_xticklabels([f"N={n}" for n in ns])
    ax1.set_ylabel("Allocation overhead (MB, lower is better)")
    ax1.set_title("Cold build vs no-op rebuild", fontsize=10)
    ax1.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)
    ax1.legend(fontsize=8, framealpha=0.9)

    bars = ax2.bar(x, saved, color=SAVED_COLOUR, edgecolor="white", linewidth=0.6, zorder=3)
    ax2.bar_label(bars, labels=[f"{s:.1f}%" for s in saved], fontsize=9, padding=3)
    ax2.set_xticks(x)
    ax2.set_xticklabels([f"N={n}" for n in ns])
    ax2.set_ylabel("Allocation the rebuild skipped (%, higher is better)")
    ax2.set_title("What the short-circuit is worth", fontsize=10)
    ax2.set_ylim(0, max(max(saved) * 1.6, 12))
    ax2.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)
    fig.text(
        0.5, 0.015,
        "The short-circuit skips building and writing content, not collecting it — and the "
        "collection walk is where the allocation is.",
        ha="center", fontsize=8, style="italic", color="#555555",
    )

    fig.tight_layout(rect=(0, 0.045, 1, 0.90))
    out = RESULTS / "_plots" / f"incremental-rebuild-{args.version}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)

    print(f"Wrote {out}")
    for n, c, w, s in zip(ns, cold, warm, saved):
        print(f"  N={n:5d}: cold {c:7.1f} MB  warm {w:7.1f} MB  saved {s:5.1f} %")


if __name__ == "__main__":
    main()
