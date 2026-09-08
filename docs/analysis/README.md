# Dated analyses

One-off audits and surveys, each a record of what was true on the day it ran. Nothing here is
maintained. A file in this directory is dated in its own filename, is never updated afterwards,
and does not get re-checked against the code, so when it disagrees with a reference doc under
[`docs/`](../README.md) or with a CI gate, the reference doc and the gate win.

They are kept rather than deleted because the finding that prompted a change is evidence of why
the change looks the way it does, and re-running the audit costs more than storing its output.
The same rule governs [`archive/`](../archive/README.md) and
[`diagrams/archive/`](../diagrams/archive/README.md).

| Document | Question it answered | Ran | Where the current answer lives |
|---|---|---|---|
| [`2026-08-15-health-scorecard.md`](2026-08-15-health-scorecard.md) | How this repository scores against the 33-row scorecard in *Vibe Architecture*, Appendix A, and what it would take to close each gap. | 2026-08-15, in two passes | The gates the scorecard names, not the scorecard: [`WORKFLOW.md`](../WORKFLOW.md) for what CI runs, and [`CHANGELOG.md`](../CHANGELOG.md) 1.2.2 § Added for what shipped from it. |

A survey that is expected to be re-run on a schedule belongs in the Evidence table of
[`docs/README.md`](../README.md#evidence) instead, as
[`vibetags-in-practice.md`](../vibetags-in-practice.md) is. This directory is for the ones that
run once.
