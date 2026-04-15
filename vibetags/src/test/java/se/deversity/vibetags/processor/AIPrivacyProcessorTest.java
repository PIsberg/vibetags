package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for @AIPrivacy annotation definition and processing in AIGuardrailProcessor.
 */
class AIPrivacyProcessorTest {

    // -----------------------------------------------------------------------
    // Annotation definition
    // -----------------------------------------------------------------------

    @Test
    void annotation_isOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("se.deversity.vibetags.annotations.AIPrivacy"),
            "@AIPrivacy must be on the processor classpath");
    }

    @Test
    void annotation_hasSourceRetention() throws Exception {
        Class<?> cls = AIPrivacy.class;
        java.lang.annotation.Retention retention = cls.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention, "@AIPrivacy must declare @Retention");
        assertEquals(java.lang.annotation.RetentionPolicy.SOURCE, retention.value(),
            "@AIPrivacy must use SOURCE retention for zero runtime overhead");
    }

    @Test
    void annotation_targetsTypeMethodAndField() throws Exception {
        java.lang.annotation.Target target = AIPrivacy.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target, "@AIPrivacy must declare @Target");
        assertArrayEquals(
            new java.lang.annotation.ElementType[]{
                java.lang.annotation.ElementType.TYPE,
                java.lang.annotation.ElementType.METHOD,
                java.lang.annotation.ElementType.FIELD
            },
            target.value(),
            "@AIPrivacy must target TYPE, METHOD, and FIELD"
        );
    }

    @Test
    void annotation_hasReasonAttributeWithDefault() throws Exception {
        java.lang.reflect.Method reason = AIPrivacy.class.getDeclaredMethod("reason");
        assertNotNull(reason.getDefaultValue(), "reason() must have a default value");
        String defaultReason = (String) reason.getDefaultValue();
        assertFalse(defaultReason.isBlank(), "default reason must not be blank");
        assertTrue(defaultReason.contains("PII"), "default reason should mention PII");
    }

    // -----------------------------------------------------------------------
    // Validation: @AIPrivacy + @AIIgnore on the same element
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_privacyAndIgnore_emitsRedundancyWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.UserProfile.email");
        when(element.getAnnotation(AIPrivacy.class)).thenReturn(mock(AIPrivacy.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size(), "Should emit exactly one warning");
        assertTrue(warnings.get(0).contains("@AIPrivacy and @AIIgnore"),
            "Warning should mention both annotations");
        assertTrue(warnings.get(0).contains("redundant"),
            "Warning should say @AIPrivacy is redundant");
    }

    @Test
    void validateAnnotations_privacyWithoutIgnore_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.UserProfile.email");
        when(element.getAnnotation(AIPrivacy.class)).thenReturn(mock(AIPrivacy.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIPrivacy is used without @AIIgnore");
    }

    // -----------------------------------------------------------------------
    // process() — PII sections written to each platform file
    // -----------------------------------------------------------------------

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToCursorRules() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.User.email", "GDPR personal email"));
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("PII / PRIVACY GUARDRAILS"),
                ".cursorrules must have PII guardrails section");
            assertTrue(content.contains("com.example.User.email"),
                ".cursorrules must list the annotated element");
            assertTrue(content.contains("GDPR personal email"),
                ".cursorrules must include the privacy reason");
            assertTrue(content.toLowerCase().contains("never"),
                ".cursorrules must contain a prohibition directive");
        });
    }

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToClaudeMd() throws Exception {
        withCwdSignalFiles(List.of("CLAUDE.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.User.ssn", "SSN - PII"));
            triggerGeneration(processor);

            String content = processor.contentFor("CLAUDE.md");
            assertTrue(content.contains("<pii_guardrails>"),
                "CLAUDE.md must contain <pii_guardrails> XML element");
            assertTrue(content.contains("</pii_guardrails>"),
                "CLAUDE.md must close <pii_guardrails>");
            assertTrue(content.contains("com.example.User.ssn"),
                "CLAUDE.md must list the annotated element");
            assertTrue(content.contains("SSN - PII"),
                "CLAUDE.md must include the privacy reason");
            assertTrue(content.contains("confidential"),
                "CLAUDE.md rule must mention confidential treatment");
        });
    }

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToAgentsMd() throws Exception {
        withCwdSignalFiles(List.of("AGENTS.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.Patient.dob", "HIPAA date of birth"));
            triggerGeneration(processor);

            String content = processor.contentFor("AGENTS.md");
            assertTrue(content.contains("PII / PRIVACY GUARDRAILS"),
                "AGENTS.md must have PII guardrails section");
            assertTrue(content.contains("com.example.Patient.dob"),
                "AGENTS.md must list the annotated element");
            assertTrue(content.contains("HIPAA date of birth"),
                "AGENTS.md must include the privacy reason");
        });
    }

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToGemini() throws Exception {
        withCwdSignalFiles(List.of("gemini_instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.Order.cardNumber", "PCI-DSS card data"));
            triggerGeneration(processor);

            String content = processor.contentFor("gemini_instructions.md");
            assertTrue(content.contains("PII / PRIVACY GUARDRAILS"),
                "gemini_instructions.md must have PII guardrails section");
            assertTrue(content.contains("com.example.Order.cardNumber"),
                "gemini_instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToCopilot() throws Exception {
        withCwdSignalFiles(List.of(".github/copilot-instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.User.address", "Home address PII"));
            triggerGeneration(processor);

            String content = processor.contentFor("copilot-instructions.md");
            assertTrue(content.contains("PII / Privacy Guardrails"),
                "copilot-instructions.md must have PII guardrails section");
            assertTrue(content.contains("com.example.User.address"),
                "copilot-instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withPrivacyAnnotation_writesPiiSectionToQwen() throws Exception {
        withCwdSignalFiles(List.of("QWEN.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), privacyRoundEnv("com.example.Employee.salary", "Salary - confidential"));
            triggerGeneration(processor);

            String content = processor.contentFor("QWEN.md");
            assertTrue(content.contains("PII / PRIVACY GUARDRAILS"),
                "QWEN.md must have PII guardrails section");
            assertTrue(content.contains("com.example.Employee.salary"),
                "QWEN.md must list the annotated element");
        });
    }

    @Test
    void process_noPrivacyAnnotations_noPiiSectionWritten() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), emptyRoundEnv());
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertFalse(content.contains("PII / PRIVACY GUARDRAILS"),
                "No PII section should appear when no @AIPrivacy annotations are present");
        });
    }

    @Test
    void process_multiplePrivacyElements_allListedInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            Element el1 = privacyElement("com.example.User.email", "email address");
            Element el2 = privacyElement("com.example.User.phone", "phone number");

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            when(roundEnv.processingOver()).thenReturn(false);
            doReturn(Set.of(el1, el2)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("com.example.User.email"), "Must list first element");
            assertTrue(content.contains("com.example.User.phone"), "Must list second element");
        });
    }

    @Test
    void process_privacyAnnotation_defaultReasonAppearsInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            Element element = mock(Element.class);
            when(element.toString()).thenReturn("com.example.Profile.birthDate");
            when(element.getSimpleName()).thenReturn(nameOf("birthDate"));
            when(element.getKind()).thenReturn(ElementKind.FIELD);
            AIPrivacy privacy = mock(AIPrivacy.class);
            when(privacy.reason()).thenReturn("Contains PII - never log, expose, or include values in suggestions.");
            when(element.getAnnotation(AIPrivacy.class)).thenReturn(privacy);

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            when(roundEnv.processingOver()).thenReturn(false);
            doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

            CapturingProcessor processor = makeCapturingProcessor(List.of());
            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("Contains PII"),
                "Default reason must appear in output");
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Processor subclass that captures generated content in-memory keyed by the
     * last path segment (filename), instead of actually writing to disk.
     * Lets tests assert on generated content without any file I/O complications.
     */
    static class CapturingProcessor extends AIGuardrailProcessor {
        final java.util.Map<String, String> captured = new java.util.LinkedHashMap<>();

        @Override
        public boolean writeFileIfChanged(String path, String content) {
            // Key by filename for easy lookup in assertions
            String key = java.nio.file.Paths.get(path).getFileName().toString();
            captured.put(key, content);
            return true;
        }

        String contentFor(String filename) {
            return captured.getOrDefault(filename, "");
        }
    }

    /**
     * Creates a {@link CapturingProcessor} and temporarily places the given
     * signal files in the CWD so that {@code resolveActiveServices()} picks them up.
     * Files are deleted after the test via the returned {@link AutoCloseable}.
     */
    private CapturingProcessor makeCapturingProcessor(List<java.nio.file.Path> cwdSignalFiles) {
        CapturingProcessor processor = new CapturingProcessor();
        Messager messager = noopMessager();
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        when(env.getOptions()).thenReturn(java.util.Map.of());
        processor.init(env);
        return processor;
    }

    /**
     * Creates the given signal files in the CWD (vibetags/) if they don't exist,
     * runs the block, then deletes any files this method created.
     */
    private void withCwdSignalFiles(List<String> relPaths, ThrowingRunnable block) throws Exception {
        java.nio.file.Path cwd = java.nio.file.Paths.get("").toAbsolutePath();
        List<java.nio.file.Path> created = new ArrayList<>();
        try {
            for (String rel : relPaths) {
                java.nio.file.Path p = cwd.resolve(rel);
                if (!Files.exists(p)) {
                    Files.createDirectories(p.getParent());
                    Files.createFile(p);
                    created.add(p);
                }
            }
            block.run();
        } finally {
            for (java.nio.file.Path p : created) {
                Files.deleteIfExists(p);
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Element privacyElement(String qualifiedName, String reason) {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn(qualifiedName);
        when(element.getKind()).thenReturn(ElementKind.CLASS);
        String simpleName = qualifiedName.contains(".")
            ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
            : qualifiedName;
        when(element.getSimpleName()).thenReturn(nameOf(simpleName));
        AIPrivacy privacy = mock(AIPrivacy.class);
        when(privacy.reason()).thenReturn(reason);
        when(element.getAnnotation(AIPrivacy.class)).thenReturn(privacy);
        return element;
    }

    private static RoundEnvironment privacyRoundEnv(String qualifiedName, String reason) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);

        Element element = privacyElement(qualifiedName, reason);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        return roundEnv;
    }

    private static RoundEnvironment emptyRoundEnv() {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        return roundEnv;
    }

    /** Calls process() with processingOver=true to trigger file generation. */
    private static void triggerGeneration(AIGuardrailProcessor processor) {
        RoundEnvironment genEnv = mock(RoundEnvironment.class);
        when(genEnv.processingOver()).thenReturn(true);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIDraft.class);
        processor.process(Set.of(), genEnv);
    }

    private static Name nameOf(String value) {
        return new Name() {
            public boolean contentEquals(CharSequence cs) { return value.contentEquals(cs); }
            public int length() { return value.length(); }
            public char charAt(int index) { return value.charAt(index); }
            public CharSequence subSequence(int start, int end) { return value.subSequence(start, end); }
            public String toString() { return value; }
        };
    }

    private static Messager noopMessager() {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) {}
        };
    }

    private static Messager capturingMessager(Diagnostic.Kind capture, List<String> sink) {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == capture) sink.add(msg.toString());
            }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) { printMessage(kind, msg); }
        };
    }
}
