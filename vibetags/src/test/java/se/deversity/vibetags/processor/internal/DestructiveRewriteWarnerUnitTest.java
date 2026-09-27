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
import org.slf4j.LoggerFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DestructiveRewriteWarner} unit tests: wholesale replacement detection, orphan sweep reporting,
 * truncation past {@code MAX_LISTED = 8}, and null-safe messager and logger handling.
 */
@DisplayName("Destructive rewrite and orphan sweep diagnostic rules")
class DestructiveRewriteWarnerUnitTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final List<DiagnosticRecord> recordedDiagnostics = new ArrayList<>();

    private record DiagnosticRecord(Diagnostic.Kind kind, String message) {}

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(DestructiveRewriteWarnerUnitTest.class.getName() + "." + testInfo.getDisplayName());
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
            recordedDiagnostics.add(new DiagnosticRecord(kind, msg.toString()));
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
    void regionReplaced_emptyPreviousOrNew_isSilent() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.regionReplaced("mod", Set.of(), Set.of("A"));
        assertTrue(recordedDiagnostics.isEmpty(), "empty previous elements must be silent (first build)");
        assertTrue(appender.list.isEmpty(), "no log on empty previous");

        warner.regionReplaced("mod", Set.of("A"), Set.of());
        assertTrue(recordedDiagnostics.isEmpty(), "empty new elements must be silent (legitimate withdrawal)");
        assertTrue(appender.list.isEmpty(), "no log on empty new");
    }

    @Test
    void regionReplaced_elementSurvives_isSilent() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.regionReplaced("mod", Set.of("A", "B"), Set.of("B", "C"));
        assertTrue(recordedDiagnostics.isEmpty(), "ordinary churn sharing an element must be silent");
        assertTrue(appender.list.isEmpty(), "no log when elements survive");
    }

    @Test
    void regionReplaced_disjointSetsUnderLimit_warnsAndNamesElements() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.regionReplaced("module-core", Set.of("A", "B"), Set.of("C", "D"));

        assertEquals(1, recordedDiagnostics.size(), "wholesale replacement must emit one warning");
        DiagnosticRecord diag = recordedDiagnostics.get(0);
        assertEquals(Diagnostic.Kind.WARNING, diag.kind());
        assertTrue(diag.message().contains("module 'module-core' is being rewritten with a completely different set"),
            "warning must identify module: " + diag.message());
        assertTrue(diag.message().contains("A, B") || (diag.message().contains("A") && diag.message().contains("B")),
            "warning must list lost elements");
        assertTrue(diag.message().contains("C, D") || (diag.message().contains("C") && diag.message().contains("D")),
            "warning must list gained elements");

        List<ILoggingEvent> warnLogs = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnLogs.size(), "wholesale replacement must log one WARN event");
        assertEquals("rewrite.replace module={} lost={} gained={}", warnLogs.get(0).getMessage());
    }

    @Test
    void regionReplaced_moreThanEightElements_summarisesWithCount() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        Set<String> previous = new LinkedHashSet<>();
        for (int i = 1; i <= 10; i++) {
            previous.add("prev" + i);
        }
        Set<String> next = new LinkedHashSet<>();
        for (int i = 1; i <= 9; i++) {
            next.add("next" + i);
        }

        warner.regionReplaced("mod", previous, next);

        assertEquals(1, recordedDiagnostics.size());
        String msg = recordedDiagnostics.get(0).message();
        assertTrue(msg.contains("prev1, prev2, prev3, prev4, prev5, prev6, prev7, prev8, and 2 more"),
            "more than 8 previous elements must be summarised: " + msg);
        assertTrue(msg.contains("next1, next2, next3, next4, next5, next6, next7, next8, and 1 more"),
            "more than 8 new elements must be summarised: " + msg);
    }

    @Test
    void regionReplaced_nullMessagerOrLogger_handlesSafely() {
        DestructiveRewriteWarner onlyLog = new DestructiveRewriteWarner(null, logger);
        onlyLog.regionReplaced("mod", Set.of("A"), Set.of("B"));
        assertEquals(1, appender.list.size(), "logger must still receive warning when messager is null");

        DestructiveRewriteWarner onlyMsg = new DestructiveRewriteWarner(messager, null);
        onlyMsg.regionReplaced("mod", Set.of("A"), Set.of("B"));
        assertEquals(1, recordedDiagnostics.size(), "messager must still receive warning when logger is null");

        DestructiveRewriteWarner neither = new DestructiveRewriteWarner(null, null);
        neither.regionReplaced("mod", Set.of("A"), Set.of("B")); // must not throw
    }

    @Test
    void orphanSweep_emptyRemoved_isSilent() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.orphanSweep(".claude/rules", List.of(), Set.of("A"));
        assertTrue(recordedDiagnostics.isEmpty(), "empty removed collection must emit nothing");
        assertTrue(appender.list.isEmpty(), "no log on empty removed collection");
    }

    @Test
    void orphanSweep_removedLessOrEqualToWritten_emitsNoteAndInfo() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.orphanSweep(".cursor/rules", List.of("oldA", "oldB"), Set.of("newA", "newB", "newC"));

        assertEquals(1, recordedDiagnostics.size(), "ordinary sweep must emit one NOTE diagnostic");
        DiagnosticRecord diag = recordedDiagnostics.get(0);
        assertEquals(Diagnostic.Kind.NOTE, diag.kind());
        assertTrue(diag.message().contains("removed 2 orphaned scoped rule file(s) under .cursor/rules (oldA, oldB)"),
            "note must report removed count and scope: " + diag.message());

        List<ILoggingEvent> infoLogs = appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infoLogs.size(), "must log one INFO event");
        assertEquals("granular.remove scope={} count={} written={} removed={}", infoLogs.get(0).getMessage());

        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
            "no WARN log when removed <= written");
    }

    @Test
    void orphanSweep_removedEqualToWritten_isStillANote() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.orphanSweep(".cursor/rules", List.of("oldA", "oldB"), Set.of("newA", "newB"));

        assertEquals(1, recordedDiagnostics.size(), "a one-for-one sweep must emit one diagnostic");
        assertEquals(Diagnostic.Kind.NOTE, recordedDiagnostics.get(0).kind(),
            "removing exactly as many files as were written is an ordinary sweep, not a destructive one");
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
            "no WARN log when removed == written");
    }

    @Test
    void orphanSweep_removedMoreThanWritten_warns() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        warner.orphanSweep("the reactor root", List.of("old1", "old2", "old3"), Set.of("new1"));

        assertEquals(1, recordedDiagnostics.size(), "destructive sweep must emit one WARNING diagnostic");
        DiagnosticRecord diag = recordedDiagnostics.get(0);
        assertEquals(Diagnostic.Kind.WARNING, diag.kind());
        assertTrue(diag.message().contains("removed 3 scoped rule file(s) under the reactor root"),
            "warning must report removed count and scope: " + diag.message());
        assertTrue(diag.message().contains("while writing only 1"),
            "warning must state written count: " + diag.message());

        List<ILoggingEvent> infoLogs = appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infoLogs.size(), "must log INFO granular.remove");

        List<ILoggingEvent> warnLogs = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnLogs.size(), "must log WARN granular.sweep");
        assertEquals("granular.sweep scope={} removed={} written={} reason=removed-more-than-written",
            warnLogs.get(0).getMessage());
    }

    @Test
    void orphanSweep_moreThanEightRemoved_summarisesWithCount() {
        DestructiveRewriteWarner warner = new DestructiveRewriteWarner(messager, logger);

        List<String> removed = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            removed.add("elem" + i);
        }

        warner.orphanSweep(".claude/rules", removed, Set.of("new1"));

        assertEquals(1, recordedDiagnostics.size());
        String msg = recordedDiagnostics.get(0).message();
        assertTrue(msg.contains("elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, and 3 more"),
            "more than 8 removed elements must be summarised: " + msg);
    }

    @Test
    void orphanSweep_nullMessagerOrLogger_handlesSafely() {
        DestructiveRewriteWarner onlyLog = new DestructiveRewriteWarner(null, logger);
        onlyLog.orphanSweep("dir", List.of("old1", "old2"), Set.of("new1"));
        assertEquals(2, appender.list.size(), "INFO and WARN logged when messager is null");

        DestructiveRewriteWarner onlyMsg = new DestructiveRewriteWarner(messager, null);
        onlyMsg.orphanSweep("dir", List.of("old1", "old2"), Set.of("new1"));
        assertEquals(1, recordedDiagnostics.size(), "WARNING emitted when logger is null");

        DestructiveRewriteWarner neither = new DestructiveRewriteWarner(null, null);
        neither.orphanSweep("dir", List.of("old1"), Set.of("new1")); // must not throw
    }
}
