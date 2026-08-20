# VibeTags Multi-Module Example — Indexed Root (Tier 1 index + Tier 3 per-module)

A two-module reactor (`core` → `app`) demonstrating the **lean indexed root** layout: the recommended
way to keep a multi-module project's always-loaded context small. It is the companion to
[`../multimodule`](../multimodule) (the **merged** root) — same reactor mechanics,
opposite Tier-1 strategy. Background: [issue #295](https://github.com/PIsberg/vibetags/issues/295).

## The tiers, as wired here

| Tier | Scope | Opt-in here | What you get |
|---|---|---|---|
| **Tier 1 — Project** | reactor root | `CLAUDE.md` + **`.vibetags-root-index`** | `CLAUDE.md` is a lean **index**: one pointer per module, no embedded detail |
| **Tier 3 — Element/topic** | per module | `core/.claude/rules/` + `core/.vibetags-roles` (and `app/…`) | role-grouped topic files (`domain-model.md`, `services.md`) that load on-demand |

`GEMINI.md` is *also* opted in at the root, but Gemini has **no scoped-rules feature**, so it keeps the
full **merged** block — showing that the index is applied per-platform, only where the tool can
auto-load the scoped rules.

> The `.vibetags-root-index` marker is what turns the root aggregate into an index. Delete it and the
> next build re-embeds the full merge (the `../multimodule` layout).

## Annotation coverage

Both modules together exercise **every one of the [44 annotations](../../README.md#project-facts)**, which
is what makes this reactor a useful test of the index rather than a toy: the index only earns its keep
when there is enough detail to be worth deferring. `ExampleCoverageTest` fails the build if an
annotation is added to the library without appearing here.

That coverage is also what makes the two tiers visible side by side. The **safety** buckets
(`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`) stay inline in the root
`CLAUDE.md` — a guardrail that only loads once the agent opens the file it protects has become a
comment. Everything else — the contracts, the thread affinities, the banned APIs, the observability
notes — collapses to a one-line module pointer and loads from `core/.claude/rules/` or
`app/.claude/rules/` when a matching source file is opened.

`.claudeignore` and `.copilotignore` are opted in at the root so `@AIIgnore` has somewhere to write;
without them the processor emits a NOTE saying the annotation is used but no ignore file exists.

## Build

Requires the in-development processor (indexed root landed in RC6). Install the library first (see the
repository root README), then:

```bash
mvn clean compile
```

## What to look at after building

- **`CLAUDE.md`** — the block between the VibeTags markers is a handful of lines: one
  `VIBETAGS-MODULE: <module>` pointer per module, each saying the detail lives in that module's
  `.claude/rules/`. No per-element guardrails inline.
- **`GEMINI.md`** — the full merged block, every module's guardrails embedded (the non-indexed form).
- **`core/.claude/rules/domain-model.md`**, **`app/.claude/rules/services.md`** — the actual Tier-3
  detail, each with a `paths:` glob so an editor loads it only when a matching source file is open.

## When to use this layout

Use the indexed root in a **multi-module reactor** where each module owns its own `.claude/rules/`.
It keeps the always-loaded root lean while the detail loads on demand. Prefer the **merged** root
(`../multimodule`) when your tooling can't auto-load scoped rules, or you want every guardrail
visible at launch. A **root** `.claude/rules/` is *not* the tool for a reactor — see the tiers section
in the repository README.

### Seeing why a file was written

```bash
mvn clean compile -Dvibetags.log.level=DEBUG   # narrate every decision to vibetags.log
```

INFO reports what ran. DEBUG adds one structured event per decision, which is what you read when a
file changed and you expected it not to, or the reverse:

```
sidecar.save id=core region=core bodies=29 moduleBodies=1 stems=2 elements=2
sidecar.read count=4 regions=4 ids=[cli, core, engine, showcase]
merge.wholefile service=mentat contributions=4 bytes=10782
merge.skip service=cody reason=no-whole-file-merger file=config.json
write.skip file=CLAUDE.md reason=cache-unchanged bytes=2481
```

The counts are the point. `contributions=4` says every module reached the merged JSON; a `1` there
means the file holds one module's view of the reactor, which is a well-formed document and a wrong
one. Every `.skip` carries a `reason=`, so "why was nothing written?" is a grep rather than a
debugger.
