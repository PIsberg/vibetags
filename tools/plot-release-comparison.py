#!/usr/bin/env python3
"""
Render the changelog comparison figure for a release: allocation overhead against the releases
before it, measured back-to-back in one session.

Reads `load-tests/results/<version>/memory-same-session.txt` — a table produced by running
`MemoryVolumeStressTest` against several `-Dprocessor.version` values on one machine, in one
sitting. Cross-day baselines are not comparable at this precision (see the header of that file),
so this script deliberately refuses to read the per-release `memory.txt` captures.

Emits `load-tests/results/_plots/alloc-release-comparison-<version>.png`.

Usage:
    python tools/plot-release-comparison.py
    python tools/plot-release-comparison.py --version 1.0.0-RC9
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

# N=10 is warmup and classloading, not processor cost: two runs of one build differed 4x. Charting
# it would put the least trustworthy number in the most eye-catching position.
EXCLUDED_N = {10}

HEADER = re.compile(r"^\s*N\s+(.*\S)\s*$")
ROW = re.compile(r"^\s*(\d+)\s+((?:\d+\s+)*\d+)\s*$")

# Muted for the history, saturated for the release under test — the eye should land on the new bar.
HISTORY_COLOURS = ["#b8c4d0", "#8fa3b8", "#5f7d99"]
RELEASE_COLOUR = "#1f7a4d"
IMPROVED = "#27ae60"
REGRESSED = "#c0392b"


def parse_table(text: str, section: str) -> tuple[list[str], dict[int, list[int]]]:
    """Reads the block headed `section` into (column labels, {N: [value per column]})."""
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith(section)), None)
    if start is None:
        sys.exit(f"section not found: {section!r}")

    columns: list[str] | None = None
    rows: dict[int, list[int]] = {}
    for line in lines[start + 1:]:
        if columns is not None and not line.strip():
            break
        header = HEADER.match(line)
        if header and columns is None:
            columns = header.group(1).split()
            continue
        row = ROW.match(line)
        if row and columns is not None:
            rows[int(row.group(1))] = [int(v) for v in row.group(2).split()]
    if not columns or not rows:
        sys.exit(f"could not parse any rows under {section!r}")
    return columns, rows


def collapse_repeat_runs(columns: list[str], rows: dict[int, list[int]]) -> tuple[list[str], dict[int, list[float]]]:
    """
    Averages columns that are repeat runs of one version, e.g. `1.0.0-RC9(a)` and `1.0.0-RC9(b)`.

    Repeat runs exist to show the noise floor. Charting them as separate bars would imply two
    releases where there is one.
    """
    labels: list[str] = []
    groups: list[list[int]] = []
    for index, column in enumerate(columns):
        name = re.sub(r"\([a-z]\)$", "", column)
        if labels and labels[-1] == name:
            groups[-1].append(index)
        else:
            labels.append(name)
            groups.append([index])
    collapsed = {
        n: [sum(values[i] for i in group) / len(group) for group in groups]
        for n, values in rows.items()
    }
    return labels, collapsed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="1.0.0-RC9")
    args = parser.parse_args()

    source = RESULTS / args.version / "memory-same-session.txt"
    if not source.exists():
        sys.exit(f"no same-session comparison for {args.version}: {source}")

    columns, rows = parse_table(source.read_text(encoding="utf-8"), "OverheadAlloc (KB)")
    labels, data = collapse_repeat_runs(columns, rows)
    if labels[-1] != args.version:
        sys.exit(f"expected the last column to be {args.version}, got {labels[-1]}")

    ns = sorted(n for n in data if n not in EXCLUDED_N)
    if not ns:
        sys.exit("every N was excluded — nothing to plot")

    previous = labels[:-1]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.6))
    fig.suptitle(
        f"VibeTags {args.version} — processor allocation overhead vs earlier releases\n"
        "MemoryVolumeStressTest, all versions measured back-to-back on one machine "
        "(overhead = processorAlloc − baselineAlloc)",
        fontsize=10,
    )

    # Panel 1 — absolute allocation overhead per N, grouped by release.
    x = np.arange(len(ns))
    width = 0.8 / len(labels)
    for i, label in enumerate(labels):
        values = [data[n][i] / 1024 for n in ns]
        is_release = i == len(labels) - 1
        offset = (i - (len(labels) - 1) / 2) * width
        bars = ax1.bar(
            x + offset, values, width,
            color=RELEASE_COLOUR if is_release else HISTORY_COLOURS[i % len(HISTORY_COLOURS)],
            edgecolor="white", linewidth=0.6,
            label=label + (" (this release)" if is_release else ""),
            zorder=3,
        )
        if is_release:
            ax1.bar_label(bars, fmt="%.0f", fontsize=7, padding=2)

    ax1.set_xticks(x)
    ax1.set_xticklabels([f"N={n}" for n in ns])
    ax1.set_ylabel("Allocation overhead (MB, lower is better)")
    ax1.set_title("Allocation overhead by annotated-class count", fontsize=10)
    ax1.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)
    ax1.legend(fontsize=8, framealpha=0.9)

    # Panel 2 — change vs each earlier release, at the largest N charted.
    largest = ns[-1]
    release_value = data[largest][-1]
    deltas = [100 * (data[largest][i] - release_value) / data[largest][i]
              for i in range(len(previous))]
    colours = [IMPROVED if d > 0 else REGRESSED for d in deltas]
    bars = ax2.barh(previous, deltas, color=colours, edgecolor="white", linewidth=0.6, zorder=3)
    ax2.bar_label(bars, labels=[f"{d:+.1f}%" for d in deltas], fontsize=9, padding=4)
    ax2.axvline(0, color="black", linewidth=0.8)
    ax2.set_xlabel(f"Allocation reduction at N={largest} (%, higher is better)")
    ax2.set_title(f"{args.version} vs each earlier release", fontsize=10)
    ax2.grid(True, axis="x", linestyle="--", alpha=0.35, zorder=0)
    span = max(abs(min(deltas)), abs(max(deltas)), 1.0)
    ax2.set_xlim(-span * 1.45, span * 1.45)

    # The noise floor belongs on the chart, not only in the commit message: without it a reader
    # cannot tell which of these bars means anything.
    ax2.text(
        0.5, -0.30,
        "Repeat runs of the same build agree to within 0.6 % at these N — "
        "bars under ~1 % are noise, not change.",
        transform=ax2.transAxes, ha="center", fontsize=8, style="italic", color="#555555",
    )

    fig.tight_layout(rect=(0, 0.04, 1, 0.94))
    out = RESULTS / "_plots" / f"alloc-release-comparison-{args.version}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)

    print(f"Wrote {out}")
    for i, label in enumerate(labels):
        print(f"  {label:>12}: " + "  ".join(f"N={n} {data[n][i] / 1024:7.1f} MB" for n in ns))


if __name__ == "__main__":
    main()
