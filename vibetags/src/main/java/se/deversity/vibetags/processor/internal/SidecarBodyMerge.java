package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.SourceSetMerge;
import se.deversity.vibetags.processor.internal.content.YamlMergeShape;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the bodies the module sidecars carry become one file's text: the reactor file every module
 * shares ({@link ModuleSidecar#mergeFor}), what a {@code WholeFileMerge} is handed
 * ({@link ModuleSidecar#contributionsFor}), and a module's own nested output across its source sets
 * ({@link ModuleSidecar#mergeModuleBodies}).
 *
 * <p>Disk-free. What needs the filesystem (the lean-index pointers, the {@code TESTING.md} opt-in)
 * is stashed on the sidecars by {@link ModuleSidecar#readAll} first, which is why this reads a
 * sidecar only through its accessors and keeps no state of its own between calls. Sidecars arrive
 * sorted by file name, and every merge here keeps that order.
 */
final class SidecarBodyMerge {

    private SidecarBodyMerge() {}

    /** The text between the shared file's markers for {@code serviceKey}; see {@link ModuleSidecar#mergeFor}. */
    static String reactorFile(String serviceKey, List<ModuleSidecar> sidecars, SubMarkers markers) {
        return new ReactorMerge(serviceKey, sidecars, markers).document();
    }


    /**
     * One merge of every region's body for one service into the text between the reactor file's
     * markers: what {@link ModuleSidecar#mergeFor} documents, as the steps it takes.
     *
     * <p>Disk-free like its caller. Whatever needed the filesystem (the lean-index pointers, the
     * {@code TESTING.md} opt-in) was stashed on the sidecars by {@link ModuleSidecar#readAll} beforehand.
     */
    private static final class ReactorMerge {

        private final String serviceKey;
        private final List<ModuleSidecar> sidecars;
        private final SubMarkers markers;
        private final boolean indexMode;
        /** Text about the file rather than about a module, written once above the regions. */
        private final String header;

        /** Region id to that region's body, in output order; filled by {@link #collectRegions}. */
        private final List<Map.Entry<String, String>> regions = new ArrayList<>();
        /** Whether any region is linked rather than embedded: a lean-index pointer stands in for it. */
        private boolean anyLinked;

        ReactorMerge(String serviceKey, List<ModuleSidecar> sidecars, SubMarkers markers) {
            this.serviceKey = serviceKey;
            this.sidecars = sidecars;
            this.markers = markers;
            this.indexMode = ModuleSidecar.isRootIndexMode(sidecars);
            this.header = markers == SubMarkers.MARKDOWN ? hoistedHeader() : "";
        }

        String document() {
            collectRegions();
            if (regions.isEmpty()) {
                return "";
            }
            // Historical behaviour is preserved whenever no pointer applies: a lone contribution is
            // returned verbatim (no sub-markers), multiple are wrapped below. When at least one
            // module was linked, always wrap so every pointer keeps its owning-module sub-marker
            // context.
            if (!anyLinked && regions.size() < ModuleSidecar.MULTI_MODULE_THRESHOLD) {
                return withFrontMatter(header, regions.get(0).getValue());
            }
            String yaml = asOneYamlDocument();
            return yaml != null ? yaml : withFrontMatter(header, stacked());
        }

        /**
         * The front matter or prologue every region opens with, to be written once for the file.
         * Only asked for in comment style: in hash style each region keeps its own.
         */
        private String hoistedHeader() {
            List<String> bodies = reactorBodiesOf(serviceKey, sidecars);
            // A Markdown body that opens with YAML front matter (the Devin Desktop safety files,
            // the Claude skill) needs that header once, at the top of the file. Stacked per module
            // it lands inside a sub-marker, where no tool reads it, and a file created by the merge
            // opens with none at all, so a trigger: always_on rule never loads (issue #684). YAML
            // documents take their own merge shape and are left alone.
            String frontMatter = PlatformRendererRegistry.mergeShapeFor(serviceKey) == null
                ? sharedFrontMatter(bodies)
                : "";
            // Same hoist, for a renderer that says in prose what the YAML platforms say in front
            // matter: text about the file rather than about a module. Only TESTING.md declares one,
            // and only when every region actually opens with it (issue #783).
            return frontMatter.isEmpty() ? sharedPrologue(serviceKey, bodies) : frontMatter;
        }

        private void collectRegions() {
            for (Map.Entry<String, List<ModuleSidecar>> region : groupByRegion(sidecars).entrySet()) {
                List<String> parts = partsOf(region.getValue());
                if (!parts.isEmpty()) {
                    regions.add(new AbstractMap.SimpleEntry<>(region.getKey(), joinParts(serviceKey, parts)));
                }
            }
        }

        /**
         * What one region's source sets put into the file: each one's body, or for a linked module
         * each one's safety digest followed by the module's one pointer.
         */
        private List<String> partsOf(List<ModuleSidecar> sourceSets) {
            List<String> parts = new ArrayList<>();
            String pointer = null;
            for (ModuleSidecar sourceSet : sourceSets) {
                String body = sourceSet.reactorBody(serviceKey);
                if (body == null || body.isBlank()) {
                    continue;
                }
                // In lean-index mode a module that maintains its own per-module output for this
                // service contributes its safety-tier digest plus a short pointer instead of its
                // full body (see applyRootIndexModeTo() and issue #332).
                String own = indexMode ? sourceSet.indexPointer(serviceKey) : null;
                addUnheaded(parts, own == null ? body : sourceSet.indexDigest(serviceKey));
                if (own != null) {
                    pointer = own;
                }
            }
            if (pointer != null) {
                anyLinked = true;
                parts.add(pointer); // one pointer per module, after every source set's digest
            }
            return parts;
        }

        /** Adds {@code text} without the hoisted header it may open with; nothing when it is blank. */
        private void addUnheaded(List<String> parts, @Nullable String text) {
            if (text != null && !text.isBlank()) {
                parts.add(withoutFrontMatter(text, header).strip());
            }
        }

        /**
         * Every region under the one scaffold a YAML platform allows, or {@code null} when this
         * service is not such a platform or its shape no longer describes what was rendered.
         *
         * <p>A YAML platform has one rules:/reviews:/customModes: key, so stacking whole documents
         * produces a file whose top-level key repeats once per module: rejected by a strict parser,
         * silently truncated to the last module by a lenient one. Those platforms declare where
         * their scaffold ends and get it written once, with every module's entries under it. A null
         * merge means the declared anchor was not in the rendering (a renderer changed without its
         * shape), and stacking is then still the better of the two wrong answers.
         */
        private @Nullable String asOneYamlDocument() {
            YamlMergeShape shape = PlatformRendererRegistry.mergeShapeFor(serviceKey);
            if (shape == null) {
                return null;
            }
            String document = shape.merge(regions, markers::open, markers::close);
            return document == null ? null : document.strip();
        }

        /** Every region between its own sub-markers, one after the other. */
        private String stacked() {
            // Every module rendered its own scoped-rules section; stacked, the heading and its
            // preamble repeated once per module (#930). Written once, after the module blocks, with
            // every line.
            String scopedIndex = ScopedIndexHoist.hoist(regions);
            StringBuilder merged = new StringBuilder();
            for (Map.Entry<String, String> region : regions) {
                merged.append(markers.wrap(region.getKey(), region.getValue()));
            }
            String document = merged.toString().strip();
            return scopedIndex.isEmpty() ? document : document + "\n\n" + scopedIndex;
        }
    }

    /**
     * One region's contribution, built from the bodies its source sets rendered.
     *
     * <p>Blank-line concatenation is right for every format that reads as prose: two source sets'
     * sections stacked under one sub-marker are exactly what a reader wants. It is wrong for a YAML
     * document, where each body carries the platform's whole scaffold, so joining two of them puts
     * a second {@code rules:} or {@code reviews:} inside the contribution, below the point the
     * merge strips a scaffold from. A strict parser then rejects the file and a lenient one keeps
     * the last block only, which is the same silent loss {@link YamlMergeShape} was written for.
     *
     * <p>A {@code null} from the shape means it no longer describes what the renderer emits. The
     * old concatenation is then still the better of two wrong answers, the same judgement
     * {@code mergeFor} already makes one level out.
     */
    private static String joinParts(String serviceKey, List<String> parts) {
        YamlMergeShape shape = PlatformRendererRegistry.mergeShapeFor(serviceKey);
        if (shape != null && parts.size() > 1) {
            String merged = shape.mergeSourceSets(parts);
            if (merged != null) {
                return merged;
            }
        }
        return joinSourceSets(serviceKey, parts);
    }

    /**
     * {@code parts} as one body through the renderer's {@link SourceSetMerge}, or blank-line
     * concatenated when it declares none or declines. Concatenation keeps every guardrail; the merge
     * only stops a structured body repeating its scaffold once per source set (issue #839).
     */
    private static String joinSourceSets(String serviceKey, List<String> parts) {
        SourceSetMerge merge = PlatformRendererRegistry.sourceSetMergeFor(serviceKey);
        if (merge != null && parts.size() > 1) {
            String merged = merge.merge(parts);
            if (merged != null) {
                return merged;
            }
        }
        return String.join("\n\n", parts);
    }

    /** Every non-blank rendered reactor body for {@code serviceKey} of every sidecar given. */
    private static List<String> reactorBodiesOf(String serviceKey, List<ModuleSidecar> sidecars) {
        List<String> bodies = new ArrayList<>();
        for (ModuleSidecar s : sidecars) {
            addIfNotBlank(bodies, s.reactorBody(serviceKey));
        }
        return bodies;
    }

    /** Every non-blank module-own (nested) body for {@code serviceKey} of every sidecar given. */
    private static List<String> moduleBodiesOf(String serviceKey, List<ModuleSidecar> sidecars) {
        List<String> bodies = new ArrayList<>();
        for (ModuleSidecar s : sidecars) {
            addIfNotBlank(bodies, s.getModuleBodies().get(serviceKey));
        }
        return bodies;
    }

    private static void addIfNotBlank(List<String> bodies, @Nullable String body) {
        if (body != null && !body.isBlank()) {
            bodies.add(body.strip());
        }
    }

    /**
     * The YAML front matter every one of {@code bodies} opens with, or {@code ""} when any body has
     * none or two bodies disagree. A header that differs per module is not one this merge can pick,
     * so those bodies keep the previous stacking rather than lose one module's header.
     */
    static String sharedFrontMatter(List<String> bodies) {
        String shared = null;
        for (String body : bodies) {
            int close = FrontMatter.end(body);
            if (close < 0) {
                return "";
            }
            String header = body.substring(0, close);
            if (shared == null) {
                shared = header;
            } else if (!shared.equals(header)) {
                return "";
            }
        }
        return shared == null ? "" : shared;
    }

    /**
     * The prologue {@code serviceKey}'s renderer declares, but only when every one of {@code bodies}
     * opens with it.
     *
     * <p>The "every one" is the guard that matters. A sidecar written by an older processor, or by
     * a module that renders this service differently, would otherwise have the first characters of
     * its own content cut off by a prefix it never wrote.
     */
    static String sharedPrologue(String serviceKey, List<String> bodies) {
        String prologue = PlatformRendererRegistry.filePrologueFor(serviceKey);
        if (prologue.isEmpty() || bodies.isEmpty()) {
            return "";
        }
        for (String body : bodies) {
            if (!body.strip().startsWith(prologue.strip())) {
                return "";
            }
        }
        return prologue.strip();
    }

    private static String withoutFrontMatter(String body, String frontMatter) {
        String stripped = body.strip();
        return !frontMatter.isEmpty() && stripped.startsWith(frontMatter)
            ? stripped.substring(frontMatter.length())
            : body;
    }

    private static String withFrontMatter(String frontMatter, String body) {
        return frontMatter.isEmpty() ? body : frontMatter + "\n\n" + body;
    }

    /**
     * Every region's body for {@code serviceKey}, as (region id → body) in output order.
     *
     * <p>The input a {@code WholeFileMerge} works from. Grouped by region for the same reason
     * {@link ModuleSidecar#mergeFor} groups: a module compiled twice (main and test source sets) is one
     * contributor, not two, and its source sets are joined before the format-aware merge sees them
     * (issue #330). Regions with no body for this service are omitted rather than contributing an
     * empty document.
     *
     * @param serviceKey e.g. {@code "pr_agent"}
     * @param sidecars   all known module sidecars (current + siblings)
     */
    static List<Map.Entry<String, String>> contributionsFor(String serviceKey, List<ModuleSidecar> sidecars) {
        List<Map.Entry<String, String>> contributions = new ArrayList<>();
        for (Map.Entry<String, List<ModuleSidecar>> region : groupByRegion(sidecars).entrySet()) {
            List<String> parts = new ArrayList<>();
            for (ModuleSidecar s : region.getValue()) {
                String body = s.getBodies().get(serviceKey);
                if (body != null && !body.isBlank()) {
                    parts.add(body);
                }
            }
            if (!parts.isEmpty()) {
                contributions.add(new AbstractMap.SimpleEntry<>(region.getKey(), parts.get(0)));
                // A second source set of the same module renders the same whole-file document from
                // a different slice of the code; both are offered so the merge unions them.
                for (int i = 1; i < parts.size(); i++) {
                    contributions.add(new AbstractMap.SimpleEntry<>(region.getKey(), parts.get(i)));
                }
            }
        }
        return contributions;
    }

    /**
     * Merges the <em>module's own</em> nested output for {@code serviceKey} across the source sets
     * of one region, main first (sidecars arrive sorted by filename, and the main source set's id
     * carries no suffix). Returns {@code ""} when no sidecar in the region has a body — the caller
     * then falls back to this compilation's freshly built content.
     */
    static String mergeModuleBodies(String serviceKey, List<ModuleSidecar> sidecars, String regionId) {
        List<ModuleSidecar> region = new ArrayList<>();
        for (ModuleSidecar s : sidecars) {
            if (s.getRegionId().equals(regionId)) region.add(s);
        }
        List<String> bodies = moduleBodiesOf(serviceKey, region);
        // Two source sets rendering one front-matter file must still give it one header (issue #684).
        String frontMatter = sharedFrontMatter(bodies);
        List<String> parts = new ArrayList<>();
        for (String body : bodies) {
            parts.add(withoutFrontMatter(body, frontMatter).strip());
        }
        return parts.isEmpty() ? "" : withFrontMatter(frontMatter, joinSourceSets(serviceKey, parts));
    }

    /** Sidecars grouped by region id, preserving the (filename-sorted) encounter order. */
    private static Map<String, List<ModuleSidecar>> groupByRegion(List<ModuleSidecar> sidecars) {
        Map<String, List<ModuleSidecar>> byRegion = new LinkedHashMap<>();
        for (ModuleSidecar s : sidecars) {
            byRegion.computeIfAbsent(s.getRegionId(), k -> new ArrayList<>()).add(s);
        }
        return byRegion;
    }
}
