package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way {@link GenerationLock#acquireFile} ends, with the events it logs on the way.
 *
 * <p>The events are a published contract ({@code docs/MULTI-MODULE.md} lists them, and
 * {@code docs/LOGGING.md} makes tested events contracts): {@code generate.lock.wait} once per wait,
 * {@code generate.lock.acquired} with how long it took, and {@code generate.lock.skip} with one
 * reason for each way the build proceeds unlocked. {@code GenerationLockTest} holds who gets the
 * lock; this holds what each path says, including the poll that runs when the file is locked by
 * something that did not pass the in-JVM gate.
 */
class GenerationLockEventsTest {

    @TempDir
    Path root;

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(GenerationLockEventsTest.class.getName() + "." + testInfo.getDisplayName());
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** Each event as "LEVEL message", with a waitedMs that varies run to run written as N. */
    private List<String> events() {
        return appender.list.stream()
            .map(e -> e.getLevel() + " " + e.getFormattedMessage().replaceAll("acquired module=(\\S+) waitedMs=\\d+",
                "acquired module=$1 waitedMs=N"))
            .toList();
    }

    private Path lockFile() {
        return root.resolve(GenerationLock.FILE_NAME);
    }

    private GenerationLock acquire(String module, Duration maxWait) {
        return GenerationLock.acquireFile(lockFile(), module, logger, maxWait);
    }

    @Test
    void anUncontendedLockIsTakenSilently() {
        try (GenerationLock lock = acquire("core", Duration.ofSeconds(5))) {
            assertTrue(lock.held());
        }
        assertEquals(List.of(), events());
    }

    @Test
    void aWaitAtTheGateIsLoggedOnce_thenTheAcquisition() throws Exception {
        AtomicBoolean held = new AtomicBoolean();
        GenerationLock first = GenerationLock.acquireFile(lockFile(), "core", null, Duration.ofSeconds(5));
        Thread second = new Thread(() -> {
            try (GenerationLock lock = acquire("cli", Duration.ofSeconds(10))) {
                held.set(lock.held());
            }
        });
        second.start();
        awaitEvents(1);
        first.close();
        second.join(10_000);
        assertTrue(held.get());
        assertEquals(List.of("INFO generate.lock.wait module=cli reason=sibling-generating",
            "INFO generate.lock.acquired module=cli waitedMs=N"), events());
    }

    @Test
    void aGateHeldPastTheLimitIsSkippedAsATimeout() {
        try (GenerationLock first = GenerationLock.acquireFile(lockFile(), "core", null, Duration.ofSeconds(5));
             GenerationLock second = acquire("cli", Duration.ofMillis(100))) {
            assertFalse(second.held());
        }
        assertEquals(List.of("INFO generate.lock.wait module=cli reason=sibling-generating",
            "WARN generate.lock.skip module=cli reason=timeout detail=waitedMs=100"), events());
    }

    @Test
    void anInterruptWhileWaitingAtTheGateIsSkipped_andTheInterruptKept() throws Exception {
        AtomicBoolean held = new AtomicBoolean(true);
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        try (GenerationLock first = GenerationLock.acquireFile(lockFile(), "core", null, Duration.ofSeconds(5))) {
            Thread second = new Thread(() -> {
                Thread.currentThread().interrupt();
                try (GenerationLock lock = acquire("cli", Duration.ofSeconds(10))) {
                    held.set(lock.held());
                }
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            });
            second.start();
            second.join(10_000);
        }
        assertFalse(held.get());
        assertTrue(stillInterrupted.get(), "the interrupt belongs to the caller and must survive");
        assertEquals(List.of("INFO generate.lock.wait module=cli reason=sibling-generating",
            "WARN generate.lock.skip module=cli reason=interrupted detail="), events());
    }

    @Test
    void aLockFileThatCannotBeOpenedIsSkippedWithTheReason() {
        Path missing = root.resolve("no-such-directory").resolve(GenerationLock.FILE_NAME);
        try (GenerationLock lock = GenerationLock.acquireFile(missing, "core", logger, Duration.ofSeconds(5))) {
            assertFalse(lock.held());
        }
        List<String> events = events();
        assertEquals(1, events.size(), events.toString());
        assertTrue(events.get(0).startsWith("WARN generate.lock.skip module=core reason=unopenable detail=java.nio.file."),
            events.get(0));

        appender.list.clear();
        try (GenerationLock again = GenerationLock.acquireFile(missing, "cli", logger, Duration.ofMillis(500))) {
            assertFalse(again.held());
        }
        assertEquals(1, appender.list.size(), "the first caller left the gate, so no wait: " + events());
    }

    /**
     * A lock taken on the file without passing the gate, as a processor that predates the gate
     * would: past the gate, the channel's tryLock is refused and the lock is polled for.
     */
    @Test
    void aFileLockedOutsideTheGateIsPolledFor_andTakenOnceReleased() throws Exception {
        AtomicBoolean held = new AtomicBoolean();
        try (FileChannel other = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock raw = other.lock();
            Thread second = new Thread(() -> {
                try (GenerationLock lock = acquire("cli", Duration.ofSeconds(10))) {
                    held.set(lock.held());
                }
            });
            second.start();
            awaitEvents(1);
            raw.release();
            second.join(10_000);
        }
        assertTrue(held.get());
        assertEquals(List.of("INFO generate.lock.wait module=cli reason=sibling-generating",
            "INFO generate.lock.acquired module=cli waitedMs=N"), events());
    }

    @Test
    void aFileLockedOutsideTheGatePastTheLimitIsSkippedAsATimeout() throws Exception {
        try (FileChannel other = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock raw = other.lock();
             GenerationLock second = acquire("cli", Duration.ofMillis(100))) {
            assertFalse(second.held());
        }
        assertEquals(List.of("INFO generate.lock.wait module=cli reason=sibling-generating",
            "WARN generate.lock.skip module=cli reason=timeout detail=waitedMs=100"), events());

        appender.list.clear();
        try (GenerationLock third = acquire("app", Duration.ofSeconds(5))) {
            assertTrue(third.held(), "a waiter that gave up on the file must have left the gate");
        }
        assertEquals(List.of(), events(), "no wait at the gate for the next caller");
    }

    @Test
    void aTimedOutWaiterLeavesTheGate_soTheNextAcquireIsImmediate() {
        try (GenerationLock first = GenerationLock.acquireFile(lockFile(), "core", null, Duration.ofSeconds(5));
             GenerationLock second = acquire("cli", Duration.ofMillis(50))) {
            assertFalse(second.held());
        }
        appender.list.clear();
        try (GenerationLock third = acquire("app", Duration.ofSeconds(5))) {
            assertTrue(third.held());
        }
        assertEquals(List.of(), events(), "no wait: nobody is left holding the gate");
    }

    private void awaitEvents(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (appender.list.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(appender.list.size() >= count, "expected " + count + " event(s), got " + events());
    }
}
