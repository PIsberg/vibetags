package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The bounded upward directory walk behind module-root resolution and reactor detection.
 *
 * <p>Both run inside somebody else's javac on every compile, so the walk is capped. The cap is far
 * above any real build's depth and must not shrink: a project nested more deeply than it would
 * silently lose its module root, and with it its sidecar identity.
 */
public final class Ancestors {

    /** Directories the walk examines, the starting one included. */
    static final int MAX_LEVELS = 64;

    private Ancestors() {
    }

    /**
     * The first of {@code from} and its ancestors, nearest first, that satisfies {@code test};
     * empty when none of the first {@value #MAX_LEVELS} does, or {@code from} is {@code null}.
     */
    public static Optional<Path> firstMatching(@Nullable Path from, Predicate<Path> test) {
        Path current = from;
        for (int i = 0; current != null && i < MAX_LEVELS; i++, current = current.getParent()) {
            if (test.test(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }
}
