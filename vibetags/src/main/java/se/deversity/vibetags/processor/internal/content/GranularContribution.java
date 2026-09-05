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
public record GranularContribution(List<String> globs, String body, String displayName, String description) {

    /** Prefix every planned description starts with; what the merge joins subjects behind. */
    public static final String DESCRIPTION_PREFIX = "AI rules for ";

    /** A contribution with no naming of its own; the writer falls back to its local plan. */
    public GranularContribution(List<String> globs, String body) {
        this(globs, body, "", "");
    }

    /** The description's subject: what follows {@link #DESCRIPTION_PREFIX}, or the whole text. */
    public String subject() {
        return description.startsWith(DESCRIPTION_PREFIX)
            ? description.substring(DESCRIPTION_PREFIX.length()) : description;
    }

    /** Whether this contribution names itself (heading and description travel with it). */
    public boolean isNamed() {
        return !displayName.isEmpty();
    }

    /**
     * The heading name and description, serialized for the sidecar under their own key.
     *
     * <p>Kept apart from {@link #serialize()} on purpose: that value's shape is what an older
     * sibling's {@link #parse} reads, and a field added to it would be read back as part of the
     * glob line or the body. A key of its own is one an older reader recognises as reserved and
     * leaves unstored.
     */
    public String serializeNaming() {
        return displayName + "\n" + description;
    }

    /** {@code this}, named as {@code serializedNaming} says; unchanged when it is malformed. */
    public GranularContribution withNaming(String serializedNaming) {
        int newline = serializedNaming.indexOf('\n');
        if (newline < 0) {
            return this;
        }
        return new GranularContribution(globs, body,
            serializedNaming.substring(0, newline), serializedNaming.substring(newline + 1));
    }

    /** {@code this} under another name; the fold across modules builds its joined heading here. */
    public GranularContribution named(String newDisplayName, String newDescription) {
        return new GranularContribution(globs, body, newDisplayName, newDescription);
    }


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
