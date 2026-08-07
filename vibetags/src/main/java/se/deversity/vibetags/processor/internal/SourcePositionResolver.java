package se.deversity.vibetags.processor.internal;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.SourceLocation;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;

/**
 * Resolves an annotated {@link Element} to its source file and line range using the javac
 * Compiler Tree API ({@code com.sun.source}). The Tree API is only available when the
 * processor runs inside javac (Maven, Gradle, plain {@code javac}); under other compilers
 * (e.g. ECJ) {@link Trees#instance} throws and this resolver degrades to returning
 * {@code null} for every element — callers must treat positions as best-effort metadata.
 */
public final class SourcePositionResolver {

    private final @Nullable Trees trees;

    private SourcePositionResolver(@Nullable Trees trees) {
        this.trees = trees;
    }

    /**
     * Creates a resolver for {@code env}, or a no-op resolver when the compiler does not
     * expose the javac Tree API. Never throws.
     */
    public static SourcePositionResolver forEnv(ProcessingEnvironment env) {
        return new SourcePositionResolver(treesFor(env));
    }

    /**
     * The Tree API for {@code env}, unwrapping build-tool decorators, or {@code null} when no
     * compiler exposes it.
     *
     * <p>{@code Trees.instance} accepts only javac's own {@code ProcessingEnvironment}, and
     * Gradle's incremental annotation processing always hands the processor a wrapper — so
     * without unwrapping, every Gradle build silently lost the {@code .vibetags-locks} line
     * ranges (the same failure {@code ModuleRootResolver} documents for module identity, which
     * has a standard-API fallback; positions have none). The wrapper is unwrapped reflectively:
     * a field holding another {@code ProcessingEnvironment} (Gradle's
     * {@code IncrementalProcessingEnvironment.delegate}), then a {@code delegate()} accessor.
     * Every reflective step is best-effort — a sealed or unknown wrapper degrades to the old
     * no-position behaviour rather than throwing.
     */
    static @Nullable Trees treesFor(ProcessingEnvironment env) {
        ProcessingEnvironment current = env;
        for (int depth = 0; current != null && depth < 5; depth++) {
            try {
                return Trees.instance(current);
            } catch (RuntimeException | Error e) {
                // Not javac's own environment: ECJ, a mocked test environment, or a build-tool
                // wrapper. Try to unwrap one layer and probe again.
                current = delegateOf(current);
            }
        }
        return null;
    }

    /** The {@code ProcessingEnvironment} wrapped by {@code env}, or {@code null} when none is found. */
    private static @Nullable ProcessingEnvironment delegateOf(ProcessingEnvironment env) {
        for (Class<?> c = env.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!ProcessingEnvironment.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(env) instanceof ProcessingEnvironment inner && inner != env) {
                        return inner;
                    }
                } catch (ReflectiveOperationException | RuntimeException | Error ignored) {
                    // Inaccessible under this runtime (JPMS, records) — try the next candidate.
                }
            }
        }
        for (String accessor : new String[]{"delegate", "getDelegate"}) {
            try {
                java.lang.reflect.Method method = env.getClass().getMethod(accessor);
                if (ProcessingEnvironment.class.isAssignableFrom(method.getReturnType())
                        && method.invoke(env) instanceof ProcessingEnvironment inner && inner != env) {
                    return inner;
                }
            } catch (ReflectiveOperationException | RuntimeException | Error ignored) {
                // No such accessor — try the next name.
            }
        }
        return null;
    }

    /** A resolver that always returns {@code null} — for tests and non-javac environments. */
    public static SourcePositionResolver noop() {
        return new SourcePositionResolver(null);
    }

    /**
     * Returns the source position of {@code element}'s declaration (annotations and modifiers
     * included in the range), or {@code null} when it cannot be determined.
     */
    public @Nullable SourceLocation resolve(Element element) {
        if (trees == null) return null;
        try {
            TreePath path = trees.getPath(element);
            if (path == null) return null;
            CompilationUnitTree unit = path.getCompilationUnit();
            SourcePositions positions = trees.getSourcePositions();
            long start = positions.getStartPosition(unit, path.getLeaf());
            if (start < 0) return null;
            long end = positions.getEndPosition(unit, path.getLeaf());
            LineMap lineMap = unit.getLineMap();
            long startLine = lineMap.getLineNumber(start);
            long endLine = end >= 0 ? lineMap.getLineNumber(end) : startLine;
            String file = sourceFilePath(unit);
            if (file == null) return null;
            return new SourceLocation(file, startLine, endLine);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Normalises the compilation unit's source URI to a forward-slash path string. */
    private static @Nullable String sourceFilePath(CompilationUnitTree unit) {
        java.net.URI uri = unit.getSourceFile().toUri();
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = uri.getSchemeSpecificPart();
        }
        if (path == null || path.isBlank()) return null;
        // Windows file URIs look like "/C:/dev/project/Foo.java" — drop the leading slash.
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        return path.replace('\\', '/');
    }
}
