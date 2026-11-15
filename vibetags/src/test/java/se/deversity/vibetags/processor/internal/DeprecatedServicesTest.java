package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.processor.VibeTagsLogger;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Some outputs name a file no tool reads (#1071, and #641 for the 1.3.5 rows 1.4.0 removed). They
 * are still written, because removing a service silently freezes an opted-in consumer's file, but a
 * consumer who opted in has to be told before the next major version stops writing them. These
 * tests pin that telling: one warning, the file, the replacement, and a {@code platform.deprecated}
 * log event per file. {@code RemovedPlatformOutputsTest} pins what a removal means.
 *
 * <p>The log events are a contract (docs/LOGGING.md). Renaming one is a breaking change.
 */
@DisplayName("Deprecated platform outputs")
class DeprecatedServicesTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /** The suite runs in parallel, so each test captures a logger named after itself. */
    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(
            DeprecatedServicesTest.class.getName() + "." + testInfo.getDisplayName());
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

    /**
     * Every deprecated output, keyed by service key: the path the warning names (a directory with a
     * trailing '/'), then each replacement, or vendor fact where there is no replacement, it must
     * name. One row per notice, so a deprecation that is announced in the docs but missing from
     * {@code DeprecatedServices} fails here by name.
     */
    private static final Map<String, List<String>> EXPECTED = expected();

    private static Map<String, List<String>> expected() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // No vendor documents a repository-root llms-full.txt as a file a tool reads, and
        // llmstxt.org never names it; llms.txt is the file the standard does name (#1071).
        m.put("llms_full", List.of("llms-full.txt", "llms.txt", "llmstxt.org"));
        return m;
    }

    @Test
    @DisplayName("every deprecated output that is opted in is named in one warning, with its replacement")
    void oneWarningNamesEachFileAndItsReplacement(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        for (Map.Entry<String, List<String>> e : EXPECTED.entrySet()) {
            optIn(root, e.getKey(), e.getValue().get(0));
        }
        List<String> warnings = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
            ServiceRegistry.buildServiceFileMap(root));

        assertEquals(1, warnings.size(),
            EXPECTED.size() + " deprecated outputs are one warning, not one line each: " + warnings);
        String warning = warnings.get(0);
        String count = EXPECTED.size() == 1 ? "an opted-in output is" : EXPECTED.size() + " opted-in outputs are";
        assertTrue(warning.startsWith("VibeTags: " + count + " deprecated"),
            "the first line build.yml's gradle-multimodule gate matches on:\n" + warning);
        for (List<String> row : EXPECTED.values()) {
            assertTrue(warning.contains(row.get(0)), "names " + row.get(0) + ":\n" + warning);
            for (String replacement : row.subList(1, row.size())) {
                assertTrue(warning.contains(replacement), "names the replacement " + replacement + ":\n" + warning);
            }
        }
        assertTrue(warning.contains("next major version"),
            "says when the file stops being written, which is what makes it actionable:\n" + warning);
    }

    @Test
    @DisplayName("each deprecated output opted in on its own is warned about by name")
    void eachDeprecatedOutputWarnsOnItsOwn(@TempDir Path parent) throws IOException {
        for (Map.Entry<String, List<String>> e : EXPECTED.entrySet()) {
            Path root = Files.createDirectories(parent.resolve(e.getKey()));
            touch(root, "CLAUDE.md");
            optIn(root, e.getKey(), e.getValue().get(0));
            List<String> warnings = new ArrayList<>();

            ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
                ServiceRegistry.buildServiceFileMap(root));

            assertEquals(1, warnings.size(), e.getKey() + " alone is one warning: " + warnings);
            assertTrue(warnings.get(0).contains(e.getValue().get(0)),
                "names " + e.getValue().get(0) + ":\n" + warnings.get(0));
        }
    }

    @Test
    @DisplayName("a project with no deprecated output opted in gets no deprecation warning")
    void currentOutputsDoNotWarn(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        touch(root, ".cursorrules");
        touch(root, "GEMINI.md");
        touch(root, ".cursorignore");
        // The replacement itself is current: llms.txt alone must not warn.
        touch(root, "llms.txt");
        List<String> warnings = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
            ServiceRegistry.buildServiceFileMap(root));

        assertTrue(warnings.isEmpty(), "nothing deprecated is opted in: " + warnings);
    }

    @Test
    @DisplayName("the nothing-opted-in note does not invite a new user to create a deprecated file")
    void optInListLeavesDeprecatedFilesOut(@TempDir Path root) {
        List<String> notes = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.NOTE, notes),
            ServiceRegistry.buildServiceFileMap(root));

        assertEquals(1, notes.size(), "one opt-in note: " + notes);
        String note = notes.get(0);
        // Matched per line: the note lists root-relative paths, and a substring check would trip over
        // a path that contains another (llms.txt is not llms-full.txt).
        List<String> offered = note.lines().map(String::strip).toList();
        for (List<String> row : EXPECTED.values()) {
            assertFalse(offered.contains(row.get(0)), "does not offer " + row.get(0) + ":\n" + note);
        }
        assertTrue(offered.contains("llms.txt") && offered.contains("AGENTS.md"),
            "still offers the replacement and the current files:\n" + note);
        assertTrue(offered.contains(".clinerules/"),
            "offers Cline's directory, marked as a directory:\n" + note);
        assertTrue(offered.contains(".greptile/config.json"),
            "offers Greptile's config by its path:\n" + note);
    }

    @Test
    @DisplayName("each deprecated output logs platform.deprecated with its key, file and replacement")
    void logEventPerDeprecatedOutput() {
        DeprecatedServices.warnIfOptedIn(capturing(Diagnostic.Kind.WARNING, new ArrayList<>()), logger,
            Set.of("llms_full", "llms", "claude"));

        assertTrue(logged("platform.deprecated key=llms_full file=llms-full.txt replacement=llms.txt"), dump());
        assertEquals(1, appender.list.size(), "one event per deprecated output, none for llms or claude:\n" + dump());
    }

    @Test
    @DisplayName("the processor's root resolution writes the event to the build's own vibetags.log")
    void rootResolutionLogsToTheRootsLog(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        touch(root, "llms-full.txt");
        VibeTagsLogger.forRoot(root, null, "INFO");
        try {
            ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, new ArrayList<>()),
                ServiceRegistry.buildServiceFileMap(root));
        } finally {
            VibeTagsLogger.shutdown(root);
        }

        Path log = root.resolve("vibetags.log");
        assertTrue(Files.exists(log), "the event is the log's only record, so the file must exist");
        assertTrue(Files.readString(log).contains(
                "platform.deprecated key=llms_full file=llms-full.txt replacement=llms.txt"),
            "resolveActiveServices has no logger parameter, so it has to find the root's own:\n"
                + Files.readString(log));
    }

    @Test
    @DisplayName("the replacement vibetags init shows is the one the warning names, and only for deprecated keys")
    void replacementIsTheWarningsReplacement() {
        for (String key : EXPECTED.keySet()) {
            String replacement = DeprecatedServices.replacement(key);
            assertTrue(replacement != null && !replacement.isBlank(), key + " has no replacement");
        }
        assertEquals("llms.txt", DeprecatedServices.replacement("llms_full"));
        assertNull(DeprecatedServices.replacement("llms"), "the replacement is not itself deprecated");
        assertNull(DeprecatedServices.replacement("claude"), "a current platform has no replacement");
    }

    @Test
    @DisplayName("every deprecated key is a real opt-in key whose path matches the service map")
    void everyDeprecatedKeyIsAnOptInKey(@TempDir Path root) {
        assertTrue(ServiceRegistry.optInKeys().containsAll(DeprecatedServices.keys()),
            "a deprecated key that is not an opt-in key can never be active: " + DeprecatedServices.keys());
        assertEquals(EXPECTED.keySet(), DeprecatedServices.keys());
        Map<String, Path> map = ServiceRegistry.buildServiceFileMap(root);
        DeprecatedServices.files().forEach((key, file) -> assertEquals(
            root.relativize(map.get(key)).toString().replace('\\', '/')
                + (ServiceRegistry.writesDirectory(key) ? "/" : ""), file,
            "the warning names the path the user created, a directory with its '/', for " + key));
    }

    private boolean logged(String fragment) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(m -> m.contains(fragment));
    }

    private String dump() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    /** Creates the entry that opts into {@code key}: a directory for a granular service, else a file. */
    private static void optIn(Path root, String key, String relative) throws IOException {
        if (ServiceRegistry.writesDirectory(key)) {
            Files.createDirectories(root.resolve(relative));
        } else {
            touch(root, relative);
        }
    }

    private static void touch(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    private static Messager capturing(Diagnostic.Kind kind, List<String> sink) {
        return new Messager() {
            @Override
            public void printMessage(Diagnostic.Kind k, CharSequence msg) {
                if (k == kind) {
                    sink.add(msg.toString());
                }
            }

            @Override
            public void printMessage(Diagnostic.Kind k, CharSequence msg, Element e) {
                printMessage(k, msg);
            }

            @Override
            public void printMessage(Diagnostic.Kind k, CharSequence msg, Element e, AnnotationMirror a) {
                printMessage(k, msg);
            }

            @Override
            public void printMessage(Diagnostic.Kind k, CharSequence msg, Element e, AnnotationMirror a,
                                     AnnotationValue v) {
                printMessage(k, msg);
            }
        };
    }
}
