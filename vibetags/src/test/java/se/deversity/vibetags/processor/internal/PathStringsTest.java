package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Paths as they appear in generated files, sidecars, the write cache and diagnostics: forward
 * slashes on every OS.
 *
 * <p>Those strings are compared across machines. A sidecar written on Windows is read by a Linux CI
 * job, the cache key of one checkout is matched against the next, and a module id is part of every
 * VIBETAGS-MODULE marker in committed output. A backslash that reaches any of them makes the same
 * repository generate different bytes depending on who built it.
 */
class PathStringsTest {

    @Test
    void backslashesBecomeForwardSlashes() {
        assertEquals("a/b/c", PathStrings.forwardSlashes("a\\b\\c"));
    }

    @Test
    void aPathAlreadyInForwardSlashesIsUnchanged() {
        assertEquals("a/b/c", PathStrings.forwardSlashes("a/b/c"));
        assertEquals("", PathStrings.forwardSlashes(""));
    }

    @Test
    void mixedSeparatorsAreAllNormalised() {
        assertEquals("C:/work/a/b", PathStrings.forwardSlashes("C:\\work/a\\b"));
    }

    @Test
    void aPathRendersWithForwardSlashesOnEveryOs() {
        assertEquals("a/b/c", PathStrings.forwardSlashes(Path.of("a", "b", "c")));
    }

    @Test
    void relativeIsThePathBelowTheBaseInForwardSlashes(@TempDir Path base) {
        assertEquals("x/y", PathStrings.relative(base, base.resolve("x").resolve("y")));
    }

    @Test
    void relativeOfTheBaseItselfIsEmpty(@TempDir Path base) {
        assertEquals("", PathStrings.relative(base, base));
    }

    @Test
    void relativeClimbsWithDotDotForAPathOutsideTheBase(@TempDir Path tmp) {
        assertEquals("../other", PathStrings.relative(tmp.resolve("base"), tmp.resolve("other")));
    }

    @Test
    void relativeDoesNotSwallowPathsThatCannotBeRelated(@TempDir Path base) {
        // Path.relativize refuses to relate an absolute path and a relative one. Callers that can
        // meet such a pair catch the exception themselves; a helper that hid it would turn their
        // explicit fallback into a wrong answer.
        assertThrows(IllegalArgumentException.class, () -> PathStrings.relative(base, Path.of("x")));
    }
}
