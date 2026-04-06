package se.deversity.vibetags.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configures a file-based SLF4J/Logback logger that appends to a log file in the consumer
 * project's root directory.
 *
 * <h2>Configuration via annotation-processor options</h2>
 * <pre>
 * Option                  Default              Description
 * ──────────────────────────────────────────────────────────────────────
 * vibetags.log.path       vibetags.log         Path to the log file.
 *                                              Relative paths are resolved against the
 *                                              project root (same dir as .cursorrules,
 *                                              CLAUDE.md, …).  Absolute paths are used
 *                                              as-is, so you can redirect the log to any
 *                                              location (e.g. /tmp/vibetags.log).
 * vibetags.log.level      INFO                 Logback level: TRACE, DEBUG, INFO, WARN,
 *                                              ERROR, or OFF.  Set to OFF to disable file
 *                                              logging entirely.
 * </pre>
 *
 * <h2>Maven example</h2>
 * <pre>{@code
 * <plugin>
 *   <artifactId>maven-compiler-plugin</artifactId>
 *   <configuration>
 *     <compilerArgs>
 *       <arg>-Avibetags.log.path=logs/vibetags.log</arg>
 *       <arg>-Avibetags.log.level=DEBUG</arg>
 *     </compilerArgs>
 *   </configuration>
 * </plugin>
 * }</pre>
 *
 * <h3>Gradle example</h3>
 * <pre>{@code
 * tasks.withType(JavaCompile) {
 *     options.compilerArgs += [
 *         '-Avibetags.log.path=logs/vibetags.log',
 *         '-Avibetags.log.level=DEBUG'
 *     ]
 * }
 * }</pre>
 *
 * <p>Logback is configured programmatically — no {@code logback.xml} is required and
 * output does not appear on the console or interfere with other loggers in the compiler JVM.
 *
 * <p>If the SLF4J binding on the classpath is not Logback (unusual, but possible in exotic
 * build environments) the method falls back to a plain SLF4J logger so the processor
 * still runs correctly, just without a dedicated file.
 */
public final class VibeTagsLogger {

    static final String LOGGER_NAME    = "se.deversity.vibetags";
    static final String DEFAULT_LOG_FILE  = "vibetags.log";
    static final String DEFAULT_LOG_LEVEL = "INFO";

    private VibeTagsLogger() {}

    /**
     * Detaches and stops all appenders on the VibeTags logger, releasing any open file handles.
     *
     * <p>Call this in tests that use a temporary directory as the project root, so the
     * directory can be cleaned up after the test completes.
     */
    public static void shutdown() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext context) {
                context.getLogger(LOGGER_NAME).detachAndStopAllAppenders();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Returns a configured SLF4J {@link Logger} with default path and level.
     *
     * @param projectRoot the consumer project root — the same directory where AI config
     *                    files ({@code .cursorrules}, {@code CLAUDE.md}, …) are written
     * @return a ready-to-use SLF4J {@code Logger}
     */
    public static Logger forRoot(Path projectRoot) {
        return forRoot(projectRoot, null, null);
    }

    /**
     * Returns a configured SLF4J {@link Logger}.
     *
     * <p>The log file path is resolved as follows:
     * <ul>
     *   <li>If {@code logPath} is absolute it is used directly.</li>
     *   <li>If {@code logPath} is relative it is resolved against {@code projectRoot}.</li>
     * </ul>
     *
     * <p>Passing {@code "OFF"} (case-insensitive) as {@code level} disables file logging and
     * returns {@link NOPLogger#NOP_LOGGER} — no file is created or written.
     *
     * <p>Safe to call on every compilation run: any appenders previously attached to this
     * logger are removed before the new one is started, preventing duplicate output during
     * incremental or daemon builds.
     *
     * @param projectRoot the consumer project root — the same directory where AI config
     *                    files ({@code .cursorrules}, {@code CLAUDE.md}, …) are written
     * @param logPath     path to the log file; relative paths are resolved against
     *                    {@code projectRoot}; defaults to {@value #DEFAULT_LOG_FILE}
     * @param level       Logback level string (TRACE / DEBUG / INFO / WARN / ERROR / OFF);
     *                    defaults to {@value #DEFAULT_LOG_LEVEL}
     * @return a ready-to-use SLF4J {@code Logger}, or a no-op logger when level is OFF
     */
    public static Logger forRoot(Path projectRoot, String logPath, String level) {
        // Resolve the effective log file path
        Path logFile = resolveLogFile(projectRoot, logPath);

        // Handle OFF level — return no-op immediately, release any previous handle
        if ("OFF".equalsIgnoreCase(level)) {
            shutdown();
            return NOPLogger.NOP_LOGGER;
        }

        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                // SLF4J is not bound to Logback — plain logger as fallback
                return LoggerFactory.getLogger(LOGGER_NAME);
            }

            Level logbackLevel = Level.toLevel(level, Level.INFO);

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n");
            encoder.start();

            FileAppender<ILoggingEvent> appender = new FileAppender<>();
            appender.setContext(context);
            appender.setFile(logFile.toString());
            appender.setAppend(true);
            appender.setEncoder(encoder);
            appender.start();

            ch.qos.logback.classic.Logger logger = context.getLogger(LOGGER_NAME);
            logger.detachAndStopAllAppenders(); // avoid duplicates in incremental/daemon builds
            logger.addAppender(appender);
            logger.setLevel(logbackLevel);
            logger.setAdditive(false); // suppress console / root-logger propagation

            return logger;
        } catch (Exception e) {
            // Never let logging setup break the annotation processor
            System.err.println("VibeTags: Failed to initialize file logger: " + e.getMessage());
            return LoggerFactory.getLogger(LOGGER_NAME);
        }
    }

    private static Path resolveLogFile(Path projectRoot, String logPath) {
        if (logPath == null || logPath.isBlank()) {
            return projectRoot.resolve(DEFAULT_LOG_FILE);
        }
        Path p = Paths.get(logPath);
        return p.isAbsolute() ? p : projectRoot.resolve(p);
    }
}
