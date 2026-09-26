package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>The source files come from {@link RoundSources}, which the early exit and the partial-round
 * ledger read too (#857). It asks {@code Elements.getFileObjectOf} before the Tree API, and that
 * order is what keeps a wrapped environment identifiable: Gradle wraps the environment for
 * incremental annotation processing, the Tree API only accepts javac's own class, and when this
 * resolver depended on it every module under Gradle collapsed onto one content-hash identity and
 * appended a duplicate region instead of replacing its own
 * (<a href="https://github.com/PIsberg/vibetags/issues/331">issue #331</a>).
 *
 * <p>Under a compiler that offers neither lookup (or with in-memory sources) this returns
 * {@code null}, and callers fall back to the working directory as before.
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
    public static @Nullable ModuleIdentity fromRound(RoundSources sources) {
        Path moduleRoot = null;
        // Sorted so a round that somehow mixes source sets picks the same one on every build.
        SortedSet<String> sourceSets = new TreeSet<>();
        // Every class in a package shares its source directory, and the walk below costs up to
        // three stats per ancestor level. Asked once per directory rather than once per class.
        // Local to this call: the build files it looks for are the consumer's, which no VibeTags
        // process writes, so the answer cannot change while one round is being read.
        Map<Path, Optional<Path>> rootBySourceDir = new HashMap<>();
        for (Element element : sources.roots()) {
            Path file = sources.fileOf(element);
            Path sourceDir = file != null ? file.getParent() : null;
            if (sourceDir == null) continue;
            Path candidate = rootBySourceDir
                .computeIfAbsent(sourceDir, dir -> Optional.ofNullable(nearestBuildFileAncestor(dir)))
                .orElse(null);
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
        // A round that saw main and a test source set at once: pickSourceSet prefers main, so
        // nothing downstream would otherwise know the test half was here and went unrouted.
        boolean mixed = sourceSets.contains(ModuleIdentity.MAIN)
            && sourceSets.stream().anyMatch(ModuleIdentity::isTestSourceSetName);
        return new ModuleIdentity(moduleRoot, pickSourceSet(sourceSets), mixed);
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
            if (hasBuildFile(current)) {
                return current;
            }
        }
        return null;
    }

    /** Whether {@code dir} carries a build file, identifying it as a module root. */
    public static boolean hasBuildFile(@Nullable Path dir) {
        if (dir == null) {
            return false;
        }
        for (String buildFile : BUILD_FILES) {
            if (Files.isRegularFile(dir.resolve(buildFile))) {
                return true;
            }
        }
        return false;
    }
}
