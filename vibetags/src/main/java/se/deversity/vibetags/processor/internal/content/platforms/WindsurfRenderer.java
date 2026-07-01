package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.headerless;
import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.of;

/**
 * PlatformRenderer for generating `.windsurfrules`.
 */
public final class WindsurfRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        headerless(AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        headerless(AnnotationCollector::immutable, FormatterRegistry.immutable()),
        headerless(AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        headerless(AnnotationCollector::observability, FormatterRegistry.observability()),
        headerless(AnnotationCollector::regulation, FormatterRegistry.regulation()),
        of("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code.\n\n", AnnotationCollector::audit, FormatterRegistry.audit()),
        of("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n", AnnotationCollector::ignore, FormatterRegistry.ignore()),
        of("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n", AnnotationCollector::draft, FormatterRegistry.draft()),
        of("\n## 🔒 PII / PRIVACY GUARDRAILS\nNever include runtime values of the following in logs, console output, external API calls, test fixtures, or mock data.\n\n", AnnotationCollector::privacy, FormatterRegistry.privacy()),
        of("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n", AnnotationCollector::core, FormatterRegistry.core()),
        of("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) or worse complexity. Always reason about time/space before proposing changes.\n\n", AnnotationCollector::performance, FormatterRegistry.performance()),
        of("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions.\n\n", AnnotationCollector::contract, FormatterRegistry.contract()),
        of("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nAI MUST NOT propose changes to the following elements without also providing the matching test code.\n\n", AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        of("\n## 🧪 STRICT TEST ISOLATION\nEnforce strict isolation for tests generated or modified for these elements:\n\n", AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        of("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nDo not refactor the structural patterns of these compatibility bridges:\n\n", AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        of("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nStrict layered architecture constraints apply to these elements:\n\n", AnnotationCollector::architecture, FormatterRegistry.architecture()),
        of("\n## 🔌 PUBLIC API SURFACE PROTECTION\nPublic API surface. Signatures and backwards compatibility must be strictly preserved:\n\n", AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        of("\n## 🚨 STRICT EXCEPTION HANDLING\nGeneric exception throwing/catching is strictly prohibited for these elements:\n\n", AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        of("\n## 🏷️ STRICT TYPE SAFETY\nLoose typing is strictly prohibited for these elements:\n\n", AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        of("\n## 🌐 INTERNATIONALIZATION MANDATE\nHardcoding user-facing strings is strictly prohibited for these elements:\n\n", AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        of("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nDynamic runtime class loading is strictly prohibited for these elements:\n\n", AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        of("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n", AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        of("\n## ♻️ IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — multiple calls = same as one call:\n\n", AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        of("\n## 🚩 FEATURE FLAG GATED CODE\nNever assume these feature flags are always active — preserve all flag checks:\n\n", AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        of("\n## 🔐 SECURITY-CRITICAL CODE\nNever weaken security properties of these elements. Every change requires security review:\n\n", AnnotationCollector::secure, FormatterRegistry.secure())
    );

    private static final List<AnnotationSections.Section> ALL_SECTIONS =
        AnnotationSections.concat(SECTIONS, AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS);

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        AnnotationSections.renderLockedAndContextPreamble(sb, collector, Platform.WINDSURF, context.getGeneratedHeader());
        AnnotationSections.render(sb, collector, Platform.WINDSURF, ALL_SECTIONS);

        return sb.toString();
    }
}
