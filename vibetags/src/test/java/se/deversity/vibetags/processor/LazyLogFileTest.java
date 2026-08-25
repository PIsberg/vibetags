package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build with nothing to say leaves nothing behind.
 *
 * <p>Logback's {@code FileAppender} opens its file in {@code start()}, so simply configuring the
 * logger created {@code vibetags.log}. A project with no annotations, which had opted no platform
 * in, still got a zero-byte file in its working tree: untracked, needing a {@code .gitignore}
 * entry, containing nothing (#487).
 *
 * <p>Tier-1 invariant 1 does not name the log file, so this is not a violation of it. It is the
 * same reasoning one level out: a processor asked to look at somebody's codebase and finding
 * nothing to guard should leave no trace of having looked. Found by the third-party corpus, which
 * compiles six real libraries and asserts nothing is written into them, and which had to exclude
 * {@code vibetags.log} by name to pass.
 *
 * <p>The second test is the one that stops the fix going too far: once there is something to log,
 * the file must appear and carry it. A lazy appender that never opens is not an improvement.
 */
class LazyLogFileTest {

    @Test
    @DisplayName("a compile with no annotations writes no log file at all")
    void noAnnotationsLeavesNoLogFile(@TempDir Path root) throws IOException {
        // false: opt nothing in. This is a project that has never heard of VibeTags, which is
        // the whole scenario - the default constructor creates every opt-in file.
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.addSource("com.example.Plain", """
            package com.example;
            /** Nothing here VibeTags cares about. */
            public class Plain {
                public String greet(String name) { return "hi " + name; }
            }
            """);

        harness.compile();

        Path log = root.resolve("vibetags.log");
        assertTrue(!Files.exists(log),
            "compiling a project with no VibeTags annotations left " + log.getFileName()
                + " in the working tree. A consumer trying VibeTags on a project that has no "
                + "annotations yet should get an untouched tree, not an empty file to gitignore.");

        // The point is a clean tree, not just the absence of one filename.
        try (var entries = Files.list(root)) {
            List<String> created = entries.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(created.stream().noneMatch(n -> n.startsWith("vibetags.log")),
                "unexpected VibeTags files in a project with no annotations: " + created);
        }
    }

    @Test
    @DisplayName("the log file still appears as soon as there is something to log")
    void annotationsStillProduceALogFile(@TempDir Path root) throws IOException {
        // An opted-in platform file plus an annotation: now there is work, and work is logged.
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CLAUDE.md");
        harness.addSource("com.example.Guarded", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "pinned by LazyLogFileTest")
            public class Guarded {
            }
            """);

        harness.compile();

        Path log = root.resolve("vibetags.log");
        assertTrue(Files.exists(log),
            "a build that generated guardrail files wrote no log. Deferring the file must not "
                + "turn into never creating it: the log is how a consumer finds out why a file "
                + "was or was not written.");
        assertTrue(Files.size(log) > 0, "the log file was created but is empty");
    }

    @Test
    @DisplayName("logging is still disabled entirely by vibetags.log.level=OFF")
    void offStillDisablesLogging(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CLAUDE.md");
        harness.addSource("com.example.Guarded", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "pinned by LazyLogFileTest")
            public class Guarded {
            }
            """);

        harness.compile("-Avibetags.log.level=OFF");

        assertEquals(false, Files.exists(root.resolve("vibetags.log")),
            "OFF is the documented way to disable file logging and must still do so");
    }
}
