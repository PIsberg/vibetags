#!/usr/bin/env python3
"""
Render the release-history allocation figure: every release measured back-to-back in one session.

`tools/plot-release-comparison.py` draws two or three releases as grouped bars, which is the right
shape for "did this release move". This one is for the other question — "where did the curve move
over the last N releases" — and it takes the same `memory-same-session.txt` file, so both read the
one table that is actually comparable column to column.

Why not `tools/plot-results.py`: that script overlays the per-release `memory.txt` captures, and
those were each taken on the day their release was cut. Two of them differing by 5 % says nothing,
because the machine was different. Every column here was measured in one sitting, switching only
`-Dprocessor.version`, which is the only way a step of a few per cent means anything.

Emits `load-tests/results/_plots/alloc-release-history-<version>.png`.

Usage:
    python tools/plot-release-history.py --version 1.3.7-SNAPSHOT
"""
import argparse
import re
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "load-tests" / "results"

# N=10 is JIT warmup and classloading rather than processor cost. See the header of any
# memory-same-session.txt for the two runs of one build that differed by 4x there.
EXCLUDED_N = {10}

# Repeat runs of one build agree to within this on this harness, so a step smaller than it is not
# a step. Drawn on the chart rather than left to the commit message.
NOISE_FLOOR_PCT = 0.6

HEADER = re.compile(r"^\s*N\s+(.*\S)\s*$")
ROW = re.compile(r"^\s*(\d+)\s+((?:\d+\s+)*\d+)\s*$")

SERIES_COLOURS = {100: "#8fa3b8", 500: "#3d6b99", 1000: "#1f7a4d"}
STEP_COLOUR = "#c0392b"


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


def largest_step(labels: list[str], values: list[float]) -> tuple[int, float] | None:
    """The index and size, in percent, of the biggest release-to-release move."""
    if len(values) < 2:
        return None
    steps = [(i, 100 * (values[i] - values[i - 1]) / values[i - 1]) for i in range(1, len(values))]
    index, pct = max(steps, key=lambda s: abs(s[1]))
    return (index, pct) if abs(pct) > NOISE_FLOOR_PCT else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True,
                        help="the results directory holding memory-same-session.txt")
    args = parser.parse_args()

    source = RESULTS / args.version / "memory-same-session.txt"
    if not source.exists():
        sys.exit(f"no same-session comparison for {args.version}: {source}")

    labels, data = parse_table(source.read_text(encoding="utf-8"), "OverheadAlloc (KB)")
    ns = sorted(n for n in data if n not in EXCLUDED_N)
    if not ns:
        sys.exit("every N was excluded — nothing to plot")

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.2))
    fig.suptitle(
        f"VibeTags processor allocation across {len(labels)} releases, one session\n"
        "MemoryVolumeStressTest, same machine and JDK throughout, switching only "
        "-Dprocessor.version (overhead = processorAlloc − baselineAlloc)",
        fontsize=10,
    )

    x = list(range(len(labels)))

    # Panel 1 — absolute MB. Log y because N=100 and N=1000 are an order of magnitude apart and
    # the shape of each line matters more than the gap between them.
    for n in ns:
        ax1.plot(x, [data[n][i] / 1024 for i in x], marker="o", markersize=4,
                 color=SERIES_COLOURS.get(n, "#777777"), label=f"N={n} classes", zorder=3)
    ax1.set_yscale("log")
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels, rotation=45, ha="right", fontsize=8)
    ax1.set_ylabel("Allocation overhead (MB, log scale, lower is better)")
    ax1.set_title("Absolute allocation overhead", fontsize=10)
    ax1.grid(True, linestyle="--", alpha=0.35, zorder=0)
    ax1.legend(fontsize=8, framealpha=0.9)

    # Panel 2 — each series indexed to its own first release, so one linear axis carries all three
    # and a step common to every N is visibly the processor rather than one noisy row.
    for n in ns:
        base = data[n][0]
        ax2.plot(x, [100 * data[n][i] / base for i in x], marker="o", markersize=4,
                 color=SERIES_COLOURS.get(n, "#777777"), label=f"N={n} classes", zorder=3)

    ax2.axhline(100, color="black", linewidth=0.8, zorder=2)
    ax2.axhspan(100 - NOISE_FLOOR_PCT, 100 + NOISE_FLOOR_PCT, color="#000000", alpha=0.07, zorder=1)

    step = largest_step(labels, [data[ns[-1]][i] / 1024 for i in x])
    if step is not None:
        index, pct = step
        ax2.axvline(index, color=STEP_COLOUR, linewidth=1.0, linestyle=":", zorder=2)
        ax2.annotate(
            f"{labels[index]}: {pct:+.1f}% at N={ns[-1]}",
            xy=(index, 100 * data[ns[-1]][index] / data[ns[-1]][0]),
            xytext=(6, 10), textcoords="offset points",
            fontsize=8, color=STEP_COLOUR,
        )

    ax2.set_xticks(x)
    ax2.set_xticklabels(labels, rotation=45, ha="right", fontsize=8)
    ax2.set_ylabel(f"Allocation overhead, indexed to {labels[0]} = 100")
    ax2.set_title("Same data, each series indexed to its own first release", fontsize=10)
    ax2.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)
    ax2.legend(fontsize=8, framealpha=0.9)
    fig.text(
        0.5, 0.015,
        f"Shaded band is the ±{NOISE_FLOOR_PCT} % run-to-run floor this harness reproduces at. "
        "A move inside it is not a change.",
        ha="center", fontsize=8, style="italic", color="#555555",
    )

    fig.tight_layout(rect=(0, 0.045, 1, 0.92))
    out = RESULTS / "_plots" / f"alloc-release-history-{args.version}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)

    print(f"Wrote {out}")
    for i, label in enumerate(labels):
        print(f"  {label:>16}: " + "  ".join(f"N={n} {data[n][i] / 1024:7.1f} MB" for n in ns))


if __name__ == "__main__":
    main()
