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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One live round's root elements, each mapped to the source file it was declared in, resolved once
 * and shared by everything that needs the mapping (#857).
 *
 * <p>Three things read it in the first round: module identity ({@link ModuleRootResolver}), the
 * early exit's key ({@link SourceDigest}) and the partial-round ledger ({@link
 * PartialRoundDetector}). Each used to resolve every root element itself, and a lookup allocates a
 * URI and a {@code Path}, so a cold build paid for the mapping three times over. Resolution is lazy
 * and happens at most once per round, so a round that needs none of the three pays nothing.
 *
 * <p><strong>Resolution order.</strong> {@link Elements#getFileObjectOf} first, the Tree API
 * second. The standard call answers from the class symbol and survives a build tool's wrapped
 * {@code ProcessingEnvironment}, which the Tree API does not; the Tree API builds a path per
 * element and was measured as most of what the digest allocated in #834. Module identity used the
 * opposite order, and for a source on disk the two name the same file for every kind of root
 * element javac hands a processor ({@code RoundSourcesTest} holds that), so the order is a cost
 * decision, not a semantic one. The Tree API is still asked when the standard call cannot answer:
 * a compiler that predates or does not implement it.
 *
 * <p>Must be read while the round is live, for the reason every such lookup must: once processing
 * is over an element can no longer be mapped back to its compilation unit.
 */
public final class RoundSources {

    private final ProcessingEnvironment env;
    private final RoundEnvironment roundEnv;

    private boolean resolved;
    private @Nullable Elements elements;
    private final Map<Element, @Nullable Path> fileByElement = new IdentityHashMap<>();
    private final Set<Path> files = new LinkedHashSet<>();
    private boolean everyRootOnDisk = true;

    private RoundSources(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        this.env = env;
        this.roundEnv = roundEnv;
    }

    /** The round's mapping, resolved on first use. */
    public static RoundSources of(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        return new RoundSources(env, roundEnv);
    }

    /** The round's root elements, in the order the round gave them. */
    public Set<? extends Element> roots() {
        return roundEnv.getRootElements();
    }

    /** The file {@code element} was declared in, or {@code null} for an in-memory source or when no API can say. */
    public @Nullable Path fileOf(Element element) {
        resolve();
        return fileByElement.get(element);
    }

    /**
     * Every distinct file behind the round's root elements, in root-element order, or {@code null}
     * when the round cannot be vouched for as a whole: it has no root elements, or one of them is
     * not a file on disk (an in-memory source cannot be read back next time).
     */
    public @Nullable List<Path> filesIfComplete() {
        resolve();
        return everyRootOnDisk && !files.isEmpty() ? new ArrayList<>(files) : null;
    }

    /** The compiler's {@link Elements}, or {@code null} when this environment will not give one. */
    public @Nullable Elements elements() {
        resolve();
        return elements;
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            elements = env.getElementUtils();
        } catch (RuntimeException | Error unavailable) {
            elements = null;
        }
        Set<? extends Element> roots = roundEnv.getRootElements();
        if (roots.isEmpty()) {
            return;
        }
        // Looked up only if the standard call cannot answer: unwrapping a decorated environment
        // is reflective and would otherwise be paid by every round of every build.
        Trees trees = null;
        boolean treesAsked = false;
        for (Element element : roots) {
            Path file = null;
            boolean answered = false;
            if (elements != null) {
                try {
                    JavaFileObject object = elements.getFileObjectOf(element);
                    if (object != null) {
                        file = onDisk(object.toUri());
                        answered = true;
                    }
                } catch (RuntimeException | Error unavailable) {
                    // An older or other compiler: fall back to the Tree API below.
                }
            }
            if (!answered) {
                if (!treesAsked) {
                    trees = SourcePositionResolver.treesFor(env);
                    treesAsked = true;
                }
                file = viaTrees(trees, element);
            }
            fileByElement.put(element, file);
            if (file == null) {
                everyRootOnDisk = false;
            } else {
                files.add(file);
            }
        }
    }

    private static @Nullable Path viaTrees(@Nullable Trees trees, Element element) {
        if (trees == null) {
            return null;
        }
        try {
            TreePath path = trees.getPath(element);
            return path == null ? null : onDisk(path.getCompilationUnit().getSourceFile().toUri());
        } catch (RuntimeException unavailable) {
            return null; // malformed URI or unexpected tree state
        }
    }

    /** A {@code file:} URI as an absolute, normalized path, or {@code null} for an in-memory source. */
    private static @Nullable Path onDisk(URI uri) {
        if (!"file".equals(uri.getScheme())) {
            return null;
        }
        try {
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (RuntimeException unusable) {
            return null;
        }
    }
}
