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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleFileLengthWarner} without a compiler in the loop (issue #695): how a character is
 * counted, which files are measured, and the WARN event, which is a contract under docs/LOGGING.md.
 */
class RuleFileLengthWarnerTest {

    private static final int LIMIT = 12_000;
    private static final int ANTIGRAVITY_BYTES = 24_000;

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final List<String> warnings = new ArrayList<>();

    /** One logger per test, as in GuardrailFileWriterLogContractTest: the suite runs in parallel. */
    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(RuleFileLengthWarnerTest.class.getName() + "." + testInfo.getDisplayName());
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

    /** A generated rule file: {@code filler} between the marker lines, {@link #markerOverhead} characters longer. */
    private static String generated(String filler) {
        return GuardrailFileWriter.MARKER_START_MD + "\n" + filler + "\n" + GuardrailFileWriter.MARKER_END_MD + "\n";
    }

    private static int markerOverhead() {
        return generated("").length();
    }

    private List<String> warnEvents() {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private void warn(Path root) {
        RuleFileLengthWarner.warn(messager, logger, root, ServiceRegistry.buildServiceFileMap(root));
    }

    @Test
    void anOversizedFileLogsTheContractEvent(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(".windsurf/rules/web.md"),
            generated("a".repeat(LIMIT + 1 - markerOverhead())), StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of("validation.rule-file-over-limit file=.windsurf/rules/web.md chars=12001 limit=12000"),
            warnEvents());
        assertEquals(1, warnings.size(), "one build warning per oversized file: " + warnings);
        assertTrue(warnings.get(0).startsWith("VibeTags: .windsurf/rules/web.md is 12001 characters"), warnings.get(0));
    }

    /**
     * Antigravity's {@code .agents/rules/} is capped in bytes, not characters (#850): its rules page
     * says "Antigravity truncates any single rule file that exceeds 24,000 bytes". The WARN event
     * carries {@code bytes=} there, so a log reader is never told a byte count is characters. A
     * marker-carrying file named like the Devin Desktop safety file is not given the
     * {@code .windsurfrules} remedy: Antigravity has no such file.
     */
    @Test
    void anOversizedAntigravityRuleLogsItsLengthInBytesAndNamesAntigravity(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".agents/rules"));
        Files.writeString(root.resolve(".agents/rules/+vibetags-safety.md"),
            generated("a".repeat(ANTIGRAVITY_BYTES + 1 - markerOverhead())), StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of("validation.rule-file-over-limit file=.agents/rules/+vibetags-safety.md bytes=24001 limit=24000"),
            warnEvents());
        assertEquals(1, warnings.size(), "one build warning per oversized file: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.startsWith("VibeTags: .agents/rules/+vibetags-safety.md is 24001 bytes, over the 24000"
            + " bytes Antigravity reads"), warning);
        assertTrue(warning.contains("truncates"), "the vendor now says what happens past the cap: " + warning);
        assertFalse(warning.contains(".windsurfrules") || warning.contains("Devin") || warning.contains("Windsurf"), warning);
    }

    /** The old 12,000-character figure warned about Antigravity files well within its 24,000-byte cap. */
    @Test
    void anAntigravityRuleOverTwelveThousandCharactersButWithinTheByteCapIsSilent(@TempDir Path root)
            throws IOException {
        Files.createDirectories(root.resolve(".agents/rules"));
        Files.writeString(root.resolve(".agents/rules/web.md"),
            generated("a".repeat(LIMIT + 1 - markerOverhead())), StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of(), warnEvents());
        assertEquals(List.of(), warnings);
    }

    /**
     * The other direction, which a character count can never see: 9,000 CJK characters are three
     * UTF-8 bytes each, under any character cap and over Antigravity's byte cap. Run with DEBUG off,
     * so the byte prefilter is the path under test.
     */
    @Test
    @DisplayName("a multi-byte Antigravity rule over the byte cap warns even with DEBUG off")
    void aMultiByteAntigravityRuleUnderTheCharacterCountButOverTheByteCapWarns(@TempDir Path root)
            throws IOException {
        logger.setLevel(Level.WARN);
        Files.createDirectories(root.resolve(".agents/rules"));
        Path file = root.resolve(".agents/rules/web.md");
        Files.writeString(file, generated("中".repeat(9_000)), StandardCharsets.UTF_8);
        long bytes = Files.size(file);
        assertTrue(bytes > ANTIGRAVITY_BYTES && Files.readString(file).length() < LIMIT,
            "the fixture must be under 12,000 characters and over 24,000 bytes, was " + bytes + " bytes");

        warn(root);

        assertEquals(List.of("validation.rule-file-over-limit file=.agents/rules/web.md bytes=" + bytes + " limit=24000"),
            warnEvents());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    /**
     * A character is a UTF-16 code unit, what a JavaScript string length reports: a character outside
     * the Basic Multilingual Plane counts twice, so a file of 12,000 code points can still warn,
     * while two-byte UTF-8 text is not counted by the byte.
     */
    @Test
    void charactersAreCountedAsUtf16CodeUnitsNotCodePointsOrBytes(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".devin/rules"));
        String astral = new String(Character.toChars(0x1F512));
        String overByCodeUnits = generated("a".repeat(LIMIT - 1 - markerOverhead()) + astral);
        assertEquals(LIMIT, overByCodeUnits.codePointCount(0, overByCodeUnits.length()), "fixture: 12,000 code points");
        Files.writeString(root.resolve(".devin/rules/astral.md"), overByCodeUnits, StandardCharsets.UTF_8);
        String accented = generated("\u00e9".repeat(LIMIT - markerOverhead()));
        assertTrue(accented.getBytes(StandardCharsets.UTF_8).length > LIMIT, "fixture: over the cap in bytes");
        Files.writeString(root.resolve(".devin/rules/accented.md"), accented, StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of("validation.rule-file-over-limit file=.devin/rules/astral.md chars=12001 limit=12000"),
            warnEvents());
    }

    /**
     * .windsurfrules is not in a rules directory and the docs give it no cap, and a file with no
     * VibeTags markers was written by its author alone; neither is measured.
     */
    @Test
    void windsurfrulesAndMarkerFreeRulesAreNotMeasured(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".devin/rules"));
        Files.writeString(root.resolve(".devin/rules/team.md"), "a".repeat(LIMIT + 1), StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".windsurfrules"), generated("a".repeat(LIMIT + 1)), StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of(), warnings);
        assertEquals(List.of(), warnEvents());
        assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.equals("validation.skip check=rule-file-length file=.devin/rules/team.md reason=no-markers")),
            "the skipped file says why");
    }

    /**
     * The byte prefilter must not swallow a real finding.
     *
     * <p>With DEBUG off, a file at or under the cap in bytes is skipped without being read, because
     * a UTF-8 file that small cannot hold more characters than the cap. Every other test in this
     * class runs with DEBUG on, which bypasses that path entirely, so without this case the
     * prefilter would be uncovered by the suite that exists to guard this warner.
     */
    @Test
    @DisplayName("with DEBUG off an oversized file still warns")
    void theBytePrefilterDoesNotSwallowAnOversizedFile(@TempDir Path root) throws IOException {
        logger.setLevel(Level.WARN);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(".windsurf/rules/web.md"),
            generated("a".repeat(LIMIT + 1 - markerOverhead())), StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of("validation.rule-file-over-limit file=.windsurf/rules/web.md chars=12001 limit=12000"),
            warnEvents());
        assertEquals(1, warnings.size(), "the prefilter must not hide an over-cap file: " + warnings);
    }

    @Test
    @DisplayName("with DEBUG off a file within the cap warns about nothing")
    void theBytePrefilterStaysSilentForASmallFile(@TempDir Path root) throws IOException {
        logger.setLevel(Level.WARN);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(".windsurf/rules/web.md"), generated("a".repeat(100)),
            StandardCharsets.UTF_8);

        warn(root);

        assertEquals(List.of(), warnEvents());
        assertEquals(List.of(), warnings);
    }

    /**
     * A file whose characters are within the cap but whose bytes are not: the prefilter declines to
     * decide and the ordinary read reaches the same verdict it always did, which is silence.
     */
    @Test
    @DisplayName("multi-byte characters do not make a within-cap file warn")
    void aMultiByteFileOverTheByteCapButUnderTheCharCapIsSilent(@TempDir Path root) throws IOException {
        logger.setLevel(Level.WARN);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        // Three bytes per character, so well over the cap in bytes and well under it in characters.
        String filler = "中".repeat(LIMIT - markerOverhead() - 1);
        Path file = root.resolve(".windsurf/rules/web.md");
        Files.writeString(file, generated(filler), StandardCharsets.UTF_8);
        assertTrue(Files.size(file) > LIMIT, "the fixture must exceed the cap in bytes to be the case it claims");

        warn(root);

        assertEquals(List.of(), warnEvents(), "the cap is characters, and this file is within it");
        assertEquals(List.of(), warnings);
    }
}
