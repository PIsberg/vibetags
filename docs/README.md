# Documentation index

Every document in this repository, and the question each one answers. Start here rather than
guessing at a filename: the point of this page is that you open one document instead of three.

The set is in three tiers, and the tier decides how much to trust a claim:

| Tier | Where | Maintained? |
|---|---|---|
| Reference | `docs/*.md` | Yes. Kept true by CI; a stale claim here is a defect. |
| Proposals | [`docs/proposals/`](proposals/) | Only until the idea ships or is dropped. Describes what does not exist yet. |
| Archive | [`docs/archive/`](archive/README.md) | No. Frozen provenance; a reference doc always wins over it. |

Headline project numbers (annotations, platforms, generated files) are stated once, in the
README's [project facts](../README.md#project-facts) line, and pinned by
`ProjectFactsConsistencyTest`. No other document restates them, so nothing else can drift.

## Entry points

Read one of these before anything under `docs/`.

| Document | Audience | What it gives you |
|---|---|---|
| [`README.md`](../README.md) | Everyone | What VibeTags is, install snippets, the platform table, the pinned project facts. |
| [`USAGE.md`](../USAGE.md) | Consumers | How to add VibeTags to a project, and a worked example for every annotation. |
| [`CLAUDE.md`](../CLAUDE.md) | Agents | The briefing: build commands, the Tier-1 invariants with the test that enforces each, and the routing list. It is tool-neutral despite the name; `AGENTS.md` and `GEMINI.md` are shorter files that route to it. |
| [`llms.txt`](../llms.txt) | Agents | The machine-readable orientation file, in the llms.txt format VibeTags also generates for consumers. |

## Reference documents

### Using VibeTags

| Document | Answers |
|---|---|
| [`ANNOTATIONS.md`](ANNOTATIONS.md) | What each `@AI*` annotation means, its attributes, and which compile-time validation warnings it can raise. Read before adding or changing an annotation. |
| [`PLATFORMS.md`](PLATFORMS.md) | Which generated file belongs to which tool, in which format, and which platforms take granular per-element rules. Read when a specific output file is the question. |
| [`MULTI-MODULE.md`](MULTI-MODULE.md) | Reactor builds: sidecar merge, per-module output, `.vibetags-root-index`, `.vibetags-roles`, `.vibetags-mirror`, and the granular file layout. |
| [`JVM-LANGUAGES.md`](JVM-LANGUAGES.md) | Kotlin, Groovy, Scala and Clojure: what works, what is silently lost, and how each rating was measured rather than claimed. |

### How it works

| Document | Answers |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The deep dive: system diagram, data flow, design decisions, limitations, repository layout. The longest document here; prefer `LOAD-BEARING.md` if you only need the rules. |
| [`LOAD-BEARING.md`](LOAD-BEARING.md) | The processing flow, the marker rules, the scoped-rules index, the internal class map, and every Tier-1 invariant stated in full with its reasoning. |
| [`PROCESSOR.md`](PROCESSOR.md) | Processor options, the write cache and fingerprint short-circuit, check mode, `.vibetags-locks`, transitive guardrails, and SPI/Gradle incremental support. |
| [`LOGGING.md`](LOGGING.md) | The logging contract behind invariant 15: `domain.event key=value`, `reason=` on every skip. Short, and load-bearing when adding a log line. |

### Changing it

| Document | Answers |
|---|---|
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | The contribution workflow and the gates a change must pass. |
| [`TESTS.md`](TESTS.md) | Which test class covers what, the fast/e2e tier split, and the coverage rules `CoverageGateTest` enforces. Read this before writing a test, not after. |
| [`WORKFLOW.md`](WORKFLOW.md) | What CI actually runs, step by step, and why each verification exists. |
| [`DEPENDENCIES.md`](DEPENDENCIES.md) | Every third-party artifact, why it is here, what ships to consumers, and what only runs the build. |
| [`RELEASING.md`](RELEASING.md) | The release process end to end, including which files carry a version, which `scripts/set-version.sh` rewrites, and which are still updated by hand. |
| [`CHANGELOG.md`](CHANGELOG.md) | What each release changed and why. The largest file in the repository; search it, do not read it. |

### Evidence

| Document | Answers |
|---|---|
| [`../evals/README.md`](../evals/README.md) | Whether the rules in `CLAUDE.md` actually bind an agent, measured rather than assumed. |
| [`vibetags-in-practice.md`](vibetags-in-practice.md) | A survey of annotation and platform usage across five real consumer codebases. A dated snapshot (2026-07-16), not a live count. |
| [`../corpus/README.md`](../corpus/README.md) | What the third-party corpus runs assert, and what they have found. |
| [`../load-tests/README.md`](../load-tests/README.md) | The benchmark harness: what each category measures and how to capture a release baseline. |

### Community

[`SECURITY.md`](SECURITY.md) for reporting a vulnerability, [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
for the community standard.

## Not reference material

- [`proposals/`](proposals/) holds ideas that have not shipped.
  [`proposed-annotations.md`](proposals/proposed-annotations.md) is the evidence base for
  candidate annotations, reverse-engineered from hand-written `CLAUDE.md` files across 225
  open-source repositories; five of its candidates shipped in 1.0.0 and the rest are still
  proposals.
- [`archive/`](archive/README.md) holds superseded specs, plans and implemented proposals, with a
  table saying when each was retired and where the current answer lives.
- [`diagrams/`](diagrams/) holds the hand-drawn PlantUML sources and the code-karta SVGs that CI
  regenerates and fails on drift; [`diagrams/archive/`](diagrams/archive/README.md) holds the
  generations they replaced.
- `analysis/` at the repository root holds dated one-off audits and surveys, dated in the
  filename. They are records of a day's findings and are never updated.

## Loading this efficiently

Per-element guardrails are *not* in this list. They live in `.claude/rules/`, indexed from the
generated block in `CLAUDE.md`, and load on demand when a matching source file is opened. An
agent should not read them ahead of time.

For a typical task the working set is `CLAUDE.md` plus exactly one document from the tables
above. Two of them earn a deliberate decision before loading, because each is several times the
size of a typical reference doc: reach for `LOAD-BEARING.md` before `ARCHITECTURE.md`, since it
answers most of what sends people to the longer file, and search `CHANGELOG.md` for the release
or symbol you care about rather than opening it.
