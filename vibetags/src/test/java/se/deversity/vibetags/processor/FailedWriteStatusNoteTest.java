package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file the writer warned it did not write is not reported as {@code no changes} (#1208).
 *
 * <p>{@code writeFileIfChanged} answers {@code false} both for a file that was already current and
 * for one it could not or would not write, and the status line after the parallel write mapped
 * {@code false} to {@code no changes}. So every such warning was followed by a NOTE contradicting
 * it: a UTF-16 {@code CLAUDE.md} (#878) printed "Failed to write AI rules file ... left unchanged"
 * and then "no changes - CLAUDE.md". The same held for each file the writer declines with a
 * warning, such as one with a stray start marker (#865). Such a file is now reported as
 * {@code not written}.
 */
@Tag("e2e")
class FailedWriteStatusNoteTest {

    private static final String SOURCE = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "frozen")
        public class Frozen {}
        """;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aFileThatFailedToWriteIsReportedAsNotWritten() throws IOException {
        ByteArrayOutputStream utf16 = new ByteArrayOutputStream();
        utf16.write(0xFF);
        utf16.write(0xFE); // UTF-16LE byte order mark
        utf16.write("# Mine\n".getBytes(StandardCharsets.UTF_16LE));
        Files.write(tempDir.resolve("CLAUDE.md"), utf16.toByteArray());

        List<String> messages = compileWithCursorRules();

        assertTrue(messages.stream().anyMatch(m -> m.contains("Failed to write AI rules file") && m.contains("CLAUDE.md")),
            "precondition: the writer warns about the UTF-16 file: " + messages);
        assertFalse(messages.contains("VibeTags: no changes - CLAUDE.md"),
            "a file the writer just failed to write is not 'no changes': " + messages);
        assertTrue(messages.contains("VibeTags: not written - CLAUDE.md"), "the status says what happened: " + messages);
        assertTrue(messages.contains("VibeTags: updated - .cursorrules"),
            "a file written in the same build is still reported as updated: " + messages);
    }

    @Test
    void aFileTheWriterDeclinedIsReportedAsNotWritten() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "<!-- VIBETAGS-START -->\nmy own notes\n", StandardCharsets.UTF_8);

        List<String> messages = compileWithCursorRules();

        assertTrue(messages.stream().anyMatch(m -> m.contains("CLAUDE.md") && m.contains("was left untouched")),
            "precondition: the writer declines the file with a stray start marker: " + messages);
        assertFalse(messages.contains("VibeTags: no changes - CLAUDE.md"), messages.toString());
        assertTrue(messages.contains("VibeTags: not written - CLAUDE.md"), messages.toString());
    }

    private List<String> compileWithCursorRules() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn(".cursorrules");
        h.addSource("com.example.Frozen", SOURCE);
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();
        return diagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).toList();
    }
}
