package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.WholeFileMerge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a round of a multi-module build publishes for each service: every module's merged view, or
 * this module's own rendering.
 *
 * <p>Every path here ends in one of those two outcomes. From the written file alone they are hard
 * to tell apart, because both are valid documents and the wrong one is merely missing its
 * siblings. Issue #265 was exactly that, and it survived as long as it did because nothing recorded
 * which path ran. So every path states its reason at DEBUG, and {@code merge.skip} never omits one.
 *
 * <p>A pure function of the content, the service files and the sidecars: it reads no file and
 * writes none, so generation and check mode call the same code and cannot drift apart.
 */
public final class CrossModuleMerge {

    private final Map<String, String> ownContent;
    private final List<ModuleSidecar> sidecars;
    /** Where to narrate; non-null only when DEBUG is on, so nothing formats an argument at a disabled level. */
    private final @Nullable Logger debugLog;

    private CrossModuleMerge(Map<String, String> ownContent, List<ModuleSidecar> sidecars, @Nullable Logger debugLog) {
        this.ownContent = ownContent;
        this.sidecars = sidecars;
        this.debugLog = debugLog;
    }

    /**
     * The content to write per service: {@code ownContent} with each service that has a merged view
     * replaced by it. Keys are never added, and keep the order they were given in.
     *
     * @param ownContent   what this module rendered, by service key
     * @param serviceFiles the file each service writes
     * @param sidecars     every module's sidecar, this module's included
     * @param debugLog     where to narrate each decision, or {@code null} when DEBUG is off
     */
    public static Map<String, String> of(Map<String, String> ownContent, Map<String, Path> serviceFiles,
                                         List<ModuleSidecar> sidecars, @Nullable Logger debugLog) {
        if (debugLog != null) {
            // The count, not the names: each service that merges says so on its own line, and 44
            // of them on one line buries the two that matter.
            debugLog.debug("merge.begin modules={} regions={} services={}",
                sidecars.size(), ModuleSidecar.regionCount(sidecars), ownContent.size());
        }
        CrossModuleMerge merge = new CrossModuleMerge(ownContent, sidecars, debugLog);
        Map<String, String> merged = new LinkedHashMap<>(ownContent);
        for (Map.Entry<String, Path> file : serviceFiles.entrySet()) {
            String shared = merge.sharedContent(file.getKey(), file.getValue().getFileName());
            if (shared != null) {
                merged.put(file.getKey(), shared);
            }
        }
        return merged;
    }

    /** Every module's view of {@code service}, or {@code null} to keep this module's own rendering. */
    private @Nullable String sharedContent(String service, @Nullable Path fileName) {
        // Path.getFileName() returns null only for root paths — guard for correctness.
        if (fileName == null || !ownContent.containsKey(service)) {
            return null;
        }
        String[] markers = GuardrailFileWriter.getMarkersFor(fileName.toString());
        if (markers == null) {
            return wholeFile(service, fileName);
        }
        return betweenMarkers(service, GuardrailFileWriter.MARKER_START_MD.equals(markers[0]));
    }

    /**
     * A JSON or TOML file. It has nowhere to put a marker, so it is a whole-file overwrite, and
     * keeping the compiling module's rendering publishes one module's view of the project. The
     * renderer supplies a format-aware merge where the file holds per-element content (issue #265).
     * Where it does not, a static configuration, there is nothing to merge and every module
     * renders the same bytes anyway.
     */
    private @Nullable String wholeFile(String service, Path fileName) {
        WholeFileMerge merger = PlatformRendererRegistry.wholeFileMergeFor(service);
        if (merger == null) {
            if (debugLog != null) {
                debugLog.debug("merge.skip service={} reason=no-whole-file-merger file={}", service, fileName);
            }
            return null;
        }
        List<Map.Entry<String, String>> contributions = ModuleSidecar.contributionsFor(service, sidecars);
        String document = merger.merge(contributions);
        if (document == null || document.isBlank()) {
            if (debugLog != null) {
                // The merger declined: an unexpected document shape, so it refuses to guess rather
                // than corrupt the file. This module's own rendering ships instead, which is valid
                // but sibling-blind. That trade is worth a line in the log.
                debugLog.debug("merge.skip service={} reason={} contributions={}",
                    service, document == null ? "merger-declined" : "merger-empty", contributions.size());
            }
            return null;
        }
        if (debugLog != null) {
            debugLog.debug("merge.wholefile service={} contributions={} bytes={}",
                service, contributions.size(), document.length());
        }
        return document;
    }

    /** A file that keeps its generated block between markers: one region per module inside it. */
    private @Nullable String betweenMarkers(String service, boolean htmlMarkers) {
        String body = ModuleSidecar.mergeFor(service, sidecars, htmlMarkers);
        if (body.isBlank()) {
            if (debugLog != null) {
                debugLog.debug("merge.skip service={} reason=empty-merged-body html={}", service, htmlMarkers);
            }
            return null;
        }
        if (debugLog != null) {
            debugLog.debug("merge.markers service={} html={} bytes={}", service, htmlMarkers, body.length());
            // Deleting TESTING.md changes what the merge reads for a module, and the file it
            // writes cannot say so: the body is well formed either way.
            for (String module : ModuleSidecar.testingFallbackModules(service, sidecars)) {
                debugLog.debug("merge.testing.fallback service={} module={}", service, module);
            }
        }
        return body;
    }
}
