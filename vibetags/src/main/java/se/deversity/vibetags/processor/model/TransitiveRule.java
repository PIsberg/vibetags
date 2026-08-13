package se.deversity.vibetags.processor.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One guardrail that reached this project through a dependency rather than through its own sources.
 *
 * <p>A library declares package-level {@code @AI...} annotations on its {@code package-info.java};
 * its build emits them into {@code vibetags/manifests/<package>.json} inside its JAR; a consuming
 * compilation reads that manifest back and turns each entry into one of these. From here on it is
 * plain data like {@link TaggedElement}, so the rendering half never learns that some rules came
 * from a JAR.
 *
 * <p><strong>Why the resource path is a Java package and not {@code META-INF/}.</strong> javac's
 * {@code CLASS_PATH} location cannot see archive directories whose names are not valid package
 * identifiers, so a manifest under {@code META-INF/} is unreadable from an annotation processor:
 * {@code Filer.getResource} throws, and javac's own file manager lists zero entries there. This is
 * measured, not assumed — {@code TransitiveManifestPathTest} pins it.
 *
 * <p>{@link #tier()} is derived from the annotation, never authored. Splitting library rules into
 * "must not be overridden" and "advice" needs no new vocabulary: VibeTags already treats the six
 * safety-bucket annotations as the always-on tier, and that list is load-bearing in the
 * scoped-index collapse. A second severity axis would be a twin of it that nothing keeps in
 * agreement.
 */
public final class TransitiveRule implements Comparable<TransitiveRule> {

    /** How strongly a library rule binds the consuming project. Derived, never authored. */
    public enum Tier {
        /**
         * From one of the six safety-bucket annotations ({@code @AILocked}, {@code @AICore},
         * {@code @AIPrivacy}, {@code @AIIgnore}, {@code @AIAudit}, {@code @AISecure}). Rendered
         * first and never dropped by the volume cap.
         */
        SAFETY,
        /** Everything else. Rendered after the application's own rules, and capped first. */
        ADVISORY
    }

    private final String origin;
    private final String packageName;
    private final String annotation;
    private final Tier tier;
    private final Map<String, String> members;

    /**
     * @param origin      the artifact the rule came from ({@code group:artifact:version}), or
     *                    {@code ""} when the publishing build did not name itself. Rendered next to
     *                    the rule: a dependency is contributing text to instructions an agent
     *                    follows, and the reader has to be able to see whose text it is.
     * @param packageName the package the rule governs, which is also the manifest's filename stem
     * @param annotation  the {@code @AI...} label, e.g. {@code "@AISecure"}
     * @param members     the annotation's attributes; iteration order is preserved as given
     */
    public TransitiveRule(String origin, String packageName, String annotation, Tier tier,
                          Map<String, String> members) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.annotation = Objects.requireNonNull(annotation, "annotation");
        this.tier = Objects.requireNonNull(tier, "tier");
        this.members = members == null || members.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public String origin()      { return origin; }
    public String packageName() { return packageName; }
    public String annotation()  { return annotation; }
    public Tier tier()          { return tier; }

    /** The annotation's attributes, in the order the manifest listed them. Never null. */
    public Map<String, String> members() { return members; }

    /**
     * The rule's attributes as one {@code key=value} line, or {@code ""} when it has none.
     * Used by the renderers and by the fingerprint, so the two can never disagree about what
     * counts as a change.
     */
    public String memberSummary() {
        if (members.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Total order by package, then annotation, then origin, then attributes.
     *
     * <p>Load-bearing for the same reason {@code GuardrailModel} sorts its buckets: manifests are
     * discovered in whatever order the compilation's imports happened to be walked, and that order
     * differs between Maven and Gradle and between machines. Sorting here makes the generated
     * files, and the build fingerprint, a function of the dependency set alone.
     */
    @Override
    public int compareTo(TransitiveRule other) {
        int c = packageName.compareTo(other.packageName);
        if (c != 0) return c;
        c = annotation.compareTo(other.annotation);
        if (c != 0) return c;
        c = origin.compareTo(other.origin);
        if (c != 0) return c;
        return memberSummary().compareTo(other.memberSummary());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransitiveRule other)) return false;
        return origin.equals(other.origin)
            && packageName.equals(other.packageName)
            && annotation.equals(other.annotation)
            && tier == other.tier
            && members.equals(other.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, packageName, annotation, tier, members);
    }

    @Override
    public String toString() {
        return annotation + " on " + packageName + " (" + tier + " from "
            + (origin.isEmpty() ? "unnamed artifact" : origin) + ")";
    }
}
