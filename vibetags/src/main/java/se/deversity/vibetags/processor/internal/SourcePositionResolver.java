package se.deversity.vibetags.processor.internal;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;

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

    /** Source location of an element declaration: file path plus 1-based inclusive line range. */
    public record Position(String file, long startLine, long endLine) {}

    private final @Nullable Trees trees;

    private SourcePositionResolver(@Nullable Trees trees) {
        this.trees = trees;
    }

    /**
     * Creates a resolver for {@code env}, or a no-op resolver when the compiler does not
     * expose the javac Tree API. Never throws.
     */
    public static SourcePositionResolver forEnv(ProcessingEnvironment env) {
        try {
            return new SourcePositionResolver(Trees.instance(env));
        } catch (RuntimeException | Error e) {
            // Not javac (ECJ, mocked test environments, ...) — positions unavailable.
            return new SourcePositionResolver(null);
        }
    }

    /** A resolver that always returns {@code null} — for tests and non-javac environments. */
    public static SourcePositionResolver noop() {
        return new SourcePositionResolver(null);
    }

    /**
     * Returns the source position of {@code element}'s declaration (annotations and modifiers
     * included in the range), or {@code null} when it cannot be determined.
     */
    public @Nullable Position resolve(Element element) {
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
            return new Position(file, startLine, endLine);
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
