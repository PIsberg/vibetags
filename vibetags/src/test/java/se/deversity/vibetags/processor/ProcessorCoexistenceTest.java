package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ProcessorCoexistence;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A peer processor the build silently dropped is named, not left to surface as
 * "cannot find symbol".
 *
 * <p>Maven stops discovering processors on the class path once {@code <annotationProcessorPaths>}
 * is set, so a project whose Lombok ran by discovery loses it the moment VibeTags' documented
 * processor path is added: verified against Lombok 1.18.42 and MapStruct 1.6.3 with the published
 * 1.4.0, where the build failed on a Lombok getter with nothing pointing at the cause. Each peer is
 * stubbed here by its trigger annotation, which is all the check reads from the source side.
 */
class ProcessorCoexistenceTest {

    @TempDir
    Path tempDir;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void lombokUsedWithoutItsProcessorIsNamedWithTheFix() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("lombok.Data", "package lombok;\npublic @interface Data {}\n");
        h.addSource("com.example.Customer",
            "package com.example;\n@lombok.Data\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\n"
                + "public class Customer {}\n");

        List<String> warnings = warnings(h.compileReturningDiagnostics());

        assertEquals(1, warnings.stream().filter(w -> w.contains("Lombok")).count(), String.valueOf(warnings));
        String w = warnings.stream().filter(m -> m.contains("Lombok")).findFirst().orElseThrow();
        assertTrue(w.contains("org.projectlombok:lombok"), w);
        assertTrue(w.contains("<annotationProcessorPaths>"), w);
        assertTrue(w.contains("annotationProcessor"), w);
    }

    @Test
    void aPeerIsNamedOnceHoweverManyElementsUseIt() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("lombok.Data", "package lombok;\npublic @interface Data {}\n");
        h.addSource("lombok.Getter", "package lombok;\npublic @interface Getter {}\n");
        h.addSource("com.example.A", "package com.example;\n@lombok.Data\npublic class A {}\n");
        h.addSource("com.example.B", "package com.example;\n@lombok.Getter\npublic class B {}\n");
        h.addSource("com.example.C",
            "package com.example;\n@se.deversity.vibetags.annotations.AICore(sensitivity = \"high\", note = \"n\")\n"
                + "public class C {}\n");

        List<String> warnings = warnings(h.compileReturningDiagnostics());

        assertEquals(1, warnings.stream().filter(w -> w.contains("Lombok")).count(), String.valueOf(warnings));
    }

    @Test
    void aPeerOnTheClassPathButUnusedIsNotReported() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("lombok.Data", "package lombok;\npublic @interface Data {}\n");
        h.addSource("com.example.Customer",
            "package com.example;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class Customer {}\n");

        List<String> warnings = warnings(h.compileReturningDiagnostics());

        assertTrue(warnings.stream().noneMatch(w -> w.contains("Lombok")), String.valueOf(warnings));
    }

    @Test
    void aPeerWhoseProcessorThisLoaderCanSeeIsNotReported() throws IOException {
        // The positive case of the loader test: a fake Lombok processor class compiled into a
        // directory and handed to the check as the processor-path loader. This is what a correct
        // Maven <annotationProcessorPaths> list, Gradle annotationProcessor configuration, or
        // class-path discovery all look like from inside the processor.
        Path peerClasses = compileFakePeerProcessor("lombok.launch", "AnnotationProcessorHider",
            "public class AnnotationProcessorHider { public static class AnnotationProcessor {} }");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {peerClasses.toUri().toURL()},
                ProcessorCoexistenceTest.class.getClassLoader())) {
            ProcessorTestHarness h = new ProcessorTestHarness(tempDir.resolve("subject"), false);
            h.addSource("lombok.Data", "package lombok;\npublic @interface Data {}\n");
            h.addSource("com.example.Customer", "package com.example;\n@lombok.Data\npublic class Customer {}\n");

            List<String> withPeer = warnings(h.compileWithReturningDiagnostics(new CheckingProcessor(loader)));
            List<String> withoutPeer = warnings(h.compileWithReturningDiagnostics(
                new CheckingProcessor(ProcessorCoexistenceTest.class.getClassLoader())));

            assertTrue(withPeer.stream().noneMatch(w -> w.contains("Lombok")), String.valueOf(withPeer));
            assertTrue(withoutPeer.stream().anyMatch(w -> w.contains("Lombok")),
                "the same compile must report Lombok when the loader cannot see it: " + withoutPeer);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyPeerIsRecognisedByItsTriggerAnnotation() {
        return ProcessorCoexistence.PEERS.stream().flatMap(peer -> peer.annotations().stream()
            .map(annotation -> DynamicTest.dynamicTest(peer.name() + " via @" + annotation, () -> {
                Path dir = Files.createTempDirectory(tempDir, "peer");
                ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
                h.addSource(stubTopLevel(annotation), stubSource(annotation));
                h.addSource("com.example.Uses", "package com.example;\n@" + annotation + "\npublic class Uses {}\n");

                List<String> warnings = warnings(h.compileWithReturningDiagnostics(
                    new CheckingProcessor(ProcessorCoexistenceTest.class.getClassLoader())));

                assertTrue(warnings.stream().anyMatch(w -> w.contains(peer.name()) && w.contains(peer.coordinates())),
                    peer.name() + " not reported for @" + annotation + ": " + warnings);
            })));
    }

    @Test
    void widenedSupportedTypesDoNotTripJavacsProcessingLint() throws IOException {
        // From a project's second build on (a sidecar on record), or with transitive guardrails,
        // the processor widens what it supports to "*". It used to declare "*" beside its own
        // package, which javac's processing lint reports as redundant, so every -Xlint:all build of
        // every consumer printed a warning only VibeTags caused.
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".vibetags-transitive");
        h.addSource("com.example.Customer",
            "package com.example;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class Customer {}\n");

        List<String> warnings = warnings(h.compileReturningDiagnostics("-Xlint:processing"));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("redundantly")), String.valueOf(warnings));
    }

    @Test
    void aFailingLookupNeverEndsTheRound() {
        // The check is advisory. Anything it throws would reach process()'s catch-all, which ends
        // the round with a warning, so validation and generation would never run. A
        // ProcessingEnvironment with no Elements (a mocked one; a broken compiler) is the shape
        // that did that to 41 processor tests.
        javax.annotation.processing.ProcessingEnvironment env =
            org.mockito.Mockito.mock(javax.annotation.processing.ProcessingEnvironment.class);
        RoundEnvironment round = org.mockito.Mockito.mock(RoundEnvironment.class);
        org.mockito.Mockito.when(env.getElementUtils()).thenThrow(new IllegalStateException("no elements"));

        assertEquals(List.of(), ProcessorCoexistence.warnAboutDroppedPeers(
            env, round, ProcessorCoexistenceTest.class.getClassLoader(), null));
    }

    /** Runs the coexistence check alone, with a chosen class loader standing in for the processor path. */
    private static final class CheckingProcessor extends AbstractProcessor {
        private final ClassLoader loader;
        private boolean done;

        CheckingProcessor(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!done) {
                done = true;
                ProcessorCoexistence.warnAboutDroppedPeers(processingEnv, roundEnv, loader, null);
            }
            return false;
        }
    }

    private Path compileFakePeerProcessor(String pkg, String simpleName, String body) throws IOException {
        Path src = tempDir.resolve("peer-src").resolve(pkg.replace('.', '/')).resolve(simpleName + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package " + pkg + ";\n" + body + "\n", StandardCharsets.UTF_8);
        Path out = Files.createDirectories(tempDir.resolve("peer-classes"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString());
        assertEquals(0, rc, "the fake peer processor did not compile");
        return out;
    }

    /** The top-level type to declare for an annotation name, nested ones included ({@code Value.Immutable}). */
    private static String stubTopLevel(String annotation) {
        String[] parts = annotation.split("\\.");
        int firstType = 0;
        while (firstType < parts.length && !Character.isUpperCase(parts[firstType].charAt(0))) {
            firstType++;
        }
        return String.join(".", List.of(parts).subList(0, firstType + 1));
    }

    private static String stubSource(String annotation) {
        String top = stubTopLevel(annotation);
        String pkg = top.substring(0, top.lastIndexOf('.'));
        String topName = top.substring(top.lastIndexOf('.') + 1);
        if (top.equals(annotation)) {
            return "package " + pkg + ";\npublic @interface " + topName + " {}\n";
        }
        String nested = annotation.substring(top.length() + 1);
        return "package " + pkg + ";\npublic @interface " + topName + " { @interface " + nested + " {} }\n";
    }

    private static List<String> warnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
    }
}
