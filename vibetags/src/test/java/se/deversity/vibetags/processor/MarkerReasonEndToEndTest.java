package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the previously attribute-less "marker" annotations now carry an optional
 * {@code reason} that is surfaced in the generated output — so the rationale survives across AI
 * sessions — and that nothing extra is emitted when the reason is left blank.
 */
class MarkerReasonEndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        harness.addSource("com.example.Api",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                + "@AIPublicAPI(reason = \"Frozen for the v2 mobile contract\")\n"
                + "public class Api {}\n");

        harness.addSource("com.example.Money",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIStrictTypes;\n"
                + "@AIStrictTypes(reason = \"Currency math broke in INC-4412 when a double leaked in\")\n"
                + "public class Money {}\n");

        // Same marker, no reason — must not emit any Reason suffix or empty <reason> element.
        harness.addSource("com.example.Plain",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIStrictTypes;\n"
                + "@AIStrictTypes\n"
                + "public class Plain {}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void cursorRulesCarriesTheReason() throws IOException {
        String cursor = harness.readFile(".cursorrules");
        assertTrue(cursor.contains("Frozen for the v2 mobile contract"),
            ".cursorrules must carry the @AIPublicAPI reason");
        assertTrue(cursor.contains("Currency math broke in INC-4412 when a double leaked in"),
            ".cursorrules must carry the @AIStrictTypes reason");
    }

    @Test
    void claudeMdCarriesTheReasonAsXml() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<reason>Frozen for the v2 mobile contract</reason>"),
            "CLAUDE.md must carry the @AIPublicAPI reason as a <reason> element");
        assertTrue(claude.contains("<reason>Currency math broke in INC-4412 when a double leaked in</reason>"),
            "CLAUDE.md must carry the @AIStrictTypes reason as a <reason> element");
    }

    @Test
    void blankReasonEmitsNothingExtra() throws IOException {
        String cursor = harness.readFile(".cursorrules");
        assertTrue(cursor.contains("com.example.Plain"), "Plain should still be listed");
        assertFalse(
            cursor.lines().anyMatch(l -> l.contains("com.example.Plain") && l.contains("Reason:")),
            "A marker with no reason must not emit a 'Reason:' suffix");

        String claude = harness.readFile("CLAUDE.md");
        assertFalse(claude.contains("<reason></reason>"),
            "A blank reason must not emit an empty <reason> element");
    }
}
