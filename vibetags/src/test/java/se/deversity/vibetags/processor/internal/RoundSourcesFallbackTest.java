package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link RoundSources} answers when the compiler's standard lookup cannot: a build tool's
 * wrapped environment whose {@code Elements.getFileObjectOf} returns nothing, throws, or whose
 * {@code Elements} is not available at all, with javac's Tree API reachable behind the wrapper or
 * not. {@code RoundSourcesTest} holds the plain javac case; this holds the fallbacks, the name read
 * off a URI that is not a file, and that a round is resolved once however often it is asked.
 */
class RoundSourcesFallbackTest {

    @TempDir
    Path dir;

    /** How the wrapped environment's standard lookup behaves. */
    private enum Standard { ANSWERS, RETURNS_NULL, THROWS, NO_ELEMENTS, FIXED_URI }

    /** What a {@link RoundSources} said in the first round, keyed by root element name. */
    private static final class Answers {
        final Map<String, Path> files = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
        List<Path> complete;
        boolean hasElements;
        int standardLookups;
    }

    /**
     * A build tool's environment around javac's, the way Gradle's incremental wrapper holds it: in
     * a field typed {@code ProcessingEnvironment} when the Tree API should be reachable by
     * unwrapping, and in an {@code Object} field when it should not.
     */
    private static final class Wrapper implements ProcessingEnvironment {
        @SuppressWarnings({"unused", "FieldCanBeLocal"}) // read reflectively by SourcePositionResolver
        private final ProcessingEnvironment reachable;
        private final Object hidden;
        private final Elements elements;

        Wrapper(ProcessingEnvironment javac, boolean treesReachable, Elements elements) {
            this.reachable = treesReachable ? javac : null;
            this.hidden = javac;
            this.elements = elements;
        }

        private ProcessingEnvironment javac() {
            return (ProcessingEnvironment) hidden;
        }

        @Override
        public Map<String, String> getOptions() {
            return javac().getOptions();
        }

        @Override
        public Messager getMessager() {
            return javac().getMessager();
        }

        @Override
        public Filer getFiler() {
            return javac().getFiler();
        }

        @Override
        public Elements getElementUtils() {
            if (elements == null) {
                throw new UnsupportedOperationException("no Elements in this environment");
            }
            return elements;
        }

        @Override
        public Types getTypeUtils() {
            return javac().getTypeUtils();
        }

        @Override
        public SourceVersion getSourceVersion() {
            return javac().getSourceVersion();
        }

        @Override
        public Locale getLocale() {
            return javac().getLocale();
        }
    }

    @SupportedAnnotationTypes("*")
    private static final class Probe extends AbstractProcessor {
        private final Standard standard;
        private final boolean treesReachable;
        private final URI fixedUri;
        final Answers answers = new Answers();
        private boolean done;

        Probe(Standard standard, boolean treesReachable, URI fixedUri) {
            this.standard = standard;
            this.treesReachable = treesReachable;
            this.fixedUri = fixedUri;
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (done || roundEnv.getRootElements().isEmpty()) {
                return false;
            }
            done = true;
            Elements elements = standard == Standard.NO_ELEMENTS ? null : countingElements(processingEnv.getElementUtils());
            RoundSources sources = RoundSources.of(new Wrapper(processingEnv, treesReachable, elements), roundEnv);
            for (int pass = 0; pass < 3; pass++) { // asked repeatedly, resolved once
                for (Element root : sources.roots()) {
                    answers.files.put(root.toString(), sources.fileOf(root));
                    answers.names.put(root.toString(), sources.fileNameOf(root));
                }
                answers.complete = sources.filesIfComplete();
                answers.hasElements = sources.elements() != null;
            }
            return false;
        }

        /** A source object that knows only its URI, which may be one SimpleJavaFileObject refuses. */
        private static JavaFileObject sourceAt(URI uri) {
            return (JavaFileObject) Proxy.newProxyInstance(JavaFileObject.class.getClassLoader(),
                new Class<?>[]{JavaFileObject.class}, (proxy, method, args) -> {
                    if (method.getName().equals("toUri")) {
                        return uri;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        }

        private Elements countingElements(Elements real) {
            return (Elements) Proxy.newProxyInstance(Elements.class.getClassLoader(), new Class<?>[]{Elements.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getFileObjectOf")) {
                        answers.standardLookups++;
                        switch (standard) {
                            case RETURNS_NULL:
                                return null;
                            case THROWS:
                                throw new UnsupportedOperationException("an older compiler");
                            case FIXED_URI:
                                return sourceAt(fixedUri);
                            default:
                                break;
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }
    }

    private Path plain;
    private Path pair;

    private Answers run(Standard standard, boolean treesReachable) throws IOException {
        return run(standard, treesReachable, null);
    }

    private Answers run(Standard standard, boolean treesReachable, URI fixedUri) throws IOException {
        plain = write("src/main/java/com/example/Plain.java", "package com.example; public class Plain {}");
        pair = write("src/main/java/com/example/Pair.java", "package com.example; public class Pair {} class PairHelper {}");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Probe probe = new Probe(standard, treesReachable, fixedUri);
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(Files.createDirectories(dir.resolve("classes")).toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            fm.getJavaFileObjectsFromPaths(List.of(plain, pair)).forEach(units::add);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, List.of("-proc:only"), null, units);
            task.setProcessors(List.of(probe));
            assertTrue(task.call(), "the fixture must compile");
        }
        return probe.answers;
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize();
    }

    private void assertEveryRootMapsToItsFile(Answers answers) {
        Map<String, Path> expected = new LinkedHashMap<>();
        expected.put("com.example.Plain", plain);
        expected.put("com.example.Pair", pair);
        expected.put("com.example.PairHelper", pair);
        assertEquals(expected, answers.files);
        assertEquals(Map.of("com.example.Plain", "Plain.java", "com.example.Pair", "Pair.java",
            "com.example.PairHelper", "Pair.java"), answers.names);
        assertEquals(List.of(plain, pair), answers.complete, "every distinct file, in root-element order");
    }

    @Test
    void theStandardLookupAnswers_andEachRootIsLookedUpOnce() throws IOException {
        Answers answers = run(Standard.ANSWERS, false);

        assertEveryRootMapsToItsFile(answers);
        assertTrue(answers.hasElements);
        assertEquals(3, answers.standardLookups, "three roots, asked three times over, resolved once");
    }

    @Test
    void whenTheStandardLookupFindsNothing_theTreeApiBehindTheWrapperAnswers() throws IOException {
        Answers answers = run(Standard.RETURNS_NULL, true);

        assertEveryRootMapsToItsFile(answers);
        assertEquals(3, answers.standardLookups);
    }

    @Test
    void whenTheStandardLookupThrows_theTreeApiBehindTheWrapperAnswers() throws IOException {
        Answers answers = run(Standard.THROWS, true);

        assertEveryRootMapsToItsFile(answers);
    }

    @Test
    void withoutElements_theTreeApiBehindTheWrapperAnswers() throws IOException {
        Answers answers = run(Standard.NO_ELEMENTS, true);

        assertEveryRootMapsToItsFile(answers);
        assertFalse(answers.hasElements, "an environment that will not give Elements has none to share");
    }

    @Test
    void whenNoApiCanAnswer_nothingIsMapped_andTheRoundIsNotVouchedFor() throws IOException {
        Answers answers = run(Standard.NO_ELEMENTS, false);

        assertEquals(3, answers.files.size(), "the roots are still the round's: " + answers.files);
        assertTrue(answers.files.values().stream().allMatch(java.util.Objects::isNull), answers.files.toString());
        assertTrue(answers.names.values().stream().allMatch(java.util.Objects::isNull), answers.names.toString());
        assertNull(answers.complete);
    }

    @Test
    void aStandardAnswerThatIsNotAFile_isNotFellBackFrom_andNamesTheFileByItsLastSegment() throws IOException {
        Answers answers = run(Standard.FIXED_URI, true, URI.create("string:///com/example/Mem.java"));

        assertTrue(answers.files.values().stream().allMatch(java.util.Objects::isNull),
            "the Tree API would have named a file on disk: " + answers.files);
        assertEquals(Set.of("Mem.java"), Set.copyOf(answers.names.values()));
        assertNull(answers.complete, "an in-memory source cannot be read back next time");
    }

    @Test
    void anOpaqueUri_isNamedByTheLastSegmentOfWhatFollowsItsScheme() throws IOException {
        assertEquals(Set.of("Ledger.kt"),
            Set.copyOf(run(Standard.FIXED_URI, false, URI.create("ksp:Ledger.kt")).names.values()));
    }

    @Test
    void aSourceInsideAnArchive_isNamedButNotMapped() throws IOException {
        Answers answers = run(Standard.FIXED_URI, false, URI.create("jar:file:/libs/a.jar!/com/example/B.java"));

        assertEquals(Set.of("B.java"), Set.copyOf(answers.names.values()));
        assertTrue(answers.files.values().stream().allMatch(java.util.Objects::isNull), answers.files.toString());
    }
}
