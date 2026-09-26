package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which rendered files a round writes, and whether each counts as carrying new rules, decided once
 * and consumed by both the real writer ({@code generateFiles()}) and check mode's dry-run writer
 * ({@code checkFiles()}) (#766).
 *
 * <p>Before this, each method computed the same per-file predicates inline and a test held them in
 * step. A check verdict is only worth anything if it reproduces generation exactly, so the decision
 * now has one home. {@code WritePlanSharingTest} fails if either method goes back to deciding for
 * itself.
 *
 * <p>{@code hasNewRules} is an OR, and the order of the terms matters less than that: an ignore file
 * is always rewritten, a file some module contributed to is rewritten, and so is one this source set
 * has just withdrawn from (#781). A first attempt at sharing this predicate wrote an AND with the
 * ignore test negated, and exclusion lists stopped being rewritten.
 */
public final class WritePlan {

    /**
     * One file to write.
     *
     * @param service     the service key
     * @param path        where the service's file lives
     * @param content     the rendered, merged content
     * @param hasNewRules passed to the writer: whether an empty round may still rewrite the file
     */
    public record Write(String service, Path path, String content, boolean hasNewRules) {}

    private final List<Write> writes;
    private final List<String> unmapped;

    private WritePlan(List<Write> writes, List<String> unmapped) {
        this.writes = Collections.unmodifiableList(writes);
        this.unmapped = Collections.unmodifiableList(unmapped);
    }

    /**
     * Plans the writes for {@code content}.
     *
     * @param content             the merged content, keyed by service
     * @param serviceFiles        every service's output path
     * @param allSidecars         the sidecars the merge read
     * @param multiModule         whether the merge path applied; if so, contribution is judged by
     *                            every sidecar rather than by this round alone
     * @param anyAnnotationsFound whether this round saw any annotation
     * @param retiredServices     services this source set has just withdrawn from
     */
    public static WritePlan of(Map<String, String> content, Map<String, Path> serviceFiles,
                               List<ModuleSidecar> allSidecars, boolean multiModule,
                               boolean anyAnnotationsFound, Set<String> retiredServices) {
        List<Write> writes = new ArrayList<>(content.size());
        List<String> unmapped = new ArrayList<>();
        for (Map.Entry<String, String> entry : content.entrySet()) {
            String service = entry.getKey();
            Path path = serviceFiles.get(service);
            if (path == null) {
                // Rendered content for a service the registry has no path for. Unreachable while every
                // renderer's key is registered, but by agreement between two collections, not by
                // construction; one unmapped key must not cost every other file its write.
                unmapped.add(service);
                continue;
            }
            boolean contributed = multiModule
                ? allSidecars.stream().anyMatch(s -> s.getBodies().containsKey(service))
                : anyAnnotationsFound;
            boolean hasNewRules = contributed || retiredServices.contains(service)
                || ServiceRegistry.isIgnoreService(service);
            writes.add(new Write(service, path, entry.getValue(), hasNewRules));
        }
        return new WritePlan(writes, unmapped);
    }

    /** The files to write, in content order. */
    public List<Write> writes() {
        return writes;
    }

    /** Services that rendered content but have no output path, and so are not written. */
    public List<String> unmapped() {
        return unmapped;
    }
}
