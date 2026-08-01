package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.validation.ArchitectureRule;
import se.deversity.vibetags.processor.internal.validation.AttributeRule;
import se.deversity.vibetags.processor.internal.validation.PairRule;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rule primitives on their own, without a compiler in the loop.
 *
 * <p>The end-to-end validation tests compile real sources, which is the right way to prove a check
 * fires. It is the wrong way to reach the branches that only open when javac misbehaves — a
 * {@code getAnnotation} that returns null for an element javac just handed back, a Tree API that
 * throws. Those are what this covers, because they are also the branches that decide whether a
 * broken environment produces a degraded build or a failed one.
 */
class ValidationRuleUnitTest {

    /** Collects what a rule reported, in order, as {@code KIND|message}. */
    private static final class Recorder {
        private final List<String> messages = new ArrayList<>();
        private final ValidationContext ctx;

        Recorder(Element element, Class<? extends java.lang.annotation.Annotation> scans) {
            Messager messager = mock(Messager.class);
            doAnswer(invocation -> {
                messages.add(invocation.getArgument(0) + "|" + invocation.getArgument(1));
                return null;
            }).when(messager).printMessage(any(Diagnostic.Kind.class), anyString(), any(Element.class));

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            doAnswer(invocation -> Set.of(element)).when(roundEnv).getElementsAnnotatedWith(scans);
            ctx = new ValidationContext(messager, roundEnv, null, null);
        }
    }

    // ------------------------------------------------------------------ PairRule

    @Test
    void pairRule_reportsANoteRatherThanAWarning_whenBuiltWithNote() {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Mirror");
        when(element.getAnnotation(AIContract.class)).thenReturn(mock(AIContract.class));
        Recorder recorder = new Recorder(element, AIKeepInSync.class);

        PairRule.note(AIKeepInSync.class, AIContract.class, " carries both. Verify the mirrors.")
            .check(recorder.ctx, element);

        assertEquals(List.of("NOTE|VibeTags: com.example.Mirror carries both. Verify the mirrors."),
            recorder.messages,
            "note() must report at NOTE, not WARNING — the pair is suspicious, not wrong");
    }

    @Test
    void pairRule_saysNothing_whenTheOtherAnnotationIsAbsent() {
        Element element = mock(Element.class);
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);
        Recorder recorder = new Recorder(element, AILocked.class);

        PairRule.warn(AILocked.class, AIDraft.class, " is contradictory.")
            .check(recorder.ctx, element);

        assertTrue(recorder.messages.isEmpty(),
            "A rule that fires on one annotation alone is noise everybody filters out: " + recorder.messages);
    }

    @Test
    void pairRule_exposesBothEndsOfThePair() {
        PairRule rule = PairRule.warn(AILocked.class, AIDraft.class, " is contradictory.");

        assertEquals(AILocked.class, rule.scans());
        assertEquals(AIDraft.class, rule.other());
    }

    // ------------------------------------------------------------------ AttributeRule

    @Test
    void attributeRule_skipsAnElementWhoseAnnotationTheCompilerWillNotHandBack() {
        // Mocked and non-javac environments return elements from getElementsAnnotatedWith whose
        // getAnnotation is null. Dereferencing that would turn a degraded environment into a
        // failed build, and VibeTags is advisory — it must never be the thing that breaks a compile.
        Element element = mock(Element.class);
        when(element.getAnnotation(AILocked.class)).thenReturn(null);
        Recorder recorder = new Recorder(element, AILocked.class);
        AtomicInteger bodyRuns = new AtomicInteger();

        AttributeRule.of(AILocked.class, (ctx, e, a) -> {
            bodyRuns.incrementAndGet();
            ctx.warn(e, "should never be reported");
        }).check(recorder.ctx, element);

        assertEquals(0, bodyRuns.get(), "The rule body must not run without its annotation");
        assertTrue(recorder.messages.isEmpty(), "Nothing should be reported: " + recorder.messages);
    }

    @Test
    void attributeRule_runsTheBody_whenTheAnnotationIsPresent() {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Guarded");
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        Recorder recorder = new Recorder(element, AILocked.class);

        AttributeRule.of(AILocked.class, (ctx, e, a) -> ctx.warn(e, "reported for " + e))
            .check(recorder.ctx, element);

        assertEquals(List.of("WARNING|VibeTags: reported for com.example.Guarded"), recorder.messages);
    }

    // ------------------------------------------------------------------ ArchitectureRule

    @Test
    void architectureRule_reportsTheToolingGap_whenTheTreeApiThrows() {
        // Under a compiler with no Tree API, a layering rule going unchecked must be visible.
        // Reporting it as a NOTE rather than swallowing it is the difference between "not checked"
        // and "checked and clean", which is the distinction the whole enforcement story rests on.
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Layer");
        se.deversity.vibetags.annotations.AIArchitecture arch =
            mock(se.deversity.vibetags.annotations.AIArchitecture.class);
        when(arch.belongsTo()).thenReturn("service");
        when(arch.cannotReference()).thenReturn(new String[]{"com.example.forbidden"});
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(arch);

        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        Messager messager = mock(Messager.class);
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0) + "|" + invocation.getArgument(1));
            return null;
        }).when(messager).printMessage(any(Diagnostic.Kind.class), anyString(), any(Element.class));
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);

        // Trees.instance(env) on a mocked ProcessingEnvironment throws rather than returning null.
        new ArchitectureRule().check(new ValidationContext(messager, roundEnv, env, null), element);

        assertTrue(messages.stream().anyMatch(m -> m.startsWith("NOTE|") && m.contains("Trees API not available")),
            "Expected a NOTE that the import scan could not run. Messages: " + messages);
    }

    @Test
    void architectureRule_saysNothingAboutTooling_whenThereIsNothingToScanFor() {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Layer");
        se.deversity.vibetags.annotations.AIArchitecture arch =
            mock(se.deversity.vibetags.annotations.AIArchitecture.class);
        when(arch.belongsTo()).thenReturn("service");
        when(arch.cannotReference()).thenReturn(new String[0]);
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(arch);
        Recorder recorder = new Recorder(element, se.deversity.vibetags.annotations.AIArchitecture.class);

        new ArchitectureRule().check(recorder.ctx, element);

        assertTrue(recorder.messages.isEmpty(),
            "No forbidden packages means no scan, and therefore nothing to report: " + recorder.messages);
    }

    @Test
    void architectureRule_stillWarnsAboutABlankLayer_whenTheTreeApiIsUnavailable() {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Layer");
        se.deversity.vibetags.annotations.AIArchitecture arch =
            mock(se.deversity.vibetags.annotations.AIArchitecture.class);
        when(arch.belongsTo()).thenReturn("");
        when(arch.cannotReference()).thenReturn(new String[0]);
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(arch);
        Recorder recorder = new Recorder(element, se.deversity.vibetags.annotations.AIArchitecture.class);

        new ArchitectureRule().check(recorder.ctx, element);

        assertTrue(recorder.messages.stream().anyMatch(m -> m.startsWith("WARNING|") && m.contains("belongsTo")),
            "The attribute check does not need a compiler and must fire regardless: " + recorder.messages);
    }

    @Test
    void architectureRule_skipsTheScan_whenThereIsNoProcessingEnvironment() {
        Element element = mock(Element.class);
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(null);
        Recorder recorder = new Recorder(element, se.deversity.vibetags.annotations.AIArchitecture.class);

        new ArchitectureRule().check(recorder.ctx, element);

        assertTrue(recorder.messages.isEmpty(),
            "An element without the annotation must produce nothing: " + recorder.messages);
    }
}
