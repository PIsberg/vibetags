# Data Model: Route Test-Code Guardrails to TESTING.md

No database. The "data" is what one compilation round knows, what it persists for its siblings,
and what ends up in files.

## Round

One javac invocation over one module and one source set.

| Field | Type | Source | Notes |
|---|---|---|---|
| `regionId` | string | `ModuleSidecar.computeModuleId` or `-Avibetags.module` | names the module's region in shared files |
| `sourceSet` | string | `ModuleIdentity.sourceSet()` | `main` when unresolved or when the round mixes source sets |
| `isTestRound` | boolean, derived | `ModuleIdentity.isTestSourceSet()` | true for `test`, `*Test`, `*Tests`, `testFixtures` |
| `testingOptedIn` | boolean, derived | `activeServices.contains("testing")` | file presence at the root, nothing else |
| `routed` | boolean, derived | `isTestRound && testingOptedIn` | the only condition under which output differs from today |

Validation: `routed` is false whenever `TESTING.md` is absent, so every derived behaviour below is
unreachable for existing users (FR-002).

## Guardrail model views

Pure, compiler-free projections of the existing `GuardrailModel`. They select buckets; they do
not copy, reorder or re-escape anything.

| View | Contents | Used for |
|---|---|---|
| full | every bucket, as today | every service in a non-routed round; non-routed services always; `~tfull~` |
| `safetyOnly()` | `@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure` buckets | routed services in a routed round |
| `withoutSafety()` | the other 38 buckets | the `testing` service in a routed round |

Invariants:
- `safetyOnly()` and `withoutSafety()` partition the full view: every annotated reference is in
  exactly one. A unit test asserts the union equals the full view and the intersection is empty,
  over all 44 annotations, so a 45th annotation cannot fall through both.
- The six safety annotations are named once, in the place the lean root index's safety digest
  already names them; this feature must not introduce a second list.
- Transitive (dependency JAR) guardrails are never test-origin. They stay with the full and
  safety views of main rounds and are excluded from `withoutSafety()` in a routed round.

## Service classification

| Class | Rule | Behaviour in a routed round |
|---|---|---|
| `testing` | key `testing`, file `TESTING.md` | renders `withoutSafety()`; `null` in any other round |
| routed instruction aggregate | `ServiceRegistry.routesTestGuardrails(key)` | renders `safetyOnly()` + pointer; full rendering kept as `~tfull~` |
| everything else | ignore files, JSON/TOML/YAML configs, `*_safety`, locks, `llms`, granular directories | unchanged, full view |

## Sidecar (persisted, `.vibetags-mod-<region>[__<sourceSet>]`)

| Key | Value | Written when | Read when |
|---|---|---|---|
| `<service>` (existing) | rendered body; in a routed round this is the routed body | always | always |
| `testing` (existing namespace, new service) | `withoutSafety()` rendering | routed round with at least one non-safety reference | merging `TESTING.md` |
| `~tfull~<service>` (new) | unrouted rendering of a routed service | routed round only | merge, when `testing` is not opted in |

Transient, never persisted (the `rootIndexMode` precedent): `testingOptedIn`, set on every sidecar
by `readAll(root)`.

State transitions of one module's test sidecar:

```text
                 test round, TESTING.md absent
   (none) ─────────────────────────────────────────▶ UNROUTED  bodies = full, no testing, no ~tfull~
      │                                                 │  ▲
      │ test round, TESTING.md present                  │  │ test round, TESTING.md absent
      ▼                                                 ▼  │
   ROUTED  bodies = safety-only + pointer, testing body, ~tfull~ = full
```

Merge reads, by state and current opt-in:

| Sidecar state | `TESTING.md` now | Body merged into a routed service | Merged into `TESTING.md` |
|---|---|---|---|
| UNROUTED | absent | full | n/a |
| UNROUTED | present (just created, test round not yet re-run) | full, lossless | nothing yet |
| ROUTED | present | safety-only + pointer | `testing` body |
| ROUTED | absent (just deleted, test round not yet re-run) | `~tfull~` | n/a |

Every cell keeps every guardrail in at least one loaded or discoverable file; that is SC-005.

## Pointer

Constant text, one per routed body, present only when the round's `withoutSafety()` view is
non-empty. Exact wording is fixed in [contracts/testing-md-output.md](contracts/testing-md-output.md).
