package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

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
 * Direct unit tests for the 12 new annotations and their compile-time validation rules.
 */
class NewAnnotationsV4ValidationTest {

    @Test
    void validateAnnotations_sandboxOnlyAndDomainModel_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SandboxMock");
        when(element.getAnnotation(AISandboxOnly.class)).thenReturn(mock(AISandboxOnly.class));
        when(element.getAnnotation(AIDomainModel.class)).thenReturn(mock(AIDomainModel.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISandboxOnly.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("@AISandboxOnly") && w.contains("@AIDomainModel"));
    }

    @Test
    void validateAnnotations_sunsetAndDraft_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SunsetClass");
        AISunset sunset = mock(AISunset.class);
        when(sunset.jira()).thenReturn("PROJ-123");
        when(element.getAnnotation(AISunset.class)).thenReturn(sunset);
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISunset.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("@AISunset") && w.contains("@AIDraft"));
    }

    @Test
    void validateAnnotations_secureLoggingAndIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.IgnoredField");
        when(element.getAnnotation(AISecureLogging.class)).thenReturn(mock(AISecureLogging.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISecureLogging.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("@AISecureLogging") && w.contains("@AIIgnore"));
    }

    @Test
    void validateAnnotations_sunsetBlankJira_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AISunset sunset = mock(AISunset.class);
        when(sunset.jira()).thenReturn(" "); // blank

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SunsetMethod");
        when(element.getAnnotation(AISunset.class)).thenReturn(sunset);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AISunset.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("@AISunset") && w.contains("jira"));
    }

    @Test
    void validateAnnotations_temporaryBlankExpiresOn_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITemporary temp = mock(AITemporary.class);
        when(temp.expiresOn()).thenReturn(""); // blank

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.TempHack");
        when(element.getAnnotation(AITemporary.class)).thenReturn(temp);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITemporary.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("@AITemporary") && w.contains("expiresOn"));
    }

    @Test
    void validateAnnotations_temporaryMalformedDate_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITemporary temp = mock(AITemporary.class);
        when(temp.expiresOn()).thenReturn("2026/05/29"); // invalid format, must be YYYY-MM-DD

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.TempHack");
        when(element.getAnnotation(AITemporary.class)).thenReturn(temp);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITemporary.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("invalid 'expiresOn' date format"));
    }

    @Test
    void validateAnnotations_temporaryExpiredDate_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITemporary temp = mock(AITemporary.class);
        when(temp.expiresOn()).thenReturn("2020-01-01"); // expired date
        when(temp.reason()).thenReturn("Expired hotfix");

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.OldTempHack");
        when(element.getAnnotation(AITemporary.class)).thenReturn(temp);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITemporary.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("has expired on 2020-01-01") && w.contains("Expired hotfix"));
    }

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
