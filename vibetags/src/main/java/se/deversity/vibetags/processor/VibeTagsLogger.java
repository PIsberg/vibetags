package se.deversity.vibetags.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Configures a file-based SLF4J/Logback logger that appends to {@code vibetags.log}
 * in the consumer project's root directory.
 *
 * <p>Logback is configured programmatically — no {@code logback.xml} is required and
 * output does not appear on the console or interfere with other loggers in the compiler JVM.
 *
 * <p>If the SLF4J binding on the classpath is not Logback (unlikely, but possible in exotic
 * build environments) the method falls back to a plain SLF4J logger so the processor
 * still runs correctly, just without a dedicated file.
 */
public final class VibeTagsLogger {

    static final String LOGGER_NAME = "se.deversity.vibetags";
    static final String LOG_FILE_NAME = "vibetags.log";

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
     * Returns a configured SLF4J {@link Logger} that appends structured entries to
     * {@code vibetags.log} inside {@code projectRoot}.
     *
     * <p>Safe to call on every compilation run: any appenders previously attached to this
     * logger are removed before the new one is started, preventing duplicate output during
     * incremental or daemon builds.
     *
     * @param projectRoot the consumer project root — the same directory where AI config
     *                    files ({@code .cursorrules}, {@code CLAUDE.md}, …) are written
     * @return a ready-to-use SLF4J {@code Logger}
     */
    public static Logger forRoot(Path projectRoot) {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                // SLF4J is not bound to Logback — return a plain logger as fallback
                return LoggerFactory.getLogger(LOGGER_NAME);
            }

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n");
            encoder.start();

            FileAppender<ILoggingEvent> appender = new FileAppender<>();
            appender.setContext(context);
            appender.setFile(projectRoot.resolve(LOG_FILE_NAME).toString());
            appender.setAppend(true);
            appender.setEncoder(encoder);
            appender.start();

            ch.qos.logback.classic.Logger logger = context.getLogger(LOGGER_NAME);
            logger.detachAndStopAllAppenders(); // avoid duplicates in incremental/daemon builds
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false); // suppress console / root-logger propagation

            return logger;
        } catch (Exception e) {
            // Never let logging setup break the annotation processor
            return LoggerFactory.getLogger(LOGGER_NAME);
        }
    }
}
