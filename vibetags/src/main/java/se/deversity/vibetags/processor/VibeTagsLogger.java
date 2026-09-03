package se.deversity.vibetags.processor;

import se.deversity.vibetags.annotations.AIThreadSafe;

import se.deversity.vibetags.processor.internal.LazyFileAppender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import org.jspecify.annotations.Nullable;

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
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.THREAD_LOCAL,
    note = "Per-thread project-root tracking partitions Logback loggers by root, so parallel compilations never detach each other's appenders (VibeTagsLoggerAsyncTest proves it)"
)
public final class VibeTagsLogger {

    static final String LOGGER_NAME    = "se.deversity.vibetags";
    static final String DEFAULT_LOG_FILE  = "vibetags.log";
    static final String DEFAULT_LOG_LEVEL = "INFO";

    private static final ThreadLocal<java.util.Set<Path>> THREAD_PROJECT_ROOTS =
            ThreadLocal.withInitial(java.util.LinkedHashSet::new);

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
                java.util.Set<Path> roots = THREAD_PROJECT_ROOTS.get();
                for (Path root : roots) {
                    String dynamicName = getLoggerName(root);
                    context.getLogger(dynamicName).detachAndStopAllAppenders();
                }
                roots.clear();
                context.getLogger(LOGGER_NAME).detachAndStopAllAppenders();
            }
        } catch (RuntimeException ignored) {
            // Never let logging teardown propagate — Logback internals may throw
            // unchecked exceptions in exotic class-loader or OSGi environments.
        }
    }

    /**
     * Detaches and stops appenders specifically for the given project root's logger.
     *
     * @param projectRoot the root whose logger should be torn down; {@code null} is a no-op, so a
     *                    caller that never resolved a root does not have to guard the call
     */
    public static void shutdown(@Nullable Path projectRoot) {
        if (projectRoot == null) return;
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext context) {
                String dynamicName = getLoggerName(projectRoot);
                context.getLogger(dynamicName).detachAndStopAllAppenders();
            }
            THREAD_PROJECT_ROOTS.get().remove(projectRoot);
        } catch (RuntimeException ignored) {
            // Never let logging teardown propagate.
        }
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
     * {@return the logger this thread already configured for {@code projectRoot}, or {@code null}}
     *
     * <p>A lookup, never a setup. {@link #forRoot(Path)} builds an appender, opens a file and sets
     * a level; this method does none of that, so code deep inside a compilation round can report
     * on the log the round is already writing without having to be handed it. That matters where
     * the call site cannot be changed - {@code AIGuardrailProcessor.generateFiles()} is
     * {@code @AILocked} because its step order is load-bearing, and threading a logger through it
     * would have edited locked code to add a diagnostic.
     *
     * <p>A root nobody configured on this thread answers {@code null}, and so does a root whose
     * level is {@code OFF}: {@code forRoot} removes it from the thread's set before returning the
     * no-op logger, so "logging is off" and "no logger here" are the same answer, which is the
     * right one for both.
     *
     * @param projectRoot the root whose logger is wanted; {@code null} answers {@code null}
     */
    public static @Nullable Logger currentFor(@Nullable Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        // Compared normalised: the processor registers the root it resolved, and a caller deeper
        // in the round may hold an equivalent path that is not equal() to it.
        Path target = projectRoot.toAbsolutePath().normalize();
        for (Path known : THREAD_PROJECT_ROOTS.get()) {
            if (known.toAbsolutePath().normalize().equals(target)) {
                return LoggerFactory.getLogger(getLoggerName(known));
            }
        }
        return null;
    }

    /**
     * Suffixes the logger name with an absolute path hash to isolate concurrent parallel test threads.
     */
    private static String getLoggerName(@Nullable Path projectRoot) {
        if (projectRoot == null) {
            return LOGGER_NAME;
        }
        return LOGGER_NAME + "." + (projectRoot.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE);
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
    public static Logger forRoot(@Nullable Path projectRoot, @Nullable String logPath, @Nullable String level) {
        if (projectRoot != null) {
            THREAD_PROJECT_ROOTS.get().add(projectRoot);
        }
        // Handle OFF level BEFORE resolving the log file path: OFF never creates a file,
        // and resolving would NPE for a null (permitted, @Nullable) projectRoot. Release
        // only THIS root's previous handle — a global shutdown() here would silently
        // detach the appenders of other roots' still-active loggers on the same thread.
        if ("OFF".equalsIgnoreCase(level)) {
            detachAppendersFor(projectRoot);
            return NOPLogger.NOP_LOGGER;
        }

        // Resolve the effective log file path
        Path logFile = resolveLogFile(projectRoot, logPath);

        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                // SLF4J is not bound to Logback — plain logger as fallback
                return LoggerFactory.getLogger(getLoggerName(projectRoot));
            }

            Level logbackLevel = Level.toLevel(level, Level.INFO);

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            // %replace collapses CR/LF inside the formatted message: the log is an event
            // stream read with grep, one event per line, and values interpolated into it come
            // from outside (compiler options, module ids, paths read back out of sidecar and
            // baseline files). A line break in one of those would split an event in two and
            // let the tail masquerade as a separate event. Pinned by
            // VibeTagsLoggerUnitTest#logMessageWithLineBreaks_staysOnOneLine.
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %replace(%msg){'[\\r\\n]+', ' '}%n");
            encoder.start();

            // Lazy on purpose: Logback's FileAppender opens its file in start(), so a build with
            // no annotations in a project that opted nothing in still got a zero-byte
            // vibetags.log in its working tree (#487). LazyFileAppender defers that to the first
            // event, so a run with nothing to say leaves nothing behind.
            LazyFileAppender appender = new LazyFileAppender();
            appender.setContext(context);
            appender.setFile(logFile.toString());
            appender.setAppend(true);
            appender.setEncoder(encoder);
            appender.setImmediateFlush(true);
            appender.start();

            String dynamicLoggerName = getLoggerName(projectRoot);
            ch.qos.logback.classic.Logger logger = context.getLogger(dynamicLoggerName);
            logger.detachAndStopAllAppenders(); // avoid duplicates in incremental/daemon builds
            logger.addAppender(appender);
            logger.setLevel(logbackLevel);
            logger.setAdditive(false); // suppress console / root-logger propagation

            return logger;
        } catch (Exception e) {
            // Never let logging setup break the annotation processor
            System.err.println("VibeTags: Failed to initialize file logger: " + e.getMessage());
            return LoggerFactory.getLogger(getLoggerName(projectRoot));
        }
    }

    /**
     * Detaches and stops the appenders of the logger belonging to {@code projectRoot} only
     * (or the base logger when {@code projectRoot} is null), leaving other roots' loggers
     * on this thread untouched.
     */
    private static void detachAppendersFor(@Nullable Path projectRoot) {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext context) {
                context.getLogger(getLoggerName(projectRoot)).detachAndStopAllAppenders();
            }
            if (projectRoot != null) {
                THREAD_PROJECT_ROOTS.get().remove(projectRoot);
            }
        } catch (RuntimeException ignored) {
            // Never let logging teardown propagate.
        }
    }

    private static Path resolveLogFile(@Nullable Path projectRoot, @Nullable String logPath) {
        // projectRoot is @Nullable: fall back to the JVM working directory, matching the
        // processor's own default root (Paths.get("")).
        Path base = projectRoot != null ? projectRoot : Paths.get("").toAbsolutePath();
        if (logPath == null || logPath.isBlank()) {
            return base.resolve(DEFAULT_LOG_FILE);
        }
        Path p = Paths.get(logPath);
        return p.isAbsolute() ? p : base.resolve(p);
    }
}
