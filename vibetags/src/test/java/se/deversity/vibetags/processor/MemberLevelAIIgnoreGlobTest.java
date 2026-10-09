package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An exclusion list holds file globs, and only a type is a file (#926).
 *
 * <p>A field annotated with {@code @AIIgnore} was written as {@code **}{@code /<fieldName>.java}: a
 * glob for a file that does not exist, or, if one with that name did, an unrelated file hidden from
 * the assistant. {@code examples/multimodule-indexed} shipped {@code **}{@code /cachedExpiryEpochDay.java}
 * in every ignore file it had. The member stays excluded wherever a platform can name it in prose;
 * the path lists leave it out, as {@code @AILocked} already does for {@code .aiexclude}.
 */
@Tag("e2e")
@DisplayName("Member-level @AIIgnore writes no file glob (#926)")
class MemberLevelAIIgnoreGlobTest {

    private static final String TYPE_AND_FIELD = """
        package com.example;
        import se.deversity.vibetags.annotations.AIIgnore;
        public class Ledger {
            @AIIgnore(reason = "cached derived value")
            private transient long cachedBalance;
        }
        """;

    private static final String IGNORED_TYPE = """
        package com.example;
        import se.deversity.vibetags.annotations.AIIgnore;
        @AIIgnore(reason = "generated")
        public class GeneratedTable {}
        """;

    /** Every exclusion file VibeTags writes globs into, opted in together. */
    private static final List<String> GLOB_FILES = List.of(".cursorignore", ".qwenignore", ".aiderignore",
        ".codeiumignore", ".rooignore", ".continueignore", ".augmentignore", ".devinignore", ".repomixignore",
        ".gitingestignore", ".gptignore", ".aiexclude");

    private static ProcessorTestHarness optedIn(Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn("GEMINI.md"); // .aiexclude renders only beside GEMINI.md or codex
        for (String f : GLOB_FILES) {
            h.touchOptIn(f);
        }
        Files.createDirectories(root.resolve(".greptile"));
        Files.writeString(root.resolve(".greptile/config.json"), "{}\n", StandardCharsets.UTF_8);
        return h;
    }

    @Test
    @DisplayName("a field's @AIIgnore puts no **/<field>.java line in any exclusion file")
    void aFieldIsNotWrittenAsAFileGlob(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = optedIn(root);
        h.addSource("com.example.Ledger", TYPE_AND_FIELD);
        h.addSource("com.example.GeneratedTable", IGNORED_TYPE);

        h.compile();

        for (String f : GLOB_FILES) {
            String content = h.readFile(f);
            assertFalse(content.contains("cachedBalance"),
                f + " names the field as if it were a file:\n" + content);
            assertTrue(content.contains("**/GeneratedTable.java"),
                f + " must still carry the ignored type's glob:\n" + content);
        }
        String greptile = h.readFile(".greptile/config.json");
        assertFalse(greptile.contains("cachedBalance"), "Greptile's ignorePatterns names the field:\n" + greptile);
        assertTrue(greptile.contains("**/GeneratedTable.java"), "Greptile keeps the type:\n" + greptile);
        // The member is still excluded where a platform can name it: GEMINI.md lists it in prose.
        assertTrue(h.readFile("GEMINI.md").contains("com.example.Ledger.cachedBalance"),
            "the field must still reach the prose outputs");
    }

    /**
     * With only a member ignored, there is nothing to put in a path list, so an exclusion file gets
     * no contribution at all rather than a header with no globs under it, which a reactor merge
     * would repeat once per module.
     */
    @Test
    @DisplayName("a module whose only @AIIgnore is a field contributes nothing to an ignore file")
    void onlyAMemberIgnoredContributesNothing(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = optedIn(root);
        h.addSource("com.example.Ledger", TYPE_AND_FIELD);

        h.compile();

        for (String f : GLOB_FILES) {
            String content = h.readFile(f);
            assertFalse(content.contains("exclusion list") || content.contains("strictly excluded"),
                f + " got a header with no globs under it:\n" + content);
        }
        assertEquals(List.of(), ignorePatternLines(h.readFile(".greptile/config.json")),
            "Greptile's ignorePatterns must hold no glob");
    }

    /**
     * A nested type is not a file either: its code is in the outermost type's file. It was written
     * as {@code **}{@code /Snapshot.java}, which hid an unrelated {@code Snapshot.java} if one existed
     * and never the code it was meant to hide. Like a member, it stays excluded in prose only. The
     * same holds for a locked nested type's {@code .aiexclude} line.
     *
     * <p>Nor does it exclude the outer type's file. That would hide the nested type from a glob-only
     * tool, and every unmarked line of {@code Ledger} with it; the owner chose under-exclusion as
     * the lesser harm (#940), and docs/ANNOTATIONS.md states the limit.
     */
    @Test
    @DisplayName("a nested type's @AIIgnore or @AILocked puts no **/<Nested>.java line in any exclusion file")
    void aNestedTypeIsNotWrittenAsAFileGlob(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = optedIn(root);
        h.addSource("com.example.Ledger", """
            package com.example;
            import se.deversity.vibetags.annotations.AIIgnore;
            import se.deversity.vibetags.annotations.AILocked;
            public class Ledger {
                @AIIgnore(reason = "derived cache")
                static class Snapshot {}
                @AILocked(reason = "wire format")
                public record Entry(long amount) {}
            }
            """);
        h.addSource("com.example.GeneratedTable", IGNORED_TYPE);

        h.compile();

        for (String f : GLOB_FILES) {
            String content = h.readFile(f);
            assertFalse(content.contains("Snapshot.java") || content.contains("Entry.java"),
                f + " names a nested type as if it were a file:\n" + content);
            assertFalse(content.contains("Ledger.java"),
                f + " excludes the outer type's whole file for a nested type's annotation (#940):\n"
                    + content);
            assertTrue(content.contains("**/GeneratedTable.java"),
                f + " must still carry the top-level type's glob:\n" + content);
        }
        assertFalse(h.readFile(".greptile/config.json").contains("Snapshot.java"),
            "Greptile's ignorePatterns names the nested type");
        assertFalse(h.readFile(".greptile/config.json").contains("Ledger.java"),
            "Greptile's ignorePatterns excludes the outer type's whole file (#940)");
        assertTrue(h.readFile("GEMINI.md").contains("com.example.Ledger.Snapshot"),
            "the nested type must still reach the prose outputs");
    }

    private static List<String> ignorePatternLines(String json) {
        return json.lines().filter(l -> l.contains("**/")).toList();
    }
}
