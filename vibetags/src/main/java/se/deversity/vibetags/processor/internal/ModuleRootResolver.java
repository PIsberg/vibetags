package se.deversity.vibetags.processor.internal;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Resolves the identity — module root directory plus source set — of the compilation unit
 * currently being processed.
 *
 * <p>Historically the processor used {@code Paths.get("")} (the JVM working directory) as the
 * module identity for multi-module sidecar aggregation. That is wrong for reactor builds: Maven
 * and Gradle compile every module <em>in-process</em>, so the working directory is the reactor
 * root for all of them. Every module then computed the same {@code _root_} identity, overwrote
 * the same sidecar file, and the shared guardrail files degraded to last-writer-wins
 * (<a href="https://github.com/PIsberg/vibetags/issues/278">issue #278</a>).
 *
 * <p>Instead, this resolver walks up from the source file of a root element in a live processing
 * round to the nearest directory containing a build file ({@code pom.xml}, {@code build.gradle},
 * {@code build.gradle.kts}) — the module root — and reads the source set out of the same path.
 *
 * <p><strong>Two ways to reach the source file, on purpose.</strong> The javac Compiler Tree API
 * ({@link Trees#instance}) only accepts javac's own {@code ProcessingEnvironment} and throws for
 * anything else. Gradle wraps the environment for incremental annotation processing — VibeTags
 * declares itself {@code aggregating} in {@code META-INF/gradle/incremental.annotation.processors},
 * so under Gradle the Tree API is <em>always</em> unavailable and this resolver used to return
 * {@code null} for every module. The caller then fell back to the working directory, which under
 * Gradle is neither the module nor the reactor root, so every module collapsed onto one
 * content-hash identity and appended a duplicate region instead of replacing its own
 * (<a href="https://github.com/PIsberg/vibetags/issues/331">issue #331</a>). {@link
 * Elements#getFileObjectOf(Element)} (Java 18+) answers the same question through the standard
 * API, survives wrapping, and is therefore tried whenever the Tree API is absent or silent.
 *
 * <p>Both paths degrade gracefully: under a compiler that offers neither (or with in-memory
 * sources), this returns {@code null} and callers fall back to the working directory as before.
 */
public final class ModuleRootResolver {

    /** Marker files whose presence identifies a directory as a module root. */
    private static final List<String> BUILD_FILES =
        List.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** Conventional source-root directory name; the segment after it names the source set. */
    private static final String SRC_DIR = "src";

    /** Safety bound for the upward walk (a build's directory depth never comes close). */
    private static final int MAX_WALK_UP = 64;

    private ModuleRootResolver() {
    }

    /**
     * Attempts to resolve the module identity from the root elements of a live processing round.
     * Returns {@code null} when it cannot be determined (no compiler API exposes the source file,
     * in-memory sources, no build file in the source file's ancestry) — the caller should fall
     * back to the JVM working directory.
     */
    public static @Nullable ModuleIdentity fromRound(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        Trees trees;
        try {
            trees = Trees.instance(env);
        } catch (RuntimeException | Error e) {
            // Not javac's own environment: ECJ, a mocked test environment, or — the common case —
            // Gradle's incremental-processing wrapper. getFileObjectOf() below covers it.
            trees = null;
        }
        Elements elements;
        try {
            elements = env.getElementUtils();
        } catch (RuntimeException | Error e) {
            elements = null;
        }

        Path moduleRoot = null;
        // Sorted so a round that somehow mixes source sets picks the same one on every build.
        SortedSet<String> sourceSets = new TreeSet<>();
        for (Element element : roundEnv.getRootElements()) {
            Path sourceDir = sourceDirOf(trees, elements, element);
            if (sourceDir == null) continue;
            Path candidate = nearestBuildFileAncestor(sourceDir);
            if (candidate == null) continue;
            if (moduleRoot == null) {
                moduleRoot = candidate;
            }
            if (moduleRoot.equals(candidate)) {
                String sourceSet = sourceSetOf(moduleRoot, sourceDir);
                if (sourceSet != null) sourceSets.add(sourceSet);
            }
        }
        if (moduleRoot == null) return null;
        return new ModuleIdentity(moduleRoot, pickSourceSet(sourceSets));
    }

    /**
     * Directory holding {@code element}'s source file, or {@code null} when no available compiler
     * API can say. Tries the Tree API first (it is the cheaper lookup when javac hands us its own
     * environment), then the standard {@link Elements#getFileObjectOf}.
     */
    private static @Nullable Path sourceDirOf(@Nullable Trees trees, @Nullable Elements elements, Element element) {
        if (trees != null) {
            try {
                TreePath path = trees.getPath(element);
                if (path != null) {
                    Path dir = directoryOf(path.getCompilationUnit().getSourceFile().toUri());
                    if (dir != null) return dir;
                }
            } catch (RuntimeException ignored) {
                // Malformed URI or unexpected tree state — fall through to the Elements path.
            }
        }
        if (elements != null) {
            try {
                JavaFileObject file = elements.getFileObjectOf(element);
                if (file != null) {
                    return directoryOf(file.toUri());
                }
            } catch (RuntimeException | Error ignored) {
                // Older/alternative compilers may not implement it — treat as unavailable.
            }
        }
        return null;
    }

    /** Parent directory of a {@code file:} URI, or {@code null} for in-memory sources. */
    private static @Nullable Path directoryOf(URI uri) {
        if (!"file".equals(uri.getScheme())) return null; // in-memory source (tests, JSR 199 strings)
        try {
            return Paths.get(uri).toAbsolutePath().normalize().getParent();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Names the source set a compiled file belongs to by reading the segment after {@code src/} in
     * its path relative to the module root: {@code src/main/java/...} → {@code "main"},
     * {@code src/test/java/...} → {@code "test"}, {@code src/integrationTest/java/...} →
     * {@code "integrationTest"}. Returns {@code null} for anything that does not follow the
     * convention (generated sources under {@code target/} or {@code build/}, for instance).
     */
    static @Nullable String sourceSetOf(Path moduleRoot, Path sourceDir) {
        Path rel;
        try {
            rel = moduleRoot.relativize(sourceDir);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            if (SRC_DIR.equals(rel.getName(i).toString())) {
                String name = rel.getName(i + 1).toString();
                return name.isBlank() ? null : name;
            }
        }
        return null;
    }

    /**
     * Collapses the source sets seen this round to one. {@code main} wins when present — a round
     * that compiles main sources is the module's primary contribution regardless of what else it
     * swept up — and an unrecognisable layout is treated as {@code main} so projects that do not
     * follow the {@code src/<sourceSet>} convention keep exactly the previous behaviour.
     */
    private static String pickSourceSet(SortedSet<String> sourceSets) {
        if (sourceSets.isEmpty() || sourceSets.contains(ModuleIdentity.MAIN)) {
            return ModuleIdentity.MAIN;
        }
        return sourceSets.first();
    }

    /**
     * Walks up from {@code dir} (inclusive) and returns the first directory containing a build
     * file, or {@code null} if none is found before the filesystem root.
     */
    static @Nullable Path nearestBuildFileAncestor(@Nullable Path dir) {
        Path current = dir;
        for (int i = 0; current != null && i < MAX_WALK_UP; i++, current = current.getParent()) {
            for (String buildFile : BUILD_FILES) {
                if (Files.isRegularFile(current.resolve(buildFile))) {
                    return current;
                }
            }
        }
        return null;
    }
}
