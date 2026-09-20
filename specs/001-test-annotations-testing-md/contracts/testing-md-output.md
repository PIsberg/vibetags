# Contract: TESTING.md output and the pointer

What a VibeTags user can rely on. Each clause names the test that will pin it.

## Opt-in

- `TESTING.md` at the VibeTags root (the JVM working directory, or `-Avibetags.root`). An empty
  file is enough: `touch TESTING.md`.
- VibeTags never creates it and never deletes it. `TestingMdLifecycleEndToEndTest`
- Its presence does not count as an AI config file for the `AGENTS.md` sole-fallback rule.
  `AgentsMdSoleFallbackTest`

## File shape

```markdown
(hand-written content, preserved)

<!-- VIBETAGS-START -->
(generated header)
(short preamble: these guardrails apply to test code; safety guardrails for test code are in the
 always-loaded instruction files)
(non-safety guardrails from test source sets, in an existing Markdown aggregate layout)
<!-- VIBETAGS-END -->

(hand-written content, preserved)
```

- Markers are the standard Markdown pair; content outside them is never changed.
  `MarkerInjectionTest`, `GuardrailFileRecoveryEndToEndTest`
- In a reactor, each contributing module is wrapped in the standard `VIBETAGS-MODULE`
  sub-markers, exactly as in the other Markdown aggregates; a single module has none.
- Interpolated values are encoded by the same `Escape` calls as the delegate layout. No value is
  interpolated by new code.
- Contains none of `@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`.
  `TestingMdRoutingEndToEndTest`

## What moves, what does not

| Annotation on test code | `TESTING.md` present | `TESTING.md` absent |
|---|---|---|
| six safety annotations | always-loaded files, unchanged | always-loaded files |
| the other 38 | `TESTING.md` only | always-loaded files |

Annotations on main code never move. "Test code" is a compilation round whose source set is
`test`, ends in `Test` or `Tests`, or is `testFixtures`. A round that compiles main and test
sources together counts as main and is not routed.

## Routed services

`ServiceRegistry.routesTestGuardrails(key)` is true when the service writes one file with
markers, declares no YAML merge shape, is not an ignore file, and is not `testing`, a `*_safety`
file, `.vibetags-locks` or an `llms` file. The resulting set is pinned key by key in
`ServiceRoutingContractTest`; a new platform fails that test until it is classified.

Not routed, by design: ignore files (an `@AIIgnore` on a test file still excludes it), JSON, TOML
and YAML tool configuration, granular rule directories.

## Pointer

Appended to a routed service's body by a routed round that moved at least one guardrail:

```text
Guardrails for test code are in TESTING.md. Read it before modifying anything under a test source set.
```

- One per module region; therefore exactly one per file in a single-module project.
- Absent when `TESTING.md` is absent, and absent for a module whose test code has no non-safety
  guardrails.
- Rendered inside the service's generated region, in that platform's comment or prose
  convention; never inside a structured element where it would be parsed as a rule.

The wording is a contract once released: consumers' committed files contain it, so changing it
rewrites a generated file in every consuming build.
