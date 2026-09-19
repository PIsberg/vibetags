# Architecture: Design History

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Design History

The documents below are point-in-time records, kept for provenance. They describe decisions as
they were made, not the system as it is; for current behaviour, the reference docs win.
[archive/README.md](../archive/README.md) is the routing table: it says when each was retired and
where its current answer lives.

- [archive/SPEC.md](../archive/SPEC.md) — the pre-1.0 design specification for parallel test
  execution and the annotations added in that initiative. Implemented; superseded by the
  reference docs.
- [archive/PLAN.md](../archive/PLAN.md) — the step-by-step execution plan for that same initiative.
  All shipped.
- [archive/transitive-guardrails.md](../archive/transitive-guardrails.md) — the design proposal
  behind guardrails inherited from dependencies. Shipped in 1.2.0.
- [archive/CONCEPT_PLUGIN.md](../archive/CONCEPT_PLUGIN.md) — the proposed core/CLI/plugin split.
  Deliberately not built; `vibetags-cli` shipped instead.
- [diagrams/archive/](../diagrams/archive/README.md) — superseded diagram generations, kept so a
  reader of an old release can see what its docs pictured.

Ideas that have not shipped are the other direction and are not history:
[proposals/](../proposals/) holds those. Dated analyses that were never reference material
(benchmark captures, audits, surveys) live under `analysis/` at the repository root, dated in the
filename.
