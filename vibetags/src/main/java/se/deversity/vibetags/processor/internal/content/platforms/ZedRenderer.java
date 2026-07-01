package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.headerless;
import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.of;

/**
 * PlatformRenderer for generating `.rules` for Zed.
 */
public final class ZedRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        headerless(AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        headerless(AnnotationCollector::immutable, FormatterRegistry.immutable()),
        headerless(AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        headerless(AnnotationCollector::observability, FormatterRegistry.observability()),
        headerless(AnnotationCollector::regulation, FormatterRegistry.regulation()),
        of("\n## Security Audits\nBefore suggesting changes to the following files, audit for the listed vulnerabilities:\n\n", AnnotationCollector::audit, FormatterRegistry.audit()),
        of("\n## Ignored Elements\nDo not reference or suggest changes to the following:\n\n", AnnotationCollector::ignore, FormatterRegistry.ignore()),
        of("\n## Implementation Tasks\nThe following elements are drafts that need implementation:\n\n", AnnotationCollector::draft, FormatterRegistry.draft()),
        of("\n## PII / Privacy Guardrails\nNever log, expose, or suggest code that outputs runtime values of these elements:\n\n", AnnotationCollector::privacy, FormatterRegistry.privacy()),
        of("\n## Core Functionality (Extreme Caution)\nThe following elements are well-tested core components — change with extreme caution:\n\n", AnnotationCollector::core, FormatterRegistry.core()),
        of("\n## Performance Constraints\nThe following elements are on a hot path — always reason about time and space complexity:\n\n", AnnotationCollector::performance, FormatterRegistry.performance()),
        of("\n## Contract-Frozen Signatures\nInternal logic may be changed; never alter method names, parameter types, order, return types, or checked exceptions:\n\n", AnnotationCollector::contract, FormatterRegistry.contract()),
        of("\n## Test-Driven Requirements\nChanges to the following elements must be accompanied by matching test code:\n\n", AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        of("\n## Strict Test Isolation\nEnforce strict isolation in tests for these elements:\n\n", AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        of("\n## Legacy Compatibility Bridge\nDo not refactor the structural patterns of these compatibility bridges:\n\n", AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        of("\n## Architectural Constraints\nStrict layered architecture constraints apply to these elements:\n\n", AnnotationCollector::architecture, FormatterRegistry.architecture()),
        of("\n## Public API Protection\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n", AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        of("\n## Strict Exceptions\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n", AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        of("\n## Strict Types\nLoose typing is strictly prohibited for these elements:\n\n", AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        of("\n## Internationalization\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n", AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        of("\n## Strict Classpath\nDynamic runtime class loading is strictly prohibited for these elements:\n\n", AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        of("\n## Schema Safety\nModifying schema or data formats without explicit migration plans is prohibited:\n\n", AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        of("\n## Idempotency\nThese operations must remain idempotent:\n\n", AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        of("\n## Feature Flags\nThese elements are behind feature flags — never assume always active:\n\n", AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        of("\n## Security-Critical Code\nNever weaken security properties of these elements:\n\n", AnnotationCollector::secure, FormatterRegistry.secure()),
        of("\n## Access Limitations\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n", AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        of("\n## Sandbox & Test Exclusion\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n", AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        of("\n## Memory Allocation Budgets\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n", AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        of("\n## Deterministic Pure Functions\nThe following elements must remain pure functions without side effects or mutations.\n\n", AnnotationCollector::pure, FormatterRegistry.pure()),
        of("\n## Framework-Free Domain Entities\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n", AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        of("\n## open-closed Extension Patterns\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n", AnnotationCollector::extensible, FormatterRegistry.extensible()),
        of("\n## Mandatory Input Sanitization\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n", AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        of("\n## Secure Logging Masking\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n", AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        of("\n## Required Chain-of-Thought Explanations\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n", AnnotationCollector::explain, FormatterRegistry.explain()),
        of("\n## Experimental Prototype Stubs\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n", AnnotationCollector::prototype, FormatterRegistry.prototype()),
        of("\n## Sunset Deprecated APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n", AnnotationCollector::sunset, FormatterRegistry.sunset()),
        of("\n## Temporary Code Workarounds\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n", AnnotationCollector::temporary, FormatterRegistry.temporary())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(context.getGeneratedHeader())
          .append("# Do not edit manually.\n\n## Locked Files (Do Not Modify)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.ZED);
        }

        sb.append("\n## Context Guidelines\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.ZED);
        }

        AnnotationSections.render(sb, collector, Platform.ZED, SECTIONS);

        return sb.toString();
    }
}
