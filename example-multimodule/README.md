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
