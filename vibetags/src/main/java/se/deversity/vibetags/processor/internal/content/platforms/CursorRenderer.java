package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.Section.of;

/**
 * PlatformRenderer for generating `.cursorrules`.
 */
public final class CursorRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        of("\n## 🛡️ MANDATORY SECURITY AUDITS\nWhen proposing edits or writing code for the following files, you MUST perform a security review before outputting the final code. You must explicitly state in your response that you have audited the changes for the required vulnerabilities.\n\n", AnnotationCollector::audit, FormatterRegistry.audit()),
        of("\n## 🚫 IGNORED ELEMENTS (EXCLUDE FROM CONTEXT)\nDo not reference, suggest changes to, or include the following in completions or answers.\n\n", AnnotationCollector::ignore, FormatterRegistry.ignore()),
        of("\n## 📝 IMPLEMENTATION TASKS (TODO)\nThe following elements are currently in DRAFT mode. Follow the instructions to implement them:\n\n", AnnotationCollector::draft, FormatterRegistry.draft()),
        of("\n## 🔒 PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNEVER include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n", AnnotationCollector::privacy, FormatterRegistry.privacy()),
        of("\n## 🧠 CORE FUNCTIONALITY (CHANGE WITH EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution.\n\n", AnnotationCollector::core, FormatterRegistry.core()),
        of("\n## ⚡ PERFORMANCE CONSTRAINTS (HOT PATH)\nThe following elements are on a hot path. Never introduce O(n²) complexity. Always reason about time/space before proposing changes.\n\n", AnnotationCollector::performance, FormatterRegistry.performance()),
        of("\n## 🔐 CONTRACT-FROZEN SIGNATURES\nThe following elements have contract-frozen public signatures. You MAY change internal implementation logic, but MUST NOT modify method names, parameter types, parameter order, return types, or checked exceptions.\n\n", AnnotationCollector::contract, FormatterRegistry.contract()),
        of("\n## 🧪 TEST-DRIVEN REQUIREMENTS\nThe following elements require a corresponding test update whenever their logic is modified.\nAI MUST NOT propose changes to these elements without also providing the matching test code.\n\n", AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        of("\n## 🧵 THREAD-SAFE BY DESIGN\nThe following elements are explicitly designed to be thread-safe via the named strategy. Any modification MUST preserve the synchronization invariant and document its reasoning.\n\n", AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        of("\n## ❄️ IMMUTABLE TYPES\nThe following types are declared immutable. NEVER introduce non-final fields, setters, or mutating methods.\n\n", AnnotationCollector::immutable, FormatterRegistry.immutable()),
        of("\n## ⚠️ DEPRECATED — ROUTE CALLERS AWAY\nThe following elements are deprecated. Do not extend them. Suggest migrating any caller to the named replacement.\n\n", AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        of("\n## 📡 OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements that downstream dashboards and alerts depend on. Never remove or rename instrumentation without flagging the affected dashboard.\n\n", AnnotationCollector::observability, FormatterRegistry.observability()),
        of("\n## 📜 REGULATORY COMPLIANCE\nThe following elements implement specific compliance clauses. Any change MUST document its compliance impact and MUST NOT weaken the requirement.\n\n", AnnotationCollector::regulation, FormatterRegistry.regulation()),
        of("\n## 🧪 STRICT TEST ISOLATION\nThe following elements must be strictly isolated when generating or modifying tests. No shared mutable state or resource conflicts are permitted.\n\n", AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        of("\n## 🌉 LEGACY COMPATIBILITY BRIDGE\nThe following elements are legacy compatibility bridges. Do not attempt to modernize or refactor their structural patterns; only modify internal business logic as explicitly requested.\n\n", AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        of("\n## 🏛️ ARCHITECTURAL BOUNDARY CONSTRAINTS\nThe following elements have strict layering constraints. Prohibit imports or references that cross boundaries.\n\n", AnnotationCollector::architecture, FormatterRegistry.architecture()),
        of("\n## 🔌 PUBLIC API SURFACE PROTECTION\nThe following elements are public-facing API surfaces. Always preserve public signatures, Javadoc, and backwards compatibility.\n\n", AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        of("\n## 🚨 STRICT EXCEPTION HANDLING\nThe following elements have strict exception constraints. Prohibit catching or throwing generic Exception/Throwable.\n\n", AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        of("\n## 🏷️ STRICT TYPE SAFETY\nThe following elements prohibit loose typing such as Object or Map<String, Object>. Strong type safety is required.\n\n", AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        of("\n## 🌐 INTERNATIONALIZATION MANDATE\nThe following elements implement i18n requirements. Prohibit hardcoded user-facing strings.\n\n", AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        of("\n## 🛡️ STRICT CLASSPATH INTEGRITY\nThe following elements prohibit dynamic runtime class loading, reflections, or loading of unverified dynamic code.\n\n", AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        of("\n## 🗄️ SCHEMA & SERIALIZATION SAFETY\nThe following elements have schema safety constraints. Restrict changing formats/fields without a backward-compatible migration plan.\n\n", AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        of("\n## ♻️ IDEMPOTENCY GUARANTEES\nThe following operations are idempotent. Multiple invocations MUST produce the same result as a single invocation. Never introduce side effects that break this guarantee.\n\n", AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        of("\n## 🚩 FEATURE FLAG GATED CODE\nThe following elements are gated behind a feature flag. Do not assume the flag is always active. Preserve the flag check.\n\n", AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        of("\n## 🔐 SECURITY-CRITICAL CODE\nThe following elements are security-critical. AI must not weaken security properties. Any change must be reviewed for security impact.\n\n", AnnotationCollector::secure, FormatterRegistry.secure())
    );

    private static final List<AnnotationSections.Section> ALL_SECTIONS =
        AnnotationSections.concat(SECTIONS, AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS);

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        AnnotationSections.renderLockedAndContextPreamble(sb, collector, Platform.CURSOR, context.getGeneratedHeader());
        AnnotationSections.render(sb, collector, Platform.CURSOR, ALL_SECTIONS);

        return sb.toString();
    }
}
