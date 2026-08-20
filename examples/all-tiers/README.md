# VibeTags Example — All Three Tiers, All Four Levels

The other examples each show one slice of the tier model. This one turns all of it on at once, and
annotates every level a guardrail can attach to, so you can read one class and then find each of its
rules in the file it ended up in.

| Example | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|
| [`../example`](../basic) | root `CLAUDE.md` | — | root `.claude/rules/` |
| [`../multimodule`](../multimodule) | merged root | per-module `CLAUDE.md` | — |
| [`../multimodule-indexed`](../multimodule-indexed) | **indexed** root | — | per-module `.claude/rules/` |
| **this one** | **indexed** root | per-module `CLAUDE.md` | per-module `.claude/rules/`, grouped by role |

## What is opted in, and what each opt-in buys

| Tier | Opt in by creating | Holds |
|---|---|---|
| **Tier 1 — Project** | `CLAUDE.md` + `.vibetags-root-index` | One region per module: that module's **safety** guardrails inline, then a pointer. Always in the agent's context, so it stays short. |
| **Tier 2 — Module** | `billing/CLAUDE.md`, `shipping/CLAUDE.md` | Only that module's guardrails. Loaded when the agent works inside the module; `billing/CLAUDE.md` contains no mention of `shipping`. |
| **Tier 3 — Element/topic** | `billing/.claude/rules/` + `billing/.vibetags-roles` | The verbose per-element detail, grouped into human-named topic files by glob. Loaded only when a matching source file is opened. |

The three never duplicate each other. The **six safety annotations** — `@AILocked`, `@AICore`,
`@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure` — stay inline at Tier 1 because a guardrail that
only loads once the agent opens the file it protects has become a comment. Everything else moves
down and is replaced by a one-line pointer.

Read the generated root `CLAUDE.md` and you will find exactly those six buckets and nothing else.

## All four levels, in one class

`billing/.../InvoiceController.java` carries a guardrail at every level the annotations support:

| Level | Annotation | Where it lands |
|---|---|---|
| **type** | `@AIAudit` | Tier 1, inline (safety) |
| **type** | `@AIContext`, `@AIPublicAPI` | Tier 3, `api-endpoints.md` |
| **instance field** | `@AIPrivacy` on `billingEmail` | Tier 1, inline (safety) |
| **instance field** | `@AISecureLogging` on the same field, `@AIPerformance` on `tenantId` | Tier 3 |
| **method** | `@AILocked` on `invoiceNumber(long)` | Tier 1, inline (safety) |
| **method** | `@AIContract`, `@AIPerformance` on `renderInvoice(…)` | Tier 3 |
| **parameter** | `@AIInputSanitized` on `customerNote` | Tier 3, addressed as `renderInvoice(java.lang.String,java.lang.String)#customerNote` |

The parameter case is the one worth looking at: it is the finest addressing VibeTags produces, and
the only level that cannot be expressed at all by a hand-written rules file that talks about classes.

## Role grouping

Without a `.vibetags-roles` file each annotated class gets its own scoped file
(`com-example-alltiers-billing-InvoiceController.md`). With one:

```
# billing/.vibetags-roles
api-endpoints   = **/*Controller.java
domain-model    = **/*Entry.java
```

you get `api-endpoints.md` and `domain-model.md` instead — a few human-named topic files, which is
the layout Claude's own documentation recommends. Each carries `paths:` front-matter so the editor
loads it only when a matching file is open.

## Build

Install the library first (see the repository root README), then:

```bash
mvn clean compile
```

## Verifying (check mode)

```bash
mvn clean compile -Dvibetags.check=true
```

Check mode writes nothing. It compares what the annotations *would* generate against what is on
disk and fails the build on any difference, naming the files — which is what a consumer runs in CI.
Try editing any `reason = "…"` and running it without regenerating.

## What to look at after building

- **`CLAUDE.md`** — Tier 1. Two module regions, six safety buckets, two pointers. Short on purpose.
- **`billing/CLAUDE.md`** — Tier 2. Billing's guardrails only.
- **`billing/.claude/rules/api-endpoints.md`** — Tier 3. The verbose detail, including the
  parameter-level rule, behind `paths: ["**/*Controller.java"]`.
- **`shipping/.claude/rules/label-printing.md`** — the same shape in the other module, proving the
  split is per-module rather than global.

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
