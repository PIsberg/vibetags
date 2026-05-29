package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.Collection;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `llms.txt` and `llms-full.txt` discovery formats.
 */
public final class LlmsRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        boolean full = (platform == Platform.LLMS_FULL);
        StringBuilder sb = new StringBuilder(8192);

        // Header
        if (full) {
            sb.append("# ").append(context.getProjectName()).append(" — AI Guardrail Rules\n")
              .append("> Complete AI guardrail configuration generated from source annotations by VibeTags.\n\n")
              .append("This document contains the full set of AI guardrail rules for this project. ")
              .append("AI tools with large context windows (such as Windsurf Cascade, Claude 4.6, or Gemini 1.5 Pro) ")
              .append("may load this file directly instead of fetching individual documentation pages.\n\n");
        } else {
            sb.append("# ").append(context.getProjectName()).append("\n\n")
              .append("> AI guardrail rules generated from source annotations by VibeTags.\n\n")
              .append("AI tools reading this file should respect the guardrails defined below. ")
              .append("These rules were extracted from Java source annotations at compile time ")
              .append("and apply to all AI assistants including Windsurf Cascade, Cursor, Claude, ")
              .append("GitHub Copilot, and Gemini.\n\n");
        }

        // 1. Locked Files
        sb.append(full ? "## Locked Files (Do Not Edit)\nThe following files are locked. AI tools MUST NOT propose modifications to them.\n\n" : "## Locked Files\n");
        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, platform);
        }

        // 2. Contextual Rules
        sb.append(full ? "\n## Contextual Rules\nThese files have specific context and focus areas for AI assistance.\n\n" : "\n## Contextual Rules\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, platform);
        }

        // 3–27: remaining sections use lambdas (FormatterCaller is @FunctionalInterface).
        // Lambdas avoid the hidden outer-class reference that anonymous classes carry.
        appendSection(sb, collector.audit(), platform,
            full ? "\n## Mandatory Security Audit Requirements\nWhen writing or modifying the following files, perform a security audit for the listed vulnerabilities before displaying any code to the user.\n\n" : "\n## Security Audit Requirements\n",
            (e, buf) -> FormatterRegistry.audit().format(e, buf, platform));

        // 4. Ignored Elements
        appendSection(sb, collector.ignore(), platform,
            full ? "\n## Ignored Elements\nThe following elements must be completely excluded from AI context. Treat them as non-existent.\n\n" : "\n## Ignored Elements\n",
            (e, buf) -> FormatterRegistry.ignore().format(e, buf, platform));

        // 5. Draft/TODO
        appendSection(sb, collector.draft(), platform,
            full ? "\n## Implementation Tasks\nThe following elements are in draft mode and need implementation.\n\n" : "\n## Implementation Tasks\n",
            (e, buf) -> FormatterRegistry.draft().format(e, buf, platform));

        // 6. Privacy/PII
        appendSection(sb, collector.privacy(), platform,
            full ? "\n## PII / Privacy Guardrails\nNever include runtime values of the following elements in logs, console output, external API calls, test fixtures, or mock data.\n\n" : "\n## PII / Privacy Guardrails\n",
            (e, buf) -> FormatterRegistry.privacy().format(e, buf, platform));

        // 7. Core
        appendSection(sb, collector.core(), platform,
            full ? "\n## 🧠 Core Functionality\nThe following elements are well-tested core functionality. Make changes with extreme caution.\n\n" : "\n## 🧠 Core Functionality\n",
            (e, buf) -> FormatterRegistry.core().format(e, buf, platform));

        // 8. Performance
        appendSection(sb, collector.performance(), platform,
            full ? "\n## ⚡ Performance Constraints\nThe following elements are on a hot-path and have strict time/space complexity constraints.\n\n" : "\n## ⚡ Performance Constraints\n",
            (e, buf) -> FormatterRegistry.performance().format(e, buf, platform));

        // 9. Contract
        appendSection(sb, collector.contract(), platform,
            full ? "\n## 🔐 Contract-Frozen Signatures\nThe following elements have frozen public API signatures. Internal implementation may be changed, but you MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n" : "\n## 🔐 Contract-Frozen Signatures\n",
            (e, buf) -> FormatterRegistry.contract().format(e, buf, platform));

        // 10. Test-Driven
        appendSection(sb, collector.testDriven(), platform,
            full ? "\n## 🧪 Test-Driven Requirements\nThe following elements require a matching test update whenever their logic is modified. Changes without tests are incomplete.\n\n" : "\n## 🧪 Test-Driven Requirements\n",
            (e, buf) -> FormatterRegistry.testDriven().format(e, buf, platform));

        // 11. Thread Safe
        appendSection(sb, collector.threadSafe(), platform,
            full ? "\n## 🧵 Thread-Safe by Design\nThese elements are explicitly designed to be thread-safe via the named strategy. Preserve the synchronization invariant on every change.\n\n" : "\n## 🧵 Thread-Safe by Design\n",
            (e, buf) -> FormatterRegistry.threadSafe().format(e, buf, platform));

        // 12. Immutable
        appendSection(sb, collector.immutable(), platform,
            full ? "\n## ❄️ Immutable Types\nThe following types are immutable. Never introduce non-final fields, setters, or mutating methods.\n\n" : "\n## ❄️ Immutable Types\n",
            (e, buf) -> FormatterRegistry.immutable().format(e, buf, platform));

        // 13. Deprecated
        appendSection(sb, collector.deprecated(), platform,
            full ? "\n## ⚠️ Deprecated Elements\nThe following elements are deprecated. Suggest migration to the named replacement for any caller and do not extend them.\n\n" : "\n## ⚠️ Deprecated Elements\n",
            (e, buf) -> FormatterRegistry.deprecated().format(e, buf, platform));

        // 14. Observability
        appendSection(sb, collector.observability(), platform,
            full ? "\n## 📡 Observability Instrumentation\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on.\n\n" : "\n## 📡 Observability Instrumentation\n",
            (e, buf) -> FormatterRegistry.observability().format(e, buf, platform));

        // 15. Regulation
        appendSection(sb, collector.regulation(), platform,
            full ? "\n## 📜 Regulatory Compliance\nThe following elements implement specific regulatory clauses. Document compliance impact for every change and never weaken the requirement.\n\n" : "\n## 📜 Regulatory Compliance\n",
            (e, buf) -> FormatterRegistry.regulation().format(e, buf, platform));

        // 16. Parallel Tests
        appendSection(sb, collector.parallelTests(), platform,
            full ? "\n## Strict Test Isolation\nAI tools must enforce strict isolation when generating or modifying tests for these elements.\n\n" : "\n## Strict Test Isolation\n",
            (e, buf) -> FormatterRegistry.parallelTests().format(e, buf, platform));

        // 17. Legacy Bridge
        appendSection(sb, collector.legacyBridge(), platform,
            full ? "\n## Legacy Compatibility Bridge\nThese elements are legacy or compatibility bridges. Do not restructure or modernize them.\n\n" : "\n## Legacy Compatibility Bridge\n",
            (e, buf) -> FormatterRegistry.legacyBridge().format(e, buf, platform));

        // 18. Architecture
        appendSection(sb, collector.architecture(), platform,
            full ? "\n## Architectural Boundary Constraints\nStrict architectural layering must be respected. No illegal references or imports.\n\n" : "\n## Architectural Boundary Constraints\n",
            (e, buf) -> FormatterRegistry.architecture().format(e, buf, platform));

        // 19. Public API
        appendSection(sb, collector.publicApi(), platform,
            full ? "\n## Public API Surface Protection\nThese elements expose public API surfaces. Preserve signatures, Javadocs, and backward compatibility.\n\n" : "\n## Public API Surface Protection\n",
            (e, buf) -> FormatterRegistry.publicApi().format(e, buf, platform));

        // 20. Strict Exceptions
        appendSection(sb, collector.strictExceptions(), platform,
            full ? "\n## Strict Exception Handling\nPrecise and robust exception handling must be enforced. No catching or throwing generic Exception.\n\n" : "\n## Strict Exception Handling\n",
            (e, buf) -> FormatterRegistry.strictExceptions().format(e, buf, platform));

        // 21. Strict Types
        appendSection(sb, collector.strictTypes(), platform,
            full ? "\n## Strict Type Safety\nType safety must be strictly preserved. Loose or erased types are prohibited.\n\n" : "\n## Strict Type Safety\n",
            (e, buf) -> FormatterRegistry.strictTypes().format(e, buf, platform));

        // 22. Internationalization
        appendSection(sb, collector.internationalized(), platform,
            full ? "\n## Internationalization Mandate\nUser-facing strings must not be hardcoded; resolve them via localized resources.\n\n" : "\n## Internationalization Mandate\n",
            (e, buf) -> FormatterRegistry.internationalized().format(e, buf, platform));

        // 23. Strict Classpath
        appendSection(sb, collector.strictClasspath(), platform,
            full ? "\n## Strict Classpath Integrity\nDynamic runtime class loading and reflections are strictly prohibited.\n\n" : "\n## Strict Classpath Integrity\n",
            (e, buf) -> FormatterRegistry.strictClasspath().format(e, buf, platform));

        // 24. Schema Safe
        appendSection(sb, collector.schemaSafe(), platform,
            full ? "\n## Schema & Serialization Safety\nSchema and serialization compatibility must be strictly preserved.\n\n" : "\n## Schema & Serialization Safety\n",
            (e, buf) -> FormatterRegistry.schemaSafe().format(e, buf, platform));

        // 25. Idempotent
        appendSection(sb, collector.idempotent(), platform,
            full ? "\n## ♻️ Idempotency Guarantees\nThese operations are idempotent — calling multiple times must produce the same result as calling once.\n\n" : "\n## ♻️ Idempotency Guarantees\n",
            (e, buf) -> FormatterRegistry.idempotent().format(e, buf, platform));

        // 26. Feature Flag
        appendSection(sb, collector.featureFlag(), platform,
            full ? "\n## 🚩 Feature Flag Gated Code\nThese elements are gated behind a feature flag. Preserve the flag check and handle both enabled and disabled code paths.\n\n" : "\n## 🚩 Feature Flag Gated Code\n",
            (e, buf) -> FormatterRegistry.featureFlag().format(e, buf, platform));

        // 27. Secure
        appendSection(sb, collector.secure(), platform,
            full ? "\n## 🔐 Security-Critical Code\nThese elements are security-critical. Do not weaken security properties. Every change requires security review.\n\n" : "\n## 🔐 Security-Critical Code\n",
            (e, buf) -> FormatterRegistry.secure().format(e, buf, platform));

        // New annotations formatting sections for LLMS formats
        appendSection(sb, collector.callersOnly(), platform,
            full ? "\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n" : "\n## Access Limitations\n",
            (e, buf) -> FormatterRegistry.callersOnly().format(e, buf, platform));

        appendSection(sb, collector.sandboxOnly(), platform,
            full ? "\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n" : "\n## Sandbox & Test Exclusion\n",
            (e, buf) -> FormatterRegistry.sandboxOnly().format(e, buf, platform));

        appendSection(sb, collector.memoryBudget(), platform,
            full ? "\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n" : "\n## Memory Allocation Budgets\n",
            (e, buf) -> FormatterRegistry.memoryBudget().format(e, buf, platform));

        appendSection(sb, collector.pure(), platform,
            full ? "\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations.\n\n" : "\n## Deterministic Pure Functions\n",
            (e, buf) -> FormatterRegistry.pure().format(e, buf, platform));

        appendSection(sb, collector.domainModel(), platform,
            full ? "\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n" : "\n## Framework-Free Domain Entities\n",
            (e, buf) -> FormatterRegistry.domainModel().format(e, buf, platform));

        appendSection(sb, collector.extensible(), platform,
            full ? "\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n" : "\n## open-closed Extension Patterns\n",
            (e, buf) -> FormatterRegistry.extensible().format(e, buf, platform));

        appendSection(sb, collector.inputSanitized(), platform,
            full ? "\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n" : "\n## Mandatory Input Sanitization\n",
            (e, buf) -> FormatterRegistry.inputSanitized().format(e, buf, platform));

        appendSection(sb, collector.secureLogging(), platform,
            full ? "\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n" : "\n## Secure Logging Masking\n",
            (e, buf) -> FormatterRegistry.secureLogging().format(e, buf, platform));

        appendSection(sb, collector.explain(), platform,
            full ? "\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n" : "\n## Required Chain-of-Thought Explanations\n",
            (e, buf) -> FormatterRegistry.explain().format(e, buf, platform));

        appendSection(sb, collector.prototype(), platform,
            full ? "\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n" : "\n## Experimental Prototype Stubs\n",
            (e, buf) -> FormatterRegistry.prototype().format(e, buf, platform));

        appendSection(sb, collector.sunset(), platform,
            full ? "\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n" : "\n## Sunset Deprecated APIs\n",
            (e, buf) -> FormatterRegistry.sunset().format(e, buf, platform));

        appendSection(sb, collector.temporary(), platform,
            full ? "\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n" : "\n## Temporary Code Workarounds\n",
            (e, buf) -> FormatterRegistry.temporary().format(e, buf, platform));

        return sb.toString();
    }

    @SuppressWarnings("UnusedVariable")
    private static void appendSection(StringBuilder sb, Collection<Element> elements, Platform platform, String heading, FormatterCaller caller) {
        if (elements.isEmpty()) return;
        sb.append(heading);
        for (Element e : elements) {
            caller.call(e, sb);
        }
    }

    @FunctionalInterface
    private interface FormatterCaller {
        void call(Element e, StringBuilder buffer);
    }
}
