package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Gradle incremental annotation processor declaration is present and well-formed.
 *
 * <p>Without this file Gradle treats the processor as non-incremental and re-runs it on the full
 * source set whenever any {@code .java} file changes — which on a large consumer project can mean
 * seconds of wasted work per save. The file marks {@link AIGuardrailProcessor} as
 * {@code aggregating} (the correct category, since output files like {@code CLAUDE.md} depend on
 * annotations gathered from across the entire compile unit, not just the changed file).
 */
class IncrementalProcessorDeclarationTest {

    private static final String RESOURCE_PATH =
        "META-INF/gradle/incremental.annotation.processors";

    @Test
    void declarationFileIsOnClasspath() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            assertNotNull(in,
                "Missing " + RESOURCE_PATH + " — Gradle will treat the processor as non-incremental.");
        }
    }

    @Test
    void declarationDeclaresProcessorAsAggregating() throws Exception {
        String content = readResource();
        String trimmed = content.trim();
        assertEquals(
            "se.deversity.vibetags.processor.AIGuardrailProcessor,aggregating",
            trimmed,
            "Declaration must list AIGuardrailProcessor as 'aggregating' (output spans the compile unit).");
    }

    @Test
    void declarationContainsNoOtherProcessors() throws Exception {
        String content = readResource();
        long nonEmptyLines = content.lines()
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.startsWith("#"))
            .count();
        assertEquals(1, nonEmptyLines,
            "Only AIGuardrailProcessor should be declared. Extra lines indicate a stale or malformed file.");
    }

    @Test
    void declarationDoesNotMisuseIsolatingMode() throws Exception {
        String content = readResource();
        assertTrue(!content.contains(",isolating"),
            "AIGuardrailProcessor must not be declared 'isolating': it reads annotations from every "
                + "source file in the round and writes shared output files. 'isolating' would cause Gradle "
                + "to skip valid recompilations and produce stale CLAUDE.md / .cursorrules content.");
    }

    private String readResource() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            assertNotNull(in, "Missing " + RESOURCE_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
