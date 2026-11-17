package se.deversity.vibetags.processor.internal;

import java.nio.file.Path;

/**
 * Paths as they are written into generated files, sidecars, the write cache and diagnostics:
 * with forward slashes on every OS.
 *
 * <p>Those strings are compared across machines. A sidecar written on Windows is read by a Linux
 * build, one checkout's cache key is matched against the next, and a module id appears in every
 * {@code VIBETAGS-MODULE} marker of committed output. A backslash reaching any of them makes the
 * same repository generate different bytes depending on who built it, so every such string goes
 * through here rather than through its own {@code replace('\\', '/')}.
 */
public final class PathStrings {

    private PathStrings() {
    }

    /** {@code path} with every backslash turned into a forward slash. */
    public static String forwardSlashes(String path) {
        return path.replace('\\', '/');
    }

    /** {@code path}'s string form with forward slashes. */
    public static String forwardSlashes(Path path) {
        return forwardSlashes(path.toString());
    }

    /**
     * {@code path} relative to {@code base}, with forward slashes: {@code ""} for the base itself,
     * and a leading {@code ..} for a path outside it.
     *
     * @throws IllegalArgumentException as {@link Path#relativize} does, for paths it cannot relate
     *     (one absolute and one relative, or different Windows drives). Not caught here: a caller
     *     that can meet such a pair has its own fallback, and hiding the exception would bypass it.
     */
    public static String relative(Path base, Path path) {
        return forwardSlashes(base.relativize(path));
    }
}
