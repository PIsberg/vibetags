# VibeTags Multi-Module Example — Indexed Root (Tier 1 index + Tier 3 per-module)

A two-module reactor (`core` → `app`) demonstrating the **lean indexed root** layout: the recommended
way to keep a multi-module project's always-loaded context small. It is the companion to
[`../example-multimodule`](../example-multimodule) (the **merged** root) — same reactor mechanics,
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
> next build re-embeds the full merge (the `../example-multimodule` layout).

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
(`../example-multimodule`) when your tooling can't auto-load scoped rules, or you want every guardrail
visible at launch. A **root** `.claude/rules/` is *not* the tool for a reactor — see the tiers section
in the repository README.
