package se.deversity.vibetags.processor.internal;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoundSources} names, for every kind of root element a round can hold, the file the Tree
 * API names for it (#857).
 *
 * <p>Module identity used to resolve through the Tree API first and the early exit through {@code
 * Elements.getFileObjectOf} first, and the one mapping that replaces both asks the cheaper standard
 * API first. That is only safe if the two never disagree about a source on disk, so this compiles
 * one of each root-element shape javac hands a processor, from real files, and holds the shared
 * answer to the Tree API's per element. An in-memory source maps to nothing, and a round holding
 * one cannot be vouched for as a whole.
 */
class RoundSourcesTest {

    @TempDir
    Path dir;

    /** Captures, per root element, what RoundSources says and what the Tree API says. */
    @SupportedAnnotationTypes("*")
    private static final class Capture extends AbstractProcessor {
        final Map<String, Path> shared = new LinkedHashMap<>();
        final Map<String, Path> tree = new LinkedHashMap<>();
        final List<List<Path>> completeFiles = new ArrayList<>();
        boolean sawRoundWithoutRoots;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            RoundSources sources = RoundSources.of(processingEnv, roundEnv);
            if (roundEnv.getRootElements().isEmpty()) {
                sawRoundWithoutRoots = true;
                assertNull(sources.filesIfComplete(), "a round with no root elements vouches for nothing");
                return false;
            }
            Trees trees = Trees.instance(processingEnv);
            for (Element element : roundEnv.getRootElements()) {
                String name = element.getKind() + " " + element;
                shared.put(name, sources.fileOf(element));
                TreePath path = trees.getPath(element);
                URI uri = path.getCompilationUnit().getSourceFile().toUri();
                tree.put(name, "file".equals(uri.getScheme()) ? Paths.get(uri).toAbsolutePath().normalize() : null);
            }
            completeFiles.add(sources.filesIfComplete());
            return false;
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Capture compile(List<Path> files, List<JavaFileObject> inMemory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Capture capture = new Capture();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Path out = Files.createDirectories(dir.resolve("classes"));
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            List<JavaFileObject> units = new ArrayList<>(inMemory);
            fm.getJavaFileObjectsFromPaths(files).forEach(units::add);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, List.of("-proc:only"), null, units);
            task.setProcessors(List.of(capture));
            assertTrue(task.call(), "the fixture must compile");
        }
        return capture;
    }

    @Test
    void everyRootElementShapeMapsToTheFileTheTreeApiNames() throws IOException {
        List<Path> files = List.of(
            write("src/main/java/com/example/Plain.java", "package com.example; public class Plain {}"),
            write("src/main/java/com/example/Pair.java",
                "package com.example; public class Pair {} class PairHelper {}"),
            write("src/main/java/com/example/Kind.java", "package com.example; public enum Kind { A }"),
            write("src/main/java/com/example/Point.java", "package com.example; public record Point(int x) {}"),
            write("src/main/java/com/example/Marker.java", "package com.example; public @interface Marker {}"),
            write("src/main/java/com/example/package-info.java", "package com.example;"),
            write("src/main/java/Unnamed.java", "public class Unnamed {}"),
            write("other root/src/x/y/Spaced.java", "package x.y; public interface Spaced {}"));

        Capture capture = compile(files, List.of());

        assertEquals(9, capture.tree.size(), "one entry per root element: " + capture.tree.keySet());
        assertTrue(capture.tree.keySet().stream().anyMatch(k -> k.startsWith("PACKAGE")),
            "the package-info root must be among them: " + capture.tree.keySet());
        assertEquals(capture.tree, capture.shared);
        assertEquals(List.of(List.copyOf(new java.util.LinkedHashSet<>(capture.tree.values()))),
            capture.completeFiles, "the complete list is every distinct file, in root-element order");
        assertTrue(capture.sawRoundWithoutRoots, "the last round has no roots, and must have been asked");
    }

    @Test
    void anInMemorySourceMapsToNothingAndTheRoundCannotBeVouchedFor() throws IOException {
        Path onDisk = write("src/main/java/com/example/Plain.java", "package com.example; public class Plain {}");
        JavaFileObject inMemory = new SimpleJavaFileObject(URI.create("string:///com/example/Mem.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "package com.example; public class Mem {}";
            }
        };

        Capture capture = compile(List.of(onDisk), List.of(inMemory));

        assertNull(capture.shared.get("CLASS com.example.Mem"), capture.shared.toString());
        assertEquals(onDisk.toAbsolutePath().normalize(), capture.shared.get("CLASS com.example.Plain"));
        assertEquals(1, capture.completeFiles.size());
        assertNull(capture.completeFiles.get(0), "one unreadable source and the digest must not vouch");
    }
}
