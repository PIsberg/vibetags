package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPerformance;

import se.deversity.vibetags.processor.internal.content.AnnotationDescriptor;
import se.deversity.vibetags.processor.internal.content.AnnotationDescriptors;
import se.deversity.vibetags.processor.model.ContentHash;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.SourceLocation;
import se.deversity.vibetags.processor.model.TransitiveRule;
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
     * <p>Each element contributes its path, its kind and its attribute values.
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
        // publishedModel, not model: the fingerprint's job is to notice when the generated content
        // would differ, so it has to hash what is written rather than what was collected. Hashing
        // the published set gets -Avibetags.exclude in for free and keeps invariant 12 true by
        // construction, where folding the raw pattern strings in would make two patterns that
        // exclude the same elements look like different builds. With no exclusions the two models
        // are the same object, so every existing consumer's fingerprint is unchanged and no cache
        // is invalidated by this.
        GuardrailModel model = collector.publishedModel();
        StringBuilder sb = new StringBuilder(4096);

        sb.append("V{").append(processorVersion).append('}');

        // One section per annotation, in AnnotationDescriptors.ALL's order with its tag and its
        // extractor. That order is this fingerprint's pinned order: it is not GuardrailAnnotations.ALL
        // and must not be aligned with it. BuildFingerprintPinnedValueTest holds the hashed string to
        // a literal, so a moved entry, a renamed tag or a reworded extractor fails there instead of
        // costing every consumer a cache miss nobody can explain (#765).
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            appendAnnotationSet(sb, descriptor.fingerprintTag(), model.of(descriptor.type()),
                descriptor.fingerprintMembers());
        }

        sb.append("S{");
        for (String s : new TreeSet<>(activeServices)) {
            sb.append(s).append(',');
        }
        sb.append('}')
            // Guardrails inherited from dependency JARs. Folded in because they are an input to the
            // generated files that the element set says nothing about: upgrading a dependency changes
            // what should be written while every annotation in this project stays byte-identical. Left
            // out, the short-circuit above would match, the generate phase would be skipped, and the
            // committed files would keep describing the previous version of the dependency — with no
            // diagnostic anywhere, because nothing failed. TransitiveFingerprintTest pins it.
            .append("X{");
        for (TransitiveRule rule : model.transitiveRules()) {
            sb.append(rule.packageName()).append(':')
              .append(rule.annotation()).append(':')
              .append(rule.origin()).append(':')
              .append(rule.tier().name()).append(':')
              .append(rule.memberSummary()).append(';');
        }
        sb.append('}')
            // Source positions of locked elements. They are content of .vibetags-locks, which
            // records each lock's line range, so moving a locked element with every annotation
            // unchanged changes what should be written. Left out, this fingerprint matched, the
            // generate phase was skipped, and the committed report kept describing the old lines
            // — while check mode failed on the same tree and told the developer to run the
            // regeneration that had just been short-circuited into doing nothing (issue #440).
            //
            // Costs nothing to a project without the report: positions are resolved only when
            // .vibetags-locks is opted in, so this map is empty and the section is two characters.
            .append("P{");
        for (TaggedElement element : model.locked()) {
            SourceLocation at = model.lockedPosition(element);
            if (at != null) {
                sb.append(element.qualifiedName()).append(':')
                  .append(at.file()).append(':')
                  .append(at.startLine()).append('-')
                  .append(at.endLine()).append(';');
            }
        }
        sb.append('}');

        return fingerprint(sb.toString());
    }

    private static void appendAnnotationSet(StringBuilder sb, String tag, Set<TaggedElement> elements,
                                            AnnotationDescriptor.FingerprintMembers attrs) {
        sb.append(tag).append('{');
        if (elements.isEmpty()) {
            sb.append('}');
            return;
        }
        // Sort by element path so iteration order can't drift between runs.
        List<TaggedElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(TaggedElement::path));
        for (TaggedElement e : sorted) {
            // The kind is rendered content too: the locks report names it, so a class that
            // becomes an interface with nothing else moving — same path, same lines, same reason
            // — changes what the file says while every other input here stays byte-identical.
            // Left out, that edit short-circuited past regeneration and check mode failed on the
            // tree the build had just called current.
            sb.append(e.path()).append('#').append(e.kind().name())
              .append('=').append(attrs.of(e)).append(';');
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
}
