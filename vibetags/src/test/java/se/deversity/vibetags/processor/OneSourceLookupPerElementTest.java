package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round maps each root element to its source file once, not once per consumer (#857).
 *
 * <p>Three things want that mapping in the first round: module identity
 * ({@code ModuleRootResolver}), the early exit's key ({@code SourceDigest}) and the partial-round
 * ledger ({@code PartialRoundDetector}). Each resolved every root element itself, allocating a URI
 * and a {@code Path} per element per consumer, which on a cold build of 1000 classes was part of
 * what the digest added.
 *
 * <p>Counted through the one standard API every consumer can use, {@code
 * Elements.getFileObjectOf}, under a {@code ProcessingEnvironment} wrapper the Tree API cannot see
 * through: the shape of a build tool's decorator whose delegate is not reachable by reflection, and
 * the one where no consumer has a cheaper lookup to hide a repeat behind.
 */
class OneSourceLookupPerElementTest {

    private static final int CLASSES = 4;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private ProcessorTestHarness moduleWithSources() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), "<project><artifactId>core</artifactId></project>",
            StandardCharsets.UTF_8);
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        for (int i = 0; i < CLASSES; i++) {
            harness.writeSourceFile("core/src/main/java/com/example/core/Node" + i + ".java", """
                package com.example.core;

                import se.deversity.vibetags.annotations.AILocked;

                @AILocked(reason = "node %d")
                public class Node%d {
                }
                """.formatted(i, i));
        }
        return harness;
    }

    private static void assertOneLookupEach(Map<String, Integer> lookups, String build) {
        assertEquals(CLASSES, lookups.size(),
            build + ": every root element must have been looked up, or this measured nothing: " + lookups);
        lookups.forEach((element, count) -> assertEquals(1, count,
            build + ": " + element + " was mapped to its source file " + count + " times: " + lookups));
    }

    @Test
    void aColdBuildMapsEachRootElementOnce() throws IOException {
        ProcessorTestHarness harness = moduleWithSources();
        CountingProcessor processor = new CountingProcessor();

        harness.compileWith(processor);

        assertOneLookupEach(processor.lookups, "cold build");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-core")),
            "module identity must still resolve from the shared mapping");
        assertTrue(harness.readFile("CLAUDE.md").contains("node 3"), "and the build must still write");
    }

    @Test
    void aRebuildThatTakesTheEarlyExitMapsEachRootElementOnce() throws IOException {
        ProcessorTestHarness harness = moduleWithSources();
        harness.compileWith(new CountingProcessor());
        CountingProcessor warm = new CountingProcessor();

        harness.compileWith(warm);

        assertOneLookupEach(warm.lookups, "no-op rebuild");
    }

    /** The processor behind a decorator that hides its delegate from reflection. */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                       "vibetags.cache", "vibetags.check", "vibetags.module"})
    private static final class CountingProcessor extends AIGuardrailProcessor {
        final Map<String, Integer> lookups = new TreeMap<>();

        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(new OpaqueEnvironment(processingEnv, counting(processingEnv.getElementUtils())));
        }

        private Elements counting(Elements delegate) {
            return (Elements) Proxy.newProxyInstance(Elements.class.getClassLoader(),
                new Class<?>[]{Elements.class}, (proxy, method, args) -> {
                    if ("getFileObjectOf".equals(method.getName()) && args[0] instanceof Element e) {
                        String name = e instanceof QualifiedNameable q
                            ? q.getQualifiedName().toString() : e.toString();
                        lookups.merge(name, 1, Integer::sum);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
        }
    }

    /**
     * A pass-through whose delegate is typed {@code Object}, so {@code SourcePositionResolver}'s
     * reflective unwrap finds no {@code ProcessingEnvironment} field and the Tree API stays closed.
     */
    private static final class OpaqueEnvironment implements ProcessingEnvironment {
        private final Object hidden;
        private final Elements elements;

        OpaqueEnvironment(ProcessingEnvironment delegate, Elements elements) {
            this.hidden = delegate;
            this.elements = elements;
        }

        private ProcessingEnvironment env() {
            return (ProcessingEnvironment) hidden;
        }

        @Override public Map<String, String> getOptions() { return env().getOptions(); }
        @Override public Messager getMessager() { return env().getMessager(); }
        @Override public Filer getFiler() { return env().getFiler(); }
        @Override public Elements getElementUtils() { return elements; }
        @Override public Types getTypeUtils() { return env().getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return env().getSourceVersion(); }
        @Override public Locale getLocale() { return env().getLocale(); }
    }
}
