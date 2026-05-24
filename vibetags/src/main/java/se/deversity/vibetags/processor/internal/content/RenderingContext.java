package se.deversity.vibetags.processor.internal.content;

import java.util.Set;

/**
 * Context containing metadata and configuration options for the current rendering run.
 */
public final class RenderingContext {
    private final String projectName;
    private final String generatedHeader;
    private final Set<String> activeServices;

    public RenderingContext(String projectName, String generatedHeader, Set<String> activeServices) {
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;
        this.activeServices = activeServices;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getGeneratedHeader() {
        return generatedHeader;
    }

    public Set<String> getActiveServices() {
        return activeServices;
    }

    public boolean isActive(Platform platform) {
        return activeServices.contains(platform.getServiceKey());
    }
}
