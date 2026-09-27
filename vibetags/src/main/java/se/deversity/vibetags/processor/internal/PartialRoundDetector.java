package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AILocked;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tells a compilation that was shown <em>all</em> of a module's sources from one that was shown
 * some of them.
 *
 * <p>The difference is the whole of a reported data-loss bug. Gradle's incremental
 * Java compilation hands javac only the sources it thinks changed. VibeTags is declared
 * {@code aggregating}, but Gradle can only find the files an aggregating processor cares about
 * through {@code CLASS}/{@code RUNTIME} annotations, and every VibeTags annotation is
 * {@code SOURCE} (invariant 5) — so Gradle recompiles the one edited file and nothing else.
 * The processor then saw one annotated element where the last build had twelve, wrote the
 * module's sidecar from that one, swept every scoped rule file it could not account for, and
 * rewrote {@code CLAUDE.md} with only the survivor. Twenty-two committed files were deleted and
 * twenty-seven lines cut, exit code 0, nothing on the console.
 *
 * <p>The judgement here is deliberately <em>evidence</em>, not arithmetic — the same rule issue
 * #383 settled for a cold reactor, applied inside one module. Two independent facts have to hold
 * before a round is called partial:
 *
 * <ol>
 *   <li><b>Something recorded is missing.</b> A sidecar describing this compilation's own source
 *       tree names elements this round did not produce. On its own this proves nothing: deleting
 *       an annotation looks exactly the same.</li>
 *   <li><b>An unread source explains it.</b> Under a source root this compilation actually
 *       compiled from, there is a {@code .java} file it did <em>not</em> compile which names the
 *       VibeTags annotation package. That file's guardrails cannot be in this round's output for
 *       one reason only: the round never read it.</li>
 * </ol>
 *
 * <p>Each condition alone has a false positive the other removes. A source excluded from
 * compilation but carrying annotations satisfies (2) on every build, and would stop VibeTags
 * writing for good — but it contributes no element, so (1) never fires for it. An annotation
 * genuinely deleted, or a whole annotated class deleted, satisfies (1) — but the file was
 * compiled, or is gone from disk, so (2) does not hold and the ordinary sweep still removes its
 * rules. That last case is the one worth protecting: a guard that refused every removal would
 * trade silent deletion for guardrails that can never be retired.
 *
 * <p>Nothing is persisted and no format changes: (1) reads sidecars that already record element
 * ids, and (2) reads the working tree. A build with in-memory sources (a JSR 199 string, most of
 * this repository's own tests) resolves no source root at all and is therefore never partial,
 * which is also what keeps the cost at zero on the healthy path — the walk in (2) only runs once
 * (1) has already found something missing.
 */
@AICore(
    sensitivity = "high",
    note = "Both conditions in unreadAnnotatedSources are load-bearing and neither may be dropped as redundant: without the missing-element check an excluded-but-annotated source stops VibeTags writing for good, and without the unread-source check a genuinely deleted annotation can never have its rule file retired"
)
public final class PartialRoundDetector {

    /**
     * The import every annotated Java source must contain, whether it imports the annotations by
     * name, by wildcard, or writes them fully qualified. There is no fourth spelling: Java has no
     * way to name a type outside the current package without the package name appearing in the
     * file, so this is a decision, not a heuristic.
     */
    static final String ANNOTATION_PACKAGE = AILocked.class.getPackageName();

    /** How many unread sources a report names before it stops looking. */
    private static final int MAX_REPORTED = 8;

    /** Safety bound on the walk, so a source root resolved wrongly cannot walk a whole disk. */
    private static final int MAX_FILES_SCANNED = 50_000;

    /** Safety bound on the walk's depth, for the same reason. */
    private static final int MAX_WALK_DEPTH = 32;

    /**
     * Path segment naming a build's own generated-source trees: Gradle's {@code build/generated/...}
     * is this exactly, Maven's {@code target/generated-sources} and {@code generated-test-sources}
     * begin with it and a dash. Never walked: those files are outputs, they are not committed, and
     * another processor's leftovers from an earlier run are not evidence about what this one was
     * shown. Matching Gradle's name alone left Maven's walked, and a stale generated file there
     * refused every round after a real annotation removal until a clean build (#866).
     */
    private static final String GENERATED_SEGMENT = "generated";

    private final Set<Path> compiledFiles = new LinkedHashSet<>();
    private final Set<Path> sourceRoots = new LinkedHashSet<>();

    /**
     * Records the source files this round compiled, and the source roots they came from.
     *
     * <p>Must be called while the round is live, for the reason every Tree API call in this
     * processor must be: once processing is over an element can no longer be mapped back to its
     * compilation unit.
     */
    public void observe(RoundSources sources) {
        Elements elements = sources.elements();
        for (Element element : sources.roots()) {
            Path file = sources.fileOf(element);
            if (file == null) {
                continue; // in-memory source, or a compiler that exposes neither API
            }
            compiledFiles.add(file);
            Path sourceRoot = sourceRootOf(file, packageNameOf(elements, element));
            if (sourceRoot != null) {
                sourceRoots.add(sourceRoot);
            }
        }
    }

    /**
     * The annotated sources this compilation never read, or empty when this round saw everything
     * its module has — which includes every build that compiles from memory, and every build whose
     * module has no record to be measured against yet.
     *
     * @param root             the VibeTags root, where the sidecars live
     * @param elementIdsSeen   the element ids this compilation produced
     * @param sourceSet        the source set this compilation compiles ({@code main}, {@code test}, ...)
     * @param moduleRoot       the compiling module's root directory; the VibeTags root for one module
     */
    public List<Path> unreadAnnotatedSources(Path root, Set<String> elementIdsSeen, String sourceSet,
                                             Path moduleRoot) {
        sourceRoots.removeIf(sourceRoot -> isGeneratedTree(root, sourceRoot));
        if (sourceRoots.isEmpty()) {
            return List.of();
        }
        if (!anyRecordedElementMissing(root, elementIdsSeen, sourceSet, moduleRoot)) {
            return List.of(); // condition (1): nothing went missing, so nothing needs explaining
        }
        return unreadSourcesNamingTheAnnotations();
    }

    /**
     * Condition (1): does a sidecar describing this compilation's own source tree name an element
     * this round did not produce?
     *
     * <p>"Describing this compilation's own source tree" is containment, not identity. The module
     * id a round computes can drift — a compilation that cannot resolve its module falls back to
     * the working directory and files itself under the root identity — and a guard that only ever
     * compared against its own id would be stepped around by exactly the builds that need it. Any
     * sidecar whose module directory contains one of this round's source roots is describing this
     * code; a reactor sibling's directory contains none of them and is therefore never consulted.
     *
     * <p>Containment says nothing about the source set, though: the test sidecar of a module has
     * the same module directory as its main one, and a main round never produces test elements.
     * Counting them as missing made (1) hold on every main round once the tests had compiled, and
     * an annotated main source excluded from the build then stopped main writing for good. A
     * sidecar of another source set is skipped; the source set is read off the sidecar's own ids,
     * which do not drift the way the module id can.
     */
    private boolean anyRecordedElementMissing(Path root, Set<String> elementIdsSeen, String sourceSet,
                                              Path moduleRoot) {
        for (ModuleSidecar sidecar : ModuleSidecar.peekAll(root)) {
            if (!describesThisCompilation(root, sidecar, moduleRoot)
                    || !ModuleSidecar.sanitizeId(sourceSet).equals(sourceSetOf(sidecar))) {
                continue;
            }
            for (String recorded : sidecar.getElementIds()) {
                if (!elementIdsSeen.contains(recorded)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The source set {@code sidecar} records: {@code main} when its id is its region's, otherwise
     * what follows the separator ({@code core__test} in region {@code core} is {@code test}).
     */
    private static String sourceSetOf(ModuleSidecar sidecar) {
        String prefix = sidecar.getRegionId() + ModuleSidecar.SOURCE_SET_SEPARATOR;
        String id = sidecar.getModuleId();
        return id.startsWith(prefix) ? id.substring(prefix.length()) : ModuleIdentity.MAIN;
    }

    /**
     * True when {@code sidecar}'s module directory contains one of this round's source roots and is
     * not a module above this one. The reactor root's directory contains every module's sources, so
     * a root that compiles sources of its own recorded elements no module round produces, and
     * containment alone made (1) hold for every module while that sidecar existed: one annotated
     * source kept out of any module's build then refused all of that module's rounds. A directory
     * strictly above the compiling module's own root is a parent module, not a record of this one.
     * The module's own directory, and any below it, still count, which is what keeps the drifted
     * identity case in the class comment covered.
     */
    private boolean describesThisCompilation(Path root, ModuleSidecar sidecar, Path moduleRoot) {
        String modulePath = sidecar.getModulePath();
        Path moduleDir;
        try {
            moduleDir = ("_root_".equals(modulePath) || modulePath.isEmpty())
                ? root.toAbsolutePath().normalize()
                : root.resolve(modulePath).toAbsolutePath().normalize();
        } catch (RuntimeException notAPath) {
            return false;
        }
        Path module = moduleRoot.toAbsolutePath().normalize();
        if (module.startsWith(moduleDir) && !module.equals(moduleDir)) {
            return false;
        }
        for (Path sourceRoot : sourceRoots) {
            if (sourceRoot.startsWith(moduleDir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Condition (2): {@code .java} files under a compiled source root that this round did not
     * compile and that name the VibeTags annotation package.
     */
    private List<Path> unreadSourcesNamingTheAnnotations() {
        Set<Path> unread = new LinkedHashSet<>();
        int scanned = 0;
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> walk = Files.walk(sourceRoot, MAX_WALK_DEPTH)) {
                for (Path candidate : (Iterable<Path>) walk::iterator) {
                    if (++scanned > MAX_FILES_SCANNED) {
                        return List.copyOf(unread);
                    }
                    if (!candidate.toString().endsWith(".java")) {
                        continue;
                    }
                    Path file = candidate.toAbsolutePath().normalize();
                    if (compiledFiles.contains(file) || !namesTheAnnotations(file)) {
                        continue;
                    }
                    unread.add(file);
                    if (unread.size() >= MAX_REPORTED) {
                        return List.copyOf(unread);
                    }
                }
            } catch (IOException | RuntimeException unwalkable) {
                // A source root this build cannot list tells us nothing either way; the other
                // roots still can, and a guard that threw here would fail somebody's compile.
            }
        }
        return List.copyOf(unread);
    }

    /**
     * Whether {@code file} mentions the annotation package.
     *
     * <p>Read as ISO-8859-1 rather than UTF-8 on purpose: the marker is ASCII, that decoding
     * cannot throw on any byte sequence, and a source file in an encoding this JVM's default
     * charset rejects must not turn into an exception inside a consumer's compilation.
     */
    private static boolean namesTheAnnotations(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(ANNOTATION_PACKAGE)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false; // a file we cannot read is not evidence of anything
        }
        return false;
    }

    /**
     * The source root {@code file} sits in: its directory with the package's directories removed.
     * {@code null} when the layout does not mirror the package, which is the one case where
     * walking would look somewhere this compilation never read from.
     */
    private static @Nullable Path sourceRootOf(Path file, @Nullable String packageName) {
        Path dir = file.getParent();
        if (dir == null || packageName == null) {
            return null;
        }
        if (packageName.isEmpty()) {
            return dir;
        }
        Path current = dir;
        List<String> segments = new ArrayList<>(List.of(packageName.split("\\.")));
        for (int i = segments.size() - 1; i >= 0; i--) {
            Path name = current.getFileName();
            if (name == null || !name.toString().equals(segments.get(i))) {
                return null; // directory layout does not mirror the package
            }
            current = current.getParent();
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** {@code element}'s package name, or {@code null} when the compiler will not say. */
    private static @Nullable String packageNameOf(@Nullable Elements elements, Element element) {
        if (element instanceof PackageElement pkg) {
            return pkg.getQualifiedName().toString();
        }
        if (elements == null) {
            return null;
        }
        try {
            PackageElement pkg = elements.getPackageOf(element);
            return pkg != null ? pkg.getQualifiedName().toString() : null;
        } catch (RuntimeException | Error unavailable) {
            return null;
        }
    }

    /**
     * True when {@code sourceRoot} is a generated-source tree. Only the part below the VibeTags root
     * is looked at: the directories a checkout happens to sit in ({@code /ci/generated/proj}) say
     * nothing about the tree, and matching them left a whole project with no source root, which
     * reads as a complete round and let an incremental one delete the rule files it was never
     * shown. A root outside the VibeTags root has no such part and is judged on its whole path.
     */
    private static boolean isGeneratedTree(Path root, Path sourceRoot) {
        Path base = root.toAbsolutePath().normalize();
        Path tree = sourceRoot.toAbsolutePath().normalize();
        return containsGeneratedSegment(tree.startsWith(base) ? base.relativize(tree) : tree);
    }

    /** True when a segment of {@code path} is {@link #GENERATED_SEGMENT}, alone or before a dash. */
    private static boolean containsGeneratedSegment(Path path) {
        for (Path name : path) {
            String segment = name.toString();
            if (segment.equals(GENERATED_SEGMENT) || segment.startsWith(GENERATED_SEGMENT + "-")) {
                return true;
            }
        }
        return false;
    }
}
