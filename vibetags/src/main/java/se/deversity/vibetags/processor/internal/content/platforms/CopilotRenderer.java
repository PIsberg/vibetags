package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.of;

/**
 * PlatformRenderer for generating `.github/copilot-instructions.md`.
 */
public final class CopilotRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        of("\n## Security Audit Requirements\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n", AnnotationCollector::audit, FormatterRegistry.audit()),
        of("\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n", AnnotationCollector::ignore, FormatterRegistry.ignore()),
        of("\n## Implementation Tasks\nFollow these instructions to implement the drafts:\n\n", AnnotationCollector::draft, FormatterRegistry.draft()),
        of("\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs the runtime values of these elements:\n\n", AnnotationCollector::privacy, FormatterRegistry.privacy()),
        of("\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n", AnnotationCollector::core, FormatterRegistry.core()),
        of("\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n", AnnotationCollector::performance, FormatterRegistry.performance()),
        of("\n## Contract-Frozen Signatures\nDo not modify the public signatures of the following elements. Internal implementation changes are permitted:\n\n", AnnotationCollector::contract, FormatterRegistry.contract()),
        of("\n## Test-Driven Requirements\nDo not suggest changes to the following elements without also providing the corresponding test update:\n\n", AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        of("\n## Thread-Safe by Design\nDo not modify these elements without preserving their thread-safety strategy:\n\n", AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        of("\n## Immutable Types\nThe following types are immutable. Do not add mutating methods or non-final fields:\n\n", AnnotationCollector::immutable, FormatterRegistry.immutable()),
        of("\n## Deprecated Elements\nDo not extend these elements. Migrate callers to the listed replacement:\n\n", AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        of("\n## Observability Instrumentation\nThe following elements have metrics/traces/logs watched by dashboards. Do not delete or rename them silently:\n\n", AnnotationCollector::observability, FormatterRegistry.observability()),
        of("\n## Regulatory Compliance\nThese elements implement compliance clauses. Document the compliance impact of every change:\n\n", AnnotationCollector::regulation, FormatterRegistry.regulation()),
        of("\n## Strict Test Isolation\nDo not share mutable state or external resources in tests for these elements:\n\n", AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        of("\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n", AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        of("\n## Architectural Boundary Constraints\nStrict layering must be respected. Boundary crossing references are prohibited:\n\n", AnnotationCollector::architecture, FormatterRegistry.architecture()),
        of("\n## Public API Surface Protection\nDo not modify public signatures or break compatibility for these elements:\n\n", AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        of("\n## Strict Exception Handling\nPrecise exception handling required. Do not catch or throw generic Exception/Throwable:\n\n", AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        of("\n## Strict Type Safety\nLoose typing is prohibited. Strongly-typed objects must be used:\n\n", AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        of("\n## Internationalization Mandate\nAll user-visible text must be localized. Do not hardcode strings:\n\n", AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        of("\n## Strict Classpath Integrity\nDynamic class loading and reflection hacks are strictly prohibited:\n\n", AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        of("\n## Schema & Serialization Safety\nDo not change serialization formats or schemas without a backward-compatible migration plan:\n\n", AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        of("\n## Idempotency Guarantees\nThe following operations are idempotent — calling them multiple times is safe:\n\n", AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        of("\n## Feature Flag Gated Code\nThe following elements are gated behind a feature flag — always preserve the flag check:\n\n", AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        of("\n## Security-Critical Code\nThe following elements are security-critical — do not weaken their security properties:\n\n", AnnotationCollector::secure, FormatterRegistry.secure()),
        of("\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries:\n\n", AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        of("\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them:\n\n", AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        of("\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully:\n\n", AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        of("\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations:\n\n", AnnotationCollector::pure, FormatterRegistry.pure()),
        of("\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages:\n\n", AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        of("\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals:\n\n", AnnotationCollector::extensible, FormatterRegistry.extensible()),
        of("\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers:\n\n", AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        of("\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams:\n\n", AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        of("\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough:\n\n", AnnotationCollector::explain, FormatterRegistry.explain()),
        of("\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them:\n\n", AnnotationCollector::prototype, FormatterRegistry.prototype()),
        of("\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden:\n\n", AnnotationCollector::sunset, FormatterRegistry.sunset()),
        of("\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit:\n\n", AnnotationCollector::temporary, FormatterRegistry.temporary())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# GitHub Copilot Instructions\n")
          .append(context.getGeneratedHeader())
          .append("# AUTO-GENERATED BY VIBETAGS. Do not edit manually.\n\n## Locked Files — DO NOT MODIFY\nDo not suggest changes to the following files:\n\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.COPILOT);
        }

        sb.append("\n## Contextual Guidelines\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.COPILOT);
        }

        AnnotationSections.render(sb, collector, Platform.COPILOT, SECTIONS);

        return sb.toString();
    }
}
