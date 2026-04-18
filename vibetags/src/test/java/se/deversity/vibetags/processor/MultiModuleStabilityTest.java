package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style unit tests to verify multi-module stability.
 * Ensures that runs with no annotations do not overwrite existing project rules.
 */
class MultiModuleStabilityTest {

    @Test
    void testEmptyRunDoesNotOverwriteExistingRules(@TempDir Path tempDir) throws IOException {
        Path claudeFile = tempDir.resolve("CLAUDE.md");
        String existingContent = "<!-- VIBETAGS-START -->\n" +
                "<project_guardrails>\n" +
                "  <locked_files>\n" +
                "    <file path=\"com.example.Important\">Reason</file>\n" +
                "  </locked_files>\n" +
                "</project_guardrails>\n" +
                "<!-- VIBETAGS-END -->";
        Files.writeString(claudeFile, existingContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        
        // Simulate a run with NO annotations found (hasNewRules = false)
        boolean changed = processor.writeFileIfChanged(claudeFile.toString(), "boiler plate", false);

        assertFalse(changed, "Should NOT have changed the file when no annotations were found");
        assertEquals(existingContent, Files.readString(claudeFile), "Content should be preserved");
    }

    @Test
    void testRunWithNameChangeUpdatesRules(@TempDir Path tempDir) throws IOException {
        Path claudeFile = tempDir.resolve("CLAUDE.md");
        String existingContent = "<!-- VIBETAGS-START -->\n" +
                "OLD CONTENT\n" +
                "<!-- VIBETAGS-END -->";
        Files.writeString(claudeFile, existingContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        
        // Simulate a run WITH annotations (hasNewRules = true)
        boolean changed = processor.writeFileIfChanged(claudeFile.toString(), "NEW CONTENT", true);

        assertTrue(changed, "Should HAVE changed the file when annotations were found");
        String finalContent = Files.readString(claudeFile);
        assertTrue(finalContent.contains("NEW CONTENT"));
        assertFalse(finalContent.contains("OLD CONTENT"));
    }

    @Test
    void testFirstRunWritesEvenIfNoAnnotations(@TempDir Path tempDir) throws IOException {
        // If the file is empty and we have no annotations, we might still want to write the opt-in markers?
        // Actually, if it's empty, writeFileIfChanged will write it.
        Path claudeFile = tempDir.resolve("CLAUDE.md");
        Files.createFile(claudeFile); // empty file

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        
        // Even if no annotations found, if the file is empty, we write the markers
        boolean changed = processor.writeFileIfChanged(claudeFile.toString(), "empty rules", false);

        assertTrue(changed, "Should write to an empty file even if no annotations found (initialization)");
        assertTrue(Files.readString(claudeFile).contains("VIBETAGS-START"));
    }
}
