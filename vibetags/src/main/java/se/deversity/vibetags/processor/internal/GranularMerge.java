package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.content.GranularContribution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every module's contribution to each granular rule file, folded into the one contribution the
 * writer renders. The granular counterpart of {@link ModuleSidecar#mergeFor}, reached through
 * {@link ModuleSidecar#mergeGranular} and {@link ModuleSidecar#mergeModuleGranular}.
 *
 * <p>Two folds happen, in this order:
 *
 * <ol>
 *   <li><strong>By region.</strong> A rule file is not owned by one module: a role declared in a
 *       reactor-root {@code .vibetags-roles} routes classes from several modules into one file
 *       (<a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>). A module
 *       compiled once per source set is one contributor, its source sets concatenated; several
 *       regions are wrapped in {@code VIBETAGS-MODULE} sub-markers when the caller asks for them.</li>
 *   <li><strong>By case-folded stem.</strong> Stems that differ only in capitalisation share one
 *       physical file on a case-insensitive filesystem, and merged separately whichever module
 *       compiled last replaced it with its own element alone
 *       (<a href="https://github.com/PIsberg/vibetags/issues/525">issue #525</a>). Every spelling
 *       in a group gets the same contribution, so whichever name the filesystem keeps, the file
 *       carries every element. A region that records one contribution under two spellings, as a
 *       module whose own plan already folded the collision does, contributes it once.</li>
 * </ol>
 *
 * <p>Order is fixed by the order the sidecars are given in (filename-sorted by the reader), so
 * which module compiled last cannot change the file. Inside a case-fold group regions are listed
 * in the order the group's stems were first met, then per stem; {@code GranularMergeShapeTest}
 * pins that.
 */
final class GranularMerge {

    /** stem -> region -> the contributions of that region's source sets, in encounter order. */
    private final Map<String, Map<String, List<GranularContribution>>> byStem = new LinkedHashMap<>();

    private GranularMerge() { }

    /**
     * The merged contribution for every stem some sidecar contributed to.
     *
     * @param units      which of a sidecar's contribution maps to read
     * @param subMarkers whether several regions are wrapped in module sub-markers
     * @param regionOnly when non-null, only sidecars of that region take part
     */
    static Map<String, GranularContribution> of(List<ModuleSidecar> sidecars,
                                                Function<ModuleSidecar, Map<String, GranularContribution>> units,
                                                boolean subMarkers,
                                                @Nullable String regionOnly) {
        GranularMerge merge = new GranularMerge();
        for (ModuleSidecar sidecar : sidecars) {
            if (regionOnly == null || sidecar.getRegionId().equals(regionOnly)) {
                merge.collect(sidecar.getRegionId(), units.apply(sidecar));
            }
        }
        return merge.merged(subMarkers);
    }

    private void collect(String region, Map<String, GranularContribution> units) {
        units.forEach((stem, contribution) -> {
            if (!contribution.isEmpty()) {
                byStem.computeIfAbsent(stem, k -> new LinkedHashMap<>())
                    .computeIfAbsent(region, k -> new ArrayList<>())
                    .add(contribution);
            }
        });
    }

    private Map<String, GranularContribution> merged(boolean subMarkers) {
        Map<String, GranularContribution> merged = new LinkedHashMap<>();
        for (Map<String, Map<String, List<GranularContribution>>> spellings : caseFoldGroups()) {
            GranularContribution shared = shared(regionsOf(spellings.values()), subMarkers);
            for (String stem : spellings.keySet()) {
                merged.put(stem, shared);
            }
        }
        return merged;
    }

    /**
     * {@link #byStem} split by case-folded stem: each group maps its spellings to their regions,
     * groups and spellings in first-met order.
     */
    private Collection<Map<String, Map<String, List<GranularContribution>>>> caseFoldGroups() {
        Map<String, Map<String, Map<String, List<GranularContribution>>>> groups = new LinkedHashMap<>();
        byStem.forEach((stem, regions) -> groups
            .computeIfAbsent(stem.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>())
            .put(stem, regions));
        return groups.values();
    }

    /** region -> its distinct contributions under any spelling of one stem. */
    private static Map<String, List<GranularContribution>> regionsOf(
            Collection<Map<String, List<GranularContribution>>> spellings) {
        Map<String, List<GranularContribution>> byRegion = new LinkedHashMap<>();
        for (Map<String, List<GranularContribution>> regions : spellings) {
            regions.forEach((region, contributions) -> {
                List<GranularContribution> kept = byRegion.computeIfAbsent(region, k -> new ArrayList<>());
                contributions.stream().filter(c -> !kept.contains(c)).forEach(kept::add);
            });
        }
        return byRegion;
    }

    /**
     * One file's contribution: globs unioned and bodies joined in region order, and the heading
     * and description joined the way the single-module fold joins them, so a file two modules
     * write under two spellings of one name is headed the same whichever compiled last
     * (<a href="https://github.com/PIsberg/vibetags/issues/579">issue #579</a>). Names and
     * subjects are de-duplicated by value: a role file spanning modules names the role once.
     */
    private static GranularContribution shared(Map<String, List<GranularContribution>> byRegion,
                                               boolean subMarkers) {
        List<GranularContribution> all = byRegion.values().stream().flatMap(List::stream).toList();
        Set<String> globs = all.stream().flatMap(c -> c.globs().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<GranularContribution> named = all.stream().filter(GranularContribution::isNamed).toList();
        String displayName = named.stream().map(GranularContribution::displayName).distinct()
            .collect(Collectors.joining(", "));
        String description = named.isEmpty() ? ""
            : GranularContribution.DESCRIPTION_PREFIX + named.stream().map(GranularContribution::subject)
                .distinct().collect(Collectors.joining(", "));
        return new GranularContribution(new ArrayList<>(globs), body(byRegion, subMarkers),
            displayName, description);
    }

    /** One region, or no sub-markers asked for: the parts joined. Several: each region wrapped. */
    private static String body(Map<String, List<GranularContribution>> byRegion, boolean subMarkers) {
        if (!subMarkers || byRegion.size() < ModuleSidecar.MULTI_MODULE_THRESHOLD) {
            return byRegion.values().stream().map(GranularMerge::joined).collect(Collectors.joining("\n\n"));
        }
        StringBuilder wrapped = new StringBuilder();
        byRegion.forEach((region, contributions) ->
            wrapped.append(ModuleSidecar.SubMarkers.MARKDOWN.wrap(region, joined(contributions))));
        return wrapped.toString().strip();
    }

    /** One region's source sets, each body stripped, separated by a blank line. */
    private static String joined(List<GranularContribution> contributions) {
        return contributions.stream().map(c -> c.body().strip()).collect(Collectors.joining("\n\n"));
    }
}
