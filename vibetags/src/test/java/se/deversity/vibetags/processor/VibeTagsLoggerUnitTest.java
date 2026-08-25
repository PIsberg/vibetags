package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering all branches of {@link VibeTagsLogger}.
 */
class VibeTagsLoggerUnitTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // --- forRoot(Path) overload ---

    @Test
    void forRootSingleArg_usesDefaults() {
        Logger logger = VibeTagsLogger.forRoot(tempDir);

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
        // Default log file should have been created
                // Write one event first. The log file is created on the first record rather than
        // when the logger is configured (#487), so what is asserted here is that the
        // resolved path is where the log lands, which is the part a consumer relies on.
        logger.info("corpus");
        assertTrue(Files.exists(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE)));
    }

    // --- forRoot(Path, String, String) with OFF level ---

    @Test
    void forRootOffLevel_returnsNopLogger() {
        Logger logger = VibeTagsLogger.forRoot(tempDir, null, "OFF");

        assertSame(NOPLogger.NOP_LOGGER, logger);
        // No log file should be created
        assertFalse(Files.exists(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE)));
    }

    @Test
    void forRootOffLevel_caseInsensitive() {
        Logger logger = VibeTagsLogger.forRoot(tempDir, null, "off");
        assertSame(NOPLogger.NOP_LOGGER, logger);
    }

    @Test
    void forRootOffLevel_mixedCase() {
        Logger logger = VibeTagsLogger.forRoot(tempDir, null, "Off");
        assertSame(NOPLogger.NOP_LOGGER, logger);
    }

    @Test
    void forRootOffLevel_nullRoot_returnsNopWithoutThrowing() {
        // projectRoot is declared @Nullable; OFF must not NPE trying to resolve a log
        // file it will never create.
        Logger logger = assertDoesNotThrow(() -> VibeTagsLogger.forRoot(null, null, "OFF"),
            "forRoot(null, null, \"OFF\") must not throw — projectRoot is @Nullable");
        assertSame(NOPLogger.NOP_LOGGER, logger);
    }

    @Test
    void forRootOffLevel_doesNotDetachSiblingRootsActiveLogger(@TempDir Path rootA, @TempDir Path rootB)
            throws Exception {
        // Two roots on the same thread (e.g. two modules compiled by the same daemon
        // thread). Turning logging OFF for root B must only release B's handle — it must
        // not silently detach the appender of root A's still-active logger.
        Logger loggerA = VibeTagsLogger.forRoot(rootA, null, "INFO");
        loggerA.info("before-off");

        VibeTagsLogger.forRoot(rootB, null, "OFF");

        loggerA.info("after-off");
        VibeTagsLogger.shutdown(rootA);

        String logged = Files.readString(rootA.resolve(VibeTagsLogger.DEFAULT_LOG_FILE));
        assertTrue(logged.contains("before-off"), "sanity: the first message must be on file");
        assertTrue(logged.contains("after-off"),
            "OFF for root B must not detach root A's appender — root A's messages were silently lost");
    }

    // --- one event per line ---

    @Test
    void logMessageWithLineBreaks_staysOnOneLine() throws Exception {
        // The log is a machine-greppable event stream: `domain.event key=value`, one event
        // per line. A value carrying CR/LF (a module id or root path taken from a compiler
        // option, a path read out of .vibetags-baseline) would otherwise split one event
        // across several lines and could forge an entry that looks like a separate event.
        Logger logger = VibeTagsLogger.forRoot(tempDir, null, "INFO");

        logger.info("write.skip file={} reason=cache-unchanged", "a\nERROR forged.event injected=true\nb");

        VibeTagsLogger.shutdown(tempDir);
        java.util.List<String> lines = Files.readAllLines(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE));

        assertEquals(1, lines.size(),
            "a value containing CR/LF must not split the event across lines, got: " + lines);
        assertTrue(lines.get(0).contains("forged.event"),
            "sanity: the value itself must survive, only its line breaks are collapsed");
    }

    // --- resolveLogFile: relative path ---

    @Test
    void forRootRelativeLogPath_resolvesAgainstProjectRoot() {
        Path logPath = tempDir.resolve("logs/custom.log");
        // Create parent dir so the logger can write
        assertDoesNotThrow(() -> Files.createDirectories(logPath.getParent()));

        Logger logger = VibeTagsLogger.forRoot(tempDir, "logs/custom.log", "INFO");

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
                // Write one event first. The log file is created on the first record rather than
        // when the logger is configured (#487), so what is asserted here is that the
        // resolved path is where the log lands, which is the part a consumer relies on.
        logger.info("corpus");
        assertTrue(Files.exists(logPath));
    }

    // --- resolveLogFile: absolute path ---

    @Test
    void forRootAbsoluteLogPath_usesDirectly() throws Exception {
        Path absoluteLog = tempDir.resolve("absolute-test.log").toAbsolutePath();

        Logger logger = VibeTagsLogger.forRoot(tempDir, absoluteLog.toString(), "INFO");

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
                // Write one event first. The log file is created on the first record rather than
        // when the logger is configured (#487), so what is asserted here is that the
        // resolved path is where the log lands, which is the part a consumer relies on.
        logger.info("corpus");
        assertTrue(Files.exists(absoluteLog));
    }

    // --- shutdown: normal path ---

    @Test
    void shutdown_releasesFileHandle() {
        VibeTagsLogger.forRoot(tempDir, null, "INFO");
        // Should not throw — file handle should be released
        assertDoesNotThrow(() -> VibeTagsLogger.shutdown());
    }

    @Test
    void forRootBlankLogPath_usesDefault() {
        Logger logger = VibeTagsLogger.forRoot(tempDir, "   ", "INFO");

        assertNotSame(NOPLogger.NOP_LOGGER, logger);
                // Write one event first. The log file is created on the first record rather than
        // when the logger is configured (#487), so what is asserted here is that the
        // resolved path is where the log lands, which is the part a consumer relies on.
        logger.info("corpus");
        assertTrue(Files.exists(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE)));
    }

    // --- resolveLogFile: invalid level fallback ---

    @Test
    void forRootInvalidLevel_fallbacksToInfo() {
        // "INVALID" is not a standard Level string, should fallback to INFO
        Logger logger = VibeTagsLogger.forRoot(tempDir, null, "INVALID_LEVEL_NAME_123");
        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
                // Write one event first. The log file is created on the first record rather than
        // when the logger is configured (#487), so what is asserted here is that the
        // resolved path is where the log lands, which is the part a consumer relies on.
        logger.info("corpus");
        assertTrue(Files.exists(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE)));
    }

    // --- Error handling: catch block in forRoot ---

    @Test
    void forRootPathIsDirectory_triggersCatchAndReturnsStandardLogger() throws Exception {
        Path dirPath = tempDir.resolve("not-a-file");
        Files.createDirectories(dirPath);

        // Attempting to set a directory as the log file path should cause start() to fail or throw
        // which will be caught in the try-catch block
        Logger logger = VibeTagsLogger.forRoot(tempDir, "not-a-file", "INFO");

        assertNotNull(logger);
        // Should fallback to a standard SLF4J logger (not necessarily the NOP logger)
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
    }
}
