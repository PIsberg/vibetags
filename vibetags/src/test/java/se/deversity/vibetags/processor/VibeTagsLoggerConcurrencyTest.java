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

    @Test
    void aSiblingSharingTheRootDoesNotSilenceAModuleStillCompiling(@TempDir Path root) throws Exception {
        // #1079. Two modules of mvn -T or gradle --parallel compile in one JVM against one
        // -Avibetags.root. The module that finishes first shuts its logger down while the other is
        // still generating, and the other's later events must still reach the log.
        CountDownLatch bothConfigured = new CountDownLatch(2);
        CountDownLatch siblingDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> stillCompiling = pool.submit(() -> {
                Logger log = VibeTagsLogger.forRoot(root, "vibetags.log", "INFO");
                bothConfigured.countDown();
                await(bothConfigured);
                await(siblingDone);
                log.info("module-core still generating");
                VibeTagsLogger.shutdown(root);
                return null;
            });
            Future<?> finishedFirst = pool.submit(() -> {
                Logger log = VibeTagsLogger.forRoot(root, "vibetags.log", "INFO");
                bothConfigured.countDown();
                await(bothConfigured);
                log.info("module-cli done");
                VibeTagsLogger.shutdown(root);
                siblingDone.countDown();
                return null;
            });
            finishedFirst.get(30, TimeUnit.SECONDS);
            stillCompiling.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String content = Files.readString(root.resolve("vibetags.log"));
        assertTrue(content.contains("module-cli done"), () -> "precondition: the first module logged:\n" + content);
        assertTrue(content.contains("module-core still generating"),
            () -> "the sibling's shutdown stopped the appender of a module still compiling:\n" + content);
    }

    @Test
    void eachModuleKeepsItsOwnLogPathWhenTheyShareTheRoot(@TempDir Path root) throws Exception {
        // #1079. Module options may name different log files under one root; the second module to
        // configure its logger must not take over the first one's.
        CountDownLatch bothConfigured = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> core = pool.submit(() -> {
                Logger log = VibeTagsLogger.forRoot(root, "core.log", "INFO");
                bothConfigured.countDown();
                await(bothConfigured);
                log.info("from module-core");
                VibeTagsLogger.shutdown(root);
                return null;
            });
            Future<?> cli = pool.submit(() -> {
                Logger log = VibeTagsLogger.forRoot(root, "cli.log", "INFO");
                bothConfigured.countDown();
                await(bothConfigured);
                log.info("from module-cli");
                VibeTagsLogger.shutdown(root);
                return null;
            });
            core.get(30, TimeUnit.SECONDS);
            cli.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String coreLog = Files.exists(root.resolve("core.log")) ? Files.readString(root.resolve("core.log")) : "";
        String cliLog = Files.exists(root.resolve("cli.log")) ? Files.readString(root.resolve("cli.log")) : "";
        assertTrue(coreLog.contains("from module-core") && !coreLog.contains("from module-cli"),
            () -> "core.log must hold module-core's events only:\n" + coreLog);
        assertTrue(cliLog.contains("from module-cli") && !cliLog.contains("from module-core"),
            () -> "cli.log must hold module-cli's events only:\n" + cliLog);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(30, TimeUnit.SECONDS), "a module thread never reached the rendezvous");
    }
}
