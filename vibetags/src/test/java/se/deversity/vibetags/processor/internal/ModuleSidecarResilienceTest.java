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

    @Test
    void renameGivesUpAfterTheLastAttemptAndTakesTheTempFileWithIt(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("sidecar.tmp"), "payload");
        Path target = dir.resolve("sidecar");
        AtomicInteger attempts = new AtomicInteger();

        IOException thrown = assertThrows(IOException.class, () ->
            ModuleSidecar.moveIntoPlace(tmp, target, (source, destination) -> {
                attempts.incrementAndGet();
                throw new AccessDeniedException(destination.toString());
            }));

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
}
