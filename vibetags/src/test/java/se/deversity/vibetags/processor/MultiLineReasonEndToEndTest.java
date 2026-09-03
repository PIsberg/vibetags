package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reason written as a Java text block must come out as one line on every platform
 * (<a href="https://github.com/PIsberg/vibetags/issues/549">issue #549</a>). The Markdown
 * platforms append the value straight after a bullet, so before this rule the second line of the
 * text block was a bare paragraph under the bullet; the XML block in CLAUDE.md carried the raw
 * newline inside {@code <reason>}.
 */
@Tag("e2e")
class MultiLineReasonEndToEndTest {

    private static final String SOURCE = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = \"""
            Partner contract v2.
            Breaking it fails the nightly reconciliation.\""")
        public class Ledger {
        }
        """;

    private static final String ONE_LINE =
        "Partner contract v2. Breaking it fails the nightly reconciliation.";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aTextBlockReasonRendersAsOneLineOnMarkdownAndXmlPlatformsAlike() throws IOException {
        Files.createDirectories(root.resolve(".github"));
        Files.createFile(root.resolve(".github/copilot-instructions.md"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/Ledger.java", SOURCE);

        harness.compile();

        String copilot = Files.readString(root.resolve(".github/copilot-instructions.md"), StandardCharsets.UTF_8);
        assertTrue(copilot.contains("- `com.example.Ledger` - " + ONE_LINE),
            "the bullet must carry the whole reason:\n" + copilot);
        assertFalse(copilot.lines().anyMatch(l -> l.startsWith("Breaking it")),
            "no line of the text block may escape the bullet as a bare paragraph");

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("<reason>" + ONE_LINE + "</reason>"),
            "the XML block gets the same one-line value:\n" + claude);
    }
}
