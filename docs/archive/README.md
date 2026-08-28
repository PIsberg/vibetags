# Archived documents

Point-in-time records, kept for provenance. Every document here describes a decision as it was
made, not the system as it is. Nothing here is maintained: a claim in this directory was true on
the day it was written and is not re-checked against the code, so if it disagrees with a
reference doc under [`docs/`](../README.md), the reference doc wins.

They are kept rather than deleted because a superseded design is still evidence of why the
current one looks the way it does, and re-deriving that reasoning costs more than storing it.
The same rule already governs [`diagrams/archive/`](../diagrams/archive/README.md).

| Document | Subject | Retired when | Where the current answer lives |
|---|---|---|---|
| [`SPEC.md`](SPEC.md) | The pre-1.0 design specification for thread-isolated logging, parallel test execution, and the annotation wave that came with it. | Implemented pre-1.0 | [`ARCHITECTURE.md` § Concurrency](../ARCHITECTURE.md#concurrency--thread-isolated-logging), [`ANNOTATIONS.md`](../ANNOTATIONS.md), [`TESTS.md`](../TESTS.md) |
| [`PLAN.md`](PLAN.md) | The step-by-step execution plan for that same initiative. All of it shipped. | Implemented pre-1.0 | [`CHANGELOG.md`](../CHANGELOG.md) for what actually shipped, release by release |
| [`transitive-guardrails.md`](transitive-guardrails.md) | The design proposal behind guardrails inherited from a dependency, including the classpath-discovery spike that fixed where the manifest lives. | Shipped in 1.2.0 | [`PROCESSOR.md` § Transitive guardrails](../PROCESSOR.md#transitive-guardrails-dependency-tree-propagation), [`USAGE.md`](../../USAGE.md#transitive-guardrails-rules-that-travel-with-a-dependency) |
| [`CONCEPT_PLUGIN.md`](CONCEPT_PLUGIN.md) | A proposed migration to `vibetags-core` plus a generation-capable CLI plus build plugins. Deliberately not built. | Superseded in 1.1.0 | `vibetags-cli` shipped instead, with two commands that need no core extraction: [`README.md` § Companion CLI](../../README.md#companion-cli-vibetags-cli) |

## Why the spike appendix in `transitive-guardrails.md` survives archiving

It is the one part of an archived document that still constrains today's code. The spike
established that `javac`'s `CLASS_PATH` location skips archive directories that are not package
identifiers, which is why the manifests sit under a Java package path and not under `META-INF/`.
That constraint is enforced by `TransitiveManifestPathTest` and stated as Tier-1 invariant 11 in
[`CLAUDE.md`](../../CLAUDE.md); the appendix is the evidence behind it, not a substitute for it.

## Adding to this directory

A document belongs here once it stops describing the system and starts describing a past
intention: a plan whose work has shipped, a proposal that was implemented, a proposal that was
decided against. Move it with `git mv`, add a row above saying when it was retired and where the
current answer lives, and fix its relative links (a file moving from `docs/` to `docs/archive/`
gains a `../` on every path it points at). Leave the document's own text alone otherwise, since
rewriting it destroys the provenance the archive exists to hold.

What does *not* belong here: dated analyses that were never reference material (benchmark
captures, audits, surveys). Those live under `analysis/` at the repository root, dated in the
filename.
