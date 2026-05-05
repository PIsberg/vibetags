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
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for @AIContract annotation definition, validation, and per-platform output.
 */
class AIContractProcessorTest {

    // -----------------------------------------------------------------------
    // Annotation definition
    // -----------------------------------------------------------------------

    @Test
    void annotation_isOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("se.deversity.vibetags.annotations.AIContract"),
            "@AIContract must be on the processor classpath");
    }

    @Test
    void annotation_hasSourceRetention() {
        java.lang.annotation.Retention retention = AIContract.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention, "@AIContract must declare @Retention");
        assertEquals(java.lang.annotation.RetentionPolicy.SOURCE, retention.value(),
            "@AIContract must use SOURCE retention for zero runtime overhead");
    }

    @Test
    void annotation_targetsTypeAndMethod() {
        java.lang.annotation.Target target = AIContract.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target, "@AIContract must declare @Target");
        assertArrayEquals(
            new java.lang.annotation.ElementType[]{
                java.lang.annotation.ElementType.TYPE,
                java.lang.annotation.ElementType.METHOD
            },
            target.value(),
            "@AIContract must target TYPE and METHOD only (not FIELD — fields have no 'signature')"
        );
    }

    @Test
    void annotation_hasReasonAttributeWithMeaningfulDefault() throws Exception {
        java.lang.reflect.Method reasonMethod = AIContract.class.getDeclaredMethod("reason");
        String defaultReason = (String) reasonMethod.getDefaultValue();
        assertNotNull(defaultReason, "reason() must have a default value");
        assertFalse(defaultReason.isBlank(), "default reason must not be blank");
        assertTrue(defaultReason.contains("signature") || defaultReason.contains("contractually"),
            "default reason should mention signature or contract");
    }

    // -----------------------------------------------------------------------
    // Validation: @AIContract + @AIDraft on the same element
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_contractAndDraft_emitsContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.PaymentGateway.charge(double)");
        when(element.getAnnotation(AIContract.class)).thenReturn(mock(AIContract.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

        processor.validateAnnotations(messager, roundEnv);

        assertEquals(1, warnings.size(), "Should emit exactly one warning");
        assertTrue(warnings.get(0).contains("@AIContract") && warnings.get(0).contains("@AIDraft"),
            "Warning should mention both annotations");
        assertTrue(warnings.get(0).toLowerCase().contains("contradict") || warnings.get(0).toLowerCase().contains("freez"),
            "Warning should characterise the contradiction");
    }

    @Test
    void validateAnnotations_contractWithoutDraft_noContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.PaymentGateway.charge(double)");
        when(element.getAnnotation(AIContract.class)).thenReturn(mock(AIContract.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIContract is used without @AIDraft");
    }

    // -----------------------------------------------------------------------
    // Validation: @AIContract + @AILocked on the same element
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_contractAndLocked_emitsOverlapWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.LegacyApi.process()");
        when(element.getAnnotation(AIContract.class)).thenReturn(mock(AIContract.class));
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);

        // @AILocked validator also fires for this element — stub for it
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AIContract") && w.contains("@AILocked")),
            "Should warn about @AIContract + @AILocked overlap");
        assertTrue(warnings.stream().anyMatch(w -> w.toLowerCase().contains("overlap") || w.toLowerCase().contains("prohibit") || w.toLowerCase().contains("intent")),
            "Warning should explain why the combination is problematic");
    }

    @Test
    void validateAnnotations_contractWithoutLocked_noOverlapWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.PaymentGateway.charge(double)");
        when(element.getAnnotation(AIContract.class)).thenReturn(mock(AIContract.class));
        when(element.getAnnotation(AILocked.class)).thenReturn(null);
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

        processor.validateAnnotations(messager, roundEnv);

        assertFalse(warnings.stream().anyMatch(w -> w.contains("@AIContract") && w.contains("@AILocked")),
            "No overlap warning when @AIContract is used without @AILocked");
    }

    // -----------------------------------------------------------------------
    // process() — contract sections written to each platform file
    // -----------------------------------------------------------------------

    @Test
    void process_withContractAnnotation_writesContractSectionToCursorRules() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.PricingService.calculatePrice", "OpenAPI v2 contract"));
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("CONTRACT-FROZEN SIGNATURES"),
                ".cursorrules must have contract-frozen section");
            assertTrue(content.contains("com.example.PricingService.calculatePrice"),
                ".cursorrules must list the annotated element");
            assertTrue(content.contains("OpenAPI v2 contract"),
                ".cursorrules must include the contract reason");
            assertTrue(content.toLowerCase().contains("must not"),
                ".cursorrules must contain a prohibition directive");
        });
    }

    @Test
    void process_withContractAnnotation_writesContractSectionToClaudeMd() throws Exception {
        withCwdSignalFiles(List.of("CLAUDE.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.BillingService.invoice", "Partner API SLA"));
            triggerGeneration(processor);

            String content = processor.contentFor("CLAUDE.md");
            assertTrue(content.contains("<contract_signatures>"),
                "CLAUDE.md must contain <contract_signatures> XML element");
            assertTrue(content.contains("</contract_signatures>"),
                "CLAUDE.md must close <contract_signatures>");
            assertTrue(content.contains("com.example.BillingService.invoice"),
                "CLAUDE.md must list the annotated element");
            assertTrue(content.contains("Partner API SLA"),
                "CLAUDE.md must include the contract reason");
            assertTrue(content.contains("signature"),
                "CLAUDE.md rule must mention signature freezing");
        });
    }

    @Test
    void process_withContractAnnotation_writesContractSectionToAgentsMd() throws Exception {
        withCwdSignalFiles(List.of("AGENTS.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.OrderApi.placeOrder", "Mobile app contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("AGENTS.md");
            assertTrue(content.contains("CONTRACT-FROZEN SIGNATURES"),
                "AGENTS.md must have contract-frozen section");
            assertTrue(content.contains("com.example.OrderApi.placeOrder"),
                "AGENTS.md must list the annotated element");
            assertTrue(content.contains("Mobile app contract"),
                "AGENTS.md must include the contract reason");
        });
    }

    @Test
    void process_withContractAnnotation_writesContractSectionToGemini() throws Exception {
        withCwdSignalFiles(List.of("gemini_instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.PaymentGateway.charge", "Bank partner API"));
            triggerGeneration(processor);

            String content = processor.contentFor("gemini_instructions.md");
            assertTrue(content.contains("CONTRACT-FROZEN SIGNATURES"),
                "gemini_instructions.md must have contract-frozen section");
            assertTrue(content.contains("com.example.PaymentGateway.charge"),
                "gemini_instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withContractAnnotation_writesContractSectionToCopilot() throws Exception {
        withCwdSignalFiles(List.of(".github/copilot-instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.ReportService.generate", "Analytics contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("copilot-instructions.md");
            assertTrue(content.contains("Contract-Frozen Signatures"),
                "copilot-instructions.md must have contract-frozen section");
            assertTrue(content.contains("com.example.ReportService.generate"),
                "copilot-instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withContractAnnotation_writesContractSectionToQwen() throws Exception {
        withCwdSignalFiles(List.of("QWEN.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.CatalogService.search", "Catalog API v3 contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("QWEN.md");
            assertTrue(content.contains("CONTRACT-FROZEN SIGNATURES"),
                "QWEN.md must have contract-frozen section");
            assertTrue(content.contains("com.example.CatalogService.search"),
                "QWEN.md must list the annotated element");
        });
    }

    @Test
    void process_noContractAnnotations_noContractSectionWritten() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), emptyRoundEnv());
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertFalse(content.contains("CONTRACT-FROZEN SIGNATURES"),
                "No contract section should appear when no @AIContract annotations are present");
        });
    }

    @Test
    void process_multipleContractElements_allListedInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            Element el1 = contractElement("com.example.PricingService.calculatePrice", "OpenAPI v2");
            Element el2 = contractElement("com.example.PricingService.applyPromoCode", "Promotions contract");

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            when(roundEnv.processingOver()).thenReturn(false);
            doReturn(Set.of(el1, el2)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AICore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPerformance.class);

            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("com.example.PricingService.calculatePrice"), "Must list first element");
            assertTrue(content.contains("com.example.PricingService.applyPromoCode"), "Must list second element");
        });
    }

    @Test
    void process_contractAnnotation_defaultReasonAppearsInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            Element element = mock(Element.class);
            when(element.toString()).thenReturn("com.example.ApiService.execute");
            when(element.getSimpleName()).thenReturn(nameOf("execute"));
            when(element.getKind()).thenReturn(ElementKind.METHOD);
            AIContract contract = mock(AIContract.class);
            when(contract.reason()).thenReturn("This signature is contractually frozen. Do not change method names, parameter types, parameter order, return types, or checked exceptions.");
            when(element.getAnnotation(AIContract.class)).thenReturn(contract);

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            when(roundEnv.processingOver()).thenReturn(false);
            doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("contractually frozen"),
                "Default reason must appear in output");
        });
    }

    @Test
    void process_contractAnnotation_llmsTxtContainsContractSection() throws Exception {
        withCwdSignalFiles(List.of("llms.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.PricingService.calculatePrice", "OpenAPI v2 contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("llms.txt");
            assertTrue(content.contains("Contract-Frozen Signatures"),
                "llms.txt must have Contract-Frozen Signatures section");
            assertTrue(content.contains("OpenAPI v2 contract"),
                "llms.txt must include the contract reason");
        });
    }

    @Test
    void process_contractAnnotation_llmsFullTxtContainsContractSection() throws Exception {
        withCwdSignalFiles(List.of("llms-full.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.PricingService.calculatePrice", "OpenAPI v2 contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("llms-full.txt");
            assertTrue(content.contains("Contract-Frozen Signatures"),
                "llms-full.txt must have Contract-Frozen Signatures section");
            assertTrue(content.contains("OpenAPI v2 contract"),
                "llms-full.txt must include the contract reason");
        });
    }

    @Test
    void process_contractAnnotation_aiderConventionsContainsContractSection() throws Exception {
        withCwdSignalFiles(List.of("CONVENTIONS.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), contractRoundEnv("com.example.BillingApi.invoice", "Partner SLA contract"));
            triggerGeneration(processor);

            String content = processor.contentFor("CONVENTIONS.md");
            assertTrue(content.contains("CONTRACT"),
                "CONVENTIONS.md must mention CONTRACT");
            assertTrue(content.contains("com.example.BillingApi.invoice"),
                "CONVENTIONS.md must list the annotated element");
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static class CapturingProcessor extends AIGuardrailProcessor {
        final java.util.Map<String, String> captured = new java.util.LinkedHashMap<>();

        @Override
        public boolean writeFileIfChanged(String path, String content, boolean hasNewRules) {
            String key = java.nio.file.Paths.get(path).getFileName().toString();
            captured.put(key, content);
            return true;
        }

        String contentFor(String filename) {
            return captured.getOrDefault(filename, "");
        }
    }

    private CapturingProcessor makeCapturingProcessor() {
        CapturingProcessor processor = new CapturingProcessor();
        Messager messager = noopMessager();
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        when(env.getOptions()).thenReturn(java.util.Map.of());
        processor.init(env);
        return processor;
    }

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

    private static Element contractElement(String qualifiedName, String reason) {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn(qualifiedName);
        when(element.getKind()).thenReturn(ElementKind.METHOD);
        String simpleName = qualifiedName.contains(".")
            ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
            : qualifiedName;
        when(element.getSimpleName()).thenReturn(nameOf(simpleName));
        AIContract contract = mock(AIContract.class);
        when(contract.reason()).thenReturn(reason);
        when(element.getAnnotation(AIContract.class)).thenReturn(contract);
        return element;
    }

    private static RoundEnvironment contractRoundEnv(String qualifiedName, String reason) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);

        Element element = contractElement(qualifiedName, reason);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        return roundEnv;
    }

    private static RoundEnvironment emptyRoundEnv() {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        return roundEnv;
    }

    private static void triggerGeneration(AIGuardrailProcessor processor) {
        RoundEnvironment genEnv = mock(RoundEnvironment.class);
        when(genEnv.processingOver()).thenReturn(true);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AIPrivacy.class);
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
