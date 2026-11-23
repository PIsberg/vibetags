package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.SupersededRegions.Region;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SupersededRegions} on plain values: no sidecar files, no mtimes to stamp.
 *
 * <p>{@code SupersededAncestorRegionTest} holds the same rule through {@code ModuleSidecar.readAll}
 * with real files, which is the right place for what a build actually leaves on disk. It cannot
 * reach a region whose timestamp could not be read, because a file that loads has an mtime, and
 * that is the case the rule's own comments are most careful about. Here a timestamp is an argument.
 */
class SupersededRegionsTest {

    private static final long UNREADABLE = Long.MAX_VALUE;

    private static Region region(String id, String path, long writtenAt, String... elements) {
        return new Region(id, path, Set.of(elements), writtenAt);
    }

    private static Set<String> retired(Region... regions) {
        return SupersededRegions.among(List.of(regions));
    }

    @Test
    @DisplayName("a region its descendants know strictly more than is retired, however fresh it is")
    void strictlyMoreBelowRetiresTheAncestorWhateverTheTimes() {
        assertEquals(Set.of("_root_"), retired(
            region("_root_", "", 2_000, "A"),
            region("app", "app", 1_000, "A", "B")));
        assertEquals(Set.of("_root_"), retired(
            region("_root_", "", UNREADABLE, "A"),
            region("app", "app", 1_000, "A", "B")),
            "containment consults no timestamp, so an unreadable one changes nothing");
    }

    @Test
    @DisplayName("equal claims above and below go to the fresher, and to the nested module on a tie")
    void equalClaimsGoToTheFresher() {
        assertEquals(Set.of("app"), retired(region("_root_", "", 2_000, "A"), region("app", "app", 1_000, "A")));
        assertEquals(Set.of("_root_"), retired(region("_root_", "", 1_000, "A"), region("app", "app", 2_000, "A")));
        assertEquals(Set.of("_root_"), retired(region("_root_", "", 1_000, "A"), region("app", "app", 1_000, "A")));
    }

    @Test
    @DisplayName("an ancestor that keeps an element of its own is never retired")
    void anAncestorWithAnElementOfItsOwnSurvives() {
        assertEquals(Set.of(), retired(
            region("_root_", "", 1_000, "A", "RootOnly"),
            region("app", "app", 2_000, "A", "B")));
    }

    @Test
    @DisplayName("two regions on one path: the staler goes, then the root identity, then the higher id")
    void twoRegionsOnOnePathResolveToOne() {
        assertEquals(Set.of("old"), retired(region("old", "x", 1_000, "A"), region("new", "x", 2_000, "A")));
        assertEquals(Set.of("_root_"), retired(region("_root_", "", 1_000, "A"), region("named", "", 1_000, "A")));
        assertEquals(Set.of("b"), retired(region("a", "x", 1_000, "A"), region("b", "x", 1_000, "A")));
        assertEquals(Set.of(), retired(region("a", "x", 1_000, "A"), region("b", "x", 2_000, "B")),
            "two modules that land on one path but claim different elements are both real");
    }

    @Test
    @DisplayName("on one path, an unreadable timestamp retires nothing in either direction")
    void anUnreadableTimestampOnOnePathRetiresNothing() {
        assertEquals(Set.of(), retired(region("a", "x", UNREADABLE, "A"), region("b", "x", 1_000, "A")));
        assertEquals(Set.of(), retired(region("a", "x", 1_000, "A"), region("b", "x", UNREADABLE, "A")));
    }

    /**
     * An unreadable mtime reads as {@code Long.MAX_VALUE}, the freshest value there is. On one path
     * the rule already refused to let that retire a region. Above and below it did not (#1158):
     * a region whose own sidecar was fine was retired because a relative's timestamp was missing.
     */
    @Test
    @DisplayName("above and below, a region whose timestamp could not be read retires nobody")
    void anUnreadableTimestampAboveOrBelowRetiresNothing() {
        assertEquals(Set.of(), retired(region("_root_", "", UNREADABLE, "A"), region("app", "app", 1_000, "A")),
            "an ancestor of unknown age must not retire the module nested inside it");
        assertEquals(Set.of(), retired(region("_root_", "", 1_000, "A"), region("app", "app", UNREADABLE, "A")),
            "a nested module of unknown age must not retire its ancestor");
    }

    @Test
    @DisplayName("siblings are never in a path relation, and a region with no elements says nothing")
    void siblingsAndEmptyRegionsAreLeftAlone() {
        assertEquals(Set.of(), retired(region("a", "a", 1_000, "A"), region("b", "b", 2_000, "A")));
        assertEquals(Set.of(), retired(region("_root_", "", 1_000), region("app", "app", 2_000, "A")));
        assertEquals(Set.of(), retired(region("ab", "ab", 1_000, "A"), region("a", "a", 2_000, "A")),
            "a path that merely starts with another's characters is not nested under it");
    }

    @Test
    @DisplayName("coverage runs down the whole tree: only the most specific claimant is left")
    void coverageIsTransitive() {
        assertEquals(Set.of("_root_", "a"), retired(
            region("_root_", "", 1_000, "A"),
            region("a", "a", 1_000, "A"),
            region("a_b", "a/b", 1_000, "A")));
    }

    @Test
    @DisplayName("a region's sidecars join into one: the first path, every claim, the freshest time")
    void sourceSetsJoinIntoOneRegion() {
        Region joined = region("core", "core", 1_000, "A").joinedWith(region("core", "elsewhere", 3_000, "ATest"));

        assertEquals(new Region("core", "core", Set.of("A", "ATest"), 3_000), joined);
    }
}
