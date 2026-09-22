#!/usr/bin/env python3
"""
Render what a project pays per platform it has opted into, and what a scoped-rules directory
takes back out of the always-loaded file.

Reads `load-tests/results/<version>/platform-breadth.txt`, the table
`PlatformBreadthStressTest` writes. The point of the figure is the gap between the `sweep`
column — the six opt-in files every baseline under `load-tests/results/` has ever measured — and
`all-files` / `all+granular`, which is what a project using the other 50-odd outputs actually
pays. That gap is invisible in every other chart this repo draws.

Emits `load-tests/results/_plots/platform-breadth-<version>.png`.

Usage:
    python tools/plot-platform-breadth.py --version 1.3.7-SNAPSHOT
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

ROW = re.compile(r"^\s*(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$")

# The sweep column is the anchor every committed baseline measured; it gets the accent so the eye
# lands on how small a slice of the chart it is.
ANCHOR = "sweep"
ANCHOR_COLOUR = "#1f7a4d"
OTHER_COLOUR = "#8fa3b8"
GRANULAR_COLOUR = "#3d6b99"
SAVED_COLOUR = "#27ae60"


class Level:
    def __init__(self, name, opt_ins, alloc_kb, millis, output_bytes, files, always_loaded):
        self.name = name
        self.opt_ins = opt_ins
        self.alloc_kb = alloc_kb
        self.millis = millis
        self.output_bytes = output_bytes
        self.files = files
        self.always_loaded = always_loaded


def parse(path: Path) -> list[Level]:
    levels = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            levels.append(Level(m.group(1), *(int(v) for v in m.groups()[1:])))
    if not levels:
        sys.exit(f"could not parse any rows from {path}")
    return levels


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    source = RESULTS / args.version / "platform-breadth.txt"
    if not source.exists():
        sys.exit(f"no breadth table for {args.version}: {source}")
    levels = parse(source)

    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(14.5, 4.9))
    fig.suptitle(
        f"VibeTags {args.version} — cost and benefit of opting more platforms in\n"
        "PlatformBreadthStressTest, N=500 annotated classes, each level in its own project root",
        fontsize=10,
    )

    names = [l.name for l in levels]
    x = np.arange(len(levels))
    colours = [ANCHOR_COLOUR if l.name == ANCHOR else
               GRANULAR_COLOUR if "granular" in l.name else OTHER_COLOUR for l in levels]

    # Panel 1 — allocation. The number a downstream build feels, and the one this harness
    # reproduces to within a per cent.
    bars = ax1.bar(x, [l.alloc_kb / 1024 for l in levels], color=colours,
                   edgecolor="white", linewidth=0.6, zorder=3)
    ax1.bar_label(bars, fmt="%.0f", fontsize=8, padding=2)
    ax1.set_xticks(x)
    ax1.set_xticklabels([f"{n}\n({l.opt_ins} opt-ins)" for n, l in zip(names, levels)], fontsize=8)
    ax1.set_ylabel("Allocation overhead (MB, lower is better)")
    ax1.set_title("What the processor allocates", fontsize=10)
    ax1.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)

    # Panel 2 — files written, log scale: the granular levels are three orders of magnitude up,
    # and that, not the byte count, is what makes them slow.
    bars = ax2.bar(x, [max(l.files, 1) for l in levels], color=colours,
                   edgecolor="white", linewidth=0.6, zorder=3)
    ax2.bar_label(bars, fmt="%d", fontsize=8, padding=2)
    ax2.set_yscale("log")
    ax2.set_xticks(x)
    ax2.set_xticklabels(names, fontsize=8, rotation=20, ha="right")
    ax2.set_ylabel("Generated files written (log scale)")
    ax2.set_title("How many files that is", fontsize=10)
    ax2.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)

    # Panel 3 — the benefit side. Scoped rules exist to shrink the file an agent loads on every
    # request, so the cost panels above are only half the story.
    with_always_loaded = [l for l in levels if l.always_loaded > 0]
    bars = ax3.bar(np.arange(len(with_always_loaded)),
                   [l.always_loaded / 1024 for l in with_always_loaded],
                   color=[SAVED_COLOUR if "granular" in l.name else OTHER_COLOUR
                          for l in with_always_loaded],
                   edgecolor="white", linewidth=0.6, zorder=3)
    ax3.bar_label(bars, fmt="%.0f", fontsize=8, padding=2)
    ax3.set_xticks(np.arange(len(with_always_loaded)))
    ax3.set_xticklabels([l.name for l in with_always_loaded], fontsize=8, rotation=20, ha="right")
    ax3.set_ylabel("CLAUDE.md (KB, lower is better)")
    ax3.set_title("What the agent loads on every request", fontsize=10)
    ax3.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)

    plain = next((l for l in levels if l.name == ANCHOR), None)
    granular = next((l for l in levels if l.name == "sweep+granular"), None)
    if plain and granular and plain.always_loaded:
        saved = 100 * (plain.always_loaded - granular.always_loaded) / plain.always_loaded
        fig.text(0.5, 0.015,
                 f"Scoped rules take {saved:.0f} % out of the always-loaded file, and the "
                 f"per-element detail loads only when a matching source file is open.",
                 ha="center", fontsize=8, style="italic", color="#555555")

    fig.tight_layout(rect=(0, 0.045, 1, 0.90))
    out = RESULTS / "_plots" / f"platform-breadth-{args.version}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)

    print(f"Wrote {out}")
    for l in levels:
        print(f"  {l.name:>15}: {l.opt_ins:3d} opt-ins  {l.alloc_kb / 1024:7.1f} MB  "
              f"{l.files:6d} files  CLAUDE.md {l.always_loaded:7d} B")


if __name__ == "__main__":
    main()
