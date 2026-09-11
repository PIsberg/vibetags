package se.deversity.vibetags.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.vibetags.processor.internal.LazyFileAppender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract for {@link LazyFileAppender}, the appender that defers creating
 * {@code vibetags.log} until something is actually logged (#487).
 *
 * <p>Why this class exists: {@code VibeTagsLoggerAsyncTest} gives every thread its own directory
 * and its own appender, so it proves isolation and nothing about contention. No test drove
 * concurrent traffic through a <em>single</em> appender, which is the only way the lazy open is
 * reached by more than one thread at once. {@code DeferredFileStream.open()} is a double-checked
 * lazy init: {@code synchronized}, guarded by a {@code volatile boolean opened} that
 * {@code hasOpenedFile()} reads with no lock at all.
 *
 * <p>Two properties, asserted together because a fix for one can break the other:
 *
 * <ul>
 *   <li>A busy appender opens its file exactly once and loses no event, however many threads
 *       arrive at the first write together.</li>
 *   <li>An idle appender creates nothing, and keeps creating nothing while a sibling appender is
 *       under load. That is invariant-adjacent: #487 is the bug where merely configuring the
 *       logger dropped a zero-byte file into a consumer's working tree.</li>
 * </ul>
 *
 * <h2>What this test does and does not detect, measured</h2>
 *
 * <p>Both breaks below were applied to {@code LazyFileAppender} on purpose and the test re-run,
 * rather than assumed:
 *
 * <ul>
 *   <li><strong>Detected.</strong> Touching the stream from {@code start()}, so the file is created
 *       before any event arrives, fails the idle-appender assertion on every one of the 300
 *       invocations. That is the #487 regression this class exists to hold shut.</li>
 *   <li><strong>NOT detected.</strong> Removing {@code synchronized} from
 *       {@code DeferredFileStream.open()} leaves the test green, three runs out of three. Logback
 *       serialises {@code doAppend} behind its own lock, so no two threads reach the lazy open at
 *       once through the logger, which is the only path a consumer uses. The lock in
 *       {@code open()} is belt-and-braces against a future caller that does not hold Logback's;
 *       this test does not pin it, and claiming otherwise would be false comfort.</li>
 * </ul>
 *
 * <p>So: a behaviour test over the observable file, not a probe of the lock. Pinning the lock would
 * mean reaching {@code DeferredFileStream} directly, which is private, and adding a seam to
 * production code to test an invariant no production caller can currently violate.
 */
@Tag("e2e")
class LazyFileAppenderAsyncTest {

    @TempDir
    static Path sharedDir;

    private static LoggerContext context;
    private static LazyFileAppender busyAppender;
    private static LazyFileAppender idleAppender;
    private static ch.qos.logback.classic.Logger busyLogger;
    private static Path busyFile;
    private static Path idleFile;

    /** Every message handed to the busy logger, so the file can be checked for losses. */
    private static final Set<String> expected = ConcurrentHashMap.newKeySet();

    /** Non-null if any invocation saw the idle appender open a file it was never written to. */
    private static final AtomicInteger idleOpened = new AtomicInteger();

    @BeforeAll
    static void wireOneAppenderPerRole() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        busyFile = sharedDir.resolve("busy").resolve("vibetags.log");
        idleFile = sharedDir.resolve("idle").resolve("vibetags.log");

        busyAppender = appenderAt(busyFile);
        idleAppender = appenderAt(idleFile);

        busyLogger = context.getLogger("vibetags.lazy-appender-async-test");
        busyLogger.detachAndStopAllAppenders();
        busyLogger.addAppender(busyAppender);
        busyLogger.setLevel(Level.INFO);
        busyLogger.setAdditive(false);
    }

    private static LazyFileAppender appenderAt(Path file) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();

        LazyFileAppender appender = new LazyFileAppender();
        appender.setContext(context);
        appender.setFile(file.toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.setImmediateFlush(true);
        appender.start();
        return appender;
    }

    // useVirtualThreads = false because two detectors (LivelockDetector, DaemonThreadHygiene)
    // report themselves inert under virtual threads: dumpAllThreads() does not see them, so a
    // clean report would mean "not observed" rather than "no problem".
    //
    // minTrust = FACT deliberately. The library grades findings ADVISORY < PROMPT < FACT <
    // VERDICT, and at PROMPT it says so itself: "synchronization the library cannot see may make
    // this correct". Gating there fails this test on its own harness: 12 workers plus surefire's
    // pool on a 16-core box get LivelockDetector reporting async-test-worker-N as starved, which
    // is scheduler pressure from the runner, not a defect in the appender. PROMPT and below still
    // print, so a real one is visible in the log; only FACT and above go red.
    @AsyncTest(threads = 12, invocations = 25, timeoutMs = 120_000,
        useVirtualThreads = false, preset = Preset.ALL,
        failOn = FailOn.HIGH, minTrust = TrustTier.FACT)
    void concurrentWritersShareOneLazilyOpenedFileWhileAnIdleOneStaysAbsent() {
        String message = "event-" + Thread.currentThread().getId() + "-" + System.nanoTime();
        expected.add(message);
        busyLogger.info(message);

        // Read the sibling's lazy-init flag with no lock, from every thread, while the busy
        // appender is mid-open. An appender nothing ever wrote to must never claim a file.
        if (idleAppender.hasOpenedFile()) {
            idleOpened.incrementAndGet();
        }
    }

    @AfterAll
    static void theBusyFileIsCompleteAndTheIdleOneWasNeverCreated() throws IOException {
        busyAppender.stop();
        idleAppender.stop();
        busyLogger.detachAndStopAllAppenders();

        assertEquals(0, idleOpened.get(),
            "an appender nothing was ever written to reported an open file; #487 says configuring "
                + "the logger must not create one");
        assertFalse(idleAppender.hasOpenedFile(), "the idle appender must never open its file");
        assertFalse(Files.exists(idleFile),
            "the idle appender left a file on disk: " + idleFile);

        assertTrue(busyAppender.hasOpenedFile(), "the busy appender must have opened its file");
        assertTrue(Files.exists(busyFile), "the busy appender wrote no file: " + busyFile);

        List<String> lines = Files.readAllLines(busyFile, StandardCharsets.UTF_8);
        assertEquals(expected.size(), lines.size(),
            "every logged event must appear exactly once; a lost or duplicated line means the "
                + "lazy open raced and a writer wrote through a stream that was replaced");
        assertEquals(expected, Set.copyOf(lines),
            "the file's content must be exactly the set of messages that were logged");
    }
}
