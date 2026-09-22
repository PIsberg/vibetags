package se.deversity.vibetags.processor.internal.content;

// CPD-OFF: forty-four entries of one shape are the point of a table, not duplication to factor out.

import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.List;

/**
 * The one table that says, per {@code @AI...} annotation, how every table-driven consumer treats
 * it: the tag and the members {@code BuildFingerprint} hashes, the formatter the flat renderers
 * call, and the stanza {@code GranularRenderer} writes into the element's rule file.
 *
 * <p>Those used to be forty-four hand-written arms in each of five classes, kept in step by
 * nothing except the guard tests that noticed a missing arm afterwards
 * (<a href="https://github.com/PIsberg/vibetags/issues/765">issue #765</a>). An annotation is now
 * one entry here, and a consumer that walks {@link #ALL} cannot forget it.
 *
 * <p><strong>Append only, and the order is load-bearing twice over.</strong> It is the order the
 * sections appear in {@code CONVENTIONS.md}, the Open Interpreter profile, the PR-reviewer
 * instruction block and every granular rule file, so moving an entry rewrites generated files in
 * every consuming build. It is also the order {@code BuildFingerprint} hashes in, so moving an
 * entry, or changing a tag or what an extractor returns, changes every consumer's cached
 * fingerprint with nothing failing to name the cause. {@code AnnotationDescriptorsTest} pins the
 * tag sequence and {@code BuildFingerprintPinnedValueTest} pins the resulting hash.
 *
 * <p>This is deliberately not {@code GuardrailAnnotations.ALL} and must not be derived from it or
 * aligned with it. That list fixes the order buckets are populated in; this one fixes what is
 * hashed and printed. They hold the same annotations, which {@code AnnotationDescriptorsTest}
 * checks, and they are free to order them differently.
 *
 * <p>The table lives in the rendering layer rather than in {@code model}, where the issue first
 * placed it, because an entry names its {@link AnnotationFormatter} and {@code model} may not
 * depend on anything under {@code internal}. From here {@code BuildFingerprint} can still read it:
 * {@code internal} may depend on {@code content}, never the reverse. The extractors belong beside
 * the renderers in any case. Invariant 12 says anything that becomes generated content reaches the
 * fingerprint, and the members a stanza prints and the members the hash reads are now a few lines
 * apart instead of two packages apart.
 *
 * <p>The extractors stay explicit per-annotation lambdas. A reflective walk over the members would
 * change the hashed string for every consumer and cannot read {@code AISunset.replacement()},
 * which is {@code Class}-valued and only exists as a resolved type member.
 */
public final class AnnotationDescriptors {

    /**
     * {@code AIIgnore.reason()}'s declared default, read once at class-load. If the annotation ever
     * loses the member, every reason is printed, which is noisy rather than wrong.
     */
    private static final String IGNORE_DEFAULT_REASON = ignoreDefaultReason();

    /** Every annotation, in the pinned hashing and printing order. Append only; see the class comment. */
    public static final List<AnnotationDescriptor> ALL = List.of(
        new AnnotationDescriptor(AILocked.class, "L", FormatterRegistry.locked(),
            e -> {
                AILocked a = e.annotation(AILocked.class);
                return a == null ? "" : a.reason();
            },
            "Locked Status",
            e -> {
                AILocked locked = e.annotation(AILocked.class);
                if (locked == null) {
                    return null;
                }
                return "- **Reason**: " + locked.reason();
            }),
        new AnnotationDescriptor(AIContext.class, "C", FormatterRegistry.context(),
            e -> {
                AIContext a = e.annotation(AIContext.class);
                return a == null ? "" : a.focus() + "|" + a.avoids();
            },
            "Context & Focus",
            e -> {
                AIContext context = e.annotation(AIContext.class);
                if (context == null) {
                    return null;
                }
                return "- **Focus**: " + context.focus() + "\n- **Avoid**: " + context.avoids();
            }),
        new AnnotationDescriptor(AIIgnore.class, "I", FormatterRegistry.ignore(),
            e -> {
                AIIgnore a = e.annotation(AIIgnore.class);
                return a == null ? "" : a.reason();
            },
            "Exclusion Rule",
            e -> {
                AIIgnore ignore = e.annotation(AIIgnore.class);
                return "This element is strictly excluded from AI context. Do not reference it."
                    + reason(ignore == null ? "" : ignore.reason(), IGNORE_DEFAULT_REASON);
            }),
        new AnnotationDescriptor(AIAudit.class, "A", FormatterRegistry.audit(),
            e -> {
                AIAudit a = e.annotation(AIAudit.class);
                if (a == null) return "";
                String[] checkFor = a.checkFor();
                return String.join(",", checkFor);
            },
            "Security Audit Requirements",
            e -> {
                AIAudit audit = e.annotation(AIAudit.class);
                if (audit == null || audit.checkFor().length == 0) {
                    return null;
                }
                return "When modifying this element, audit for:\n- " + String.join("\n- ", audit.checkFor());
            }),
        new AnnotationDescriptor(AIDraft.class, "D", FormatterRegistry.draft(),
            e -> {
                AIDraft a = e.annotation(AIDraft.class);
                return a == null ? "" : a.instructions();
            },
            "Implementation Tasks",
            e -> {
                AIDraft draft = e.annotation(AIDraft.class);
                if (draft == null) {
                    return null;
                }
                return "- **Instruction**: " + draft.instructions();
            }),
        new AnnotationDescriptor(AIPrivacy.class, "P", FormatterRegistry.privacy(),
            e -> {
                AIPrivacy a = e.annotation(AIPrivacy.class);
                return a == null ? "" : a.reason();
            },
            "PII / Privacy Guardrails",
            e -> {
                AIPrivacy privacy = e.annotation(AIPrivacy.class);
                if (privacy == null) {
                    return null;
                }
                return "- **Rule**: Never log or expose runtime values of this element.\n- **Reason**: " + privacy.reason();
            }),
        new AnnotationDescriptor(AICore.class, "K", FormatterRegistry.core(),
            e -> {
                AICore a = e.annotation(AICore.class);
                return a == null ? "" : a.sensitivity() + "|" + a.note();
            },
            "Core Functionality",
            e -> {
                AICore core = e.annotation(AICore.class);
                if (core == null) {
                    return null;
                }
                return "- **Sensitivity**: " + core.sensitivity() + "\n- **Note**: " + core.note();
            }),
        new AnnotationDescriptor(AIPerformance.class, "F", FormatterRegistry.performance(),
            e -> {
                AIPerformance a = e.annotation(AIPerformance.class);
                return a == null ? "" : a.constraint();
            },
            "Performance Constraints",
            e -> {
                AIPerformance perf = e.annotation(AIPerformance.class);
                if (perf == null) {
                    return null;
                }
                return "- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: " + perf.constraint();
            }),
        new AnnotationDescriptor(AIContract.class, "T", FormatterRegistry.contract(),
            e -> {
                AIContract a = e.annotation(AIContract.class);
                return a == null ? "" : a.reason();
            },
            "Contract-Frozen Signature",
            e -> {
                AIContract contract = e.annotation(AIContract.class);
                if (contract == null) {
                    return null;
                }
                return "- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.\n- **Reason**: " + contract.reason();
            }),
        new AnnotationDescriptor(AITestDriven.class, "TD", FormatterRegistry.testDriven(),
            e -> {
                AITestDriven a = e.annotation(AITestDriven.class);
                if (a == null) return "";
                StringBuilder attrs = new StringBuilder();
                attrs.append(a.coverageGoal()).append('|')
                    .append(a.testLocation()).append('|');
                for (AITestDriven.Framework f : a.framework()) attrs.append(f.name()).append(',');
                attrs.append('|').append(a.mockPolicy());
                return attrs.toString();
            },
            "Test-Driven Requirements",
            e -> {
                AITestDriven td = e.annotation(AITestDriven.class);
                if (td == null) {
                    return null;
                }
                StringBuilder frameworks = new StringBuilder();
                for (AITestDriven.Framework f : td.framework()) {
                    if (frameworks.length() > 0) frameworks.append(", ");
                    frameworks.append(f.name());
                }
                String frameworksStr = frameworks.toString();
                String locationHint = td.testLocation().isEmpty() ? "" : "\n- **Test Location**: " + td.testLocation();
                String mockHint = td.mockPolicy().isEmpty() ? "" : "\n- **Mock Policy**: " + td.mockPolicy();
                return "- **Rule**: Changes MUST be accompanied by a matching test update.\n- **Coverage Goal**: " + td.coverageGoal() + "%\n- **Frameworks**: " + frameworksStr + locationHint + mockHint;
            }),
        new AnnotationDescriptor(AIThreadSafe.class, "TS", FormatterRegistry.threadSafe(),
            e -> {
                AIThreadSafe a = e.annotation(AIThreadSafe.class);
                return a == null ? "" : a.strategy().name() + "|" + a.note();
            },
            "Thread-Safety Guarantee",
            e -> {
                AIThreadSafe ts = e.annotation(AIThreadSafe.class);
                if (ts == null) {
                    return null;
                }
                return "- **Strategy**: " + ts.strategy().name() + (ts.note().isEmpty() ? "" : "\n- **Note**: " + ts.note());
            }),
        new AnnotationDescriptor(AIImmutable.class, "IM", FormatterRegistry.immutable(),
            e -> {
                AIImmutable a = e.annotation(AIImmutable.class);
                return a == null ? "" : a.note();
            },
            "Immutable Type",
            e -> {
                AIImmutable im = e.annotation(AIImmutable.class);
                if (im == null) {
                    return null;
                }
                return "- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods." + (im.note().isEmpty() ? "" : "\n- **Note**: " + im.note());
            }),
        new AnnotationDescriptor(AIDeprecated.class, "DP", FormatterRegistry.deprecated(),
            e -> {
                AIDeprecated a = e.annotation(AIDeprecated.class);
                return a == null ? "" : a.replacedBy() + "|" + a.migrationGuide() + "|" + a.deadline();
            },
            "Deprecated — Migrate Callers",
            e -> {
                AIDeprecated dep = e.annotation(AIDeprecated.class);
                if (dep == null) {
                    return null;
                }
                return (dep.replacedBy().isEmpty() ? "" : "- **Replaced by**: " + dep.replacedBy() + "\n") + "- **Migration**: " + dep.migrationGuide() + (dep.deadline().isEmpty() ? "" : "\n- **Deadline**: " + dep.deadline());
            }),
        new AnnotationDescriptor(AIObservability.class, "OB", FormatterRegistry.observability(),
            e -> {
                AIObservability a = e.annotation(AIObservability.class);
                if (a == null) return "";
                return String.join(",", a.metrics()) + "|"
                     + String.join(",", a.traces()) + "|"
                     + String.join(",", a.logs()) + "|"
                     + a.note();
            },
            "Observability Instrumentation",
            e -> {
                AIObservability obs = e.annotation(AIObservability.class);
                if (obs == null) {
                    return null;
                }
                StringBuilder summary = new StringBuilder();
                if (obs.metrics().length > 0) summary.append("Metrics: ").append(String.join(", ", obs.metrics())).append(". ");
                if (obs.traces().length > 0)  summary.append("Traces: ").append(String.join(", ", obs.traces())).append(". ");
                if (obs.logs().length > 0)    summary.append("Logs: ").append(String.join(", ", obs.logs())).append(". ");
                if (!obs.note().isEmpty())    summary.append("Note: ").append(obs.note());
                return "- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.\n- **Details**: " + summary.toString().stripTrailing();
            }),
        new AnnotationDescriptor(AIRegulation.class, "RG", FormatterRegistry.regulation(),
            e -> {
                AIRegulation a = e.annotation(AIRegulation.class);
                return a == null ? "" : a.standard() + "|" + a.clause() + "|" + a.description();
            },
            "Regulatory Compliance",
            e -> {
                AIRegulation reg = e.annotation(AIRegulation.class);
                if (reg == null) {
                    return null;
                }
                return "- **Standard**: " + reg.standard() + (reg.clause().isEmpty() ? "" : "\n- **Clause**: " + reg.clause()) + "\n- **Description**: " + reg.description();
            }),
        new AnnotationDescriptor(AIParallelTests.class, "PT", FormatterRegistry.parallelTests(),
            e -> {
                AIParallelTests a = e.annotation(AIParallelTests.class);
                return a == null ? "" : a.reason();
            },
            "Strict Test Isolation",
            e -> {
                AIParallelTests parallel = e.annotation(AIParallelTests.class);
                return "- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources."
                    + reason(parallel == null ? "" : parallel.reason());
            }),
        new AnnotationDescriptor(AILegacyBridge.class, "LB", FormatterRegistry.legacyBridge(),
            e -> {
                AILegacyBridge a = e.annotation(AILegacyBridge.class);
                return a == null ? "" : a.reason();
            },
            "Legacy Compatibility Bridge",
            e -> {
                AILegacyBridge bridge = e.annotation(AILegacyBridge.class);
                return "- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested."
                    + reason(bridge == null ? "" : bridge.reason());
            }),
        new AnnotationDescriptor(AIArchitecture.class, "AR", FormatterRegistry.architecture(),
            e -> {
                AIArchitecture a = e.annotation(AIArchitecture.class);
                if (a == null) return "";
                return a.belongsTo() + "|" + String.join(",", a.cannotReference());
            },
            "Architectural Boundary Constraints",
            e -> {
                AIArchitecture arch = e.annotation(AIArchitecture.class);
                if (arch == null) {
                    return null;
                }
                String cannotRefStr = String.join(", ", arch.cannotReference());
                return "- **Layer**: " + arch.belongsTo() + (arch.cannotReference().length > 0 ? "\n- **Prohibited References**: " + cannotRefStr : "");
            }),
        new AnnotationDescriptor(AIPublicAPI.class, "PA", FormatterRegistry.publicApi(),
            e -> {
                AIPublicAPI a = e.annotation(AIPublicAPI.class);
                return a == null ? "" : a.reason();
            },
            "Public API Surface Protection",
            e -> {
                AIPublicAPI api = e.annotation(AIPublicAPI.class);
                return "- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility."
                    + reason(api == null ? "" : api.reason());
            }),
        new AnnotationDescriptor(AIStrictExceptions.class, "SE", FormatterRegistry.strictExceptions(),
            e -> {
                AIStrictExceptions a = e.annotation(AIStrictExceptions.class);
                return a == null ? "" : a.reason();
            },
            "Strict Exception Handling",
            e -> {
                AIStrictExceptions exceptions = e.annotation(AIStrictExceptions.class);
                return "- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions."
                    + reason(exceptions == null ? "" : exceptions.reason());
            }),
        new AnnotationDescriptor(AIStrictTypes.class, "ST", FormatterRegistry.strictTypes(),
            e -> {
                AIStrictTypes a = e.annotation(AIStrictTypes.class);
                return a == null ? "" : a.reason();
            },
            "Strict Type Safety",
            e -> {
                AIStrictTypes types = e.annotation(AIStrictTypes.class);
                return "- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety."
                    + reason(types == null ? "" : types.reason());
            }),
        new AnnotationDescriptor(AIInternationalized.class, "IT", FormatterRegistry.internationalized(),
            e -> {
                AIInternationalized a = e.annotation(AIInternationalized.class);
                return a == null ? "" : a.reason();
            },
            "Internationalization Mandate",
            e -> {
                AIInternationalized i18n = e.annotation(AIInternationalized.class);
                return "- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources."
                    + reason(i18n == null ? "" : i18n.reason());
            }),
        new AnnotationDescriptor(AIStrictClasspath.class, "SC", FormatterRegistry.strictClasspath(),
            e -> {
                AIStrictClasspath a = e.annotation(AIStrictClasspath.class);
                return a == null ? "" : a.reason();
            },
            "Strict Classpath Integrity",
            e -> {
                AIStrictClasspath classpath = e.annotation(AIStrictClasspath.class);
                return "- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code."
                    + reason(classpath == null ? "" : classpath.reason());
            }),
        new AnnotationDescriptor(AISchemaSafe.class, "SS", FormatterRegistry.schemaSafe(),
            e -> {
                AISchemaSafe a = e.annotation(AISchemaSafe.class);
                return a == null ? "" : a.reason();
            },
            "Schema & Serialization Safety",
            e -> {
                AISchemaSafe schema = e.annotation(AISchemaSafe.class);
                return "- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths."
                    + reason(schema == null ? "" : schema.reason());
            }),
        new AnnotationDescriptor(AIIdempotent.class, "ID", FormatterRegistry.idempotent(),
            e -> {
                AIIdempotent a = e.annotation(AIIdempotent.class);
                return a == null ? "" : a.reason();
            },
            "Idempotency Guarantee",
            e -> {
                AIIdempotent idempotent = e.annotation(AIIdempotent.class);
                if (idempotent == null) {
                    return null;
                }
                return "- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once." + (idempotent.reason().isEmpty() ? "" : "\n- **Reason**: " + idempotent.reason());
            }),
        new AnnotationDescriptor(AIFeatureFlag.class, "FF", FormatterRegistry.featureFlag(),
            e -> {
                AIFeatureFlag a = e.annotation(AIFeatureFlag.class);
                return a == null ? "" : a.flag() + "|" + a.defaultValue();
            },
            "Feature Flag Gate",
            e -> {
                AIFeatureFlag ff = e.annotation(AIFeatureFlag.class);
                if (ff == null) {
                    return null;
                }
                String flagDisplay = ff.flag().isEmpty() ? "(unspecified)" : "'" + ff.flag() + "'";
                return "- **Flag**: " + flagDisplay + " (default: " + ff.defaultValue() + ")\n- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.";
            }),
        new AnnotationDescriptor(AISecure.class, "SEC", FormatterRegistry.secure(),
            e -> {
                AISecure a = e.annotation(AISecure.class);
                return a == null ? "" : a.aspect();
            },
            "Security-Critical Code",
            e -> {
                AISecure secure = e.annotation(AISecure.class);
                if (secure == null) {
                    return null;
                }
                return "- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact." + (secure.aspect().isEmpty() ? "" : "\n- **Aspect**: " + secure.aspect());
            }),
        new AnnotationDescriptor(AICallersOnly.class, "CO", FormatterRegistry.callersOnly(),
            e -> {
                AICallersOnly a = e.annotation(AICallersOnly.class);
                return a == null ? "" : String.join(",", a.value());
            },
            "Access Restrictions",
            e -> {
                AICallersOnly callersOnly = e.annotation(AICallersOnly.class);
                if (callersOnly == null) {
                    return null;
                }
                return "- **Allowed Callers**: [" + String.join(", ", callersOnly.value()) + "]";
            }),
        new AnnotationDescriptor(AISandboxOnly.class, "SO", FormatterRegistry.sandboxOnly(),
            e -> {
                AISandboxOnly a = e.annotation(AISandboxOnly.class);
                return a == null ? "" : a.reason();
            },
            "Sandbox Restriction",
            e -> {
                AISandboxOnly sandbox = e.annotation(AISandboxOnly.class);
                return "- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code."
                    + reason(sandbox == null ? "" : sandbox.reason());
            }),
        new AnnotationDescriptor(AIMemoryBudget.class, "MB", FormatterRegistry.memoryBudget(),
            e -> {
                AIMemoryBudget a = e.annotation(AIMemoryBudget.class);
                return a == null ? "" : a.value().name();
            },
            "Memory Budget Constraints",
            e -> {
                AIMemoryBudget mb = e.annotation(AIMemoryBudget.class);
                if (mb == null) {
                    return null;
                }
                return "- **Policy**: " + mb.value().name() + "\n- **Rule**: Strictly limit or prevent object allocations.";
            }),
        new AnnotationDescriptor(AIPure.class, "PU", FormatterRegistry.pure(),
            e -> {
                AIPure a = e.annotation(AIPure.class);
                return a == null ? "" : a.reason();
            },
            "Mathematical Purity",
            e -> {
                AIPure pure = e.annotation(AIPure.class);
                return "- **Rule**: Must remain a pure function. Forbid state modifications and side effects."
                    + reason(pure == null ? "" : pure.reason());
            }),
        new AnnotationDescriptor(AIDomainModel.class, "DM", FormatterRegistry.domainModel(),
            e -> {
                AIDomainModel a = e.annotation(AIDomainModel.class);
                return a == null ? "" : String.join(",", a.allow());
            },
            "Domain Model Boundary",
            e -> {
                AIDomainModel dm = e.annotation(AIDomainModel.class);
                if (dm == null) {
                    return null;
                }
                String allowedStr = String.join(", ", dm.allow());
                return "- **Purity**: Framework-free DDD Entity." + (dm.allow().length > 0 ? "\n- **Allowed Imports**: " + allowedStr : "");
            }),
        new AnnotationDescriptor(AIExtensible.class, "EX", FormatterRegistry.extensible(),
            e -> {
                AIExtensible a = e.annotation(AIExtensible.class);
                return a == null ? "" : a.value().name();
            },
            "Polymorphic Extension Pattern",
            e -> {
                AIExtensible extensible = e.annotation(AIExtensible.class);
                if (extensible == null) {
                    return null;
                }
                return "- **Pattern**: " + extensible.value().name() + "\n- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.";
            }),
        new AnnotationDescriptor(AIInputSanitized.class, "IZ", FormatterRegistry.inputSanitized(),
            e -> {
                AIInputSanitized a = e.annotation(AIInputSanitized.class);
                if (a == null) return "";
                StringBuilder types = new StringBuilder();
                for (AIInputSanitized.SanitizerType t : a.value()) types.append(t.name()).append(',');
                return types.toString();
            },
            "Input Sanitization",
            e -> {
                AIInputSanitized is = e.annotation(AIInputSanitized.class);
                if (is == null) {
                    return null;
                }
                String[] types = new String[is.value().length];
                for (int i = 0; i < is.value().length; i++) {
                    types[i] = is.value()[i].name();
                }
                return "- **Target Filters**: " + String.join(", ", types) + "\n- **Rule**: Run raw input strings through approved sanitizers.";
            }),
        new AnnotationDescriptor(AISecureLogging.class, "SL", FormatterRegistry.secureLogging(),
            e -> {
                AISecureLogging a = e.annotation(AISecureLogging.class);
                return a == null ? "" : a.value().name();
            },
            "Secure Logging Masking",
            e -> {
                AISecureLogging sl = e.annotation(AISecureLogging.class);
                if (sl == null) {
                    return null;
                }
                return "- **Policy**: " + sl.value().name() + "\n- **Rule**: Never pass this raw variable to log appenders or stdout streams.";
            }),
        new AnnotationDescriptor(AIExplain.class, "XP", FormatterRegistry.explain(),
            e -> {
                AIExplain a = e.annotation(AIExplain.class);
                return a == null ? "" : a.value().name();
            },
            "Chain-of-Thought Explanation",
            e -> {
                AIExplain explain = e.annotation(AIExplain.class);
                if (explain == null) {
                    return null;
                }
                return "- **Complexity Level**: " + explain.value().name() + "\n- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.";
            }),
        new AnnotationDescriptor(AIPrototype.class, "PR", FormatterRegistry.prototype(),
            e -> {
                AIPrototype a = e.annotation(AIPrototype.class);
                return a == null ? "" : a.reason();
            },
            "Experimental Prototype",
            e -> {
                AIPrototype prototype = e.annotation(AIPrototype.class);
                return "- **Scope**: Rapid prototype. QA rules and strict coverage metrics are temporarily suspended."
                    + reason(prototype == null ? "" : prototype.reason());
            }),
        new AnnotationDescriptor(AISunset.class, "SN", FormatterRegistry.sunset(),
            e -> {
                AISunset a = e.annotation(AISunset.class);
                if (a == null) return "";
                // replacement() is Class-valued, so it is unreadable here — the collector resolved it
                // to a type name while the compiler was still in scope.
                return a.jira() + "|" + e.typeMember("AISunset.replacement", "");
            },
            "Sunset Element",
            e -> {
                AISunset sunset = e.annotation(AISunset.class);
                if (sunset == null) {
                    return null;
                }
                // replacement() is Class-valued and unreadable here; the collector resolved it to a
                // type name while the compiler was still in scope.
                String repName = e.typeMember("AISunset.replacement", "java.lang.Object");
                return "- **Status**: Strict Deprecation (No new references)\n- **JIRA Ticket**: " + sunset.jira() + "\n- **Replacement**: " + repName;
            }),
        new AnnotationDescriptor(AITemporary.class, "TM", FormatterRegistry.temporary(),
            e -> {
                AITemporary a = e.annotation(AITemporary.class);
                return a == null ? "" : a.expiresOn() + "|" + a.reason();
            },
            "Temporary Workaround",
            e -> {
                AITemporary temp = e.annotation(AITemporary.class);
                if (temp == null) {
                    return null;
                }
                return "- **Expiration**: " + temp.expiresOn() + "\n- **Reason**: " + temp.reason() + "\n- **Rule**: Hotfix or stub that must be removed before expiration.";
            }),
        new AnnotationDescriptor(AIGenerated.class, "GEN", FormatterRegistry.generated(),
            e -> {
                AIGenerated a = e.annotation(AIGenerated.class);
                return a == null ? "" : a.from() + "|" + a.regenerateWith() + "|" + a.editInstead();
            },
            "Generated — Edit The Source",
            e -> {
                AIGenerated generated = e.annotation(AIGenerated.class);
                if (generated == null) {
                    return null;
                }
                String target = generated.editInstead().isEmpty() ? generated.from() : generated.editInstead();
                return "- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.\n- **Generated from**: "
                        + generated.from() + "\n- **Edit instead**: " + target
                        + (generated.regenerateWith().isEmpty() ? "" : "\n- **Regenerate with**: " + generated.regenerateWith());
            }),
        // "LDB", not "LB": that tag is legacyBridge's, and tags are unique per annotation (#765).
        new AnnotationDescriptor(AILoadBearing.class, "LDB", FormatterRegistry.loadBearing(),
            e -> {
                AILoadBearing a = e.annotation(AILoadBearing.class);
                return a == null ? "" : a.invariant() + "|" + a.breaksIf() + "|" + a.suppressAudit();
            },
            "Load-Bearing Oddity",
            e -> {
                AILoadBearing lb = e.annotation(AILoadBearing.class);
                if (lb == null) {
                    return null;
                }
                return "- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.\n- **Invariant**: "
                        + lb.invariant()
                        + (lb.breaksIf().isEmpty() ? "" : "\n- **Breaks if changed**: " + lb.breaksIf())
                        + (lb.suppressAudit() ? "\n- **Audit**: Not a defect — do not flag." : "");
            }),
        new AnnotationDescriptor(AIBannedApi.class, "BA", FormatterRegistry.bannedApi(),
            e -> {
                AIBannedApi a = e.annotation(AIBannedApi.class);
                return a == null ? "" : String.join(",", a.forbidden()) + "|" + a.useInstead() + "|" + a.reason();
            },
            "Banned APIs",
            e -> {
                AIBannedApi banned = e.annotation(AIBannedApi.class);
                if (banned == null) {
                    return null;
                }
                return "- **Rule**: The following compile here but are prohibited at this element.\n- **Forbidden**: "
                        + String.join(", ", banned.forbidden())
                        + (banned.useInstead().isEmpty() ? "" : "\n- **Use instead**: " + banned.useInstead())
                        + (banned.reason().isEmpty() ? "" : "\n- **Reason**: " + banned.reason());
            }),
        new AnnotationDescriptor(AIThreadAffinity.class, "TA", FormatterRegistry.threadAffinity(),
            e -> {
                AIThreadAffinity a = e.annotation(AIThreadAffinity.class);
                return a == null ? "" : a.value().name() + "|" + a.thread() + "|" + a.marshalVia()
                    + "|" + a.symptomIfViolated();
            },
            "Thread Affinity",
            e -> {
                AIThreadAffinity ta = e.annotation(AIThreadAffinity.class);
                if (ta == null) {
                    return null;
                }
                return "- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to \"fix\" it; marshal the call instead.\n- **Affinity**: "
                        + ta.value().name() + (ta.thread().isEmpty() ? "" : " (" + ta.thread() + ")")
                        + (ta.marshalVia().isEmpty() ? "" : "\n- **Marshal via**: " + ta.marshalVia())
                        + (ta.symptomIfViolated().isEmpty() ? "" : "\n- **Symptom if violated**: " + ta.symptomIfViolated());
            }),
        new AnnotationDescriptor(AIKeepInSync.class, "KIS", FormatterRegistry.keepInSync(),
            e -> {
                AIKeepInSync a = e.annotation(AIKeepInSync.class);
                return a == null ? "" : String.join(",", a.mirrors()) + "|" + a.reason() + "|" + a.enforcedBy();
            },
            "Mirrored — Keep In Sync",
            e -> {
                AIKeepInSync kis = e.annotation(AIKeepInSync.class);
                if (kis == null) {
                    return null;
                }
                return "- **Rule**: Free to change, but every mirror must change in the same commit.\n- **Mirrors**: "
                        + String.join(", ", kis.mirrors())
                        + (kis.reason().isEmpty() ? "" : "\n- **Reason**: " + kis.reason())
                        + "\n- **Enforced by**: "
                        + (kis.enforcedBy().isEmpty() ? "nothing — a partial edit desyncs silently" : kis.enforcedBy());
            })
    );

    private AnnotationDescriptors() {}

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
}
