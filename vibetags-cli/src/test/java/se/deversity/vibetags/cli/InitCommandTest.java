package se.deversity.vibetags.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vibetags init} — the observable contract: which files an invocation creates,
 * which it refuses to touch, and what it reports. The platform keys and paths asserted
 * here come from {@code ServiceRegistry}, so a processor-side rename fails this suite
 * rather than silently splitting the CLI from the processor.
 */
class InitCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            dir);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void list_showsOptInKeysWithPaths_andCreatesNothing() {
        int code = run("init", "--list");

        assertEquals(0, code);
        assertTrue(out().contains("claude -> CLAUDE.md"), out());
        assertTrue(out().contains("cursor -> .cursorrules"), out());
        assertFalse(Files.exists(dir.resolve("CLAUDE.md")),
            "--list must never create a file: presence is the opt-in signal");
    }

    @Test
    void platforms_createEmptyOptInFiles() throws Exception {
        int code = run("init", "--platforms", "claude,cursor");

        assertEquals(0, code);
        assertTrue(Files.isRegularFile(dir.resolve("CLAUDE.md")));
        assertTrue(Files.isRegularFile(dir.resolve(".cursorrules")));
        assertEquals(0, Files.size(dir.resolve("CLAUDE.md")),
            "init creates the opt-in empty; the next compile fills it");
        assertTrue(out().contains("created:"), out());
    }

    @Test
    void granularKey_createsADirectory() {
        int code = run("init", "--platforms", "claude_granular");

        assertEquals(0, code);
        assertTrue(Files.isDirectory(dir.resolve(".claude/rules")),
            "granular services opt in with a directory, not a file");
    }

    @Test
    void nestedFileKey_createsParentDirectories() {
        int code = run("init", "--platforms", "copilot");

        assertEquals(0, code);
        assertTrue(Files.isRegularFile(dir.resolve(".github/copilot-instructions.md")));
    }

    @Test
    void existingFile_isReportedActiveAndLeftUntouched() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "hand-authored\n");

        int code = run("init", "--platforms", "claude");

        assertEquals(0, code);
        assertEquals("hand-authored\n", Files.readString(dir.resolve("CLAUDE.md")),
            "an existing opt-in file is the user's; init must never truncate it");
        assertTrue(out().contains("already active"), out());
    }

    @Test
    void unknownKey_failsBeforeCreatingAnything() {
        int code = run("init", "--platforms", "claude,notaplatform");

        assertEquals(2, code);
        assertFalse(Files.exists(dir.resolve("CLAUDE.md")),
            "validation must reject the whole request, not create the valid half");
        assertTrue(err().contains("notaplatform"));
    }

    @Test
    void noArguments_listsAndCreatesNothing() throws Exception {
        int code = run("init");

        assertEquals(2, code);
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries.count(), "bare init must not guess an opt-in for the user");
        }
    }

    @Test
    void dirOption_targetsAnotherDirectory() throws Exception {
        Path other = Files.createDirectory(dir.resolve("elsewhere"));

        int code = Main.run(new String[]{"init", "--platforms", "claude", "--dir", other.toString()},
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            dir);

        assertEquals(0, code);
        assertTrue(Files.isRegularFile(other.resolve("CLAUDE.md")));
        assertFalse(Files.exists(dir.resolve("CLAUDE.md")));
    }

    @Test
    void symlinkedParentDirectory_isRefusedBeforeCreatingOutsideTheRoot() throws Exception {
        Path project = Files.createDirectory(dir.resolve("project"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        symlinkOrSkip(project.resolve(".github"), outside);

        int code = Main.run(new String[]{"init", "--platforms", "copilot", "--dir", project.toString()},
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            dir);

        assertEquals(1, code, out() + err());
        assertFalse(Files.exists(outside.resolve("copilot-instructions.md")),
            "a symlinked parent planted in a checkout must not redirect the write outside the root");
        assertTrue(err().contains("outside the project root"), err());
    }

    @Test
    void projectRootReachedThroughASymlink_stillWorks() throws Exception {
        // The escape check compares real paths on both sides, so a linked root (macOS /tmp,
        // developers' own layout choices) must not be mistaken for an escape.
        Path project = Files.createDirectory(dir.resolve("project"));
        Path link = dir.resolve("link");
        symlinkOrSkip(link, project);

        int code = Main.run(new String[]{"init", "--platforms", "claude", "--dir", link.toString()},
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            dir);

        assertEquals(0, code, out() + err());
        assertTrue(Files.isRegularFile(project.resolve("CLAUDE.md")), out() + err());
    }

    @Test
    void invalidDirPath_isAUsageErrorNotAStackTrace() {
        // NUL is rejected by Path.of on every platform; before the fix this escaped as an
        // uncaught InvalidPathException.
        int code = run("init", "--list", "--dir", "bad\0path");

        assertEquals(2, code, err());
        assertTrue(err().contains("--dir"), err());
    }

    @Test
    void unknownCommand_isAUsageError() {
        assertEquals(2, run("frobnicate"));
    }

    private static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // Windows without Developer Mode or the symlink privilege lands here; the
            // Linux CI legs run the real assertion.
            Assumptions.abort("cannot create symlinks in this environment: " + e);
        }
    }
}
