# VibeTags Multi-Module Example

A three-module Maven reactor (`core` → `engine` → `cli`) that runs the VibeTags annotation
processor in **every** module, with all modules sharing the reactor root as the VibeTags root.
It mirrors the setup of real multi-module consumers (blindbean, codekarta) and serves as the
regression example for
[issue #278](https://github.com/PIsberg/vibetags/issues/278): before the fix, the monolithic
guardrail files (`CLAUDE.md`, `.cursorrules`, `llms.txt`) only kept the annotations of the
**last** module compiled.

## Layout

| Module | Annotations |
|---|---|
| `core` | `@AIDomainModel`, `@AILocked`, `@AIImmutable` |
| `engine` | `@AIExtensible`, `@AIThreadSafe` |
| `cli` | `@AIAudit`, `@AITestDriven`, `@AIContract`, `@AIPure`, `@AIIdempotent` |

The parent POM passes `-Avibetags.root=${maven.multiModuleProjectDirectory}` (anchored by the
`.mvn/` directory) so every module writes to the same shared root.

## Build

Install the library first (see the repository root README), then:

```bash
mvn clean verify
```

After the build, `CLAUDE.md`, `.cursorrules`, and `llms.txt` contain one
`VIBETAGS-MODULE: <module>` sub-block per module — entries from `core`, `engine`, **and**
`cli` all survive, regardless of reactor order. The per-module `.vibetags-mod-*` sidecar
files at this root are the aggregation mechanism; they are safe to delete (the next build
recreates them).


## Tiers demonstrated

This reactor uses the **merged** Tier-1 root plus **Tier-2** per-module files:

- **Tier 1 — merged root** (`CLAUDE.md`, `.cursorrules`, `llms.txt`): every module's guardrails
  embedded in one always-loaded block (the #278 regression — all modules survive).
- **Tier 2 — per-module** (`core/CLAUDE.md`, `engine/CLAUDE.md`, `cli/CLAUDE.md`): each carries only
  *that* module's guardrails, loaded when you work inside the module.

For the **lean indexed** Tier-1 root (one pointer per module instead of the full merge) paired with
**Tier-3** per-module scoped rules, see the sibling
[`../example-multimodule-indexed`](../example-multimodule-indexed). The repository README's
*Organizing Context Files* section explains all three tiers and when to use each (and why a **root**
`.claude/rules/` belongs to single-module projects, not reactors —
[#295](https://github.com/PIsberg/vibetags/issues/295)).
