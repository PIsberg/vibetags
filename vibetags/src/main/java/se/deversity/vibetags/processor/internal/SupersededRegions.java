package se.deversity.vibetags.processor.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which of several regions are leftovers: every region whose annotated elements are all accounted
 * for by a fresher region above or below it in the module tree. {@code ModuleSidecar.readAll}
 * leaves those out of the merge and, when it prunes, deletes their sidecars.
 *
 * <p>An annotated element belongs to exactly one module: its source file lives in exactly one
 * module directory. Two regions claiming the same element are therefore the same sources read
 * twice under two identities, and one of the two is a leftover. Which one is settled by
 * <em>freshness</em>, not by depth: the sidecar a build wrote more recently is the one that
 * describes the tree as it is now.
 *
 * <p>Both directions happen, and the difference is not cosmetic:
 *
 * <ul>
 *   <li><strong>Sources move down into a subproject.</strong> A Gradle repository with one
 *       included subproject below the VibeTags root leaves {@code .vibetags-mod-_root_}
 *       ({@code modulePath=}) beside {@code .vibetags-mod-app}. The ordinary stale check cannot
 *       retire the first — its module path is the root directory, which always exists — so
 *       every build emitted both regions, byte-identical, into every generated file.</li>
 *   <li><strong>Sources move up out of one.</strong> {@code app/} survives the move as a
 *       directory (resources, an emptied build file), so the stale check cannot retire
 *       {@code .vibetags-mod-app} either, and now it is the <em>nested</em> sidecar that is the
 *       leftover. Retiring the root region here would be worse than the duplication: the
 *       aggregate is assembled from sidecars, so every later edit would render into a region
 *       that is then dropped and the file would freeze on the departed module's last text.</li>
 * </ul>
 *
 * <p>A third shape has no nesting at all: two regions naming the <em>same</em> directory,
 * which is what a Gradle {@code settings.gradle} carrying {@code rootProject.name='x'} beside
 * {@code include 'x'} produces, and what any two compilations produce when both resolve a
 * root-like module path. {@code isNestedUnder} is false in both directions for equal paths,
 * so before this was handled both regions survived and every generated file stated the same
 * guardrails twice. {@link #yieldsOnSamePath} settles that pair, again by freshness.
 *
 * <p><strong>Strict containment is decided first, and without timestamps.</strong> A region
 * whose every element is claimed by regions nested inside it, where those regions between them
 * also know at least one element it never had, is retired on that ground alone. The strictness
 * matters: equal element sets are exactly what the second case above also looks like, and
 * there it is the nested sidecar that is the leftover, so equality is left to freshness. Freshness used to gate this, and that left the reported case unrepaired in both
 * directions at once (#621): the ancestor was the more recently written file, so its
 * descendant was not allowed to retire it, and the ancestor's smaller element set could
 * not cover the descendant's, so neither went. Both survived and every element they
 * shared was written twice, byte-identical, into every generated file.
 *
 * <p>Recency is not evidence of correctness here, and arguably the reverse. The ancestor
 * identity is the <em>fallback</em> a compilation takes when it cannot resolve its module
 * root from the round's sources, so it is the identity most likely to be simultaneously
 * wrong and freshly written. A nested identity is only ever produced by resolving a real
 * build file.
 *
 * <p>Freshness still settles the two relations where containment cannot say which identity
 * is the leftover: a nested region against an ancestor that outlived it, and two regions
 * on one path. A region already retired by containment takes no part in those decisions,
 * because two regions holding equal element sets each cover the other and dropping both
 * would take every guardrail with them. Ties go to the more specific module, as before.
 *
 * <p>Conservative in every other respect. A region is dropped only when the surviving
 * regions cover <em>all</em> of its elements, so a reactor root that compiles sources of
 * its own keeps at least one element no submodule has and keeps its region. Sibling
 * modules are never in a path relation. A sidecar recording no element ids says nothing
 * and is left alone, as is one whose timestamp cannot be read.
 */
final class SupersededRegions {

    private SupersededRegions() { }

    /**
     * One region as this rule sees it. A region spans one sidecar per source set, and this is all
     * of them taken together.
     *
     * @param id        the region id its sidecars share
     * @param path      its module path in comparable form: forward slashes, {@code ""} for the root
     * @param elements  the annotated element ids its sidecars record
     * @param writtenAt the mtime in millis of its freshest sidecar, or {@link Long#MAX_VALUE} when
     *                  one could not be read
     */
    record Region(String id, String path, Set<String> elements, long writtenAt) {

        /** Defensive copy: the rule compares these sets while the caller goes on to change its sidecars. */
        Region {
            elements = Set.copyOf(elements);
        }

        /** This region with one more of its sidecars: the first path, both claims, the fresher time. */
        Region joinedWith(Region sameRegion) {
            Set<String> both = new LinkedHashSet<>(elements);
            both.addAll(sameRegion.elements);
            return new Region(id, path, both, Math.max(writtenAt, sameRegion.writtenAt));
        }
    }

    /** The ids of the regions to retire; empty when every region speaks for sources of its own. */
    static Set<String> among(Collection<Region> regions) {
        Set<String> coveredFromBelow = coveredByTheirDescendants(regions);
        // A region already retired by containment is not allowed to retire anything by freshness.
        // Without that, two regions holding equal element sets each cover the other and both are
        // dropped, taking every guardrail with them. Ties go to the more specific module.
        Set<String> superseded = new LinkedHashSet<>(coveredFromBelow);
        for (Region region : regions) {
            if (!coveredFromBelow.contains(region.id()) && yieldsToAFresherRelative(region, regions, coveredFromBelow)) {
                superseded.add(region.id());
            }
        }
        return superseded;
    }

    /**
     * Containment by the modules nested inside a region, decided without consulting timestamps.
     *
     * <p>A region whose every element is also claimed by regions below it is the same sources
     * under a less specific identity, and that is true whenever it is true. Gating it on freshness
     * left the reported case unrepaired in both directions at once: the ancestor was the newer
     * file so its descendant could not retire it, and the ancestor's smaller element set could not
     * cover the descendant's, so neither was dropped and every shared element was written twice
     * (#621). Recency is no evidence here. The ancestor identity is the <em>fallback</em> a
     * compilation takes when it cannot resolve its module root from the round's sources, so it is
     * exactly the identity most likely to be both wrong and freshly written.
     *
     * <p>The safety is containment itself: a reactor root that compiles sources of its own keeps
     * at least one element no submodule has, fails containment, and keeps its region.
     */
    private static Set<String> coveredByTheirDescendants(Collection<Region> regions) {
        Set<String> covered = new LinkedHashSet<>();
        for (Region region : regions) {
            if (region.elements().isEmpty()) {
                continue; // nothing to compare against - keep it
            }
            Set<String> claimedBelow = new LinkedHashSet<>();
            for (Region other : regions) {
                if (!other.id().equals(region.id()) && isNestedUnder(other.path(), region.path())) {
                    claimedBelow.addAll(other.elements());
                }
            }
            // Strictly more, not merely as much. Equal element sets are genuinely ambiguous: they
            // are what "the sources moved up out of the subproject" also looks like, and there it
            // is the nested sidecar that is the leftover and the root that is real. Only freshness
            // separates those two, so equality is left to yieldsToAFresherRelative. A descendant
            // that knows an element the ancestor never had cannot be the stale one.
            if (claimedBelow.containsAll(region.elements()) && !region.elements().containsAll(claimedBelow)) {
                covered.add(region.id());
            }
        }
        return covered;
    }

    /**
     * The relations that genuinely need a tie-break, which are the ones where containment alone
     * cannot say which identity is the leftover: whether fresher regions above, below or beside
     * {@code region} claim every element it has.
     */
    private static boolean yieldsToAFresherRelative(Region region, Collection<Region> regions,
                                                    Set<String> coveredFromBelow) {
        if (region.elements().isEmpty()) {
            return false; // nothing to compare against - keep it
        }
        if (region.writtenAt() == Long.MAX_VALUE) {
            return false; // unreadable timestamp - never retire on a guess
        }
        Set<String> claimed = new LinkedHashSet<>();
        for (Region other : regions) {
            if (!other.id().equals(region.id()) && !coveredFromBelow.contains(other.id()) && outranks(other, region)) {
                claimed.addAll(other.elements());
            }
        }
        return !claimed.isEmpty() && claimed.containsAll(region.elements());
    }

    /** Whether what {@code other} claims counts against {@code region}, by where the two stand. */
    private static boolean outranks(Region other, Region region) {
        if (isNestedUnder(other.path(), region.path())) {
            // A descendant retires its ancestor on equal timestamps too (see the class javadoc).
            // Containment has already taken the cases where the descendants know strictly more;
            // what is left here is equal element sets, where only freshness can say which
            // identity is the leftover.
            return other.writtenAt() >= region.writtenAt();
        }
        if (isNestedUnder(region.path(), other.path())) {
            return other.writtenAt() > region.writtenAt();
        }
        return region.path().equals(other.path())
            && yieldsOnSamePath(region.id(), other.id(), region.writtenAt(), other.writtenAt());
    }

    /**
     * Whether {@code region} gives way to {@code other} when both name the <em>same</em>
     * directory. Neither is nested under the other, so the ancestor rule cannot separate them,
     * and without this they both survive and every generated file states the same guardrails
     * twice under two {@code VIBETAGS-MODULE} markers.
     *
     * <p>Freshness decides first, as everywhere else here. A tie goes to the named module over
     * the root identity, matching the ancestor rule's preference for the more specific of two
     * identities, and otherwise to the lower region id. The relation is asymmetric for any two
     * distinct regions, so exactly one of the pair is retired and every build retires the same
     * one. Containment is still required by the caller, which is what keeps this from eating a
     * real module: {@code ""} is also the catch-all path for a compilation root that cannot be
     * relativized, and two genuinely different modules that land on it claim different elements.
     */
    private static boolean yieldsOnSamePath(String region, String other,
                                            long writtenAt, long otherWrittenAt) {
        // An unreadable mtime reads as Long.MAX_VALUE, i.e. maximally fresh. It must never be
        // the reason a region is retired: duplication is recoverable, a dropped live region is
        // not. The caller already refuses to retire a region whose own mtime is unreadable.
        if (otherWrittenAt == Long.MAX_VALUE) return false;
        if (otherWrittenAt != writtenAt) return otherWrittenAt > writtenAt;
        boolean regionIsRoot = isRootRegionId(region);
        if (regionIsRoot != isRootRegionId(other)) return regionIsRoot;
        return region.compareTo(other) > 0;
    }

    /** True for the identity a compilation gets when it resolves to the VibeTags root itself. */
    private static boolean isRootRegionId(String regionId) {
        return regionId.isEmpty() || "_root_".equals(regionId);
    }

    /** True when {@code candidate} names a directory strictly below {@code ancestor}. */
    private static boolean isNestedUnder(String candidate, String ancestor) {
        if (candidate.isEmpty() || candidate.equals(ancestor)) return false;
        return ancestor.isEmpty() || candidate.startsWith(ancestor + "/");
    }
}
