package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.util.LinkedHashMap;
import java.util.Map;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating per-class granular rules.
 */
public final class GranularRenderer implements PlatformRenderer {

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        // Return null since granular output is written per-element via writeGranular, not as a single file.
        return null;
    }

    public Map<Element, StringBuilder> renderGranular(AnnotationCollector collector) {
        Map<Element, StringBuilder> elementRules = new LinkedHashMap<>();

        for (Element e : collector.locked()) {
            AILocked locked = e.getAnnotation(AILocked.class);
            if (locked != null) {
                appendToGranular(elementRules, e, "Locked Status", "- **Reason**: " + locked.reason());
            }
        }
        for (Element e : collector.context()) {
            AIContext context = e.getAnnotation(AIContext.class);
            if (context != null) {
                appendToGranular(elementRules, e, "Context & Focus", "- **Focus**: " + context.focus() + "\n- **Avoid**: " + context.avoids());
            }
        }
        for (Element e : collector.ignore()) {
            appendToGranular(elementRules, e, "Exclusion Rule", "This element is strictly excluded from AI context. Do not reference it.");
        }
        for (Element e : collector.audit()) {
            AIAudit audit = e.getAnnotation(AIAudit.class);
            if (audit != null && audit.checkFor().length > 0) {
                appendToGranular(elementRules, e, "Security Audit Requirements", "When modifying this element, audit for:\n- " + String.join("\n- ", audit.checkFor()));
            }
        }
        for (Element e : collector.draft()) {
            AIDraft draft = e.getAnnotation(AIDraft.class);
            if (draft != null) {
                appendToGranular(elementRules, e, "Implementation Tasks", "- **Instruction**: " + draft.instructions());
            }
        }
        for (Element e : collector.privacy()) {
            AIPrivacy privacy = e.getAnnotation(AIPrivacy.class);
            if (privacy != null) {
                appendToGranular(elementRules, e, "PII / Privacy Guardrails", "- **Rule**: Never log or expose runtime values of this element.\n- **Reason**: " + privacy.reason());
            }
        }
        for (Element e : collector.core()) {
            AICore core = e.getAnnotation(AICore.class);
            if (core != null) {
                appendToGranular(elementRules, e, "Core Functionality", "- **Sensitivity**: " + core.sensitivity() + "\n- **Note**: " + core.note());
            }
        }
        for (Element e : collector.performance()) {
            AIPerformance perf = e.getAnnotation(AIPerformance.class);
            if (perf != null) {
                appendToGranular(elementRules, e, "Performance Constraints", "- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: " + perf.constraint());
            }
        }
        for (Element e : collector.contract()) {
            AIContract contract = e.getAnnotation(AIContract.class);
            if (contract != null) {
                appendToGranular(elementRules, e, "Contract-Frozen Signature", "- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.\n- **Reason**: " + contract.reason());
            }
        }
        for (Element e : collector.testDriven()) {
            AITestDriven td = e.getAnnotation(AITestDriven.class);
            if (td != null) {
                StringBuilder frameworks = new StringBuilder();
                for (AITestDriven.Framework f : td.framework()) {
                    if (frameworks.length() > 0) frameworks.append(", ");
                    frameworks.append(f.name());
                }
                String frameworksStr = frameworks.toString();
                String locationHint = td.testLocation().isEmpty() ? "" : "\n- **Test Location**: " + td.testLocation();
                String mockHint = td.mockPolicy().isEmpty() ? "" : "\n- **Mock Policy**: " + td.mockPolicy();
                appendToGranular(elementRules, e, "Test-Driven Requirements", "- **Rule**: Changes MUST be accompanied by a matching test update.\n- **Coverage Goal**: " + td.coverageGoal() + "%\n- **Frameworks**: " + frameworksStr + locationHint + mockHint);
            }
        }
        for (Element e : collector.threadSafe()) {
            AIThreadSafe ts = e.getAnnotation(AIThreadSafe.class);
            if (ts != null) {
                appendToGranular(elementRules, e, "Thread-Safety Guarantee", "- **Strategy**: " + ts.strategy().name() + (ts.note().isEmpty() ? "" : "\n- **Note**: " + ts.note()));
            }
        }
        for (Element e : collector.immutable()) {
            AIImmutable im = e.getAnnotation(AIImmutable.class);
            if (im != null) {
                appendToGranular(elementRules, e, "Immutable Type", "- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods." + (im.note().isEmpty() ? "" : "\n- **Note**: " + im.note()));
            }
        }
        for (Element e : collector.deprecated()) {
            AIDeprecated dep = e.getAnnotation(AIDeprecated.class);
            if (dep != null) {
                appendToGranular(elementRules, e, "Deprecated — Migrate Callers", (dep.replacedBy().isEmpty() ? "" : "- **Replaced by**: " + dep.replacedBy() + "\n") + "- **Migration**: " + dep.migrationGuide() + (dep.deadline().isEmpty() ? "" : "\n- **Deadline**: " + dep.deadline()));
            }
        }
        for (Element e : collector.observability()) {
            AIObservability obs = e.getAnnotation(AIObservability.class);
            if (obs != null) {
                StringBuilder summary = new StringBuilder();
                if (obs.metrics().length > 0) summary.append("Metrics: ").append(String.join(", ", obs.metrics())).append(". ");
                if (obs.traces().length > 0)  summary.append("Traces: ").append(String.join(", ", obs.traces())).append(". ");
                if (obs.logs().length > 0)    summary.append("Logs: ").append(String.join(", ", obs.logs())).append(". ");
                if (!obs.note().isEmpty())    summary.append("Note: ").append(obs.note());
                appendToGranular(elementRules, e, "Observability Instrumentation", "- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.\n- **Details**: " + summary);
            }
        }
        for (Element e : collector.regulation()) {
            AIRegulation reg = e.getAnnotation(AIRegulation.class);
            if (reg != null) {
                appendToGranular(elementRules, e, "Regulatory Compliance", "- **Standard**: " + reg.standard() + (reg.clause().isEmpty() ? "" : "\n- **Clause**: " + reg.clause()) + "\n- **Description**: " + reg.description());
            }
        }
        for (Element e : collector.parallelTests()) {
            appendToGranular(elementRules, e, "Strict Test Isolation", "- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.");
        }
        for (Element e : collector.legacyBridge()) {
            appendToGranular(elementRules, e, "Legacy Compatibility Bridge", "- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.");
        }
        for (Element e : collector.architecture()) {
            AIArchitecture arch = e.getAnnotation(AIArchitecture.class);
            if (arch != null) {
                String cannotRefStr = String.join(", ", arch.cannotReference());
                appendToGranular(elementRules, e, "Architectural Boundary Constraints", "- **Layer**: " + arch.belongsTo() + (arch.cannotReference().length > 0 ? "\n- **Prohibited References**: " + cannotRefStr : ""));
            }
        }
        for (Element e : collector.publicApi()) {
            appendToGranular(elementRules, e, "Public API Surface Protection", "- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.");
        }
        for (Element e : collector.strictExceptions()) {
            appendToGranular(elementRules, e, "Strict Exception Handling", "- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.");
        }
        for (Element e : collector.strictTypes()) {
            appendToGranular(elementRules, e, "Strict Type Safety", "- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.");
        }
        for (Element e : collector.internationalized()) {
            appendToGranular(elementRules, e, "Internationalization Mandate", "- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.");
        }
        for (Element e : collector.strictClasspath()) {
            appendToGranular(elementRules, e, "Strict Classpath Integrity", "- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.");
        }
        for (Element e : collector.schemaSafe()) {
            appendToGranular(elementRules, e, "Schema & Serialization Safety", "- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.");
        }
        for (Element e : collector.idempotent()) {
            AIIdempotent idempotent = e.getAnnotation(AIIdempotent.class);
            if (idempotent != null) {
                appendToGranular(elementRules, e, "Idempotency Guarantee", "- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once." + (idempotent.reason().isEmpty() ? "" : "\n- **Reason**: " + idempotent.reason()));
            }
        }
        for (Element e : collector.featureFlag()) {
            AIFeatureFlag ff = e.getAnnotation(AIFeatureFlag.class);
            if (ff != null) {
                String flagDisplay = ff.flag().isEmpty() ? "(unspecified)" : "'" + ff.flag() + "'";
                appendToGranular(elementRules, e, "Feature Flag Gate", "- **Flag**: " + flagDisplay + " (default: " + ff.defaultValue() + ")\n- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.");
            }
        }
        for (Element e : collector.secure()) {
            AISecure secure = e.getAnnotation(AISecure.class);
            if (secure != null) {
                appendToGranular(elementRules, e, "Security-Critical Code", "- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact." + (secure.aspect().isEmpty() ? "" : "\n- **Aspect**: " + secure.aspect()));
            }
        }

        return elementRules;
    }

    private void appendToGranular(Map<Element, StringBuilder> elementRules, Element element, String title, String content) {
        Element owner = ElementNaming.owningElement(element);
        StringBuilder sb = elementRules.computeIfAbsent(owner, k -> new StringBuilder());
        if (sb.length() > 0) sb.append("\n");

        if (!owner.equals(element)) {
            ElementKind kind = element.getKind();
            String kindStr = (kind != null) ? kind.toString().toLowerCase(java.util.Locale.ROOT) : "element";
            sb.append("### Rules for ").append(kindStr).append(" ").append(element.getSimpleName()).append("\n");
        } else {
            sb.append("## ").append(title).append("\n");
        }
        sb.append(content).append("\n");
    }
}
