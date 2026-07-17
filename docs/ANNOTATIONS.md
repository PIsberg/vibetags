# Annotation Reference

Full list of the 39 `@AI*` annotations, their semantics, and the compile-time validation warnings the processor emits; the Javadoc on each `@interface` in `vibetags-annotations/` is the source of truth — this is a convenience index.

### Annotations (all `RetentionPolicy.SOURCE`)

| Annotation | Targets | Key Attributes |
|---|---|---|
| `@AILocked` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIContext` | TYPE, METHOD | `focus: String`, `avoids: String` |
| `@AIDraft` | TYPE, METHOD | `instructions: String` |
| `@AIAudit` | TYPE, METHOD | `checkFor: String[]` |
| `@AIIgnore` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIPrivacy` | TYPE, METHOD, FIELD | `reason: String` |
| `@AICore` | TYPE, METHOD, FIELD | `sensitivity: String`, `note: String` |
| `@AIPerformance` | TYPE, METHOD | `constraint: String` |
| `@AIContract` | TYPE, METHOD | `reason: String` |
| `@AITestDriven` | TYPE, METHOD | `testLocation: String`, `coverageGoal: int`, `framework: Framework[]`, `mockPolicy: String` |
| `@AIThreadSafe` | TYPE, METHOD | `strategy: Strategy`, `note: String` |
| `@AIImmutable` | TYPE | `note: String` |
| `@AIDeprecated` | TYPE, METHOD, FIELD | `replacedBy: String`, `migrationGuide: String`, `deadline: String` |
| `@AIObservability` | TYPE, METHOD | `metrics: String[]`, `traces: String[]`, `logs: String[]`, `note: String` |
| `@AIRegulation` | TYPE, METHOD, FIELD | `standard: String`, `clause: String`, `description: String` |
| `@AIArchitecture` | TYPE | `belongsTo: String`, `cannotReference: String[]` |
| `@AILegacyBridge` | TYPE, METHOD | `reason: String` |
| `@AIStrictClasspath` | TYPE, METHOD | `reason: String` |
| `@AIInternationalized` | TYPE, METHOD | `reason: String` |
| `@AIPublicAPI` | TYPE, METHOD | `reason: String` |
| `@AISchemaSafe` | TYPE, FIELD | `reason: String` |
| `@AIStrictExceptions` | TYPE, METHOD | `reason: String` |
| `@AIStrictTypes` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIParallelTests` | TYPE, METHOD | `reason: String` |
| `@AIIdempotent` | TYPE, METHOD | `reason: String` |
| `@AIFeatureFlag` | TYPE, METHOD, FIELD | `flag: String`, `defaultValue: boolean` |
| `@AISecure` | TYPE, METHOD | `aspect: String` |
| `@AICallersOnly` | TYPE, METHOD | `value: String[]` |
| `@AISandboxOnly` | TYPE, METHOD | `reason: String` |
| `@AIMemoryBudget` | TYPE, METHOD | `value: AllocationPolicy` |
| `@AIPure` | METHOD | `reason: String` |
| `@AIDomainModel` | TYPE | `allow: String[]` |
| `@AIExtensible` | TYPE | `value: Strategy` |
| `@AIInputSanitized` | PARAMETER, FIELD | `value: SanitizerType[]` |
| `@AISecureLogging` | FIELD, PARAMETER | `value: MaskingPolicy` |
| `@AIExplain` | TYPE, METHOD | `value: ComplexityLevel` |
| `@AIPrototype` | TYPE | `reason: String` |
| `@AISunset` | TYPE, METHOD, FIELD | `jira: String`, `replacement: Class<?>` |
| `@AITemporary` | TYPE, METHOD | `expiresOn: String`, `reason: String` |

**Annotation semantics:**

- `@AILocked` — code is visible but must not be modified by AI
- `@AIIgnore` — code is excluded from AI context entirely (treat as non-existent); unlike `@AILocked`, the AI should not even be aware of it
- `@AIPrivacy` — element handles PII; AI must never include its runtime values in logs, test fixtures, mock data, or API suggestions (GDPR/HIPAA/PCI-DSS use cases)
- `@AICore` — marks well-tested, sensitive core logic (e.g., months to stabilize); AI is instructed to treat changes with extreme care
- `@AIPerformance` — enforces strict time/space complexity on hot-path code; AI must not introduce O(n²) or worse solutions
- `@AIContract` — freezes the public signature (method name, parameter types, parameter order, return type, checked exceptions); AI may change internal logic but must not alter the visible API surface
- `@AITestDriven` — every change must include a matching test update; declares preferred framework, coverage goal, and mock policy
- `@AIThreadSafe` — declares an explicit thread-safety strategy (`SYNCHRONIZED`, `LOCK_FREE`, `IMMUTABLE`, `THREAD_LOCAL`, `OTHER`); AI must preserve the synchronization invariant
- `@AIImmutable` — declares the type immutable; the processor warns if any non-static field is non-final
- `@AIDeprecated` — actively routes AI toward replacing callers; richer than Java's `@Deprecated` (replacement target, migration guide, removal deadline)
- `@AIObservability` — names the metrics, trace spans, and log statements downstream dashboards depend on; AI must not silently remove or rename them
- `@AIRegulation` — ties code to a specific regulatory clause (GDPR, PCI-DSS, HIPAA, SOX, …); AI must document compliance impact and never weaken the requirement
- `@AIArchitecture` — declares `belongsTo` layer and forbidden `cannotReference` layers; AI must not introduce cross-layer imports
- `@AILegacyBridge` — marks compatibility shims for upstream quirks/bugs; AI must not modernize the structure, only internal logic may change
- `@AIStrictClasspath` — prohibits dynamic class loading, custom `ClassLoader`s, and runtime reflection; all deps must resolve at compile time
- `@AIInternationalized` — all user-visible text must come from i18n bundles; AI must never hardcode user-facing strings
- `@AIPublicAPI` — all changes must be additive and backward-compatible; renaming or changing serialization is forbidden
- `@AISchemaSafe` — maps to persistent storage; destructive schema changes require explicit backward-compatible migrations
- `@AIStrictExceptions` — prohibits catching/throwing `Exception`/`Throwable`; requires specific types with descriptive messages
- `@AIStrictTypes` — prohibits loose types (`Object`, raw collections, `double` for money); requires well-typed domain models
- `@AIParallelTests` — generated/modified tests must be parallel-safe: no shared mutable state, fixed ports, or execution-order dependencies
- `@AIIdempotent` — marks operations that must remain idempotent; AI must never introduce side effects that cause repeated invocations to produce different results
- `@AIFeatureFlag` — marks code gated behind a feature flag; AI must preserve the flag check and never assume it is always active
- `@AISecure` — marks security-critical code; AI must never weaken security properties and must flag every change for security review
- `@AICallersOnly` — only the listed callers may invoke the element; AI must not introduce calls from outside the allowed boundary
- `@AISandboxOnly` — sandbox/test-harness code; production code must never import or reference it
- `@AIMemoryBudget` — strict heap-allocation budget (`ZERO_ALLOCATION`, `NO_AUTOBOXING`, …); AI must optimize allocations and never add per-call garbage
- `@AIPure` — must remain a pure function: no side effects, no state mutation, deterministic for the same inputs
- `@AIDomainModel` — framework-free domain entity; AI must not import Spring, JPA/Hibernate, Jackson, or other framework packages (exceptions via `allow`)
- `@AIExtensible` — extend via the declared polymorphic pattern (`STRATEGY_PATTERN`, `VISITOR_PATTERN`, …); AI must not append branch conditionals
- `@AIInputSanitized` — parameter/field must pass approved sanitizers (`SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`, …) before reaching queries or renderers
- `@AISecureLogging` — sensitive value; AI must enforce the masking policy (`OMIT`, `HASH`, `MASK_CREDIT_CARD`, …) in any logging it writes
- `@AIExplain` — changes require a step-by-step proof of correctness in the PR/walkthrough, scaled to the declared complexity level
- `@AIPrototype` — experimental stub: QA/test constraints are relaxed, but production classes must never import it
- `@AISunset` — strict deprecation tied to a JIRA ticket; introducing *new* references is forbidden, callers route to `replacement`
- `@AITemporary` — hotfix/stub with an expiration date (`YYYY-MM-DD`); must be removed before expiry, warned at compile time once exceeded

**Compile-time validation warnings:**

- `@AIDraft` + `@AILocked` on the same element — contradictory (locked but needs drafting)
- `@AIAudit` with empty `checkFor[]` — no-op; nothing to audit
- `@AIPrivacy` + `@AIIgnore` on the same element — redundant; ignore already excludes
- `@AIContract` + `@AIDraft` on the same element — contradictory (signature frozen but needs drafting)
- `@AIContract` + `@AILocked` on the same element — overlapping intent (`@AILocked` already prohibits all changes; `@AIContract` is redundant)
- `@AITestDriven` + `@AIIgnore` / `@AILocked` — contradictory (cannot enforce tests on excluded or locked code)
- `@AITestDriven` with `coverageGoal` outside `[0, 100]` — invalid value
- `@AIImmutable` on a type with a non-final, non-static instance field — violates the immutability declaration
- `@AIDeprecated` + `@AILocked` on the same element — contradictory (locked preserves; deprecated routes callers away)
- `@AIThreadSafe(IMMUTABLE)` + `@AIImmutable` — redundant; `@AIImmutable` already implies thread-safety
- `@AIObservability` with no metrics, traces, or logs — no-op; nothing to preserve
- `@AIRegulation` with a blank `standard` — required attribute missing
- `@AIIdempotent` + `@AIDraft` on the same element — contradictory (idempotent declares a stable contract; draft marks it as unfinished)
- `@AIFeatureFlag` + `@AILocked` on the same element — contradictory (locked freezes code; feature flag implies conditional execution)
- `@AIFeatureFlag` with blank `flag` — no-op; the flag key is unspecified
- `@AISecure` with blank `aspect` — advisory; consider specifying the security concern (e.g. `"authentication"`, `"encryption"`)
- `@AISecure` + `@AIIgnore` on the same element — contradictory; `@AIIgnore` hides the element but `@AISecure` requires AI visibility for security review
- `@AISunset` + `@AIDraft` on the same element — contradictory (sunset elements must not be actively drafted or expanded)
- `@AISunset` with a blank `jira` — required attribute missing
- `@AITemporary` with a blank or unparseable `expiresOn` date — invalid value
- `@AITemporary` whose `expiresOn` date has passed — expired workaround still in the codebase
- `@AIIgnore` present but no `.cursorignore` / `.claudeignore` / `.copilotignore` / `.qwenignore` / `.aiexclude` exists — orphaned ignore annotation
- `@AILocked` present but no `.aiexclude` — Gemini/Codex lock not active
