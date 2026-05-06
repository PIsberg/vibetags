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
import se.deversity.vibetags.annotations.AITestDriven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for @AITestDriven annotation definition, validation, and per-platform output.
 */
class AITestDrivenProcessorTest {

    // -----------------------------------------------------------------------
    // Annotation definition
    // -----------------------------------------------------------------------

    @Test
    void annotation_isOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("se.deversity.vibetags.annotations.AITestDriven"),
            "@AITestDriven must be on the processor classpath");
    }

    @Test
    void annotation_hasSourceRetention() {
        java.lang.annotation.Retention retention =
            AITestDriven.class.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention, "@AITestDriven must declare @Retention");
        assertEquals(java.lang.annotation.RetentionPolicy.SOURCE, retention.value(),
            "@AITestDriven must use SOURCE retention for zero runtime overhead");
    }

    @Test
    void annotation_targetsTypeAndMethod() {
        java.lang.annotation.Target target =
            AITestDriven.class.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target, "@AITestDriven must declare @Target");
        assertArrayEquals(
            new java.lang.annotation.ElementType[]{
                java.lang.annotation.ElementType.TYPE,
                java.lang.annotation.ElementType.METHOD
            },
            target.value(),
            "@AITestDriven must target TYPE and METHOD only"
        );
    }

    @Test
    void annotation_testLocationDefaultIsEmpty() throws Exception {
        java.lang.reflect.Method m = AITestDriven.class.getDeclaredMethod("testLocation");
        assertEquals("", m.getDefaultValue(), "testLocation() must default to empty string");
    }

    @Test
    void annotation_coverageGoalDefaultIs100() throws Exception {
        java.lang.reflect.Method m = AITestDriven.class.getDeclaredMethod("coverageGoal");
        assertEquals(100, m.getDefaultValue(), "coverageGoal() must default to 100");
    }

    @Test
    void annotation_frameworkDefaultIsJunit5() throws Exception {
        java.lang.reflect.Method m = AITestDriven.class.getDeclaredMethod("framework");
        AITestDriven.Framework[] defaults = (AITestDriven.Framework[]) m.getDefaultValue();
        assertNotNull(defaults);
        assertEquals(1, defaults.length);
        assertEquals(AITestDriven.Framework.JUNIT_5, defaults[0],
            "Default framework must be JUNIT_5");
    }

    @Test
    void annotation_mockPolicyDefaultIsEmpty() throws Exception {
        java.lang.reflect.Method m = AITestDriven.class.getDeclaredMethod("mockPolicy");
        assertEquals("", m.getDefaultValue(), "mockPolicy() must default to empty string");
    }

    @Test
    void framework_enum_hasExpectedConstants() {
        Set<String> names = new java.util.HashSet<>();
        for (AITestDriven.Framework f : AITestDriven.Framework.values()) {
            names.add(f.name());
        }
        assertTrue(names.contains("JUNIT_5"),  "Framework enum must contain JUNIT_5");
        assertTrue(names.contains("JUNIT_4"),  "Framework enum must contain JUNIT_4");
        assertTrue(names.contains("TESTNG"),   "Framework enum must contain TESTNG");
        assertTrue(names.contains("MOCKITO"),  "Framework enum must contain MOCKITO");
        assertTrue(names.contains("ASSERTJ"),  "Framework enum must contain ASSERTJ");
        assertTrue(names.contains("SPOCK"),    "Framework enum must contain SPOCK");
        assertTrue(names.contains("NONE"),     "Framework enum must contain NONE");
    }

    // -----------------------------------------------------------------------
    // Validation: @AITestDriven + @AIIgnore
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_testDrivenAndIgnore_emitsContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdIgnore = mockTestDriven(100, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.OrderService.processPayment");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdIgnore);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AITestDriven") && w.contains("@AIIgnore")),
            "Should warn about @AITestDriven + @AIIgnore combination");
        assertTrue(warnings.stream().anyMatch(w -> w.toLowerCase().contains("ignored") || w.toLowerCase().contains("excludes")),
            "Warning should explain why the combination is problematic");
    }

    @Test
    void validateAnnotations_testDrivenWithoutIgnore_noContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdAlone = mockTestDriven(100, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.OrderService.processPayment");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdAlone);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertFalse(warnings.stream().anyMatch(w -> w.contains("@AITestDriven") && w.contains("@AIIgnore")),
            "No warning when @AITestDriven is used alone");
    }

    // -----------------------------------------------------------------------
    // Validation: @AITestDriven + @AILocked
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_testDrivenAndLocked_emitsContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdLocked = mockTestDriven(100, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.LegacyService.compute");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdLocked);
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(null);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("@AITestDriven") && w.contains("@AILocked")),
            "Should warn about @AITestDriven + @AILocked combination");
    }

    // -----------------------------------------------------------------------
    // Validation: coverageGoal out of range
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_coverageGoalAbove100_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdAbove = mockTestDriven(150, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Service.method");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdAbove);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);
        when(element.getAnnotation(AILocked.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("coverageGoal") && w.contains("150")),
            "Should warn about coverageGoal > 100");
    }

    @Test
    void validateAnnotations_coverageGoalNegative_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdNeg = mockTestDriven(-1, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Service.method");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdNeg);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);
        when(element.getAnnotation(AILocked.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("coverageGoal")),
            "Should warn about negative coverageGoal");
    }

    @Test
    void validateAnnotations_coverageGoal100_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        AITestDriven tdValid = mockTestDriven(100, "");
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Service.method");
        when(element.getAnnotation(AITestDriven.class)).thenReturn(tdValid);
        when(element.getAnnotation(AIIgnore.class)).thenReturn(null);
        when(element.getAnnotation(AILocked.class)).thenReturn(null);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        processor.validateAnnotations(messager, roundEnv);

        assertFalse(warnings.stream().anyMatch(w -> w.contains("coverageGoal")),
            "No coverageGoal warning for valid value 100");
    }

    // -----------------------------------------------------------------------
    // process() — test-driven sections written to each platform file
    // -----------------------------------------------------------------------

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToCursorRules() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.OrderService.calculateDiscount", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("TEST-DRIVEN"),
                ".cursorrules must have test-driven section");
            assertTrue(content.contains("com.example.OrderService.calculateDiscount"),
                ".cursorrules must list the annotated element");
            assertTrue(content.contains("100"),
                ".cursorrules must include the coverage goal");
        });
    }

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToClaudeMd() throws Exception {
        withCwdSignalFiles(List.of("CLAUDE.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.OrderService.updateOrderStatus", 95, "JUNIT_5", "src/test/java/com/example/service/OrderServiceTest.java"));
            triggerGeneration(processor);

            String content = processor.contentFor("CLAUDE.md");
            assertTrue(content.contains("<test_driven_requirements>"),
                "CLAUDE.md must contain <test_driven_requirements> XML element");
            assertTrue(content.contains("</test_driven_requirements>"),
                "CLAUDE.md must close <test_driven_requirements>");
            assertTrue(content.contains("com.example.OrderService.updateOrderStatus"),
                "CLAUDE.md must list the annotated element");
            assertTrue(content.contains("<coverage_goal>95</coverage_goal>"),
                "CLAUDE.md must include coverage_goal element");
            assertTrue(content.contains("OrderServiceTest.java"),
                "CLAUDE.md must include the test_location when provided");
            assertTrue(content.contains("tests are incomplete"),
                "CLAUDE.md rule must instruct that changes without tests are incomplete");
        });
    }

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToAgentsMd() throws Exception {
        withCwdSignalFiles(List.of("AGENTS.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.BillingService.invoice", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("AGENTS.md");
            assertTrue(content.contains("TEST-DRIVEN"),
                "AGENTS.md must have test-driven section");
            assertTrue(content.contains("com.example.BillingService.invoice"),
                "AGENTS.md must list the annotated element");
        });
    }

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToGemini() throws Exception {
        withCwdSignalFiles(List.of("gemini_instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.PricingService.calculatePrice", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("gemini_instructions.md");
            assertTrue(content.contains("TEST-DRIVEN"),
                "gemini_instructions.md must have test-driven section");
            assertTrue(content.contains("com.example.PricingService.calculatePrice"),
                "gemini_instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToCopilot() throws Exception {
        withCwdSignalFiles(List.of(".github/copilot-instructions.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.ReportService.generate", 80, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("copilot-instructions.md");
            assertTrue(content.contains("Test-Driven"),
                "copilot-instructions.md must have test-driven section");
            assertTrue(content.contains("com.example.ReportService.generate"),
                "copilot-instructions.md must list the annotated element");
        });
    }

    @Test
    void process_withTestDrivenAnnotation_writesTestDrivenSectionToQwen() throws Exception {
        withCwdSignalFiles(List.of("QWEN.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.CatalogService.search", 90, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("QWEN.md");
            assertTrue(content.contains("TEST-DRIVEN"),
                "QWEN.md must have test-driven section");
            assertTrue(content.contains("com.example.CatalogService.search"),
                "QWEN.md must list the annotated element");
        });
    }

    @Test
    void process_noTestDrivenAnnotations_noTestDrivenSectionWritten() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), emptyRoundEnv());
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertFalse(content.contains("TEST-DRIVEN"),
                "No test-driven section when no @AITestDriven annotations are present");
        });
    }

    @Test
    void process_testDrivenWithLocation_locationAppearsInOutput() throws Exception {
        withCwdSignalFiles(List.of("CLAUDE.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.Service.method", 100, "JUNIT_5", "src/test/java/com/example/ServiceTest.java"));
            triggerGeneration(processor);

            String content = processor.contentFor("CLAUDE.md");
            assertTrue(content.contains("ServiceTest.java"),
                "Test location must appear in output when provided");
        });
    }

    @Test
    void process_testDrivenWithMockPolicy_mockPolicyAppearsInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();

            Element element = testDrivenElement("com.example.Service.method", 100, "Use H2 for database tests");
            RoundEnvironment roundEnv = testDrivenRoundEnvWith(element);

            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("H2 for database tests"),
                "Mock policy must appear in output when provided");
        });
    }

    @Test
    void process_testDrivenWithMockPolicy_mockPolicyAppearsInClaudeMd() throws Exception {
        withCwdSignalFiles(List.of("CLAUDE.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();

            Element element = testDrivenElement("com.example.PaymentService.charge", 95, "Mock Stripe client; use real validation logic");
            RoundEnvironment roundEnv = testDrivenRoundEnvWith(element);

            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor("CLAUDE.md");
            assertTrue(content.contains("mock_policy"),
                "Claude XML must include <mock_policy> tag when mockPolicy is non-empty");
            assertTrue(content.contains("Mock Stripe client"),
                "Claude XML must include the mock policy value");
        });
    }

    @Test
    void process_testDrivenWithLocation_locationAppearsInLlmsFullTxt() throws Exception {
        withCwdSignalFiles(List.of("llms-full.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv(
                "com.example.OrderService.placeOrder", 100, "JUNIT_5",
                "src/test/java/com/example/OrderServiceTest.java"));
            triggerGeneration(processor);

            String content = processor.contentFor("llms-full.txt");
            assertTrue(content.contains("Test Location"),
                "llms-full.txt must include Test Location when testLocation is provided");
            assertTrue(content.contains("OrderServiceTest.java"),
                "llms-full.txt must include the testLocation path");
        });
    }

    @Test
    void process_testDrivenWithMockPolicy_mockPolicyAppearsInLlmsFullTxt() throws Exception {
        withCwdSignalFiles(List.of("llms-full.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();

            Element element = testDrivenElement("com.example.BillingService.invoice", 90, "Always mock the mailer");
            RoundEnvironment roundEnv = testDrivenRoundEnvWith(element);

            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor("llms-full.txt");
            assertTrue(content.contains("Mock Policy"),
                "llms-full.txt must include Mock Policy section when mockPolicy is provided");
            assertTrue(content.contains("Always mock the mailer"),
                "llms-full.txt must include the mockPolicy value");
        });
    }

    @Test
    void process_testDrivenAnnotation_llmsTxtContainsTestDrivenSection() throws Exception {
        withCwdSignalFiles(List.of("llms.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.OrderService.calculateDiscount", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("llms.txt");
            assertTrue(content.contains("Test-Driven Requirements"),
                "llms.txt must have Test-Driven Requirements section");
        });
    }

    @Test
    void process_testDrivenAnnotation_llmsFullTxtContainsTestDrivenSection() throws Exception {
        withCwdSignalFiles(List.of("llms-full.txt"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.OrderService.calculateDiscount", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("llms-full.txt");
            assertTrue(content.contains("Test-Driven Requirements"),
                "llms-full.txt must have Test-Driven Requirements section");
            assertTrue(content.contains("Coverage Goal"),
                "llms-full.txt must include Coverage Goal entry");
        });
    }

    @Test
    void process_multipleTestDrivenElements_allListedInOutput() throws Exception {
        withCwdSignalFiles(List.of(".cursorrules"), () -> {
            Element el1 = testDrivenElement("com.example.OrderService.calculateDiscount", 100, "");
            Element el2 = testDrivenElement("com.example.BillingService.invoice", 90, "");

            RoundEnvironment roundEnv = mock(RoundEnvironment.class);
            when(roundEnv.processingOver()).thenReturn(false);
            doReturn(Set.of(el1, el2)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AICore.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPerformance.class);
            doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), roundEnv);
            triggerGeneration(processor);

            String content = processor.contentFor(".cursorrules");
            assertTrue(content.contains("com.example.OrderService.calculateDiscount"), "Must list first element");
            assertTrue(content.contains("com.example.BillingService.invoice"), "Must list second element");
        });
    }

    @Test
    void process_testDrivenAnnotation_aiderConventionsContainsTestDrivenSection() throws Exception {
        withCwdSignalFiles(List.of("CONVENTIONS.md"), () -> {
            CapturingProcessor processor = makeCapturingProcessor();
            processor.process(Set.of(), testDrivenRoundEnv("com.example.BillingApi.invoice", 100, "JUNIT_5", ""));
            triggerGeneration(processor);

            String content = processor.contentFor("CONVENTIONS.md");
            assertTrue(content.contains("TEST-DRIVEN"),
                "CONVENTIONS.md must mention TEST-DRIVEN");
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

    private static AITestDriven mockTestDriven(int coverageGoal, String testLocation) {
        AITestDriven td = mock(AITestDriven.class);
        when(td.coverageGoal()).thenReturn(coverageGoal);
        when(td.testLocation()).thenReturn(testLocation);
        when(td.mockPolicy()).thenReturn("");
        doReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5}).when(td).framework();
        return td;
    }

    private static Element testDrivenElement(String qualifiedName, int coverageGoal, String mockPolicy) {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn(qualifiedName);
        when(element.getKind()).thenReturn(ElementKind.METHOD);
        String simpleName = qualifiedName.contains(".")
            ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
            : qualifiedName;
        when(element.getSimpleName()).thenReturn(nameOf(simpleName));

        AITestDriven td = mock(AITestDriven.class);
        when(td.coverageGoal()).thenReturn(coverageGoal);
        when(td.testLocation()).thenReturn("");
        when(td.mockPolicy()).thenReturn(mockPolicy);
        doReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5}).when(td).framework();
        when(element.getAnnotation(AITestDriven.class)).thenReturn(td);
        return element;
    }

    private static Element testDrivenElementWithLocation(String qualifiedName, int coverageGoal, String testLocation) {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn(qualifiedName);
        when(element.getKind()).thenReturn(ElementKind.METHOD);
        String simpleName = qualifiedName.contains(".")
            ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
            : qualifiedName;
        when(element.getSimpleName()).thenReturn(nameOf(simpleName));

        AITestDriven td = mock(AITestDriven.class);
        when(td.coverageGoal()).thenReturn(coverageGoal);
        when(td.testLocation()).thenReturn(testLocation);
        when(td.mockPolicy()).thenReturn("");
        doReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5}).when(td).framework();
        when(element.getAnnotation(AITestDriven.class)).thenReturn(td);
        return element;
    }

    private static RoundEnvironment testDrivenRoundEnv(String qualifiedName, int coverageGoal, String frameworkStr, String testLocation) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);

        Element element = testDrivenElementWithLocation(qualifiedName, coverageGoal, testLocation);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        return roundEnv;
    }

    private static RoundEnvironment testDrivenRoundEnvWith(Element element) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);
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
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);
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
        doReturn(Set.of()).when(genEnv).getElementsAnnotatedWith(AITestDriven.class);
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
