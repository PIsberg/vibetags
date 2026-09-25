package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * How the bodies that several source sets of one module rendered for a file are joined into one.
 *
 * <p>Blank-line concatenation keeps every guardrail, so this is not a correctness hook the way
 * {@link YamlMergeShape} is. It exists because concatenation keeps the scaffolding too: a structured
 * body stacked twice repeats its header, its wrapper and the rule sentence under every section, in
 * a file an agent loads on every session (issue #839).
 *
 * <p>Implementations parse output rendered by a renderer in this package, so the shape is known
 * exactly. When a body does not have that shape, return {@code null} and the caller keeps the
 * concatenation, which is longer but loses nothing.
 */
@FunctionalInterface
public interface SourceSetMerge {

    /**
     * Joins one module's bodies for a service.
     *
     * @param bodies the rendered bodies, main source set first, each stripped of surrounding blank lines
     * @return the single body, or {@code null} to decline
     */
    @Nullable String merge(List<String> bodies);
}
