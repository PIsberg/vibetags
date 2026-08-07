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
import se.deversity.vibetags.processor.internal.validation.ValidationRule;
import se.deversity.vibetags.processor.internal.validation.ValidationRules;

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

    // ------------------------------------------------------------------ null-returning attributes

    /**
     * javac never hands back a null String from an annotation member, but a mocked or non-javac
     * environment does, and several rules read one before testing it for blankness. VibeTags is
     * advisory: under a degraded environment it has to stay quiet, not take the build down with it.
     */
    @Test
    void attributeRules_treatANullStringAttributeAsBlankRatherThanThrowing() {
        record Case(String label, ValidationRule rule, Element element) { }

        List<Case> cases = List.of(
            new Case("@AIRegulation.standard",
                findRuleFor(se.deversity.vibetags.annotations.AIRegulation.class),
                elementWithNullAttribute(se.deversity.vibetags.annotations.AIRegulation.class,
                    a -> when(a.standard()).thenReturn(null))),
            new Case("@AIFeatureFlag.flag",
                findRuleFor(se.deversity.vibetags.annotations.AIFeatureFlag.class),
                elementWithNullAttribute(se.deversity.vibetags.annotations.AIFeatureFlag.class,
                    a -> when(a.flag()).thenReturn(null))),
            new Case("@AISecure.aspect",
                findRuleFor(se.deversity.vibetags.annotations.AISecure.class),
                elementWithNullAttribute(se.deversity.vibetags.annotations.AISecure.class,
                    a -> when(a.aspect()).thenReturn(null))),
            new Case("@AISunset.jira",
                findRuleFor(se.deversity.vibetags.annotations.AISunset.class),
                elementWithNullAttribute(se.deversity.vibetags.annotations.AISunset.class,
                    a -> when(a.jira()).thenReturn(null))));

        for (Case c : cases) {
            Recorder recorder = new Recorder(c.element(), c.rule().scans());
            c.rule().check(recorder.ctx, c.element());
            assertTrue(recorder.messages.stream().anyMatch(m -> m.startsWith("WARNING|")),
                c.label() + ": a null attribute is as unusable as a blank one and must warn, not throw. "
                    + recorder.messages);
        }
    }

    /** The registry's rule for {@code type}, so the test drives the real one rather than a copy. */
    private static ValidationRule findRuleFor(Class<? extends java.lang.annotation.Annotation> type) {
        return ValidationRules.all().stream()
            .filter(r -> r.scans().equals(type) && r instanceof AttributeRule)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no AttributeRule registered for @" + type.getSimpleName()));
    }

    private static <A extends java.lang.annotation.Annotation> Element elementWithNullAttribute(
            Class<A> type, java.util.function.Consumer<A> stub) {
        A annotation = mock(type);
        stub.accept(annotation);
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Degraded");
        when(element.getAnnotation(type)).thenReturn(annotation);
        return element;
    }

    // ------------------------------------------------------------------ ModernJavaRules guards

    @Test
    void pureRule_saysNothing_whenTheElementIsNotAMethod() {
        // @AIPure targets METHOD, so javac cannot produce this. A non-javac environment can, and
        // the cast that would follow is the kind that turns a warning into a ClassCastException.
        se.deversity.vibetags.annotations.AIPure pure =
            mock(se.deversity.vibetags.annotations.AIPure.class);
        Element notAMethod = mock(Element.class);
        when(notAMethod.getAnnotation(se.deversity.vibetags.annotations.AIPure.class)).thenReturn(pure);
        Recorder recorder = new Recorder(notAMethod, se.deversity.vibetags.annotations.AIPure.class);

        findRuleFor(se.deversity.vibetags.annotations.AIPure.class).check(recorder.ctx, notAMethod);

        assertTrue(recorder.messages.isEmpty(),
            "Nothing to say about an element that is not a method: " + recorder.messages);
    }

    @Test
    void publicApiRule_stopsWalking_whenTheEnclosingChainRunsOut() {
        // getEnclosingElement() returning null ends the chain without ever reaching a PACKAGE.
        // Walking past it is an infinite loop or an NPE depending on how the loop is written.
        se.deversity.vibetags.annotations.AIPublicAPI api =
            mock(se.deversity.vibetags.annotations.AIPublicAPI.class);
        Element orphan = mock(Element.class);
        when(orphan.toString()).thenReturn("com.example.Orphan");
        when(orphan.getAnnotation(se.deversity.vibetags.annotations.AIPublicAPI.class)).thenReturn(api);
        // Public, so the walk does not stop on the element itself and has to reach the null.
        when(orphan.getModifiers()).thenReturn(Set.of(javax.lang.model.element.Modifier.PUBLIC));
        when(orphan.getEnclosingElement()).thenReturn(null);
        Recorder recorder = new Recorder(orphan, se.deversity.vibetags.annotations.AIPublicAPI.class);

        findRuleFor(se.deversity.vibetags.annotations.AIPublicAPI.class).check(recorder.ctx, orphan);

        assertTrue(recorder.messages.isEmpty(),
            "A chain that ends without a non-public link is public all the way up, and the walk must "
                + "terminate rather than loop or throw: " + recorder.messages);
    }

    @Test
    void architectureRule_treatsNullAttributesAsAbsent() {
        // Same degraded-environment case as the attribute rules: a null belongsTo is as unusable as
        // a blank one, and a null entry inside cannotReference must be skipped rather than matched.
        se.deversity.vibetags.annotations.AIArchitecture arch =
            mock(se.deversity.vibetags.annotations.AIArchitecture.class);
        when(arch.belongsTo()).thenReturn(null);
        when(arch.cannotReference()).thenReturn(new String[]{null, ""});
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Degraded");
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(arch);
        Recorder recorder = new Recorder(element, se.deversity.vibetags.annotations.AIArchitecture.class);

        new ArchitectureRule().check(recorder.ctx, element);

        assertTrue(recorder.messages.stream().anyMatch(m -> m.startsWith("WARNING|") && m.contains("belongsTo")),
            "a null layer name must warn like a blank one: " + recorder.messages);
        assertTrue(recorder.messages.stream().noneMatch(m -> m.startsWith("ERROR|")),
            "a null forbidden entry bans nothing and must not fail the build: " + recorder.messages);
    }

    @Test
    void architectureRule_skipsTheScan_whenCannotReferenceIsNull() {
        se.deversity.vibetags.annotations.AIArchitecture arch =
            mock(se.deversity.vibetags.annotations.AIArchitecture.class);
        when(arch.belongsTo()).thenReturn("service");
        when(arch.cannotReference()).thenReturn(null);
        Element element = mock(Element.class);
        when(element.getAnnotation(se.deversity.vibetags.annotations.AIArchitecture.class)).thenReturn(arch);
        Recorder recorder = new Recorder(element, se.deversity.vibetags.annotations.AIArchitecture.class);

        new ArchitectureRule().check(recorder.ctx, element);

        assertTrue(recorder.messages.isEmpty(),
            "Nothing forbidden means nothing to scan and nothing to say: " + recorder.messages);
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
    void architectureRule_skipsTheScan_whenTheOnlyForbiddenEntryIsBlank(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        // A blank entry in cannotReference matches every import if it is not skipped, so this is
        // the difference between "you configured nothing" and "you forbade everything".
        List<String> messages = compileAndCollect(tmp,
            "package com.example.blank;\n"
                + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                + "import java.util.List;\n"
                + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"\", \"  \"})\n"
                + "public class BlankForbidden { List<String> l; }\n",
            "com/example/blank/BlankForbidden.java");

        assertTrue(messages.stream().noneMatch(m -> m.startsWith("ERROR|")),
            "A blank forbidden entry bans nothing and must not fail the build: " + messages);
    }

    @Test
    void architectureRule_matchesAnOnDemandImport_whenTheForbiddenEntryEndsWithADot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        // cannotReference = "com.example.forbidden." is how a package-with-trailing-dot reads to
        // somebody writing the annotation, and `import com.example.forbidden.*` is the import it is
        // most obviously meant to catch. The prefix-star arm is what makes that pair work.
        List<String> messages = compileAndCollect(tmp,
            "package com.example.star;\n"
                + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                + "import java.util.*;\n"
                + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"java.util.\"})\n"
                + "public class StarImport { List<String> l; }\n",
            "com/example/star/StarImport.java");

        assertTrue(messages.stream().anyMatch(m -> m.startsWith("ERROR|") && m.contains("illegal import")),
            "An on-demand import of a forbidden package must be reported: " + messages);
    }

    /**
     * Compiles one source with the real processor and returns {@code KIND|message} lines.
     *
     * <p>{@code -Avibetags.root} pins the processor's write root to the JUnit temp dir. Without it
     * the processor falls back to the real working directory, so any opt-in file
     * (CONVENTIONS.md, gemini_instructions.md, .github/copilot-instructions.md, ...) that happens to
     * exist there gets silently overwritten with this test's fixture content on every {@code mvn
     * test} — see issue found 2026-08-07.
     */
    private static List<String> compileAndCollect(java.nio.file.Path tmp, String code, String name) {
        List<String> messages = new ArrayList<>();
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
            new javax.tools.DiagnosticCollector<>();
        javax.tools.JavaFileObject source = new javax.tools.SimpleJavaFileObject(
                java.net.URI.create("string:///" + name), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        try (javax.tools.StandardJavaFileManager fm =
                 compiler.getStandardFileManager(diagnostics, null, null)) {
            javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path"), "-proc:only",
                    "-Avibetags.root=" + tmp.toAbsolutePath()),
                null, List.of(source));
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            VibeTagsLogger.shutdown();
        }
        for (var d : diagnostics.getDiagnostics()) {
            messages.add(d.getKind() + "|" + d.getMessage(java.util.Locale.ROOT));
        }
        return messages;
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
