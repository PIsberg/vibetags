package se.deversity.vibetags.processor.internal;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.model.TransitiveRule;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Consuming half of transitive guardrails: finds the manifests of dependency JARs and reads them
 * back into {@link TransitiveRule}s.
 *
 * <h2>Why discovery is driven by imports</h2>
 *
 * <p>There is no supported way for an annotation processor to <em>enumerate</em> what is on the
 * compile classpath. {@code ClassLoader.getResources} sees the processor path, not the classpath,
 * and returns nothing under the {@code -processorpath} setup VibeTags documents. Listing through
 * javac's own file manager works, but only behind
 * {@code --add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED}, which is not
 * something a library may demand of every consumer's build.
 *
 * <p>{@link Filer#getResource} on {@link StandardLocation#CLASS_PATH} <em>does</em> work, with the
 * processor on the processor path, for any resource whose directory is a valid Java package name.
 * It just needs the exact name up front. So the manifest is keyed by the package it governs, and
 * the keys are derived from the packages this compilation actually imports.
 *
 * <p>Three things fall out of that, all of which the original design had to arrange separately.
 * A package the project never imports is never looked up, so the volume of inherited text is bounded
 * by what the project actually uses. A library cannot publish a rule under another library's key,
 * because the key is a filename inside its own JAR. And there is no scan, so the cost is one lookup
 * per distinct imported package prefix rather than a walk of every JAR.
 *
 * <h2>When the Tree API is missing</h2>
 *
 * <p>kapt, ECJ and Gradle's isolated compiler workers may expose no {@link Trees}. Discovery then
 * finds nothing, which is reported as unchecked rather than as a failure, and the build can supply
 * keys explicitly with {@code -Avibetags.manifest.packages} or a pre-extracted directory with
 * {@code -Avibetags.manifest.dir}. The same fallback covers JPMS builds, where dependencies are on
 * the module path and {@code CLASS_PATH} does not see them.
 */
public final class TransitiveManifestReader {

    /** Marker file at the consumer root that opts a project into reading dependency manifests. */
    public static final String MARKER_FILE = ".vibetags-transitive";

    /**
     * Package prefixes never probed. The JDK and the JVM languages' own runtimes ship no VibeTags
     * manifests, and a wide import of {@code java.util} would otherwise cost a lookup per build for
     * a result that is always a miss.
     */
    private static final List<String> SKIPPED_PREFIXES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
        "kotlin.", "scala.", "groovy.", "clojure.");

    /**
     * Ceiling on distinct lookups per compilation. A lookup is cheap, but "cheap times unbounded"
     * is how a processor becomes the slowest thing in someone's build. Reaching the cap is logged
     * rather than swallowed, because a silent cap reads exactly like full coverage.
     */
    static final int MAX_LOOKUPS = 4096;

    /** Fewest segments a candidate key may have: {@code com.foo}, never bare {@code com}. */
    private static final int MIN_SEGMENTS = 2;

    private final Set<String> probed = new LinkedHashSet<>();
    private final Set<TransitiveRule> found = new LinkedHashSet<>();
    private final @Nullable Logger log;

    private boolean treesUnavailable;
    private @Nullable String treesUnavailableReason;
    private boolean lookupCapHit;

    public TransitiveManifestReader(@Nullable Logger log) {
        this.log = log;
    }

    /** True when {@code root} carries the opt-in marker. */
    public static boolean optedIn(Path root) {
        return Files.isRegularFile(root.resolve(MARKER_FILE));
    }

    /** Every rule discovered so far, deduplicated. An unmodifiable snapshot. */
    public Set<TransitiveRule> rules() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /** The number of distinct classpath lookups performed. */
    public int lookupCount() {
        return probed.size();
    }

    /** True when the compiler exposed no Tree API, so imports could not be read. */
    public boolean treesUnavailable() {
        return treesUnavailable;
    }

    /** Why the Tree API was unavailable, or {@code null} when it was available. */
    public @Nullable String treesUnavailableReason() {
        return treesUnavailableReason;
    }

    /** True when {@link #MAX_LOOKUPS} was reached and some candidates went unprobed. */
    public boolean lookupCapHit() {
        return lookupCapHit;
    }

    /**
     * Reads this round's imports and resolves any manifests they point at.
     *
     * <p>Must run while the round is live: the Tree API cannot map an element back to its
     * compilation unit once processing is over.
     */
    public void scanRound(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        Set<String> candidates;
        try {
            candidates = candidatesFrom(env, roundEnv);
        } catch (Throwable t) {
            // Trees unavailable (kapt, ECJ, an isolated Gradle worker) or threw. Inherited rules
            // going undiscovered must never become a build that cannot run.
            treesUnavailable = true;
            String message = t.getMessage();
            treesUnavailableReason = (message == null || message.isBlank())
                ? t.getClass().getSimpleName() : message;
            if (log != null) {
                log.debug("transitive.skip reason=trees-unavailable detail={}", treesUnavailableReason);
            }
            return;
        }
        resolve(env.getFiler(), candidates);
    }

    /**
     * Resolves an explicit key list, for {@code -Avibetags.manifest.packages} and for callers that
     * know their dependency packages without a Tree API.
     */
    public void resolveExplicit(Filer filer, Collection<String> packageNames) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String name : packageNames) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty()) {
                candidates.add(trimmed);
            }
        }
        resolve(filer, candidates);
    }

    /**
     * Reads every {@code *.json} in {@code dir} as a manifest.
     *
     * <p>This is the escape hatch for a build tool that resolved the dependency graph itself, and
     * the only path that works when the compiler exposes no Tree API or the dependencies live on
     * the module path. Unlike classpath discovery it can see two manifests for the same package, so
     * it is also the only place a split-package collision is detectable at all.
     *
     * @return the names of files that could not be read as manifests
     */
    public List<String> resolveDirectory(Path dir) {
        List<String> rejected = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return rejected;
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> fileNameOf(p).endsWith(TransitiveManifest.RESOURCE_SUFFIX))
                  .forEach(files::add);
        } catch (IOException e) {
            rejected.add(dir + " (" + e.getMessage() + ")");
            return rejected;
        }
        // Sorted so a directory listing's order, which the filesystem does not guarantee, cannot
        // reach the output.
        files.sort(java.util.Comparator.comparing(TransitiveManifestReader::fileNameOf));
        for (Path file : files) {
            String name = fileNameOf(file);
            String stem = name.substring(0, name.length() - TransitiveManifest.RESOURCE_SUFFIX.length());
            try {
                accept(stem, Files.readString(file, StandardCharsets.UTF_8), file.toString());
            } catch (IOException | RuntimeException e) {
                rejected.add(name + " (" + e.getMessage() + ")");
            }
        }
        return rejected;
    }

    // ------------------------------------------------------------------------------------------

    private void resolve(Filer filer, Set<String> candidates) {
        for (String candidate : candidates) {
            if (probed.size() >= MAX_LOOKUPS) {
                if (!lookupCapHit) {
                    lookupCapHit = true;
                    if (log != null) {
                        log.warn("transitive.skip reason=lookup-cap cap={} remaining-unprobed=true", MAX_LOOKUPS);
                    }
                }
                return;
            }
            if (!probed.add(candidate)) {
                continue;
            }
            String json = read(filer, candidate);
            if (json == null) {
                continue;
            }
            try {
                accept(candidate, json, TransitiveManifest.RESOURCE_PACKAGE + "/" + candidate);
            } catch (RuntimeException e) {
                if (log != null) {
                    log.warn("transitive.skip package={} reason=unparseable detail={}", candidate, e.getMessage());
                }
            }
        }
    }

    private void accept(String packageName, String json, String source) {
        List<TransitiveRule> rules = TransitiveManifest.parse(json, packageName);
        found.addAll(rules);
        if (log != null && log.isDebugEnabled()) {
            log.debug("transitive.read package={} rules={} source={}", packageName, rules.size(), source);
        }
    }

    /**
     * One classpath lookup. A miss is the expected outcome for almost every candidate, so it is
     * neither logged at INFO nor surfaced to the user.
     */
    private @Nullable String read(Filer filer, String packageName) {
        try {
            FileObject fo = filer.getResource(StandardLocation.CLASS_PATH,
                TransitiveManifest.RESOURCE_PACKAGE, TransitiveManifest.resourceNameFor(packageName));
            try (InputStream in = fo.openInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            // FileNotFoundException for the overwhelming majority; FilerException for a name javac
            // will not accept; IllegalArgumentException for a location this compiler does not
            // support. All mean "no manifest here".
            return null;
        }
    }

    /**
     * The candidate manifest keys implied by this round's imports: for every import, each package
     * prefix of at least {@link #MIN_SEGMENTS} segments.
     *
     * <p>Prefixes rather than the exact import package, because a library may govern a whole
     * subtree from one {@code package-info.java}: an import of {@code com.acme.crypto.api.Cipher}
     * must find a manifest published for {@code com.acme.crypto}.
     */
    static Set<String> candidatesFrom(ProcessingEnvironment env, RoundEnvironment roundEnv) {
        Trees trees = Trees.instance(env);
        List<ImportedName> imports = new ArrayList<>();
        Set<CompilationUnitTree> seen = new LinkedHashSet<>();
        for (Element root : roundEnv.getRootElements()) {
            TreePath path = trees.getPath(root);
            if (path == null) {
                continue;
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            if (unit == null || !seen.add(unit)) {
                continue;
            }
            for (ImportTree imp : unit.getImports()) {
                if (imp.getQualifiedIdentifier() == null) {
                    continue;
                }
                imports.add(ImportedName.of(imp.getQualifiedIdentifier().toString(), imp.isStatic()));
            }
        }
        return candidatesFromImports(imports);
    }

    /**
     * One import statement, reduced to what the candidate walk needs.
     *
     * <p>The two flags are what tells a package apart from a type. Discarding them and walking the
     * raw identifier means every ordinary {@code import a.b.C} also probes {@code a.b.C} — a name
     * that can never host a manifest, because manifests are keyed by the package they govern. That
     * is one guaranteed-miss lookup per import in the project, which is bounded and harmless but
     * also entirely avoidable.
     */
    record ImportedName(String identifier, boolean isStatic, boolean isWildcard) {

        /** Parses one import's qualified identifier, stripping any trailing {@code .*}. */
        static ImportedName of(String qualifiedIdentifier, boolean isStatic) {
            String identifier = qualifiedIdentifier.strip();
            boolean wildcard = identifier.endsWith(".*");
            if (wildcard) {
                identifier = identifier.substring(0, identifier.length() - 2);
            }
            return new ImportedName(identifier, isStatic, wildcard);
        }

        /** A plain type import, for tests and callers with no {@code ImportTree} to hand. */
        static ImportedName of(String qualifiedIdentifier) {
            return of(qualifiedIdentifier, false);
        }

        /**
         * How many trailing segments name something other than a package.
         *
         * <p>{@code import a.b.*} is already a package. {@code import a.b.C} ends in a type.
         * {@code import static a.b.C.*} ends in a type. {@code import static a.b.C.m} ends in a
         * type and a member.
         */
        int nonPackageSegments() {
            if (isWildcard) {
                return isStatic ? 1 : 0;
            }
            return isStatic ? 2 : 1;
        }
    }

    /** The prefix expansion of {@code imports}, in a stable (sorted) order. */
    static Set<String> candidatesFromImports(Collection<ImportedName> imports) {
        Set<String> out = new TreeSet<>();
        for (ImportedName imported : imports) {
            String identifier = imported.identifier();
            if (identifier.isEmpty() || isSkipped(identifier)) {
                continue;
            }
            String[] segments = identifier.split("\\.");
            int limit = segments.length - imported.nonPackageSegments();
            StringBuilder prefix = new StringBuilder(identifier.length());
            for (int i = 0; i < limit; i++) {
                if (segments[i].isEmpty()) {
                    break;
                }
                if (i > 0) {
                    prefix.append('.');
                }
                prefix.append(segments[i]);
                if (i + 1 >= MIN_SEGMENTS) {
                    out.add(prefix.toString());
                }
            }
        }
        return out;
    }

    /** Convenience for tests and callers holding plain type-import strings. */
    static Set<String> candidatesFromTypeImports(Collection<String> imports) {
        List<ImportedName> parsed = new ArrayList<>(imports.size());
        for (String raw : imports) {
            parsed.add(ImportedName.of(raw));
        }
        return candidatesFromImports(parsed);
    }

    /**
     * A path's file name, or {@code ""} when it has none.
     *
     * <p>{@code Path.getFileName()} returns null for a root path. Nothing in a directory listing
     * is a root, but the compiler cannot know that and neither can a reader — naming the case once
     * is cheaper than three unexplained dereferences.
     */
    private static String fileNameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static boolean isSkipped(String identifier) {
        for (String prefix : SKIPPED_PREFIXES) {
            if (identifier.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
