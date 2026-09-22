package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

/**
 * Everything the table-driven consumers need to know about one generated output. One entry of
 * {@link PlatformDescriptors#ALL}; the reasons for the shape are on that class.
 *
 * @param serviceKey    the logical service key, e.g. {@code "cursor"}. The key of every
 *                      service-indexed map in the processor
 * @param relativePath  where the output lives, relative to the project root, always with
 *                      {@code /} separators. A {@code String} and not a {@code Path} because
 *                      this table is read by the rendering layer, which has no project root
 * @param kind          whether the path is a file or a directory of per-element rule files
 * @param implicitParent the service whose activation activates this one, or {@code null} when the
 *                      path's own presence on disk is the opt-in. Exactly the keys with a parent
 *                      are the keys outside {@code ServiceRegistry.optInKeys()}
 * @param platform      the {@link Platform} that renders it, or {@code null} for a key that is a
 *                      marker and nothing else ({@code root_index})
 * @param renderer      the renderer for that platform, or {@code null} when there is none
 * @param ignoreLabel   the tool's display name in an exclusion file's header comment, or
 *                      {@code null} when this output is not one of those files
 * @param globSyntax    true when {@code @AIIgnore} writes bare {@code .gitignore} globs into this
 *                      output rather than a prose sentence
 */
public record PlatformDescriptor(
        String serviceKey,
        String relativePath,
        Kind kind,
        @Nullable String implicitParent,
        @Nullable Platform platform,
        @Nullable PlatformRenderer renderer,
        @Nullable String ignoreLabel,
        boolean globSyntax) {

    /**
     * Whether a service's path is a file or a directory.
     *
     * <p>The file name cannot answer it: {@code .clinerules} is the {@code cline} file and the
     * {@code cline_granular} directory at the same path, and {@code isOptedIn} lets exactly one of
     * the two activate (issue #642).
     */
    public enum Kind {
        /** One file, written whole or between markers. */
        FILE,
        /** A directory of per-element rule files. */
        DIRECTORY
    }

    /**
     * True when the path's own presence on disk is the user's opt-in signal, which is every entry
     * that is not an implicit child of another one.
     */
    public boolean optIn() {
        return implicitParent == null;
    }
}
