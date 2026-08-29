package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating per-class granular rules.
 */
public final class GranularRenderer implements PlatformRenderer {

    /**
     * {@code AIIgnore.reason()}'s declared default, read once at class-load. If the annotation ever
     * loses the member, every reason is printed, which is noisy rather than wrong.
     */
    private static final String IGNORE_DEFAULT_REASON = ignoreDefaultReason();

    @Override
    public @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context) {
        // Return null since granular output is written per-element via writeGranular, not as a single file.
        return null;
    }

    public Map<TaggedElement, GranularBody> renderGranular(GuardrailModel model) {
        Map<TaggedElement, GranularBody> elementRules = new LinkedHashMap<>();

        for (TaggedElement e : model.locked()) {
            AILocked locked = e.annotation(AILocked.class);
            if (locked != null) {
                appendToGranular(elementRules, e, "Locked Status", "- **Reason**: " + locked.reason());
            }
        }
        for (TaggedElement e : model.context()) {
            AIContext context = e.annotation(AIContext.class);
            if (context != null) {
                appendToGranular(elementRules, e, "Context & Focus", "- **Focus**: " + context.focus() + "\n- **Avoid**: " + context.avoids());
            }
        }
        for (TaggedElement e : model.ignore()) {
            AIIgnore ignore = e.annotation(AIIgnore.class);
            appendToGranular(elementRules, e, "Exclusion Rule", "This element is strictly excluded from AI context. Do not reference it."
                + reason(ignore == null ? "" : ignore.reason(), IGNORE_DEFAULT_REASON));
        }
        for (TaggedElement e : model.audit()) {
            AIAudit audit = e.annotation(AIAudit.class);
            if (audit != null && audit.checkFor().length > 0) {
                appendToGranular(elementRules, e, "Security Audit Requirements", "When modifying this element, audit for:\n- " + String.join("\n- ", audit.checkFor()));
            }
        }
        for (TaggedElement e : model.draft()) {
            AIDraft draft = e.annotation(AIDraft.class);
            if (draft != null) {
                appendToGranular(elementRules, e, "Implementation Tasks", "- **Instruction**: " + draft.instructions());
            }
        }
        for (TaggedElement e : model.privacy()) {
            AIPrivacy privacy = e.annotation(AIPrivacy.class);
            if (privacy != null) {
                appendToGranular(elementRules, e, "PII / Privacy Guardrails", "- **Rule**: Never log or expose runtime values of this element.\n- **Reason**: " + privacy.reason());
            }
        }
        for (TaggedElement e : model.core()) {
            AICore core = e.annotation(AICore.class);
            if (core != null) {
                appendToGranular(elementRules, e, "Core Functionality", "- **Sensitivity**: " + core.sensitivity() + "\n- **Note**: " + core.note());
            }
        }
        for (TaggedElement e : model.performance()) {
            AIPerformance perf = e.annotation(AIPerformance.class);
            if (perf != null) {
                appendToGranular(elementRules, e, "Performance Constraints", "- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: " + perf.constraint());
            }
        }
        for (TaggedElement e : model.contract()) {
            AIContract contract = e.annotation(AIContract.class);
            if (contract != null) {
                appendToGranular(elementRules, e, "Contract-Frozen Signature", "- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.\n- **Reason**: " + contract.reason());
            }
        }
        for (TaggedElement e : model.testDriven()) {
            AITestDriven td = e.annotation(AITestDriven.class);
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
        for (TaggedElement e : model.threadSafe()) {
            AIThreadSafe ts = e.annotation(AIThreadSafe.class);
            if (ts != null) {
                appendToGranular(elementRules, e, "Thread-Safety Guarantee", "- **Strategy**: " + ts.strategy().name() + (ts.note().isEmpty() ? "" : "\n- **Note**: " + ts.note()));
            }
        }
        for (TaggedElement e : model.immutable()) {
            AIImmutable im = e.annotation(AIImmutable.class);
            if (im != null) {
                appendToGranular(elementRules, e, "Immutable Type", "- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods." + (im.note().isEmpty() ? "" : "\n- **Note**: " + im.note()));
            }
        }
        for (TaggedElement e : model.deprecated()) {
            AIDeprecated dep = e.annotation(AIDeprecated.class);
            if (dep != null) {
                appendToGranular(elementRules, e, "Deprecated — Migrate Callers", (dep.replacedBy().isEmpty() ? "" : "- **Replaced by**: " + dep.replacedBy() + "\n") + "- **Migration**: " + dep.migrationGuide() + (dep.deadline().isEmpty() ? "" : "\n- **Deadline**: " + dep.deadline()));
            }
        }
        for (TaggedElement e : model.observability()) {
            AIObservability obs = e.annotation(AIObservability.class);
            if (obs != null) {
                StringBuilder summary = new StringBuilder();
                if (obs.metrics().length > 0) summary.append("Metrics: ").append(String.join(", ", obs.metrics())).append(". ");
                if (obs.traces().length > 0)  summary.append("Traces: ").append(String.join(", ", obs.traces())).append(". ");
                if (obs.logs().length > 0)    summary.append("Logs: ").append(String.join(", ", obs.logs())).append(". ");
                if (!obs.note().isEmpty())    summary.append("Note: ").append(obs.note());
                appendToGranular(elementRules, e, "Observability Instrumentation", "- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.\n- **Details**: " + summary);
            }
        }
        for (TaggedElement e : model.regulation()) {
            AIRegulation reg = e.annotation(AIRegulation.class);
            if (reg != null) {
                appendToGranular(elementRules, e, "Regulatory Compliance", "- **Standard**: " + reg.standard() + (reg.clause().isEmpty() ? "" : "\n- **Clause**: " + reg.clause()) + "\n- **Description**: " + reg.description());
            }
        }
        for (TaggedElement e : model.parallelTests()) {
            AIParallelTests parallel = e.annotation(AIParallelTests.class);
            appendToGranular(elementRules, e, "Strict Test Isolation", "- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources."
                + reason(parallel == null ? "" : parallel.reason()));
        }
        for (TaggedElement e : model.legacyBridge()) {
            AILegacyBridge bridge = e.annotation(AILegacyBridge.class);
            appendToGranular(elementRules, e, "Legacy Compatibility Bridge", "- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested."
                + reason(bridge == null ? "" : bridge.reason()));
        }
        for (TaggedElement e : model.architecture()) {
            AIArchitecture arch = e.annotation(AIArchitecture.class);
            if (arch != null) {
                String cannotRefStr = String.join(", ", arch.cannotReference());
                appendToGranular(elementRules, e, "Architectural Boundary Constraints", "- **Layer**: " + arch.belongsTo() + (arch.cannotReference().length > 0 ? "\n- **Prohibited References**: " + cannotRefStr : ""));
            }
        }
        for (TaggedElement e : model.publicApi()) {
            AIPublicAPI api = e.annotation(AIPublicAPI.class);
            appendToGranular(elementRules, e, "Public API Surface Protection", "- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility."
                + reason(api == null ? "" : api.reason()));
        }
        for (TaggedElement e : model.strictExceptions()) {
            AIStrictExceptions exceptions = e.annotation(AIStrictExceptions.class);
            appendToGranular(elementRules, e, "Strict Exception Handling", "- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions."
                + reason(exceptions == null ? "" : exceptions.reason()));
        }
        for (TaggedElement e : model.strictTypes()) {
            AIStrictTypes types = e.annotation(AIStrictTypes.class);
            appendToGranular(elementRules, e, "Strict Type Safety", "- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety."
                + reason(types == null ? "" : types.reason()));
        }
        for (TaggedElement e : model.internationalized()) {
            AIInternationalized i18n = e.annotation(AIInternationalized.class);
            appendToGranular(elementRules, e, "Internationalization Mandate", "- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources."
                + reason(i18n == null ? "" : i18n.reason()));
        }
        for (TaggedElement e : model.strictClasspath()) {
            AIStrictClasspath classpath = e.annotation(AIStrictClasspath.class);
            appendToGranular(elementRules, e, "Strict Classpath Integrity", "- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code."
                + reason(classpath == null ? "" : classpath.reason()));
        }
        for (TaggedElement e : model.schemaSafe()) {
            AISchemaSafe schema = e.annotation(AISchemaSafe.class);
            appendToGranular(elementRules, e, "Schema & Serialization Safety", "- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths."
                + reason(schema == null ? "" : schema.reason()));
        }
        for (TaggedElement e : model.idempotent()) {
            AIIdempotent idempotent = e.annotation(AIIdempotent.class);
            if (idempotent != null) {
                appendToGranular(elementRules, e, "Idempotency Guarantee", "- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once." + (idempotent.reason().isEmpty() ? "" : "\n- **Reason**: " + idempotent.reason()));
            }
        }
        for (TaggedElement e : model.featureFlag()) {
            AIFeatureFlag ff = e.annotation(AIFeatureFlag.class);
            if (ff != null) {
                String flagDisplay = ff.flag().isEmpty() ? "(unspecified)" : "'" + ff.flag() + "'";
                appendToGranular(elementRules, e, "Feature Flag Gate", "- **Flag**: " + flagDisplay + " (default: " + ff.defaultValue() + ")\n- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.");
            }
        }
        for (TaggedElement e : model.secure()) {
            AISecure secure = e.annotation(AISecure.class);
            if (secure != null) {
                appendToGranular(elementRules, e, "Security-Critical Code", "- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact." + (secure.aspect().isEmpty() ? "" : "\n- **Aspect**: " + secure.aspect()));
            }
        }

        // New annotations granular rules
        for (TaggedElement e : model.callersOnly()) {
            AICallersOnly callersOnly = e.annotation(AICallersOnly.class);
            if (callersOnly != null) {
                appendToGranular(elementRules, e, "Access Restrictions", "- **Allowed Callers**: [" + String.join(", ", callersOnly.value()) + "]");
            }
        }
        for (TaggedElement e : model.sandboxOnly()) {
            AISandboxOnly sandbox = e.annotation(AISandboxOnly.class);
            appendToGranular(elementRules, e, "Sandbox Restriction", "- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code."
                + reason(sandbox == null ? "" : sandbox.reason()));
        }
        for (TaggedElement e : model.memoryBudget()) {
            AIMemoryBudget mb = e.annotation(AIMemoryBudget.class);
            if (mb != null) {
                appendToGranular(elementRules, e, "Memory Budget Constraints", "- **Policy**: " + mb.value().name() + "\n- **Rule**: Strictly limit or prevent object allocations.");
            }
        }
        for (TaggedElement e : model.pure()) {
            AIPure pure = e.annotation(AIPure.class);
            appendToGranular(elementRules, e, "Mathematical Purity", "- **Rule**: Must remain a pure function. Forbid state modifications and side effects."
                + reason(pure == null ? "" : pure.reason()));
        }
        for (TaggedElement e : model.domainModel()) {
            AIDomainModel dm = e.annotation(AIDomainModel.class);
            if (dm != null) {
                String allowedStr = String.join(", ", dm.allow());
                appendToGranular(elementRules, e, "Domain Model Boundary", "- **Purity**: Framework-free DDD Entity." + (dm.allow().length > 0 ? "\n- **Allowed Imports**: " + allowedStr : ""));
            }
        }
        for (TaggedElement e : model.extensible()) {
            AIExtensible extensible = e.annotation(AIExtensible.class);
            if (extensible != null) {
                appendToGranular(elementRules, e, "Polymorphic Extension Pattern", "- **Pattern**: " + extensible.value().name() + "\n- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.");
            }
        }
        for (TaggedElement e : model.inputSanitized()) {
            AIInputSanitized is = e.annotation(AIInputSanitized.class);
            if (is != null) {
                String[] types = new String[is.value().length];
                for (int i = 0; i < is.value().length; i++) {
                    types[i] = is.value()[i].name();
                }
                appendToGranular(elementRules, e, "Input Sanitization", "- **Target Filters**: " + String.join(", ", types) + "\n- **Rule**: Run raw input strings through approved sanitizers.");
            }
        }
        for (TaggedElement e : model.secureLogging()) {
            AISecureLogging sl = e.annotation(AISecureLogging.class);
            if (sl != null) {
                appendToGranular(elementRules, e, "Secure Logging Masking", "- **Policy**: " + sl.value().name() + "\n- **Rule**: Never pass this raw variable to log appenders or stdout streams.");
            }
        }
        for (TaggedElement e : model.explain()) {
            AIExplain explain = e.annotation(AIExplain.class);
            if (explain != null) {
                appendToGranular(elementRules, e, "Chain-of-Thought Explanation", "- **Complexity Level**: " + explain.value().name() + "\n- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.");
            }
        }
        for (TaggedElement e : model.prototype()) {
            AIPrototype prototype = e.annotation(AIPrototype.class);
            appendToGranular(elementRules, e, "Experimental Prototype", "- **Scope**: Rapid prototype. QA rules and strict coverage metrics are temporarily suspended."
                + reason(prototype == null ? "" : prototype.reason()));
        }
        for (TaggedElement e : model.sunset()) {
            AISunset sunset = e.annotation(AISunset.class);
            if (sunset != null) {
                // replacement() is Class-valued and unreadable here; the collector resolved it to a
                // type name while the compiler was still in scope.
                String repName = e.typeMember("AISunset.replacement", "java.lang.Object");
                appendToGranular(elementRules, e, "Sunset Element", "- **Status**: Strict Deprecation (No new references)\n- **JIRA Ticket**: " + sunset.jira() + "\n- **Replacement**: " + repName);
            }
        }
        for (TaggedElement e : model.temporary()) {
            AITemporary temp = e.annotation(AITemporary.class);
            if (temp != null) {
                appendToGranular(elementRules, e, "Temporary Workaround", "- **Expiration**: " + temp.expiresOn() + "\n- **Reason**: " + temp.reason() + "\n- **Rule**: Hotfix or stub that must be removed before expiration.");
            }
        }

        // v1.0.0 evidence-based wave
        for (TaggedElement e : model.generated()) {
            AIGenerated generated = e.annotation(AIGenerated.class);
            if (generated != null) {
                String target = generated.editInstead().isEmpty() ? generated.from() : generated.editInstead();
                appendToGranular(elementRules, e, "Generated — Edit The Source",
                    "- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.\n- **Generated from**: "
                        + generated.from() + "\n- **Edit instead**: " + target
                        + (generated.regenerateWith().isEmpty() ? "" : "\n- **Regenerate with**: " + generated.regenerateWith()));
            }
        }
        for (TaggedElement e : model.loadBearing()) {
            AILoadBearing lb = e.annotation(AILoadBearing.class);
            if (lb != null) {
                appendToGranular(elementRules, e, "Load-Bearing Oddity",
                    "- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.\n- **Invariant**: "
                        + lb.invariant()
                        + (lb.breaksIf().isEmpty() ? "" : "\n- **Breaks if changed**: " + lb.breaksIf())
                        + (lb.suppressAudit() ? "\n- **Audit**: Not a defect — do not flag." : ""));
            }
        }
        for (TaggedElement e : model.bannedApi()) {
            AIBannedApi banned = e.annotation(AIBannedApi.class);
            if (banned != null) {
                appendToGranular(elementRules, e, "Banned APIs",
                    "- **Rule**: The following compile here but are prohibited at this element.\n- **Forbidden**: "
                        + String.join(", ", banned.forbidden())
                        + (banned.useInstead().isEmpty() ? "" : "\n- **Use instead**: " + banned.useInstead())
                        + (banned.reason().isEmpty() ? "" : "\n- **Reason**: " + banned.reason()));
            }
        }
        for (TaggedElement e : model.threadAffinity()) {
            AIThreadAffinity ta = e.annotation(AIThreadAffinity.class);
            if (ta != null) {
                appendToGranular(elementRules, e, "Thread Affinity",
                    "- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to \"fix\" it; marshal the call instead.\n- **Affinity**: "
                        + ta.value().name() + (ta.thread().isEmpty() ? "" : " (" + ta.thread() + ")")
                        + (ta.marshalVia().isEmpty() ? "" : "\n- **Marshal via**: " + ta.marshalVia())
                        + (ta.symptomIfViolated().isEmpty() ? "" : "\n- **Symptom if violated**: " + ta.symptomIfViolated()));
            }
        }
        for (TaggedElement e : model.keepInSync()) {
            AIKeepInSync kis = e.annotation(AIKeepInSync.class);
            if (kis != null) {
                appendToGranular(elementRules, e, "Mirrored — Keep In Sync",
                    "- **Rule**: Free to change, but every mirror must change in the same commit.\n- **Mirrors**: "
                        + String.join(", ", kis.mirrors())
                        + (kis.reason().isEmpty() ? "" : "\n- **Reason**: " + kis.reason())
                        + "\n- **Enforced by**: "
                        + (kis.enforcedBy().isEmpty() ? "nothing — a partial edit desyncs silently" : kis.enforcedBy()));
            }
        }

        return elementRules;
    }

    /**
     * The {@code - **Reason**:} line for an annotation's {@code reason()} member, or nothing at all
     * when the author left it out.
     *
     * <p>Nineteen annotations declare a {@code reason()}, and until issue #506 twelve of them had
     * it discarded here. The stanza that rendered instead was the annotation's constant
     * boilerplate, byte-identical for every use of that annotation in every project, while the one
     * project-specific sentence the author wrote reached nobody. Nothing failed, because
     * {@code reason} is optional and the rule file was still written, which is what made the loss
     * silent: the annotation reads as though it carries a reason, and the file it generates does
     * not.
     *
     * <p>The line is emitted only when it carries something. A bare annotation must render as
     * though the member does not exist, not as though its value went missing, which is the rule
     * {@code CommonFormatterHelper.bullet} already applies in the aggregate renderers.
     */
    private static String reason(String value) {
        return (value == null || value.isBlank()) ? "" : "\n- **Reason**: " + value;
    }

    /**
     * As {@link #reason(String)}, but silent while the value is still the annotation's declared
     * default. {@code @AIIgnore}'s default says "Excluded from AI context.", which restates the
     * rule line directly above it; echoing it costs an agent's context window and carries no
     * guardrail in return, so only a reason somebody actually wrote is rendered.
     * {@code AIIgnoreFormatter} draws the same line for the aggregate files.
     */
    private static String reason(String value, String declaredDefault) {
        return declaredDefault.equals(value) ? "" : reason(value);
    }

    private static String ignoreDefaultReason() {
        try {
            Object declared = AIIgnore.class.getDeclaredMethod("reason").getDefaultValue();
            return declared instanceof String value ? value : "";
        } catch (NoSuchMethodException e) {
            return "";
        }
    }

    /**
     * Records one stanza for {@code element} under {@code title}. The stanza is kept structured
     * (see {@link GranularBody}) rather than appended as text, so the file-level renderer can hoist
     * the constant rule sentence shared by every element in a section instead of repeating it.
     */
    private void appendToGranular(Map<TaggedElement, GranularBody> elementRules, TaggedElement element, String title, String content) {
        TaggedElement owner = element.owner();
        elementRules.computeIfAbsent(owner, k -> new GranularBody())
            .add(new GranularBody.Entry(owner, element, title, List.of(content.split("\n", -1))));
    }
}
