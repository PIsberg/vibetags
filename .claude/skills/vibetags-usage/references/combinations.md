# VibeTags: Annotation combinations

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Annotation Combinations

| Combination | Result |
|---|---|
| `@AIContext` + `@AIAudit` | Guide implementation AND enforce security checks |
| `@AIDraft` + `@AIContext` | Request implementation with style constraints |
| `@AIPrivacy` (field) + `@AIContext` (class) | Class-level guidance with PII fields protected |
| `@AICore` + `@AIPerformance` | Hot-path core logic with strict complexity rules |
| `@AIContract` + `@AIPerformance` | Contract-frozen signature with performance budget |
| `@AIContract` + `@AIContext` | Frozen signature with guidance on internal implementation |
| `@AILocked` + `@AIDraft` | **Warning**: contradictory — don't combine |
| `@AIIgnore` + `@AIPrivacy` | **Warning**: redundant — `@AIIgnore` already excludes |
| `@AIContract` + `@AIDraft` | **Warning**: contradictory — frozen signature can't need drafting |
| `@AIGenerated` + `@AILocked` | Belt-and-braces on generated output; `@AIGenerated` alone is usually better, since it redirects instead of dead-ending |
| `@AILoadBearing` + `@AIExplain` | Supply the rationale AND require one back for any change |
| `@AIBannedApi` + `@AIArchitecture` | Ban specific symbols AND the layers they live in |
| `@AIThreadAffinity` + `@AIThreadSafe` | **Warning**: contradictory — opposite claims, one is false |
| `@AIGenerated` + `@AIIgnore` | **Warning**: contradictory — generated code must stay readable |
| `@AILoadBearing(suppressAudit)` + `@AIAudit` | **Warning**: contradictory — one suppresses findings, the other mandates them |
| `@AIContract` + `@AILocked` | **Warning**: overlapping intent — consider using only `@AILocked` |
| `@AITestDriven` + `@AIContext` | Enforce TDD workflow AND guide implementation style |
| `@AITestDriven` + `@AIPerformance` | Any change must include tests AND meet complexity constraints |
| `@AITestDriven` + `@AIIgnore` | **Warning**: contradictory — `@AIIgnore` excludes element from AI context |
| `@AITestDriven` + `@AILocked` | **Warning**: contradictory — `@AILocked` prohibits all changes |
| `@AIThreadSafe` + `@AIPerformance` | Concurrent code with strict complexity budget |
| `@AIThreadSafe` + `@AIAudit` | Preserve sync invariant AND audit each change for new bugs |
| `@AIImmutable` + `@AIThreadSafe(IMMUTABLE)` | **Warning**: redundant — `@AIImmutable` already implies thread-safety |
| `@AIDeprecated` + `@AIContext` | Mark for removal AND guide migration approach |
| `@AIDeprecated` + `@AILocked` | **Warning**: contradictory — locked preserves; deprecated routes callers away |
| `@AIObservability` + `@AIPerformance` | Instrumented hot-path code with budget AND dashboard dependencies |
| `@AIObservability` + `@AICore` | Core logic whose metrics feed dashboards — change with extreme caution |
| `@AIRegulation` + `@AIAudit` | Compliance clause AND mandatory security audit |
| `@AIRegulation` + `@AIPrivacy` | PII handler tied to a specific GDPR/HIPAA/PCI-DSS clause |
| `@AIRegulation` + `@AILocked` | Compliance code that must not be modified at all |
| `@AIArchitecture` + `@AIAudit` | Enforce layer boundaries AND audit each change for illegal imports |
| `@AIArchitecture` + `@AIContext` | Layer constraints with guidance on permitted patterns within that layer |
| `@AILegacyBridge` + `@AILocked` | Compatibility shim that must not be touched at all |
| `@AILegacyBridge` + `@AIContext` | Bridge code with guidance on what internal logic *can* be changed |
| `@AIPublicAPI` + `@AIContract` | Whole-class backward-compat rule AND per-method frozen signature (belt-and-suspenders) |
| `@AIPublicAPI` + `@AITestDriven` | Public API change must include tests proving backward compatibility |
| `@AISchemaSafe` + `@AIPrivacy` | Persistent entity with PII fields that must not appear in logs or fixtures |
| `@AISchemaSafe` + `@AIRegulation` | Schema tied to a compliance clause (GDPR erasure table, PCI card-data store) |
| `@AIStrictTypes` + `@AIPerformance` | Typed domain model AND strict complexity budget |
| `@AIStrictTypes` + `@AIRegulation` | Type-safe financial or PII handler tied to a regulatory clause |
| `@AIStrictExceptions` + `@AIAudit` | Precise error handling AND audit every change for swallowed exceptions |
| `@AIStrictExceptions` + `@AIObservability` | Error handler whose log statements feed dashboards — must not be silenced |
| `@AIInternationalized` + `@AIContext` | i18n enforcement with guidance on which bundle/framework to use |
| `@AIStrictClasspath` + `@AIPerformance` | Compile-time-only deps AND strict complexity budget |
| `@AIParallelTests` + `@AITestDriven` | Tests must be parallel-safe AND include coverage for every change |
| `@AIIdempotent` + `@AIDraft` | **Warning**: contradictory — idempotent declares a stable contract; draft marks it as unfinished |
| `@AIIdempotent` + `@AIContext` | Idempotent operation with guidance on which deduplication approach to use |
| `@AIFeatureFlag` + `@AILocked` | **Warning**: contradictory — locked freezes code; feature flag implies conditional execution |
| `@AIFeatureFlag` + `@AIContext` | Flag-gated code with guidance on how to manage the flag lifecycle |
| `@AISecure` + `@AIIgnore` | **Warning**: contradictory — `@AIIgnore` hides the element; `@AISecure` requires AI visibility for security review |
| `@AISecure` + `@AIAudit` | Security-critical code that must also be audited on every change |
| `@AISecure` + `@AIPrivacy` | Security-critical PII handler — must not be weakened AND values must never leak |
| `@AISecure` + `@AICore` | Core security logic — treat all changes with extreme caution AND flag for security review |
| `@AISandboxOnly` + `@AIDomainModel` | **Warning**: contradictory — sandbox mocks should not be subjected to framework-free domain model constraints |
| `@AISunset` + `@AIDraft` | **Warning**: contradictory — sunset elements must not be actively drafted or expanded |
| `@AISecureLogging` + `@AIIgnore` | **Warning**: redundant — `@AIIgnore` already completely excludes this element |
| `@AIMemoryBudget` + `@AIPerformance` | Enforce zero-allocation along with O(1) latency constraints on hot-path logic |
| `@AIPure` + `@AIMemoryBudget` | Enforce deterministic pure functions that have a zero allocation footprint |
| `@AIExplain` + `@AICore` | Core sensitive logic requiring high-fidelity Sequence/Class diagrams for any modification |
