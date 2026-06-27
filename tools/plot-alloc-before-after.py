#!/usr/bin/env python3
"""
Render a before/after allocation comparison for the 1.0.0-RC1 processing
optimizations (skip absent-annotation javac scans + skip Tree API position
resolution when the .vibetags-locks report is not opted in).

Reads two `MemoryVolumeStressTest` result tables — a baseline and an optimized
run captured back-to-back on the same machine — and emits a two-panel PNG:

  * left  : processor allocation overhead (MB) vs N, baseline vs optimized
  * right : percentage allocation reduction at each N

Usage:
    python tools/plot-alloc-before-after.py
"""
from pathlib import Path
import re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "load-tests" / "results" / "1.0.0-RC1"
OUT = REPO / "load-tests" / "results" / "_plots" / "alloc-before-after-1.0.0-RC1.png"

ROW = re.compile(r"^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$")


def load_overhead(path):
    """Returns {N: overheadAllocKB} from a MemoryVolumeStressTest table."""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            n = int(m.group(1))
            overhead_kb = int(m.group(4))
            out[n] = overhead_kb
    return out


def main():
    base = load_overhead(RESULTS / "memory-baseline.txt")
    opt = load_overhead(RESULTS / "memory.txt")
    ns = sorted(set(base) & set(opt))

    base_mb = [base[n] / 1024 for n in ns]
    opt_mb = [opt[n] / 1024 for n in ns]
    reduction_pct = [100 * (base[n] - opt[n]) / base[n] for n in ns]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.3))
    fig.suptitle(
        "VibeTags 1.0.0-RC1 — processor allocation overhead, baseline vs optimized\n"
        "(skip absent-annotation scans in collect + validate, skip unused Tree API "
        "position resolution, pre-size renderer buffers)",
        fontsize=10,
    )

    # Panel 1 — absolute overhead
    ax1.plot(ns, base_mb, "o-", color="#c0392b", label="baseline", linewidth=2)
    ax1.plot(ns, opt_mb, "s-", color="#27ae60", label="optimized", linewidth=2)
    ax1.set_xscale("log")
    ax1.set_xlabel("N annotated classes (log scale)")
    ax1.set_ylabel("Allocation overhead (MB)")
    ax1.set_title("Processor-attributable allocation")
    ax1.grid(True, which="both", alpha=0.3)
    ax1.legend()
    ax1.set_xticks(ns)
    ax1.set_xticklabels([str(n) for n in ns])

    # Panel 2 — % reduction
    bars = ax2.bar([str(n) for n in ns], reduction_pct, color="#2980b9")
    ax2.set_xlabel("N annotated classes")
    ax2.set_ylabel("Allocation reduction (%)")
    ax2.set_title("Reduction vs baseline")
    ax2.grid(True, axis="y", alpha=0.3)
    for bar, pct in zip(bars, reduction_pct):
        ax2.annotate(f"{pct:.1f}%",
                     (bar.get_x() + bar.get_width() / 2, bar.get_height()),
                     ha="center", va="bottom", fontsize=9)
    ax2.set_ylim(0, max(reduction_pct) * 1.25)

    fig.tight_layout(rect=[0, 0, 1, 0.90])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT, dpi=130)
    print(f"Wrote {OUT}")
    for n in ns:
        print(f"  N={n:<5} baseline={base[n]/1024:7.1f} MB  optimized={opt[n]/1024:7.1f} MB  "
              f"reduction={100*(base[n]-opt[n])/base[n]:5.1f}%")


if __name__ == "__main__":
    main()
