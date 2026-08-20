# VibeTags Multi-Module Example

A five-module Maven reactor (`core` → `engine` → `cli`, plus `showcase` and `tests`) that runs the
VibeTags annotation processor in **every** module, with all modules sharing the reactor root as the
VibeTags root.
It mirrors the setup of real multi-module consumers (blindbean, codekarta) and serves as the
regression example for
[issue #278](https://github.com/PIsberg/vibetags/issues/278): before the fix, the monolithic
guardrail files (`CLAUDE.md`, `.cursorrules`, `llms.txt`) only kept the annotations of the
**last** module compiled.

## Layout

| Module | Annotations | Why it is here |
|---|---|---|
| `core` | 3 — `@AIDomainModel`, `@AILocked`, `@AIImmutable` | The dependency root every other module compiles against |
| `engine` | 2 — `@AIExtensible`, `@AIThreadSafe` | A middle module, so the merge has to survive more than two contributors |
| `cli` | 5 — `@AIAudit`, `@AITestDriven`, `@AIContract`, `@AIPure`, `@AIIdempotent` | The leaf, and usually the last to compile — the module that used to win under last-writer-wins |
| `showcase` | **all 44** | Carries the full annotation set so the merged root has real volume to merge. `ExampleCoverageTest` fails the build if an annotation is added to the library without appearing here |
| `tests` | 0 | A module with no annotations at all: it must not erase the others' contributions, and it exercises the `.vibetags-mirror` cross-module mirroring |

The small per-module counts are deliberate. Spreading a few distinct annotations across `core`,
`engine` and `cli` is what makes a lost contribution obvious in the merged output — if `core`'s
`@AILocked` disappears from the root `CLAUDE.md`, that is visible at a glance in a way it would not
be inside a module carrying forty-four.

The parent POM passes `-Avibetags.root=${maven.multiModuleProjectDirectory}` (anchored by the
`.mvn/` directory) so every module writes to the same shared root.

## Verifying the guardrails (check mode)

```bash
mvn clean compile                          # regenerate
mvn clean compile -Dvibetags.check=true    # verify, and FAIL if anything drifted
```

This is what a consumer runs in CI, and it is the answer to "how do I test my guardrails" — not a
test you write, but a flag that makes the compiler check them. In check mode the processor writes
nothing; it compares what the annotations *would* generate against what is on disk and fails the
build on any difference, naming the files.

The parent POM wires it as `-Avibetags.check=${vibetags.check}`, defaulting to `false`, so a normal
build still regenerates. CI runs both: the plain build, then the check.

Try it — edit any `reason = "…"` in a module, then run the check without regenerating. The build
fails and lists every file the edit should have touched.

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
[`../examples/multimodule-indexed`](../multimodule-indexed). The repository README's
*Organizing Context Files* section explains all three tiers and when to use each (and why a **root**
`.claude/rules/` belongs to single-module projects, not reactors —
[#295](https://github.com/PIsberg/vibetags/issues/295)).

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
