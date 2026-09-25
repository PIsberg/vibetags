package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.RoleConfig;

/**
 * Context containing metadata and configuration options for the current rendering run.
 */
public final class RenderingContext {
    private final String projectName;
    private final String generatedHeader;
    private final Set<String> activeServices;
    private final int estimatedContentSize;
    private final Set<TaggedElement> granularOwners;
    private final Set<TaggedElement> indexOwners;
    private final @Nullable RoleConfig roles;
    private final boolean safetyDigest;
    private final boolean testRound;

    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices) {
        this(projectName, generatedHeader, activeServices, 4096);
    }

    /**
     * @param estimatedContentSize a capacity hint (bytes) for the top-level {@code StringBuilder} of
     *        an O(N) renderer, derived from the collected element count so large outputs avoid
     *        repeated grow-and-copy reallocation. Clamped to a small floor.
     */
    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices,
                            int estimatedContentSize) {
        this(projectName, generatedHeader, activeServices, estimatedContentSize, Collections.emptySet());
    }

    /**
     * @param estimatedContentSize a capacity hint (bytes) for the top-level {@code StringBuilder} of
     *        an O(N) renderer, derived from the collected element count so large outputs avoid
     *        repeated grow-and-copy reallocation. Clamped to a small floor.
     * @param granularOwners owner elements with granular scoped rule files this run (see
     *        {@link #granularOwners()}); pass an empty set when no granular service is active.
     */
    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices,
                            int estimatedContentSize, Set<TaggedElement> granularOwners) {
        this(projectName, generatedHeader, activeServices, estimatedContentSize, granularOwners, null);
    }

    /**
     * @param roles the role routing in effect for this run (a {@code .vibetags-roles} config), or
     *        {@code null} when roles are off. Used by the scoped-rules index so its pointers name the
     *        same files {@code GranularRulesWriter} writes (role-grouped or per-class).
     */
    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices,
                            int estimatedContentSize, Set<TaggedElement> granularOwners,
                            @Nullable RoleConfig roles) {
        this(projectName, generatedHeader, activeServices, estimatedContentSize, granularOwners, granularOwners,
            roles, false, false);
    }

    /**
     * @param safetyDigest render the safety tier only, with no scoped-rules index — see
     *        {@link #safetyDigest()}.
     * @param testRound this round compiled test code, see {@link #testRound()}.
     */
    private RenderingContext(String projectName, String generatedHeader, Set<String> activeServices,
                             int estimatedContentSize, Set<TaggedElement> granularOwners,
                             Set<TaggedElement> indexOwners, @Nullable RoleConfig roles,
                             boolean safetyDigest, boolean testRound) {
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;
        // Defensive copy: prevent callers from mutating the set through the stored reference.
        this.activeServices = Collections.unmodifiableSet(new LinkedHashSet<>(activeServices));
        this.estimatedContentSize = Math.max(256, estimatedContentSize);
        // Sorted by the stable path identity, so the scoped-rules index is a pure function of the
        // source. Owners arrive in annotation-processing round order, which the JLS does not
        // constrain and which differs between Maven and Gradle; leaving it as-collected made two
        // builds of identical sources emit byte-different index lines (issue #325). Sorting here
        // rather than at each emit site means every current and future consumer of granularOwners()
        // is deterministic by construction.
        this.granularOwners = Collections.unmodifiableSet(sorted(granularOwners));
        this.indexOwners = Collections.unmodifiableSet(sorted(indexOwners));
        this.roles = roles;
        this.safetyDigest = safetyDigest;
        this.testRound = testRound;
    }

    /**
     * A copy of this context that renders the <em>safety digest</em>: the always-on buckets inline,
     * and no scoped-rules index at all.
     *
     * <p>Used for the region a module contributes to a lean indexed reactor root. The root cannot
     * carry that module's index — the scoped files live under the module directory, not the root's —
     * so the pointer sentence names the directory instead, and this digest is what keeps
     * {@code @AILocked} / {@code @AICore} / {@code @AIAudit} unconditionally in context there
     * (<a href="https://github.com/PIsberg/vibetags/issues/332">issue #332</a>).
     */
    public RenderingContext asSafetyDigest() {
        return new RenderingContext(projectName, generatedHeader, activeServices, estimatedContentSize,
            granularOwners, indexOwners, roles, true, testRound);
    }

    /**
     * A copy of this context whose scoped-rules index lists only {@code owners}, a subset of
     * {@link #granularOwners()}. Owners outside the subset keep their scoped files and keep the
     * aggregate collapsed; they only lose their index line (issue #839).
     */
    public RenderingContext withIndexOwners(Set<TaggedElement> owners) {
        return new RenderingContext(projectName, generatedHeader, activeServices, estimatedContentSize,
            granularOwners, owners, roles, safetyDigest, testRound);
    }

    /**
     * A copy of this context for a round that compiled test code, the only kind of round whose
     * guardrails {@code TESTING.md} carries.
     *
     * <p>A plain flag rather than the source set's name: this layer may not import compiler APIs
     * (invariant 7), and what a source set is called is the collector's business, not a renderer's.
     */
    public RenderingContext asTestRound() {
        return new RenderingContext(projectName, generatedHeader, activeServices, estimatedContentSize,
            granularOwners, indexOwners, roles, safetyDigest, true);
    }

    private static Set<TaggedElement> sorted(Set<TaggedElement> owners) {
        Set<TaggedElement> sortedOwners = new LinkedHashSet<>();
        owners.stream()
                .sorted(Comparator.comparing(TaggedElement::path)
                                  .thenComparing(o -> String.valueOf(o.kind())))
                .forEach(sortedOwners::add);
        return sortedOwners;
    }

    /** True when this round compiled test code; see {@link #asTestRound()}. */
    public boolean testRound() {
        return testRound;
    }

    /**
     * True when this run renders a safety digest: aggregate renderers keep their inline safety
     * sections but emit no scoped-rules index.
     */
    public boolean safetyDigest() {
        return safetyDigest;
    }

    /** Capacity hint (bytes) for an O(N) renderer's top-level StringBuilder. */
    public int estimatedContentSize() {
        return estimatedContentSize;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getGeneratedHeader() {
        return generatedHeader;
    }

    /** Returns an unmodifiable view of the active-service keys. */
    public Set<String> getActiveServices() {
        return activeServices;
    }

    /**
     * Owner elements (class/package) that have granular scoped rule files generated this run.
     * Empty when no {@code *_granular} service is active. Aggregate renderers use this to emit a
     * scoped-rules index instead of duplicating each element's full guardrails inline.
     */
    public Set<TaggedElement> granularOwners() {
        return granularOwners;
    }

    /**
     * The owners the scoped-rules index lists: the granular owners whose files say something the
     * aggregate does not already carry inline. Same order as {@link #granularOwners()}.
     */
    public Set<TaggedElement> indexOwners() {
        return indexOwners;
    }

    /** The role routing for this run (a {@code .vibetags-roles} config), or {@code null} when off. */
    public @Nullable RoleConfig roles() {
        return roles;
    }

    public boolean isActive(Platform platform) {
        return activeServices.contains(platform.getServiceKey());
    }
}
