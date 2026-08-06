package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One compilation's contribution to one granular rule file: the globs its frontmatter needs and the
 * rendered body between the markers.
 *
 * <p>Exists because a granular rule file is <em>not</em> always owned by a single module. A role
 * declared in a reactor-root {@code .vibetags-roles} can match classes in several modules, and every
 * one of those modules resolves the same output path — so each compile replaced the file with only
 * its own classes and the rest vanished silently
 * (<a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>). Two source sets of one
 * module collide the same way. Recording the contribution rather than the finished file is what lets
 * {@link se.deversity.vibetags.processor.internal.ModuleSidecar} merge them instead.
 *
 * <p>Persisted in the module sidecar, so the wire form has to survive a round-trip through a
 * {@code key=value} line: {@link #serialize()} puts the tab-joined globs on the first line and the
 * body underneath. Globs are single-line by construction (they come from a config line or from a
 * class name), so the first newline is an unambiguous separator.
 */
public record GranularContribution(List<String> globs, String body) {

    /** Separator between globs on the serialized header line; illegal inside a glob pattern. */
    private static final String GLOB_SEPARATOR = "\t";

    /** Defensive copy: a contribution is handed to the merge and must not change underneath it. */
    public GranularContribution {
        globs = List.copyOf(globs);
    }

    /** True when this contribution has no rules to add — nothing for the merge to keep. */
    public boolean isEmpty() {
        return body.isBlank();
    }

    /** Wire form: tab-joined globs, newline, body. */
    public String serialize() {
        return String.join(GLOB_SEPARATOR, globs) + "\n" + body;
    }

    /**
     * Parses {@link #serialize()}'s output, or returns {@code null} when the value has no header
     * line at all. A malformed entry is dropped rather than guessed at: the caller then falls back
     * to the rendering the compiling module produced itself, which is the pre-merge behaviour.
     */
    public static @Nullable GranularContribution parse(String serialized) {
        int newline = serialized.indexOf('\n');
        if (newline < 0) {
            return null;
        }
        List<String> globs = new ArrayList<>();
        for (String glob : serialized.substring(0, newline).split(GLOB_SEPARATOR, -1)) {
            if (!glob.isBlank()) {
                globs.add(glob);
            }
        }
        return new GranularContribution(globs, serialized.substring(newline + 1));
    }
}
