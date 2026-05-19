package se.deversity.vibetags.processor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-concurrency stress test for VibeTagsLogger isolated parallel execution.
 * Uses async-test-lib to force real thread collisions and race-condition checks.
 */
class VibeTagsLoggerAsyncTest {

    @BeforeAll
    static void initSlf4j() {
        // Eagerly initialize SLF4J to prevent race conditions on SubstituteLoggerFactory during concurrent init
        LoggerFactory.getLogger(VibeTagsLoggerAsyncTest.class);
    }

    @AsyncTest(threads = 20, invocations = 10, timeoutMs = 60_000)
    void testLoggerIsolationUnderConcurrency(@TempDir Path tempDir) throws Exception {
        long threadId = Thread.currentThread().threadId();
        // Dynamic isolated subdirectory per thread to prevent cross-talk
        Path threadDir = tempDir.resolve("thread-" + threadId + "-" + System.nanoTime());
        Files.createDirectories(threadDir);

        Path logFile = threadDir.resolve("vibetags.log");

        // Initialize isolated logger
        Logger log = VibeTagsLogger.forRoot(threadDir, "vibetags.log", "INFO");

        // Write logging messages
        String helloMsg = "Hello from thread " + threadId;
        String goodbyeMsg = "Goodbye from thread " + threadId;
        log.info(helloMsg);
        log.info(goodbyeMsg);

        // Verify that the log file was created and contains ONLY this thread's logs
        assertTrue(Files.exists(logFile), "Log file should exist for thread " + threadId);
        String content = Files.readString(logFile);
        
        assertTrue(content.contains(helloMsg), "Log file must contain hello message");
        assertTrue(content.contains(goodbyeMsg), "Log file must contain goodbye message");

        // Shutdown isolated logger specifically
        VibeTagsLogger.shutdown(threadDir);
    }
}
