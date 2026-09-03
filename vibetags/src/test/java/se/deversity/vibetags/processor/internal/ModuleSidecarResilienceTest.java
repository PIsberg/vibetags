package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ModuleSidecar} does when the filesystem, not the content, is the problem.
 *
 * <p>Both behaviours pinned here were bugs found by {@code ModuleSidecarAsyncTest} on Windows,
 * where a parallel reactor has one module renaming its sidecar into place while a sibling reads
 * the same directory: the rename was aborted by the reader's open handle, and the read was folded
 * into "malformed" and deleted a valid sibling's sidecar. Neither is reproducible on Linux, which
 * is why these are deterministic unit tests rather than more stress.
 */
class ModuleSidecarResilienceTest {

    // ---------------------------------------------------------------- rename retry

    @Test
    void renameRetriesUntilTheFilesystemLetsItThrough(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        Path target = dir.resolve("sidecar");
        AtomicInteger attempts = new AtomicInteger();

        ModuleSidecar.moveIntoPlace(tmp, target, (source, destination) -> {
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
        for (int attempt = 1; attempt < ModuleSidecar.MOVE_ATTEMPTS; attempt++) {
            nominal += ModuleSidecar.backoffMillis(attempt);
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

        ModuleSidecar.moveIntoPlace(tmp, target, (source, destination) -> {
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
            ModuleSidecar.moveIntoPlace(tmp, target, (source, destination) -> {
                attempts.incrementAndGet();
                throw new AccessDeniedException(destination.toString());
            }, millis -> { }));

        assertEquals(ModuleSidecar.MOVE_ATTEMPTS, attempts.get(), "should stop at the attempt cap");
        assertTrue(thrown instanceof AccessDeniedException, "should report the filesystem's own failure");
        assertFalse(Files.exists(tmp), "a temp file left behind would litter the reactor root");
        assertFalse(Files.exists(target), "nothing was moved, so nothing should exist at the target");
    }

    @Test
    void aFailureThatIsNotAFilesystemFailurePropagatesImmediately(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IOException.class, () ->
            ModuleSidecar.moveIntoPlace(tmp, dir.resolve("sidecar"), (source, destination) -> {
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
                ModuleSidecar.moveIntoPlace(tmp, dir.resolve("sidecar"), (source, destination) -> {
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
    void theProductionMoverReplacesAnExistingSidecar(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "new");
        Path target = Files.writeString(dir.resolve("sidecar"), "old");

        ModuleSidecar.ATOMIC_REPLACE.move(tmp, target);

        assertEquals("new", Files.readString(target));
    }

    // ---------------------------------------------------------------- unreadable vs malformed

    @Test
    void anUnreadableFileIsNotTheSameAsAMalformedOne(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("never-existed");
        assertSame(ModuleSidecar.UNREADABLE, ModuleSidecar.load(missing),
            "a file that cannot be read says nothing about its content");

        Path corrupt = Files.writeString(dir.resolve(".vibetags-mod-corrupt"),
            "# version=" + ModuleSidecar.FORMAT_VERSION + "\nmoduleId=corrupt\nclaude=not~valid~base64!\n");
        assertNull(ModuleSidecar.load(corrupt), "content that will not decode is malformed");
    }

    @Test
    void readAllPrunesAMalformedSidecarButNeverAnUnreadableOne(@TempDir Path root) throws IOException {
        ModuleSidecar valid = new ModuleSidecar("good", "good");
        valid.putBody("claude", "body");
        Files.createDirectories(root.resolve("good"));
        valid.save(root);

        Path malformed = Files.writeString(root.resolve(".vibetags-mod-bad"), "not a sidecar at all\n");

        // A directory sitting where a sidecar file should be is the portable stand-in for
        // "cannot be read": every OS refuses to read it, none of them call it corrupt.
        Path unreadable = Files.createDirectory(root.resolve(".vibetags-mod-locked"));

        List<ModuleSidecar> all = ModuleSidecar.readAll(root);

        assertEquals(1, all.size(), "only the valid sidecar should be merged");
        assertEquals("good", all.get(0).getModuleId());
        assertFalse(Files.exists(malformed), "a malformed sidecar should still be pruned");
        assertTrue(Files.exists(unreadable),
            "readAll deleted a sidecar it could not even read — that is how a parallel reactor "
                + "loses a module's guardrails");
    }

    @Test
    void loadForReportsNothingRatherThanASentinelWhenTheFileCannotBeRead(@TempDir Path root) throws IOException {
        Path asDirectory = root.resolve(".vibetags-mod-locked");
        Files.createDirectory(asDirectory);

        assertNull(ModuleSidecar.loadFor(root, "locked"),
            "a caller comparing against last round's content must not be handed a sentinel");

        ModuleSidecar saved = new ModuleSidecar("real", "real");
        saved.putBody("claude", "body");
        Files.createDirectories(root.resolve("real"));
        saved.save(root);
        ModuleSidecar reloaded = ModuleSidecar.loadFor(root, "real");
        assertNotNull(reloaded);
        assertEquals("body", reloaded.getBodies().get("claude"));
    }

    // ---------------------------------------------------------------- unrepresentable module path

    /**
     * A sibling sidecar can name a module directory this filesystem cannot even spell: written on
     * Linux for a module called {@code api:v2} or {@code core } and synced into a Windows checkout,
     * or hand-edited. {@code root.resolve} then throws InvalidPathException, a RuntimeException
     * that escaped readAll, staleGranularStems and anyStale alike and failed every module of the
     * reactor with an uncaught-exception diagnostic. A NUL byte is illegal everywhere, so the
     * case reproduces on Linux as well. The module cannot exist here, so it is stale, not fatal.
     */
    @Test
    void aModulePathThisFilesystemRejectsIsStaleNotFatal(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".vibetags-mod-core"),
            "# version=2\nmoduleId=core\nmodulePath=core" + (char) 0 + "x\nregionId=core\n");

        assertTrue(ModuleSidecar.anyStale(root), "the module cannot exist here, so its sidecar is stale");
        assertTrue(ModuleSidecar.staleGranularStems(root).isEmpty(),
            "it names no live stems and, above all, does not throw");
        assertTrue(ModuleSidecar.readAll(root).isEmpty(),
            "a module whose directory cannot exist here contributes nothing");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-core")),
            "and the reading round prunes it like any other stale sidecar");
    }
}
