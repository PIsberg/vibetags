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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four outputs name a tool that has moved on (#641). They are still written, because removing a
 * service silently freezes an opted-in consumer's file, but a consumer who opted in has to be told
 * before the next major version stops writing them. These tests pin that telling: one warning, the
 * file, the replacement, and a {@code platform.deprecated} log event per file.
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

    @Test
    @DisplayName("every deprecated output that is opted in is named in one warning, with its replacement")
    void oneWarningNamesEachFileAndItsReplacement(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        touch(root, "gemini_instructions.md");
        touch(root, ".cody/config.json");
        touch(root, ".codyignore");
        touch(root, ".supermavenignore");
        touch(root, ".clinerules");
        List<String> warnings = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
            ServiceRegistry.buildServiceFileMap(root));

        assertEquals(1, warnings.size(),
            "five deprecated files are one warning, not five lines of build output: " + warnings);
        String warning = warnings.get(0);
        for (String file : List.of("gemini_instructions.md", ".cody/config.json", ".codyignore",
                ".supermavenignore", ".clinerules")) {
            assertTrue(warning.contains(file), "names " + file + ":\n" + warning);
        }
        for (String replacement : List.of("GEMINI.md", ".gemini/styleguide.md", "AGENTS.md",
                ".cursorignore", ".clinerules/")) {
            assertTrue(warning.contains(replacement), "names the replacement " + replacement + ":\n" + warning);
        }
        assertTrue(warning.contains("next major version"),
            "says when the file stops being written, which is what makes it actionable:\n" + warning);
    }

    @Test
    @DisplayName("a project with no deprecated output opted in gets no deprecation warning")
    void currentOutputsDoNotWarn(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        touch(root, ".cursorrules");
        touch(root, "GEMINI.md");
        touch(root, ".cursorignore");
        List<String> warnings = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
            ServiceRegistry.buildServiceFileMap(root));

        assertTrue(warnings.isEmpty(), "nothing deprecated is opted in: " + warnings);
    }

    @Test
    @DisplayName("a .clinerules/ directory is the current Cline shape and is not warned about")
    void clineRulesDirectoryIsNotTheDeprecatedFile(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        Files.createDirectories(root.resolve(".clinerules"));
        List<String> warnings = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.WARNING, warnings),
            ServiceRegistry.buildServiceFileMap(root));

        assertTrue(warnings.isEmpty(),
            "the user already followed Cline's current docs; warning them to do so is noise: " + warnings);
    }

    @Test
    @DisplayName("the nothing-opted-in note does not invite a new user to create a deprecated file")
    void optInListLeavesDeprecatedFilesOut(@TempDir Path root) {
        List<String> notes = new ArrayList<>();

        ServiceRegistry.resolveActiveServices(capturing(Diagnostic.Kind.NOTE, notes),
            ServiceRegistry.buildServiceFileMap(root));

        assertEquals(1, notes.size(), "one opt-in note: " + notes);
        String note = notes.get(0);
        // Matched per line: the note lists bare file names, and a substring check would trip over
        // .mentatconfig.json for Cody's config.json.
        List<String> offered = note.lines().map(String::strip).toList();
        for (String file : List.of("gemini_instructions.md", "config.json", ".codyignore",
                ".supermavenignore", ".clinerules")) {
            assertFalse(offered.contains(file), "does not offer " + file + ":\n" + note);
        }
        assertTrue(offered.contains("GEMINI.md") && offered.contains("AGENTS.md"),
            "still offers the replacements:\n" + note);
    }

    @Test
    @DisplayName("each deprecated output logs platform.deprecated with its key, file and replacement")
    void logEventPerDeprecatedOutput() {
        DeprecatedServices.warnIfOptedIn(capturing(Diagnostic.Kind.WARNING, new ArrayList<>()), logger,
            Set.of("cody_ignore", "supermaven_ignore", "claude"));

        assertTrue(logged("platform.deprecated key=cody_ignore file=.codyignore replacement=AGENTS.md"),
            dump());
        assertTrue(logged("platform.deprecated key=supermaven_ignore file=.supermavenignore replacement=.cursorignore"),
            dump());
        assertEquals(2, appender.list.size(), "one event per deprecated output, none for claude:\n" + dump());
    }

    @Test
    @DisplayName("the processor's root resolution writes the event to the build's own vibetags.log")
    void rootResolutionLogsToTheRootsLog(@TempDir Path root) throws IOException {
        touch(root, "CLAUDE.md");
        touch(root, ".supermavenignore");
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
                "platform.deprecated key=supermaven_ignore file=.supermavenignore replacement=.cursorignore"),
            "resolveActiveServices has no logger parameter, so it has to find the root's own:\n"
                + Files.readString(log));
    }

    @Test
    @DisplayName("every deprecated key is a real opt-in key whose path matches the service map")
    void everyDeprecatedKeyIsAnOptInKey(@TempDir Path root) {
        assertTrue(ServiceRegistry.optInKeys().containsAll(DeprecatedServices.keys()),
            "a deprecated key that is not an opt-in key can never be active: " + DeprecatedServices.keys());
        assertEquals(Set.of("gemini", "cody", "cody_ignore", "supermaven_ignore", "cline"),
            DeprecatedServices.keys());
        Map<String, Path> map = ServiceRegistry.buildServiceFileMap(root);
        DeprecatedServices.files().forEach((key, file) -> assertEquals(
            root.relativize(map.get(key)).toString().replace('\\', '/'), file,
            "the warning names the file the user created, so it has to be the mapped path for " + key));
    }

    private boolean logged(String fragment) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(m -> m.contains(fragment));
    }

    private String dump() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
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
