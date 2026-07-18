package se.deversity.vibetags.processor.internal;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Resolves the root directory of the Maven/Gradle module currently being compiled.
 *
 * <p>Historically the processor used {@code Paths.get("")} (the JVM working directory) as the
 * module identity for multi-module sidecar aggregation. That is wrong for reactor builds: Maven
 * and Gradle compile every module <em>in-process</em>, so the working directory is the reactor
 * root for all of them. Every module then computed the same {@code _root_} identity, overwrote
 * the same sidecar file, and the shared guardrail files degraded to last-writer-wins
 * (<a href="https://github.com/PIsberg/vibetags/issues/278">issue #278</a>).
 *
 * <p>Instead, this resolver walks up from the source file of any root element in a live
 * processing round (via the javac Compiler Tree API) to the nearest directory containing a build
 * file ({@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts}) — the module root.
 * Like {@link SourcePositionResolver}, it degrades gracefully under non-javac compilers or
 * in-memory compilation: callers fall back to the working directory, i.e. the previous behavior.
 */
public final class ModuleRootResolver {

    /** Marker files whose presence identifies a directory as a module root. */
    private static final List<String> BUILD_FILES =
        List.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** Safety bound for the upward walk (a build's directory depth never comes close). */
    private static final int MAX_WALK_UP = 64;

    private ModuleRootResolver() {
    }

    /**
     * Attempts to resolve the module root from the root elements of a live processing round.
     * Returns {@code null} when it cannot be determined (non-javac compiler, in-memory sources,
     * no build file in the source file's ancestry) — the caller should fall back to the JVM
     * working directory.
     */
    public static @Nullable Path fromRound(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        Trees trees;
        try {
            trees = Trees.instance(env);
        } catch (RuntimeException | Error e) {
            return null; // Not javac (ECJ, mocked test environments, ...)
        }
        for (Element element : roundEnv.getRootElements()) {
            try {
                TreePath path = trees.getPath(element);
                if (path == null) continue;
                URI uri = path.getCompilationUnit().getSourceFile().toUri();
                if (!"file".equals(uri.getScheme())) continue; // in-memory source (tests)
                Path sourceDir = Paths.get(uri).toAbsolutePath().normalize().getParent();
                Path moduleRoot = nearestBuildFileAncestor(sourceDir);
                if (moduleRoot != null) return moduleRoot;
            } catch (RuntimeException e) {
                // Malformed URI or unexpected tree state — try the next root element.
            }
        }
        return null;
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
