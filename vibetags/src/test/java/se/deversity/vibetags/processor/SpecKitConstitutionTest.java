package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Spec Kit constitution is filled in, and stays filled in.
 *
 * <p>It shipped as the unfilled template — `[PRINCIPLE_1_NAME]`, `[GOVERNANCE_RULES]` and the rest
 * (issue #787). Every `/speckit.*` command reads it, so an empty one does not fail: it quietly
 * presents the absence of rules as the absence of constraints, which is the worst way for a
 * governing document to be wrong.
 *
 * <p>Re-running the Spec Kit installer restores the template, so this is not a one-off fix. The
 * check is for the template's own placeholder shape rather than for particular wording, so the
 * constitution stays free to be rewritten without touching this test.
 */
class SpecKitConstitutionTest {

    /** `[UPPER_SNAKE]`, which is the shape every unfilled Spec Kit placeholder takes. */
    private static final Pattern PLACEHOLDER =
        Pattern.compile(Pattern.quote("[") + "[A-Z][A-Z0-9_]*" + Pattern.quote("]"));

    private static final Path CONSTITUTION =
        Path.of("..").resolve(".specify").resolve("memory").resolve("constitution.md");

    @Test
    @DisplayName("no unfilled Spec Kit placeholders remain")
    void theTemplateHasBeenFilledIn() throws IOException {
        assumeTrue(Files.isRegularFile(CONSTITUTION),
            "no .specify/memory/constitution.md — Spec Kit is not installed here, nothing to govern");

        String text = Files.readString(CONSTITUTION, StandardCharsets.UTF_8);

        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }

        assertEquals(List.of(), found,
            "the constitution is back to the Spec Kit template. Every /speckit.* command reads it, "
                + "and an unfilled one reads as 'this project has no constraints'. Fill it, or "
                + "delete the file so nothing reads it at all.");
    }

    @Test
    @DisplayName("it points at the authoritative documents rather than restating them")
    void itReferencesRatherThanDuplicates() throws IOException {
        assumeTrue(Files.isRegularFile(CONSTITUTION), "Spec Kit not installed here");

        String text = Files.readString(CONSTITUTION, StandardCharsets.UTF_8);

        assertTrue(text.contains("CLAUDE.md"),
            "the Tier-1 invariants live in CLAUDE.md; a constitution that does not point there is "
                + "either incomplete or is copying them, and a copy drifts");
        assertTrue(text.contains("LOAD-BEARING.md"),
            "the reasoning behind the invariants lives in docs/LOAD-BEARING.md");
    }
}
