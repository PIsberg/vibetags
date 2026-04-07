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

    // --- resolveLogFile: relative path ---

    @Test
    void forRootRelativeLogPath_resolvesAgainstProjectRoot() {
        Path logPath = tempDir.resolve("logs/custom.log");
        // Create parent dir so the logger can write
        assertDoesNotThrow(() -> Files.createDirectories(logPath.getParent()));

        Logger logger = VibeTagsLogger.forRoot(tempDir, "logs/custom.log", "INFO");

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
        assertTrue(Files.exists(logPath));
    }

    // --- resolveLogFile: absolute path ---

    @Test
    void forRootAbsoluteLogPath_usesDirectly() throws Exception {
        Path absoluteLog = tempDir.resolve("absolute-test.log").toAbsolutePath();

        Logger logger = VibeTagsLogger.forRoot(tempDir, absoluteLog.toString(), "INFO");

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
        assertTrue(Files.exists(absoluteLog));
    }

    // --- shutdown: normal path ---

    @Test
    void shutdown_releasesFileHandle() {
        VibeTagsLogger.forRoot(tempDir, null, "INFO");
        // Should not throw — file handle should be released
        assertDoesNotThrow(VibeTagsLogger::shutdown);
    }

    // --- resolveLogFile: blank logPath (covers isBlank branch) ---

    @Test
    void forRootBlankLogPath_usesDefault() {
        Logger logger = VibeTagsLogger.forRoot(tempDir, "   ", "INFO");

        assertNotNull(logger);
        assertNotSame(NOPLogger.NOP_LOGGER, logger);
        assertTrue(Files.exists(tempDir.resolve(VibeTagsLogger.DEFAULT_LOG_FILE)));
    }
}
