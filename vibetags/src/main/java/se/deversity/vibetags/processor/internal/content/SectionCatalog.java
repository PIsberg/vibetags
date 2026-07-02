package se.deversity.vibetags.processor.internal.content;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source-of-truth for the section header + description text that platform renderers
 * prepend to each annotation bucket (e.g. "MANDATORY SECURITY AUDITS", "PII / PRIVACY
 * GUARDRAILS"). {@link se.deversity.vibetags.processor.internal.content.platforms.CursorRenderer
 * CursorRenderer}'s wording is the canonical default; platforms whose copy differs today
 * register an override here instead of re-typing the string in their own section list. A
 * platform/key pair registered in {@link #HEADERLESS} renders with no heading at all — its
 * elements are still formatted, just folded into the previous section — matching Windsurf and
 * Zed, which omit a heading for a handful of buckets.
 *
 * <p>This catalog intentionally does not normalize or unify wording across platforms: every
 * override below reproduces, verbatim, text that already existed in a renderer prior to this
 * class's extraction, so that generated output stays byte-identical.
 */
public final class SectionCatalog {

    /** One entry per annotation bucket that carries a section header in at least one renderer. */
    public enum Key {
        AUDIT, IGNORE, DRAFT, PRIVACY, CORE, PERFORMANCE, CONTRACT, TEST_DRIVEN, THREAD_SAFE, IMMUTABLE, DEPRECATED, OBSERVABILITY, REGULATION, PARALLEL_TESTS, LEGACY_BRIDGE, ARCHITECTURE, PUBLIC_API, STRICT_EXCEPTIONS, STRICT_TYPES, INTERNATIONALIZED, STRICT_CLASSPATH, SCHEMA_SAFE, IDEMPOTENT, FEATURE_FLAG, SECURE, CALLERS_ONLY, SANDBOX_ONLY, MEMORY_BUDGET, PURE, DOMAIN_MODEL, EXTENSIBLE, INPUT_SANITIZED, SECURE_LOGGING, EXPLAIN, PROTOTYPE, SUNSET, TEMPORARY
    }

    private SectionCatalog() {}

    private static final Map<Key, String> DEFAULT = new EnumMap<>(Key.class);
    private static final Map<Platform, Map<Key, String>> OVERRIDES = new EnumMap<>(Platform.class);
    private static final Map<Platform, Set<Key>> HEADERLESS = new EnumMap<>(Platform.class);

    static {
        DEFAULT.put(Key.AUDIT, "\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n");
        DEFAULT.put(Key.IGNORE, "\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n");
        DEFAULT.put(Key.DRAFT, "\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n");
        DEFAULT.put(Key.PRIVACY, "\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNEVER include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
        DEFAULT.put(Key.CORE, "\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        DEFAULT.put(Key.PERFORMANCE, "\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nThe following elements are on a hot path. Never introduce O(n²) complexity. Always reason about time/space before proposing changes.\n\n");
        DEFAULT.put(Key.CONTRACT, "\n## 🔐 CONTRACT-FROZEN SIGNATURES\nThe following elements have contract-frozen public signatures. You MAY change internal implementation logic, but MUST NOT modify method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
        DEFAULT.put(Key.TEST_DRIVEN, "\n## 🧪 TEST-DRIVEN REQUIREMENTS\nThe following elements require a corresponding test update whenever their logic is modified.\nAI MUST NOT propose changes to these elements without also providing the matching test code.\n\n");
        DEFAULT.put(Key.THREAD_SAFE, "\n## 🧵 THREAD-SAFE BY DESIGN\nThe following elements are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning.\n\n");
        DEFAULT.put(Key.IMMUTABLE, "\n## ❄️ IMMUTABLE TYPES\nThe following types are declared immutable. NEVER introduce non-final fields, setters, or mutating methods.\n\n");
        DEFAULT.put(Key.DEPRECATED, "\n## ⚠️ DEPRECATED — ROUTE CALLERS AWAY\nThe following elements are deprecated. Do not extend them. Suggest migrating any caller to the named replacement.\n\n");
        DEFAULT.put(Key.OBSERVABILITY, "\n## 📡 OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the affected dashboard.\n\n");
        DEFAULT.put(Key.REGULATION, "\n## 📜 REGULATORY COMPLIANCE\nThe following elements implement specific compliance clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.\n\n");
        DEFAULT.put(Key.PARALLEL_TESTS, "\n## 🧪 STRICT TEST ISOLATION\nThe following elements must be strictly isolated when generating or modifying tests. No shared mutable state or resource conflicts are permitted.\n\n");
        DEFAULT.put(Key.LEGACY_BRIDGE, "\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nThe following elements are legacy compatibility bridges. Do not attempt to modernize or refactor their structural patterns; only modify internal business logic as explicitly requested.\n\n");
        DEFAULT.put(Key.ARCHITECTURE, "\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nThe following elements have strict layering constraints. Prohibit imports or references that cross boundaries.\n\n");
        DEFAULT.put(Key.PUBLIC_API, "\n## 🔌 PUBLIC API SURFACE PROTECTION\nThe following elements are public-facing API surfaces. Always preserve public signatures, Javadoc, and backwards compatibility.\n\n");
        DEFAULT.put(Key.STRICT_EXCEPTIONS, "\n## 🚨 STRICT EXCEPTION HANDLING\nThe following elements have strict exception constraints. Prohibit catching or throwing generic Exception/Throwable.\n\n");
        DEFAULT.put(Key.STRICT_TYPES, "\n## 🏷️ STRICT TYPE SAFETY\nThe following elements prohibit loose typing such as Object or Map<String, Object>. Strong type safety is required.\n\n");
        DEFAULT.put(Key.INTERNATIONALIZED, "\n## 🌐 INTERNATIONALIZATION MANDATE\nThe following elements implement i18n requirements. Prohibit hardcoded user-facing strings.\n\n");
        DEFAULT.put(Key.STRICT_CLASSPATH, "\n## 🛡️ STRICT CLASSPATH INTEGRITY\nThe following elements prohibit dynamic runtime class loading, reflections, or loading of unverified dynamic code.\n\n");
        DEFAULT.put(Key.SCHEMA_SAFE, "\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nThe following elements have schema safety constraints. Restrict changing formats/fields without a backward-compatible migration plan.\n\n");
        DEFAULT.put(Key.IDEMPOTENT, "\n## ♻️ IDEMPOTENCY GUARANTEES\nThe following operations are idempotent. Multiple invocations MUST produce the same result as a single invocation. Never introduce side effects that break this guarantee.\n\n");
        DEFAULT.put(Key.FEATURE_FLAG, "\n## 🚩 FEATURE FLAG GATED CODE\nThe following elements are gated behind a feature flag. Do not assume the flag is always active. Preserve the flag check.\n\n");
        DEFAULT.put(Key.SECURE, "\n## 🔐 SECURITY-CRITICAL CODE\nThe following elements are security-critical. AI must not weaken security properties. Any change must be reviewed for security impact.\n\n");
        DEFAULT.put(Key.CALLERS_ONLY, "\n## 🚫 ACCESS & CALLS LIMITATIONS\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n");
        DEFAULT.put(Key.SANDBOX_ONLY, "\n## 🛡️ SANDBOX & TEST HARNESS EXCLUSION\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n");
        DEFAULT.put(Key.MEMORY_BUDGET, "\n## ⚡ MEMORY ALLOCATION BUDGETS\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n");
        DEFAULT.put(Key.PURE, "\n## 🧠 DETERMINISTIC PURE FUNCTIONS\nThe following elements must remain pure functions without side effects or mutations.\n\n");
        DEFAULT.put(Key.DOMAIN_MODEL, "\n## 🧱 FRAMEWORK-FREE DOMAIN ENTITIES\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n");
        DEFAULT.put(Key.EXTENSIBLE, "\n## ❄️ open-closed EXTENSION PATTERNS\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n");
        DEFAULT.put(Key.INPUT_SANITIZED, "\n## 🚨 MANDATORY INPUT SANITIZATION\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n");
        DEFAULT.put(Key.SECURE_LOGGING, "\n## 🔒 SECURE LOGGING MASKING\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n");
        DEFAULT.put(Key.EXPLAIN, "\n## 📋 REQUIRED CHAIN-OF-THOUGHT EXPLANATIONS\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n");
        DEFAULT.put(Key.PROTOTYPE, "\n## 🛠️ EXPERIMENTAL PROTOTYPE STUBS\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n");
        DEFAULT.put(Key.SUNSET, "\n## ⚠️ SUNSET DEPRACTED APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n");
        DEFAULT.put(Key.TEMPORARY, "\n## 🚧 TEMPORARY CODE WORKAROUNDS\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n");

        Map<Key, String> windsurfOverrides = new EnumMap<>(Key.class);
        windsurfOverrides.put(Key.AUDIT, "\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code.\n\n");
        windsurfOverrides.put(Key.PRIVACY, "\n## 🔒 PII / PRIVACY GUARDRAILS\nNever include runtime values of the following in logs, console output, external API calls, test fixtures, or mock data.\n\n");
        windsurfOverrides.put(Key.PERFORMANCE, "\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) or worse complexity. Always reason about time/space before proposing changes.\n\n");
        windsurfOverrides.put(Key.CONTRACT, "\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
        windsurfOverrides.put(Key.TEST_DRIVEN, "\n## 🧪 TEST-DRIVEN REQUIREMENTS\nAI MUST NOT propose changes to the following elements without also providing the matching test code.\n\n");
        windsurfOverrides.put(Key.PARALLEL_TESTS, "\n## 🧪 STRICT TEST ISOLATION\nEnforce strict isolation for tests generated or modified for these elements:\n\n");
        windsurfOverrides.put(Key.LEGACY_BRIDGE, "\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
        windsurfOverrides.put(Key.ARCHITECTURE, "\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n");
        windsurfOverrides.put(Key.PUBLIC_API, "\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
        windsurfOverrides.put(Key.STRICT_EXCEPTIONS, "\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
        windsurfOverrides.put(Key.STRICT_TYPES, "\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n");
        windsurfOverrides.put(Key.INTERNATIONALIZED, "\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
        windsurfOverrides.put(Key.STRICT_CLASSPATH, "\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
        windsurfOverrides.put(Key.SCHEMA_SAFE, "\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        windsurfOverrides.put(Key.IDEMPOTENT, "\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — multiple calls = same as one call:\n\n");
        windsurfOverrides.put(Key.FEATURE_FLAG, "\n## 🚩 FEATURE FLAG GATED CODE\nNever assume these feature flags are always active — preserve all flag checks:\n\n");
        windsurfOverrides.put(Key.SECURE, "\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Every change requires security review:\n\n");
        OVERRIDES.put(Platform.WINDSURF, windsurfOverrides);
        HEADERLESS.put(Platform.WINDSURF, EnumSet.of(Key.THREAD_SAFE, Key.IMMUTABLE, Key.DEPRECATED, Key.OBSERVABILITY, Key.REGULATION));

        Map<Key, String> zedOverrides = new EnumMap<>(Key.class);
        zedOverrides.put(Key.AUDIT, "\n## Security Audits\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n");
        zedOverrides.put(Key.IGNORE, "\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n");
        zedOverrides.put(Key.DRAFT, "\n## Implementation Tasks\nThe following elements are drafts that need implementation:\n\n");
        zedOverrides.put(Key.PRIVACY, "\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs runtime values of these elements:\n\n");
        zedOverrides.put(Key.CORE, "\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n");
        zedOverrides.put(Key.PERFORMANCE, "\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n");
        zedOverrides.put(Key.CONTRACT, "\n## Contract-Frozen Signatures\nInternal logic may be changed; never alter method names, parameter types, order, return types, or checked exceptions:\n\n");
        zedOverrides.put(Key.TEST_DRIVEN, "\n## Test-Driven Requirements\nChanges to the following elements must be accompanied by matching test code:\n\n");
        zedOverrides.put(Key.PARALLEL_TESTS, "\n## Strict Test Isolation\nEnforce strict isolation in tests for these elements:\n\n");
        zedOverrides.put(Key.LEGACY_BRIDGE, "\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
        zedOverrides.put(Key.ARCHITECTURE, "\n## Architectural Constraints\nStrict layered architecture constraints apply to these elements:\n\n");
        zedOverrides.put(Key.PUBLIC_API, "\n## Public API Protection\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
        zedOverrides.put(Key.STRICT_EXCEPTIONS, "\n## Strict Exceptions\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
        zedOverrides.put(Key.STRICT_TYPES, "\n## Strict Types\nLoose typing is strictly prohibited for these elements:\n\n");
        zedOverrides.put(Key.INTERNATIONALIZED, "\n## Internationalization\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
        zedOverrides.put(Key.STRICT_CLASSPATH, "\n## Strict Classpath\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
        zedOverrides.put(Key.SCHEMA_SAFE, "\n## Schema Safety\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        zedOverrides.put(Key.IDEMPOTENT, "\n## Idempotency\nThese operations must remain idempotent:\n\n");
        zedOverrides.put(Key.FEATURE_FLAG, "\n## Feature Flags\nThese elements are behind feature flags — never assume always active:\n\n");
        zedOverrides.put(Key.SECURE, "\n## Security-Critical Code\nNever weaken security properties of these elements:\n\n");
        zedOverrides.put(Key.CALLERS_ONLY, "\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n");
        zedOverrides.put(Key.SANDBOX_ONLY, "\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n");
        zedOverrides.put(Key.MEMORY_BUDGET, "\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n");
        zedOverrides.put(Key.PURE, "\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations.\n\n");
        zedOverrides.put(Key.DOMAIN_MODEL, "\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n");
        zedOverrides.put(Key.EXTENSIBLE, "\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n");
        zedOverrides.put(Key.INPUT_SANITIZED, "\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n");
        zedOverrides.put(Key.SECURE_LOGGING, "\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n");
        zedOverrides.put(Key.EXPLAIN, "\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n");
        zedOverrides.put(Key.PROTOTYPE, "\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n");
        zedOverrides.put(Key.SUNSET, "\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n");
        zedOverrides.put(Key.TEMPORARY, "\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n");
        OVERRIDES.put(Platform.ZED, zedOverrides);
        HEADERLESS.put(Platform.ZED, EnumSet.of(Key.THREAD_SAFE, Key.IMMUTABLE, Key.DEPRECATED, Key.OBSERVABILITY, Key.REGULATION));

        Map<Key, String> copilotOverrides = new EnumMap<>(Key.class);
        copilotOverrides.put(Key.AUDIT, "\n## Security Audit Requirements\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n");
        copilotOverrides.put(Key.IGNORE, "\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n");
        copilotOverrides.put(Key.DRAFT, "\n## Implementation Tasks\nFollow these instructions to implement the drafts:\n\n");
        copilotOverrides.put(Key.PRIVACY, "\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs the runtime values of these elements:\n\n");
        copilotOverrides.put(Key.CORE, "\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n");
        copilotOverrides.put(Key.PERFORMANCE, "\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n");
        copilotOverrides.put(Key.CONTRACT, "\n## Contract-Frozen Signatures\nDo not modify the public signatures of the following elements. Internal implementation changes are permitted:\n\n");
        copilotOverrides.put(Key.TEST_DRIVEN, "\n## Test-Driven Requirements\nDo not suggest changes to the following elements without also providing the corresponding test update:\n\n");
        copilotOverrides.put(Key.THREAD_SAFE, "\n## Thread-Safe by Design\nDo not modify these elements without preserving their thread-safety strategy:\n\n");
        copilotOverrides.put(Key.IMMUTABLE, "\n## Immutable Types\nThe following types are immutable. Do not add mutating methods or non-final fields:\n\n");
        copilotOverrides.put(Key.DEPRECATED, "\n## Deprecated Elements\nDo not extend these elements. Migrate callers to the listed replacement:\n\n");
        copilotOverrides.put(Key.OBSERVABILITY, "\n## Observability Instrumentation\nThe following elements have metrics/traces/logs watched by dashboards. Do not delete or rename them silently:\n\n");
        copilotOverrides.put(Key.REGULATION, "\n## Regulatory Compliance\nThese elements implement compliance clauses. Document the compliance impact of every change:\n\n");
        copilotOverrides.put(Key.PARALLEL_TESTS, "\n## Strict Test Isolation\nDo not share mutable state or external resources in tests for these elements:\n\n");
        copilotOverrides.put(Key.LEGACY_BRIDGE, "\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n");
        copilotOverrides.put(Key.ARCHITECTURE, "\n## Architectural Boundary Constraints\nStrict layering must be respected. Boundary crossing references are prohibited:\n\n");
        copilotOverrides.put(Key.PUBLIC_API, "\n## Public API Surface Protection\nDo not modify public signatures or break compatibility for these elements:\n\n");
        copilotOverrides.put(Key.STRICT_EXCEPTIONS, "\n## Strict Exception Handling\nPrecise exception handling required. Do not catch or throw generic Exception/Throwable:\n\n");
        copilotOverrides.put(Key.STRICT_TYPES, "\n## Strict Type Safety\nLoose typing is prohibited. Strongly-typed objects must be used:\n\n");
        copilotOverrides.put(Key.INTERNATIONALIZED, "\n## Internationalization Mandate\nAll user-visible text must be localized. Do not hardcode strings:\n\n");
        copilotOverrides.put(Key.STRICT_CLASSPATH, "\n## Strict Classpath Integrity\nDynamic class loading and reflection hacks are strictly prohibited:\n\n");
        copilotOverrides.put(Key.SCHEMA_SAFE, "\n## Schema & Serialization Safety\nDo not change serialization formats or schemas without a backward-compatible migration plan:\n\n");
        copilotOverrides.put(Key.IDEMPOTENT, "\n## Idempotency Guarantees\nThe following operations are idempotent — calling them multiple times is safe:\n\n");
        copilotOverrides.put(Key.FEATURE_FLAG, "\n## Feature Flag Gated Code\nThe following elements are gated behind a feature flag — always preserve the flag check:\n\n");
        copilotOverrides.put(Key.SECURE, "\n## Security-Critical Code\nThe following elements are security-critical — do not weaken their security properties:\n\n");
        copilotOverrides.put(Key.CALLERS_ONLY, "\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries:\n\n");
        copilotOverrides.put(Key.SANDBOX_ONLY, "\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them:\n\n");
        copilotOverrides.put(Key.MEMORY_BUDGET, "\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully:\n\n");
        copilotOverrides.put(Key.PURE, "\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations:\n\n");
        copilotOverrides.put(Key.DOMAIN_MODEL, "\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages:\n\n");
        copilotOverrides.put(Key.EXTENSIBLE, "\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals:\n\n");
        copilotOverrides.put(Key.INPUT_SANITIZED, "\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers:\n\n");
        copilotOverrides.put(Key.SECURE_LOGGING, "\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams:\n\n");
        copilotOverrides.put(Key.EXPLAIN, "\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough:\n\n");
        copilotOverrides.put(Key.PROTOTYPE, "\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them:\n\n");
        copilotOverrides.put(Key.SUNSET, "\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden:\n\n");
        copilotOverrides.put(Key.TEMPORARY, "\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit:\n\n");
        OVERRIDES.put(Platform.COPILOT, copilotOverrides);

        Map<Key, String> qwenOverrides = new EnumMap<>(Key.class);
        qwenOverrides.put(Key.AUDIT, "\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review. Explicitly state that you have audited the changes for the listed vulnerabilities.\n\n");
        qwenOverrides.put(Key.IGNORE, "\n## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI's memory and context:\n\n");
        qwenOverrides.put(Key.DRAFT, "\n## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        qwenOverrides.put(Key.PRIVACY, "\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle PII. Never include their runtime values in logs,\nconsole output, external API calls, test fixtures, or mock data.\n\n");
        qwenOverrides.put(Key.CORE, "\n## 🧠 CORE FUNCTIONALITY\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        qwenOverrides.put(Key.PERFORMANCE, "\n## ⚡ PERFORMANCE CONSTRAINTS\nHot-path elements — O(n²) complexity is forbidden. Reason about complexity before proposing changes.\n\n");
        qwenOverrides.put(Key.CONTRACT, "\n## 🔐 CONTRACT-FROZEN SIGNATURES\nSignatures (method names, parameters, return types, checked exceptions) are immutable. Internal logic may be changed.\n\n");
        qwenOverrides.put(Key.TEST_DRIVEN, "\n## 🧪 TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by a matching test update in the same response.\n\n");
        qwenOverrides.put(Key.THREAD_SAFE, "\n## 🧵 THREAD-SAFE BY DESIGN\nThese elements are explicitly designed to be thread-safe — preserve the synchronization invariant.\n\n");
        qwenOverrides.put(Key.IMMUTABLE, "\n## ❄️ IMMUTABLE TYPES\nThe following types are immutable — never add setters, mutating methods, or non-final fields.\n\n");
        qwenOverrides.put(Key.DEPRECATED, "\n## ⚠️ DEPRECATED ELEMENTS\nDo not extend or build on these elements — migrate callers to the replacement.\n\n");
        qwenOverrides.put(Key.OBSERVABILITY, "\n## 📡 OBSERVABILITY INSTRUMENTATION\nThese elements carry instrumentation watched by dashboards. Preserve every metric, trace, and log statement.\n\n");
        qwenOverrides.put(Key.REGULATION, "\n## 📜 REGULATORY COMPLIANCE\nThese elements implement compliance clauses — document compliance impact and never weaken the requirement.\n\n");
        qwenOverrides.put(Key.PARALLEL_TESTS, "\n## 🧪 STRICT TEST ISOLATION\nTests must run in complete thread isolation without shared mutable state:\n\n");
        qwenOverrides.put(Key.LEGACY_BRIDGE, "\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nModernization/structural refactoring is prohibited for these elements:\n\n");
        qwenOverrides.put(Key.ARCHITECTURE, "\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n");
        qwenOverrides.put(Key.PUBLIC_API, "\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n");
        qwenOverrides.put(Key.STRICT_EXCEPTIONS, "\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n");
        qwenOverrides.put(Key.STRICT_TYPES, "\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n");
        qwenOverrides.put(Key.INTERNATIONALIZED, "\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n");
        qwenOverrides.put(Key.STRICT_CLASSPATH, "\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n");
        qwenOverrides.put(Key.SCHEMA_SAFE, "\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        qwenOverrides.put(Key.IDEMPOTENT, "\n## ♻️ IDEMPOTENCY GUARANTEES\nMultiple invocations of these operations must produce the same result:\n\n");
        qwenOverrides.put(Key.FEATURE_FLAG, "\n## 🚩 FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume it is always active:\n\n");
        qwenOverrides.put(Key.SECURE, "\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Flag any change for security review:\n\n");
        OVERRIDES.put(Platform.QWEN, qwenOverrides);

        Map<Key, String> codexOverrides = new EnumMap<>(Key.class);
        codexOverrides.put(Key.IGNORE, "\n## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
        codexOverrides.put(Key.DRAFT, "\n## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        codexOverrides.put(Key.PRIVACY, "\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle PII. Never include their runtime values in logs,\nconsole output, external API calls, test fixtures, or mock data.\n\n");
        codexOverrides.put(Key.CORE, "\n## 🧠 CORE FUNCTIONALITY\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n");
        codexOverrides.put(Key.PERFORMANCE, "\n## ⚡ PERFORMANCE CONSTRAINTS\nHot-path elements — never introduce O(n²) or worse. Always reason about complexity before proposing changes.\n\n");
        codexOverrides.put(Key.CONTRACT, "\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal logic may be modified, but never change method names, parameter types, parameter order, return types, or checked exceptions.\n\n");
        codexOverrides.put(Key.TEST_DRIVEN, "\n## 🧪 TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by a matching test update in the same response.\n\n");
        codexOverrides.put(Key.THREAD_SAFE, "\n## 🧵 THREAD-SAFE BY DESIGN\nThese elements are explicitly designed to be thread-safe. Preserve the synchronization invariant on every change.\n\n");
        codexOverrides.put(Key.IMMUTABLE, "\n## ❄️ IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods.\n\n");
        codexOverrides.put(Key.DEPRECATED, "\n## ⚠️ DEPRECATED ELEMENTS\nDo not extend these elements. Suggest migrating callers to the listed replacement.\n\n");
        codexOverrides.put(Key.OBSERVABILITY, "\n## 📡 OBSERVABILITY INSTRUMENTATION\nThese elements carry instrumentation watched by dashboards/alerts. Do not remove or rename without flagging.\n\n");
        codexOverrides.put(Key.REGULATION, "\n## 📜 REGULATORY COMPLIANCE\nThese elements implement specific compliance clauses. Document compliance impact for every change.\n\n");
        codexOverrides.put(Key.PARALLEL_TESTS, "\n## 🧪 STRICT TEST ISOLATION\nTests for the following elements must be strictly isolated (no shared mutable state/resources):\n\n");
        codexOverrides.put(Key.LEGACY_BRIDGE, "\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not restructure or refactor structural patterns of these compatibility bridges:\n\n");
        codexOverrides.put(Key.ARCHITECTURE, "\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layering must be respected. No illegal boundary crossing references:\n\n");
        codexOverrides.put(Key.PUBLIC_API, "\n## 🔌 PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and behavior without breaking backwards compatibility:\n\n");
        codexOverrides.put(Key.STRICT_EXCEPTIONS, "\n## 🚨 STRICT EXCEPTION HANDLING\nPrecise and robust exception handling required. Generic exception catching/throwing is prohibited:\n\n");
        codexOverrides.put(Key.STRICT_TYPES, "\n## 🏷️ STRICT TYPE SAFETY\nType safety must be strictly preserved. Loose or erased types are prohibited:\n\n");
        codexOverrides.put(Key.INTERNATIONALIZED, "\n## 🌐 INTERNATIONALIZATION MANDATE\nDo not hardcode user-facing strings. All user-visible text must be localized:\n\n");
        codexOverrides.put(Key.STRICT_CLASSPATH, "\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic class loading, custom classloaders, and reflection hacks are prohibited:\n\n");
        codexOverrides.put(Key.SCHEMA_SAFE, "\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nPreserve database/contract schema and serialization compatibility on every change:\n\n");
        codexOverrides.put(Key.IDEMPOTENT, "\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent (multiple invocations = same result as once):\n\n");
        codexOverrides.put(Key.FEATURE_FLAG, "\n## 🚩 FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n");
        codexOverrides.put(Key.SECURE, "\n## 🔐 SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Review every change for security impact:\n\n");
        OVERRIDES.put(Platform.CODEX, codexOverrides);

        Map<Key, String> geminiOverrides = new EnumMap<>(Key.class);
        geminiOverrides.put(Key.AUDIT, "\n## CONTINUOUS AUDIT REQUIREMENTS\nYou are acting as a Senior Staff Engineer. Whenever you write code for the files listed below, you must ensure your completions and chat responses strictly prevent the listed vulnerabilities:\n\n");
        geminiOverrides.put(Key.IGNORE, "## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
        geminiOverrides.put(Key.DRAFT, "## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
        geminiOverrides.put(Key.PRIVACY, "## PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNever include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
        geminiOverrides.put(Key.CORE, "\n## CORE FUNCTIONALITY (EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution:\n\n");
        geminiOverrides.put(Key.PERFORMANCE, "\n## PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) complexity into these elements. Always reason about complexity before proposing changes:\n\n");
        geminiOverrides.put(Key.CONTRACT, "\n## CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions:\n\n");
        geminiOverrides.put(Key.TEST_DRIVEN, "\n## TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by matching test code in the same response:\n\n");
        geminiOverrides.put(Key.THREAD_SAFE, "\n## THREAD-SAFE BY DESIGN\nThese elements are thread-safe by design — preserve the synchronization invariant on every change:\n\n");
        geminiOverrides.put(Key.IMMUTABLE, "\n## IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods:\n\n");
        geminiOverrides.put(Key.DEPRECATED, "\n## DEPRECATED ELEMENTS\nThe following elements are deprecated. Suggest migration to the named replacement for any caller:\n\n");
        geminiOverrides.put(Key.OBSERVABILITY, "\n## OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements watched by dashboards. Preserve every instrumentation point:\n\n");
        geminiOverrides.put(Key.REGULATION, "\n## REGULATORY COMPLIANCE\nThe following elements implement compliance clauses. Document compliance impact for every change:\n\n");
        geminiOverrides.put(Key.PARALLEL_TESTS, "\n## STRICT TEST ISOLATION\nTests for these elements must run in complete isolation without sharing mutable state:\n\n");
        geminiOverrides.put(Key.LEGACY_BRIDGE, "\n## LEGACY COMPATIBILITY BRIDGE\nDo not restructure compatibility bridges. Only modify business logic:\n\n");
        geminiOverrides.put(Key.ARCHITECTURE, "\n## ARCHITECTURAL BOUNDARY CONSTRAINTS\nRespect architectural layering. Boundary crossing references are prohibited:\n\n");
        geminiOverrides.put(Key.PUBLIC_API, "\n## PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and backwards compatibility:\n\n");
        geminiOverrides.put(Key.STRICT_EXCEPTIONS, "\n## STRICT EXCEPTION HANDLING\nCatching/throwing generic Exception is prohibited. Use precise exceptions:\n\n");
        geminiOverrides.put(Key.STRICT_TYPES, "\n## STRICT TYPE SAFETY\nLoose typing is prohibited. Strongly-typed models required:\n\n");
        geminiOverrides.put(Key.INTERNATIONALIZED, "\n## INTERNATIONALIZATION MANDATE\nUser-facing strings must not be hardcoded; retrieve from resources:\n\n");
        geminiOverrides.put(Key.STRICT_CLASSPATH, "\n## STRICT CLASSPATH INTEGRITY\nDynamic class loading and reflection hacks are strictly prohibited:\n\n");
        geminiOverrides.put(Key.SCHEMA_SAFE, "\n## SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n");
        geminiOverrides.put(Key.IDEMPOTENT, "\n## IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — calling them multiple times must produce the same result:\n\n");
        geminiOverrides.put(Key.FEATURE_FLAG, "\n## FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n");
        geminiOverrides.put(Key.SECURE, "\n## SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Flag any change for security review:\n\n");
        geminiOverrides.put(Key.CALLERS_ONLY, "\n## ACCESS & CALLS LIMITATIONS\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries:\n\n");
        geminiOverrides.put(Key.SANDBOX_ONLY, "\n## SANDBOX & TEST HARNESS EXCLUSION\nThe following elements are strictly sandbox/test code. Production code must never import or reference them:\n\n");
        geminiOverrides.put(Key.MEMORY_BUDGET, "\n## MEMORY ALLOCATION BUDGETS\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully:\n\n");
        geminiOverrides.put(Key.PURE, "\n## DETERMINISTIC PURE FUNCTIONS\nThe following elements must remain pure functions without side effects or mutations:\n\n");
        geminiOverrides.put(Key.DOMAIN_MODEL, "\n## FRAMEWORK-FREE DOMAIN ENTITIES\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages:\n\n");
        geminiOverrides.put(Key.EXTENSIBLE, "\n## OPEN-CLOSED EXTENSION PATTERNS\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals:\n\n");
        geminiOverrides.put(Key.INPUT_SANITIZED, "\n## MANDATORY INPUT SANITIZATION\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers:\n\n");
        geminiOverrides.put(Key.SECURE_LOGGING, "\n## SECURE LOGGING MASKING\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams:\n\n");
        geminiOverrides.put(Key.EXPLAIN, "\n## REQUIRED CHAIN-OF-THOUGHT EXPLANATIONS\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough:\n\n");
        geminiOverrides.put(Key.PROTOTYPE, "\n## EXPERIMENTAL PROTOTYPE STUBS\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them:\n\n");
        geminiOverrides.put(Key.SUNSET, "\n## SUNSET DEPRECATED APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden:\n\n");
        geminiOverrides.put(Key.TEMPORARY, "\n## TEMPORARY CODE WORKAROUNDS\nTemporary stubs or hacks that must be refactored or removed before their expiration limit:\n\n");
        OVERRIDES.put(Platform.GEMINI, geminiOverrides);
    }

    /**
     * Returns the exact header + description text a renderer should print before formatting
     * {@code key}'s elements for {@code platform}, or {@code null} if the section is headerless
     * on that platform (see {@link #isHeaderless(Platform, Key)}).
     */
    public static String header(Platform platform, Key key) {
        if (isHeaderless(platform, key)) {
            return null;
        }
        Map<Key, String> overrides = OVERRIDES.get(platform);
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
        }
        return DEFAULT.get(key);
    }

    /** True if {@code platform} renders {@code key}'s elements with no section heading. */
    public static boolean isHeaderless(Platform platform, Key key) {
        Set<Key> keys = HEADERLESS.get(platform);
        return keys != null && keys.contains(key);
    }
}
