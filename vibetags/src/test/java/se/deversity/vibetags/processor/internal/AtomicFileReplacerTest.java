package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link AtomicFileReplacer} gets a file into place when the filesystem, not the content, is
 * the problem.
 *
 * <p>The retry was a bug found by {@code ModuleSidecarAsyncTest} on Windows, where a parallel
 * reactor has one module renaming its sidecar into place while a sibling reads the same directory
 * and the reader's open handle aborts the rename. It is not reproducible on Linux, which is why
 * these are deterministic unit tests rather than more stress.
 */
class AtomicFileReplacerTest {

    // ---------------------------------------------------------------- replace

    @Test
    void replaceWritesTheContentAndLeavesOnlyTheTarget(@TempDir Path dir) throws IOException {
        Path target = Files.writeString(dir.resolve(".vibetags-cache"), "old");

        AtomicFileReplacer.replace(target, "new åäö");

        assertEquals("new åäö", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of(target), filesIn(dir), "the temp file must have been renamed away");
    }

    /**
     * A write that dies part way, a full disk being the usual cause, must not leave its temp file
     * in the reactor root: every later build would carry it, and nothing else ever deletes it.
     */
    @Test
    void aWriteThatFailsPartWayLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Path target = Files.writeString(dir.resolve(".vibetags-baseline"), "approved");

        IOException thrown = assertThrows(IOException.class, () ->
            AtomicFileReplacer.replace(target, "replacement", (tmp, content) -> {
                Files.writeString(tmp, "repl");
                throw new IOException("No space left on device");
            }, AtomicFileReplacer.ATOMIC_REPLACE));

        assertEquals("No space left on device", thrown.getMessage(), "the write's own failure is the one to report");
        assertEquals("approved", Files.readString(target), "a failed replace must leave the target as it was");
        assertEquals(List.of(target), filesIn(dir), "the half-written temp file was left behind");
    }

    @Test
    void aFilesystemRootCannotBeReplaced() {
        Path root = Path.of("").toAbsolutePath().getRoot();

        assertThrows(IOException.class, () -> AtomicFileReplacer.replace(root, "content"));
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    // ---------------------------------------------------------------- rename retry

    @Test
    void renameRetriesUntilTheFilesystemLetsItThrough(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        Path target = dir.resolve("sidecar");
        AtomicInteger attempts = new AtomicInteger();

        AtomicFileReplacer.moveIntoPlace(tmp, target, (source, destination) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AccessDeniedException(destination.toString());
            }
            Files.move(source, destination);
        });

        assertEquals(3, attempts.get(), "should have retried twice before succeeding");
        assertEquals("payload", Files.readString(target));
        assertFalse(Files.exists(tmp), "the temp file should have been renamed away");
    }

    /**
     * The nominal schedule has to be long enough to outlast the blocker that actually shows up.
     *
     * <p>The number is measured, not chosen: instrumenting the retry loop over five full suite
     * runs on Windows recorded successes as late as attempt 9, and the old 275 ms budget gave up
     * in two of those five runs. Asserting the budget rather than the attempt count keeps this
     * honest if the schedule's shape changes — halving it would reopen the flake.
     */
    @Test
    void theRetryBudgetOutlastsTheBlockerObservedInPractice() {
        long nominal = 0;
        for (int attempt = 1; attempt < AtomicFileReplacer.MOVE_ATTEMPTS; attempt++) {
            nominal += AtomicFileReplacer.backoffMillis(attempt);
        }

        assertTrue(nominal >= 2_000,
            "the retry budget is " + nominal + " ms; the blockers that caused ModuleSidecarAsyncTest "
                + "to fail outlasted 275 ms, so anything near that reopens the flake");
        // Jitter spends between half and all of each nominal wait, so the floor is what matters.
        assertTrue(nominal / 2 >= 1_000,
            "even the most jittered-down run must still wait over a second before giving up");
    }

    /**
     * A blocker lasting longer than the old 10-attempt cap must now be ridden out rather than
     * turned into a lost module. This fails against the previous schedule, which is the point.
     */
    @Test
    void renameRidesOutABlockerThatOutlastsTheOldTenAttemptCap(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        Path target = dir.resolve("sidecar");
        AtomicInteger attempts = new AtomicInteger();

        AtomicFileReplacer.moveIntoPlace(tmp, target, (source, destination) -> {
            if (attempts.incrementAndGet() <= 11) {
                throw new AccessDeniedException(destination.toString());
            }
            Files.move(source, destination);
        }, millis -> { /* the schedule is asserted above; this test is about the cap */ });

        assertEquals(12, attempts.get(), "should have kept retrying past the old cap of 10");
        assertEquals("payload", Files.readString(target), "the payload must survive the retries");
        assertFalse(Files.exists(tmp), "the temp file should have been renamed away");
    }

    @Test
    void renameGivesUpAfterTheLastAttemptAndTakesTheTempFileWithIt(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        Path target = dir.resolve("sidecar");
        AtomicInteger attempts = new AtomicInteger();

        IOException thrown = assertThrows(IOException.class, () ->
            // No-op backoff: this test is about giving up at the cap, and paying the real
            // multi-second schedule to prove it would put a stress test in the fast tier.
            AtomicFileReplacer.moveIntoPlace(tmp, target, (source, destination) -> {
                attempts.incrementAndGet();
                throw new AccessDeniedException(destination.toString());
            }, millis -> { }));

        assertEquals(AtomicFileReplacer.MOVE_ATTEMPTS, attempts.get(), "should stop at the attempt cap");
        assertTrue(thrown instanceof AccessDeniedException, "should report the filesystem's own failure");
        assertFalse(Files.exists(tmp), "a temp file left behind would litter the reactor root");
        assertFalse(Files.exists(target), "nothing was moved, so nothing should exist at the target");
    }

    @Test
    void aFailureThatIsNotAFilesystemFailurePropagatesImmediately(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IOException.class, () ->
            AtomicFileReplacer.moveIntoPlace(tmp, dir.resolve("sidecar"), (source, destination) -> {
                attempts.incrementAndGet();
                throw new IOException("disk is on fire");
            }));

        assertEquals(1, attempts.get(), "retrying is for transient sharing violations, not every failure");
    }

    @Test
    void anInterruptedRetryStopsWaitingAndKeepsTheInterruptFlag(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        AtomicInteger attempts = new AtomicInteger();

        Thread.currentThread().interrupt();
        try {
            IOException thrown = assertThrows(IOException.class, () ->
                AtomicFileReplacer.moveIntoPlace(tmp, dir.resolve("sidecar"), (source, destination) -> {
                    attempts.incrementAndGet();
                    throw new AccessDeniedException(destination.toString());
                }));

            assertEquals(1, attempts.get(), "an interrupted build should stop retrying at once");
            assertTrue(thrown instanceof AccessDeniedException,
                "the caller needs the move failure, not the interruption");
            assertTrue(Thread.currentThread().isInterrupted(),
                "swallowing the interrupt would leave javac unable to shut down");
        } finally {
            Thread.interrupted(); // clear the flag so it cannot leak into the next test
        }
    }

    @Test
    void twoClassloadersInOneProcessNeverShareATempName(@TempDir Path dir) throws Exception {
        // A parallel reactor can load the processor once per module, each copy with its own
        // sequence counter, so `<pid>-<n>` repeats across them. Two modules then wrote the root
        // .vibetags-baseline or .vibetags-cache through one temp file: one rename moved bytes its
        // writer never produced and the other failed with NoSuchFileException (found by #911's test).
        Path first = tempNameFromOwnLoader(dir);
        Path second = tempNameFromOwnLoader(dir);
        assertNotEquals(first, second,
            "two copies of AtomicFileReplacer in one JVM produced the same temp file name");
        assertTrue(first.getFileName().toString().startsWith(".vibetags-baseline.")
                && first.getFileName().toString().endsWith(".tmp"),
            "readAll, the prune scan and .gitignore match the prefix and the .tmp suffix: " + first);
    }

    /** The first temp name a fresh copy of AtomicFileReplacer, in a classloader of its own, hands out. */
    private static Path tempNameFromOwnLoader(Path dir) throws Exception {
        URL[] path = {
            AtomicFileReplacer.class.getProtectionDomain().getCodeSource().getLocation(),
            org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation(),
        };
        try (URLClassLoader loader = new URLClassLoader(path, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = loader.loadClass(AtomicFileReplacer.class.getName());
            assertNotSame(AtomicFileReplacer.class, type, "precondition: a second copy of the class");
            Method unique = type.getDeclaredMethod("uniqueTempFile", Path.class, String.class);
            unique.setAccessible(true);
            return (Path) unique.invoke(null, dir, ".vibetags-baseline");
        }
    }

    @Test
    void theProductionMoverReplacesAnExistingFile(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "new");
        Path target = Files.writeString(dir.resolve("sidecar"), "old");

        AtomicFileReplacer.ATOMIC_REPLACE.move(tmp, target);

        assertEquals("new", Files.readString(target));
    }
}
