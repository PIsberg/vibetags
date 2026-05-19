package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AIThreadSafe;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Direct unit tests for {@link se.deversity.vibetags.processor.internal.AnnotationValidator}
 * targeting the validation-warning branches not exercised by existing integration tests.
 */
class AnnotationValidatorUnitTest {

    // -----------------------------------------------------------------------
    // @AIDraft + @AIIgnore contradiction
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_draftAndIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.NotifService");
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size(), "Should emit exactly one warning for @AIDraft + @AIIgnore");
        String w = warnings.get(0);
        assertTrue(w.contains("@AIDraft") && w.contains("@AIIgnore"),
            "Warning must mention both @AIDraft and @AIIgnore: " + w);
    }

    @Test
    void validateAnnotations_draftWithoutIgnore_noContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(), "No warning when @AIDraft is used without @AIIgnore");
    }

    // -----------------------------------------------------------------------
    // @AIContext with blank focus AND avoids
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_aiContext_bothBlank_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIContext ctx = mock(AIContext.class);
        when(ctx.focus()).thenReturn("   ");  // blank
        when(ctx.avoids()).thenReturn("");    // blank

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Parser");
        when(element.getAnnotation(AIContext.class)).thenReturn(ctx);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContext.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size(), "Should emit one warning when both focus and avoids are blank");
        String w = warnings.get(0);
        assertTrue(w.contains("@AIContext"),
            "Warning must mention @AIContext: " + w);
        assertTrue(w.toLowerCase().contains("blank") || w.toLowerCase().contains("ignored"),
            "Warning must describe the no-op state: " + w);
    }

    @Test
    void validateAnnotations_aiContext_onlyFocusBlank_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIContext ctx = mock(AIContext.class);
        when(ctx.focus()).thenReturn("");          // blank
        when(ctx.avoids()).thenReturn("regex");    // not blank

        Element element = mock(Element.class);
        when(element.getAnnotation(AIContext.class)).thenReturn(ctx);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContext.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when at least one of focus/avoids is non-blank");
    }

    @Test
    void validateAnnotations_aiContext_onlyAvoidsBlank_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIContext ctx = mock(AIContext.class);
        when(ctx.focus()).thenReturn("memory usage");  // not blank
        when(ctx.avoids()).thenReturn("");              // blank

        Element element = mock(Element.class);
        when(element.getAnnotation(AIContext.class)).thenReturn(ctx);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContext.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when focus is non-blank even if avoids is blank");
    }

    // -----------------------------------------------------------------------
    // @AIDeprecated with blank replacedBy
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_aiDeprecated_blankReplacedBy_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIDeprecated dep = mock(AIDeprecated.class);
        when(dep.replacedBy()).thenReturn("");

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.OldService");
        when(element.getAnnotation(AILocked.class)).thenReturn(null);
        when(element.getAnnotation(AIDeprecated.class)).thenReturn(dep);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIDeprecated.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIDeprecated") && w.contains("replacedBy")),
            "Should warn when @AIDeprecated has blank replacedBy. Warnings: " + warnings);
    }

    @Test
    void validateAnnotations_aiDeprecated_nonBlankReplacedBy_noReplacedByWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIDeprecated dep = mock(AIDeprecated.class);
        when(dep.replacedBy()).thenReturn("com.example.NewService");

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.OldService");
        when(element.getAnnotation(AILocked.class)).thenReturn(null);
        when(element.getAnnotation(AIDeprecated.class)).thenReturn(dep);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIDeprecated.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().noneMatch(w -> w.contains("replacedBy")),
            "No replacedBy warning when replacedBy is non-blank. Warnings: " + warnings);
    }

    // -----------------------------------------------------------------------
    // @AIThreadSafe(IMMUTABLE) without @AIImmutable — no redundancy warning
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_aiThreadSafeImmutable_withoutAiImmutable_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIThreadSafe ts = mock(AIThreadSafe.class);
        when(ts.strategy()).thenReturn(AIThreadSafe.Strategy.IMMUTABLE);

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SafeType");
        when(element.getAnnotation(AIImmutable.class)).thenReturn(null);
        when(element.getAnnotation(AIThreadSafe.class)).thenReturn(ts);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIThreadSafe.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "@AIThreadSafe(IMMUTABLE) without @AIImmutable must not emit redundancy warning");
    }

    @Test
    void validateAnnotations_aiThreadSafe_nonImmutableStrategy_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIThreadSafe ts = mock(AIThreadSafe.class);
        when(ts.strategy()).thenReturn(AIThreadSafe.Strategy.SYNCHRONIZED);

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SyncType");
        when(element.getAnnotation(AIImmutable.class)).thenReturn(mock(AIImmutable.class));
        when(element.getAnnotation(AIThreadSafe.class)).thenReturn(ts);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIThreadSafe.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "Non-IMMUTABLE @AIThreadSafe strategy with @AIImmutable must not emit redundancy warning");
    }

    // -----------------------------------------------------------------------
    // @AIObservability — false branch (at least one signal present)
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_aiObservability_withMetrics_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIObservability obs = mock(AIObservability.class);
        when(obs.metrics()).thenReturn(new String[]{"payment.count"});
        when(obs.traces()).thenReturn(new String[]{});
        when(obs.logs()).thenReturn(new String[]{});

        Element element = mock(Element.class);
        when(element.getAnnotation(AIObservability.class)).thenReturn(obs);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIObservability.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIObservability declares at least one metric");
    }

    @Test
    void validateAnnotations_aiObservability_withTracesOnly_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIObservability obs = mock(AIObservability.class);
        when(obs.metrics()).thenReturn(new String[]{});
        when(obs.traces()).thenReturn(new String[]{"checkout.span"});
        when(obs.logs()).thenReturn(new String[]{});

        Element element = mock(Element.class);
        when(element.getAnnotation(AIObservability.class)).thenReturn(obs);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIObservability.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIObservability declares at least one trace span");
    }

    // -----------------------------------------------------------------------
    // @AIRegulation — false branch (non-blank standard)
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_aiRegulation_nonBlankStandard_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AIRegulation reg = mock(AIRegulation.class);
        when(reg.standard()).thenReturn("GDPR");

        Element element = mock(Element.class);
        when(element.getAnnotation(AIRegulation.class)).thenReturn(reg);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIRegulation.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIRegulation has a non-blank standard");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static Messager capturingMessager(List<String> sink) {
        Messager m = mock(Messager.class);
        doAnswer(inv -> {
            if (Diagnostic.Kind.WARNING.equals(inv.getArgument(0))) {
                sink.add(inv.getArgument(1, CharSequence.class).toString());
            }
            return null;
        }).when(m).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class));
        doAnswer(inv -> {
            if (Diagnostic.Kind.WARNING.equals(inv.getArgument(0))) {
                sink.add(inv.getArgument(1, CharSequence.class).toString());
            }
            return null;
        }).when(m).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class), any());
        return m;
    }
}
