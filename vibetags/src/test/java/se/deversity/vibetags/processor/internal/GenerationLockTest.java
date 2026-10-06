package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generation lock's contract: a second module waits for the first, and nothing it meets ever
 * fails the build. The race it closes is {@code ParallelReactorGenerationTest}'s.
 */
class GenerationLockTest {

    @TempDir
    Path root;

    @Test
    void aSecondModuleInThisJvmWaitsUntilTheFirstReleases() throws Exception {
        // Same JVM, same file: tryLock throws OverlappingFileLockException rather than waiting,
        // which is the case a plain lock() call gets wrong (EnforcementBaseline's did until #911).
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicBoolean secondHeld = new AtomicBoolean();
        AtomicLong releasedAt = new AtomicLong();
        AtomicLong acquiredAt = new AtomicLong();
        try (GenerationLock first = GenerationLock.acquire(root, "core", null)) {
            assertTrue(first.held(), "precondition: the first module takes the lock");
            Thread second = new Thread(() -> {
                try (GenerationLock lock = GenerationLock.acquire(root, "cli", null)) {
                    acquiredAt.set(System.nanoTime());
                    secondHeld.set(lock.held());
                }
                secondDone.countDown();
            });
            second.start();
            assertFalse(secondDone.await(300, TimeUnit.MILLISECONDS),
                "the second module must wait while the first holds the lock");
            releasedAt.set(System.nanoTime());
        }
        assertTrue(secondDone.await(10, TimeUnit.SECONDS), "the second module must proceed once the lock is free");
        assertTrue(secondHeld.get(), "and hold the lock itself, not run unlocked");
        assertTrue(acquiredAt.get() >= releasedAt.get(), "it acquired only after the first released");
    }

    @Test
    void aWaitPastTheLimitProceedsUnlockedInsteadOfHanging() throws Exception {
        try (GenerationLock first = GenerationLock.acquire(root, "core", null)) {
            assertTrue(first.held(), "precondition: the first module takes the lock");
            try (GenerationLock second = GenerationLock.acquire(root, "cli", null, Duration.ofMillis(100))) {
                assertFalse(second.held(), "past the limit the second module proceeds, unlocked");
            }
        }
    }

    @Test
    void aRootThatGivesNoLockFileProceedsUnlocked() {
        Path missing = root.resolve("no-such-directory");
        try (GenerationLock lock = GenerationLock.acquire(missing, "core", null)) {
            assertFalse(lock.held(), "an unopenable lock file must not fail generation");
        }
    }

    @Test
    void theNoLockFactoryHoldsNothingAndClosesCleanly() {
        try (GenerationLock lock = GenerationLock.none()) {
            assertFalse(lock.held());
        }
    }

    @Test
    void aWaiterThatGivesUpLeavesTheHolderLockedAgainstAnotherProcess() throws Exception {
        // #923: on Linux the JDK takes fcntl locks, which belong to the process, so closing any
        // channel on the file drops all of them. A waiter that opened its own channel and closed it
        // on timeout freed the holder's lock for a second build on the same root, while the holder
        // still reported it held. Windows locks per handle, so this passes there either way.
        Path lockFile = root.resolve(GenerationLock.FILE_NAME);
        try (GenerationLock holder = GenerationLock.acquire(root, "core", null)) {
            assertTrue(holder.held(), "precondition: the first module takes the lock");
            assertEquals("BLOCKED", tryLockInAnotherProcess(lockFile),
                "precondition: another process cannot take a held lock");
            try (GenerationLock waiter = GenerationLock.acquire(root, "cli", null, Duration.ofMillis(100))) {
                assertFalse(waiter.held(), "precondition: the waiter gives up");
            }
            assertEquals("BLOCKED", tryLockInAnotherProcess(lockFile),
                "a waiter giving up must not release the holder's lock for another process");
        }
    }

    /** Runs {@link OtherProcess} in a fresh JVM and returns what it printed. */
    private static String tryLockInAnotherProcess(Path lockFile) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            OtherProcess.class.getName(), lockFile.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the other process must finish");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    /** A second build on the same root: tries the lock once and says whether it got it. */
    static final class OtherProcess {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                System.out.println(lock == null ? "BLOCKED" : "ACQUIRED");
            }
        }
    }

    @Test
    void releasingLetsTheNextAcquireSucceedAtOnce() {
        try (GenerationLock first = GenerationLock.acquire(root, "core", null)) {
            assertTrue(first.held());
        }
        try (GenerationLock again = GenerationLock.acquire(root, "core", null, Duration.ofMillis(50))) {
            assertTrue(again.held(), "a released lock must be free for the next module");
        }
    }
}
