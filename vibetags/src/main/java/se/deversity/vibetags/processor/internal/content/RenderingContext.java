package se.deversity.vibetags.processor.internal.content;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.RoleConfig;

/**
 * Context containing metadata and configuration options for the current rendering run.
 */
public final class RenderingContext {
    private final String projectName;
    private final String generatedHeader;
    private final Set<String> activeServices;
    private final int estimatedContentSize;
    private final Set<Element> granularOwners;
    private final RoleConfig roles;

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
                            int estimatedContentSize, Set<Element> granularOwners) {
        this(projectName, generatedHeader, activeServices, estimatedContentSize, granularOwners, null);
    }

    /**
     * @param roles the role routing in effect for this run (a {@code .vibetags-roles} config), or
     *        {@code null} when roles are off. Used by the scoped-rules index so its pointers name the
     *        same files {@code GranularRulesWriter} writes (role-grouped or per-class).
     */
    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices,
                            int estimatedContentSize, Set<Element> granularOwners, RoleConfig roles) {
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;
        // Defensive copy: prevent callers from mutating the set through the stored reference.
        this.activeServices = Collections.unmodifiableSet(new LinkedHashSet<>(activeServices));
        this.estimatedContentSize = Math.max(256, estimatedContentSize);
        this.granularOwners = Collections.unmodifiableSet(new LinkedHashSet<>(granularOwners));
        this.roles = roles;
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
    public Set<Element> granularOwners() {
        return granularOwners;
    }

    /** The role routing for this run (a {@code .vibetags-roles} config), or {@code null} when off. */
    public RoleConfig roles() {
        return roles;
    }

    public boolean isActive(Platform platform) {
        return activeServices.contains(platform.getServiceKey());
    }
}
