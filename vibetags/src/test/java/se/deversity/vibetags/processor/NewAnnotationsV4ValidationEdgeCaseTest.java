package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.*;

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
 * Tests validation branches not covered by NewAnnotationsV4ValidationTest.
 */
class NewAnnotationsV4ValidationEdgeCaseTest {

    @Test
    void validateAnnotations_legacyBridgeAndDraft_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Bridge");
        when(element.getAnnotation(AILegacyBridge.class)).thenReturn(mock(AILegacyBridge.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILegacyBridge.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AILegacyBridge") && w.contains("@AIDraft")));
    }

    @Test
    void validateAnnotations_publicApiAndLocked_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Api");
        when(element.getAnnotation(AIPublicAPI.class)).thenReturn(mock(AIPublicAPI.class));
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        when(element.getModifiers()).thenReturn(Set.of(javax.lang.model.element.Modifier.PUBLIC));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIPublicAPI.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIPublicAPI") && w.contains("@AILocked")));
    }

    @Test
    void validateAnnotations_parallelTestsAndLocked_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Test");
        when(element.getAnnotation(AIParallelTests.class)).thenReturn(mock(AIParallelTests.class));
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIParallelTests.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIParallelTests") && w.contains("@AILocked")));
    }

    @Test
    void validateAnnotations_schemaSafeAndIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Schema");
        when(element.getAnnotation(AISchemaSafe.class)).thenReturn(mock(AISchemaSafe.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISchemaSafe.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AISchemaSafe") && w.contains("@AIIgnore")));
    }

    @Test
    void validateAnnotations_strictClasspathAndLocked_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.CP");
        when(element.getAnnotation(AIStrictClasspath.class)).thenReturn(mock(AIStrictClasspath.class));
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIStrictClasspath.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIStrictClasspath") && w.contains("@AILocked")));
    }

    @Test
    void validateAnnotations_featureFlagAndLocked_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.FF");
        AIFeatureFlag ff = mock(AIFeatureFlag.class);
        when(ff.flag()).thenReturn("some.flag");
        when(element.getAnnotation(AIFeatureFlag.class)).thenReturn(ff);
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIFeatureFlag.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIFeatureFlag") && w.contains("@AILocked")));
    }

    @Test
    void validateAnnotations_featureFlagBlankFlag_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.FF");
        AIFeatureFlag ff = mock(AIFeatureFlag.class);
        when(ff.flag()).thenReturn("  ");
        when(element.getAnnotation(AIFeatureFlag.class)).thenReturn(ff);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIFeatureFlag.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIFeatureFlag") && w.contains("blank 'flag'")));
    }

    @Test
    void validateAnnotations_idempotentAndDraft_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Idem");
        when(element.getAnnotation(AIIdempotent.class)).thenReturn(mock(AIIdempotent.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIIdempotent.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIIdempotent") && w.contains("@AIDraft")));
    }

    @Test
    void validateAnnotations_architectureBlankBelongsTo_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Arch");
        AIArchitecture arch = mock(AIArchitecture.class);
        when(arch.belongsTo()).thenReturn(" ");
        when(arch.cannotReference()).thenReturn(new String[0]);
        when(element.getAnnotation(AIArchitecture.class)).thenReturn(arch);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIArchitecture.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIArchitecture") && w.contains("blank 'belongsTo'")));
    }

    @Test
    void validateAnnotations_secureBlankAspect_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Secure");
        AISecure sec = mock(AISecure.class);
        when(sec.aspect()).thenReturn("");
        when(element.getAnnotation(AISecure.class)).thenReturn(sec);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISecure.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AISecure") && w.contains("blank 'aspect'")));
    }

    @Test
    void validateAnnotations_secureAndIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Secure");
        AISecure sec = mock(AISecure.class);
        when(sec.aspect()).thenReturn("auth");
        when(element.getAnnotation(AISecure.class)).thenReturn(sec);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISecure.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AISecure") && w.contains("@AIIgnore")));
    }

    private static Messager capturingMessager(List<String> sink) {
        Messager m = mock(Messager.class);
        doAnswer(inv -> {
            if (Diagnostic.Kind.WARNING.equals(inv.getArgument(0))) {
                sink.add(inv.getArgument(1, CharSequence.class).toString());
            }
            return null;
        }).when(m).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class), any());
        
        doAnswer(inv -> {
            if (Diagnostic.Kind.WARNING.equals(inv.getArgument(0))) {
                sink.add(inv.getArgument(1, CharSequence.class).toString());
            }
            return null;
        }).when(m).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class));
        
        return m;
    }
}
