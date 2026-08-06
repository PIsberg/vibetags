package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that VibeTagsLogger isolates configurations for concurrent compilations running in parallel threads.
 */
class VibeTagsLoggerConcurrencyTest {

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void testParallelLoggerIsolation(@TempDir Path tempDir) throws InterruptedException, ExecutionException {
        // Eagerly initialize SLF4J to prevent race conditions on SubstituteLoggerFactory during concurrent init
        org.slf4j.LoggerFactory.getLogger(VibeTagsLoggerConcurrencyTest.class);

        int threadsCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadsCount; i++) {
            final int index = i;
            Path root = tempDir.resolve("thread-" + index);
            tasks.add(() -> {
                try {
                    Files.createDirectories(root);
                    Path logFile = root.resolve("vibetags.log");

                    // Initialize isolated logger
                    Logger log = VibeTagsLogger.forRoot(root, "vibetags.log", "INFO");

                    // Write logging messages
                    log.info("Hello from thread " + index);

                    // Small sleep to allow interleaving with other threads
                    Thread.sleep(100);

                    log.info("Goodbye from thread " + index);

                    // Verify that the log file was created and contains our logs
                    assertTrue(Files.exists(logFile), "Log file should exist for thread " + index);
                    String content = Files.readString(logFile);
                    assertTrue(content.contains("Hello from thread " + index), "Log file must contain hello message");
                    assertTrue(content.contains("Goodbye from thread " + index), "Log file must contain goodbye message");

                    // Shutdown isolated logger specifically
                    VibeTagsLogger.shutdown(root);
                    return true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            });
        }

        List<Future<Boolean>> results = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<Boolean> future : results) {
            assertTrue(future.get(), "Each isolated logging task must pass cleanly without interference or file locks.");
        }
    }
}
