#!/usr/bin/env python3
"""
Render the figure that splits "processor overhead" into javac's share and VibeTags'.

Reads the measured table in `load-tests/results/<version>/processor-tax.txt` and emits
`load-tests/results/_plots/processor-tax-<version>.png`.

Usage:
    python tools/plot-processor-tax.py
    python tools/plot-processor-tax.py --version 1.0.0-RC9
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

PROC_NONE = "#d8dee6"     # javac alone — context, not cost
AP_TAX = "#8fa3b8"        # the price of any annotation processor
VIBETAGS = "#1f7a4d"      # the only part this codebase can move

ROW = re.compile(
    r"^\s*(\S+)\s+(?:run\s+(\w+)\s+)?(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)%\s*$")


def parse(path: Path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            version, run, proc_none, no_op, full, ap_tax, share, pct = m.groups()
            rows.append({
                "label": version + (f" ({run})" if run else ""),
                "version": version,
                "procNone": int(proc_none) / 1024,
                "apTax": int(ap_tax) / 1024,
                "share": int(share) / 1024,
                "pct": float(pct),
            })
    if not rows:
        sys.exit(f"no measurement rows parsed from {path}")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="1.0.0-RC9")
    args = parser.parse_args()

    source = RESULTS / args.version / "processor-tax.txt"
    if not source.exists():
        sys.exit(f"no processor-tax measurement for {args.version}: {source}")
    rows = parse(source)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.6))
    fig.suptitle(
        "Where the \"processor overhead\" actually goes — 1000 annotated classes\n"
        "Three compiles of the same sources: annotation processing off, on-but-doing-nothing, "
        "and VibeTags",
        fontsize=10)

    # Panel 1 — stacked composition per measured run.
    labels = [r["label"] for r in rows]
    y = range(len(rows))
    base = [r["procNone"] for r in rows]
    tax = [r["apTax"] for r in rows]
    share = [r["share"] for r in rows]

    ax1.barh(y, base, color=PROC_NONE, edgecolor="white", label="javac alone (-proc:none)", zorder=3)
    ax1.barh(y, tax, left=base, color=AP_TAX, edgecolor="white",
             label="cost of running ANY processor", zorder=3)
    ax1.barh(y, share, left=[b + t for b, t in zip(base, tax)], color=VIBETAGS,
             edgecolor="white", label="VibeTags itself", zorder=3)
    for i, r in enumerate(rows):
        ax1.text(r["procNone"] + r["apTax"] + r["share"] + 8, i, f"{r['share']:.0f} MB",
                 va="center", fontsize=8, color=VIBETAGS, fontweight="bold")

    ax1.set_yticks(list(y))
    ax1.set_yticklabels(labels, fontsize=9)
    ax1.invert_yaxis()
    ax1.set_xlabel("Allocation (MB)")
    ax1.set_title("Composition of one compile", fontsize=10)
    ax1.grid(True, axis="x", linestyle="--", alpha=0.35, zorder=0)
    ax1.legend(fontsize=8, loc="lower right", framealpha=0.95)

    # Panel 2 — what the harness reports vs what VibeTags actually costs.
    rc9 = [r for r in rows if r["version"] == args.version]
    reported = sum(r["apTax"] + r["share"] for r in rc9) / len(rc9)
    actual = sum(r["share"] for r in rc9) / len(rc9)
    bars = ax2.bar(["reported as\n\"processor overhead\"", "actually VibeTags"],
                   [reported, actual], width=0.55,
                   color=[AP_TAX, VIBETAGS], edgecolor="white", zorder=3)
    ax2.bar_label(bars, fmt="%.0f MB", fontsize=10, padding=3)
    ax2.set_ylabel("Allocation at N=1000 (MB)")
    ax2.set_title(f"{args.version}: the headline number is ~{reported / actual:.1f}x "
                  f"VibeTags' real cost", fontsize=10)
    ax2.grid(True, axis="y", linestyle="--", alpha=0.35, zorder=0)
    ax2.set_ylim(0, reported * 1.25)
    ax2.text(0.5, -0.22,
             "The difference is javac's annotation-processing subsystem, which no change to this "
             "codebase can move.",
             transform=ax2.transAxes, ha="center", fontsize=8, style="italic", color="#555555")

    fig.tight_layout(rect=(0, 0.04, 1, 0.92))
    out = RESULTS / "_plots" / f"processor-tax-{args.version}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)
    print(f"Wrote {out}")
    for r in rows:
        print(f"  {r['label']:<18} javac={r['procNone']:6.0f} MB  apTax={r['apTax']:6.0f} MB  "
              f"vibetags={r['share']:5.0f} MB  ({r['pct']}%)")


if __name__ == "__main__":
    main()
