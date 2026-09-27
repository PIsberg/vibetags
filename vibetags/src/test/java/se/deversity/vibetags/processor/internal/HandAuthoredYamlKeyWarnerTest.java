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
import se.deversity.vibetags.processor.internal.validation.ValidationContext;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HandAuthoredYamlKeyWarner} unit tests: duplicate YAML key detection, skipping non-YAML
 * or non-existent services, unreadable file handling, and relative vs. absolute display paths.
 */
@DisplayName("Hand-authored YAML key warner rules")
class HandAuthoredYamlKeyWarnerTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(HandAuthoredYamlKeyWarnerTest.class.getName() + "." + testInfo.getDisplayName());
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

    private final Messager messager = new Messager() {
        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            if (kind == Diagnostic.Kind.WARNING) {
                warnings.add(msg.toString());
            }
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                                 AnnotationValue v) {
            printMessage(kind, msg);
        }
    };

    @Test
    void displayPath_insideRoot_returnsRelativeForwardSlashPath(@TempDir Path root) {
        Path sub = root.resolve("subdir/sub2/file.yml");
        String displayed = HandAuthoredYamlKeyWarner.displayPath(root, sub);
        assertEquals("subdir/sub2/file.yml", displayed);
    }

    @Test
    void displayPath_outsideRoot_returnsAbsoluteForwardSlashPath(@TempDir Path root, @TempDir Path other) {
        Path file = other.resolve("external.yml");
        String displayed = HandAuthoredYamlKeyWarner.displayPath(root, file);
        assertEquals(file.toAbsolutePath().normalize().toString().replace('\\', '/'), displayed,
            "paths outside root must remain absolute with forward slashes");
    }

    @Test
    void warn_nonYamlService_isSkipped(@TempDir Path root) throws IOException {
        Path claudeFile = root.resolve("CLAUDE.md");
        Files.writeString(claudeFile, "read: [\"something\"]\n", StandardCharsets.UTF_8);

        HandAuthoredYamlKeyWarner.warn(messager, logger, root, Map.of("claude", claudeFile));

        assertTrue(warnings.isEmpty(), "non-YAML services must be skipped");
        assertTrue(appender.list.isEmpty(), "no log for non-YAML services");
    }

    @Test
    void warn_missingOrDirectoryFile_isSkipped(@TempDir Path root) throws IOException {
        Path missing = root.resolve(".aider.conf.yml");
        Path dir = root.resolve("sub");
        Files.createDirectory(dir);

        HandAuthoredYamlKeyWarner.warn(messager, logger, root,
            Map.of("aider_conf", missing, "coderabbit", dir));

        assertTrue(warnings.isEmpty(), "missing or directory files must be skipped");
        assertTrue(appender.list.isEmpty(), "no log for missing or directory files");
    }

    @Test
    void warn_fileWithoutRecognizedMarkers_isSkipped(@TempDir Path root) throws IOException {
        // .json has no markers registered in GuardrailFileWriter.getMarkersFor (returns null)
        Path jsonFile = root.resolve(".aider.conf.json");
        Files.writeString(jsonFile, "read:\n  - test\n", StandardCharsets.UTF_8);

        HandAuthoredYamlKeyWarner.warn(messager, logger, root, Map.of("aider_conf", jsonFile));

        assertTrue(warnings.isEmpty(), "file without recognized markers must be skipped");
    }

    @Test
    void warn_unreadableFile_logsDebug(@TempDir Path root) throws IOException {
        Path aiderConf = root.resolve(".aider.conf.yml");
        Files.createFile(aiderConf);

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                aiderConf, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {

            HandAuthoredYamlKeyWarner.warn(messager, logger, root, Map.of("aider_conf", aiderConf));

            assertTrue(warnings.isEmpty(), "unreadable file must emit no warning");
            List<ILoggingEvent> debugLogs = appender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
            assertTrue(debugLogs.stream().anyMatch(e -> e.getMessage().contains("validation.skip check=duplicate-yaml-key")),
                "unreadable file must log validation.skip debug message");
        }
    }

    @Test
    void warn_duplicateKey_warnsAndLogs(@TempDir Path root) throws IOException {
        Path aiderConf = root.resolve(".aider.conf.yml");
        Files.writeString(aiderConf, """
            read:
              - hand-file.txt
            # VIBETAGS-START
            read:
              - gen-file.txt
            # VIBETAGS-END
            """, StandardCharsets.UTF_8);

        HandAuthoredYamlKeyWarner.warn(messager, logger, root, Map.of("aider_conf", aiderConf));

        assertEquals(1, warnings.size(), "duplicate YAML key must emit one warning");
        String warning = warnings.get(0);
        assertTrue(warning.startsWith(ValidationContext.PREFIX),
            "warning must start with validation prefix: " + warning);
        assertTrue(warning.contains(".aider.conf.yml"), "warning must name file: " + warning);
        assertTrue(warning.contains("read"), "warning must name duplicate key: " + warning);

        List<ILoggingEvent> warnLogs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .toList();
        assertEquals(1, warnLogs.size(), "duplicate key must log one WARN event");
        assertEquals("validation.duplicate-yaml-key file={} key={} handLine={} generatedLine={} readLine={}",
            warnLogs.get(0).getMessage());
    }

    @Test
    void warn_duplicateKey_withNullLogger_warnsQuietlyWithoutNpe(@TempDir Path root) throws IOException {
        Path aiderConf = root.resolve(".aider.conf.yml");
        Files.writeString(aiderConf, """
            read:
              - hand-file.txt
            # VIBETAGS-START
            read:
              - gen-file.txt
            # VIBETAGS-END
            """, StandardCharsets.UTF_8);

        HandAuthoredYamlKeyWarner.warn(messager, null, root, Map.of("aider_conf", aiderConf));

        assertEquals(1, warnings.size(), "must warn on messager even when logger is null");
    }

    @Test
    void warn_noDuplicateKey_isSilent(@TempDir Path root) throws IOException {
        Path aiderConf = root.resolve(".aider.conf.yml");
        Files.writeString(aiderConf, """
            model: gpt-4
            # VIBETAGS-START
            read:
              - gen-file.txt
            # VIBETAGS-END
            """, StandardCharsets.UTF_8);

        HandAuthoredYamlKeyWarner.warn(messager, logger, root, Map.of("aider_conf", aiderConf));

        assertTrue(warnings.isEmpty(), "clean file with no duplicate owned key must be silent");
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
            "no WARN log on clean file");
    }
}
