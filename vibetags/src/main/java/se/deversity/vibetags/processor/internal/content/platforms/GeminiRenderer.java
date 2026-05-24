package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `GEMINI.md` and `gemini_instructions.md`.
 */
public final class GeminiRenderer implements PlatformRenderer {
    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# GEMINI AI INSTRUCTIONS\n").append(context.getGeneratedHeader()).append("\n");

        if (!collector.locked().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.locked()) FormatterRegistry.locked().format(e, sec, platform);
            sb.append("\n## LOCKED FILES (DO NOT MODIFY)\nDo not suggest modifications to the following files:\n\n").append(sec);
        }

        if (!collector.context().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.context()) FormatterRegistry.context().format(e, sec, platform);
            sb.append("\n## CONTEXTUAL RULES\nApply the following context when assisting with these files:\n\n").append(sec);
        }

        if (!collector.audit().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.audit()) FormatterRegistry.audit().format(e, sec, platform);
            sb.append("\n## CONTINUOUS AUDIT REQUIREMENTS\nYou are acting as a Senior Staff Engineer. Whenever you write code for the files listed below, you must ensure your completions and chat responses strictly prevent the listed vulnerabilities:\n\n").append(sec);
        }

        if (!collector.ignore().isEmpty()) {
            StringBuilder sec = new StringBuilder("## IGNORED ELEMENTS\nThe following elements must be completely excluded from AI context and completions:\n\n");
            for (Element e : collector.ignore()) FormatterRegistry.ignore().format(e, sec, platform);
            sb.append(sec);
        }

        if (!collector.draft().isEmpty()) {
            StringBuilder sec = new StringBuilder("## IMPLEMENTATION TASKS\nThe following elements are drafts that need implementation:\n\n");
            for (Element e : collector.draft()) FormatterRegistry.draft().format(e, sec, platform);
            sb.append(sec);
        }

        if (!collector.privacy().isEmpty()) {
            StringBuilder sec = new StringBuilder("## PII / PRIVACY GUARDRAILS\nThe following elements handle Personally Identifiable Information (PII).\nNever include their runtime values in logs, console output, external API calls,\ntest fixtures, mock data, or code suggestions.\n\n");
            for (Element e : collector.privacy()) FormatterRegistry.privacy().format(e, sec, platform);
            sb.append(sec);
        }

        if (!collector.core().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.core()) FormatterRegistry.core().format(e, sec, platform);
            sb.append("\n## CORE FUNCTIONALITY (EXTREME CAUTION)\nThe following elements are well-tested core components. Make changes with extreme caution:\n\n").append(sec);
        }

        if (!collector.performance().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.performance()) FormatterRegistry.performance().format(e, sec, platform);
            sb.append("\n## PERFORMANCE CONSTRAINTS (HOT PATH)\nNever introduce O(n²) complexity into these elements. Always reason about complexity before proposing changes:\n\n").append(sec);
        }

        if (!collector.contract().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.contract()) FormatterRegistry.contract().format(e, sec, platform);
            sb.append("\n## CONTRACT-FROZEN SIGNATURES\nInternal implementation may be changed, but MUST NOT alter method names, parameter types, parameter order, return types, or checked exceptions:\n\n").append(sec);
        }

        if (!collector.testDriven().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.testDriven()) FormatterRegistry.testDriven().format(e, sec, platform);
            sb.append("\n## TEST-DRIVEN REQUIREMENTS\nChanges to the following elements MUST be accompanied by matching test code in the same response:\n\n").append(sec);
        }

        if (!collector.threadSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.threadSafe()) FormatterRegistry.threadSafe().format(e, sec, platform);
            sb.append("\n## THREAD-SAFE BY DESIGN\nThese elements are thread-safe by design — preserve the synchronization invariant on every change:\n\n").append(sec);
        }

        if (!collector.immutable().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.immutable()) FormatterRegistry.immutable().format(e, sec, platform);
            sb.append("\n## IMMUTABLE TYPES\nThe following types are immutable. Do not introduce non-final fields, setters, or mutating methods:\n\n").append(sec);
        }

        if (!collector.deprecated().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.deprecated()) FormatterRegistry.deprecated().format(e, sec, platform);
            sb.append("\n## DEPRECATED ELEMENTS\nThe following elements are deprecated. Suggest migration to the named replacement for any caller:\n\n").append(sec);
        }

        if (!collector.observability().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.observability()) FormatterRegistry.observability().format(e, sec, platform);
            sb.append("\n## OBSERVABILITY INSTRUMENTATION\nThe following elements emit metrics, traces, or log statements watched by dashboards. Preserve every instrumentation point:\n\n").append(sec);
        }

        if (!collector.regulation().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.regulation()) FormatterRegistry.regulation().format(e, sec, platform);
            sb.append("\n## REGULATORY COMPLIANCE\nThe following elements implement compliance clauses. Document compliance impact for every change:\n\n").append(sec);
        }

        if (!collector.parallelTests().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.parallelTests()) FormatterRegistry.parallelTests().format(e, sec, platform);
            sb.append("\n## STRICT TEST ISOLATION\nTests for these elements must run in complete isolation without sharing mutable state:\n\n").append(sec);
        }

        if (!collector.legacyBridge().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.legacyBridge()) FormatterRegistry.legacyBridge().format(e, sec, platform);
            sb.append("\n## LEGACY COMPATIBILITY BRIDGE\nDo not restructure compatibility bridges. Only modify business logic:\n\n").append(sec);
        }

        if (!collector.architecture().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.architecture()) FormatterRegistry.architecture().format(e, sec, platform);
            sb.append("\n## ARCHITECTURAL BOUNDARY CONSTRAINTS\nRespect architectural layering. Boundary crossing references are prohibited:\n\n").append(sec);
        }

        if (!collector.publicApi().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.publicApi()) FormatterRegistry.publicApi().format(e, sec, platform);
            sb.append("\n## PUBLIC API SURFACE PROTECTION\nPreserve public signatures, Javadoc, and backwards compatibility:\n\n").append(sec);
        }

        if (!collector.strictExceptions().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.strictExceptions()) FormatterRegistry.strictExceptions().format(e, sec, platform);
            sb.append("\n## STRICT EXCEPTION HANDLING\nCatching/throwing generic Exception is prohibited. Use precise exceptions:\n\n").append(sec);
        }

        if (!collector.strictTypes().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.strictTypes()) FormatterRegistry.strictTypes().format(e, sec, platform);
            sb.append("\n## STRICT TYPE SAFETY\nLoose typing is prohibited. Strongly-typed models required:\n\n").append(sec);
        }

        if (!collector.internationalized().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.internationalized()) FormatterRegistry.internationalized().format(e, sec, platform);
            sb.append("\n## INTERNATIONALIZATION MANDATE\nUser-facing strings must not be hardcoded; retrieve from resources:\n\n").append(sec);
        }

        if (!collector.strictClasspath().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.strictClasspath()) FormatterRegistry.strictClasspath().format(e, sec, platform);
            sb.append("\n## STRICT CLASSPATH INTEGRITY\nDynamic class loading and reflection hacks are strictly prohibited:\n\n").append(sec);
        }

        if (!collector.schemaSafe().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.schemaSafe()) FormatterRegistry.schemaSafe().format(e, sec, platform);
            sb.append("\n## SCHEMA & SERIALIZATION SAFETY\nModifying schema or data formats without explicit migration plans is prohibited:\n\n").append(sec);
        }

        if (!collector.idempotent().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.idempotent()) FormatterRegistry.idempotent().format(e, sec, platform);
            sb.append("\n## IDEMPOTENCY GUARANTEES\nThese operations must remain idempotent — calling them multiple times must produce the same result:\n\n").append(sec);
        }

        if (!collector.featureFlag().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.featureFlag()) FormatterRegistry.featureFlag().format(e, sec, platform);
            sb.append("\n## FEATURE FLAG GATED CODE\nThese elements are gated behind a feature flag. Never assume the flag is always active:\n\n").append(sec);
        }

        if (!collector.secure().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.secure()) FormatterRegistry.secure().format(e, sec, platform);
            sb.append("\n## SECURITY-CRITICAL CODE\nDo not weaken security properties of these elements. Flag any change for security review:\n\n").append(sec);
        }

        return sb.toString();
    }
}
