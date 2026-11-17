package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bounded upward walk shared by module-root resolution and reactor detection.
 *
 * <p>Both walks run inside somebody else's javac on every compile. The bound keeps a pathological
 * path from making that walk unbounded, and it must not shrink: a project nested more deeply than
 * the bound would silently lose its module root, and with it its sidecar identity.
 */
class AncestorsTest {

    /** {@code d0/d1/.../d(n-1)}: a relative path with {@code n} names and no filesystem behind it. */
    private static Path deep(int names) {
        Path p = Path.of("d0");
        for (int i = 1; i < names; i++) {
            p = p.resolve("d" + i);
        }
        return p;
    }

    @Test
    void theStartingDirectoryItselfCounts() {
        Path start = deep(5);
        assertEquals(Optional.of(start), Ancestors.firstMatching(start, p -> p.equals(start)));
    }

    @Test
    void theNearestMatchingAncestorWins() {
        Path start = deep(5);
        // d0/d1 and d0 both match; d0/d1 is nearer.
        assertEquals(Optional.of(deep(2)), Ancestors.firstMatching(start, p -> p.getNameCount() <= 2));
    }

    @Test
    void noMatchUpToTheTopIsEmpty() {
        assertEquals(Optional.empty(), Ancestors.firstMatching(deep(5), p -> false));
    }

    @Test
    void aNullStartIsEmpty() {
        assertEquals(Optional.empty(), Ancestors.firstMatching(null, p -> true));
    }

    @Test
    void theWalkChecksTheStartAndSixtyThreeAncestors() {
        // 64 directories in all: the bound both callers had as MAX_WALK_UP = 64 before sharing it.
        Path start = deep(80);
        assertEquals(Optional.of(deep(80 - 63)),
            Ancestors.firstMatching(start, p -> p.getNameCount() == 80 - 63),
            "an ancestor 63 levels up is within the bound");
        assertEquals(Optional.empty(),
            Ancestors.firstMatching(start, p -> p.getNameCount() == 80 - 64),
            "an ancestor 64 levels up is past it");
    }
}
