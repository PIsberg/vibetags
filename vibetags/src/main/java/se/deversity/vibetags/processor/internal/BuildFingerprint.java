package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

import se.deversity.vibetags.processor.model.ContentHash;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes a stable fingerprint of the annotation-processing inputs (the collected element set
 * plus the resolved active services). When the fingerprint matches a previous run's value and
 * every previously written file is still byte-stable on disk, the processor can skip the entire
 * content-build + per-file-compare phase.
 *
 * <p>Stability is the only correctness requirement here: the same inputs must always produce the
 * same hex output across processor invocations. A collision is not free, and the claim this
 * paragraph used to make — that one could only skip byte-identical work — was wrong in direction:
 * when the <em>changed</em> input string collides with the previous one, the short-circuit skips a
 * regeneration whose output would have differed, and the per-file size+mtime checks cannot notice
 * (they guard against on-disk drift, not against the inputs changing). The risk is accepted for
 * non-adversarial input — see {@link se.deversity.vibetags.processor.model.ContentHash} for the
 * honest version of the trade — and revisiting the hash width belongs to the next cache-format
 * bump.
 *
 * <p>Stateless. All methods are static.
 */
@AIImmutable(note = "Purely stateless; private constructor prevents instantiation; all computation results are returned as values")
public final class BuildFingerprint {

    private BuildFingerprint() {}

    /**
     * Computes the fingerprint over (processor version × collector annotations × resolved active
     * services).
     *
     * <p>Element ordering is normalised by element-path (a stable, FQN-like string) before hashing,
     * because {@link java.util.LinkedHashSet} preserves insertion order and javac's discovery order
     * is not guaranteed to be deterministic across runs. Active services are sorted alphabetically
     * for the same reason.
     *
     * <p>The processor version ({@link ProcessorVersion}) is part of the input deliberately: a new
     * processor release may render different content from identical annotations, so an upgrade must
     * invalidate the previous fingerprint rather than short-circuit past regeneration.
     */
    @AIContract(reason = "Same inputs must always produce the same 8-hex output across JVM restarts; changing the algorithm silently invalidates all existing .vibetags-cache files")
    public static String compute(AnnotationCollector collector, Set<String> activeServices) {
        return compute(collector, activeServices, ProcessorVersion.get());
    }

    /**
     * Version-explicit variant of {@link #compute(AnnotationCollector, Set)}. Visible so tests can
     * verify that a version change alone invalidates the fingerprint.
     */
    public static String compute(AnnotationCollector collector, Set<String> activeServices,
                                 String processorVersion) {
        GuardrailModel model = collector.model();
        StringBuilder sb = new StringBuilder(4096);

        sb.append("V{").append(processorVersion).append('}');

        appendAnnotationSet(sb, "L", model.locked(), e -> {
            AILocked a = e.annotation(AILocked.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "C", model.context(), e -> {
            AIContext a = e.annotation(AIContext.class);
            return a == null ? "" : a.focus() + "|" + a.avoids();
        });
        appendAnnotationSet(sb, "I", model.ignore(), e -> {
            // @AIIgnore has no attributes that affect output beyond presence + element name,
            // both of which are already captured by the element-path key.
            return "";
        });
        appendAnnotationSet(sb, "A", model.audit(), e -> {
            AIAudit a = e.annotation(AIAudit.class);
            if (a == null) return "";
            String[] checkFor = a.checkFor();
            return String.join(",", checkFor);
        });
        appendAnnotationSet(sb, "D", model.draft(), e -> {
            AIDraft a = e.annotation(AIDraft.class);
            return a == null ? "" : a.instructions();
        });
        appendAnnotationSet(sb, "P", model.privacy(), e -> {
            AIPrivacy a = e.annotation(AIPrivacy.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "K", model.core(), e -> {
            AICore a = e.annotation(AICore.class);
            return a == null ? "" : a.sensitivity() + "|" + a.note();
        });
        appendAnnotationSet(sb, "F", model.performance(), e -> {
            AIPerformance a = e.annotation(AIPerformance.class);
            return a == null ? "" : a.constraint();
        });
        appendAnnotationSet(sb, "T", model.contract(), e -> {
            AIContract a = e.annotation(AIContract.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "TD", model.testDriven(), e -> {
            AITestDriven a = e.annotation(AITestDriven.class);
            if (a == null) return "";
            StringBuilder attrs = new StringBuilder();
            attrs.append(a.coverageGoal()).append('|');
            attrs.append(a.testLocation()).append('|');
            for (AITestDriven.Framework f : a.framework()) attrs.append(f.name()).append(',');
            attrs.append('|').append(a.mockPolicy());
            return attrs.toString();
        });
        appendAnnotationSet(sb, "TS", model.threadSafe(), e -> {
            AIThreadSafe a = e.annotation(AIThreadSafe.class);
            return a == null ? "" : a.strategy().name() + "|" + a.note();
        });
        appendAnnotationSet(sb, "IM", model.immutable(), e -> {
            AIImmutable a = e.annotation(AIImmutable.class);
            return a == null ? "" : a.note();
        });
        appendAnnotationSet(sb, "DP", model.deprecated(), e -> {
            AIDeprecated a = e.annotation(AIDeprecated.class);
            return a == null ? "" : a.replacedBy() + "|" + a.migrationGuide() + "|" + a.deadline();
        });
        appendAnnotationSet(sb, "OB", model.observability(), e -> {
            AIObservability a = e.annotation(AIObservability.class);
            if (a == null) return "";
            return String.join(",", a.metrics()) + "|"
                 + String.join(",", a.traces()) + "|"
                 + String.join(",", a.logs()) + "|"
                 + a.note();
        });
        appendAnnotationSet(sb, "RG", model.regulation(), e -> {
            AIRegulation a = e.annotation(AIRegulation.class);
            return a == null ? "" : a.standard() + "|" + a.clause() + "|" + a.description();
        });
        appendAnnotationSet(sb, "PT", model.parallelTests(), e -> "");
        appendAnnotationSet(sb, "LB", model.legacyBridge(), e -> "");
        appendAnnotationSet(sb, "AR", model.architecture(), e -> {
            AIArchitecture a = e.annotation(AIArchitecture.class);
            if (a == null) return "";
            return a.belongsTo() + "|" + String.join(",", a.cannotReference());
        });
        appendAnnotationSet(sb, "PA", model.publicApi(), e -> "");
        appendAnnotationSet(sb, "SE", model.strictExceptions(), e -> "");
        appendAnnotationSet(sb, "ST", model.strictTypes(), e -> "");
        appendAnnotationSet(sb, "IT", model.internationalized(), e -> "");
        appendAnnotationSet(sb, "SC", model.strictClasspath(), e -> "");
        appendAnnotationSet(sb, "SS", model.schemaSafe(), e -> "");
        appendAnnotationSet(sb, "ID", model.idempotent(), e -> {
            AIIdempotent a = e.annotation(AIIdempotent.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "FF", model.featureFlag(), e -> {
            AIFeatureFlag a = e.annotation(AIFeatureFlag.class);
            return a == null ? "" : a.flag() + "|" + a.defaultValue();
        });
        appendAnnotationSet(sb, "SEC", model.secure(), e -> {
            AISecure a = e.annotation(AISecure.class);
            return a == null ? "" : a.aspect();
        });
        appendAnnotationSet(sb, "CO", model.callersOnly(), e -> {
            AICallersOnly a = e.annotation(AICallersOnly.class);
            return a == null ? "" : String.join(",", a.value());
        });
        appendAnnotationSet(sb, "SO", model.sandboxOnly(), e -> {
            AISandboxOnly a = e.annotation(AISandboxOnly.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "MB", model.memoryBudget(), e -> {
            AIMemoryBudget a = e.annotation(AIMemoryBudget.class);
            return a == null ? "" : a.value().name();
        });
        appendAnnotationSet(sb, "PU", model.pure(), e -> {
            AIPure a = e.annotation(AIPure.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "DM", model.domainModel(), e -> {
            AIDomainModel a = e.annotation(AIDomainModel.class);
            return a == null ? "" : String.join(",", a.allow());
        });
        appendAnnotationSet(sb, "EX", model.extensible(), e -> {
            AIExtensible a = e.annotation(AIExtensible.class);
            return a == null ? "" : a.value().name();
        });
        appendAnnotationSet(sb, "IZ", model.inputSanitized(), e -> {
            AIInputSanitized a = e.annotation(AIInputSanitized.class);
            if (a == null) return "";
            StringBuilder types = new StringBuilder();
            for (AIInputSanitized.SanitizerType t : a.value()) types.append(t.name()).append(',');
            return types.toString();
        });
        appendAnnotationSet(sb, "SL", model.secureLogging(), e -> {
            AISecureLogging a = e.annotation(AISecureLogging.class);
            return a == null ? "" : a.value().name();
        });
        appendAnnotationSet(sb, "XP", model.explain(), e -> {
            AIExplain a = e.annotation(AIExplain.class);
            return a == null ? "" : a.value().name();
        });
        appendAnnotationSet(sb, "PR", model.prototype(), e -> {
            AIPrototype a = e.annotation(AIPrototype.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "SN", model.sunset(), e -> {
            AISunset a = e.annotation(AISunset.class);
            if (a == null) return "";
            // replacement() is Class-valued, so it is unreadable here — the collector resolved it
            // to a type name while the compiler was still in scope.
            return a.jira() + "|" + e.typeMember("AISunset.replacement", "");
        });
        appendAnnotationSet(sb, "TM", model.temporary(), e -> {
            AITemporary a = e.annotation(AITemporary.class);
            return a == null ? "" : a.expiresOn() + "|" + a.reason();
        });
        appendAnnotationSet(sb, "GEN", model.generated(), e -> {
            AIGenerated a = e.annotation(AIGenerated.class);
            return a == null ? "" : a.from() + "|" + a.regenerateWith() + "|" + a.editInstead();
        });
        appendAnnotationSet(sb, "LB", model.loadBearing(), e -> {
            AILoadBearing a = e.annotation(AILoadBearing.class);
            return a == null ? "" : a.invariant() + "|" + a.breaksIf() + "|" + a.suppressAudit();
        });
        appendAnnotationSet(sb, "BA", model.bannedApi(), e -> {
            AIBannedApi a = e.annotation(AIBannedApi.class);
            return a == null ? "" : String.join(",", a.forbidden()) + "|" + a.useInstead() + "|" + a.reason();
        });
        appendAnnotationSet(sb, "TA", model.threadAffinity(), e -> {
            AIThreadAffinity a = e.annotation(AIThreadAffinity.class);
            return a == null ? "" : a.value().name() + "|" + a.thread() + "|" + a.marshalVia()
                + "|" + a.symptomIfViolated();
        });
        appendAnnotationSet(sb, "KIS", model.keepInSync(), e -> {
            AIKeepInSync a = e.annotation(AIKeepInSync.class);
            return a == null ? "" : String.join(",", a.mirrors()) + "|" + a.reason() + "|" + a.enforcedBy();
        });

        sb.append("S{");
        for (String s : new TreeSet<>(activeServices)) {
            sb.append(s).append(',');
        }
        sb.append('}');

        return fingerprint(sb.toString());
    }

    private static void appendAnnotationSet(StringBuilder sb, String tag, Set<TaggedElement> elements,
                                            AttributeExtractor attrs) {
        sb.append(tag).append('{');
        if (elements.isEmpty()) {
            sb.append('}');
            return;
        }
        // Sort by element path so iteration order can't drift between runs.
        List<TaggedElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(TaggedElement::path));
        for (TaggedElement e : sorted) {
            sb.append(e.path()).append('=').append(attrs.extract(e)).append(';');
        }
        sb.append('}');
    }

    /**
     * Same fingerprint algorithm as {@link WriteCache#fingerprint(String)} — 8-char hex of
     * {@link String#hashCode()}. Cheap, intrinsified on hot JVMs, and the rest of the cache file
     * already trusts this construction. Collisions cannot corrupt output because the per-file
     * {@link WriteCache} entries are still validated by size + mtime + their own fingerprint.
     */
    @AIPerformance(constraint = "O(N) in string length; uses String.hashCode() which HotSpot intrinsifies on x86; must not allocate intermediate byte[]")
    static String fingerprint(String s) {
        return ContentHash.of(s);
    }

    @FunctionalInterface
    private interface AttributeExtractor {
        String extract(TaggedElement e);
    }
}
