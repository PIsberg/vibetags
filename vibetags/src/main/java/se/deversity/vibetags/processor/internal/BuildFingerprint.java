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
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;

import javax.lang.model.element.Element;
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
 * same hex output across processor invocations. Collisions are tolerable because the per-file
 * {@link WriteCache} still validates each output's size+mtime; the worst a fingerprint collision
 * can do is skip work that would have produced byte-identical files.
 *
 * <p>Stateless. All methods are static.
 */
@AIImmutable(note = "Purely stateless; private constructor prevents instantiation; all computation results are returned as values")
public final class BuildFingerprint {

    private BuildFingerprint() {}

    /**
     * Computes the fingerprint over (collector annotations × resolved active services).
     *
     * <p>Element ordering is normalised by element-path (a stable, FQN-like string) before hashing,
     * because {@link java.util.LinkedHashSet} preserves insertion order and javac's discovery order
     * is not guaranteed to be deterministic across runs. Active services are sorted alphabetically
     * for the same reason.
     */
    @AIContract(reason = "Same inputs must always produce the same 8-hex output across JVM restarts; changing the algorithm silently invalidates all existing .vibetags-cache files")
    public static String compute(AnnotationCollector collector, Set<String> activeServices) {
        StringBuilder sb = new StringBuilder(4096);

        appendAnnotationSet(sb, "L", collector.locked(), e -> {
            AILocked a = e.getAnnotation(AILocked.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "C", collector.context(), e -> {
            AIContext a = e.getAnnotation(AIContext.class);
            return a == null ? "" : a.focus() + "|" + a.avoids();
        });
        appendAnnotationSet(sb, "I", collector.ignore(), e -> {
            // @AIIgnore has no attributes that affect output beyond presence + element name,
            // both of which are already captured by the element-path key.
            return "";
        });
        appendAnnotationSet(sb, "A", collector.audit(), e -> {
            AIAudit a = e.getAnnotation(AIAudit.class);
            if (a == null) return "";
            String[] checkFor = a.checkFor();
            return String.join(",", checkFor);
        });
        appendAnnotationSet(sb, "D", collector.draft(), e -> {
            AIDraft a = e.getAnnotation(AIDraft.class);
            return a == null ? "" : a.instructions();
        });
        appendAnnotationSet(sb, "P", collector.privacy(), e -> {
            AIPrivacy a = e.getAnnotation(AIPrivacy.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "K", collector.core(), e -> {
            AICore a = e.getAnnotation(AICore.class);
            return a == null ? "" : a.sensitivity() + "|" + a.note();
        });
        appendAnnotationSet(sb, "F", collector.performance(), e -> {
            AIPerformance a = e.getAnnotation(AIPerformance.class);
            return a == null ? "" : a.constraint();
        });
        appendAnnotationSet(sb, "T", collector.contract(), e -> {
            AIContract a = e.getAnnotation(AIContract.class);
            return a == null ? "" : a.reason();
        });
        appendAnnotationSet(sb, "TD", collector.testDriven(), e -> {
            AITestDriven a = e.getAnnotation(AITestDriven.class);
            if (a == null) return "";
            StringBuilder attrs = new StringBuilder();
            attrs.append(a.coverageGoal()).append('|');
            attrs.append(a.testLocation()).append('|');
            for (AITestDriven.Framework f : a.framework()) attrs.append(f.name()).append(',');
            attrs.append('|').append(a.mockPolicy());
            return attrs.toString();
        });
        appendAnnotationSet(sb, "TS", collector.threadSafe(), e -> {
            AIThreadSafe a = e.getAnnotation(AIThreadSafe.class);
            return a == null ? "" : a.strategy().name() + "|" + a.note();
        });
        appendAnnotationSet(sb, "IM", collector.immutable(), e -> {
            AIImmutable a = e.getAnnotation(AIImmutable.class);
            return a == null ? "" : a.note();
        });
        appendAnnotationSet(sb, "DP", collector.deprecated(), e -> {
            AIDeprecated a = e.getAnnotation(AIDeprecated.class);
            return a == null ? "" : a.replacedBy() + "|" + a.migrationGuide() + "|" + a.deadline();
        });
        appendAnnotationSet(sb, "OB", collector.observability(), e -> {
            AIObservability a = e.getAnnotation(AIObservability.class);
            if (a == null) return "";
            return String.join(",", a.metrics()) + "|"
                 + String.join(",", a.traces()) + "|"
                 + String.join(",", a.logs()) + "|"
                 + a.note();
        });
        appendAnnotationSet(sb, "RG", collector.regulation(), e -> {
            AIRegulation a = e.getAnnotation(AIRegulation.class);
            return a == null ? "" : a.standard() + "|" + a.clause() + "|" + a.description();
        });
        appendAnnotationSet(sb, "PT", collector.parallelTests(), e -> "");
        appendAnnotationSet(sb, "LB", collector.legacyBridge(), e -> "");
        appendAnnotationSet(sb, "AR", collector.architecture(), e -> {
            AIArchitecture a = e.getAnnotation(AIArchitecture.class);
            if (a == null) return "";
            return a.belongsTo() + "|" + String.join(",", a.cannotReference());
        });
        appendAnnotationSet(sb, "PA", collector.publicApi(), e -> "");
        appendAnnotationSet(sb, "SE", collector.strictExceptions(), e -> "");
        appendAnnotationSet(sb, "ST", collector.strictTypes(), e -> "");
        appendAnnotationSet(sb, "IT", collector.internationalized(), e -> "");
        appendAnnotationSet(sb, "SC", collector.strictClasspath(), e -> "");
        appendAnnotationSet(sb, "SS", collector.schemaSafe(), e -> "");

        sb.append("S{");
        for (String s : new TreeSet<>(activeServices)) {
            sb.append(s).append(',');
        }
        sb.append('}');

        return fingerprint(sb.toString());
    }

    private static void appendAnnotationSet(StringBuilder sb, String tag, Set<Element> elements,
                                            AttributeExtractor attrs) {
        sb.append(tag).append('{');
        if (elements.isEmpty()) {
            sb.append('}');
            return;
        }
        // Sort by element path so iteration order can't drift between runs.
        List<Element> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(ElementNaming::elementPath));
        for (Element e : sorted) {
            sb.append(ElementNaming.elementPath(e)).append('=').append(attrs.extract(e)).append(';');
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
        int h = s.hashCode();
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = HEX[h & 0xF];
            h >>>= 4;
        }
        return new String(out);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @FunctionalInterface
    private interface AttributeExtractor {
        String extract(Element e);
    }
}
