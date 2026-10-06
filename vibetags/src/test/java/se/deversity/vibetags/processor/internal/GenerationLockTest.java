package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
        // which is the case a plain lock() call, or EnforcementBaseline's pattern, gets wrong.
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
    void releasingLetsTheNextAcquireSucceedAtOnce() {
        try (GenerationLock first = GenerationLock.acquire(root, "core", null)) {
            assertTrue(first.held());
        }
        try (GenerationLock again = GenerationLock.acquire(root, "core", null, Duration.ofMillis(50))) {
            assertTrue(again.held(), "a released lock must be free for the next module");
        }
    }
}
