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
    private final Map<Element, String> nameByElement = new IdentityHashMap<>();
    private final Set<Path> files = new LinkedHashSet<>();
    private boolean everyRootOnDisk = true;
    private @Nullable Trees trees;
    private boolean treesAsked;

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
     * The name of the file {@code element} was declared in, extension included ({@code Ledger.java},
     * {@code Billing.kt} under the KSP front end), or {@code null} when no API can say. Unlike
     * {@link #fileOf} it answers for an in-memory source too: the name is all a glob needs (#939).
     */
    public @Nullable String fileNameOf(Element element) {
        resolve();
        return nameByElement.get(element);
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
        elements = elementUtilsOf(env);
        for (Element element : roundEnv.getRootElements()) {
            URI uri = viaStandardApi(element);
            record(element, uri != null ? uri : viaTrees(trees(), element));
        }
    }

    /** The compiler's {@link Elements}, or {@code null} when this environment will not give one. */
    private static @Nullable Elements elementUtilsOf(ProcessingEnvironment env) {
        try {
            return env.getElementUtils();
        } catch (RuntimeException | Error unavailable) {
            return null;
        }
    }

    /** What {@link Elements#getFileObjectOf} names, or {@code null} when it cannot answer. */
    private @Nullable URI viaStandardApi(Element element) {
        if (elements == null) {
            return null;
        }
        try {
            JavaFileObject object = elements.getFileObjectOf(element);
            return object == null ? null : object.toUri();
        } catch (RuntimeException | Error unavailable) {
            return null; // an older or other compiler: the Tree API is asked instead
        }
    }

    /**
     * The Tree API, looked up on first need only: unwrapping a decorated environment is reflective
     * and would otherwise be paid by every round of every build, though the standard call answers.
     */
    private @Nullable Trees trees() {
        if (!treesAsked) {
            trees = SourcePositionResolver.treesFor(env);
            treesAsked = true;
        }
        return trees;
    }

    /** Files {@code element} under the file and the name {@code uri} gives, or under none. */
    private void record(Element element, @Nullable URI uri) {
        Path file = uri == null ? null : onDisk(uri);
        String name = uri == null ? null : lastSegment(uri);
        if (name != null) {
            nameByElement.put(element, name);
        }
        fileByElement.put(element, file);
        if (file == null) {
            everyRootOnDisk = false;
        } else {
            files.add(file);
        }
    }

    private static @Nullable URI viaTrees(@Nullable Trees trees, Element element) {
        if (trees == null) {
            return null;
        }
        try {
            TreePath path = trees.getPath(element);
            return path == null ? null : path.getCompilationUnit().getSourceFile().toUri();
        } catch (RuntimeException unavailable) {
            return null; // malformed URI or unexpected tree state
        }
    }

    /** The last segment of {@code uri}'s path, for any scheme, or {@code null} when it has none. */
    private static @Nullable String lastSegment(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            path = uri.getSchemeSpecificPart();
        }
        if (path == null) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? null : name;
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
